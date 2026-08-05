package com.aliahad.aichat.inference

import android.content.Context
import android.os.PowerManager
import android.os.Build
import com.aliahad.aichat.BuildConfig
import com.aliahad.aichat.context.ContextRuntimeMonitor
import com.aliahad.aichat.context.ContextMemoryPolicy
import com.aliahad.aichat.context.DeviceContextIdentity
import com.aliahad.aichat.core.BackendBenchmark
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.InferenceBenchmarkSample
import com.aliahad.aichat.data.ModelBenchmarkDao
import com.aliahad.aichat.data.ModelBenchmarkEntity
import com.aliahad.aichat.model.ModelRepository
import com.aliahad.aichat.residency.ModelResidencyController
import com.aliahad.aichat.settings.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

interface ModelBenchmarkRepository {
    fun observe(modelId: String): Flow<List<BackendBenchmark>>
    suspend fun forCurrentRuntime(modelSha256: String): List<BackendBenchmark>
    suspend fun upsert(benchmark: BackendBenchmark)
}

class RoomModelBenchmarkRepository(
    context: Context,
    private val dao: ModelBenchmarkDao,
) : ModelBenchmarkRepository {
    private val device = DeviceContextIdentity.read(context)

    override fun observe(modelId: String): Flow<List<BackendBenchmark>> =
        dao.observeForModel(modelId).map { rows -> rows.map(ModelBenchmarkEntity::toDomain) }

    override suspend fun forCurrentRuntime(modelSha256: String): List<BackendBenchmark> =
        dao.forConfiguration(modelSha256, device.key, BuildConfig.LLAMA_RUNTIME_REVISION)
            .map(ModelBenchmarkEntity::toDomain)

    override suspend fun upsert(benchmark: BackendBenchmark) {
        dao.upsert(benchmark.toEntity())
    }
}

interface InferenceBenchmarkRunner {
    suspend fun optimizeSelectedModel(): List<BackendBenchmark>
}

class DefaultInferenceBenchmarkRunner(
    context: Context,
    private val modelRepository: ModelRepository,
    private val inferenceEngine: InferenceEngine,
    private val residencyController: ModelResidencyController,
    private val settings: AppSettingsRepository,
    private val benchmarks: ModelBenchmarkRepository,
) : InferenceBenchmarkRunner {
    private val device = DeviceContextIdentity.read(context)
    private val monitor = ContextRuntimeMonitor(context)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    override suspend fun optimizeSelectedModel(): List<BackendBenchmark> {
        val selected = requireNotNull(modelRepository.selectedModel()) { "Select a model first." }
        val model = modelRepository.ensureSha256(selected)
        val path = requireNotNull(model.localPath) { "The selected model is unavailable." }
        val beforeThermal = powerManager.currentThermalStatus
        residencyController.unload()
        var samples = emptyMap<BackendMode, InferenceBenchmarkSample>()
        val memory = monitor.measureVerification {
            samples = inferenceEngine.benchmark(
                path,
                model.displayName,
                settings.generationSettings.first(),
            )
            1
        }
        val afterThermal = powerManager.currentThermalStatus
        val pssCeiling = ContextMemoryPolicy.stablePssCeilingBytes(
            Build.MANUFACTURER,
            device.physicalRamBytes,
        )
        val memoryStable = runCatching { monitor.requireStable(memory) }.isSuccess &&
            (samples[BackendMode.VULKAN]?.peakPssBytes ?: 0L) < pssCeiling
        val now = System.currentTimeMillis()
        val results = listOf(BackendMode.CPU, BackendMode.VULKAN).map { backend ->
            val sample = samples[backend]
            BackendBenchmark(
                id = benchmarkStableId(requireNotNull(model.sha256), device.key, backend),
                modelId = model.id,
                modelSha256 = requireNotNull(model.sha256),
                deviceFingerprint = device.key,
                backend = backend,
                llamaRevision = BuildConfig.LLAMA_RUNTIME_REVISION,
                loadMillis = sample?.loadMillis,
                promptTokensPerSecond = sample?.promptTokensPerSecond,
                generationTokensPerSecond = sample?.generationTokensPerSecond,
                peakPssBytes = sample?.peakPssBytes ?: memory.pssBytes,
                thermalDelta = afterThermal - beforeThermal,
                success = sample != null,
                failureReason = if (sample == null) {
                    (inferenceEngine as? BenchmarkFailureSource)
                        ?.benchmarkFailure(backend)
                        ?.let { "${it.stage.name}: ${it.message}" }
                        ?: "$backend failed its native benchmark."
                } else {
                    null
                },
                measuredAt = now,
            ).also { benchmarks.upsert(it) }
        }
        val cpu = results.first { it.backend == BackendMode.CPU }
        val vulkan = results.first { it.backend == BackendMode.VULKAN }
        val useVulkan = vulkan.success && cpu.success &&
            memoryStable &&
            requireNotNull(vulkan.generationTokensPerSecond) >=
            requireNotNull(cpu.generationTokensPerSecond) * VULKAN_MINIMUM_SPEEDUP &&
            afterThermal < PowerManager.THERMAL_STATUS_SEVERE
        settings.setChosenAutoBackend(if (useVulkan) BackendMode.VULKAN else BackendMode.CPU)
        if (vulkan.success) {
            settings.clearVulkanQuarantine(
                requireNotNull(model.sha256),
                device.key,
                BuildConfig.LLAMA_RUNTIME_REVISION,
            )
        }
        settings.setBackend(BackendMode.AUTO)
        residencyController.ensureLoaded()
        return results
    }

    private companion object {
        const val VULKAN_MINIMUM_SPEEDUP = 1.15

    }
}

internal fun benchmarkStableId(modelSha256: String, device: String, backend: BackendMode): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$modelSha256:$device:${backend.name}:${BuildConfig.LLAMA_RUNTIME_REVISION}"
            .toByteArray())
        .joinToString("") { "%02x".format(it) }

private fun ModelBenchmarkEntity.toDomain() = BackendBenchmark(
    id, modelId, modelSha256, deviceFingerprint, backend, llamaRevision, loadMillis,
    promptTokensPerSecond, generationTokensPerSecond, peakPssBytes, thermalDelta,
    success, failureReason, measuredAt,
)

private fun BackendBenchmark.toEntity() = ModelBenchmarkEntity(
    id, modelId, modelSha256, deviceFingerprint, backend, llamaRevision, loadMillis,
    promptTokensPerSecond, generationTokensPerSecond, peakPssBytes, thermalDelta,
    success, failureReason, measuredAt,
)
