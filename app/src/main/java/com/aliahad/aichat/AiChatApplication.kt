package com.aliahad.aichat

import android.app.Application
import android.util.Log
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.data.ChatRepository
import com.aliahad.aichat.data.RoomChatRepository
import com.aliahad.aichat.attachment.AttachmentRepository
import com.aliahad.aichat.attachment.DefaultAttachmentRepository
import com.aliahad.aichat.activity.ActivityRepository
import com.aliahad.aichat.activity.RoomActivityRepository
import com.aliahad.aichat.activity.OfficeWorkScheduler
import com.aliahad.aichat.activity.PhoneSourceAccessManager
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
import com.aliahad.aichat.memory.MemoryIndexer
import com.aliahad.aichat.memory.MemoryRepository
import com.aliahad.aichat.memory.PromptContextPlanner
import com.aliahad.aichat.memory.RoomMemoryRepository
import com.aliahad.aichat.context.ContextProfileRepository
import com.aliahad.aichat.context.RoomContextProfileRepository
import com.aliahad.aichat.residency.ModelResidencyController
import com.aliahad.aichat.settings.AppSettingsRepository
import com.aliahad.aichat.settings.TokenCipher
import com.aliahad.aichat.skill.RoomSkillRepository
import com.aliahad.aichat.skill.SkillRepository
import com.aliahad.aichat.brief.HealthConnectDataSource
import com.aliahad.aichat.brief.HealthDataSource
import com.aliahad.aichat.diagnostics.DiagnosticsReportBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.aliahad.aichat.context.DeviceContextIdentity
import com.aliahad.aichat.BuildConfig

class AiChatApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() == "$packageName:vulkan") return
        container = AppContainer(this)
        OfficeWorkScheduler.schedule(this)
        applicationScope.launch {
            try {
                OfficeWorkScheduler.verifyAndRepair(this@AiChatApplication)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("AiChatApplication", "Periodic work verification failed", error)
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

class AppContainer(val application: Application) {
    val database: AppDatabase = AppDatabase.create(application)
    val settings = AppSettingsRepository(application, TokenCipher(application))
    val chatRepository: ChatRepository = RoomChatRepository(database)
    val runtimeOperationGate = RuntimeOperationGate()
    val memoryIndexer: MemoryIndexer = AppSearchMemoryIndexer(application)
    val memoryRepository: RoomMemoryRepository = RoomMemoryRepository(
        database,
        memoryIndexer,
    )
    val skillRepository: SkillRepository = RoomSkillRepository(database)
    val contextProfileRepository: ContextProfileRepository =
        RoomContextProfileRepository(application, database.modelContextProfileDao(), settings)
    val healthDataSource: HealthDataSource = HealthConnectDataSource(application)
    val phoneSourceAccessManager = PhoneSourceAccessManager(application, healthDataSource)
    val activityRepository: ActivityRepository = RoomActivityRepository(database)
    val conversationSummaryRepository = ConversationSummaryRepository(database)
    val attachmentRepository: AttachmentRepository = DefaultAttachmentRepository(application, database)
    val modelBenchmarkRepository: ModelBenchmarkRepository =
        RoomModelBenchmarkRepository(application, database.modelBenchmarkDao())
    private val cpuInferenceEngine: InferenceEngine = NativeInferenceEngine(application, com.aliahad.aichat.core.BackendMode.CPU)
    private val vulkanInferenceEngine: InferenceEngine = VulkanInferenceClient(application)
    val inferenceEngine: InferenceEngine = RecoveringInferenceEngine(
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
            ::residencyController.isInitialized && residencyController.isInteractiveUseActive
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
    val diagnosticsReportBuilder = DiagnosticsReportBuilder(application, this)
    lateinit var promptContextPlanner: PromptContextPlanner
        private set
    lateinit var residencyController: ModelResidencyController
        private set
    lateinit var conversationSummarizer: BackgroundConversationSummarizer
        private set
    lateinit var inferenceBenchmarkRunner: InferenceBenchmarkRunner
        private set
    val modelRepository: ModelRepository = DefaultModelRepository(
        context = application,
        dao = database.modelDao(),
        projectorDao = database.projectorDao(),
        settings = settings,
        isPathInUse = { path ->
            if (::residencyController.isInitialized) {
                residencyController.isPathInUse(path)
            } else {
                inferenceEngine.loadedModelPath == path
            }
        },
    )
    val officeBackupRepository: OfficeBackupRepository = EncryptedOfficeBackupRepository(
        context = application,
        database = database,
        settings = settings,
    )

    init {
        promptContextPlanner = PromptContextPlanner(
            inferenceEngine = inferenceEngine,
            memoryRepository = memoryRepository,
            summaries = conversationSummaryRepository,
        )
        residencyController = ModelResidencyController(
            context = application,
            inferenceEngine = inferenceEngine,
            modelRepository = modelRepository,
            attachmentRepository = attachmentRepository,
            contextProfiles = contextProfileRepository,
            settingsRepository = settings,
        )
        conversationSummarizer = BackgroundConversationSummarizer(
            inferenceEngine = inferenceEngine,
            residencyState = residencyController.state,
            interactiveUseActive = { residencyController.isInteractiveUseActive },
        )
        // Interactive turns cancel-and-join any in-flight background summarization
        // so a rolling summary can never delay a chat turn behind the runtime gate
        // or interleave with the turn's session restore.
        residencyController.registerBackgroundWorkCancellation {
            conversationSummarizer.cancelAndJoin()
        }
        inferenceBenchmarkRunner = DefaultInferenceBenchmarkRunner(
            context = application,
            modelRepository = modelRepository,
            inferenceEngine = inferenceEngine,
            residencyController = residencyController,
            settings = settings,
            benchmarks = modelBenchmarkRepository,
        )
    }

}
