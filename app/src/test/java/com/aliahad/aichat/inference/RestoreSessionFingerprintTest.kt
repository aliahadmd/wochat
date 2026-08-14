package com.aliahad.aichat.inference

import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreSessionFingerprintTest {
    private val settings = GenerationSettings(
        thinkingEnabled = false,
        systemPrompt = "You are a helpful assistant.",
    )
    private val history = listOf(
        turn("m1", MessageRole.USER, "hello"),
        turn("m2", MessageRole.ASSISTANT, "hi there"),
    )

    @Test
    fun sameInputsProduceTheSameFingerprintAndSkip() {
        val fingerprint = restoreFingerprint("chat", settings, history)
        assertEquals(fingerprint, restoreFingerprint("chat", settings.copy(), history.toList()))
        assertTrue(shouldSkipRestore("chat", fingerprint, "chat", fingerprint))
    }

    @Test
    fun changedSystemPromptDoesNotSkip() {
        val fingerprint = restoreFingerprint("chat", settings, history)
        val changed = settings.copy(systemPrompt = "You are a terse assistant.")
        val changedFingerprint = restoreFingerprint("chat", changed, history)
        assertFalse(shouldSkipRestore("chat", fingerprint, "chat", changedFingerprint))
    }

    @Test
    fun changedThinkingEnabledDoesNotSkip() {
        val fingerprint = restoreFingerprint("chat", settings, history)
        val changed = settings.copy(thinkingEnabled = true)
        val changedFingerprint = restoreFingerprint("chat", changed, history)
        assertFalse(shouldSkipRestore("chat", fingerprint, "chat", changedFingerprint))
    }

    @Test
    fun changedHistorySizeDoesNotSkip() {
        val fingerprint = restoreFingerprint("chat", settings, history)
        val changedFingerprint = restoreFingerprint("chat", settings, history.take(1))
        assertFalse(shouldSkipRestore("chat", fingerprint, "chat", changedFingerprint))
    }

    @Test
    fun changedConversationIdDoesNotSkip() {
        val fingerprint = restoreFingerprint("chat", settings, history)
        val changedFingerprint = restoreFingerprint("other", settings, history)
        assertFalse(shouldSkipRestore("chat", fingerprint, "other", changedFingerprint))
    }

    @Test
    fun emptyHistoryIsFingerprintedWithoutACrash() {
        val fingerprint = restoreFingerprint("chat", settings, emptyList())
        assertTrue(shouldSkipRestore("chat", fingerprint, "chat", fingerprint))
    }

    private fun turn(id: String, role: MessageRole, content: String) = ChatTurn(
        message = ChatMessage(
            id = id,
            conversationId = "chat",
            role = role,
            content = content,
            createdAt = 0L,
            status = MessageStatus.COMPLETE,
        ),
    )
}
