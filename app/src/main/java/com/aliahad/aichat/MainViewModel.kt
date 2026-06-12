package com.aliahad.aichat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.BackupPreview
import com.aliahad.aichat.core.Attachment
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.AttachmentProcessingState
import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.core.ActivitySourceStats
import com.aliahad.aichat.core.ChatQualityMode
import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.Conversation
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.GenerationEvent
import com.aliahad.aichat.core.GenerationStopReason
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.InferenceMetrics
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.core.ModelRecord
import com.aliahad.aichat.core.MemoryItem
import com.aliahad.aichat.core.ModelContextProfile
import com.aliahad.aichat.core.MemoryType
import com.aliahad.aichat.core.ProjectorRecord
import com.aliahad.aichat.core.PhoneSourceAccessState
import com.aliahad.aichat.core.PhoneSourceStatus
import com.aliahad.aichat.core.UserTurn
import com.aliahad.aichat.activity.OfficeWorkScheduler
import com.aliahad.aichat.residency.ModelResidencyState
import com.aliahad.aichat.inference.VisionBudgetPlanner
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
    MEMORY,
    SETTINGS,
}

private fun GenerationStopReason.toMessageStatus(): MessageStatus = when (this) {
    GenerationStopReason.EOG -> MessageStatus.COMPLETE
    GenerationStopReason.TOKEN_LIMIT,
    GenerationStopReason.CONTEXT_LIMIT,
    GenerationStopReason.PROCESS_DEATH -> MessageStatus.CONTINUABLE
    GenerationStopReason.CANCELLED -> MessageStatus.CANCELLED
    GenerationStopReason.DECODE_ERROR,
    GenerationStopReason.ERROR -> MessageStatus.ERROR
}

private fun mergeContinuation(existing: String, continuation: String): String {
    if (existing.isEmpty() || continuation.isEmpty()) return existing + continuation
    val maximum = minOf(existing.length, continuation.length, 320)
    for (overlap in maximum downTo 12) {
        if (existing.regionMatches(
                existing.length - overlap,
                continuation,
                0,
                overlap,
                ignoreCase = false,
            )
        ) {
            return existing + continuation.drop(overlap)
        }
    }
    return existing + continuation
}

