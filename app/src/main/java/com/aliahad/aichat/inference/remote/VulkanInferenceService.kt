package com.aliahad.aichat.inference.remote

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Debug
import android.os.RemoteException
import android.util.Log
import com.aliahad.aichat.core.BackendFailureStage
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.GenerationEvent
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.InferenceBenchmarkSample
import com.aliahad.aichat.core.InferenceExecutionProfile
import com.aliahad.aichat.core.ModelLoadConfiguration
import com.aliahad.aichat.inference.BackendInferenceException
import com.aliahad.aichat.inference.NativeInferenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicLong

class VulkanInferenceService : Service() {
    private lateinit var engine: NativeInferenceEngine
    private lateinit var codec: VulkanRequestCodec
    private val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, error ->
                Log.e(TAG, "Vulkan service coroutine failed", error)
            },
    )

    override fun onCreate() {
        super.onCreate()
        codec = VulkanRequestCodec(this)
        engine = NativeInferenceEngine(this, BackendMode.VULKAN)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        if (::engine.isInitialized) engine.destroy()
        super.onDestroy()
    }

    private val binder = object : IVulkanInferenceService.Stub() {
        override fun loadModel(
            path: String,
            displayName: String,
            contextTokens: Int,
            declaredContextTokens: Int,
            temperature: Float,
            modelSha256: String,
        ): Bundle = blockingResult(BackendFailureStage.LOAD) {
            engine.loadModel(
                path,
                displayName,
                ModelLoadConfiguration(
                    contextTokens = contextTokens,
                    declaredContextTokens = declaredContextTokens,
                    backend = BackendMode.VULKAN,
                    temperature = temperature,
                    modelSha256 = modelSha256.ifBlank { null },
                ),
            )
            RemoteProtocol.success {
                putInt(RemoteProtocol.KEY_CONTEXT_LIMIT, engine.modelContextLimit)
                putInt(RemoteProtocol.KEY_CONTEXT_SIZE, engine.activeContextSize)
                engine.metrics.value.modelLoadMillis?.let {
                    putLong(RemoteProtocol.KEY_MODEL_LOAD_MILLIS, it)
                }
            }
        }

        override fun loadProjector(path: String, imageTokenBudget: Int): Bundle =
            blockingResult(BackendFailureStage.PROJECTOR) {
                val capabilities = engine.loadProjector(path, imageTokenBudget)
                RemoteProtocol.success {
                    putInt(
                        RemoteProtocol.KEY_CAPABILITIES,
                        (if (capabilities.vision) 1 else 0) or (if (capabilities.audio) 2 else 0),
                    )
                }
            }

        override fun unloadProjector() {
            runBlocking { engine.unloadProjector() }
        }

        override fun restoreSession(requestPath: String): Bundle =
            blockingResult(BackendFailureStage.RESTORE) {
                val request = codec.readRestore(requestPath)
                engine.restoreSession(request.conversationId, request.history, request.settings)
                RemoteProtocol.success {
                    engine.metrics.value.historyRestoreMillis?.let {
                        putLong(RemoteProtocol.KEY_HISTORY_RESTORE_MILLIS, it)
                    }
                }
            }

        override fun generate(requestPath: String, callback: IVulkanGenerationCallback) {
            scope.launch {
                val batch = CallbackBatch(callback)
                try {
                    val request = codec.readGeneration(requestPath)
                    engine.generate(
                        request.turn,
                        request.settings,
                        InferenceExecutionProfile.NORMAL,
                    ).collect { event ->
                        when (event) {
                            is GenerationEvent.Phase -> batch.phase(event)
                            is GenerationEvent.ThoughtDelta -> batch.thought(event.text)
                            is GenerationEvent.AnswerDelta -> batch.answer(event.text)
                            is GenerationEvent.Completed -> {
                                batch.flush()
                                withCallback {
                                    callback.onCompleted(
                                        event.reason.ordinal,
                                        event.answerTokens,
                                        event.continuationCount,
                                    )
                                }
                            }
                            is GenerationEvent.BackendFallback -> Unit
                        }
                    }
                } catch (cancelled: CancellationException) {
                    batch.flush()
                    withCallback {
                        callback.onFailure(
                            BackendFailureStage.UNKNOWN.ordinal,
                            (cancelled.message ?: "Vulkan generation was cancelled.").take(1_000),
                        )
                    }
                } catch (error: Throwable) {
                    batch.flush()
                    val stage = (error as? BackendInferenceException)?.stage
                        ?: BackendFailureStage.UNKNOWN
                    withCallback {
                        callback.onFailure(
                            stage.ordinal,
                            (error.message ?: "Vulkan generation failed.").take(1_000),
                        )
                    }
                }
            }
        }

        override fun countTokens(requestPath: String): Int = runBlocking {
            engine.countTokens(codec.readTokenCount(requestPath))
        }

        override fun verifyLoadedContext(): Bundle = blockingResult(BackendFailureStage.VERIFY) {
            val generated = engine.verifyLoadedContext()
            RemoteProtocol.success { putInt(RemoteProtocol.KEY_GENERATED, generated) }
        }

        override fun cancel() {
            engine.cancel()
        }

        override fun unload() {
            runBlocking { engine.unload() }
        }

        override fun benchmark(
            path: String,
            displayName: String,
            maxNewTokens: Int,
            maxAnswerTokens: Int,
            temperature: Float,
            thinkingEnabled: Boolean,
            systemPrompt: String,
        ): Bundle = blockingResult(BackendFailureStage.BENCHMARK) {
            val (rawSample, peakPss) = measurePeakPss {
                engine.benchmark(
                    path,
                    displayName,
                    GenerationSettings(
                        maxNewTokens,
                        maxAnswerTokens,
                        temperature,
                        thinkingEnabled,
                        systemPrompt,
                    ),
                )[BackendMode.VULKAN]
                    ?: throw BackendInferenceException(
                        BackendMode.VULKAN,
                        BackendFailureStage.BENCHMARK,
                        "Vulkan failed its native benchmark.",
                    )
            }
            val sample = rawSample.copy(peakPssBytes = peakPss)
            sample.toBundle()
        }

        override fun systemInfo(): String = engine.systemInfo()
    }

    private fun blockingResult(
        defaultStage: BackendFailureStage,
        block: suspend () -> Bundle,
    ): Bundle = runBlocking {
        try {
            block()
        } catch (error: Throwable) {
            val stage = (error as? BackendInferenceException)?.stage ?: defaultStage
            RemoteProtocol.failure(stage, error.message ?: "Vulkan inference failed.")
        }
    }
}

