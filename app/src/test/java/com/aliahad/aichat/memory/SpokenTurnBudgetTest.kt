package com.aliahad.aichat.memory

import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.TurnOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A spoken turn is capped shorter than a typed one, and told to sound like speech.
 * These guard the two ways that has gone wrong before: silently changing a typed
 * turn, and wording an instruction so the model stops using the conversation.
 */
class SpokenTurnBudgetTest {

    private val settings = GenerationSettings(maxNewTokens = 1024, maxAnswerTokens = 8192)

    @Test
    fun `a spoken turn is capped`() {
        val capped = settings.cappedFor(ContextBudget.COMPACT)
        assertEquals(160, capped.maxNewTokens)
        assertEquals(160, capped.maxAnswerTokens)
    }

    @Test
    fun `a typed turn keeps the user's own limits`() {
        assertEquals(settings, settings.cappedFor(ContextBudget.FULL))
    }

    @Test
    fun `a shorter user limit is never raised by the cap`() {
        // The point is a ceiling, not an assignment. Someone who chose 80 meant it.
        val terse = GenerationSettings(maxNewTokens = 80, maxAnswerTokens = 80)
        val capped = terse.cappedFor(ContextBudget.COMPACT)
        assertEquals(80, capped.maxNewTokens)
        assertEquals(80, capped.maxAnswerTokens)
    }

    @Test
    fun `only a spoken turn carries the style instruction`() {
        assertNull(ContextBudget.FULL.spokenStyle)
        assertNull(ContextBudget.FULL.maxAnswerTokens)
        assertTrue(requireNotNull(ContextBudget.COMPACT.spokenStyle).isNotBlank())
    }

    @Test
    fun `voice maps to the spoken budget and typing does not`() {
        assertEquals(ContextBudget.COMPACT, ContextBudget.forOrigin(TurnOrigin.VOICE))
        assertEquals(ContextBudget.FULL, ContextBudget.forOrigin(TurnOrigin.TYPED))
    }

    @Test
    fun `the style instruction talks about form, never about what context exists`() {
        // The memory header regression: wording that reads as a declaration of the
        // available context made the model answer "the memory does not contain a
        // list of rivers" while the conversation sat in the KV cache. A style note
        // must not reintroduce that, so it may not claim anything about sources.
        val style = requireNotNull(ContextBudget.COMPACT.spokenStyle).lowercase()
        listOf("context", "only", "provided", "based on", "memory").forEach { claim ->
            assertFalse("style instruction should not scope the model's context: $claim",
                style.contains(claim))
        }
    }

    @Test
    fun `the style instruction stays cheap enough to prefill every turn`() {
        // Prefill is compute-bound at ~56 ms/token cold, so this is paid on every
        // spoken turn. Roughly four characters per token; 60 tokens would be ~3.4 s.
        val style = requireNotNull(ContextBudget.COMPACT.spokenStyle)
        assertTrue("style instruction is ${style.length} chars", style.length < 240)
    }
}
