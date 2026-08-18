package com.aliahad.aichat.ui.viewmodel

import com.aliahad.aichat.core.MessageStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spoken answer stopped a sentence short of the written one.
 *
 * The runner clears `isSending` synchronously in its `finally` block, while the final
 * message content reaches this flow through Room. Keying completion on `isSending`
 * therefore marked the turn finished while the text was still the previous ~250 ms
 * snapshot, and the call stopped collecting before the tail ever arrived. Reported
 * from a real call: three sentences stored, two spoken.
 */
class VoiceAnswerCompletionTest {

    @Test
    fun `a streaming row is still streaming even after the runner stops sending`() {
        // The exact race. The runner has finished and cleared isSending, but Room has
        // not re-emitted the row yet, so its text is stale and must not be treated as
        // the final answer.
        assertTrue(isStreaming(MessageStatus.STREAMING, running = false))
    }

    @Test
    fun `a completed row ends the turn even while the runner still reports sending`() {
        // The row carries the final content and the final status in one update, so it
        // is safe to finish on as soon as it lands.
        assertFalse(isStreaming(MessageStatus.COMPLETE, running = true))
    }

    @Test
    fun `a streaming row with the runner still sending is streaming`() {
        assertTrue(isStreaming(MessageStatus.STREAMING, running = true))
    }

    @Test
    fun `every terminal status ends the turn`() {
        // Cancellation and errors have to end the speaking phase too, or the call
        // hangs waiting for an answer that is never coming.
        listOf(
            MessageStatus.COMPLETE,
            MessageStatus.CONTINUABLE,
            MessageStatus.CANCELLED,
            MessageStatus.ERROR,
        ).forEach { status ->
            assertFalse("$status should end the turn", isStreaming(status, running = true))
        }
    }

    @Test
    fun `with no assistant row at all the runner decides`() {
        // A turn that failed before creating a row must not leave the call speaking
        // forever, so this is the one case where isSending is still the authority.
        assertTrue(isStreaming(null, running = true))
        assertFalse(isStreaming(null, running = false))
    }
}
