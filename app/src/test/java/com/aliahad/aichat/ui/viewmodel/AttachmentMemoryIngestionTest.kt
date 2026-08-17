package com.aliahad.aichat.ui.viewmodel

import com.aliahad.aichat.core.Attachment
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.AttachmentProcessingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the pure gate helper used by [ChatTurnRunner] before handing extracted
 * attachment text to MemoryRepository.rememberAttachment. Idempotency of the
 * ingestion itself is enforced inside RoomMemoryRepository via the SHA-256
 * content hash and the deterministic "attachment:<id>" stableId of insertIfAbsent.
 */
class AttachmentMemoryIngestionTest {

    @Test
    fun `blank extraction is skipped`() {
        assertNull(attachmentMemoryContent(""))
        assertNull(attachmentMemoryContent("   "))
        assertNull(attachmentMemoryContent("\n\t "))
    }

    @Test
    fun `extraction is trimmed`() {
        assertEquals("invoice body", attachmentMemoryContent("\n  invoice body  \n"))
    }

    @Test
    fun `short extraction is kept verbatim`() {
        assertEquals("Meeting notes for Q3 review.", attachmentMemoryContent("Meeting notes for Q3 review."))
    }

    @Test
    fun `extraction at the cap is kept whole`() {
        val text = "a".repeat(MAX_ATTACHMENT_MEMORY_CHARS)
        assertEquals(text, attachmentMemoryContent(text))
    }

    @Test
    fun `long extraction is truncated to the cap`() {
        val text = "b".repeat(MAX_ATTACHMENT_MEMORY_CHARS + 2_500)
        val result = attachmentMemoryContent(text)
        assertEquals(MAX_ATTACHMENT_MEMORY_CHARS, requireNotNull(result).length)
        assertEquals("b".repeat(MAX_ATTACHMENT_MEMORY_CHARS), result)
    }
}

/**
 * The audio limits, extracted from the turn runner so they can be checked without
 * a database, a model or a coroutine.
 */
class AudioAttachmentLimitTest {

    private fun audio(durationMillis: Long?) = Attachment(
        id = "clip-${durationMillis ?: "unknown"}-${counter++}",
        conversationId = null,
        draftKey = "draft",
        displayName = "clip.m4a",
        mimeType = "audio/mp4",
        kind = AttachmentKind.AUDIO,
        originalPath = "/tmp/clip.m4a",
        previewPath = null,
        derivedImagePaths = emptyList(),
        byteSize = 1_000L,
        pageCount = null,
        selectedPages = emptySet(),
        imageTokenBudget = null,
        state = AttachmentProcessingState.READY,
        progress = 1f,
        error = null,
        createdAt = 0L,
        durationMillis = durationMillis,
    )

    private var counter = 0

    @Test
    fun `no audio is always fine`() {
        assertNull(audioAttachmentError(emptyList()))
    }

    @Test
    fun `up to the limit is accepted`() {
        val clips = List(ChatTurnRunner.MAX_AUDIO_ATTACHMENTS) { audio(1_000L) }
        assertNull(audioAttachmentError(clips))
    }

    @Test
    fun `one clip too many is rejected`() {
        val clips = List(ChatTurnRunner.MAX_AUDIO_ATTACHMENTS + 1) { audio(1_000L) }
        assertEquals(
            "A message can include up to ${ChatTurnRunner.MAX_AUDIO_ATTACHMENTS} audio files.",
            audioAttachmentError(clips),
        )
    }

    @Test
    fun `unknown duration is rejected rather than assumed to be zero`() {
        assertEquals(
            "Audio duration is unavailable. Retry the attachment.",
            audioAttachmentError(listOf(audio(null))),
        )
    }

    @Test
    fun `total duration over the cap is rejected`() {
        val clips = listOf(audio(60_000L), audio(31_000L))
        assertEquals(
            "Audio attachments can total up to 90 seconds per message.",
            audioAttachmentError(clips),
        )
    }

    @Test
    fun `total duration exactly at the cap is accepted`() {
        val clips = listOf(audio(60_000L), audio(30_000L))
        assertNull(audioAttachmentError(clips))
    }
}
