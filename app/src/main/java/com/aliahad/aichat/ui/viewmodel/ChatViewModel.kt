package com.aliahad.aichat.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliahad.aichat.attachment.AttachmentRepository
import com.aliahad.aichat.core.Attachment
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.AttachmentProcessingState
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.Conversation
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.GenerationStopReason
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/** The most attachments a single message may carry. */
internal const val MAX_MESSAGE_ATTACHMENTS = 20

/**
 * Whether moving from [previous] to [next] should discard the current draft.
 *
 * True only for a genuine move between two different conversations. Restoring
 * the same conversation after process death ([previous] == [next]) and the very
 * first selection of the session ([previous] == null) both keep the draft —
 * otherwise every cold start would silently throw away what the user typed.
 */
internal fun shouldClearDraftOnSwitch(previous: String?, next: String): Boolean =
    previous != null && previous != next

/** How much of a picker selection fits, and what to tell the user if some does not. */
internal data class AttachmentIntake(
    val accepted: Int,
    val rejected: Int,
    val message: String?,
)

internal fun planAttachmentIntake(staged: Int, selected: Int, limit: Int): AttachmentIntake {
    val remaining = (limit - staged).coerceAtLeast(0)
    val accepted = minOf(selected, remaining)
    val rejected = selected - accepted
    val message = when {
        rejected <= 0 -> null
        accepted == 0 ->
            "Attachment limit reached \u2014 a message can include up to $limit."
        else ->
            "Added $accepted of $selected \u2014 a message can include up to $limit attachments."
    }
    return AttachmentIntake(accepted = accepted, rejected = rejected, message = message)
}

