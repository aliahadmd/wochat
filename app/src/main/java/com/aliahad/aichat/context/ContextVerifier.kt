package com.aliahad.aichat.context

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import com.aliahad.aichat.core.ContextVerificationMetrics
import com.aliahad.aichat.core.ContextVerificationState
import com.aliahad.aichat.core.ModelContextProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

interface ContextVerifier {
    fun setUiForeground(foreground: Boolean)
    suspend fun beginInferenceUse()
    fun endInferenceUse()
    fun scheduleVerification()
    suspend fun reverifySelectedModel()
}

object ContextCandidates {
    private val steps = listOf(
        4_096,
        8_192,
        16_384,
        32_768,
        65_536,
        131_072,
        262_144,
    )

    fun remaining(profile: ModelContextProfile): List<Int> {
        val declared = profile.declaredContextTokens
        if (declared <= 0) return emptyList()
        if (profile.state in setOf(
                ContextVerificationState.LIMITED,
                ContextVerificationState.FAILED,
            )
        ) {
            return emptyList()
        }
        val baseline = minOf(SAFE_CONTEXT_TOKENS, declared)
        val candidates = steps.filter { it in baseline..declared }.toMutableList()
        if (declared !in candidates) candidates += declared
        return candidates.distinct().sorted().filter { it > profile.verifiedContextTokens }
    }

    private const val SAFE_CONTEXT_TOKENS = 4_096
}

object ContextMemoryPolicy {
    private const val GIB = 1_024L * 1_024 * 1_024
    private const val MIB = 1_024L * 1_024
    private const val HYPEROS_PROCESS_PSS_LIMIT_BYTES = 6L * GIB
    private const val HYPEROS_HEADROOM_BYTES = 128L * MIB

    fun stablePssCeilingBytes(manufacturer: String, physicalRamBytes: Long): Long {
        val vendor = manufacturer.lowercase()
        return if (vendor.contains("xiaomi") ||
            vendor.contains("redmi") ||
            vendor.contains("poco")
        ) {
            minOf(
                HYPEROS_PROCESS_PSS_LIMIT_BYTES - HYPEROS_HEADROOM_BYTES,
                physicalRamBytes * 4 / 5,
            )
        } else {
            physicalRamBytes * 4 / 5
        }
    }
}

class ContextRuntimeMonitor(
    context: Context,
) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val physicalRamBytes = ActivityManager.MemoryInfo().also {
        activityManager.getMemoryInfo(it)
    }.totalMem
    private val stablePssCeilingBytes = ContextMemoryPolicy.stablePssCeilingBytes(
        manufacturer = Build.MANUFACTURER,
        physicalRamBytes = physicalRamBytes,
    )

    fun requireStable() {
        check(powerManager.currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE) {
            "Verification paused because the phone is thermally constrained."
        }
        val memory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)
        check(!memory.lowMemory) {
            "Verification paused because Android reports critical memory pressure."
        }
    }

    fun requireStable(metrics: ContextVerificationMetrics) {
        check(metrics.pssBytes < stablePssCeilingBytes) {
            "Peak PSS ${formatBytes(metrics.pssBytes)} exceeds this phone's stable " +
                "${formatBytes(stablePssCeilingBytes)} HyperOS ceiling."
        }
        requireStable()
    }

    suspend fun measureVerification(block: suspend () -> Int): ContextVerificationMetrics =
        coroutineScope {
            val peakPss = AtomicLong()
            val peakRss = AtomicLong()
            val peakSwap = AtomicLong()
            fun sample() {
                val metrics = snapshot(0)
                peakPss.accumulateAndGet(metrics.pssBytes, ::maxOf)
                peakRss.accumulateAndGet(metrics.rssBytes, ::maxOf)
                peakSwap.accumulateAndGet(metrics.swapBytes, ::maxOf)
            }
            sample()
            val sampler = launch(Dispatchers.Default) {
                while (isActive) {
                    delay(100)
                    sample()
                }
            }
            try {
                val generated = block()
                delay(500)
                sample()
                ContextVerificationMetrics(
                    generatedTokens = generated,
                    pssBytes = peakPss.get(),
                    rssBytes = peakRss.get(),
                    swapBytes = peakSwap.get(),
                )
            } finally {
                sampler.cancel()
            }
        }

    fun snapshot(generatedTokens: Int): ContextVerificationMetrics {
        val debug = Debug.MemoryInfo()
        Debug.getMemoryInfo(debug)
        val status = readProcessStatus()
        return ContextVerificationMetrics(
            generatedTokens = generatedTokens,
            pssBytes = debug.totalPss.toLong() * 1_024,
            rssBytes = status["VmRSS"] ?: 0L,
            swapBytes = status["VmSwap"] ?: 0L,
        )
    }

    private fun readProcessStatus(): Map<String, Long> =
        runCatching {
            java.io.File("/proc/self/status").useLines { lines ->
                lines.mapNotNull { line ->
                    val name = line.substringBefore(':')
                    if (name != "VmRSS" && name != "VmSwap") return@mapNotNull null
                    val value = line.substringAfter(':')
                        .trim()
                        .substringBefore(' ')
                        .toLongOrNull()
                        ?: return@mapNotNull null
                    name to value * 1_024
                }.toMap()
            }
        }.getOrDefault(emptyMap())

    private fun formatBytes(bytes: Long): String =
        "%.2f GiB".format(bytes.toDouble() / (1_024.0 * 1_024 * 1_024))
}
