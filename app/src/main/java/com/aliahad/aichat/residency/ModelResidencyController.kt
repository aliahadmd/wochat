package com.aliahad.aichat.residency

import android.content.Context
import android.os.UserManager
import com.aliahad.aichat.data.isDeviceCurrentlyLocked
import android.util.Log
import com.aliahad.aichat.attachment.AttachmentRepository
import com.aliahad.aichat.context.ContextCandidates
import com.aliahad.aichat.context.ContextProfileRepository
import com.aliahad.aichat.context.ContextRuntimeMonitor
import com.aliahad.aichat.context.ContextVerifier
import com.aliahad.aichat.context.RoomContextProfileRepository
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.ContextVerificationState
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.ModelContextProfile
import com.aliahad.aichat.core.ModelLoadConfiguration
import com.aliahad.aichat.core.ModelRecord
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.MultimodalRequirement
import com.aliahad.aichat.inference.InferenceEngine
import com.aliahad.aichat.model.ModelRepository
import com.aliahad.aichat.settings.AppSettingsRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

sealed interface ModelResidencyState {
    data object Idle : ModelResidencyState
    data object WaitingForUnlock : ModelResidencyState
    data object WaitingForModel : ModelResidencyState
    data class Loading(val modelName: String) : ModelResidencyState
    data class Ready(
        val modelName: String,
        val contextSize: Int,
        val declaredContextSize: Int,
        val loadMillis: Long,
        val visionReady: Boolean = false,
        val audioReady: Boolean = false,
        val verifiedContextSize: Int = 0,
        val contextVerificationState: ContextVerificationState =
            ContextVerificationState.UNVERIFIED,
    ) : ModelResidencyState
    data class Error(val message: String) : ModelResidencyState
}

data class ModelLoadSignature(
    val path: String,
    val contextSize: Int,
    val temperature: Float,
    val backend: BackendMode = BackendMode.CPU,
    val projectorPath: String? = null,
    val imageTokenBudget: Int? = null,
)

