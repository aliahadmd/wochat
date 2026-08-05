package com.aliahad.aichat.activity

import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.core.ActivitySourceStats
import com.aliahad.aichat.core.MemorySensitivity
import com.aliahad.aichat.data.ActivityEventEntity
import com.aliahad.aichat.data.ActivitySourceStatsRow
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.memory.SensitiveTextRedactor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.util.UUID

interface ActivityRepository {
    fun recent(limit: Int = 100): Flow<List<ActivityEventEntity>>
    val sourceStats: Flow<List<ActivitySourceStats>>
    suspend fun between(
        sources: Set<ActivitySource>,
        fromInclusive: Long,
        toExclusive: Long,
        limit: Int = 500,
    ): List<ActivityEventEntity>
    suspend fun deleteSource(source: ActivitySource)
    suspend fun record(
        source: ActivitySource,
        eventType: String,
        startedAt: Long,
        endedAt: Long? = null,
        packageName: String? = null,
        title: String? = null,
        text: String? = null,
        metadataJson: String = "{}",
        sensitivity: MemorySensitivity = MemorySensitivity.PRIVATE,
        stableKey: String? = null,
    )
}

class RoomActivityRepository(
    database: AppDatabase,
) : ActivityRepository {
    private val dao = database.activityDao()

    override fun recent(limit: Int): Flow<List<ActivityEventEntity>> =
        dao.observeRecent(limit.coerceIn(1, 500))

    override val sourceStats: Flow<List<ActivitySourceStats>> =
        dao.observeSourceStats().map { rows -> rows.map(ActivitySourceStatsRow::toDomain) }

    override suspend fun between(
        sources: Set<ActivitySource>,
        fromInclusive: Long,
        toExclusive: Long,
        limit: Int,
    ): List<ActivityEventEntity> = if (sources.isEmpty()) {
        emptyList()
    } else {
        dao.between(sources.toList(), fromInclusive, toExclusive, limit.coerceIn(1, 2_000))
    }

    override suspend fun deleteSource(source: ActivitySource) {
        dao.deleteSource(source)
        dao.deleteSummaries(source)
    }

    override suspend fun record(
        source: ActivitySource,
        eventType: String,
        startedAt: Long,
        endedAt: Long?,
        packageName: String?,
        title: String?,
        text: String?,
        metadataJson: String,
        sensitivity: MemorySensitivity,
        stableKey: String?,
    ) {
        val redactedTitle = title?.let(SensitiveTextRedactor::redact)?.take(500)
        val redacted = text?.let(SensitiveTextRedactor::redact)?.take(8_000)
        val key = stableKey ?: UUID.randomUUID().toString()
        dao.insert(
            ActivityEventEntity(
                id = sha256("${source.name}:$key").take(40),
                source = source,
                eventType = eventType.take(80),
                startedAt = startedAt,
                endedAt = endedAt,
                packageName = packageName?.take(240),
                title = redactedTitle,
                redactedText = redacted,
                metadataJson = metadataJson.take(16_000),
                sensitivity = sensitivity,
                pinned = false,
                compactedIntoId = null,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }
}

private fun ActivitySourceStatsRow.toDomain() = ActivitySourceStats(
    source = source,
    eventCount = eventCount,
    lastEventAt = lastEventAt,
)

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
