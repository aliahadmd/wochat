package com.aliahad.aichat

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.StrictMode
import android.util.Log
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.data.DatabaseLockedException
import com.aliahad.aichat.data.isDeviceCurrentlyLocked
import com.aliahad.aichat.data.ChatRepository
import com.aliahad.aichat.data.RoomChatRepository
import com.aliahad.aichat.attachment.AttachmentRepository
import com.aliahad.aichat.attachment.DefaultAttachmentRepository
import com.aliahad.aichat.memory.MemoryWorkScheduler
import com.aliahad.aichat.backup.EncryptedOfficeBackupRepository
import com.aliahad.aichat.backup.OfficeBackupRepository
import com.aliahad.aichat.inference.InferenceEngine
import com.aliahad.aichat.inference.NativeInferenceEngine
import com.aliahad.aichat.inference.BackendRecoveryPolicy
import com.aliahad.aichat.inference.RecoveringInferenceEngine
import com.aliahad.aichat.inference.remote.VulkanInferenceClient
import com.aliahad.aichat.inference.DefaultInferenceBenchmarkRunner
import com.aliahad.aichat.inference.InferenceBenchmarkRunner
import com.aliahad.aichat.inference.ModelBenchmarkRepository
import com.aliahad.aichat.inference.RoomModelBenchmarkRepository
import com.aliahad.aichat.inference.RuntimeOperationGate
import com.aliahad.aichat.model.DefaultModelRepository
import com.aliahad.aichat.model.ModelRepository
import com.aliahad.aichat.memory.AppSearchMemoryIndexer
import com.aliahad.aichat.memory.BackgroundConversationSummarizer
import com.aliahad.aichat.memory.ConversationSummaryRepository
import com.aliahad.aichat.memory.MemoryEmbedder
import com.aliahad.aichat.memory.MemoryIndexer
import com.aliahad.aichat.memory.MemoryRepository
import com.aliahad.aichat.memory.PromptContextPlanner
import com.aliahad.aichat.memory.RoomMemoryRepository
import com.aliahad.aichat.context.ContextProfileRepository
import com.aliahad.aichat.context.RoomContextProfileRepository
import com.aliahad.aichat.residency.ModelResidencyController
import com.aliahad.aichat.ui.viewmodel.ChatTurnRunner
import com.aliahad.aichat.ui.viewmodel.UiMessageManager
import com.aliahad.aichat.settings.AppSettingsRepository
import com.aliahad.aichat.settings.TokenCipher
import com.aliahad.aichat.skill.RoomSkillRepository
import com.aliahad.aichat.skill.SkillRepository
import com.aliahad.aichat.diagnostics.DiagnosticsReportBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.aliahad.aichat.context.DeviceContextIdentity
import com.aliahad.aichat.BuildConfig

class AiChatApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var container: AppContainer
        private set

    private val _containerWarm = MutableStateFlow(false)

    /**
     * False until [AppContainer.warmUp] has finished on a background thread.
     *
     * The UI must not touch container-backed ViewModels before this turns true:
     * doing so forces the lazy database open onto the main thread, which is the
     * stall this whole arrangement exists to avoid.
     */
    val containerWarm: StateFlow<Boolean> = _containerWarm.asStateFlow()

    private val _startupBlockedByLock = MutableStateFlow(false)

    /**
     * True when startup could not proceed because the device is locked.
     *
     * The database key is wrapped by a Keystore key created with
     * `setUnlockedDeviceRequired(true)`, so nothing that touches the database
     * can run behind the keyguard. The UI uses this to explain the wait rather
     * than sit on a splash screen forever.
     */
    val startupBlockedByLock: StateFlow<Boolean> = _startupBlockedByLock.asStateFlow()

    /** Serialises startup so the unlock retry cannot race the initial attempt. */
    private val startupMutex = Mutex()

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // The user just dismissed the keyguard, so the wrapping key is usable.
            startStartupSequence()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Installed before the process check on purpose, so the :vulkan service
        // process is covered too — it loads native code and is exactly the kind
        // of place a stray main-thread read would hide.
        installStrictModeInDebug()
        if (Application.getProcessName() == "$packageName:vulkan") return
        // Cheap: every expensive member of AppContainer is lazy.
        container = AppContainer(this)
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startStartupSequence()
    }

    private fun startStartupSequence() {
        applicationScope.launch { runStartupSequence() }
    }

    private suspend fun runStartupSequence() = startupMutex.withLock {
        if (_containerWarm.value) return@withLock
        if (isDeviceCurrentlyLocked()) {
            // Deliberately do not touch the database: the Keystore call would
            // throw and, uncaught on a worker thread, take the process down.
            Log.i(TAG, "Startup deferred: device is locked; waiting for ACTION_USER_PRESENT")
            _startupBlockedByLock.value = true
            return@withLock
        }

        // Opening the SQLCipher database (key derivation + Room migrations)
        // and loading the native inference library used to run on the main
        // thread, stalling the first frame. Warm them here instead, so
        // whichever consumer touches them first finds them ready.
        val warmUp = applicationScope.async {
            try {
                container.warmUp()
                true
            } catch (locked: DatabaseLockedException) {
                // Raced the keyguard between the check above and the key access.
                Log.i(TAG, "Runtime warm-up deferred: device locked", locked)
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // A non-lock failure still lets the UI through so it can surface
                // the problem rather than hiding behind the splash.
                Log.e(TAG, "Startup reconciliation failed: runtime warmup", error)
                true
            }
        }
        // The UI is gated on containerWarm, so this release must be bounded.
        // warmUp() is blocking and cannot be cancelled, so the deadline lets
        // the UI through rather than interrupting the work: a slow warm-up
        // costs a brief stall on first use, a stuck one would otherwise mean
        // a splash screen that never goes away.
        val release = applicationScope.launch {
            val warmed = withTimeoutOrNull(WARM_UP_RELEASE_TIMEOUT_MILLIS) { warmUp.await() }
            if (warmed == null) {
                Log.e(
                    TAG,
                    "Runtime warm-up still running after " +
                        "$WARM_UP_RELEASE_TIMEOUT_MILLIS ms; releasing the UI anyway",
                )
            }
            if (warmed != false) _containerWarm.value = true
        }
        val warmedSuccessfully = warmUp.await()
        release.join()
        if (!warmedSuccessfully) {
            _startupBlockedByLock.value = true
            return@withLock
        }
        _startupBlockedByLock.value = false

        // WorkManager scheduling touches disk, so it stays off the main thread too.
        reconcileStartupStep("work scheduling") {
            MemoryWorkScheduler.schedule(this@AiChatApplication)
        }
        try {
            MemoryWorkScheduler.verifyAndRepair(this@AiChatApplication)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Periodic work verification failed", error)
        }
        reconcileStartupStep("interrupted work") {
            container.chatRepository.markInterruptedMessages()
            container.attachmentRepository.markInterrupted()
            container.attachmentRepository.cleanupAbandonedDrafts()
        }
        reconcileStartupStep("model catalog") {
            container.modelRepository.ensureOfficialRecords()
        }
        reconcileStartupStep("memory index") {
            container.memoryIndexer.rebuild(container.memoryRepository.memories.first())
            container.memoryRepository.purgeStaleIndexDocs()
        }
    }

    private companion object {
        /**
         * How long the splash may wait on warm-up before the UI is let through
         * anyway. Long enough for a cold SQLCipher open plus the native library
         * load, short enough that a stuck warm-up cannot strand the user.
         */
        const val WARM_UP_RELEASE_TIMEOUT_MILLIS = 8_000L
        const val TAG = "AiChatApplication"
    }

    /**
     * Debug-only self-reporting for main-thread disk I/O, network and leaked
     * resources.
     *
     * This exists because nothing in the project could previously observe such
     * defects: CI compiles the app and runs JVM tests but never launches it, so
     * the main-thread database open that plan 016 fixed survived a full audit
     * and fifteen shipped plans unnoticed. With this in place a regression
     * announces itself in logcat on the next debug launch.
     *
     * `penaltyLog()` only — deliberately not `penaltyDeath()`. Known violations
     * still exist, and crashing debug builds would block other work. Escalating
     * is a separate decision to make once the baseline is clean.
     */
    private fun installStrictModeInDebug() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }

    /**
     * The only place the model's ~4.9 GB of file-backed pages are given back.
     *
     * They used to be dropped after every restore and every generation, which meant
     * each turn refaulted the whole mapping from storage while prefilling — 730
     * prompt tokens took 33.4 s at 21.8 tok/s. Now the kernel has to actually ask.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_RUNNING_LOW) return
        if (!containerWarm.value) return
        runCatching { container.inferenceEngine.releaseResidentPages() }
            .onFailure { Log.w("AiChatApplication", "Could not release model pages", it) }
    }

    private suspend fun reconcileStartupStep(
        label: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e("AiChatApplication", "Startup reconciliation failed: $label", error)
        }
    }

}

/**
 * Application-wide singletons.
 *
 * Every expensive member is `by lazy` on purpose. Constructing the container
 * must stay cheap because it happens on the main thread in
 * [AiChatApplication.onCreate], while opening the SQLCipher database (key
 * derivation plus Room migrations) and loading the native inference library
 * are slow enough to stall the first frame. [AiChatApplication] warms the
 * expensive ones on a background dispatcher immediately after construction.
 *
 * Do not turn any of these back into eager initializers.
 */
