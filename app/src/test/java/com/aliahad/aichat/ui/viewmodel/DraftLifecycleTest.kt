package com.aliahad.aichat.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the draft-clearing rule. Two failure modes sit either side of it:
 * clearing too eagerly throws away what the user typed on every cold start,
 * and not clearing at all lets a draft follow the user into another
 * conversation and be sent there by mistake.
 */
class DraftLifecycleTest {

    @Test
    fun `clears when moving between two different conversations`() {
        assertTrue(shouldClearDraftOnSwitch(previous = "conv-a", next = "conv-b"))
    }

    @Test
    fun `keeps the draft when the same conversation is restored`() {
        // Process death restores selectedConversationId before the observer
        // attaches; this must not look like a switch.
        assertFalse(shouldClearDraftOnSwitch(previous = "conv-a", next = "conv-a"))
    }

    @Test
    fun `keeps the draft on the first selection of the session`() {
        assertFalse(shouldClearDraftOnSwitch(previous = null, next = "conv-a"))
    }

    @Test
    fun `clears when a newly created conversation is selected`() {
        // Creating a chat selects it; the composer should start empty.
        assertTrue(shouldClearDraftOnSwitch(previous = "conv-a", next = "conv-new"))
    }
}
