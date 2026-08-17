package com.aliahad.aichat.inference

import android.util.Log
import com.aliahad.aichat.BuildConfig
import com.aliahad.aichat.core.BackendBenchmark
import com.aliahad.aichat.core.BackendFailureStage
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.GenerationEvent
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.GenerationStopReason
import com.aliahad.aichat.core.InferenceBenchmarkSample
import com.aliahad.aichat.core.InferenceExecutionProfile
import com.aliahad.aichat.core.InferenceMetrics
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.ModelCapabilities
import com.aliahad.aichat.core.ModelLoadConfiguration
import com.aliahad.aichat.core.UserTurn
import com.aliahad.aichat.settings.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform

typealias CpuFallbackConfigurationResolver = suspend (ModelLoadConfiguration) -> ModelLoadConfiguration

interface BackendRecoveryController {
    suspend fun resolve(requested: BackendMode, modelSha256: String?): BackendMode
    suspend fun quarantine(
        modelId: String?,
        modelSha256: String?,
        stage: BackendFailureStage,
        reason: String,
    )
}

class BackendRecoveryPolicy(
    private val settings: AppSettingsRepository,
    private val benchmarks: ModelBenchmarkRepository,
    private val deviceFingerprint: String,
    private val runtimeRevision: String = BuildConfig.LLAMA_RUNTIME_REVISION,
) : BackendRecoveryController {
    override suspend fun resolve(requested: BackendMode, modelSha256: String?): BackendMode {
        if (requested != BackendMode.VULKAN || modelSha256 == null) return BackendMode.CPU
        if (settings.isVulkanQuarantined(modelSha256, deviceFingerprint, runtimeRevision)) {
            return BackendMode.CPU
        }
        val passing = benchmarks.forCurrentRuntime(modelSha256)
            .any { it.backend == BackendMode.VULKAN && it.success }
        if (!passing) settings.selectCpuAfterVulkanRejection()
        return if (passing) BackendMode.VULKAN else BackendMode.CPU
    }

    override suspend fun quarantine(
        modelId: String?,
        modelSha256: String?,
        stage: BackendFailureStage,
        reason: String,
    ) {
        if (modelSha256 == null) {
            settings.selectCpuAfterVulkanRejection()
            return
        }
        settings.quarantineVulkan(modelSha256, deviceFingerprint, runtimeRevision)
        benchmarks.upsert(
            BackendBenchmark(
                id = benchmarkStableId(modelSha256, deviceFingerprint, BackendMode.VULKAN),
                modelId = modelId ?: modelSha256,
                modelSha256 = modelSha256,
                deviceFingerprint = deviceFingerprint,
                backend = BackendMode.VULKAN,
                llamaRevision = runtimeRevision,
                loadMillis = null,
                promptTokensPerSecond = null,
                generationTokensPerSecond = null,
                peakPssBytes = null,
                thermalDelta = null,
                success = false,
                failureReason = "${stage.name}: ${reason.take(450)}",
                measuredAt = System.currentTimeMillis(),
            ),
        )
    }
}

