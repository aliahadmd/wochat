package com.aliahad.aichat

import com.aliahad.aichat.attachment.attachmentsForMessages
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.AttachmentProcessingState
import com.aliahad.aichat.data.AttachmentDao
import com.aliahad.aichat.data.AttachmentEntity
import com.aliahad.aichat.data.AttachmentWithMessageId
import com.aliahad.aichat.data.AttachmentChunkEntity
import com.aliahad.aichat.data.MessageAttachmentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentBatchLoadTest {
    @Test
    fun batchesAllMessagesIntoOneQueryAndGroupsByMessage() = runTest {
        val rows = listOf(
            row("m1", attachment("a1")),
            row("m1", attachment("a2")),
            row("m2", attachment("a3")),
        )
        val dao = FakeAttachmentDao(rows)

        val result = dao.attachmentsForMessages(listOf("m1", "m2", "m3"))

        assertEquals(1, dao.queryCount)
        assertEquals(listOf("m1", "m2", "m3"), dao.lastMessageIds)
        assertEquals(setOf("m1", "m2"), result.keys)
        assertEquals(listOf("a1", "a2"), result.getValue("m1").map { it.id })
        assertEquals(listOf("a3"), result.getValue("m2").map { it.id })
    }

    @Test
    fun preservesDaoRowOrderWithinEachMessage() = runTest {
        val rows = listOf(
            row("m1", attachment("first")),
            row("m1", attachment("second")),
            row("m1", attachment("third")),
        )
        val dao = FakeAttachmentDao(rows)

        val result = dao.attachmentsForMessages(listOf("m1"))

        assertEquals(listOf("first", "second", "third"), result.getValue("m1").map { it.id })
    }

    @Test
    fun emptyMessageListShortCircuitsWithoutQuery() = runTest {
        val dao = FakeAttachmentDao(listOf(row("m1", attachment("a1"))))

        val result = dao.attachmentsForMessages(emptyList())

        assertTrue(result.isEmpty())
        assertEquals(0, dao.queryCount)
    }

    @Test
    fun unknownMessageIdsAreAbsentFromResult() = runTest {
        val dao = FakeAttachmentDao(listOf(row("m1", attachment("a1"))))

        val result = dao.attachmentsForMessages(listOf("missing-1", "missing-2"))

        assertTrue(result.isEmpty())
        assertFalse(result.containsKey("missing-1"))
    }
}

private fun attachment(id: String) = AttachmentEntity(
    id = id,
    conversationId = "conversation",
    draftKey = null,
    displayName = "$id.txt",
    mimeType = "text/plain",
    kind = AttachmentKind.TEXT,
    originalPath = "/tmp/$id",
    previewPath = null,
    derivedImagePaths = "",
    byteSize = 10L,
    pageCount = null,
    selectedPages = "",
    imageTokenBudget = null,
    state = AttachmentProcessingState.READY,
    progress = 1f,
    error = null,
    createdAt = 0L,
    durationMillis = null,
)

private fun row(messageId: String, entity: AttachmentEntity) =
    AttachmentWithMessageId(messageId, entity)

private class FakeAttachmentDao(
    private val rows: List<AttachmentWithMessageId>,
) : AttachmentDao {
    var queryCount = 0
        private set
    var lastMessageIds: List<String>? = null
        private set

    override suspend fun getForMessages(messageIds: List<String>): List<AttachmentWithMessageId> {
        queryCount += 1
        lastMessageIds = messageIds
        return rows.filter { it.messageId in messageIds }
    }

    override fun observeDraft(draftKey: String): Flow<List<AttachmentEntity>> = emptyFlow()
    override suspend fun getForMessage(messageId: String): List<AttachmentEntity> =
        error("unused")
    override suspend fun get(id: String): AttachmentEntity? = error("unused")
    override suspend fun getByIds(ids: List<String>): List<AttachmentEntity> = error("unused")
    override suspend fun upsert(attachment: AttachmentEntity) = error("unused")
    override suspend fun upsertChunks(chunks: List<AttachmentChunkEntity>) = error("unused")
    override suspend fun deleteChunks(attachmentId: String) = error("unused")
    override suspend fun chunks(attachmentId: String): List<AttachmentChunkEntity> = error("unused")
    override suspend fun searchChunks(
        attachmentId: String,
        query: String,
        limit: Int,
    ): List<AttachmentChunkEntity> = error("unused")
    override suspend fun bind(bindings: List<MessageAttachmentEntity>) = error("unused")
    override suspend fun assignToConversation(ids: List<String>, conversationId: String) =
        error("unused")
    override suspend fun updateImageTokenBudget(ids: List<String>, budget: Int) = error("unused")
    override suspend fun updateSelectedPages(id: String, pages: String) = error("unused")
    override suspend fun updateSelectedPagesAndImages(id: String, pages: String, paths: String) =
        error("unused")
    override suspend fun delete(id: String) = error("unused")
    override suspend fun abandonedDrafts(cutoff: Long): List<AttachmentEntity> = error("unused")
    override suspend fun markInterrupted() = error("unused")
    override suspend fun activeProcessingCount(): Int = error("unused")
}
