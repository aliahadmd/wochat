package com.aliahad.aichat.residency

import android.content.Context
import android.os.UserManager
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.ChatQualityMode
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.inference.InferenceEngine
import com.aliahad.aichat.model.ModelRepository
import com.aliahad.aichat.settings.AppSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

sealed interface ModelResidencyState {
    data object Idle : ModelResidencyState
    data object WaitingForUnlock : ModelResidencyState
    data object WaitingForModel : ModelResidencyState
    data class Loading(val modelName: String) : ModelResidencyState
    data class Ready(
        val modelName: String,
        val contextSize: Int,
        val loadMillis: Long,
        val visionReady: Boolean = false,
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
    private val settingsRepository: AppSettingsRepository,
) {
    private val userManager = context.getSystemService(UserManager::class.java)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<ModelResidencyState>(ModelResidencyState.Idle)
    val state: StateFlow<ModelResidencyState> = _state.asStateFlow()

    private var loadedSignature: ModelLoadSignature? = null

    suspend fun preloadAfterUnlock() {
        if (!userManager.isUserUnlocked) {
            _state.value = ModelResidencyState.WaitingForUnlock
            return
        }
        ensureLoaded()
    }

    suspend fun ensureLoaded(
        qualityMode: ChatQualityMode? = null,
        requireVision: Boolean = false,
        imageTokenBudget: Int = 280,
    ) = mutex.withLock {
        val model = if (qualityMode == null) {
            selectedOrReadyModel()
        } else {
            modelRepository.modelForQuality(qualityMode)?.also {
                require(it.status == DownloadStatus.READY && it.localPath != null) {
                    "${it.displayName} is not installed."
                }
                if (!it.selected) modelRepository.selectModel(it.id)
            }
        }
        if (model?.localPath == null) {
            _state.value = ModelResidencyState.WaitingForModel
            return@withLock
        }
        val projector = if (requireVision) {
            requireNotNull(modelRepository.projectorForModel(model.id)) {
                "This model has no configured vision projector."
            }.also {
                require(it.status == DownloadStatus.READY && it.localPath != null) {
                    "Download ${it.displayName} before sending images."
                }
            }
        } else null
        val generation = settingsRepository.generationSettings.first().normalized()
        val previous = loadedSignature
        val baseMatches = previous?.path == model.localPath &&
            previous.contextSize == generation.contextSize &&
            previous.temperature == generation.temperature &&
            inferenceEngine.loadedModelPath == model.localPath
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
        val signature = ModelLoadSignature(
            path = model.localPath,
            contextSize = generation.contextSize,
            temperature = generation.temperature,
            projectorPath = retainedProjectorPath,
            imageTokenBudget = retainedImageBudget,
        )
        if (loadedSignature == signature &&
            inferenceEngine.loadedModelPath == signature.path &&
            inferenceEngine.loadedProjectorPath == signature.projectorPath
        ) {
            return@withLock
        }

        _state.value = ModelResidencyState.Loading(model.displayName)
        try {
            val mark = TimeSource.Monotonic.markNow()
            if (!baseMatches) {
                if (inferenceEngine.loadedModelPath != null) inferenceEngine.unload()
                inferenceEngine.loadModel(
                    path = signature.path,
                    displayName = model.displayName,
                    backend = BackendMode.CPU,
                    settings = generation,
                )
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
            val loadMillis = mark.elapsedNow().inWholeMilliseconds
            loadedSignature = signature
            _state.value = ModelResidencyState.Ready(
                modelName = model.displayName,
                contextSize = generation.contextSize,
                loadMillis = loadMillis,
                visionReady = signature.projectorPath != null,
            )
        } catch (error: Throwable) {
            loadedSignature = null
            _state.value = ModelResidencyState.Error(
                error.message ?: "Unable to keep the model loaded",
            )
            throw error
        }
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

    private suspend fun selectedOrReadyModel() =
        modelRepository.selectedModel() ?: modelRepository.models.first()
            .firstOrNull { it.localPath != null && it.status == com.aliahad.aichat.core.DownloadStatus.READY }
            ?.also { modelRepository.selectModel(it.id) }
}
