package com.aliahad.aichat.ui.viewmodel

import com.aliahad.aichat.ThinkingUiState
import com.aliahad.aichat.attachment.AttachmentRepository
import com.aliahad.aichat.core.Attachment
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.AttachmentProcessingState
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.GenerationEvent
import com.aliahad.aichat.core.GenerationStopReason
import com.aliahad.aichat.core.InferenceExecutionProfile
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.core.ModelRecord
import com.aliahad.aichat.core.MultimodalRequirement
import com.aliahad.aichat.core.TurnOrigin
import com.aliahad.aichat.core.UserTurn
import com.aliahad.aichat.data.ChatRepository
import com.aliahad.aichat.inference.InferenceEngine
import com.aliahad.aichat.inference.VisionBudgetPlanner
import com.aliahad.aichat.inference.VisionDetailProfile
import com.aliahad.aichat.memory.MemoryRepository
import com.aliahad.aichat.memory.PromptContextPlanner
import com.aliahad.aichat.model.ModelConstants
import com.aliahad.aichat.model.ModelRepository
import com.aliahad.aichat.residency.ModelResidencyController
import com.aliahad.aichat.residency.ModelResidencyState
import com.aliahad.aichat.settings.AppSettingsRepository
import com.aliahad.aichat.skill.MAX_SELECTED_SKILLS
import com.aliahad.aichat.skill.SkillRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

data class ChatRunState(
    val thinking: ThinkingUiState? = null,
    val usedMemoryCount: Int = 0,
    val isSending: Boolean = false,
)

data class SendTurnRequest(
    val conversationId: String,
    val text: String,
    val attachments: List<Attachment>,
    val selectedSkillIds: List<String>,
    val memoryEnabled: Boolean,
    val conversationTemporary: Boolean,
    val origin: TurnOrigin = TurnOrigin.TYPED,
    val onDraftCommitted: () -> Unit,
)

data class ContinueTurnRequest(
    val conversationId: String,
    val target: ChatMessage,
    val memoryEnabled: Boolean,
)

/**
 * Owns the complete persisted chat-turn transaction and inference lifecycle.
 * Route state stays in [ChatViewModel]; this runner exposes only operation state.
 */