private suspend fun <T> measurePeakPss(block: suspend () -> T): Pair<T, Long> = coroutineScope {
    val peak = AtomicLong()
    fun sample() {
        peak.accumulateAndGet(Debug.getPss().toLong() * 1_024, ::maxOf)
    }
    sample()
    val sampler = launch(Dispatchers.Default) {
        while (isActive) {
            delay(100)
            sample()
        }
    }
    try {
        val result = block()
        sample()
        result to peak.get()
    } finally {
        sampler.cancel()
    }
}

private const val TAG = "VulkanService"

private inline fun withCallback(block: () -> Unit) {
    try {
        block()
    } catch (remote: RemoteException) {
        Log.w(TAG, "Vulkan client went away during callback", remote)
    }
}

private class CallbackBatch(
    private val callback: IVulkanGenerationCallback,
) {
    private var phase = RemoteProtocol.PHASE_NONE
    private val thought = StringBuilder()
    private val answer = StringBuilder()
    private var events = 0

    fun phase(event: GenerationEvent.Phase) {
        flush()
        phase = RemoteProtocol.phaseCode(event.state)
        flush()
    }

    fun thought(text: String) {
        thought.append(text)
        maybeFlush()
    }

    fun answer(text: String) {
        answer.append(text)
        maybeFlush()
    }

    private fun maybeFlush() {
        events++
        if (events >= MAX_BATCH_EVENTS || thought.length + answer.length >= MAX_BATCH_CHARS) flush()
    }

    fun flush() {
        if (phase == RemoteProtocol.PHASE_NONE && thought.isEmpty() && answer.isEmpty()) return
        withCallback { callback.onBatch(phase, thought.toString(), answer.toString()) }
        phase = RemoteProtocol.PHASE_NONE
        thought.clear()
        answer.clear()
        events = 0
    }

    private companion object {
        const val MAX_BATCH_EVENTS = 16
        const val MAX_BATCH_CHARS = 1_024
    }
}

private fun InferenceBenchmarkSample.toBundle(): Bundle = RemoteProtocol.success {
    putLong(RemoteProtocol.KEY_LOAD_MILLIS, loadMillis)
    putDouble(RemoteProtocol.KEY_PROMPT_TPS, promptTokensPerSecond)
    putDouble(RemoteProtocol.KEY_GENERATION_TPS, generationTokensPerSecond)
    peakPssBytes?.let { putLong(RemoteProtocol.KEY_PEAK_PSS, it) }
}
