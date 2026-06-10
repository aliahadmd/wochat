package com.aliahad.aichat.inference

import android.content.Context
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.InferenceMetrics
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.ModelCapabilities
import com.aliahad.aichat.core.UserTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

class NativeInferenceEngine(
    context: Context,
) : InferenceEngine {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val _state = MutableStateFlow<InferenceState>(InferenceState.Uninitialized)
    override val state: StateFlow<InferenceState> = _state.asStateFlow()
    private val _metrics = MutableStateFlow(InferenceMetrics())
    override val metrics: StateFlow<InferenceMetrics> = _metrics.asStateFlow()

    @Volatile private var cancelled = false
    override var loadedModelPath: String? = null
        private set
    override var loadedProjectorPath: String? = null
        private set
    private var loadedModelName: String? = null
    private var loadedBackend = BackendMode.CPU
    private var activeConversationId: String? = null

    init {
        System.loadLibrary("aichat-native")
        nativeInit(context.applicationInfo.nativeLibraryDir)
        _state.value = InferenceState.Idle
    }

    override suspend fun loadModel(
        path: String,
        displayName: String,
        backend: BackendMode,
        settings: GenerationSettings,
    ) = withContext(dispatcher) {
        val selected = BackendMode.CPU
        _state.value = InferenceState.Loading(displayName)
        val mark = TimeSource.Monotonic.markNow()
        val error = nativeLoad(
            path,
            selected.nativeCode,
            settings.contextSize,
            settings.temperature,
        )
        if (error != null) {
            _state.value = InferenceState.Error(error)
            error(error)
        }
        loadedBackend = selected
        loadedModelPath = path
        loadedModelName = displayName
        loadedProjectorPath = null
        activeConversationId = null
        _metrics.value = _metrics.value.copy(
            modelLoadMillis = mark.elapsedNow().inWholeMilliseconds,
        )
        _state.value = InferenceState.Ready(displayName, loadedBackend)
    }

    override suspend fun loadProjector(
        path: String,
        imageTokenBudget: Int,
    ): ModelCapabilities = withContext(dispatcher) {
        nativeLoadProjector(path, imageTokenBudget)?.let { error(it) }
        loadedProjectorPath = path
        val flags = nativeCapabilities()
        ModelCapabilities(vision = flags and 1 != 0, audio = flags and 2 != 0)
    }

    override suspend fun unloadProjector() = withContext(dispatcher) {
        nativeUnloadProjector()
        loadedProjectorPath = null
        activeConversationId = null
    }

    override suspend fun restoreSession(
        conversationId: String,
        history: List<ChatTurn>,
        settings: GenerationSettings,
    ) = withContext(dispatcher) {
        check(loadedModelPath != null) { "Load a model first" }
        if (activeConversationId == conversationId) return@withContext
        _state.value = InferenceState.PreparingHistory
        val mark = TimeSource.Monotonic.markNow()
        val trimmedMessages = HistoryTrimmer.trim(history.map(ChatTurn::message), settings.contextSize)
        val retainedIds = trimmedMessages.map(ChatMessage::id).toSet()
        val trimmed = history.filter { it.message.id in retainedIds }
        val prompt = if (settings.thinkingEnabled) {
            "${settings.systemPrompt}\nUse your internal reasoning before answering."
        } else {
            "${settings.systemPrompt}\nAnswer directly without displaying hidden reasoning."
        }
        nativeRestore(prompt, emptyArray(), emptyArray(), settings.thinkingEnabled)?.let {
            _state.value = InferenceState.Error(it)
            error(it)
        }
        trimmed.forEach { turn ->
            val mediaPaths = turn.attachments.flatMap { it.imagePaths }
            val content = turn.withAttachmentText()
            val error = if (turn.message.role == MessageRole.USER && mediaPaths.isNotEmpty()) {
                nativeAppendHistoryMedia(turn.message.role.nativeRole, content, mediaPaths.toTypedArray())
            } else {
                nativeAppendHistoryText(turn.message.role.nativeRole, content)
            }
            error?.let {
                _state.value = InferenceState.Error(it)
                error(it)
            }
        }
        activeConversationId = conversationId
        _metrics.value = _metrics.value.copy(
            historyRestoreMillis = mark.elapsedNow().inWholeMilliseconds,
        )
        _state.value = InferenceState.Ready(requireNotNull(loadedModelName), loadedBackend)
    }

    override fun generate(turn: UserTurn, settings: GenerationSettings): Flow<String> = flow {
        check(state.value is InferenceState.Ready) { "Model is not ready" }
        check(activeConversationId == turn.conversationId) { "Restore this conversation before generating" }
        cancelled = false
        val mediaPaths = turn.attachments.flatMap { it.imagePaths }
        _state.value = if (mediaPaths.isEmpty()) {
            InferenceState.EvaluatingPrompt
        } else {
            InferenceState.EncodingMedia
        }
        val promptMark = TimeSource.Monotonic.markNow()
        val preparedPrompt = turn.withAttachmentText()
        val beginError = if (mediaPaths.isEmpty()) {
            nativeBeginUserPrompt(preparedPrompt, settings.maxNewTokens)
        } else {
            nativeBeginUserTurn(preparedPrompt, mediaPaths.toTypedArray(), settings.maxNewTokens)
        }
        beginError?.let { error(it) }
        _metrics.value = _metrics.value.copy(
            promptEvaluationMillis = promptMark.elapsedNow().inWholeMilliseconds,
            firstTokenMillis = null,
        )
        _state.value = InferenceState.Generating
        val firstTokenMark = TimeSource.Monotonic.markNow()
        var emittedFirstToken = false
        try {
            while (!cancelled) {
                val token = nativeNextToken() ?: break
                if (token.isNotEmpty()) {
                    if (!emittedFirstToken) {
                        emittedFirstToken = true
                        _metrics.value = _metrics.value.copy(
                            firstTokenMillis = firstTokenMark.elapsedNow().inWholeMilliseconds,
                        )
                    }
                    emit(token)
                }
            }
        } catch (cancel: CancellationException) {
            cancelled = true
            throw cancel
        } catch (error: Throwable) {
            activeConversationId = null
            _state.value = InferenceState.Error(error.message ?: "Generation failed")
            throw error
        } finally {
            nativeFinishGeneration()
            nativeReleaseModelPages()
            if (_state.value !is InferenceState.Error) {
                _state.value = InferenceState.Ready(requireNotNull(loadedModelName), loadedBackend)
            }
        }
    }.flowOn(dispatcher)

    override fun cancel() {
        cancelled = true
    }

    override suspend fun unload() = withContext(dispatcher) {
        cancelled = true
        nativeUnload()
        loadedModelPath = null
        loadedModelName = null
        loadedProjectorPath = null
        activeConversationId = null
        _metrics.value = InferenceMetrics()
        _state.value = InferenceState.Idle
    }

    override suspend fun benchmark(
        path: String,
        displayName: String,
        settings: GenerationSettings,
    ): Map<BackendMode, Double> = withContext(dispatcher) {
        val results = linkedMapOf<BackendMode, Double>()
        for (backend in listOf(BackendMode.CPU, BackendMode.VULKAN)) {
            val error = nativeLoad(path, backend.nativeCode, 1024, settings.temperature)
            if (error != null) continue
            nativeRestore("Answer briefly.", emptyArray(), emptyArray(), false)
            nativeBeginUserPrompt("Reply with one word.", 8)
            val mark = TimeSource.Monotonic.markNow()
            var tokens = 0
            while (nativeNextToken() != null) tokens++
            val seconds = mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1) / 1000.0
            results[backend] = tokens / seconds
            nativeUnload()
        }
        _state.value = InferenceState.Idle
        results
    }

    override fun systemInfo(): String = nativeSystemInfo()

    override suspend fun countTokens(text: String): Int = withContext(dispatcher) {
        nativeCountTokens(text).coerceAtLeast(0)
    }

    override fun destroy() {
        cancelled = true
        nativeShutdown()
    }

    private external fun nativeInit(nativeLibDir: String)
    private external fun nativeLoad(
        modelPath: String,
        backend: Int,
        contextSize: Int,
        temperature: Float,
    ): String?
    private external fun nativeRestore(
        systemPrompt: String,
        roles: Array<String>,
        contents: Array<String>,
        thinkingEnabled: Boolean,
    ): String?
    private external fun nativeLoadProjector(path: String, imageTokenBudget: Int): String?
    private external fun nativeUnloadProjector()
    private external fun nativeCapabilities(): Int
    private external fun nativeAppendHistoryText(role: String, content: String): String?
    private external fun nativeAppendHistoryMedia(
        role: String,
        content: String,
        paths: Array<String>,
    ): String?
    private external fun nativeBeginUserPrompt(prompt: String, maxTokens: Int): String?
    private external fun nativeBeginUserTurn(
        prompt: String,
        paths: Array<String>,
        maxTokens: Int,
    ): String?
    private external fun nativeCountTokens(text: String): Int
    private external fun nativeNextToken(): String?
    private external fun nativeFinishGeneration()
    private external fun nativeUnload()
    private external fun nativeReleaseModelPages()
    private external fun nativeSystemInfo(): String
    private external fun nativeShutdown()

}

private fun UserTurn.withAttachmentText(): String = buildString {
    attachments.filter { it.extractedText.isNotBlank() }.forEach {
        append("\n\n<attachment name=\"")
        append(it.displayName)
        append("\">\n")
        append(it.extractedText)
        append("\n</attachment>")
    }
    if (isNotEmpty()) {
        append("\n\nUse the attachment labels as sources and cite relevant claims as [filename, page N] when a page is available.")
        append("\n\n")
    }
    append(text)
}

private fun ChatTurn.withAttachmentText(): String = UserTurn(
    conversationId = message.conversationId,
    text = message.content,
    attachments = attachments,
).withAttachmentText()

private val BackendMode.nativeCode: Int
    get() = when (this) {
        BackendMode.AUTO -> 0
        BackendMode.CPU -> 1
        BackendMode.VULKAN -> 2
    }

private val MessageRole.nativeRole: String
    get() = when (this) {
        MessageRole.SYSTEM -> "system"
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
    }
