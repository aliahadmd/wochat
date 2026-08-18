package com.aliahad.aichat.inference

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A saved sequence is a KV cache built from exact tokens. Adopting one that does not
 * match would not merely be stale — the model would answer from a history that never
 * happened. Every field checked here is one that changes those tokens.
 */
class SessionStateStoreTest {

    private val messages = listOf(
        SavedMessage("user", "What is the capital of Bangladesh?"),
        SavedMessage("assistant", "Dhaka."),
    )

    private fun saved(
        conversationId: String = "c1",
        modelPath: String = "/models/gemma.gguf",
        systemPrompt: String = "You are helpful.",
        thinkingEnabled: Boolean = false,
        messages: List<SavedMessage> = this.messages,
        contextSize: Int = 4096,
    ) = SavedSession(conversationId, modelPath, systemPrompt, thinkingEnabled, messages, contextSize)

    private fun prefixOf(
        saved: SavedSession?,
        conversationId: String = "c1",
        modelPath: String? = "/models/gemma.gguf",
        contextSize: Int = 4096,
        systemPrompt: String = "You are helpful.",
        thinkingEnabled: Boolean = false,
        history: List<SavedMessage> = this.messages,
    ) = savedSessionPrefixLength(
        saved, conversationId, modelPath, contextSize, systemPrompt, thinkingEnabled, history,
    )

    @Test
    fun `an exact match covers the whole history`() {
        assertEquals(2, prefixOf(saved()))
    }

    @Test
    fun `a saved prefix of a longer conversation is reused for what it covers`() {
        // The everyday case: the file was written two turns ago and the conversation
        // has moved on. The remainder is decoded on top of it.
        val history = messages + SavedMessage("user", "And its population?")
        assertEquals(2, prefixOf(saved(), history = history))
    }

    @Test
    fun `a saved session longer than the history is refused`() {
        // Messages were deleted or the conversation was rolled back. The cache holds
        // tokens the history no longer contains, so it cannot be a prefix.
        assertEquals(REBUILD_SESSION, prefixOf(saved(), history = messages.take(1)))
    }

    @Test
    fun `a diverging message is refused even at the same length`() {
        val history = listOf(messages[0], SavedMessage("assistant", "Chittagong."))
        assertEquals(REBUILD_SESSION, prefixOf(saved(), history = history))
    }

    @Test
    fun `another conversation's sequence is never adopted`() {
        assertEquals(REBUILD_SESSION, prefixOf(saved(conversationId = "c2")))
    }

    @Test
    fun `another model's sequence is never adopted`() {
        // A different model means a different tokenizer and different KV geometry,
        // so these bytes are not stale, they are unreadable.
        assertEquals(REBUILD_SESSION, prefixOf(saved(modelPath = "/models/other.gguf")))
    }

    @Test
    fun `a sequence built in a different context is refused`() {
        // Context size is not fixed: the residency controller probes candidates and
        // falls back to a smaller safe context under memory pressure, so the same
        // model can be reloaded into different geometry than this was written for.
        assertEquals(REBUILD_SESSION, prefixOf(saved(contextSize = 8192)))
        assertEquals(REBUILD_SESSION, prefixOf(saved(), contextSize = 2048))
    }

    @Test
    fun `a descriptor written before context size was recorded is refused`() {
        // contextSize defaults to 0 so an older file still decodes rather than
        // throwing, and then fails the match instead of being trusted.
        assertEquals(REBUILD_SESSION, prefixOf(saved(contextSize = 0), contextSize = 0))
    }

    @Test
    fun `a changed system prompt invalidates the sequence`() {
        // The system prompt is the very first tokens; changing it moves everything.
        assertEquals(REBUILD_SESSION, prefixOf(saved(systemPrompt = "You are terse.")))
    }

    @Test
    fun `a changed thinking mode invalidates the sequence`() {
        assertEquals(REBUILD_SESSION, prefixOf(saved(thinkingEnabled = true)))
    }

    @Test
    fun `no saved session means rebuild`() {
        assertEquals(REBUILD_SESSION, prefixOf(null))
    }

    @Test
    fun `an unloaded model means rebuild`() {
        assertEquals(REBUILD_SESSION, prefixOf(saved(), modelPath = null))
    }

    @Test
    fun `an empty saved session is not worth restoring`() {
        assertEquals(REBUILD_SESSION, prefixOf(saved(messages = emptyList())))
    }

    @Test
    fun `a role swap is refused even when the text matches`() {
        val history = listOf(
            SavedMessage("assistant", "What is the capital of Bangladesh?"),
            SavedMessage("user", "Dhaka."),
        )
        assertEquals(REBUILD_SESSION, prefixOf(saved(), history = history))
    }
}