class RecoveringInferenceEngine(
    private val cpu: InferenceEngine,
    private val vulkan: InferenceEngine,
    private val recoveryPolicy: BackendRecoveryController,
    private val operationGate: RuntimeOperationGate = RuntimeOperationGate(),
    private val cpuFallbackConfigurationResolver: CpuFallbackConfigurationResolver = { configuration ->
        configuration.safeCpuFallback()
    },
    private val utilityUseBarrier: () -> Boolean = { false },
) : InferenceEngine, BenchmarkFailureSource {
    private val _state = MutableStateFlow<InferenceState>(InferenceState.Idle)
    override val state: StateFlow<InferenceState> = _state.asStateFlow()
    private val _metrics = MutableStateFlow(InferenceMetrics())
    override val metrics: StateFlow<InferenceMetrics> = _metrics.asStateFlow()

    private var active: InferenceEngine = cpu
    private var activeBackend = BackendMode.CPU
    private var model: LoadedModel? = null
    private var projector: LoadedProjector? = null
    private var projectorCapabilities: ModelCapabilities? = null
    private var session: RestoredSession? = null
    private var pendingFallback: GenerationEvent.BackendFallback? = null
    private val benchmarkFailures = mutableMapOf<BackendMode, BackendInferenceException>()
    private val locallyQuarantinedModels = mutableMapOf<String, String>()

    override val loadedModelPath: String?
        get() = active.loadedModelPath
    override val loadedProjectorPath: String?
        get() = active.loadedProjectorPath
    override val loadedCapabilities: ModelCapabilities?
        get() = active.loadedCapabilities
    override val modelContextLimit: Int
        get() = active.modelContextLimit
    override val activeContextSize: Int
        get() = active.activeContextSize

    override suspend fun loadModel(
        path: String,
        displayName: String,
        configuration: ModelLoadConfiguration,
    ) = operationGate.runExclusive {
        session = null
        projector = null
        projectorCapabilities = null
        val requested = configuration.backend.let {
            if (it == BackendMode.AUTO) BackendMode.CPU else it
        }
        val policyResolution = if (
            configuration.modelSha256?.let(locallyQuarantinedModels::containsKey) == true
        ) {
            Result.success(BackendMode.CPU)
        } else {
            runCatching { recoveryPolicy.resolve(requested, configuration.modelSha256) }
        }
        val selected = policyResolution.getOrElse { resolutionError ->
            configuration.modelSha256?.let { locallyQuarantinedModels[it] = path }
            runCatching {
                recoveryPolicy.quarantine(
                    configuration.modelId,
                    configuration.modelSha256,
                    BackendFailureStage.LOAD,
                    resolutionError.message ?: "Vulkan eligibility check failed.",
                )
            }
            BackendMode.CPU
        }
        model = LoadedModel(path, displayName, configuration)
        val previousActive = active
        activeBackend = selected
        active = if (selected == BackendMode.VULKAN) vulkan else cpu
        if (previousActive !== active && previousActive.loadedModelPath != null) {
            previousActive.unload()
        }
        _state.value = InferenceState.Loading(displayName)
        try {
            active.loadModel(path, displayName, configuration.copy(backend = selected))
            syncFromActive()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (selected != BackendMode.VULKAN) {
                recordTerminalError(error)
                throw error
            }
            recoverToCpu(error.failureStage(BackendFailureStage.LOAD), error, false)
        }
    }

    override suspend fun loadProjector(path: String, imageTokenBudget: Int): ModelCapabilities =
        operationGate.runExclusive {
            projector = LoadedProjector(path, imageTokenBudget)
            try {
                active.loadProjector(path, imageTokenBudget).also {
                    projectorCapabilities = it
                    syncFromActive()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (activeBackend != BackendMode.VULKAN) {
                    recordTerminalError(error)
                    throw error
                }
                recoverToCpu(error.failureStage(BackendFailureStage.PROJECTOR), error, false)
                requireNotNull(projectorCapabilities)
            }
        }

    override suspend fun unloadProjector() = operationGate.runExclusive {
        active.unloadProjector()
        projector = null
        projectorCapabilities = null
        session = null
        syncFromActive()
    }

    /**
     * Persisting is best-effort and must never take the recovery gate: it runs after
     * a turn has already succeeded, so blocking on it would add its cost to the next
     * turn for no benefit, and a failure to save is not a backend failure.
     */
    override suspend fun persistSession(
        conversationId: String,
        settings: GenerationSettings,
        history: List<ChatTurn>,
    ) {
        runCatching { active.persistSession(conversationId, settings, history) }
            .onFailure { Log.w("RecoveringInferenceEngine", "Could not persist the session", it) }
    }

    override suspend fun restoreSession(
        conversationId: String,
        history: List<ChatTurn>,
        settings: GenerationSettings,
    ) = operationGate.runExclusive {
        session = RestoredSession(conversationId, history, settings)
        // A new restore supersedes any RESTORE-stage recovery signal from a prior turn: if
        // that turn was cancelled before its generate ran, the stale event must not leak
        // into the next turn's generate. LOAD/PROJECTOR/PROMPT-stage signals are excluded:
        // they are set before this turn's restore (loadModel/loadProjector/countTokens all
        // precede restoreSession) and must survive to this turn's generate.
        if (pendingFallback?.stage == BackendFailureStage.RESTORE) {
            pendingFallback = null
        }
        try {
            active.restoreSession(conversationId, history, settings)
            syncFromActive()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (activeBackend != BackendMode.VULKAN) {
                recordTerminalError(error)
                throw error
            }
            recoverToCpu(error.failureStage(BackendFailureStage.RESTORE), error, true)
        }
    }

    override fun generate(
        turn: UserTurn,
        settings: GenerationSettings,
        profile: InferenceExecutionProfile,
    ): Flow<GenerationEvent> = flow {
        // Interactive-use barrier guarantee: while an interactive chat turn is
        // active (utilityUseBarrier reports true), UTILITY-profile generation is
        // rejected outright — checked both before acquiring the runtime gate and
        // again immediately after, so a utility operation that slips past the
        // summarizer's own barrier checks can never flip the engine session state
        // (restoreSession/KV cache) mid-turn. The rejection is thrown before any
        // backend call, so the engine never records a terminal error for it.
        requireUtilityAllowed(profile)
        operationGate.runExclusive {
            requireUtilityAllowed(profile)
            pendingFallback?.let {
                emit(it)
                pendingFallback = null
            }
            val attemptedBackend = activeBackend
            var backendFailure: BackendInferenceException? = null
            active.generate(turn, settings, profile)
                .transform { event ->
                    if (event is GenerationEvent.Completed &&
                        event.reason == GenerationStopReason.DECODE_ERROR
                    ) {
                        throw BackendInferenceException(
                            attemptedBackend,
                            BackendFailureStage.DECODE,
                            "$attemptedBackend decode failed.",
                        )
                    }
                    if (event is GenerationEvent.Phase) _state.value = event.state
                    emit(event)
                }
                .catch { error ->
                    when {
                        error is CancellationException -> throw error
                        attemptedBackend == BackendMode.VULKAN &&
                            error is BackendInferenceException -> backendFailure = error
                        else -> {
                            recordTerminalError(error)
                            throw error
                        }
                    }
                }
                .collect { event -> emit(event) }

            val failure = backendFailure
            if (failure == null) {
                syncFromActive()
            } else {
                recoverToCpu(failure.stage, failure, true)
                val fallback = requireNotNull(pendingFallback).copy(discardPartialOutput = true)
                pendingFallback = null
                emit(fallback)
                cpu.generate(turn, settings, profile)
                    .catch { cpuError ->
                        if (cpuError is CancellationException) throw cpuError
                        recordTerminalError(cpuError)
                        throw cpuError
                    }
                    .collect { event ->
                        if (event is GenerationEvent.Phase) _state.value = event.state
                        emit(event)
                    }
                syncFromActive()
            }
        }
    }

    private fun requireUtilityAllowed(profile: InferenceExecutionProfile) {
        if (profile == InferenceExecutionProfile.UTILITY && utilityUseBarrier()) {
            throw IllegalStateException(
                "Utility generation rejected: interactive inference is active.",
            )
        }
    }

    override suspend fun countTokens(text: String): Int = operationGate.runExclusive {
        try {
            active.countTokens(text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (activeBackend != BackendMode.VULKAN) {
                recordTerminalError(error)
                throw error
            }
            recoverToCpu(error.failureStage(BackendFailureStage.PROMPT), error, true)
            cpu.countTokens(text)
        }
    }

    override suspend fun verifyLoadedContext(): Int = operationGate.runExclusive {
        try {
            active.verifyLoadedContext()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (activeBackend != BackendMode.VULKAN) {
                recordTerminalError(error)
                throw error
            }
            recoverToCpu(error.failureStage(BackendFailureStage.VERIFY), error, false)
            cpu.verifyLoadedContext()
        }
    }

    override fun cancel() {
        cpu.cancel()
        vulkan.cancel()
    }

    override fun releaseResidentPages() {
        cpu.releaseResidentPages()
        vulkan.releaseResidentPages()
    }

    // Embedding always runs on the CPU engine: it is a small model, it must not
    // contend with the chat backend, and it has no fallback semantics to recover.
    override suspend fun loadEmbedder(path: String) = cpu.loadEmbedder(path)

    override suspend fun unloadEmbedder() = cpu.unloadEmbedder()

    override val embeddingDimensions: Int
        get() = cpu.embeddingDimensions

    override suspend fun embed(text: String): FloatArray? = cpu.embed(text)

    override suspend fun unload() = operationGate.runExclusive {
        cancel()
        runCatching { active.unload() }
        if (active !== cpu) runCatching { cpu.unload() }
        if (active !== vulkan) runCatching { vulkan.unload() }
        clearCachedState()
        _state.value = InferenceState.Idle
        _metrics.value = InferenceMetrics()
    }

    override suspend fun benchmark(
        path: String,
        displayName: String,
        settings: GenerationSettings,
    ): Map<BackendMode, InferenceBenchmarkSample> = operationGate.runExclusive {
        cancel()
        runCatching { cpu.unload() }
        runCatching { vulkan.unload() }
        benchmarkFailures.clear()
        val samples = linkedMapOf<BackendMode, InferenceBenchmarkSample>()
        captureBenchmark(BackendMode.CPU) { cpu.benchmark(path, displayName, settings) }
            .let(samples::putAll)
        captureBenchmark(BackendMode.VULKAN) { vulkan.benchmark(path, displayName, settings) }
            .let(samples::putAll)
        if (samples.containsKey(BackendMode.VULKAN)) {
            locallyQuarantinedModels.entries.removeAll { it.value == path }
        }
        clearCachedState()
        _state.value = InferenceState.Idle
        samples
    }

    override fun benchmarkFailure(backend: BackendMode): BackendInferenceException? =
        benchmarkFailures[backend]

    override fun systemInfo(): String = buildString {
        append(cpu.systemInfo())
        runCatching { vulkan.systemInfo() }.getOrNull()?.takeIf(String::isNotBlank)?.let {
            append("\nVulkan service: ")
            append(it)
        }
    }

    override fun destroy() {
        cancel()
        cpu.destroy()
        vulkan.destroy()
    }

    private suspend fun recoverToCpu(
        stage: BackendFailureStage,
        error: Throwable,
        restoreSession: Boolean,
    ) {
        val loaded = requireNotNull(model) { "No model is available for CPU recovery." }
        _state.value = InferenceState.Recovering(
            loaded.displayName,
            BackendMode.VULKAN,
            BackendMode.CPU,
            stage,
        )
        loaded.configuration.modelSha256?.let { locallyQuarantinedModels[it] = loaded.path }
        runCatching {
            recoveryPolicy.quarantine(
                loaded.configuration.modelId,
                loaded.configuration.modelSha256,
                stage,
                error.message ?: "Vulkan inference failed.",
            )
        }
        runCatching { vulkan.unload() }
        active = cpu
        activeBackend = BackendMode.CPU
        try {
            if (cpu.loadedModelPath != null) cpu.unload()
            val cpuConfiguration = runCatching {
                cpuFallbackConfigurationResolver(loaded.configuration)
            }.getOrElse {
                loaded.configuration.safeCpuFallback()
            }.copy(backend = BackendMode.CPU)
            cpu.loadModel(
                loaded.path,
                loaded.displayName,
                cpuConfiguration,
            )
            projector?.let {
                projectorCapabilities = cpu.loadProjector(it.path, it.imageTokenBudget)
            }
            if (restoreSession) {
                session?.let { restored ->
                    cpu.restoreSession(restored.conversationId, restored.history, restored.settings)
                }
            }
            pendingFallback = GenerationEvent.BackendFallback(
                from = BackendMode.VULKAN,
                to = BackendMode.CPU,
                stage = stage,
                discardPartialOutput = false,
            )
            syncFromActive()
        } catch (cpuError: Throwable) {
            _state.value = InferenceState.Error(cpuError.message ?: "CPU recovery failed.")
            throw cpuError
        }
    }

    private fun syncFromActive() {
        _metrics.value = active.metrics.value
        _state.value = active.state.value
    }

    private fun recordTerminalError(error: Throwable) {
        _state.value = InferenceState.Error(error.message ?: "Inference failed.")
    }

    private fun clearCachedState() {
        active = cpu
        activeBackend = BackendMode.CPU
        model = null
        projector = null
        projectorCapabilities = null
        session = null
        pendingFallback = null
    }

    private suspend fun captureBenchmark(
        backend: BackendMode,
        block: suspend () -> Map<BackendMode, InferenceBenchmarkSample>,
    ): Map<BackendMode, InferenceBenchmarkSample> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        benchmarkFailures[backend] = error as? BackendInferenceException
            ?: BackendInferenceException(
                backend,
                BackendFailureStage.BENCHMARK,
                error.message ?: "$backend benchmark failed.",
                error,
            )
        emptyMap()
    }
}

private data class LoadedModel(
    val path: String,
    val displayName: String,
    val configuration: ModelLoadConfiguration,
)

private data class LoadedProjector(
    val path: String,
    val imageTokenBudget: Int,
)

private data class RestoredSession(
    val conversationId: String,
    val history: List<ChatTurn>,
    val settings: GenerationSettings,
)

private fun Throwable.failureStage(default: BackendFailureStage): BackendFailureStage =
    (this as? BackendInferenceException)?.stage ?: default

private fun ModelLoadConfiguration.safeCpuFallback(): ModelLoadConfiguration = copy(
    contextTokens = contextTokens.coerceAtMost(SAFE_CPU_CONTEXT_TOKENS),
    backend = BackendMode.CPU,
)

private const val SAFE_CPU_CONTEXT_TOKENS = 4_096