class ChatViewModel internal constructor(
    private val savedStateHandle: SavedStateHandle,
    private val application: Application,
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
            input = savedStateHandle[KEY_DRAFT_INPUT] ?: "",
            selectedConversationId = savedStateHandle[KEY_SELECTED_CONVERSATION_ID],
            models = initialModels,
        ),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var draftJob: Job? = null
    private var generationJob: Job? = null
    private var searchJob: Job? = null
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
            settings.memoryEnabled.collectLatest { enabled ->
                memoryEnabled = enabled
                _uiState.update { it.copy(memoryEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.thinkingEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(thinkingEnabled = enabled) }
            }
        }
        observeDraft(initialDraftKey)
    }

    fun newConversation() = createConversation(temporary = false)

    // The "Temporary chat" entry point is gone: the composer's Memory toggle gates
    // the same two paths (memory is neither read into the prompt nor written back),
    // so a second control for it was redundant. The `temporary` flag itself stays
    // on the conversation model and is still honoured by ChatTurnRunner, because
    // conversations created before this change still carry it.

    private fun createConversation(temporary: Boolean) {
        viewModelScope.launch {
            val mode = settings.lastQualityMode.first()
            runCatching { chatRepository.createConversation(mode, temporary) }
                .onSuccess { selectConversation(it.id) }
                .onFailure(uiMessages::report)
        }
    }

    fun setInput(value: String) {
        savedStateHandle[KEY_DRAFT_INPUT] = value
        _uiState.update { it.copy(input = value) }
    }

    fun selectConversation(id: String) {
        if (_uiState.value.selectedConversationId == id && observedConversationId == id) return
        val previous = _uiState.value.selectedConversationId
        generationJob?.cancel()
        runner.stop()
        // Moving to a different conversation starts a fresh draft. Without this
        // the text, staged attachments and selected skills follow the user into
        // the new conversation and can be sent there by mistake.
        if (shouldClearDraftOnSwitch(previous, id)) clearDraft()
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
        viewModelScope.launch { loadModelForConversation() }
    }

    /**
     * Loading the model is the clearest case where a retry is genuinely useful:
     * it commonly fails for transient reasons (the runtime gate is busy, a
     * backend fell back) and succeeds on a second attempt, so the failure gets
     * a real Retry rather than a notice the user can only dismiss.
     */
    private suspend fun loadModelForConversation() {
        runCatching { residencyController.ensureLoaded() }.onFailure { error ->
            uiMessages.report(error, actionLabel = "Retry") {
                viewModelScope.launch { loadModelForConversation() }
            }
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

    /** Debounced full-text chat search across all conversations. */
    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            val results = runCatching { chatRepository.searchConversations(query) }
                .onFailure(uiMessages::report)
                .getOrDefault(emptyList())
            _uiState.update { state ->
                if (state.searchQuery.trim() == query.trim()) {
                    state.copy(searchResults = results)
                } else {
                    state
                }
            }
        }
    }

    /** Renders the conversation as Markdown and writes it to the user-selected document. */
    fun exportConversationMarkdown(uri: Uri, conversationId: String) {
        viewModelScope.launch {
            runCatching {
                val conversation = chatRepository.conversation(conversationId)
                    ?: error("Conversation was not found.")
                val messages = chatRepository.getMessages(conversationId)
                val attachments = attachmentRepository.attachmentsForMessages(
                    messages.map(ChatMessage::id),
                )
                val markdown = formatConversationMarkdown(conversation, messages, attachments)
                withContext(Dispatchers.IO) {
                    application.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(markdown.toByteArray(Charsets.UTF_8))
                    } ?: error("Unable to open the export destination.")
                }
            }.onFailure(uiMessages::report)
        }
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

    fun setThinkingMode(enabled: Boolean) {
        launchCatching { settings.setThinkingEnabled(enabled) }
    }

    /**
     * Same preference the Memory screen owns, surfaced in the composer so a single
     * question can be asked without it recalling — or being recalled — later.
     */
    fun setMemoryMode(enabled: Boolean) {
        launchCatching { settings.setMemoryEnabled(enabled) }
    }

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

    /**
     * Stages a whole picker selection, accepting only what fits under
     * [MAX_MESSAGE_ATTACHMENTS] and telling the user when anything was left
     * out. Callers must pass the full selection — silently truncating before
     * this point is what made attachments disappear without a word.
     */
    fun stageAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val intake = planAttachmentIntake(
                staged = _uiState.value.draftAttachments.size,
                selected = uris.size,
                limit = MAX_MESSAGE_ATTACHMENTS,
            )
            uris.take(intake.accepted).forEach { stageAttachmentNow(it) }
            intake.message?.let(uiMessages::report)
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
        savedStateHandle[KEY_DRAFT_INPUT] = ""
        _uiState.update {
            it.copy(
                draftKey = key,
                input = "",
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
        const val MAX_ATTACHMENTS = MAX_MESSAGE_ATTACHMENTS
        const val MAX_ATTACHMENT_BYTES = 500L * 1024 * 1024
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val KEY_SELECTED_CONVERSATION_ID = "selected_conversation_id"
        const val KEY_DRAFT_KEY = "draft_key"
        const val KEY_DRAFT_INPUT = "draft_input"
    }
}

/**
 * Renders a conversation as a portable Markdown document. Thinking content is
 * not stored on messages, so only answer text and metadata are exported.
 */
internal fun formatConversationMarkdown(
    conversation: Conversation,
    messages: List<ChatMessage>,
    attachmentsByMessage: Map<String, List<Attachment>>,
): String = buildString {
    appendLine("# ${conversation.title}")
    appendLine()
    appendLine("_Exported from wochat on ${Instant.now()}_")
    messages.forEach { message ->
        appendLine()
        appendLine(
            "## ${message.role.name.lowercase().replaceFirstChar { it.uppercase() }} · " +
                Instant.ofEpochMilli(message.createdAt),
        )
        message.stopReason
            ?.takeIf { it != GenerationStopReason.EOG }
            ?.let { appendLine("<!-- stopped early: ${it.name.lowercase()} -->") }
        attachmentsByMessage[message.id]?.forEach { attachment ->
            appendLine("- attachment: ${attachment.displayName} (${attachment.kind.name.lowercase()})")
        }
        appendLine()
        appendLine(message.content.ifBlank { "_(no text)_" })
    }
}
