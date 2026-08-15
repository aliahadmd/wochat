package com.aliahad.aichat.ui.viewmodel

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