data class ThinkingUiState(
    val messageId: String,
    val text: String = "",
    val complete: Boolean = false,
    val expanded: Boolean = false,
)

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
    val memories: List<MemoryItem> = emptyList(),
    val contextProfiles: List<ModelContextProfile> = emptyList(),
    val memoryEnabled: Boolean = true,
    val collectionPaused: Boolean = false,
    val phoneSourceStatuses: Map<ActivitySource, PhoneSourceStatus> = emptyMap(),
    val phoneSourceStats: Map<ActivitySource, ActivitySourceStats> = emptyMap(),
    val thinking: ThinkingUiState? = null,
    val usedMemoryCount: Int = 0,
    val isSending: Boolean = false,
    val tokenMasked: String? = null,
    val tokenTesting: Boolean = false,
    val error: String? = null,
    val pendingProjectorId: String? = null,
    val pendingBackupImportUri: Uri? = null,
    val backupPreview: BackupPreview? = null,
    val backupBusy: Boolean = false,
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
        viewModelScope.launch {
            container.memoryRepository.memories.collectLatest { memories ->
                _uiState.update { it.copy(memories = memories) }
            }
        }
        viewModelScope.launch {
            container.contextProfileRepository.profiles.collectLatest { profiles ->
                _uiState.update { it.copy(contextProfiles = profiles) }
            }
        }
        viewModelScope.launch {
            container.settings.memoryEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(memoryEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            container.settings.collectionPaused.collectLatest { paused ->
                _uiState.update { it.copy(collectionPaused = paused) }
            }
        }
        viewModelScope.launch {
            container.activityRepository.sourceStats.collectLatest { stats ->
                _uiState.update { state ->
                    state.copy(phoneSourceStats = stats.associateBy(ActivitySourceStats::source))
                }
            }
        }
        refreshPhoneSourceAccess()
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

    fun newTemporaryConversation() {
        viewModelScope.launch {
            val mode = container.settings.lastQualityMode.first()
            runCatching { container.chatRepository.createConversation(mode, temporary = true) }
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
        _uiState.update {
            it.copy(
                selectedConversationId = id,
                page = AppPage.CHAT,
                messages = emptyList(),
                thinking = null,
            )
        }
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
                _uiState.update {
                    it.copy(
                        selectedConversationId = null,
                        messages = emptyList(),
                        thinking = null,
                    )
                }
            }
        }
    }

    fun toggleThinking(messageId: String) {
        _uiState.update { state ->
            val thinking = state.thinking?.takeIf { it.messageId == messageId }
                ?: return@update state
            state.copy(thinking = thinking.copy(expanded = !thinking.expanded))
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
            val previousTurns = previous.map { message ->
                ChatTurn(
                    message = message,
                    attachments = container.attachmentRepository.contextsForMessage(message.id, message.content),
                )
            }
            val contexts = container.attachmentRepository.contexts(draft.map { it.id }, prompt)
            val visualCount = contexts.sumOf { it.imagePaths.size }
            // Restoring this conversation re-encodes history images, so the projector
            // must be loaded (at a budget no smaller than history was encoded with)
            // even when the new message itself has no attachments.
            val allHistoryImageBudget = previousTurns.maxOfOrNull { turn ->
                turn.attachments.filter { it.imagePaths.isNotEmpty() }
                    .maxOfOrNull { it.imageTokenBudget } ?: 0
            } ?: 0
            val userText = prompt.ifEmpty { attachmentOnlyPrompt(draft) }
            var assistant: ChatMessage? = null
            var keepThinking = false
            container.residencyController.beginInferenceUse()
            try {
                _uiState.update { it.copy(isSending = true, error = null) }
                val provisionalContext =
                    (_uiState.value.residencyState as? ModelResidencyState.Ready)?.contextSize
                        ?: 4_096
                val initialVisualBudget = VisionBudgetPlanner.allocate(
                    mode,
                    contexts,
                    userText,
                    provisionalContext - settings.maxNewTokens - 256,
                )
                val loadConfiguration = container.residencyController.ensureLoaded(
                    qualityMode = mode,
                    requireVision = visualCount > 0 || allHistoryImageBudget > 0,
                    imageTokenBudget = maxOf(initialVisualBudget, allHistoryImageBudget),
                )
                val contextPlan = container.promptContextPlanner.plan(
                    conversationId = conversationId,
                    history = previousTurns,
                    currentText = userText + contexts.joinToString { it.extractedText },
                    settings = settings,
                    contextTokens = loadConfiguration.contextTokens,
                    memoryEnabled = _uiState.value.memoryEnabled &&
                        _uiState.value.conversations.firstOrNull { it.id == conversationId }?.temporary != true,
                )
                val plannedTurns = contextPlan.history
                val historyImageBudget = plannedTurns.maxOfOrNull { turn ->
                    turn.attachments.filter { it.imagePaths.isNotEmpty() }
                        .maxOfOrNull { it.imageTokenBudget } ?: 0
                } ?: 0
                val availableVisualTokens = loadConfiguration.contextTokens -
                    contextPlan.estimatedTokens -
                    contextPlan.outputReserveTokens -
                    192
                val visualBudget = VisionBudgetPlanner.allocate(
                    mode,
                    contexts,
                    userText,
                    availableVisualTokens,
                )
                if (visualCount > 0 && visualBudget != initialVisualBudget) {
                    container.residencyController.ensureLoaded(
                        qualityMode = mode,
                        requireVision = true,
                        imageTokenBudget = maxOf(visualBudget, historyImageBudget),
                    )
                }
                val adjustedContexts = contexts.map { it.copy(imageTokenBudget = visualBudget) }
                val user = container.chatRepository.addMessage(
                    conversationId,
                    MessageRole.USER,
                    userText,
                )
                container.memoryRepository.rememberMessage(
                    message = user,
                    conversationTemporary = _uiState.value.conversations
                        .firstOrNull { it.id == conversationId }
                        ?.temporary == true,
                )
                container.attachmentRepository.bind(
                    user.id,
                    conversationId,
                    draft.map { it.id },
                    visualBudget.takeIf { visualCount > 0 },
                )
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
                _uiState.update {
                    it.copy(
                        thinking = ThinkingUiState(requireNotNull(assistant).id),
                        usedMemoryCount = contextPlan.memories.size,
                    )
                }
                val plannedSettings = settings.copy(systemPrompt = contextPlan.systemPrompt)
                container.inferenceEngine.restoreSession(conversationId, plannedTurns, plannedSettings)
                if (visualCount > 0 && historyImageBudget > visualBudget) {
                    // History must be reconstructed at its original detail. Once its
                    // KV state is restored, reload only the projector at this turn's
                    // lower budget so Fast descriptions stay fast.
                    container.residencyController.ensureLoaded(
                        qualityMode = mode,
                        requireVision = true,
                        imageTokenBudget = visualBudget,
                    )
                }
                val content = StringBuilder()
                var lastSavedAt = 0L
                var completion: GenerationEvent.Completed? = null
                container.inferenceEngine.generate(
                    UserTurn(
                        conversationId = conversationId,
                        text = user.content,
                        attachments = adjustedContexts,
                    ),
                    plannedSettings,
                ).collect { event ->
                    when (event) {
                        is GenerationEvent.ThoughtDelta -> {
                            val messageId = assistant?.id ?: return@collect
                            _uiState.update { state ->
                                val thinking = state.thinking
                                    ?.takeIf { it.messageId == messageId }
                                    ?: ThinkingUiState(messageId)
                                state.copy(
                                    thinking = thinking.copy(
                                        text = thinking.text + event.text,
                                        complete = false,
                                    ),
                                )
                            }
                        }
                        is GenerationEvent.AnswerDelta -> {
                            content.append(event.text)
                            _uiState.update { state ->
                                val thinking = state.thinking
                                if (thinking == null || thinking.text.isEmpty()) {
                                    state
                                } else {
                                    keepThinking = true
                                    state.copy(
                                        thinking = thinking.copy(
                                            complete = true,
                                            expanded = false,
                                        ),
                                    )
                                }
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastSavedAt >= 250 || content.length < 40) {
                                assistant?.copy(content = content.toString())?.let { updated ->
                                    assistant = updated
                                    container.chatRepository.updateMessage(updated)
                                }
                                lastSavedAt = now
                            }
                        }
                        is GenerationEvent.Completed -> completion = event
                        is GenerationEvent.Phase -> Unit
                    }
                }
                val result = completion ?: GenerationEvent.Completed(
                    reason = GenerationStopReason.ERROR,
                    answerTokens = 0,
                    continuationCount = 0,
                )
                assistant?.copy(
                    content = content.toString().ifEmpty {
                        if (plannedSettings.thinkingEnabled &&
                            result.reason == GenerationStopReason.EOG
                        ) {
                            "The model finished thinking without producing a final answer."
                        } else {
                            ""
                        }
                    },
                    status = if (content.isEmpty() &&
                        plannedSettings.thinkingEnabled &&
                        result.reason == GenerationStopReason.EOG
                    ) {
                        MessageStatus.ERROR
                    } else {
                        result.reason.toMessageStatus()
                    },
                    stopReason = result.reason,
                    continuationCount = result.continuationCount,
                    promptTokens = contextPlan.estimatedTokens,
                    generatedTokens = result.answerTokens,
                )?.let { completed ->
                    assistant = completed
                    container.chatRepository.updateMessage(completed)
                }
            } catch (cancelled: CancellationException) {
                assistant?.let {
                    container.chatRepository.updateMessage(
                        it.copy(
                            status = MessageStatus.CANCELLED,
                            stopReason = GenerationStopReason.CANCELLED,
                        ),
                    )
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
                _uiState.update { state ->
                    state.copy(
                        isSending = false,
                        thinking = if (keepThinking) {
                            state.thinking?.copy(complete = true, expanded = false)
                        } else {
                            null
                        },
                    )
                }
                container.residencyController.endInferenceUse()
            }
        }
    }

    fun stopGeneration() {
        container.inferenceEngine.cancel()
    }

    fun continueResponse() {
        if (generationJob?.isActive == true) return
        val conversationId = _uiState.value.selectedConversationId ?: return
        val target = _uiState.value.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.status == MessageStatus.CONTINUABLE
        } ?: return
        generationJob = viewModelScope.launch {
            val settings = _uiState.value.generationSettings.normalized()
            val mode = currentQualityMode()
            resolveQualityModel(mode) ?: return@launch
            var assistant = target.copy(status = MessageStatus.STREAMING)
            var keepThinking = false
            container.residencyController.beginInferenceUse()
            try {
                _uiState.update {
                    it.copy(
                        isSending = true,
                        thinking = ThinkingUiState(target.id),
                        error = null,
                    )
                }
                container.chatRepository.updateMessage(assistant)
                val messages = container.chatRepository.getMessages(conversationId)
                    .filter { it.id != target.id && it.status != MessageStatus.STREAMING }
                val turns = messages.map { message ->
                    ChatTurn(
                        message = message,
                        attachments = container.attachmentRepository.contextsForMessage(
                            message.id,
                            message.content,
                        ),
                    )
                } + ChatTurn(target.copy(status = MessageStatus.COMPLETE))
                val historyImageBudget = turns.maxOfOrNull { turn ->
                    turn.attachments.filter { it.imagePaths.isNotEmpty() }
                        .maxOfOrNull { it.imageTokenBudget } ?: 0
                } ?: 0
                val loadConfiguration = container.residencyController.ensureLoaded(
                    qualityMode = mode,
                    requireVision = historyImageBudget > 0,
                    imageTokenBudget = historyImageBudget.coerceAtLeast(70),
                )
                val hiddenPrompt =
                    "Continue the immediately preceding assistant answer from exactly where it stopped. " +
                        "Do not repeat the existing text and do not add a preamble."
                val contextPlan = container.promptContextPlanner.plan(
                    conversationId = conversationId,
                    history = turns,
                    currentText = hiddenPrompt,
                    settings = settings,
                    contextTokens = loadConfiguration.contextTokens,
                    memoryEnabled = _uiState.value.memoryEnabled,
                )
                val plannedSettings = settings.copy(systemPrompt = contextPlan.systemPrompt)
                container.inferenceEngine.restoreSession(
                    conversationId,
                    contextPlan.history,
                    plannedSettings,
                )
                val continuation = StringBuilder()
                var completion: GenerationEvent.Completed? = null
                var lastSavedAt = 0L
                container.inferenceEngine.generate(
                    UserTurn(conversationId, hiddenPrompt),
                    plannedSettings,
                ).collect { event ->
                    when (event) {
                        is GenerationEvent.ThoughtDelta -> _uiState.update { state ->
                            val thinking = state.thinking
                                ?.takeIf { it.messageId == target.id }
                                ?: ThinkingUiState(target.id)
                            state.copy(
                                thinking = thinking.copy(
                                    text = thinking.text + event.text,
                                    complete = false,
                                ),
                            )
                        }
                        is GenerationEvent.AnswerDelta -> {
                            continuation.append(event.text)
                            _uiState.update { state ->
                                val thinking = state.thinking
                                if (thinking == null || thinking.text.isEmpty()) {
                                    state
                                } else {
                                    keepThinking = true
                                    state.copy(
                                        thinking = thinking.copy(
                                            complete = true,
                                            expanded = false,
                                        ),
                                    )
                                }
                            }
                            val merged = mergeContinuation(target.content, continuation.toString())
                            val now = System.currentTimeMillis()
                            if (now - lastSavedAt >= 250 || continuation.length < 40) {
                                assistant = assistant.copy(content = merged)
                                container.chatRepository.updateMessage(assistant)
                                lastSavedAt = now
                            }
                        }
                        is GenerationEvent.Completed -> completion = event
                        is GenerationEvent.Phase -> Unit
                    }
                }
                val result = completion ?: GenerationEvent.Completed(
                    GenerationStopReason.ERROR,
                    0,
                    0,
                )
                assistant = assistant.copy(
                    content = mergeContinuation(target.content, continuation.toString()),
                    status = result.reason.toMessageStatus(),
                    stopReason = result.reason,
                    continuationCount = target.continuationCount + result.continuationCount + 1,
                    generatedTokens = (target.generatedTokens ?: 0) + result.answerTokens,
                )
                container.chatRepository.updateMessage(assistant)
            } catch (error: Throwable) {
                container.chatRepository.updateMessage(
                    assistant.copy(
                        status = MessageStatus.CONTINUABLE,
                        stopReason = GenerationStopReason.ERROR,
                    ),
                )
                if (error !is CancellationException) showError(error)
            } finally {
                _uiState.update { state ->
                    state.copy(
                        isSending = false,
                        thinking = if (keepThinking) {
                            state.thinking?.copy(complete = true, expanded = false)
                        } else {
                            null
                        },
                    )
                }
                container.residencyController.endInferenceUse()
            }
        }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setMemoryEnabled(enabled) }
    }

    fun setCollectionPaused(paused: Boolean) {
        viewModelScope.launch {
            container.settings.setCollectionPaused(paused)
            if (!paused) collectFromGrantedSources(_uiState.value.phoneSourceStatuses)
        }
    }

    fun refreshPhoneSourceAccess() {
        val previous = _uiState.value.phoneSourceStatuses
        val current = container.phoneSourceAccessManager.snapshot()
        _uiState.update { it.copy(phoneSourceStatuses = current) }
        if (!_uiState.value.collectionPaused) {
            current.values
                .filter { status ->
                    status.state == PhoneSourceAccessState.GRANTED &&
                        previous[status.source]?.state != PhoneSourceAccessState.GRANTED
                }
                .forEach { OfficeWorkScheduler.collectNow(container.application, it.source) }
        }
    }

    private fun collectFromGrantedSources(statuses: Map<ActivitySource, PhoneSourceStatus>) {
        statuses.values
            .filter { it.state == PhoneSourceAccessState.GRANTED }
            .forEach { OfficeWorkScheduler.collectNow(container.application, it.source) }
    }

    fun clearCollectedSource(source: ActivitySource) {
        viewModelScope.launch {
            runCatching {
                container.memoryRepository.forgetActivitySource(source)
                container.activityRepository.deleteSource(source)
            }
                .onFailure(::showError)
        }
    }

    fun addMemory(content: String) {
        viewModelScope.launch {
            runCatching {
                container.memoryRepository.remember(
                    type = MemoryType.FACT,
                    title = content,
                    content = content,
                )
            }.onFailure(::showError)
        }
    }

    fun correctMemory(id: String, content: String) {
        viewModelScope.launch {
            runCatching { container.memoryRepository.correct(id, content) }.onFailure(::showError)
        }
    }

    fun pinMemory(id: String, pinned: Boolean) {
        viewModelScope.launch {
            runCatching { container.memoryRepository.setPinned(id, pinned) }.onFailure(::showError)
        }
    }

    fun forgetMemory(id: String) {
        viewModelScope.launch {
            runCatching { container.memoryRepository.forget(id) }.onFailure(::showError)
        }
    }

    fun exportOfficeBackup(uri: Uri, passphrase: String) {
        if (_uiState.value.backupBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(backupBusy = true, error = null) }
            runCatching {
                container.officeBackupRepository.export(uri, passphrase.toCharArray())
            }.onFailure(::showError)
            _uiState.update { it.copy(backupBusy = false) }
        }
    }

    fun selectOfficeBackup(uri: Uri?) {
        if (_uiState.value.backupBusy) return
        _uiState.update { it.copy(pendingBackupImportUri = uri, backupPreview = null) }
    }

    fun prepareOfficeImport(passphrase: String) {
        val uri = _uiState.value.pendingBackupImportUri ?: return
        if (_uiState.value.backupBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(backupBusy = true, error = null) }
            runCatching {
                container.officeBackupRepository.prepareImport(uri, passphrase.toCharArray())
            }.onSuccess { preview ->
                _uiState.update {
                    it.copy(
                        pendingBackupImportUri = null,
                        backupPreview = preview,
                    )
                }
            }.onFailure(::showError)
            _uiState.update { it.copy(backupBusy = false) }
        }
    }

    fun commitOfficeImport() {
        val preview = _uiState.value.backupPreview ?: return
        if (_uiState.value.backupBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(backupBusy = true, error = null) }
            runCatching {
                container.officeBackupRepository.commitImport(preview)
                runCatching {
                    container.memoryIndexer.rebuild(container.memoryRepository.memories.first())
                }
            }.onSuccess {
                _uiState.update { it.copy(backupPreview = null) }
            }.onFailure(::showError)
            _uiState.update { it.copy(backupBusy = false) }
        }
    }

    fun discardOfficeImport() {
        val preview = _uiState.value.backupPreview
        _uiState.update {
            it.copy(
                pendingBackupImportUri = null,
                backupPreview = null,
            )
        }
        if (preview != null) {
            viewModelScope.launch {
                runCatching { container.officeBackupRepository.discardImport(preview) }
            }
        }
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
                container.contextProfileRepository.deleteForModel(id)
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
            if (previous.temperature != settings.normalized().temperature) {
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

    fun reverifyContext() {
        viewModelScope.launch {
            runCatching { container.residencyController.reverifySelectedModel() }
                .onFailure(::showError)
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
    }

}
