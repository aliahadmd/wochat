package com.aliahad.aichat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.Attachment
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.AttachmentProcessingState
import com.aliahad.aichat.core.ChatQualityMode
import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.Conversation
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.InferenceMetrics
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.core.ModelRecord
import com.aliahad.aichat.core.ProjectorRecord
import com.aliahad.aichat.core.UserTurn
import com.aliahad.aichat.residency.ModelResidencyState
import com.aliahad.aichat.inference.HistoryTrimmer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

enum class AppPage {
    CHAT,
    SETTINGS,
}

data class MainUiState(
    val conversations: List<Conversation> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val models: List<ModelRecord> = emptyList(),
    val projectors: List<ProjectorRecord> = emptyList(),
    val draftAttachments: List<Attachment> = emptyList(),
    val messageAttachments: Map<String, List<Attachment>> = emptyMap(),
    val draftKey: String = "",
    val selectedConversationId: String? = null,
    val page: AppPage = AppPage.CHAT,
    val backendMode: BackendMode = BackendMode.AUTO,
    val generationSettings: GenerationSettings = GenerationSettings(),
    val inferenceState: InferenceState = InferenceState.Uninitialized,
    val inferenceMetrics: InferenceMetrics = InferenceMetrics(),
    val residencyState: ModelResidencyState = ModelResidencyState.Idle,
    val isSending: Boolean = false,
    val tokenMasked: String? = null,
    val tokenTesting: Boolean = false,
    val error: String? = null,
    val pendingProjectorId: String? = null,
)

class MainViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        MainUiState(
            tokenMasked = container.settings.maskedToken(),
            draftKey = UUID.randomUUID().toString(),
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var generationJob: Job? = null
    private var configurationReloadJob: Job? = null
    private var draftJob: Job? = null

    init {
        viewModelScope.launch {
            container.chatRepository.conversations.collectLatest { conversations ->
                _uiState.update { it.copy(conversations = conversations) }
                if (_uiState.value.selectedConversationId == null && conversations.isNotEmpty()) {
                    selectConversation(conversations.first().id)
                }
            }
        }
        viewModelScope.launch {
            container.modelRepository.models.collectLatest { models ->
                _uiState.update { it.copy(models = models) }
            }
        }
        viewModelScope.launch {
            container.modelRepository.projectors.collectLatest { projectors ->
                _uiState.update { it.copy(projectors = projectors) }
            }
        }
        observeDraft(_uiState.value.draftKey)
        viewModelScope.launch {
            container.settings.backendMode.collectLatest { mode ->
                _uiState.update { it.copy(backendMode = mode) }
            }
        }
        viewModelScope.launch {
            container.settings.generationSettings.collectLatest { settings ->
                _uiState.update { it.copy(generationSettings = settings) }
            }
        }
        viewModelScope.launch {
            container.inferenceEngine.state.collectLatest { state ->
                _uiState.update { it.copy(inferenceState = state) }
            }
        }
        viewModelScope.launch {
            container.inferenceEngine.metrics.collectLatest { metrics ->
                _uiState.update { it.copy(inferenceMetrics = metrics) }
            }
        }
        viewModelScope.launch {
            container.residencyController.state.collectLatest { state ->
                _uiState.update { it.copy(residencyState = state) }
            }
        }
    }

    fun setPage(page: AppPage) {
        _uiState.update { it.copy(page = page) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun newConversation() {
        viewModelScope.launch {
            val mode = container.settings.lastQualityMode.first()
            runCatching { container.chatRepository.createConversation(mode) }
                .onSuccess {
                    selectConversation(it.id)
                    setPage(AppPage.CHAT)
                }
                .onFailure(::showError)
        }
    }

    fun selectConversation(id: String) {
        if (_uiState.value.selectedConversationId == id) return
        generationJob?.cancel()
        container.inferenceEngine.cancel()
        observeConversation(id)
    }

    private fun observeConversation(id: String) {
        _uiState.update { it.copy(selectedConversationId = id, page = AppPage.CHAT, messages = emptyList()) }
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            container.chatRepository.messages(id).collectLatest { messages ->
                _uiState.update { it.copy(messages = messages) }
                val attachments = messages.associate { message ->
                    message.id to container.attachmentRepository.attachmentsForMessage(message.id)
                }
                _uiState.update { it.copy(messageAttachments = attachments) }
            }
        }
        viewModelScope.launch {
            val mode = _uiState.value.conversations.firstOrNull { it.id == id }?.qualityMode
                ?: ChatQualityMode.FAST
            runCatching { prepareQualityMode(mode) }.onFailure(::showError)
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            runCatching {
                container.chatRepository.getMessages(id).forEach { message ->
                    container.attachmentRepository.attachmentsForMessage(message.id).forEach {
                        container.attachmentRepository.remove(it.id)
                    }
                }
                container.chatRepository.deleteConversation(id)
            }
                .onFailure(::showError)
            if (_uiState.value.selectedConversationId == id) {
                _uiState.update { it.copy(selectedConversationId = null, messages = emptyList()) }
            }
        }
    }

    fun sendMessage(text: String) {
        val prompt = text.trim()
        val draft = _uiState.value.draftAttachments
        if ((prompt.isEmpty() && draft.isEmpty()) || generationJob?.isActive == true) return
        if (draft.any { it.state != AttachmentProcessingState.READY }) {
            showError(IllegalStateException("Wait for every attachment to finish processing."))
            return
        }
        generationJob = viewModelScope.launch {
            val conversationId = ensureConversation() ?: return@launch
            val mode = currentQualityMode()
            resolveQualityModel(mode) ?: return@launch
            val settings = _uiState.value.generationSettings.normalized()
            val previous = container.chatRepository.getMessages(conversationId)
                .filter { it.status != MessageStatus.STREAMING }
            val retainedIds = HistoryTrimmer.trim(previous, settings.contextSize).map { it.id }.toSet()
            val previousTurns = previous.filter { it.id in retainedIds }.map { message ->
                ChatTurn(
                    message = message,
                    attachments = container.attachmentRepository.contextsForMessage(message.id, message.content),
                )
            }
            val contexts = container.attachmentRepository.contexts(draft.map { it.id }, prompt)
            val visualCount = contexts.sumOf { it.imagePaths.size }
            val userText = prompt.ifEmpty { attachmentOnlyPrompt(draft) }
            var assistant: ChatMessage? = null
            try {
                _uiState.update { it.copy(isSending = true, error = null) }
                val initialVisualBudget = allocateVisualBudget(
                    mode,
                    visualCount,
                    settings.contextSize - settings.maxNewTokens - 256,
                )
                container.residencyController.ensureLoaded(
                    qualityMode = mode,
                    requireVision = visualCount > 0,
                    imageTokenBudget = initialVisualBudget,
                )
                var historyTokens = 0
                previousTurns.forEach { turn ->
                    historyTokens += container.inferenceEngine.countTokens(
                        turn.message.content + turn.attachments.joinToString { it.extractedText },
                    ) + turn.attachments.sumOf { it.imagePaths.size * it.imageTokenBudget }
                }
                val currentTextTokens = container.inferenceEngine.countTokens(
                    userText + contexts.joinToString { it.extractedText },
                )
                val availableVisualTokens = settings.contextSize - settings.maxNewTokens -
                    historyTokens - currentTextTokens - 256
                val visualBudget = allocateVisualBudget(mode, visualCount, availableVisualTokens)
                if (visualCount > 0 && visualBudget != initialVisualBudget) {
                    container.residencyController.ensureLoaded(
                        qualityMode = mode,
                        requireVision = true,
                        imageTokenBudget = visualBudget,
                    )
                }
                val adjustedContexts = contexts.map { it.copy(imageTokenBudget = visualBudget) }
                val user = container.chatRepository.addMessage(
                    conversationId,
                    MessageRole.USER,
                    userText,
                )
                container.attachmentRepository.bind(user.id, conversationId, draft.map { it.id })
                _uiState.update {
                    it.copy(
                        messageAttachments = it.messageAttachments + (user.id to draft),
                    )
                }
                clearDraft()
                assistant = container.chatRepository.addMessage(
                    conversationId,
                    MessageRole.ASSISTANT,
                    "",
                    MessageStatus.STREAMING,
                )
                container.inferenceEngine.restoreSession(conversationId, previousTurns, settings)
                val content = StringBuilder()
                var lastSavedAt = 0L
                container.inferenceEngine.generate(
                    UserTurn(
                        conversationId = conversationId,
                        text = user.content,
                        attachments = adjustedContexts,
                    ),
                    settings,
                ).collect { token ->
                    content.append(token)
                    val now = System.currentTimeMillis()
                    if (now - lastSavedAt >= 250 || content.length < 40) {
                        assistant?.copy(content = content.toString())?.let { updated ->
                            assistant = updated
                            container.chatRepository.updateMessage(updated)
                        }
                        lastSavedAt = now
                    }
                }
                assistant?.copy(
                    content = content.toString(),
                    status = MessageStatus.COMPLETE,
                )?.let { completed ->
                    assistant = completed
                    container.chatRepository.updateMessage(completed)
                }
            } catch (cancelled: CancellationException) {
                assistant?.let {
                    container.chatRepository.updateMessage(it.copy(status = MessageStatus.CANCELLED))
                }
            } catch (error: Throwable) {
                assistant?.let {
                    container.chatRepository.updateMessage(
                        it.copy(
                            content = it.content.ifEmpty { "Generation failed: ${error.message}" },
                            status = MessageStatus.ERROR,
                        ),
                    )
                }
                showError(error)
            } finally {
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    fun stopGeneration() {
        container.inferenceEngine.cancel()
        generationJob?.cancel()
    }

    fun startDownload(id: String) {
        viewModelScope.launch {
            runCatching { container.modelRepository.startOfficialDownload(id) }.onFailure(::showError)
        }
    }

    fun startProjectorDownload(id: String) {
        _uiState.update { it.copy(pendingProjectorId = null) }
        viewModelScope.launch {
            runCatching { container.modelRepository.startProjectorDownload(id) }.onFailure(::showError)
        }
    }

    fun pauseProjectorDownload(id: String) {
        viewModelScope.launch {
            runCatching { container.modelRepository.pauseProjectorDownload(id) }.onFailure(::showError)
        }
    }

    fun deleteProjector(id: String) {
        viewModelScope.launch {
            runCatching {
                if (_uiState.value.projectors.firstOrNull { it.id == id }?.localPath
                        ?.let(container.residencyController::isPathInUse) == true
                ) {
                    container.residencyController.unload()
                }
                container.modelRepository.deleteProjector(id)
            }.onFailure(::showError)
        }
    }

    fun dismissProjectorPrompt() {
        _uiState.update { it.copy(pendingProjectorId = null) }
    }

    fun stageAttachment(uri: Uri) {
        viewModelScope.launch {
            val current = _uiState.value.draftAttachments
            if (current.size >= MAX_ATTACHMENTS) {
                showError(IllegalStateException("A message can include up to $MAX_ATTACHMENTS attachments."))
                return@launch
            }
            runCatching {
                val item = container.attachmentRepository.stage(_uiState.value.draftKey, uri)
                val combined = current.sumOf(Attachment::byteSize) + item.byteSize
                if (combined > MAX_ATTACHMENT_BYTES) {
                    container.attachmentRepository.remove(item.id)
                    error("Attachments exceed the 500 MB message limit.")
                }
                if (item.kind == AttachmentKind.IMAGE) promptForProjector()
            }.onFailure(::showError)
        }
    }

    fun removeAttachment(id: String) {
        viewModelScope.launch {
            runCatching { container.attachmentRepository.remove(id) }.onFailure(::showError)
        }
    }

    fun retryAttachment(id: String) {
        viewModelScope.launch {
            runCatching { container.attachmentRepository.retry(id) }.onFailure(::showError)
        }
    }

    fun selectAttachmentPages(id: String, pages: Set<Int>) {
        viewModelScope.launch {
            runCatching { container.attachmentRepository.selectPages(id, pages) }.onFailure(::showError)
        }
    }

    fun updateQualityMode(mode: ChatQualityMode) {
        if (_uiState.value.isSending) return
        viewModelScope.launch {
            val id = ensureConversation() ?: return@launch
            runCatching {
                container.chatRepository.setQualityMode(id, mode)
                container.settings.setLastQualityMode(mode)
                prepareQualityMode(mode)
            }.onFailure(::showError)
        }
    }

    fun pauseDownload(id: String) {
        viewModelScope.launch {
            runCatching { container.modelRepository.pauseOfficialDownload(id) }.onFailure(::showError)
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            runCatching { container.modelRepository.importModel(uri) }
                .onSuccess { container.modelRepository.selectModel(it.id) }
                .onFailure(::showError)
        }
    }

    fun selectModel(id: String) {
        viewModelScope.launch {
            runCatching {
                container.residencyController.unload()
                container.modelRepository.selectModel(id)
                container.residencyController.ensureLoaded()
            }.onFailure(::showError)
        }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch {
            runCatching {
                val model = _uiState.value.models.firstOrNull { it.id == id }
                if (model?.localPath?.let(container.residencyController::isPathInUse) == true) {
                    container.residencyController.unload()
                }
                container.modelRepository.deleteModel(id)
            }.onFailure(::showError)
        }
    }

    fun updateBackend(@Suppress("UNUSED_PARAMETER") mode: BackendMode) {
        viewModelScope.launch {
            container.settings.setBackend(BackendMode.CPU)
            container.residencyController.reloadForConfigurationChange()
        }
    }

    fun updateGeneration(settings: GenerationSettings) {
        val previous = _uiState.value.generationSettings
        viewModelScope.launch {
            container.settings.updateGeneration(settings)
            if (previous.contextSize != settings.normalized().contextSize ||
                previous.temperature != settings.normalized().temperature
            ) {
                configurationReloadJob?.cancel()
                configurationReloadJob = viewModelScope.launch {
                    delay(750)
                    runCatching { container.residencyController.reloadForConfigurationChange() }
                        .onFailure(::showError)
                }
            }
        }
    }

    fun retryPreload() {
        viewModelScope.launch {
            runCatching { container.residencyController.ensureLoaded() }.onFailure(::showError)
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            runCatching { container.residencyController.unload() }.onFailure(::showError)
        }
    }

    fun saveToken(token: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(tokenTesting = true) }
            val test = container.modelRepository.testHuggingFaceToken(token)
            test.onSuccess {
                container.settings.saveToken(token)
                _uiState.update { state ->
                    state.copy(tokenMasked = container.settings.maskedToken(), tokenTesting = false)
                }
            }.onFailure {
                _uiState.update { state -> state.copy(tokenTesting = false) }
                showError(it)
            }
        }
    }

    fun clearToken() {
        container.settings.clearToken()
        _uiState.update { it.copy(tokenMasked = null) }
    }

    private suspend fun ensureConversation(): String? {
        _uiState.value.selectedConversationId?.let { return it }
        val mode = container.settings.lastQualityMode.first()
        return runCatching { container.chatRepository.createConversation(mode).id }
            .onSuccess(::observeConversation)
            .onFailure(::showError)
            .getOrNull()
    }

    private suspend fun resolveSelectedModel(): ModelRecord? {
        container.modelRepository.selectedModel()?.let { return it }
        val ready = _uiState.value.models.firstOrNull { it.status == DownloadStatus.READY }
        if (ready != null) {
            container.modelRepository.selectModel(ready.id)
            return ready.copy(selected = true)
        }
        showError(IllegalStateException("Download or import a GGUF model in Settings first."))
        return null
    }

    private suspend fun resolveQualityModel(mode: ChatQualityMode): ModelRecord? {
        val model = container.modelRepository.modelForQuality(mode)
        if (model?.status == DownloadStatus.READY && model.localPath != null) return model
        showError(
            IllegalStateException(
                "${if (mode == ChatQualityMode.FAST) "Gemma 4 E4B" else "Gemma 4 12B"} is not installed.",
            ),
        )
        return null
    }

    private suspend fun prepareQualityMode(mode: ChatQualityMode) {
        resolveQualityModel(mode) ?: return
        container.residencyController.ensureLoaded(qualityMode = mode)
    }

    private fun currentQualityMode(): ChatQualityMode =
        _uiState.value.conversations.firstOrNull {
            it.id == _uiState.value.selectedConversationId
        }?.qualityMode ?: ChatQualityMode.FAST

    private suspend fun promptForProjector() {
        val mode = currentQualityMode()
        val model = container.modelRepository.modelForQuality(mode) ?: return
        val projector = container.modelRepository.projectorForModel(model.id) ?: return
        if (projector.status != DownloadStatus.READY) {
            _uiState.update { it.copy(pendingProjectorId = projector.id) }
        }
    }

    private fun observeDraft(key: String) {
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            container.attachmentRepository.observeDraft(key).collectLatest { attachments ->
                _uiState.update { it.copy(draftAttachments = attachments) }
                if (attachments.any {
                        it.state == AttachmentProcessingState.READY &&
                            (it.kind == AttachmentKind.IMAGE || it.derivedImagePaths.isNotEmpty())
                    }
                ) {
                    promptForProjector()
                }
            }
        }
    }

    private fun clearDraft() {
        val key = UUID.randomUUID().toString()
        _uiState.update { it.copy(draftKey = key, draftAttachments = emptyList()) }
        observeDraft(key)
    }

    private fun allocateVisualBudget(
        mode: ChatQualityMode,
        imageCount: Int,
        availableTokens: Int,
    ): Int {
        if (imageCount <= 0) return 70
        require(availableTokens >= imageCount * 70) {
            "All current attachments cannot fit. Increase context or remove an attachment."
        }
        val preferred = if (mode == ChatQualityMode.FAST) 560 else 1120
        val availablePerImage = (availableTokens / imageCount).coerceAtLeast(70)
        return SUPPORTED_VISUAL_BUDGETS.lastOrNull { it <= minOf(preferred, availablePerImage) } ?: 70
    }

    private fun attachmentOnlyPrompt(attachments: List<Attachment>): String =
        "Describe and analyze ${attachments.joinToString { it.displayName }}."

    private fun showError(error: Throwable) {
        _uiState.update { it.copy(error = error.message ?: error.javaClass.simpleName) }
    }

    override fun onCleared() {
        container.inferenceEngine.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_ATTACHMENTS = 20
        const val MAX_ATTACHMENT_BYTES = 500L * 1024 * 1024
        val SUPPORTED_VISUAL_BUDGETS = listOf(70, 140, 280, 560, 1120)
    }

}