class ChatTurnRunner(
    private val chatRepository: ChatRepository,
    private val attachmentRepository: AttachmentRepository,
    private val skillRepository: SkillRepository,
    private val memoryRepository: MemoryRepository,
    private val modelRepository: ModelRepository,
    private val promptContextPlanner: PromptContextPlanner,
    private val residencyController: ModelResidencyController,
    private val inferenceEngine: InferenceEngine,
    private val settingsRepository: AppSettingsRepository,
    private val messages: UiMessageManager,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ChatRunState())
    val state: StateFlow<ChatRunState> = _state.asStateFlow()

    private var turnJob: Job? = null

    /** True while a turn is running, regardless of whether any UI is attached. */
    val isRunning: Boolean
        get() = turnJob?.isActive == true

    /**
     * Runs a turn on the process scope so it survives the Activity.
     *
     * Returns false when a turn is already in flight. That check lives here rather
     * than in the ViewModel because the ViewModel is recreated on every relaunch and
     * would otherwise have no idea a turn was still going — two turns would then
     * race on the one inference engine.
     */
    fun launchTurn(request: SendTurnRequest): Boolean {
        if (isRunning) return false
        turnJob = scope.launch { send(request) }
        return true
    }

    /** Cancels the running turn, if any. Only ever called for an explicit user stop. */
    fun cancelTurn() {
        stop()
        turnJob?.cancel()
    }

    /**
     * Cancels and waits. Needed before deleting a conversation: letting a turn write
     * a message row into a conversation being torn down is how orphaned rows appear.
     */
    suspend fun cancelTurnAndJoin() {
        stop()
        turnJob?.cancelAndJoin()
    }

    /** Resumes a CONTINUABLE reply on the same process scope as a normal turn. */
    fun launchContinue(request: ContinueTurnRequest): Boolean {
        if (isRunning) return false
        turnJob = scope.launch { continueResponse(request) }
        return true
    }

    fun toggleThinking(messageId: String) {
        _state.update { state ->
            val thinking = state.thinking?.takeIf { it.messageId == messageId }
                ?: return@update state
            state.copy(thinking = thinking.copy(expanded = !thinking.expanded))
        }
    }

    fun stop() {
        inferenceEngine.cancel()
    }

    /**
     * Runs one chat turn end to end.
     *
     * Genuinely complex (detekt measures 58): validation, attachment and audio
     * limits, memory/skill context assembly, backend selection and fallback,
     * streaming, continuation and persistence all live on this one path.
     * Suppressed rather than raising the global threshold, so the debt stays
     * visible and specific.
     *
     * Splitting it is deferred item DEBT-02 ("deduplicate send/continueResponse
     * turn lifecycle"), which the round-1 notes gate behind the plan-006
     * characterisation tests — those exist now, so the refactor is unblocked but
     * deliberately out of scope for the UX round.
     */
    @Suppress("CyclomaticComplexMethod")
    suspend fun send(request: SendTurnRequest) {
        val prompt = request.text.trim()
        val draft = request.attachments
        if (_state.value.isSending || (prompt.isEmpty() && draft.isEmpty())) return
        if (draft.any { it.state != AttachmentProcessingState.READY }) {
            messages.report("Wait for every attachment to finish processing.")
            return
        }
        val audio = draft.filter { it.kind == AttachmentKind.AUDIO }
        if (audio.size > MAX_AUDIO_ATTACHMENTS) {
            messages.report("A message can include up to $MAX_AUDIO_ATTACHMENTS audio files.")
            return
        }
        if (audio.any { it.durationMillis == null }) {
            messages.report("Audio duration is unavailable. Retry the attachment.")
            return
        }
        if (audio.sumOf { it.durationMillis ?: 0L } > MAX_AUDIO_DURATION_MILLIS) {
            messages.report("Audio attachments can total up to 90 seconds per message.")
            return
        }

        var assistant: ChatMessage? = null
        var keepThinking = false
        var inferenceUseStarted = false
        val trace = TurnTrace("send")
        val content = StringBuilder()
        val thinkingBuffer = StringBuilder()
        fun flushThinking() {
            val messageId = assistant?.id ?: return
            if (thinkingBuffer.isEmpty()) return
            _state.update { state ->
                val thinking = state.thinking?.takeIf { it.messageId == messageId }
                    ?: ThinkingUiState(messageId)
                state.copy(
                    thinking = thinking.copy(text = thinkingBuffer.toString(), complete = false),
                )
            }
        }
        try {
            val model = resolveSelectedModel() ?: return
            val visionProfile = visionDetailProfile(model)
            val settings = settingsRepository.generationSettings.first().normalized()
            val activeSkills = skillRepository.promptBlocksForSelection(
                request.selectedSkillIds.take(MAX_SELECTED_SKILLS),
            )
            trace.mark("setup")
            val previous = chatRepository.getMessages(request.conversationId)
                .filter { it.status != MessageStatus.STREAMING }
            val previousContexts = attachmentRepository.contextsForMessages(
                messageIds = previous.map { it.id },
                promptFor = { messageId -> previous.first { it.id == messageId }.content },
            )
            val previousTurns = previous.map { message ->
                ChatTurn(
                    message = message,
                    attachments = previousContexts[message.id].orEmpty(),
                )
            }
            val contexts = attachmentRepository.contexts(draft.map(Attachment::id), prompt)
            val visualCount = contexts.sumOf { it.imagePaths.size }
            val audioCount = contexts.sumOf { it.audioPaths.size }
            val audioTokenEstimate = contexts.sumOf { it.audioTokenEstimate }
            val historyHasImages = previousTurns.any { turn ->
                turn.attachments.any { it.imagePaths.isNotEmpty() }
            }
            val historyHasAudio = previousTurns.any { turn ->
                turn.attachments.any { it.audioPaths.isNotEmpty() }
            }
            val mediaRequirement = multimodalRequirement(
                hasImages = visualCount > 0 || historyHasImages,
                hasAudio = audioCount > 0 || historyHasAudio,
            )
            val allHistoryImageBudget = maxImageTokenBudget(previousTurns)
            val allHistoryAudioTokenEstimate = previousTurns.sumOf { turn ->
                turn.attachments.sumOf { it.audioTokenEstimate }
            }
            val userText = prompt.ifEmpty { attachmentOnlyPrompt(draft) }
            trace.mark("history")

            residencyController.beginInferenceUse()
            inferenceUseStarted = true
            messages.clear()
            _state.update { it.copy(isSending = true) }
            val provisionalContext =
                (residencyController.state.value as? ModelResidencyState.Ready)?.contextSize ?: 4_096
            val initialVisualBudget = VisionBudgetPlanner.allocate(
                visionProfile,
                contexts,
                userText,
                provisionalContext - settings.maxNewTokens -
                    allHistoryAudioTokenEstimate - audioTokenEstimate - 256,
            )
            val loadConfiguration = residencyController.ensureLoaded(
                requirement = mediaRequirement,
                imageTokenBudget = maxOf(initialVisualBudget, allHistoryImageBudget),
            )
            trace.mark("load")
            val planningContextTokens = loadConfiguration.contextTokens - allHistoryAudioTokenEstimate
            require(planningContextTokens > 512) {
                "Audio history cannot fit in the active context. Start a new conversation."
            }
            val contextPlan = promptContextPlanner.plan(
                conversationId = request.conversationId,
                history = previousTurns,
                currentText = userText + contexts.joinToString { it.extractedText },
                settings = settings,
                contextTokens = planningContextTokens,
                memoryEnabled = request.memoryEnabled && !request.conversationTemporary,
                skillBlocks = activeSkills,
            )
            trace.mark("plan")
            val plannedTurns = contextPlan.history
            val historyImageBudget = maxImageTokenBudget(plannedTurns)
            val historyAudioTokenEstimate = plannedTurns.sumOf { turn ->
                turn.attachments.sumOf { it.audioTokenEstimate }
            }
            val availableVisualTokens = loadConfiguration.contextTokens -
                contextPlan.estimatedTokens - contextPlan.outputReserveTokens -
                historyAudioTokenEstimate - audioTokenEstimate - 192
            require(availableVisualTokens >= 0) {
                "Audio and text cannot fit in the active context. Remove audio or shorten the message."
            }
            val visualBudget = VisionBudgetPlanner.allocate(
                visionProfile,
                contexts,
                userText,
                availableVisualTokens,
            )
            if (visualCount > 0 && visualBudget != initialVisualBudget) {
                residencyController.ensureLoaded(
                    requirement = mediaRequirement,
                    imageTokenBudget = maxOf(visualBudget, historyImageBudget),
                )
            }
            val adjustedContexts = contexts.map { it.copy(imageTokenBudget = visualBudget) }
            trace.mark("budget")
            val user = chatRepository.addMessage(
                request.conversationId,
                MessageRole.USER,
                userText,
                origin = request.origin,
            )
            skillRepository.recordInvocation(user.id, activeSkills)
            if (settingsRepository.memoryEnabled.first()) {
                memoryRepository.rememberMessage(user, request.conversationTemporary)
                if (!request.conversationTemporary) {
                    contexts.forEach { attachmentContext ->
                        val memoryContent =
                            attachmentMemoryContent(attachmentContext.extractedText)
                                ?: return@forEach
                        memoryRepository.rememberAttachment(
                            attachmentId = attachmentContext.attachmentId,
                            displayName = attachmentContext.displayName,
                            content = memoryContent,
                        )
                    }
                }
            }
            attachmentRepository.bind(
                user.id,
                request.conversationId,
                draft.map(Attachment::id),
                visualBudget.takeIf { visualCount > 0 },
            )
            request.onDraftCommitted()
            trace.mark("persist-user")
            assistant = chatRepository.addMessage(
                request.conversationId,
                MessageRole.ASSISTANT,
                "",
                MessageStatus.STREAMING,
                request.origin,
            )
            _state.update {
                it.copy(
                    thinking = if (settings.thinkingEnabled) {
                        ThinkingUiState(requireNotNull(assistant).id)
                    } else {
                        null
                    },
                    usedMemoryCount = contextPlan.memories.size,
                )
            }
            trace.mark("persist-assistant")
            val plannedSettings = settings.copy(systemPrompt = contextPlan.systemPrompt)
            inferenceEngine.restoreSession(request.conversationId, plannedTurns, plannedSettings)
            trace.mark("restore")
            if (visualCount > 0 && historyImageBudget > visualBudget) {
                residencyController.ensureLoaded(mediaRequirement, maxOf(visualBudget, historyImageBudget))
            }
            var lastSavedAt = 0L
            var lastThinkingFlushAt = 0L
            var completion: GenerationEvent.Completed? = null
            inferenceEngine.generate(
                UserTurn(
                    conversationId = request.conversationId,
                    text = user.content,
                    preamble = contextPlan.turnPreamble,
                    attachments = adjustedContexts,
                ),
                plannedSettings,
                InferenceExecutionProfile.NORMAL,
            ).collect { event ->
                when (event) {
                    is GenerationEvent.ThoughtDelta -> {
                        trace.firstToken()
                        val messageId = assistant?.id ?: return@collect
                        thinkingBuffer.append(event.text)
                        val now = System.currentTimeMillis()
                        if (now - lastThinkingFlushAt >= 100 || thinkingBuffer.length < 64) {
                            lastThinkingFlushAt = now
                            _state.update { state ->
                                val thinking = state.thinking?.takeIf { it.messageId == messageId }
                                    ?: ThinkingUiState(messageId)
                                state.copy(
                                    thinking = thinking.copy(
                                        text = thinkingBuffer.toString(),
                                        complete = false,
                                    ),
                                )
                            }
                        }
                    }
                    is GenerationEvent.AnswerDelta -> {
                        trace.firstToken()
                        content.append(event.text)
                        _state.update { state ->
                            val thinking = state.thinking
                            if (thinking == null || thinking.text.isEmpty()) state else {
                                keepThinking = true
                                state.copy(
                                    thinking = thinking.copy(complete = true, expanded = false),
                                )
                            }
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastSavedAt >= 250 || content.length < 40) {
                            assistant?.copy(content = content.toString())?.let { updated ->
                                assistant = updated
                                chatRepository.updateMessage(updated)
                            }
                            lastSavedAt = now
                        }
                    }
                    is GenerationEvent.BackendFallback -> if (event.discardPartialOutput) {
                        content.setLength(0)
                        thinkingBuffer.setLength(0)
                        lastSavedAt = 0L
                        lastThinkingFlushAt = 0L
                        keepThinking = false
                        assistant?.copy(content = "")?.let { reset ->
                            assistant = reset
                            chatRepository.updateMessage(reset)
                            _state.update {
                                it.copy(
                                    thinking = if (settings.thinkingEnabled) {
                                        ThinkingUiState(reset.id)
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                    is GenerationEvent.Completed -> completion = event
                    is GenerationEvent.Phase -> if (event.state is InferenceState.Generating) {
                        trace.mark("prefill")
                    }
                }
            }
            flushThinking()
            val result = completion ?: GenerationEvent.Completed(
                GenerationStopReason.ERROR,
                0,
                0,
            )
            assistant?.copy(
                content = content.toString().ifEmpty {
                    if (plannedSettings.thinkingEnabled && result.reason == GenerationStopReason.EOG) {
                        "The model finished thinking without producing a final answer."
                    } else {
                        ""
                    }
                },
                status = if (
                    content.isEmpty() && plannedSettings.thinkingEnabled &&
                    result.reason == GenerationStopReason.EOG
                ) MessageStatus.ERROR else result.reason.toMessageStatus(),
                stopReason = result.reason,
                continuationCount = result.continuationCount,
                promptTokens = contextPlan.estimatedTokens,
                generatedTokens = result.answerTokens,
            )?.let { completed ->
                assistant = completed
                chatRepository.updateMessage(completed)
            }
        } catch (cancelled: CancellationException) {
            assistant?.let {
                chatRepository.updateMessage(
                    it.copy(
                        content = content.toString().ifEmpty { it.content },
                        status = MessageStatus.CANCELLED,
                        stopReason = GenerationStopReason.CANCELLED,
                    ),
                )
            }
            throw cancelled
        } catch (error: Throwable) {
            assistant?.let {
                chatRepository.updateMessage(
                    it.copy(
                        content = content.toString().ifEmpty {
                            it.content.ifEmpty { "Generation failed: ${error.message}" }
                        },
                        status = MessageStatus.ERROR,
                    ),
                )
            }
            messages.report(error)
        } finally {
            flushThinking()
            _state.update { state ->
                state.copy(
                    isSending = false,
                    thinking = if (keepThinking) {
                        state.thinking?.copy(complete = true, expanded = false)
                    } else {
                        null
                    },
                )
            }
            if (inferenceUseStarted) residencyController.endInferenceUse()
        }
    }

    suspend fun continueResponse(request: ContinueTurnRequest) {
        if (_state.value.isSending) return
        val target = request.target
        var assistant = target.copy(status = MessageStatus.STREAMING)
        var keepThinking = false
        var inferenceUseStarted = false
        val continuation = StringBuilder()
        val thinkingBuffer = StringBuilder()
        fun flushThinking() {
            if (thinkingBuffer.isEmpty()) return
            _state.update { state ->
                val thinking = state.thinking?.takeIf { it.messageId == target.id }
                    ?: ThinkingUiState(target.id)
                state.copy(
                    thinking = thinking.copy(text = thinkingBuffer.toString(), complete = false),
                )
            }
        }
        try {
            val settings = settingsRepository.generationSettings.first().normalized()
            resolveSelectedModel() ?: return
            residencyController.beginInferenceUse()
            inferenceUseStarted = true
            messages.clear()
            _state.update {
                it.copy(
                    isSending = true,
                    thinking = if (settings.thinkingEnabled) ThinkingUiState(target.id) else null,
                )
            }
            chatRepository.updateMessage(assistant)
            val messages = chatRepository.getMessages(request.conversationId)
                .filter { it.id != target.id && it.status != MessageStatus.STREAMING }
            val sourceUser = messages.lastOrNull { it.role == MessageRole.USER }
            val activeSkills = sourceUser?.let { skillRepository.blocksForMessage(it.id) }.orEmpty()
            val turns = messages.let { history ->
                val turnContexts = attachmentRepository.contextsForMessages(
                    messageIds = history.map { it.id },
                    promptFor = { messageId -> history.first { it.id == messageId }.content },
                )
                history.map { message ->
                    ChatTurn(message, turnContexts[message.id].orEmpty())
                }
            } + ChatTurn(target.copy(status = MessageStatus.COMPLETE))
            val historyImageBudget = maxImageTokenBudget(turns)
            val historyHasImages = turns.any { turn ->
                turn.attachments.any { it.imagePaths.isNotEmpty() }
            }
            val historyHasAudio = turns.any { turn ->
                turn.attachments.any { it.audioPaths.isNotEmpty() }
            }
            val historyAudioTokenEstimate = turns.sumOf { turn ->
                turn.attachments.sumOf { it.audioTokenEstimate }
            }
            val loadConfiguration = residencyController.ensureLoaded(
                multimodalRequirement(historyHasImages, historyHasAudio),
                historyImageBudget.coerceAtLeast(70),
            )
            val planningContextTokens = loadConfiguration.contextTokens - historyAudioTokenEstimate
            require(planningContextTokens > 512) {
                "Audio history cannot fit in the active context. Start a new conversation."
            }
            val hiddenPrompt =
                "Continue the immediately preceding assistant answer from exactly where it stopped. " +
                    "Do not repeat the existing text and do not add a preamble."
            val contextPlan = promptContextPlanner.plan(
                conversationId = request.conversationId,
                history = turns,
                currentText = hiddenPrompt,
                settings = settings,
                contextTokens = planningContextTokens,
                memoryEnabled = request.memoryEnabled,
                skillBlocks = activeSkills,
            )
            val plannedSettings = settings.copy(systemPrompt = contextPlan.systemPrompt)
            inferenceEngine.restoreSession(
                request.conversationId,
                contextPlan.history,
                plannedSettings,
            )
            var completion: GenerationEvent.Completed? = null
            var lastSavedAt = 0L
            var lastThinkingFlushAt = 0L
            inferenceEngine.generate(
                UserTurn(
                    request.conversationId,
                    hiddenPrompt,
                    preamble = contextPlan.turnPreamble,
                ),
                plannedSettings,
            ).collect { event ->
                when (event) {
                    is GenerationEvent.ThoughtDelta -> {
                        thinkingBuffer.append(event.text)
                        val now = System.currentTimeMillis()
                        if (now - lastThinkingFlushAt >= 100 || thinkingBuffer.length < 64) {
                            lastThinkingFlushAt = now
                            _state.update { state ->
                                val thinking = state.thinking?.takeIf { it.messageId == target.id }
                                    ?: ThinkingUiState(target.id)
                                state.copy(
                                    thinking = thinking.copy(
                                        text = thinkingBuffer.toString(),
                                        complete = false,
                                    ),
                                )
                            }
                        }
                    }
                    is GenerationEvent.AnswerDelta -> {
                        continuation.append(event.text)
                        _state.update { state ->
                            val thinking = state.thinking
                            if (thinking == null || thinking.text.isEmpty()) state else {
                                keepThinking = true
                                state.copy(
                                    thinking = thinking.copy(complete = true, expanded = false),
                                )
                            }
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastSavedAt >= 250 || continuation.length < 40) {
                            // Overlap dedup scans the accumulated continuation; only pay
                            // it at save boundaries, not on every streamed delta.
                            assistant = assistant.copy(
                                content = mergeContinuation(target.content, continuation.toString()),
                            )
                            chatRepository.updateMessage(assistant)
                            lastSavedAt = now
                        }
                    }
                    is GenerationEvent.BackendFallback -> if (event.discardPartialOutput) {
                        continuation.setLength(0)
                        thinkingBuffer.setLength(0)
                        lastSavedAt = 0L
                        lastThinkingFlushAt = 0L
                        keepThinking = false
                        assistant = target.copy(status = MessageStatus.STREAMING)
                        chatRepository.updateMessage(assistant)
                        _state.update {
                            it.copy(
                                thinking = if (settings.thinkingEnabled) {
                                    ThinkingUiState(target.id)
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    is GenerationEvent.Completed -> completion = event
                    is GenerationEvent.Phase -> Unit
                }
            }
            flushThinking()
            val result = completion ?: GenerationEvent.Completed(GenerationStopReason.ERROR, 0, 0)
            assistant = assistant.copy(
                content = mergeContinuation(target.content, continuation.toString()),
                status = result.reason.toMessageStatus(),
                stopReason = result.reason,
                continuationCount = target.continuationCount + result.continuationCount + 1,
                generatedTokens = (target.generatedTokens ?: 0) + result.answerTokens,
            )
            chatRepository.updateMessage(assistant)
        } catch (cancelled: CancellationException) {
            chatRepository.updateMessage(
                assistant.copy(
                    content = mergeContinuation(target.content, continuation.toString()),
                    status = MessageStatus.CONTINUABLE,
                    stopReason = GenerationStopReason.CANCELLED,
                ),
            )
            throw cancelled
        } catch (error: Throwable) {
            chatRepository.updateMessage(
                assistant.copy(
                    content = mergeContinuation(target.content, continuation.toString()),
                    status = MessageStatus.CONTINUABLE,
                    stopReason = GenerationStopReason.ERROR,
                ),
            )
            messages.report(error)
        } finally {
            flushThinking()
            _state.update { state ->
                state.copy(
                    isSending = false,
                    thinking = if (keepThinking) {
                        state.thinking?.copy(complete = true, expanded = false)
                    } else {
                        null
                    },
                )
            }
            if (inferenceUseStarted) residencyController.endInferenceUse()
        }
    }

    private suspend fun resolveSelectedModel(): ModelRecord? {
        modelRepository.selectedModel()?.takeIf {
            it.status == DownloadStatus.READY && it.localPath != null
        }?.let { return it }
        val ready = modelRepository.models.first().firstOrNull {
            it.status == DownloadStatus.READY && it.localPath != null
        }
        if (ready != null) {
            modelRepository.selectModel(ready.id)
            return ready.copy(selected = true)
        }
        messages.report("Download Gemma 4 E4B in Settings first.")
        return null
    }

    private fun visionDetailProfile(@Suppress("UNUSED_PARAMETER") model: ModelRecord) =
        VisionDetailProfile.MOBILE

    private fun attachmentOnlyPrompt(attachments: List<Attachment>) =
        "Describe and analyze ${attachments.joinToString { it.displayName }}."

    private fun multimodalRequirement(hasImages: Boolean, hasAudio: Boolean) = when {
        hasImages && hasAudio -> MultimodalRequirement.BOTH
        hasImages -> MultimodalRequirement.VISION
        hasAudio -> MultimodalRequirement.AUDIO
        else -> MultimodalRequirement.NONE
    }

    companion object {
        const val MAX_AUDIO_ATTACHMENTS = 3
        const val MAX_AUDIO_DURATION_MILLIS = 90_000L
    }
}

/**
 * Largest per-image token budget any turn in [turns] was rendered with, so a
 * reload keeps history images at the detail they were already encoded at.
 */
private fun maxImageTokenBudget(turns: List<ChatTurn>): Int =
    turns.maxOfOrNull { turn ->
        turn.attachments.filter { it.imagePaths.isNotEmpty() }
            .maxOfOrNull { it.imageTokenBudget } ?: 0
    } ?: 0

private fun GenerationStopReason.toMessageStatus(): MessageStatus = when (this) {
    GenerationStopReason.EOG -> MessageStatus.COMPLETE
    GenerationStopReason.TOKEN_LIMIT,
    GenerationStopReason.CONTEXT_LIMIT,
    GenerationStopReason.PROCESS_DEATH -> MessageStatus.CONTINUABLE
    GenerationStopReason.CANCELLED -> MessageStatus.CANCELLED
    GenerationStopReason.DECODE_ERROR,
    GenerationStopReason.ERROR,
    GenerationStopReason.REPETITION -> MessageStatus.ERROR
}

internal fun mergeContinuation(existing: String, continuation: String): String {
    if (existing.isEmpty() || continuation.isEmpty()) return existing + continuation
    val maximum = minOf(existing.length, continuation.length, 320)
    for (overlap in maximum downTo 12) {
        if (existing.regionMatches(existing.length - overlap, continuation, 0, overlap)) {
            return existing + continuation.drop(overlap)
        }
    }
    return existing + continuation
}

/** Hard cap for attachment text ingested into memory in a single entry. */
internal const val MAX_ATTACHMENT_MEMORY_CHARS = 4_000

/**
 * Normalizes extracted attachment text for memory ingestion. Blank extractions
 * (images, audio, unsupported formats) are skipped and long documents are
 * capped so a single attachment cannot flood the memory store.
 */
internal fun attachmentMemoryContent(extractedText: String): String? {
    val trimmed = extractedText.trim()
    if (trimmed.isBlank()) return null
    return trimmed.take(MAX_ATTACHMENT_MEMORY_CHARS)
}
