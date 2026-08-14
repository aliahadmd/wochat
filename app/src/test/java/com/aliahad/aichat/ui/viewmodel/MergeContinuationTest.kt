package com.aliahad.aichat.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Characterization tests for the file-level [mergeContinuation] overlap-stitching helper.
 * Expected values are pinned against the current implementation:
 * - overlap search window is [12, minOf(existing.length, continuation.length, 320)]
 * - overlap is only matched at the tail of [existing] against the head of [continuation]
 */
class MergeContinuationTest {

    @Test
    fun emptyExistingReturnsContinuationAsIs() {
        assertEquals("abc", mergeContinuation("", "abc"))
    }

    @Test
    fun emptyContinuationReturnsExistingAsIs() {
        assertEquals("abc", mergeContinuation("abc", ""))
    }

    @Test
    fun exactTailOverlapOfTwelveCharsIsStitchedOnce() {
        val existing = "prefix_text_OVERLAP12345"
        val continuation = "OVERLAP12345_suffix"

        assertEquals(
            "prefix_text_OVERLAP12345_suffix",
            mergeContinuation(existing, continuation),
        )
    }

    @Test
    fun overlapShorterThanTwelveCharsIsNotStitched() {
        val existing = "head" + "Q".repeat(11)
        val continuation = "Q".repeat(11) + "tail"

        assertEquals(existing + continuation, mergeContinuation(existing, continuation))
    }

    @Test
    fun overlapLongerThanThreeTwentyIsCappedAtThreeTwenty() {
        val shared = "A".repeat(400)
        val existing = "START$shared"
        val continuation = "${shared}ENDMARKER"

        val merged = mergeContinuation(existing, continuation)

        // Only 320 chars of the 400-char overlap are removed, so the first 80 chars
        // of the continuation beyond the cap remain visible.
        assertEquals("START${"A".repeat(480)}ENDMARKER", merged)
    }

    @Test
    fun noOverlapProducesPlainConcatenation() {
        assertEquals("helloworld", mergeContinuation("hello", "world"))
    }

    @Test
    fun overlapOnlyInTheMiddleIsIgnored() {
        val existing = "start MIDDLESEGMENT1234567890 mid"
        val continuation = "MIDDLESEGMENT1234567890 tail"

        assertEquals(existing + continuation, mergeContinuation(existing, continuation))
    }
}
