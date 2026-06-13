package com.aliahad.aichat.speech

import kotlin.math.abs
import kotlin.math.sqrt

internal data class PcmSignalSummary(
    val peakAmplitude: Int,
    val rmsNormalized: Double,
) {
    val hasAudibleSignal: Boolean
        get() = peakAmplitude >= MIN_AUDIBLE_PEAK && rmsNormalized >= MIN_AUDIBLE_RMS

    private companion object {
        const val MIN_AUDIBLE_PEAK = 700
        const val MIN_AUDIBLE_RMS = 0.003
    }
}

internal class PcmSignalTracker {
    private var peakAmplitude = 0
    private var squaredAmplitudeSum = 0.0
    private var sampleCount = 0L

    fun add(samples: ShortArray, count: Int) {
        require(count in 0..samples.size)
        repeat(count) { index ->
            val amplitude = samples[index].toInt()
            peakAmplitude = maxOf(peakAmplitude, abs(amplitude))
            squaredAmplitudeSum += amplitude.toDouble() * amplitude
        }
        sampleCount += count
    }

    fun summary(): PcmSignalSummary {
        val rms = if (sampleCount == 0L) {
            0.0
        } else {
            sqrt(squaredAmplitudeSum / sampleCount) / Short.MAX_VALUE
        }
        return PcmSignalSummary(
            peakAmplitude = peakAmplitude,
            rmsNormalized = rms,
        )
    }
}
