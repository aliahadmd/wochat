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

    @Query(
        "SELECT COUNT(*) FROM projectors WHERE status IN ('QUEUED', 'DOWNLOADING', 'VERIFYING')",
    )
    suspend fun activeTransferCount(): Int
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

    @Query("UPDATE attachments SET imageTokenBudget = :budget WHERE id IN (:ids)")
    suspend fun updateImageTokenBudget(ids: List<String>, budget: Int)

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

    @Query(
        "SELECT COUNT(*) FROM attachments WHERE state IN ('COPYING', 'EXTRACTING', 'RANKING')",
    )
    suspend fun activeProcessingCount(): Int
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

    @Query(
        "UPDATE messages SET status = 'CONTINUABLE', stopReason = 'PROCESS_DEATH' " +
            "WHERE status = 'STREAMING'",
    )
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

    @Query("UPDATE models SET sha256 = :sha256 WHERE id = :id")
    suspend fun updateSha256(id: String, sha256: String)

    @Query(
        "SELECT COUNT(*) FROM models WHERE status IN ('QUEUED', 'DOWNLOADING', 'VERIFYING')",
    )
    suspend fun activeTransferCount(): Int
}

@Dao
interface ModelContextProfileDao {
    @Query("SELECT * FROM model_context_profiles ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ModelContextProfileEntity>>

    @Query("SELECT * FROM model_context_profiles WHERE id = :id")
    suspend fun get(id: String): ModelContextProfileEntity?

    @Query(
        "SELECT * FROM model_context_profiles WHERE modelId = :modelId " +
            "AND deviceFingerprint = :deviceFingerprint " +
            "ORDER BY updatedAt DESC LIMIT 1",
    )
    suspend fun latestForModel(
        modelId: String,
        deviceFingerprint: String,
    ): ModelContextProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ModelContextProfileEntity)

    @Query("DELETE FROM model_context_profiles WHERE modelId = :modelId")
    suspend fun deleteForModel(modelId: String)
}

@Dao
interface BackupImportInvalidationDao {
    @Query(
        "UPDATE conversations SET updatedAt = updatedAt " +
            "WHERE rowid = (SELECT rowid FROM conversations LIMIT 1)",
    )
    suspend fun touchConversations()

    @Query(
        "UPDATE messages SET createdAt = createdAt " +
            "WHERE rowid = (SELECT rowid FROM messages LIMIT 1)",
    )
    suspend fun touchMessages()

    @Query(
        "UPDATE attachments SET createdAt = createdAt " +
            "WHERE rowid = (SELECT rowid FROM attachments LIMIT 1)",
    )
    suspend fun touchAttachments()

    @Query(
        "UPDATE attachment_chunks SET ordinal = ordinal " +
            "WHERE rowid = (SELECT rowid FROM attachment_chunks LIMIT 1)",
    )
    suspend fun touchAttachmentChunks()

    @Query(
        "UPDATE message_attachments SET ordinal = ordinal " +
            "WHERE rowid = (SELECT rowid FROM message_attachments LIMIT 1)",
    )
    suspend fun touchMessageAttachments()

    @Query(
        "UPDATE conversation_summaries SET updatedAt = updatedAt " +
            "WHERE rowid = (SELECT rowid FROM conversation_summaries LIMIT 1)",
    )
    suspend fun touchConversationSummaries()

    @Query(
        "UPDATE memory_items SET updatedAt = updatedAt " +
            "WHERE rowid = (SELECT rowid FROM memory_items LIMIT 1)",
    )
    suspend fun touchMemoryItems()

    @Query(
        "UPDATE memory_sources SET createdAt = createdAt " +
            "WHERE rowid = (SELECT rowid FROM memory_sources LIMIT 1)",
    )
    suspend fun touchMemorySources()

    @Query(
        "UPDATE memory_corrections SET createdAt = createdAt " +
            "WHERE rowid = (SELECT rowid FROM memory_corrections LIMIT 1)",
    )
    suspend fun touchMemoryCorrections()

    @Query(
        "UPDATE memory_summaries SET updatedAt = updatedAt " +
            "WHERE rowid = (SELECT rowid FROM memory_summaries LIMIT 1)",
    )
    suspend fun touchMemorySummaries()

    @Query(
        "UPDATE activity_events SET createdAt = createdAt " +
            "WHERE rowid = (SELECT rowid FROM activity_events LIMIT 1)",
    )
    suspend fun touchActivityEvents()

    @Query(
        "UPDATE collector_checkpoints SET lastCollectedAt = lastCollectedAt " +
            "WHERE rowid = (SELECT rowid FROM collector_checkpoints LIMIT 1)",
    )
    suspend fun touchCollectorCheckpoints()

    @Query(
        "UPDATE action_audits SET createdAt = createdAt " +
            "WHERE rowid = (SELECT rowid FROM action_audits LIMIT 1)",
    )
    suspend fun touchActionAudits()

    @Transaction
    suspend fun notifyImportedTables() {
        touchConversations()
        touchMessages()
        touchAttachments()
        touchAttachmentChunks()
        touchMessageAttachments()
        touchConversationSummaries()
        touchMemoryItems()
        touchMemorySources()
        touchMemoryCorrections()
        touchMemorySummaries()
        touchActivityEvents()
        touchCollectorCheckpoints()
        touchActionAudits()
    }
}
