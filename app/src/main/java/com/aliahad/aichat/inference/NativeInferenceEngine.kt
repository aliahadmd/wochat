package com.aliahad.aichat.inference

import android.content.Context
import android.os.PowerManager
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.BackendFailureStage
import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.GenerationEvent
import com.aliahad.aichat.core.GenerationStopReason
import com.aliahad.aichat.core.InferenceMetrics
import com.aliahad.aichat.core.InferenceExecutionProfile
import com.aliahad.aichat.core.InferenceBenchmarkSample
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.ModelCapabilities
import com.aliahad.aichat.core.ModelLoadConfiguration
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
    private val nativeBackend: BackendMode = BackendMode.CPU,
) : InferenceEngine {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val _state = MutableStateFlow<InferenceState>(InferenceState.Uninitialized)
    override val state: StateFlow<InferenceState> = _state.asStateFlow()
    private val _metrics = MutableStateFlow(InferenceMetrics())
    override val metrics: StateFlow<InferenceMetrics> = _metrics.asStateFlow()

    // HyperOS freezes the process shortly after the screen turns off, even with the
    // residency foreground service running, which stalls long restores and
    // generations until the app is foregrounded again. Hold the CPU only while
    // native work runs, with a timeout so a wedged call cannot drain the battery.
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aichat:inference")
        .apply { setReferenceCounted(false) }

    @Volatile private var cancelled = false
    override var loadedModelPath: String? = null
        private set
    override var loadedProjectorPath: String? = null
        private set
    override var loadedCapabilities: ModelCapabilities? = null
        private set
    override var modelContextLimit: Int = 0
        private set
    override var activeContextSize: Int = 0
        private set
    private var loadedModelName: String? = null
    private var loadedBackend = BackendMode.CPU
    private var activeConversationId: String? = null
    private var activeRestoreFingerprint: String? = null

    init {
        require(nativeBackend != BackendMode.AUTO) { "A native engine must use one concrete backend." }
        System.loadLibrary("aichat-native")
        nativeInit(context.applicationInfo.nativeLibraryDir, nativeBackend.nativeCode)
        _state.value = InferenceState.Idle
    }

    private fun holdCpu() {
        if (!wakeLock.isHeld) wakeLock.acquire(30 * 60 * 1000L)
    }

    private fun releaseCpu() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    override suspend fun loadModel(
        path: String,
        displayName: String,
        configuration: ModelLoadConfiguration,
    ) = withContext(dispatcher) {
        val selected = configuration.backend.let { if (it == BackendMode.AUTO) nativeBackend else it }
        require(selected == nativeBackend) {
            "$nativeBackend engine cannot load the $selected backend."
        }
        _state.value = InferenceState.Loading(displayName)
        val mark = TimeSource.Monotonic.markNow()
        holdCpu()
        val loadError = try {
            nativeLoad(
                path,
                selected.nativeCode,
                configuration.contextTokens,
                configuration.temperature,
            )
        } finally {
            releaseCpu()
        }
        if (loadError != null) {
            // The native side has already unloaded the previous model before this
            // load attempt failed; mirror unload() so callers never see stale state.
            loadedModelPath = null
            loadedModelName = null
            loadedProjectorPath = null
            loadedCapabilities = null
            modelContextLimit = 0
            activeContextSize = 0
            activeConversationId = null
            _state.value = InferenceState.Error(loadError)
            throw BackendInferenceException(selected, BackendFailureStage.LOAD, loadError)
        }
        loadedBackend = selected
        loadedModelPath = path
        loadedModelName = displayName
        modelContextLimit = nativeModelContextLimit()
        activeContextSize = nativeCurrentContextSize()
        loadedProjectorPath = null
        loadedCapabilities = null
        activeConversationId = null
        activeRestoreFingerprint = null
        _metrics.value = _metrics.value.copy(
            modelLoadMillis = mark.elapsedNow().inWholeMilliseconds,
        )
        _state.value = InferenceState.Ready(displayName, loadedBackend)
    }

    override suspend fun loadProjector(
        path: String,
        imageTokenBudget: Int,
    ): ModelCapabilities = withContext(dispatcher) {
        holdCpu()
        try {
            nativeLoadProjector(path, imageTokenBudget)?.let {
                throw BackendInferenceException(loadedBackend, BackendFailureStage.PROJECTOR, it)
            }
        } finally {
            releaseCpu()
        }
        loadedProjectorPath = path
        val flags = nativeCapabilities()
        ModelCapabilities(
            vision = flags and 1 != 0,
            audio = flags and 2 != 0,
            contextLimit = modelContextLimit,
        ).also { loadedCapabilities = it }
    }

    override suspend fun unloadProjector() = withContext(dispatcher) {
        nativeUnloadProjector()
        loadedProjectorPath = null
        loadedCapabilities = null
        activeConversationId = null
        activeRestoreFingerprint = null
    }

    override suspend fun restoreSession(
        conversationId: String,
        history: List<ChatTurn>,
        settings: GenerationSettings,
    ) = withContext(dispatcher) {
        check(loadedModelPath != null) { "Load a model first" }
        // Each turn re-plans the system prompt and history; skip the expensive
        // native replay only when the exact restore inputs are unchanged.
        val fingerprint = restoreFingerprint(conversationId, settings, history)
        if (shouldSkipRestore(activeConversationId, activeRestoreFingerprint, conversationId, fingerprint)) {
            return@withContext
        }
        // The native session may be cleared below; a failed restore must not leave
        // the previous conversation marked active against the new conversation's
        // KV cache. Remember which conversation it held so the reuse check knows
        // whether the cache belongs to this conversation at all.
        val previousConversationId = activeConversationId
        activeConversationId = null
        activeRestoreFingerprint = null
        _state.value = InferenceState.PreparingHistory
        holdCpu()
        val mark = TimeSource.Monotonic.markNow()
        val prompt = if (settings.thinkingEnabled) {
            "${settings.systemPrompt}\nUse your internal reasoning before answering."
        } else {
            "${settings.systemPrompt}\nAnswer directly without displaying hidden reasoning."
        }
        // A follow-up turn is almost always the previous turn's history plus the
        // exchange just finished, which the live KV cache already holds. Ask the
        // session how much of it is still valid and decode only the remainder;
        // rebuilding instead cost 29.3 s for 764 tokens on every single turn.
        val reusablePrefix = if (previousConversationId == conversationId) {
            nativeSessionPrefixLength(
                prompt,
                settings.thinkingEnabled,
                history.map { it.message.role.nativeRole }.toTypedArray(),
                history.map { it.withAttachmentText() }.toTypedArray(),
            )
        } else {
            REBUILD_SESSION
        }
        try {
            if (reusablePrefix == REBUILD_SESSION) {
                nativeRestore(prompt, emptyArray(), emptyArray(), settings.thinkingEnabled)?.let {
                    _state.value = InferenceState.Error(it)
                    throw BackendInferenceException(loadedBackend, BackendFailureStage.RESTORE, it)
                }
            }
            val pending = if (reusablePrefix == REBUILD_SESSION) history else history.drop(reusablePrefix)
            pending.forEach { turn ->
                val mediaPaths = turn.attachments.flatMap { it.mediaPaths }
                val content = turn.withAttachmentText()
                val error = if (turn.message.role == MessageRole.USER && mediaPaths.isNotEmpty()) {
                    nativeAppendHistoryMedia(turn.message.role.nativeRole, content, mediaPaths.toTypedArray())
                } else {
                    nativeAppendHistoryText(turn.message.role.nativeRole, content)
                }
                error?.let {
                    _state.value = InferenceState.Error(it)
                    throw BackendInferenceException(loadedBackend, BackendFailureStage.RESTORE, it)
                }
            }
        } finally {
            // Deliberately does NOT release the model's file-backed pages here.
            // Doing so used to MADV_DONTNEED the whole ~4.9 GB mapping after every
            // restore, so the next turn had to fault all of it back from storage
            // *while* prefilling — measured at 21.8 tok/s (730 prompt tokens in
            // 33.4 s) with RssFile cycling 140 MB -> 2.4 GB -> 140 MB per turn.
            // Pages are now surrendered only on real pressure, via onTrimMemory.
            releaseCpu()
        }
        activeConversationId = conversationId
        activeRestoreFingerprint = fingerprint
        _metrics.value = _metrics.value.copy(
            historyRestoreMillis = mark.elapsedNow().inWholeMilliseconds,
        )
        _state.value = InferenceState.Ready(requireNotNull(loadedModelName), loadedBackend)
    }

    override fun generate(
        turn: UserTurn,
        settings: GenerationSettings,
        profile: InferenceExecutionProfile,
    ): Flow<GenerationEvent> = flow {
        check(state.value is InferenceState.Ready) { "Model is not ready" }
        check(activeConversationId == turn.conversationId) { "Restore this conversation before generating" }
        cancelled = false
        holdCpu()
        try {
            val mediaPaths = turn.attachments.flatMap { it.mediaPaths }
            _state.value = if (mediaPaths.isEmpty()) {
                InferenceState.EvaluatingPrompt
            } else {
                InferenceState.EncodingMedia
            }
            emit(GenerationEvent.Phase(_state.value))
            val promptMark = TimeSource.Monotonic.markNow()
            val preparedPrompt = turn.withAttachmentText()
            val beginError = if (mediaPaths.isEmpty()) {
                nativeBeginUserPrompt(
                    turn.preamble + preparedPrompt,
                    preparedPrompt,
                    settings.maxNewTokens,
                )
            } else {
                nativeBeginUserTurn(preparedPrompt, mediaPaths.toTypedArray(), settings.maxNewTokens)
            }
            beginError?.let {
                _state.value = InferenceState.Error(it)
                throw BackendInferenceException(
                    loadedBackend,
                    if (mediaPaths.isEmpty()) BackendFailureStage.PROMPT else BackendFailureStage.MEDIA,
                    it,
                )
            }
            _metrics.value = _metrics.value.copy(
                promptEvaluationMillis = promptMark.elapsedNow().inWholeMilliseconds,
                firstTokenMillis = null,
            )
            _state.value = InferenceState.Generating
            emit(GenerationEvent.Phase(InferenceState.Generating))
            val firstTokenMark = TimeSource.Monotonic.markNow()
            var emittedFirstToken = false
            var continuationCount = 0
            var stopReason = GenerationStopReason.ERROR
            val repetitionGuard = RepetitionGuard()
            try {
                while (!cancelled) {
                    val token = nativeNextToken()
                    if (token == null) {
                        stopReason = nativeLastStopReason().toStopReason()
                        if (stopReason == GenerationStopReason.DECODE_ERROR) {
                            throw BackendInferenceException(
                                loadedBackend,
                                BackendFailureStage.DECODE,
                                "$loadedBackend decode failed.",
                            )
                        }
                        val answerTokens = nativeGeneratedAnswerTokens().coerceAtLeast(0)
                        if (stopReason == GenerationStopReason.TOKEN_LIMIT &&
                            answerTokens < settings.maxAnswerTokens &&
                            continuationCount < MAX_CONTINUATIONS
                        ) {
                            val remaining = settings.maxAnswerTokens - answerTokens
                            nativeContinueGeneration(minOf(settings.maxNewTokens, remaining))
                            continuationCount++
                            continue
                        }
                        break
                    }
                    if (token.isNotEmpty()) {
                        if (!emittedFirstToken) {
                            emittedFirstToken = true
                            _metrics.value = _metrics.value.copy(
                                firstTokenMillis = firstTokenMark.elapsedNow().inWholeMilliseconds,
                            )
                        }
                        when (nativeLastTokenChannel()) {
                            TOKEN_CHANNEL_THOUGHT -> emit(GenerationEvent.ThoughtDelta(token))
                            TOKEN_CHANNEL_ANSWER -> {
                                if (!repetitionGuard.accept(token)) {
                                    stopReason = GenerationStopReason.REPETITION
                                    cancelled = true
                                    break
                                }
                                emit(GenerationEvent.AnswerDelta(token))
                            }
                        }
                    }
                }
                if (cancelled && stopReason == GenerationStopReason.ERROR) {
                    stopReason = GenerationStopReason.CANCELLED
                }
                emit(
                    GenerationEvent.Completed(
                        reason = stopReason,
                        answerTokens = nativeGeneratedAnswerTokens().coerceAtLeast(0),
                        continuationCount = continuationCount,
                    ),
                )
            } catch (cancel: CancellationException) {
                cancelled = true
                throw cancel
            } catch (error: Throwable) {
                activeConversationId = null
                activeRestoreFingerprint = null
                _state.value = InferenceState.Error(error.message ?: "Generation failed")
                throw error
            } finally {
                nativeFinishGeneration()
                // Keep the weights resident between turns; see restoreSession().
                if (_state.value !is InferenceState.Error) {
                    _state.value = InferenceState.Ready(requireNotNull(loadedModelName), loadedBackend)
                }
            }
        } finally {
            releaseCpu()
        }
    }.flowOn(dispatcher)

    override fun cancel() {
        cancelled = true
    }

    override fun releaseResidentPages() {
        if (loadedModelPath == null) return
        nativeReleaseModelPages()
    }

    override suspend fun unload() = withContext(dispatcher) {
        cancelled = true
        nativeUnload()
        loadedModelPath = null
        loadedModelName = null
        loadedProjectorPath = null
        loadedCapabilities = null
        modelContextLimit = 0
        activeContextSize = 0
        activeConversationId = null
        activeRestoreFingerprint = null
        _metrics.value = InferenceMetrics()
        _state.value = InferenceState.Idle
    }

    override suspend fun benchmark(
        path: String,
        displayName: String,
        settings: GenerationSettings,
    ): Map<BackendMode, InferenceBenchmarkSample> = withContext(dispatcher) {
        val results = linkedMapOf<BackendMode, InferenceBenchmarkSample>()
        holdCpu()
        try {
            for (backend in listOf(nativeBackend)) {
                try {
                    val loadMark = TimeSource.Monotonic.markNow()
                    nativeLoad(path, backend.nativeCode, 1024, settings.temperature)?.let {
                        throw BackendInferenceException(backend, BackendFailureStage.BENCHMARK, it)
                    }
                    val loadMillis = loadMark.elapsedNow().inWholeMilliseconds
                    nativeRestore(
                        "You are running a local performance check. Answer directly.",
                        emptyArray(),
                        emptyArray(),
                        false,
                    )?.let {
                        throw BackendInferenceException(backend, BackendFailureStage.BENCHMARK, it)
                    }
                    val promptMark = TimeSource.Monotonic.markNow()
                    nativeBeginUserPrompt(
                        "Reply with exactly the word ready.",
                        "Reply with exactly the word ready.",
                        8,
                    )?.let {
                        throw BackendInferenceException(backend, BackendFailureStage.BENCHMARK, it)
                    }
                    val promptMillis = promptMark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
                    val generationMark = TimeSource.Monotonic.markNow()
                    var tokens = 0
                    while (nativeNextToken() != null) tokens++
                    val benchmarkStop = nativeLastStopReason().toStopReason()
                    check(tokens > 0) { "$backend generated no tokens during its benchmark." }
                    check(benchmarkStop in setOf(
                        GenerationStopReason.EOG,
                        GenerationStopReason.TOKEN_LIMIT,
                    )) { "$backend benchmark ended with $benchmarkStop." }
                    val seconds = generationMark.elapsedNow().inWholeMilliseconds
                        .coerceAtLeast(1) / 1000.0
                    results[backend] = InferenceBenchmarkSample(
                        loadMillis = loadMillis,
                        promptTokensPerSecond = 8.0 / (promptMillis / 1000.0),
                        generationTokensPerSecond = tokens / seconds,
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Throwable) {
                    // A missing result is persisted by the runner as a backend failure. Keep
                    // benchmarking so a Vulkan fault can never discard the safe CPU result.
                } finally {
                    runCatching { nativeFinishGeneration() }
                    runCatching { nativeUnload() }
                }
            }
        } finally {
            releaseCpu()
        }
        loadedModelPath = null
        loadedModelName = null
        loadedProjectorPath = null
        loadedCapabilities = null
        activeConversationId = null
        activeRestoreFingerprint = null
        activeContextSize = 0
        _state.value = InferenceState.Idle
        results
    }

    override fun systemInfo(): String = nativeSystemInfo()

    override suspend fun countTokens(text: String): Int = withContext(dispatcher) {
        nativeCountTokens(text).coerceAtLeast(0)
    }

    override suspend fun verifyLoadedContext(): Int = withContext(dispatcher) {
        check(loadedModelPath != null) { "Load a model before verifying context" }
        holdCpu()
        try {
            nativeRestore(
                "You are verifying a local inference context. Answer directly.",
                emptyArray(),
                emptyArray(),
                false,
            )?.let {
                throw BackendInferenceException(loadedBackend, BackendFailureStage.VERIFY, it)
            }
            nativeBeginUserPrompt(
                "Reply with exactly the word verified.",
                "Reply with exactly the word verified.",
                VERIFICATION_TOKEN_LIMIT,
            )?.let {
                throw BackendInferenceException(loadedBackend, BackendFailureStage.VERIFY, it)
            }
            var generated = 0
            while (true) {
                val token = nativeNextToken() ?: break
                if (token.isNotEmpty() && nativeLastTokenChannel() == TOKEN_CHANNEL_ANSWER) {
                    generated++
                }
            }
            val stop = nativeLastStopReason().toStopReason()
            check(
                generated > 0 &&
                    stop in setOf(GenerationStopReason.EOG, GenerationStopReason.TOKEN_LIMIT),
            ) {
                "Context verification did not complete a valid decode"
            }
            generated
        } finally {
            nativeFinishGeneration()
            nativeReleaseModelPages()
            activeConversationId = null
            activeRestoreFingerprint = null
            releaseCpu()
        }
    }

    override fun destroy() {
        cancelled = true
        nativeShutdown()
    }

    private external fun nativeInit(nativeLibDir: String, backend: Int)
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
    private external fun nativeBeginUserPrompt(
        prompt: String,
        recordedPrompt: String,
        maxTokens: Int,
    ): String?
    private external fun nativeBeginUserTurn(
        prompt: String,
        paths: Array<String>,
        maxTokens: Int,
    ): String?
    private external fun nativeCountTokens(text: String): Int
    private external fun nativeNextToken(): String?
    private external fun nativeContinueGeneration(maxTokens: Int)
    private external fun nativeLastStopReason(): Int
    private external fun nativeLastTokenChannel(): Int
    private external fun nativeGeneratedAnswerTokens(): Int
    private external fun nativeModelContextLimit(): Int
    private external fun nativeCurrentContextSize(): Int
    private external fun nativeFinishGeneration()
    private external fun nativeUnload()
    private external fun nativeReleaseModelPages()
    private external fun nativeSessionPrefixLength(
        systemPrompt: String,
        enableThinking: Boolean,
        roles: Array<String>,
        contents: Array<String>,
    ): Int
    private external fun nativeSystemInfo(): String
    private external fun nativeShutdown()

}

/**
 * Summarizes every input [restoreSession] feeds to the native layer: the
 * system prompt and thinking flag shape the native prompt, while the history
 * size plus the last message id and content proxy the replayed turns. Content
 * participates because continuations mutate the target assistant row in place,
 * so an unchanged last id does NOT imply unchanged content. Any new generation
 * setting consumed by the native restore must join this string or the
 * silent-staleness skip returns.
 */
internal fun restoreFingerprint(
    conversationId: String,
    settings: GenerationSettings,
    history: List<ChatTurn>,
): String = conversationId + '\u0000' +
    settings.thinkingEnabled.toString() + '\u0000' +
    settings.systemPrompt.hashCode() + '\u0000' +
    history.size + '\u0000' +
    history.lastOrNull()?.message?.id.hashCode() + '\u0000' +
    history.lastOrNull()?.message?.content.hashCode()

internal fun shouldSkipRestore(
    activeConversationId: String?,
    activeRestoreFingerprint: String?,
    conversationId: String,
    fingerprint: String,
): Boolean = activeConversationId == conversationId && activeRestoreFingerprint == fingerprint

private class RepetitionGuard {
    private val answer = StringBuilder()

    fun accept(delta: String): Boolean {
        answer.append(delta)
        if (answer.length < 480) return true
        val tail = answer.takeLast(160)
        val preceding = answer.substring(
            (answer.length - 480).coerceAtLeast(0),
            answer.length - 160,
        )
        return tail !in preceding
    }
}

private fun Int.toStopReason(): GenerationStopReason = when (this) {
    1 -> GenerationStopReason.EOG
    2 -> GenerationStopReason.TOKEN_LIMIT
    3 -> GenerationStopReason.CONTEXT_LIMIT
    4, 5 -> GenerationStopReason.DECODE_ERROR
    else -> GenerationStopReason.ERROR
}

/** `nativeSessionPrefixLength` sentinel: the KV cache cannot be reused. */
private const val REBUILD_SESSION = -1

private const val TOKEN_CHANNEL_THOUGHT = 1
private const val TOKEN_CHANNEL_ANSWER = 2
private const val MAX_CONTINUATIONS = 16
private const val VERIFICATION_TOKEN_LIMIT = 12

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
