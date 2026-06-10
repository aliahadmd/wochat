package com.aliahad.aichat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE conversations SET qualityMode = :mode, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateQualityMode(
        id: String,
        mode: com.aliahad.aichat.core.ChatQualityMode,
        updatedAt: Long,
    )
}

@Dao
interface ProjectorDao {
    @Query("SELECT * FROM projectors ORDER BY displayName")
    fun observeAll(): Flow<List<ProjectorRecordEntity>>

    @Query("SELECT * FROM projectors WHERE id = :id")
    suspend fun get(id: String): ProjectorRecordEntity?

    @Query("SELECT * FROM projectors WHERE modelId = :modelId LIMIT 1")
    suspend fun getForModel(modelId: String): ProjectorRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(projector: ProjectorRecordEntity)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE draftKey = :draftKey ORDER BY createdAt")
    fun observeDraft(draftKey: String): Flow<List<AttachmentEntity>>

    @Query(
        "SELECT a.* FROM attachments a INNER JOIN message_attachments ma " +
            "ON a.id = ma.attachmentId WHERE ma.messageId = :messageId ORDER BY ma.ordinal",
    )
    suspend fun getForMessage(messageId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun get(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attachment: AttachmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunks(chunks: List<AttachmentChunkEntity>)

    @Query("DELETE FROM attachment_chunks WHERE attachmentId = :attachmentId")
    suspend fun deleteChunks(attachmentId: String)

    @Query("SELECT * FROM attachment_chunks WHERE attachmentId = :attachmentId ORDER BY ordinal")
    suspend fun chunks(attachmentId: String): List<AttachmentChunkEntity>

    @Query(
        "SELECT * FROM attachment_chunks WHERE attachmentId = :attachmentId " +
            "AND (:query = '' OR lower(content) LIKE '%' || lower(:query) || '%') " +
            "ORDER BY CASE WHEN lower(content) LIKE '%' || lower(:query) || '%' THEN 0 ELSE 1 END, ordinal LIMIT :limit",
    )
    suspend fun searchChunks(attachmentId: String, query: String, limit: Int): List<AttachmentChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun bind(bindings: List<MessageAttachmentEntity>)

    @Query(
        "UPDATE attachments SET conversationId = :conversationId, draftKey = NULL WHERE id IN (:ids)",
    )
    suspend fun assignToConversation(ids: List<String>, conversationId: String)

    @Query("UPDATE attachments SET selectedPages = :pages WHERE id = :id")
    suspend fun updateSelectedPages(id: String, pages: String)

    @Query("UPDATE attachments SET selectedPages = :pages, derivedImagePaths = :paths WHERE id = :id")
    suspend fun updateSelectedPagesAndImages(id: String, pages: String, paths: String)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM attachments WHERE draftKey IS NOT NULL AND createdAt < :cutoff")
    suspend fun abandonedDrafts(cutoff: Long): List<AttachmentEntity>

    @Query(
        "UPDATE attachments SET state = 'FAILED', error = 'Processing interrupted; retry attachment.' " +
            "WHERE state IN ('COPYING', 'EXTRACTING', 'RANKING')",
    )
    suspend fun markInterrupted()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getForConversation(conversationId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    @Query("UPDATE messages SET status = 'CANCELLED' WHERE status = 'STREAMING'")
    suspend fun markInterruptedAsCancelled()
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY selected DESC, displayName ASC")
    fun observeAll(): Flow<List<ModelRecordEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun get(id: String): ModelRecordEntity?

    @Query("SELECT * FROM models WHERE selected = 1 LIMIT 1")
    suspend fun getSelected(): ModelRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: ModelRecordEntity)

    @Query("UPDATE models SET selected = 0")
    suspend fun clearSelection()

    @Query("UPDATE models SET selected = 1 WHERE id = :id")
    suspend fun select(id: String)

    @Transaction
    suspend fun selectOnly(id: String) {
        clearSelection()
        select(id)
    }

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun delete(id: String)
}
