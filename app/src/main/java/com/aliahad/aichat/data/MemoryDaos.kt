package com.aliahad.aichat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationSummaryDao {
    @Query("SELECT * FROM conversation_summaries WHERE conversationId = :conversationId")
    suspend fun get(conversationId: String): ConversationSummaryEntity?

    @Query("SELECT * FROM conversation_summaries")
    suspend fun all(): List<ConversationSummaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: ConversationSummaryEntity)
}

@Dao
interface MemoryDao {
    @Query(
        "SELECT * FROM memory_items WHERE status = 'ACTIVE' " +
            "ORDER BY pinned DESC, importance DESC, updatedAt DESC",
    )
    fun observeActive(): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_items WHERE id = :id")
    suspend fun get(id: String): MemoryItemEntity?

    @Query(
        "SELECT * FROM memory_items " +
            "WHERE contentHash = :contentHash AND status = 'ACTIVE' LIMIT 1",
    )
    suspend fun getByHash(contentHash: String): MemoryItemEntity?

    @Query(
        "SELECT * FROM memory_items WHERE status = 'ACTIVE' " +
            "AND (:includePrivate = 1 OR sensitivity = 'NORMAL') " +
            "ORDER BY pinned DESC, importance DESC, updatedAt DESC LIMIT :limit",
    )
    suspend fun candidates(includePrivate: Boolean, limit: Int): List<MemoryItemEntity>

    @Query(
        "SELECT * FROM memory_items WHERE status = 'ACTIVE' " +
            "AND (:includePrivate = 1 OR sensitivity = 'NORMAL') " +
            "AND id IN (:ids) " +
            "ORDER BY pinned DESC, importance DESC, updatedAt DESC LIMIT 500",
    )
    suspend fun candidatesIn(includePrivate: Boolean, ids: List<String>): List<MemoryItemEntity>

    @Query("SELECT id FROM memory_items WHERE pinned = 1 AND status = 'ACTIVE'")
    suspend fun pinnedIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(memory: MemoryItemEntity): Long

    @Query(
        "UPDATE memory_items SET status = 'SUPERSEDED', updatedAt = :updatedAt " +
            "WHERE id = :id AND status != 'DELETED'",
    )
    suspend fun markSuperseded(id: String, updatedAt: Long)

    @Query("UPDATE memory_items SET status = 'DELETED', updatedAt = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, updatedAt: Long)

    @Query("UPDATE memory_items SET pinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(source: MemorySourceEntity): Long

    @Query("SELECT * FROM memory_sources WHERE memoryId IN (:memoryIds) ORDER BY createdAt")
    suspend fun sourcesFor(memoryIds: List<String>): List<MemorySourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCorrection(correction: MemoryCorrectionEntity)

    @Query("SELECT id, status FROM memory_items")
    suspend fun idStatusRows(): List<MemoryStatusRow>

    @Query(
        "SELECT id FROM memory_items " +
            "WHERE status IN ('DELETED', 'SUPERSEDED') AND updatedAt < :cutoff " +
            "ORDER BY updatedAt LIMIT :limit",
    )
    suspend fun purgeableIds(cutoff: Long, limit: Int): List<String>

    @Query("DELETE FROM memory_sources WHERE memoryId IN (:memoryIds)")
    suspend fun deleteSourcesFor(memoryIds: List<String>)

    @Query("DELETE FROM memory_corrections WHERE memoryId IN (:memoryIds)")
    suspend fun deleteCorrectionsFor(memoryIds: List<String>)

    @Query("DELETE FROM memory_items WHERE id IN (:memoryIds)")
    suspend fun deleteByIds(memoryIds: List<String>)
}
