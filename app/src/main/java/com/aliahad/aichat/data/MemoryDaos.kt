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
        "SELECT DISTINCT memoryId FROM memory_sources " +
            "WHERE kind = 'ACTIVITY' AND label = :sourceLabel",
    )
    suspend fun activityMemoryIds(sourceLabel: String): List<String>

    @Query(
        "UPDATE memory_items SET status = 'DELETED', updatedAt = :updatedAt " +
            "WHERE id IN (" +
            "SELECT memoryId FROM memory_sources WHERE kind = 'ACTIVITY' AND label = :sourceLabel" +
            ")",
    )
    suspend fun markActivitySourceDeleted(sourceLabel: String, updatedAt: Long)
}

@Dao
interface ActivityDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: ActivityEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummary(summary: MemorySummaryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(checkpoint: CollectorCheckpointEntity)

    @Query("SELECT * FROM collector_checkpoints WHERE collector = :collector")
    suspend fun checkpoint(collector: String): CollectorCheckpointEntity?

    @Query("SELECT * FROM collector_checkpoints ORDER BY collector")
    suspend fun checkpoints(): List<CollectorCheckpointEntity>

    @Query("SELECT * FROM activity_events ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActivityEventEntity>>

    @Query(
        "SELECT source, COUNT(*) AS eventCount, MAX(startedAt) AS lastEventAt " +
            "FROM activity_events GROUP BY source",
    )
    fun observeSourceStats(): Flow<List<ActivitySourceStatsRow>>

    @Query(
        "SELECT * FROM activity_events " +
            "WHERE (:includePrivate = 1 OR sensitivity = 'NORMAL') " +
            "ORDER BY pinned DESC, startedAt DESC LIMIT :limit",
    )
    suspend fun retrievalCandidates(
        includePrivate: Boolean,
        limit: Int,
    ): List<ActivityEventEntity>

    @Query(
        "SELECT * FROM activity_events WHERE source IN (:sources) " +
            "AND startedAt >= :fromInclusive AND startedAt < :toExclusive " +
            "ORDER BY startedAt DESC LIMIT :limit",
    )
    suspend fun between(
        sources: List<com.aliahad.aichat.core.ActivitySource>,
        fromInclusive: Long,
        toExclusive: Long,
        limit: Int,
    ): List<ActivityEventEntity>

    @Query(
        "SELECT * FROM activity_events WHERE source = :source AND startedAt < :before " +
            "AND compactedIntoId IS NULL AND pinned = 0 ORDER BY startedAt LIMIT :limit",
    )
    suspend fun uncompactedBefore(
        source: com.aliahad.aichat.core.ActivitySource,
        before: Long,
        limit: Int,
    ): List<ActivityEventEntity>

    @Query("UPDATE activity_events SET compactedIntoId = :summaryId WHERE id IN (:ids)")
    suspend fun markCompacted(ids: List<String>, summaryId: String)

    @Query("DELETE FROM activity_events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM activity_events WHERE source = :source")
    suspend fun deleteSource(source: com.aliahad.aichat.core.ActivitySource)

    @Query("DELETE FROM memory_summaries WHERE source = :source")
    suspend fun deleteSummaries(source: com.aliahad.aichat.core.ActivitySource)
}
