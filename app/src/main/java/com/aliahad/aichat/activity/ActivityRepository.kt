package com.aliahad.aichat.activity

import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.core.MemorySensitivity
import com.aliahad.aichat.data.ActivityEventEntity
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.memory.SensitiveTextRedactor
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.util.UUID

interface ActivityRepository {
    fun recent(limit: Int = 100): Flow<List<ActivityEventEntity>>
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
                title = title?.take(500),
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

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
