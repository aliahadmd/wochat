package com.aliahad.aichat

import com.aliahad.aichat.core.Attachment
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.AttachmentProcessingState
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.Conversation
import com.aliahad.aichat.core.GenerationStopReason
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.data.snippetAround
import com.aliahad.aichat.ui.viewmodel.formatConversationMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationExportSearchTest {
    @Test
    fun markdownExportIncludesRolesContentAndAttachmentNames() {
        val conversation = Conversation(
            id = "c1",
            title = "Trip planning",
            createdAt = 0L,
            updatedAt = 0L,
            qualityMode = com.aliahad.aichat.core.ChatQualityMode.FAST,
            temporary = false,
        )
        val messages = listOf(
            message("m1", MessageRole.USER, "What should I pack for Hokkaido in winter?"),
            message(
                "m2",
                MessageRole.ASSISTANT,
                "Thermal layers, waterproof boots, and hand warmers.",
                stopReason = GenerationStopReason.EOG,
            ),
            message(
                "m3",
                MessageRole.ASSISTANT,
                "Also a power bank for the cold.",
                stopReason = GenerationStopReason.CANCELLED,
            ),
        )
        val attachments = mapOf(
            "m1" to listOf(attachment("packing-list.pdf", AttachmentKind.PDF)),
        )

        val markdown = formatConversationMarkdown(conversation, messages, attachments)

        assertTrue(markdown.startsWith("# Trip planning"))
        assertTrue(markdown.contains("## User"))
        assertTrue(markdown.contains("## Assistant"))
        assertTrue(markdown.contains("What should I pack for Hokkaido in winter?"))
        assertTrue(markdown.contains("Thermal layers, waterproof boots, and hand warmers."))
        assertTrue(markdown.contains("- attachment: packing-list.pdf (pdf)"))
        // EOG is the normal stop; only abnormal stops are annotated.
        assertFalse(markdown.contains("stopped early: eog"))
        assertTrue(markdown.contains("<!-- stopped early: cancelled -->"))
    }

    @Test
    fun emptyMessageExportsWithPlaceholder() {
        val conversation = Conversation(
            id = "c1",
            title = "Empty",
            createdAt = 0L,
            updatedAt = 0L,
            qualityMode = com.aliahad.aichat.core.ChatQualityMode.FAST,
            temporary = false,
        )
        val markdown = formatConversationMarkdown(
            conversation,
            listOf(message("m1", MessageRole.ASSISTANT, "")),
            emptyMap(),
        )
        assertTrue(markdown.contains("_(no text)_"))
    }

    @Test
    fun snippetCentersOnTheMatchAndCollapsesNewlines() {
        val content = "x".repeat(100) + "\nthe QUERY appears here\n" + "y".repeat(100)
        val snippet = snippetAround(content, "query")
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertFalse(snippet.contains('\n'))
        assertTrue(snippet.contains("QUERY"))
    }

    @Test
    fun snippetFallsBackToHeadWhenQueryMissing() {
        val content = "a".repeat(300)
        val snippet = snippetAround(content, "zzz")
        assertEquals(120, snippet.length)
    }

    private fun message(
        id: String,
        role: MessageRole,
        content: String,
        stopReason: GenerationStopReason? = null,
    ) = ChatMessage(
        id = id,
        conversationId = "c1",
        role = role,
        content = content,
        createdAt = 0L,
        status = MessageStatus.COMPLETE,
        stopReason = stopReason,
    )

    private fun attachment(name: String, kind: AttachmentKind) = Attachment(
        id = "a1",
        conversationId = "c1",
        draftKey = null,
        displayName = name,
        mimeType = "application/pdf",
        kind = kind,
        originalPath = "/tmp/original",
        previewPath = null,
        derivedImagePaths = emptyList(),
        byteSize = 10L,
        pageCount = null,
        selectedPages = emptySet(),
        imageTokenBudget = null,
        state = AttachmentProcessingState.READY,
        progress = 1f,
        error = null,
        createdAt = 0L,
    )
}
