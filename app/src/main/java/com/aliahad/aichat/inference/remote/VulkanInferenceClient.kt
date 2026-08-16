package com.aliahad.aichat.inference.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import com.aliahad.aichat.core.BackendFailureStage
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.GenerationEvent
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.InferenceBenchmarkSample
import com.aliahad.aichat.core.InferenceExecutionProfile
import com.aliahad.aichat.core.InferenceMetrics
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.ModelCapabilities
import com.aliahad.aichat.core.ModelLoadConfiguration
import com.aliahad.aichat.core.UserTurn
import com.aliahad.aichat.inference.BackendInferenceException
import com.aliahad.aichat.inference.InferenceEngine
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.TimeSource

class VulkanInferenceClient @OptIn(ExperimentalCoroutinesApi::class) constructor(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : InferenceEngine {
    private val context = context.applicationContext
    private val codec = VulkanRequestCodec(this.context)
    private val connectionLock = Any()
    private val _state = MutableStateFlow<InferenceState>(InferenceState.Idle)
    override val state: StateFlow<InferenceState> = _state.asStateFlow()
    private val _metrics = MutableStateFlow(InferenceMetrics())
    override val metrics: StateFlow<InferenceMetrics> = _metrics.asStateFlow()

    @Volatile private var service: IVulkanInferenceService? = null
    @Volatile private var pendingConnection: CompletableDeferred<IVulkanInferenceService>? = null
    @Volatile private var bound = false
    @Volatile private var activeGeneration: Channel<RemoteGenerationSignal>? = null
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

    private val deathRecipient = IBinder.DeathRecipient {
        disconnect(
            BackendInferenceException(
                BackendMode.VULKAN,
                BackendFailureStage.SERVICE_DIED,
                "The Vulkan inference process stopped unexpectedly.",
            ),
        )
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                disconnect(connectFailure("The Vulkan inference service returned no binder."))
                return
            }
            val connected = IVulkanInferenceService.Stub.asInterface(binder)
            try {
                binder.linkToDeath(deathRecipient, 0)
            } catch (error: RemoteException) {
                disconnect(connectFailure("The Vulkan inference service died while connecting.", error))
                return
            }
            synchronized(connectionLock) {
                service = connected
                pendingConnection?.complete(connected)
                pendingConnection = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            disconnect(
                BackendInferenceException(
                    BackendMode.VULKAN,
                    BackendFailureStage.SERVICE_DIED,
                    "The Vulkan inference process disconnected.",
                ),
            )
        }

        override fun onBindingDied(name: ComponentName?) {
            disconnect(
                BackendInferenceException(
                    BackendMode.VULKAN,
                    BackendFailureStage.SERVICE_DIED,
                    "The Vulkan inference service binding died.",
                ),
            )
        }

        override fun onNullBinding(name: ComponentName?) {
            disconnect(connectFailure("The Vulkan inference service could not start."))
        }
    }

    override suspend fun loadModel(
        path: String,
        displayName: String,
        configuration: ModelLoadConfiguration,
    ) = withContext(dispatcher) {
        require(configuration.backend in setOf(BackendMode.VULKAN, BackendMode.AUTO))
        _state.value = InferenceState.Loading(displayName)
        val result = remoteCall(BackendFailureStage.LOAD) {
            it.loadModel(
                path,
                displayName,
                configuration.contextTokens,
                configuration.declaredContextTokens,
                configuration.temperature,
                configuration.modelSha256.orEmpty(),
            )
        }
        result.requireRemoteSuccess(BackendFailureStage.LOAD)
        loadedModelPath = path
        loadedModelName = displayName
        loadedProjectorPath = null
        loadedCapabilities = null
        modelContextLimit = result.getInt(RemoteProtocol.KEY_CONTEXT_LIMIT)
        activeContextSize = result.getInt(RemoteProtocol.KEY_CONTEXT_SIZE)
        _metrics.value = InferenceMetrics(
            modelLoadMillis = result.optionalLong(RemoteProtocol.KEY_MODEL_LOAD_MILLIS),
        )
        _state.value = InferenceState.Ready(displayName, BackendMode.VULKAN)
    }

    override suspend fun loadProjector(path: String, imageTokenBudget: Int): ModelCapabilities =
        withContext(dispatcher) {
            val result = remoteCall(BackendFailureStage.PROJECTOR) {
                it.loadProjector(path, imageTokenBudget)
            }
            result.requireRemoteSuccess(BackendFailureStage.PROJECTOR)
            loadedProjectorPath = path
            val flags = result.getInt(RemoteProtocol.KEY_CAPABILITIES)
            ModelCapabilities(
                vision = flags and 1 != 0,
                audio = flags and 2 != 0,
                contextLimit = modelContextLimit,
            ).also { loadedCapabilities = it }
        }

    override suspend fun unloadProjector() = withContext(dispatcher) {
        remoteCall(BackendFailureStage.PROJECTOR) { it.unloadProjector() }
        loadedProjectorPath = null
        loadedCapabilities = null
    }

    override suspend fun restoreSession(
        conversationId: String,
        history: List<ChatTurn>,
        settings: GenerationSettings,
    ) = withContext(dispatcher) {
        _state.value = InferenceState.PreparingHistory
        val requestPath = codec.writeRestore(conversationId, history, settings)
        try {
            val result = remoteCall(BackendFailureStage.RESTORE) { it.restoreSession(requestPath) }
            result.requireRemoteSuccess(BackendFailureStage.RESTORE)
            _metrics.value = _metrics.value.copy(
                historyRestoreMillis = result.optionalLong(RemoteProtocol.KEY_HISTORY_RESTORE_MILLIS),
            )
            _state.value = InferenceState.Ready(requireNotNull(loadedModelName), BackendMode.VULKAN)
        } finally {
            File(requestPath).delete()
        }
    }

    override fun generate(
        turn: UserTurn,
        settings: GenerationSettings,
        profile: InferenceExecutionProfile,
    ): Flow<GenerationEvent> = flow {
        val requestPath = codec.writeGeneration(turn, settings)
        val signals = Channel<RemoteGenerationSignal>(Channel.UNLIMITED)
        activeGeneration = signals
        var completed = false
        val firstTokenMark = TimeSource.Monotonic.markNow()
        var firstToken = true
        val callback = object : IVulkanGenerationCallback.Stub() {
            override fun onBatch(phase: Int, thoughtDelta: String?, answerDelta: String?) {
                RemoteProtocol.phase(phase)?.let {
                    signals.trySend(RemoteGenerationSignal.Event(GenerationEvent.Phase(it)))
                }
                thoughtDelta?.takeIf(String::isNotEmpty)?.let {
                    signals.trySend(RemoteGenerationSignal.Event(GenerationEvent.ThoughtDelta(it)))
                }
                answerDelta?.takeIf(String::isNotEmpty)?.let {
                    signals.trySend(RemoteGenerationSignal.Event(GenerationEvent.AnswerDelta(it)))
                }
            }

            override fun onCompleted(reason: Int, answerTokens: Int, continuationCount: Int) {
                signals.trySend(
                    RemoteGenerationSignal.Event(
                        GenerationEvent.Completed(
                            RemoteProtocol.stopReason(reason),
                            answerTokens,
                            continuationCount,
                        ),
                    ),
                )
                signals.trySend(RemoteGenerationSignal.Complete)
            }

            override fun onFailure(stage: Int, message: String?) {
                val failureStage = BackendFailureStage.entries.getOrElse(stage) {
                    BackendFailureStage.UNKNOWN
                }
                signals.trySend(
                    RemoteGenerationSignal.Failure(
                        BackendInferenceException(
                            BackendMode.VULKAN,
                            failureStage,
                            message ?: "Vulkan generation failed.",
                        ),
                    ),
                )
            }
        }
        try {
            remoteCall(BackendFailureStage.PROMPT) { it.generate(requestPath, callback) }
            for (signal in signals) {
                when (signal) {
                    is RemoteGenerationSignal.Event -> {
                        val event = signal.event
                        if (event is GenerationEvent.Phase) _state.value = event.state
                        if (firstToken &&
                            (event is GenerationEvent.AnswerDelta || event is GenerationEvent.ThoughtDelta)
                        ) {
                            firstToken = false
                            _metrics.value = _metrics.value.copy(
                                firstTokenMillis = firstTokenMark.elapsedNow().inWholeMilliseconds,
                            )
                        }
                        emit(event)
                    }
                    is RemoteGenerationSignal.Failure -> throw signal.error
                    RemoteGenerationSignal.Complete -> {
                        completed = true
                        break
                    }
                }
            }
            _state.value = InferenceState.Ready(requireNotNull(loadedModelName), BackendMode.VULKAN)
        } finally {
            if (!completed) runCatching { service?.cancel() }
            activeGeneration = null
            signals.close()
            File(requestPath).delete()
        }
    }.flowOn(dispatcher)

    override suspend fun countTokens(text: String): Int = withContext(dispatcher) {
        val requestPath = codec.writeTokenCount(text)
        try {
            remoteCall(BackendFailureStage.PROMPT) { it.countTokens(requestPath) }.coerceAtLeast(0)
        } finally {
            File(requestPath).delete()
        }
    }

    override suspend fun verifyLoadedContext(): Int = withContext(dispatcher) {
        val result = remoteCall(BackendFailureStage.VERIFY) { it.verifyLoadedContext() }
        result.requireRemoteSuccess(BackendFailureStage.VERIFY)
        result.getInt(RemoteProtocol.KEY_GENERATED)
    }

    override fun cancel() {
        runCatching { service?.cancel() }
    }

    // The weights live in the remote service's address space, so there is nothing
    // this client can hand back to the kernel.
    override fun releaseResidentPages() = Unit

    override suspend fun unload() = withContext(dispatcher) {
        runCatching { service?.unload() }
        clearLoadedState()
        _state.value = InferenceState.Idle
    }

    override suspend fun benchmark(
        path: String,
        displayName: String,
        settings: GenerationSettings,
    ): Map<BackendMode, InferenceBenchmarkSample> = withContext(dispatcher) {
        val result = remoteCall(BackendFailureStage.BENCHMARK) {
            it.benchmark(
                path,
                displayName,
                settings.maxNewTokens,
                settings.maxAnswerTokens,
                settings.temperature,
                settings.thinkingEnabled,
                settings.systemPrompt.take(MAX_IPC_TEXT_CHARS),
            )
        }
        result.requireRemoteSuccess(BackendFailureStage.BENCHMARK)
        mapOf(
            BackendMode.VULKAN to InferenceBenchmarkSample(
                loadMillis = result.getLong(RemoteProtocol.KEY_LOAD_MILLIS),
                promptTokensPerSecond = result.getDouble(RemoteProtocol.KEY_PROMPT_TPS),
                generationTokensPerSecond = result.getDouble(RemoteProtocol.KEY_GENERATION_TPS),
                peakPssBytes = result.optionalLong(RemoteProtocol.KEY_PEAK_PSS),
            ),
        )
    }

    override fun systemInfo(): String = runCatching { service?.systemInfo() }.getOrNull().orEmpty()

    override fun destroy() {
        cancel()
        disconnect(null)
    }

    private suspend fun service(): IVulkanInferenceService {
        service?.let { return it }
        val deferred = synchronized(connectionLock) {
            service?.let { return it }
            pendingConnection ?: CompletableDeferred<IVulkanInferenceService>().also {
                pendingConnection = it
                val intent = Intent(context, VulkanInferenceService::class.java)
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    it.completeExceptionally(connectFailure("Unable to bind Vulkan inference service."))
                    pendingConnection = null
                }
            }
        }
        return try {
            withTimeout(CONNECT_TIMEOUT_MILLIS) { deferred.await() }
        } catch (error: Throwable) {
            val failure = if (error is BackendInferenceException) error else connectFailure(
                "Timed out starting Vulkan inference.",
                error,
            )
            disconnect(failure)
            throw failure
        }
    }

    private suspend fun <T> remoteCall(
        stage: BackendFailureStage,
        call: (IVulkanInferenceService) -> T,
    ): T = try {
        call(service())
    } catch (error: DeadObjectException) {
        throw BackendInferenceException(
            BackendMode.VULKAN,
            BackendFailureStage.SERVICE_DIED,
            "The Vulkan inference process stopped unexpectedly.",
            error,
        )
    } catch (error: RemoteException) {
        throw BackendInferenceException(
            BackendMode.VULKAN,
            stage,
            "Vulkan IPC failed: ${error.message ?: error.javaClass.simpleName}",
            error,
        )
    }

    private fun disconnect(error: BackendInferenceException?) {
        synchronized(connectionLock) {
            service = null
            pendingConnection?.let { pending ->
                error?.let(pending::completeExceptionally)
                    ?: pending.cancel()
            }
            pendingConnection = null
            if (bound) runCatching { context.unbindService(connection) }
            bound = false
        }
        if (error != null) {
            activeGeneration?.trySend(RemoteGenerationSignal.Failure(error))
            _state.value = InferenceState.Error(error.message ?: "Vulkan inference failed.")
        }
        clearLoadedState()
    }

    private fun clearLoadedState() {
        loadedModelPath = null
        loadedProjectorPath = null
        loadedCapabilities = null
        loadedModelName = null
        modelContextLimit = 0
        activeContextSize = 0
    }

    private fun connectFailure(message: String, cause: Throwable? = null) = BackendInferenceException(
        BackendMode.VULKAN,
        BackendFailureStage.CONNECT,
        message,
        cause,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
        const val MAX_IPC_TEXT_CHARS = 4_096
    }
}

private sealed interface RemoteGenerationSignal {
    data class Event(val event: GenerationEvent) : RemoteGenerationSignal
    data class Failure(val error: BackendInferenceException) : RemoteGenerationSignal
    data object Complete : RemoteGenerationSignal
}

private fun Bundle.optionalLong(key: String): Long? = if (containsKey(key)) getLong(key) else null
