package com.aliahad.aichat.inference

import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.BackendFailureStage
import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.GenerationEvent
import com.aliahad.aichat.core.InferenceMetrics
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.InferenceExecutionProfile
import com.aliahad.aichat.core.ModelCapabilities
import com.aliahad.aichat.core.ModelLoadConfiguration
import com.aliahad.aichat.core.InferenceBenchmarkSample
import com.aliahad.aichat.core.UserTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

class BackendInferenceException(
    val backend: BackendMode,
    val stage: BackendFailureStage,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class RuntimeOperationGate {
    private val mutex = Mutex()

    suspend fun <T> runExclusive(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}

interface InferenceEngine {
    val state: StateFlow<InferenceState>
    val metrics: StateFlow<InferenceMetrics>
    val loadedModelPath: String?
    val loadedProjectorPath: String?
    val loadedCapabilities: ModelCapabilities?
    val modelContextLimit: Int
    val activeContextSize: Int
    suspend fun loadModel(
        path: String,
        displayName: String,
        configuration: ModelLoadConfiguration,
    )
    suspend fun loadProjector(path: String, imageTokenBudget: Int): ModelCapabilities
    suspend fun unloadProjector()
    suspend fun restoreSession(conversationId: String, history: List<ChatTurn>, settings: GenerationSettings)

    /**
     * Writes the live KV cache to disk so it survives a model reload.
     *
     * HyperOS trims this app whenever it is backgrounded, which unloads the model
     * and takes the cache with it; the next turn then re-decodes the whole
     * conversation, measured at 72.6 s for 929 history tokens against 5 ms when the
     * cache had survived. [history] must be the conversation as it now stands, so
     * that it matches what the next [restoreSession] will pass.
     *
     * Best-effort by design: failing to save costs exactly what today costs.
     */
    suspend fun persistSession(
        conversationId: String,
        settings: GenerationSettings,
        history: List<ChatTurn>,
    )
    fun generate(
        turn: UserTurn,
        settings: GenerationSettings,
        profile: InferenceExecutionProfile = InferenceExecutionProfile.NORMAL,
    ): Flow<GenerationEvent>
    /**
     * Declares the tools the assistant may call, or clears them with "[]".
     *
     * Changes the prompt the chat template builds, so it invalidates the KV cache
     * like any system-prompt change. Set once per conversation, not per turn.
     */
    suspend fun setTools(toolsJson: String)

    /** Whatever the finished turn asked to call, as a JSON array, or "[]". */
    suspend fun lastToolCalls(): String

    suspend fun countTokens(text: String): Int
    suspend fun verifyLoadedContext(): Int

    /**
     * Surrenders the model's file-backed pages back to the kernel.
     *
     * Only for genuine memory pressure (`onTrimMemory`). The pages fault back in on
     * the next decode, which costs tens of seconds of prefill throughput, so this
     * must never be called on the per-turn path.
     */
    fun releaseResidentPages()

    /**
     * Loads the sentence-embedding model used for semantic memory recall.
     *
     * A separate, much smaller model with its own context — never the chat model,
     * whose KV cache must survive between turns.
     */
    suspend fun loadEmbedder(path: String)

    suspend fun unloadEmbedder()

    /** Dimensions of the loaded embedder, or 0 when none is loaded. */
    val embeddingDimensions: Int

    /** L2-normalized embedding, or null when no embedder is loaded or the text is empty. */
    suspend fun embed(text: String): FloatArray?
    fun cancel()
    suspend fun unload()
    suspend fun benchmark(
        path: String,
        displayName: String,
        settings: GenerationSettings,
    ): Map<BackendMode, InferenceBenchmarkSample>
    fun systemInfo(): String
    fun destroy()
}

interface BenchmarkFailureSource {
    fun benchmarkFailure(backend: BackendMode): BackendInferenceException?
}
