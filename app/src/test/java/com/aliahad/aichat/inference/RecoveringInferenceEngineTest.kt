package com.aliahad.aichat.inference

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveringInferenceEngineTest {
    @Test
    fun vulkanDecodeFailureDiscardsPartialAttemptAndReplaysOnceOnCpu() = runTest {
        val cpu = FakeInferenceEngine(BackendMode.CPU).apply {
            generation = {
                flowOf(
                    GenerationEvent.AnswerDelta("safe"),
                    GenerationEvent.Completed(GenerationStopReason.EOG, 1, 0),
                )
            }
        }
        val vulkan = FakeInferenceEngine(BackendMode.VULKAN).apply {
            generation = {
                flow {
                    emit(GenerationEvent.AnswerDelta("discard me"))
                    throw BackendInferenceException(
                        BackendMode.VULKAN,
                        BackendFailureStage.DECODE,
                        "device lost",
                    )
                }
            }
        }
        val policy = FakeRecoveryPolicy(BackendMode.VULKAN)
        val engine = RecoveringInferenceEngine(cpu, vulkan, policy)

        engine.loadModel("/model", "Gemma", modelConfiguration())
        engine.restoreSession("chat", emptyList(), GenerationSettings())
        val events = engine.generate(
            UserTurn("chat", "hello"),
            GenerationSettings(),
            InferenceExecutionProfile.NORMAL,
        ).toList()

        assertEquals("discard me", (events[0] as GenerationEvent.AnswerDelta).text)
        val fallback = events.filterIsInstance<GenerationEvent.BackendFallback>().single()
        assertTrue(fallback.discardPartialOutput)
        assertEquals(BackendFailureStage.DECODE, fallback.stage)
        assertEquals("safe", events.filterIsInstance<GenerationEvent.AnswerDelta>().last().text)
        assertEquals(1, cpu.loadCount)
        assertEquals(1, cpu.restoreCount)
        assertEquals(1, cpu.generateCount)
        assertEquals(1, policy.quarantines.size)
        assertEquals(BackendMode.CPU, (engine.state.value as InferenceState.Ready).backend)
    }

    @Test
    fun vulkanLoadFailureFallsBackBeforeGenerationAndSignalsNonDestructiveReset() = runTest {
        val cpu = FakeInferenceEngine(BackendMode.CPU)
        val vulkan = FakeInferenceEngine(BackendMode.VULKAN).apply {
            loadFailure = BackendInferenceException(
                BackendMode.VULKAN,
                BackendFailureStage.LOAD,
                "load crash",
            )
        }
        val policy = FakeRecoveryPolicy(BackendMode.VULKAN)
        val engine = RecoveringInferenceEngine(cpu, vulkan, policy)

        engine.loadModel("/model", "Gemma", modelConfiguration())
        engine.restoreSession("chat", emptyList(), GenerationSettings())
        val events = engine.generate(
            UserTurn("chat", "hello"),
            GenerationSettings(),
            InferenceExecutionProfile.NORMAL,
        ).toList()

        val fallback = events.first() as GenerationEvent.BackendFallback
        assertFalse(fallback.discardPartialOutput)
        assertEquals(BackendFailureStage.LOAD, fallback.stage)
        assertEquals(1, cpu.generateCount)
        assertEquals(1, policy.quarantines.size)
    }

    @Test
    fun cpuFailureIsNotRetried() = runTest {
        val cpu = FakeInferenceEngine(BackendMode.CPU).apply {
            generation = { flow { throw IllegalStateException("cpu failed") } }
        }
        val vulkan = FakeInferenceEngine(BackendMode.VULKAN)
        val policy = FakeRecoveryPolicy(BackendMode.CPU)
        val engine = RecoveringInferenceEngine(cpu, vulkan, policy)
        engine.loadModel("/model", "Gemma", modelConfiguration())
        engine.restoreSession("chat", emptyList(), GenerationSettings())

        val failure = runCatching {
            engine.generate(
                UserTurn("chat", "hello"),
                GenerationSettings(),
                InferenceExecutionProfile.NORMAL,
            ).toList()
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(1, cpu.generateCount)
        assertEquals(0, vulkan.generateCount)
        assertTrue(policy.quarantines.isEmpty())
    }

    @Test
    fun cancellationNeverTriggersCpuReplay() = runTest {
        val cpu = FakeInferenceEngine(BackendMode.CPU)
        val vulkan = FakeInferenceEngine(BackendMode.VULKAN).apply {
            generation = { flow { throw CancellationException("cancelled") } }
        }
        val policy = FakeRecoveryPolicy(BackendMode.VULKAN)
        val engine = RecoveringInferenceEngine(cpu, vulkan, policy)
        engine.loadModel("/model", "Gemma", modelConfiguration())
        engine.restoreSession("chat", emptyList(), GenerationSettings())

        runCatching {
            engine.generate(
                UserTurn("chat", "hello"),
                GenerationSettings(),
                InferenceExecutionProfile.NORMAL,
            ).toList()
        }

        assertEquals(0, cpu.generateCount)
        assertTrue(policy.quarantines.isEmpty())
    }

    @Test
    fun downstreamCollectorFailureNeverQuarantinesOrReplaysVulkan() = runTest {
        val cpu = FakeInferenceEngine(BackendMode.CPU)
        val vulkan = FakeInferenceEngine(BackendMode.VULKAN).apply {
            generation = {
                flowOf(
                    GenerationEvent.AnswerDelta("persist me"),
                    GenerationEvent.Completed(GenerationStopReason.EOG, 1, 0),
                )
            }
        }
        val policy = FakeRecoveryPolicy(BackendMode.VULKAN)
        val engine = RecoveringInferenceEngine(cpu, vulkan, policy)
        engine.loadModel("/model", "Gemma", modelConfiguration())
        engine.restoreSession("chat", emptyList(), GenerationSettings())

        val failure = runCatching {
            engine.generate(
                UserTurn("chat", "hello"),
                GenerationSettings(),
                InferenceExecutionProfile.NORMAL,
            ).collect { event ->
                if (event is GenerationEvent.AnswerDelta) error("database write failed")
            }
        }.exceptionOrNull()

        assertEquals("database write failed", failure?.message)
        assertEquals(0, cpu.loadCount)
        assertEquals(0, cpu.generateCount)
        assertTrue(policy.quarantines.isEmpty())
    }

    @Test
    fun cpuReplayNeverReusesOversizedVulkanContextByDefault() = runTest {
        val cpu = FakeInferenceEngine(BackendMode.CPU)
        val vulkan = FakeInferenceEngine(BackendMode.VULKAN).apply {
            generation = {
                flow {
                    throw BackendInferenceException(
                        BackendMode.VULKAN,
                        BackendFailureStage.DECODE,
                        "device lost",
                    )
                }
            }
        }
        val engine = RecoveringInferenceEngine(
            cpu,
            vulkan,
            FakeRecoveryPolicy(BackendMode.VULKAN),
        )
        engine.loadModel(
            "/model",
            "Gemma",
            modelConfiguration().copy(contextTokens = 16_384),
        )
        engine.restoreSession("chat", emptyList(), GenerationSettings())

        engine.generate(
            UserTurn("chat", "hello"),
            GenerationSettings(),
            InferenceExecutionProfile.NORMAL,
        ).toList()

        assertEquals(4_096, cpu.lastLoadConfiguration?.contextTokens)
        assertEquals(BackendMode.CPU, cpu.lastLoadConfiguration?.backend)
    }

    @Test
    fun remoteBenchmarkCrashKeepsCpuSample() = runTest {
        val cpuSample = InferenceBenchmarkSample(10, 20.0, 30.0)
        val cpu = FakeInferenceEngine(BackendMode.CPU).apply {
            benchmarkResult = mapOf(BackendMode.CPU to cpuSample)
        }
        val vulkan = FakeInferenceEngine(BackendMode.VULKAN).apply {
            benchmarkFailure = BackendInferenceException(
                BackendMode.VULKAN,
                BackendFailureStage.SERVICE_DIED,
                "process died",
            )
        }
        val engine = RecoveringInferenceEngine(
            cpu,
            vulkan,
            FakeRecoveryPolicy(BackendMode.CPU),
        )

        val samples = engine.benchmark("/model", "Gemma", GenerationSettings())

        assertEquals(cpuSample, samples[BackendMode.CPU])
        assertFalse(samples.containsKey(BackendMode.VULKAN))
    }

    private fun modelConfiguration() = ModelLoadConfiguration(
        contextTokens = 4_096,
        declaredContextTokens = 128_000,
        backend = BackendMode.VULKAN,
        temperature = 0.2f,
        modelId = "e2b",
        modelSha256 = "abc123",
    )
}

private class FakeRecoveryPolicy(
    private val resolved: BackendMode,
) : BackendRecoveryController {
    val quarantines = mutableListOf<BackendFailureStage>()

    override suspend fun resolve(requested: BackendMode, modelSha256: String?): BackendMode = resolved

    override suspend fun quarantine(
        modelId: String?,
        modelSha256: String?,
        stage: BackendFailureStage,
        reason: String,
    ) {
        quarantines += stage
    }
}

private class FakeInferenceEngine(
    private val backend: BackendMode,
) : InferenceEngine {
    private val mutableState = MutableStateFlow<InferenceState>(InferenceState.Idle)
    override val state: StateFlow<InferenceState> = mutableState
    override val metrics: StateFlow<InferenceMetrics> = MutableStateFlow(InferenceMetrics())
    override var loadedModelPath: String? = null
    override var loadedProjectorPath: String? = null
    override var loadedCapabilities: ModelCapabilities? = null
    override var modelContextLimit: Int = 128_000
    override var activeContextSize: Int = 0
    var loadFailure: Throwable? = null
    var benchmarkFailure: Throwable? = null
    var benchmarkResult: Map<BackendMode, InferenceBenchmarkSample> = emptyMap()
    var generation: () -> Flow<GenerationEvent> = {
        flowOf(GenerationEvent.Completed(GenerationStopReason.EOG, 0, 0))
    }
    var loadCount = 0
    var restoreCount = 0
    var generateCount = 0
    var lastLoadConfiguration: ModelLoadConfiguration? = null

    override suspend fun loadModel(
        path: String,
        displayName: String,
        configuration: ModelLoadConfiguration,
    ) {
        loadCount++
        loadFailure?.let { throw it }
        lastLoadConfiguration = configuration
        loadedModelPath = path
        activeContextSize = configuration.contextTokens
        mutableState.value = InferenceState.Ready(displayName, backend)
    }

    override suspend fun loadProjector(path: String, imageTokenBudget: Int): ModelCapabilities {
        loadedProjectorPath = path
        return ModelCapabilities(true, true, modelContextLimit).also { loadedCapabilities = it }
    }

    override suspend fun unloadProjector() {
        loadedProjectorPath = null
        loadedCapabilities = null
    }

    override suspend fun restoreSession(
        conversationId: String,
        history: List<ChatTurn>,
        settings: GenerationSettings,
    ) {
        restoreCount++
    }

    override fun generate(
        turn: UserTurn,
        settings: GenerationSettings,
        profile: InferenceExecutionProfile,
    ): Flow<GenerationEvent> {
        generateCount++
        return generation()
    }

    override suspend fun countTokens(text: String): Int = text.length

    override suspend fun verifyLoadedContext(): Int = 1

    override fun cancel() = Unit

    override suspend fun unload() {
        loadedModelPath = null
        loadedProjectorPath = null
        loadedCapabilities = null
        activeContextSize = 0
        mutableState.value = InferenceState.Idle
    }

    override suspend fun benchmark(
        path: String,
        displayName: String,
        settings: GenerationSettings,
    ): Map<BackendMode, InferenceBenchmarkSample> {
        benchmarkFailure?.let { throw it }
        return benchmarkResult
    }

    override fun systemInfo(): String = backend.name

    override fun destroy() = Unit
}