class AppContainer(val application: Application) {
    val database: AppDatabase by lazy { AppDatabase.create(application) }
    val settings by lazy { AppSettingsRepository(application, TokenCipher(application)) }
    val chatRepository: ChatRepository by lazy { RoomChatRepository(database) }
    val runtimeOperationGate = RuntimeOperationGate()
    val memoryIndexer: MemoryIndexer by lazy { AppSearchMemoryIndexer(application) }
    val memoryRepository: RoomMemoryRepository by lazy {
        RoomMemoryRepository(
            database,
            memoryIndexer,
            // Lambda, not a captured reference: the embedder may not be downloaded or
            // loaded yet, and every caller already treats null as "lexical only".
            MemoryEmbedder { text -> inferenceEngine.embed(text) },
        )
    }
    val skillRepository: SkillRepository by lazy { RoomSkillRepository(database) }
    val contextProfileRepository: ContextProfileRepository by lazy {
        RoomContextProfileRepository(application, database.modelContextProfileDao(), settings)
    }
    val conversationSummaryRepository by lazy { ConversationSummaryRepository(database) }
    val attachmentRepository: AttachmentRepository by lazy {
        DefaultAttachmentRepository(application, database)
    }
    val modelBenchmarkRepository: ModelBenchmarkRepository by lazy {
        RoomModelBenchmarkRepository(application, database.modelBenchmarkDao())
    }
    private val cpuInferenceEngine: InferenceEngine by lazy {
        NativeInferenceEngine(application, com.aliahad.aichat.core.BackendMode.CPU)
    }
    private val vulkanInferenceEngine: InferenceEngine by lazy { VulkanInferenceClient(application) }
    val inferenceEngine: InferenceEngine by lazy {
        RecoveringInferenceEngine(
        cpu = cpuInferenceEngine,
        vulkan = vulkanInferenceEngine,
        recoveryPolicy = BackendRecoveryPolicy(
            settings = settings,
            benchmarks = modelBenchmarkRepository,
            deviceFingerprint = DeviceContextIdentity.read(application).key,
            runtimeRevision = BuildConfig.LLAMA_RUNTIME_REVISION,
        ),
        operationGate = runtimeOperationGate,
        utilityUseBarrier = {
            residencyControllerLazy.isInitialized() && residencyController.isInteractiveUseActive
        },
        cpuFallbackConfigurationResolver = { original ->
            val model = modelRepository.models.first().firstOrNull { candidate ->
                candidate.id == original.modelId ||
                    (original.modelSha256 != null && candidate.sha256 == original.modelSha256)
            }
            val profile = model?.let { selected ->
                contextProfileRepository.resolve(
                    selected,
                    com.aliahad.aichat.core.BackendMode.CPU,
                )
            }
            val declared = profile?.declaredContextTokens
                ?.takeIf { it > 0 }
                ?: original.declaredContextTokens
            val verified = maxOf(
                profile?.verifiedContextTokens ?: 0,
                RoomContextProfileRepository.SAFE_CONTEXT_TOKENS,
            )
            original.copy(
                contextTokens = if (declared > 0) verified.coerceAtMost(declared) else verified,
                declaredContextTokens = declared,
                backend = com.aliahad.aichat.core.BackendMode.CPU,
            )
        },
        )
    }
    val diagnosticsReportBuilder by lazy { DiagnosticsReportBuilder(application, this) }
    val promptContextPlanner: PromptContextPlanner by lazy {
        PromptContextPlanner(
            inferenceEngine = inferenceEngine,
            memoryRepository = memoryRepository,
            summaries = conversationSummaryRepository,
        )
    }

