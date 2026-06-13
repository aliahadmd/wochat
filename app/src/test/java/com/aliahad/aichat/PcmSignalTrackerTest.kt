package com.aliahad.aichat

import com.aliahad.aichat.speech.PcmSignalTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSignalTrackerTest {
    @Test
    fun silenceIsNotReportedAsAudible() {
        val tracker = PcmSignalTracker()

        tracker.add(ShortArray(1_600), 1_600)

        assertFalse(tracker.summary().hasAudibleSignal)
    }

    @Test
    fun normalSpeechAmplitudeIsReportedAsAudible() {
        val tracker = PcmSignalTracker()
        val samples = ShortArray(1_600) { index ->
            if (index % 2 == 0) 2_000 else -2_000
        }

        tracker.add(samples, samples.size)

        assertTrue(tracker.summary().hasAudibleSignal)
    }
}
