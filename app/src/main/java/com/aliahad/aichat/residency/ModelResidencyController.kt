package com.aliahad.aichat.residency

import android.content.Context
import android.os.UserManager
import com.aliahad.aichat.attachment.AttachmentRepository
import com.aliahad.aichat.context.ContextCandidates
import com.aliahad.aichat.context.ContextProfileRepository
import com.aliahad.aichat.context.ContextRuntimeMonitor
import com.aliahad.aichat.context.ContextVerifier
import com.aliahad.aichat.context.RoomContextProfileRepository
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.ChatQualityMode
import com.aliahad.aichat.core.ContextVerificationState
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.ModelContextProfile
import com.aliahad.aichat.core.ModelLoadConfiguration
import com.aliahad.aichat.core.ModelRecord
import com.aliahad.aichat.inference.InferenceEngine
import com.aliahad.aichat.model.ModelRepository
import com.aliahad.aichat.settings.AppSettingsRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
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
    private val userManager = context.getSystemService(UserManager::class.java)
    private val runtimeMonitor = ContextRuntimeMonitor(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val activeInferenceUsers = AtomicInteger(0)
    private val _state = MutableStateFlow<ModelResidencyState>(ModelResidencyState.Idle)
    val state: StateFlow<ModelResidencyState> = _state.asStateFlow()

    @Volatile private var uiForeground = false
    private var loadedSignature: ModelLoadSignature? = null
    private var verificationJob: Job? = null

    suspend fun preloadAfterUnlock() {
        if (!userManager.isUserUnlocked) {
            _state.value = ModelResidencyState.WaitingForUnlock
            return
        }
        ensureLoaded()
        scheduleVerification()
    }

    suspend fun ensureLoaded(
        qualityMode: ChatQualityMode? = null,
        requireVision: Boolean = false,
        imageTokenBudget: Int = 280,
    ): ModelLoadConfiguration {
        val configuration = mutex.withLock {
            ensureLoadedLocked(qualityMode, requireVision, imageTokenBudget)
        }
        scheduleVerification()
        return configuration
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
        activeInferenceUsers.incrementAndGet()
        verificationJob?.let { job ->
            job.cancel()
            job.cancelAndJoin()
        }
    }

    override fun endInferenceUse() {
        activeInferenceUsers.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
        scheduleVerification()
    }

    override fun scheduleVerification() {
        if (!canStartVerification()) return
        if (verificationJob?.isActive == true) return
        verificationJob = scope.launch {
            delay(15_000)
            verifyProgressively()
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

    private suspend fun ensureLoadedLocked(
        qualityMode: ChatQualityMode?,
        requireVision: Boolean,
        imageTokenBudget: Int,
    ): ModelLoadConfiguration {
        val unresolved = if (qualityMode == null) {
            selectedOrReadyModel()
        } else {
            modelRepository.modelForQuality(qualityMode)?.also {
                require(it.status == DownloadStatus.READY && it.localPath != null) {
                    "${it.displayName} is not installed."
                }
                if (!it.selected) modelRepository.selectModel(it.id)
            }
        }
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
        val resolvedContext = maxOf(
            profile.verifiedContextTokens,
            RoomContextProfileRepository.SAFE_CONTEXT_TOKENS,
        ).let { value ->
            profile.declaredContextTokens.takeIf { it > 0 }?.let { value.coerceAtMost(it) } ?: value
        }
        val generation = settingsRepository.generationSettings.first().normalized()
        val projector = if (requireVision) {
            requireNotNull(modelRepository.projectorForModel(model.id)) {
                "This model has no configured vision projector."
            }.also {
                require(it.status == DownloadStatus.READY && it.localPath != null) {
                    "Download ${it.displayName} before sending images."
                }
            }
        } else {
            null
        }
        val previous = loadedSignature
        val baseMatches = previous != null &&
            previous.path == modelPath &&
            previous.contextSize == resolvedContext &&
            previous.temperature == generation.temperature &&
            inferenceEngine.loadedModelPath == modelPath &&
            inferenceEngine.activeContextSize == resolvedContext
        val retainedProjectorPath = if (!requireVision && baseMatches) {
            previous.projectorPath
        } else {
            projector?.localPath
        }
        val retainedImageBudget = if (!requireVision && baseMatches) {
            previous.imageTokenBudget
        } else {
            projector?.let { imageTokenBudget }
        }
        var signature = ModelLoadSignature(
            path = modelPath,
            contextSize = resolvedContext,
            temperature = generation.temperature,
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
                    temperature = generation.temperature,
                ),
            )
            loadedSignature = signature.copy(projectorPath = null, imageTokenBudget = null)
            profile = contextProfiles.recordDeclared(profile, inferenceEngine.modelContextLimit)
            if (inferenceEngine.activeContextSize != resolvedContext) {
                signature = signature.copy(contextSize = inferenceEngine.activeContextSize)
                loadedSignature = signature.copy(projectorPath = null, imageTokenBudget = null)
            }
        }
        if (signature.projectorPath != null &&
            (inferenceEngine.loadedProjectorPath != signature.projectorPath ||
                previous?.imageTokenBudget != signature.imageTokenBudget)
        ) {
            inferenceEngine.loadProjector(
                signature.projectorPath,
                requireNotNull(signature.imageTokenBudget),
            )
        }
        loadedSignature = signature
        val configuration = ModelLoadConfiguration(
            contextTokens = signature.contextSize,
            declaredContextTokens = profile.declaredContextTokens,
            temperature = generation.temperature,
        )
        _state.value = ModelResidencyState.Ready(
            modelName = model.displayName,
            contextSize = signature.contextSize,
            declaredContextSize = profile.declaredContextTokens,
            loadMillis = mark.elapsedNow().inWholeMilliseconds,
            visionReady = signature.projectorPath != null,
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
                ensureLoadedLocked(null, false, 280)
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
            if (activeInferenceUsers.get() == 0 && userManager.isUserUnlocked) {
                runCatching {
                    mutex.withLock { ensureLoadedLocked(null, false, 280) }
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
                            temperature = 0f,
                        ),
                    )
                    check(inferenceEngine.activeContextSize == candidate) {
                        "llama.cpp allocated ${inferenceEngine.activeContextSize} instead of $candidate tokens."
                    }
                    loadedSignature = ModelLoadSignature(
                        path = model.localPath,
                        contextSize = candidate,
                        temperature = 0f,
                    )
                    inferenceEngine.verifyLoadedContext()
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
            activeInferenceUsers.get() == 0 &&
            userManager.isUserUnlocked

    private suspend fun eligibleForVerification(): Boolean =
        canStartVerification() &&
            !modelRepository.hasActiveTransfers() &&
            !attachmentRepository.hasActiveProcessing()

    private suspend fun selectedReadyModel(): ModelRecord? =
        modelRepository.selectedModel()
            ?.takeIf { it.status == DownloadStatus.READY && it.localPath != null }
            ?.let { model -> modelRepository.ensureSha256(model) }

    private suspend fun selectedOrReadyModel(): ModelRecord? =
        modelRepository.selectedModel() ?: modelRepository.models.first()
            .firstOrNull { it.localPath != null && it.status == DownloadStatus.READY }
            ?.also { modelRepository.selectModel(it.id) }
}