    // Held as an explicit Lazy so collaborators can ask whether the controller
    // exists yet without forcing it into existence — the barrier below and
    // modelRepository's isPathInUse both need "if it was built, ask it".
    private val residencyControllerLazy = lazy {
        ModelResidencyController(
            context = application,
            inferenceEngine = inferenceEngine,
            modelRepository = modelRepository,
            attachmentRepository = attachmentRepository,
            contextProfiles = contextProfileRepository,
            settingsRepository = settings,
        ).also { controller ->
            // Interactive turns cancel-and-join any in-flight background summarization
            // so a rolling summary can never delay a chat turn behind the runtime gate
            // or interleave with the turn's session restore. The lambda body is
            // deferred, so referring to conversationSummarizer here does not force it.
            controller.registerBackgroundWorkCancellation {
                conversationSummarizer.cancelAndJoin()
            }
        }
    }
    val residencyController: ModelResidencyController by residencyControllerLazy

    /**
     * Snackbar/message queue. Container-scoped rather than per-Activity so a message
     * raised by a turn still running in the background is not lost with the UI.
     */
    val uiMessages by lazy { UiMessageManager() }

    /**
     * Scope for chat turns.
     *
     * Deliberately NOT viewModelScope. A turn used to die the moment the Activity
     * went away — the ViewModel's onCleared even cancelled inference explicitly — so
     * leaving the app mid-answer threw away the rest of it. Turns now run on the
     * process, and the UI attaches to and detaches from them.
     */
    private val turnScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Single process-wide runner. There is one inference engine, so there must be
     * exactly one thing driving it: a per-Activity runner meant a relaunched UI had
     * no idea a turn was in flight and would start a second one on the same engine.
     */
    val chatTurnRunner: ChatTurnRunner by lazy {
        ChatTurnRunner(
            chatRepository = chatRepository,
            attachmentRepository = attachmentRepository,
            skillRepository = skillRepository,
            memoryRepository = memoryRepository,
            modelRepository = modelRepository,
            promptContextPlanner = promptContextPlanner,
            residencyController = residencyController,
            inferenceEngine = inferenceEngine,
            settingsRepository = settings,
            messages = uiMessages,
            scope = turnScope,
        )
    }

    val conversationSummarizer: BackgroundConversationSummarizer by lazy {
        BackgroundConversationSummarizer(
            inferenceEngine = inferenceEngine,
            residencyState = residencyController.state,
            interactiveUseActive = { residencyController.isInteractiveUseActive },
        )
    }
    val inferenceBenchmarkRunner: InferenceBenchmarkRunner by lazy {
        DefaultInferenceBenchmarkRunner(
            context = application,
            modelRepository = modelRepository,
            inferenceEngine = inferenceEngine,
            residencyController = residencyController,
            settings = settings,
            benchmarks = modelBenchmarkRepository,
        )
    }
    val modelRepository: ModelRepository by lazy {
        DefaultModelRepository(
            context = application,
            dao = database.modelDao(),
            projectorDao = database.projectorDao(),
            settings = settings,
            isPathInUse = { path ->
                if (residencyControllerLazy.isInitialized()) {
                    residencyController.isPathInUse(path)
                } else {
                    inferenceEngine.loadedModelPath == path
                }
            },
        )
    }
    val officeBackupRepository: OfficeBackupRepository by lazy {
        EncryptedOfficeBackupRepository(
            context = application,
            database = database,
            settings = settings,
        )
    }

    /**
     * Forces the slow singletons into existence. Call this from a background
     * dispatcher only: it opens the encrypted database (running any pending Room
     * migrations) and loads the native inference library.
     */
    fun warmUp() {
        // Every lazy the ViewModel factory can reach must be listed here.
        // Warming only a subset does not help: whichever one is missed simply
        // resolves on the main thread when the factory builds a ViewModel.
        // StrictMode caught exactly that — inferenceBenchmarkRunner was omitted,
        // and DeviceContextIdentity.read() inside it cost a 64 ms main-thread
        // disk read on every launch.
        database
        settings
        chatRepository
        memoryIndexer
        memoryRepository
        skillRepository
        contextProfileRepository
        conversationSummaryRepository
        attachmentRepository
        modelBenchmarkRepository
        inferenceEngine
        modelRepository
        officeBackupRepository
        diagnosticsReportBuilder
        promptContextPlanner
        residencyController
        conversationSummarizer
        inferenceBenchmarkRunner
    }
}
