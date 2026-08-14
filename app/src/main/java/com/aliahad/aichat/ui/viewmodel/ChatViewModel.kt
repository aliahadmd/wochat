package com.aliahad.aichat.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliahad.aichat.attachment.AttachmentRepository
import com.aliahad.aichat.core.Attachment
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.AttachmentProcessingState
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.core.ModelRecord
import com.aliahad.aichat.core.TurnOrigin
import com.aliahad.aichat.data.ChatRepository
import com.aliahad.aichat.inference.InferenceEngine
import com.aliahad.aichat.model.ModelConstants
import com.aliahad.aichat.model.ModelRepository
import com.aliahad.aichat.residency.ModelResidencyController
import com.aliahad.aichat.settings.AppSettingsRepository
import com.aliahad.aichat.skill.MAX_SELECTED_SKILLS
import com.aliahad.aichat.skill.SkillRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel internal constructor(
    private val savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val modelRepository: ModelRepository,
    private val skillRepository: SkillRepository,
    private val attachmentRepository: AttachmentRepository,
    private val settings: AppSettingsRepository,
    private val inferenceEngine: InferenceEngine,
    private val residencyController: ModelResidencyController,
    private val runner: ChatTurnRunner,
    private val projectorPrompts: ProjectorPromptCoordinator,
    private val uiMessages: UiMessageManager,
) : ViewModel() {
    private val initialDraftKey = savedStateHandle.get<String>(KEY_DRAFT_KEY)
        ?: UUID.randomUUID().toString().also { savedStateHandle[KEY_DRAFT_KEY] = it }
    private val initialModels = ModelConstants.OFFICIAL_MODELS.map { spec ->
        ModelRecord(
            id = spec.id,
            displayName = spec.displayName,
            fileName = spec.fileName,
            localPath = null,
            sourceRepo = spec.repository,
            expectedBytes = spec.sizeBytes,
            sha256 = spec.sha256,
            downloadedBytes = 0,
            status = DownloadStatus.NOT_DOWNLOADED,
            error = null,
            selected = false,
        )
    }
    private val _uiState = MutableStateFlow(
        ChatUiState(
            draftKey = initialDraftKey,
            selectedConversationId = savedStateHandle[KEY_SELECTED_CONVERSATION_ID],
            models = initialModels,
        ),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var draftJob: Job? = null
    private var generationJob: Job? = null
    private var observedConversationId: String? = null
    private var memoryEnabled: Boolean = true

    init {
        viewModelScope.launch {
            chatRepository.conversations.collectLatest { conversations ->
                _uiState.update { it.copy(conversations = conversations) }
                val selectedId = _uiState.value.selectedConversationId
                when {
                    selectedId != null && conversations.any { it.id == selectedId } -> {
                        if (observedConversationId != selectedId) observeConversation(selectedId)
                    }
                    conversations.isNotEmpty() -> selectConversation(conversations.first().id)
                    selectedId != null -> clearConversationSelection()
                }
            }
        }
        viewModelScope.launch {
            runCatching { modelRepository.ensureOfficialRecords() }.onFailure(uiMessages::report)
            modelRepository.models.collectLatest { models ->
                _uiState.update { it.copy(models = models, modelCatalogLoaded = true) }
                if (models.any { it.selected } && _uiState.value.draftAttachments.any {
                        it.kind == AttachmentKind.IMAGE ||
                            it.kind == AttachmentKind.AUDIO ||
                            it.derivedImagePaths.isNotEmpty()
                    }
                ) {
                    projectorPrompts.requestForSelectedModel()
                }
            }
        }
        viewModelScope.launch {
            modelRepository.projectors.collectLatest { projectors ->
                _uiState.update { it.copy(projectors = projectors) }
            }
        }
        viewModelScope.launch {
            skillRepository.skills.collectLatest { skills ->
                val enabledIds = skills.filter { it.enabled }.map { it.id }.toSet()
                _uiState.update {
                    it.copy(
                        skills = skills,
                        selectedSkillIds = it.selectedSkillIds.filter(enabledIds::contains),
                    )
                }
            }
        }
        viewModelScope.launch {
            inferenceEngine.state.collectLatest { value ->
                _uiState.update { it.copy(inferenceState = value) }
            }
        }
        viewModelScope.launch {
            inferenceEngine.metrics.collectLatest { value ->
                _uiState.update { it.copy(inferenceMetrics = value) }
            }
        }
        viewModelScope.launch {
            residencyController.state.collectLatest { value ->
                _uiState.update { it.copy(residencyState = value) }
            }
        }
        viewModelScope.launch {
            runner.state.collectLatest { value ->
                _uiState.update {
                    it.copy(
                        thinking = value.thinking,
                        usedMemoryCount = value.usedMemoryCount,
                        isSending = value.isSending,
                    )
                }
            }
        }
        viewModelScope.launch {
            settings.memoryEnabled.collectLatest { memoryEnabled = it }
        }
        observeDraft(initialDraftKey)
    }

    fun newConversation() = createConversation(temporary = false)

    fun newTemporaryConversation() = createConversation(temporary = true)

    private fun createConversation(temporary: Boolean) {
        viewModelScope.launch {
            val mode = settings.lastQualityMode.first()
            runCatching { chatRepository.createConversation(mode, temporary) }
                .onSuccess { selectConversation(it.id) }
                .onFailure(uiMessages::report)
        }
    }

    fun selectConversation(id: String) {
        if (_uiState.value.selectedConversationId == id && observedConversationId == id) return
        generationJob?.cancel()
        runner.stop()
        observeConversation(id)
    }

    private fun observeConversation(id: String) {
        observedConversationId = id
        savedStateHandle[KEY_SELECTED_CONVERSATION_ID] = id
        _uiState.update {
            it.copy(
                selectedConversationId = id,
                messages = emptyList(),
                messageAttachments = emptyMap(),
                messageSkills = emptyMap(),
            )
        }
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            chatRepository.messages(id).collectLatest { messages ->
                val ids = messages.map { it.id }
                val attachments = attachmentRepository.attachmentsForMessages(ids)
                val skills = skillRepository.blocksForMessages(ids)
                _uiState.update {
                    it.copy(messages = messages, messageAttachments = attachments, messageSkills = skills)
                }
            }
        }
        viewModelScope.launch {
            runCatching { residencyController.ensureLoaded() }.onFailure(uiMessages::report)
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            runCatching {
                if (_uiState.value.selectedConversationId == id) {
                    runner.stop()
                    generationJob?.cancelAndJoin()
                }
                val messageIds = chatRepository.getMessages(id).map { it.id }
                attachmentRepository.attachmentsForMessages(messageIds).values
                    .flatten()
                    .forEach { attachment ->
                        attachmentRepository.remove(attachment.id)
                    }
                chatRepository.deleteConversation(id)
            }.onFailure(uiMessages::report)
            if (_uiState.value.selectedConversationId == id) clearConversationSelection()
        }
    }

    private fun clearConversationSelection() {
        savedStateHandle.remove<String>(KEY_SELECTED_CONVERSATION_ID)
        observedConversationId = null
        messagesJob?.cancel()
        _uiState.update {
            it.copy(
                selectedConversationId = null,
                messages = emptyList(),
                messageAttachments = emptyMap(),
                messageSkills = emptyMap(),
            )
        }
    }

    fun sendMessage(text: String, origin: TurnOrigin = TurnOrigin.TYPED) {
        if (generationJob?.isActive == true) return
        generationJob = viewModelScope.launch {
            val conversationId = ensureConversation() ?: return@launch
            val state = _uiState.value
            val temporary = state.conversations.firstOrNull { it.id == conversationId }?.temporary == true
            runner.send(
                SendTurnRequest(
                    conversationId = conversationId,
                    text = text,
                    attachments = state.draftAttachments,
                    selectedSkillIds = state.selectedSkillIds,
                    memoryEnabled = memoryEnabled,
                    conversationTemporary = temporary,
                    origin = origin,
                    onDraftCommitted = ::clearDraft,
                ),
            )
        }
    }

    fun stopGeneration() {
        runner.stop()
        generationJob?.cancel()
    }

    fun continueResponse() {
        if (generationJob?.isActive == true) return
        val state = _uiState.value
        val conversationId = state.selectedConversationId ?: return
        val target = state.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.status == MessageStatus.CONTINUABLE
        } ?: return
        generationJob = viewModelScope.launch {
            runner.continueResponse(ContinueTurnRequest(conversationId, target, memoryEnabled))
        }
    }

    fun toggleThinking(messageId: String) = runner.toggleThinking(messageId)

    fun toggleSelectedSkill(id: String) {
        if (_uiState.value.isSending) return
        var maxReached = false
        _uiState.update { state ->
            if (state.skills.none { it.id == id && it.enabled }) return@update state
            val selected = state.selectedSkillIds
            val next = when {
                id in selected -> selected - id
                selected.size >= MAX_SELECTED_SKILLS -> {
                    maxReached = true
                    selected
                }
                else -> selected + id
            }
            state.copy(selectedSkillIds = next)
        }
        if (maxReached) uiMessages.report("A message can use up to $MAX_SELECTED_SKILLS skills.")
    }

    fun clearSelectedSkills() {
        _uiState.update { it.copy(selectedSkillIds = emptyList()) }
    }

    fun stageAttachment(uri: Uri) {
        viewModelScope.launch {
            stageAttachmentNow(uri)
        }
    }

    fun removeAttachment(id: String) = launchCatching { attachmentRepository.remove(id) }

    fun retryAttachment(id: String) = launchCatching { attachmentRepository.retry(id) }

    fun selectAttachmentPages(id: String, pages: Set<Int>) =
        launchCatching { attachmentRepository.selectPages(id, pages) }

    private suspend fun stageAttachmentNow(uri: Uri) {
        val current = _uiState.value.draftAttachments
        if (current.size >= MAX_ATTACHMENTS) {
            uiMessages.report("A message can include up to $MAX_ATTACHMENTS attachments.")
            return
        }
        runCatching {
            val item = attachmentRepository.stage(_uiState.value.draftKey, uri)
            val combined = current.sumOf(Attachment::byteSize) + item.byteSize
            if (combined > MAX_ATTACHMENT_BYTES) {
                attachmentRepository.remove(item.id)
                error("Attachments exceed the 500 MB message limit.")
            }
            if (item.kind == AttachmentKind.IMAGE || item.kind == AttachmentKind.AUDIO) {
                projectorPrompts.requestForSelectedModel()
            }
        }.onFailure(uiMessages::report)
    }

    private suspend fun ensureConversation(): String? {
        _uiState.value.selectedConversationId?.let { return it }
        val mode = settings.lastQualityMode.first()
        return runCatching { chatRepository.createConversation(mode).id }
            .onSuccess(::observeConversation)
            .onFailure(uiMessages::report)
            .getOrNull()
    }

    private fun observeDraft(key: String) {
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            attachmentRepository.observeDraft(key).collectLatest { attachments ->
                _uiState.update { it.copy(draftAttachments = attachments) }
                if (attachments.any {
                        it.state == AttachmentProcessingState.READY &&
                            (it.kind == AttachmentKind.IMAGE ||
                                it.kind == AttachmentKind.AUDIO ||
                                it.derivedImagePaths.isNotEmpty())
                    }
                ) {
                    projectorPrompts.requestForSelectedModel()
                }
            }
        }
    }

    private fun clearDraft() {
        val key = UUID.randomUUID().toString()
        savedStateHandle[KEY_DRAFT_KEY] = key
        _uiState.update {
            it.copy(
                draftKey = key,
                draftAttachments = emptyList(),
                selectedSkillIds = emptyList(),
            )
        }
        observeDraft(key)
    }

    private fun launchCatching(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() }.onFailure(uiMessages::report) }
    }

    override fun onCleared() {
        runner.stop()
        super.onCleared()
    }

    private companion object {
        const val MAX_ATTACHMENTS = 20
        const val MAX_ATTACHMENT_BYTES = 500L * 1024 * 1024
        const val KEY_SELECTED_CONVERSATION_ID = "selected_conversation_id"
        const val KEY_DRAFT_KEY = "draft_key"
    }
}