class ModelResidencyController(
    context: Context,
    private val inferenceEngine: InferenceEngine,
    private val modelRepository: ModelRepository,
    private val attachmentRepository: AttachmentRepository,
    private val contextProfiles: ContextProfileRepository,
    private val settingsRepository: AppSettingsRepository,
) : ContextVerifier {
    private val appContext = context.applicationContext
    private val userManager = context.getSystemService(UserManager::class.java)

    /**
     * Whether the database-backed model catalog can actually be touched.
     *
     * `isUserUnlocked` alone is not enough: it is the Direct Boot signal and
     * stays true behind the keyguard once the user has unlocked since boot.
     * The database key is created with `setUnlockedDeviceRequired(true)`, so it
     * also needs the device to be unlocked *right now* — otherwise every path
     * below throws DatabaseLockedException from a background thread.
     */
    private val deviceUsable: Boolean
        get() = userManager.isUserUnlocked && !appContext.isDeviceCurrentlyLocked()
    private val runtimeMonitor = ContextRuntimeMonitor(context)
    private val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, error -> recordError(error) },
    )
    private val mutex = Mutex()
    private val inferenceUseCounter = InferenceUseCounter()
    private val _state = MutableStateFlow<ModelResidencyState>(ModelResidencyState.Idle)
    val state: StateFlow<ModelResidencyState> = _state.asStateFlow()

    @Volatile private var uiForeground = false
    @Volatile private var backgroundWorkCancellation: (suspend () -> Unit)? = null
    private var loadedSignature: ModelLoadSignature? = null
    private var verificationJob: Job? = null

    /**
     * True while at least one interactive inference flow (chat turn) is active.
     * Background utility generation checks this barrier before and after touching
     * the engine, and the engine rejects UTILITY-profile operations while it is
     * true, so utility work can never interleave with an interactive turn.
     */
    val isInteractiveUseActive: Boolean
        get() = inferenceUseCounter.count > 0

    /**
     * Registers the cancellation invoked when interactive inference starts. Best-effort
     * background generation (rolling summarization) uses it to yield the runtime gate
     * instead of delaying the user's turn. The lambda is suspend so the cancellation
     * can be awaited (cancel-and-join) before the interactive turn proceeds.
     */
    fun registerBackgroundWorkCancellation(cancellation: suspend () -> Unit) {
        backgroundWorkCancellation = cancellation
    }

    suspend fun preloadAfterUnlock() {
        if (!deviceUsable) {
            _state.value = ModelResidencyState.WaitingForUnlock
            return
        }
        ensureLoaded()
        scheduleVerification()
    }

    suspend fun ensureLoaded(
        requireVision: Boolean = false,
        imageTokenBudget: Int = 280,
    ): ModelLoadConfiguration = ensureLoaded(
        requirement = if (requireVision) MultimodalRequirement.VISION else MultimodalRequirement.NONE,
        imageTokenBudget = imageTokenBudget,
    )

    suspend fun ensureLoaded(
        requirement: MultimodalRequirement,
        imageTokenBudget: Int = 280,
    ): ModelLoadConfiguration = try {
        val configuration = mutex.withLock {
            ensureLoadedLocked(requirement, imageTokenBudget)
        }
        scheduleVerification()
        configuration
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        if (inferenceEngine.loadedModelPath == null) loadedSignature = null
        recordError(error)
        throw error
    }

    override fun setUiForeground(foreground: Boolean) {
        uiForeground = foreground
        if (foreground) {
            scheduleVerification()
        } else {
            verificationJob?.cancel()
        }
    }

    override suspend fun beginInferenceUse() {
        inferenceUseCounter.begin {
            verificationJob?.let { job ->
                job.cancel()
                job.cancelAndJoin()
            }
            // The use counter is already incremented, so isInteractiveUseActive is
            // true BEFORE this cancellation runs: background generation started
            // concurrently sees the barrier and aborts, and the awaitable join
            // guarantees any in-flight utility generation has fully released the
            // engine gate before the interactive turn proceeds.
            try {
                backgroundWorkCancellation?.invoke()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Best-effort: background work cancellation must never block an
                // interactive turn.
                Log.w(TAG, "Background work cancellation failed while starting inference use", error)
            }
        }
    }

    override fun endInferenceUse() {
        inferenceUseCounter.end()
        scheduleVerification()
    }

    override fun scheduleVerification() {
        if (!canStartVerification()) return
        if (verificationJob?.isActive == true) return
        verificationJob = scope.launch {
            try {
                delay(15_000)
                verifyProgressively()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                recordError(error)
            }
        }
    }

    override suspend fun reverifySelectedModel() {
        verificationJob?.let { job ->
            job.cancel()
            job.cancelAndJoin()
        }
        val model = selectedReadyModel() ?: return
        contextProfiles.resetVerification(model)
        scheduleVerification()
    }

    suspend fun reloadForConfigurationChange() {
        ensureLoaded()
    }

    suspend fun unload() = mutex.withLock {
        inferenceEngine.cancel()
        if (inferenceEngine.loadedModelPath != null) inferenceEngine.unload()
        loadedSignature = null
        _state.value = ModelResidencyState.Idle
    }

    fun isPathInUse(path: String): Boolean =
        loadedSignature?.path == path ||
            loadedSignature?.projectorPath == path ||
            inferenceEngine.loadedModelPath == path ||
            inferenceEngine.loadedProjectorPath == path


    /**
     * Loads the sentence embedder if it has been downloaded.
     *
     * Best-effort by design: semantic recall is an enhancement, and a missing or
     * broken embedder must leave retrieval on its lexical path rather than stop the
     * user chatting. Attempted on every residency check rather than only after a
     * chat-model load: the 318 MB download usually finishes while the chat model is
     * already resident, and tying it to a load meant it sat unused until the next
     * app restart. Cheap to repeat — it returns immediately once loaded.
     */
    private suspend fun ensureEmbedderLoaded() {
        if (inferenceEngine.embeddingDimensions > 0) return
        val record = runCatching { modelRepository.embeddingModel.first() }
            .onFailure { Log.w(TAG, "Could not read the embedding model record", it) }
            .getOrNull()
        if (record == null) {
            Log.i(TAG, "Semantic recall off: no embedding model record")
            return
        }
        val path = record.localPath
        if (path == null || !java.io.File(path).exists()) {
            Log.i(TAG, "Semantic recall off: not downloaded (status=${record.status}, path=$path)")
            return
        }
        runCatching { inferenceEngine.loadEmbedder(path) }
            .onSuccess { Log.i(TAG, "Semantic recall on: embedder loaded from $path") }
            .onFailure { Log.w(TAG, "Embedder unavailable; memory stays lexical", it) }
    }

    private suspend fun ensureLoadedLocked(
        requirement: MultimodalRequirement,
        imageTokenBudget: Int,
    ): ModelLoadConfiguration {
        val unresolved = selectedOrReadyModel()
        if (unresolved?.localPath == null) {
            _state.value = ModelResidencyState.WaitingForModel
            return ModelLoadConfiguration(
                contextTokens = RoomContextProfileRepository.SAFE_CONTEXT_TOKENS,
                declaredContextTokens = 0,
                temperature = settingsRepository.generationSettings.first().temperature,
            )
        }
        val model = modelRepository.ensureSha256(unresolved)
        val modelPath = requireNotNull(model.localPath)
        var profile = contextProfiles.resolve(model)
        var resolvedContext = maxOf(
            profile.verifiedContextTokens,
            RoomContextProfileRepository.SAFE_CONTEXT_TOKENS,
        ).let { value ->
            profile.declaredContextTokens.takeIf { it > 0 }?.let { value.coerceAtMost(it) } ?: value
        }
        val generation = settingsRepository.generationSettings.first().normalized()
        val projector = if (requirement != MultimodalRequirement.NONE) {
            requireNotNull(modelRepository.projectorForModel(model.id)) {
                "This model has no configured multimedia projector."
            }.also {
                require(it.status == DownloadStatus.READY && it.localPath != null) {
                    "Download ${it.displayName} before sending image or audio attachments."
                }
            }
        } else {
            null
        }
        val previous = loadedSignature
        val baseMatches = previous?.matchesLoadedBase(
            expectedPath = modelPath,
            expectedContextSize = resolvedContext,
            expectedTemperature = generation.temperature,
            expectedBackend = profile.backend,
            actualBackend = (inferenceEngine.state.value as? InferenceState.Ready)?.backend,
            loadedPath = inferenceEngine.loadedModelPath,
            activeContextSize = inferenceEngine.activeContextSize,
        ) == true
        val retainedProjectorPath = if (requirement == MultimodalRequirement.NONE && baseMatches) {
            previous.projectorPath
        } else {
            projector?.localPath
        }
        val retainedImageBudget = if (requirement == MultimodalRequirement.NONE && baseMatches) {
            previous.imageTokenBudget
        } else {
            projector?.let { imageTokenBudget }
        }
        var signature = ModelLoadSignature(
            path = modelPath,
            contextSize = resolvedContext,
            temperature = generation.temperature,
            backend = profile.backend,
            projectorPath = retainedProjectorPath,
            imageTokenBudget = retainedImageBudget,
        )
        val mark = TimeSource.Monotonic.markNow()
        if (!baseMatches) {
            _state.value = ModelResidencyState.Loading(model.displayName)
            if (inferenceEngine.loadedModelPath != null) inferenceEngine.unload()
            inferenceEngine.loadModel(
                path = signature.path,
                displayName = model.displayName,
                configuration = ModelLoadConfiguration(
                    contextTokens = resolvedContext,
                    declaredContextTokens = profile.declaredContextTokens,
                    backend = profile.backend,
                    temperature = generation.temperature,
                    modelId = model.id,
                    modelSha256 = model.sha256,
                ),
            )
            loadedSignature = signature.copy(projectorPath = null, imageTokenBudget = null)
            val actualBackend = (inferenceEngine.state.value as? InferenceState.Ready)?.backend
                ?: profile.backend
            if (actualBackend != signature.backend) {
                profile = contextProfiles.resolve(model)
                check(profile.backend == actualBackend) {
                    "Backend recovery selected $actualBackend but settings resolved ${profile.backend}."
                }
                resolvedContext = maxOf(
                    profile.verifiedContextTokens,
                    RoomContextProfileRepository.SAFE_CONTEXT_TOKENS,
                ).let { value ->
                    profile.declaredContextTokens.takeIf { it > 0 }
                        ?.let { value.coerceAtMost(it) }
                        ?: value
                }
                if (inferenceEngine.activeContextSize != resolvedContext) {
                    inferenceEngine.unload()
                    inferenceEngine.loadModel(
                        path = modelPath,
                        displayName = model.displayName,
                        configuration = ModelLoadConfiguration(
                            contextTokens = resolvedContext,
                            declaredContextTokens = profile.declaredContextTokens,
                            backend = actualBackend,
                            temperature = generation.temperature,
                            modelId = model.id,
                            modelSha256 = model.sha256,
                        ),
                    )
                    check((inferenceEngine.state.value as? InferenceState.Ready)?.backend == actualBackend) {
                        "CPU recovery could not reload the matching context profile."
                    }
                }
                signature = signature.copy(
                    backend = actualBackend,
                    contextSize = inferenceEngine.activeContextSize,
                )
                loadedSignature = signature.copy(projectorPath = null, imageTokenBudget = null)
                if (settingsRepository.backendMode.first() == BackendMode.AUTO) {
                    settingsRepository.setChosenAutoBackend(actualBackend)
                }
            }
            profile = contextProfiles.recordDeclared(profile, inferenceEngine.modelContextLimit)
            if (inferenceEngine.activeContextSize != resolvedContext) {
                signature = signature.copy(contextSize = inferenceEngine.activeContextSize)
                loadedSignature = signature.copy(projectorPath = null, imageTokenBudget = null)
            }
        }
        if (signature.projectorPath != null &&
            (inferenceEngine.loadedProjectorPath != signature.projectorPath ||
                previous?.imageTokenBudget != signature.imageTokenBudget ||
                inferenceEngine.loadedCapabilities == null)
        ) {
            inferenceEngine.loadProjector(
                signature.projectorPath,
                requireNotNull(signature.imageTokenBudget),
            )
        }
        val capabilities = inferenceEngine.loadedCapabilities
        if (requirement in setOf(MultimodalRequirement.VISION, MultimodalRequirement.BOTH)) {
            require(capabilities?.vision == true) {
                "The selected projector does not support image input. Download a vision-capable Gemma 4 projector."
            }
        }
        if (requirement in setOf(MultimodalRequirement.AUDIO, MultimodalRequirement.BOTH)) {
            require(capabilities?.audio == true) {
                "The selected projector does not support audio input. Download an audio-capable Gemma 4 projector."
            }
        }
        loadedSignature = signature
        ensureEmbedderLoaded()
        val configuration = ModelLoadConfiguration(
            contextTokens = signature.contextSize,
            declaredContextTokens = profile.declaredContextTokens,
            backend = signature.backend,
            temperature = generation.temperature,
            modelId = model.id,
            modelSha256 = model.sha256,
        )
        _state.value = ModelResidencyState.Ready(
            modelName = model.displayName,
            contextSize = signature.contextSize,
            declaredContextSize = profile.declaredContextTokens,
            loadMillis = mark.elapsedNow().inWholeMilliseconds,
            visionReady = capabilities?.vision == true,
            audioReady = capabilities?.audio == true,
            verifiedContextSize = profile.verifiedContextTokens,
            contextVerificationState = profile.state,
        )
        return configuration
    }

    private suspend fun verifyProgressively() {
        if (!eligibleForVerification()) return
        val model = selectedReadyModel() ?: return
        try {
            mutex.withLock {
                ensureLoadedLocked(MultimodalRequirement.NONE, 280)
            }
            var profile = contextProfiles.resolve(model)
            val candidates = ContextCandidates.remaining(profile)
            for (candidate in candidates) {
                coroutineContext.ensureActive()
                if (!eligibleForVerification()) break
                profile = verifyCandidate(model, profile, candidate)
                if (profile.state in setOf(
                        ContextVerificationState.LIMITED,
                        ContextVerificationState.FAILED,
                    )
                ) {
                    break
                }
                delay(1_500)
            }
        } catch (cancelled: CancellationException) {
            contextProfiles.resolve(model).takeIf {
                it.state == ContextVerificationState.VERIFYING
            }?.let { contextProfiles.markPaused(it) }
            throw cancelled
        } finally {
            if (inferenceUseCounter.count == 0 && deviceUsable) {
                runCatching {
                    mutex.withLock { ensureLoadedLocked(MultimodalRequirement.NONE, 280) }
                }
            }
        }
    }

    private suspend fun verifyCandidate(
        model: ModelRecord,
        initialProfile: ModelContextProfile,
        candidate: Int,
    ): ModelContextProfile {
        var profile = contextProfiles.markAttempt(initialProfile, candidate)
        return try {
            runtimeMonitor.requireStable()
            val mark = TimeSource.Monotonic.markNow()
            val metrics = runtimeMonitor.measureVerification {
                mutex.withLock {
                    if (inferenceEngine.loadedModelPath != null) inferenceEngine.unload()
                    inferenceEngine.loadModel(
                        path = requireNotNull(model.localPath),
                        displayName = model.displayName,
                        configuration = ModelLoadConfiguration(
                            contextTokens = candidate,
                            declaredContextTokens = profile.declaredContextTokens,
                            backend = profile.backend,
                            temperature = 0f,
                            modelId = model.id,
                            modelSha256 = model.sha256,
                        ),
                    )
                    check((inferenceEngine.state.value as? InferenceState.Ready)?.backend == profile.backend) {
                        "${profile.backend} context verification switched to the CPU fallback."
                    }
                    check(inferenceEngine.activeContextSize == candidate) {
                        "llama.cpp allocated ${inferenceEngine.activeContextSize} instead of $candidate tokens."
                    }
                    loadedSignature = ModelLoadSignature(
                        path = model.localPath,
                        contextSize = candidate,
                        temperature = 0f,
                        backend = profile.backend,
                    )
                    val generated = inferenceEngine.verifyLoadedContext()
                    check((inferenceEngine.state.value as? InferenceState.Ready)?.backend == profile.backend) {
                        "${profile.backend} context verification did not finish on its requested backend."
                    }
                    generated
                }
            }
            runtimeMonitor.requireStable(metrics)
            mutex.withLock {
                profile = contextProfiles.recordPassed(
                    profile = profile,
                    candidateTokens = candidate,
                    declaredTokens = inferenceEngine.modelContextLimit,
                    metrics = metrics,
                )
                _state.value = ModelResidencyState.Ready(
                    modelName = model.displayName,
                    contextSize = candidate,
                    declaredContextSize = inferenceEngine.modelContextLimit,
                    loadMillis = mark.elapsedNow().inWholeMilliseconds,
                    verifiedContextSize = profile.verifiedContextTokens,
                    contextVerificationState = profile.state,
                )
            }
            profile
        } catch (cancelled: CancellationException) {
            contextProfiles.markPaused(profile)
            throw cancelled
        } catch (error: Throwable) {
            val metrics = runCatching { runtimeMonitor.snapshot(0) }.getOrNull()
            profile = contextProfiles.recordFailure(
                profile = profile,
                candidateTokens = candidate,
                reason = error.message ?: "Context verification failed.",
                metrics = metrics,
            )
            profile
        }
    }

    private fun canStartVerification(): Boolean =
        uiForeground &&
            inferenceUseCounter.count == 0 &&
            deviceUsable

    private suspend fun eligibleForVerification(): Boolean =
        canStartVerification() &&
            !modelRepository.hasActiveTransfers() &&
            !attachmentRepository.hasActiveProcessing()

    private suspend fun selectedReadyModel(): ModelRecord? =
        modelRepository.selectedModel()
            ?.takeIf { it.status == DownloadStatus.READY && it.localPath != null }
            ?.let { model -> modelRepository.ensureSha256(model) }

    private suspend fun selectedOrReadyModel(): ModelRecord? =
        modelRepository.selectedModel()
            ?.takeIf { it.localPath != null && it.status == DownloadStatus.READY }
            ?: modelRepository.models.first()
            .firstOrNull { it.localPath != null && it.status == DownloadStatus.READY }
            ?.also { modelRepository.selectModel(it.id) }

    private fun recordError(error: Throwable) {
        val message = error.message?.takeIf(String::isNotBlank)
            ?: error.javaClass.simpleName
        Log.e(TAG, "Model residency operation failed", error)
        _state.value = ModelResidencyState.Error(message)
    }

    private companion object {
        const val TAG = "ModelResidency"
    }
}

/**
 * Tracks how many inference flows currently use the resident model. The begin/end
 * pairing is load-bearing for context verification: if begin is cancelled after the
 * increment, the increment must roll back, otherwise verification stays disabled.
 */
internal class InferenceUseCounter {
    private val users = AtomicInteger(0)

    val count: Int
        get() = users.get()

    suspend fun begin(joinVerification: suspend () -> Unit) {
        users.incrementAndGet()
        try {
            joinVerification()
        } catch (error: Throwable) {
            end()
            throw error
        }
    }

    fun end() {
        users.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
    }
}

internal fun ModelLoadSignature.matchesLoadedBase(
    expectedPath: String,
    expectedContextSize: Int,
    expectedTemperature: Float,
    expectedBackend: BackendMode,
    actualBackend: BackendMode?,
    loadedPath: String?,
    activeContextSize: Int,
): Boolean =
    path == expectedPath &&
        contextSize == expectedContextSize &&
        temperature == expectedTemperature &&
        backend == expectedBackend &&
        actualBackend == expectedBackend &&
        loadedPath == expectedPath &&
        activeContextSize == expectedContextSize
