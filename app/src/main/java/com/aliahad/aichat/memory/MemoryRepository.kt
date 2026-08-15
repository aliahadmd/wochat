package com.aliahad.aichat.memory

import androidx.room.withTransaction
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.MemoryHit
import com.aliahad.aichat.core.MemoryItem
import com.aliahad.aichat.core.MemoryQuery
import com.aliahad.aichat.core.MemorySensitivity
import com.aliahad.aichat.core.MemorySource
import com.aliahad.aichat.core.MemorySourceKind
import com.aliahad.aichat.core.MemoryStatus
import com.aliahad.aichat.core.MemoryType
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.data.MemoryCorrectionEntity
import com.aliahad.aichat.data.MemoryItemEntity
import com.aliahad.aichat.data.MemorySourceEntity
import com.aliahad.aichat.data.MemoryStatusRow
import com.aliahad.aichat.data.ActivityEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.max

interface MemoryRepository {
    val memories: Flow<List<MemoryItem>>
    suspend fun rememberMessage(message: ChatMessage, conversationTemporary: Boolean)
    suspend fun remember(
        type: MemoryType,
        title: String,
        content: String,
        importance: Float = 0.7f,
        sensitivity: MemorySensitivity = MemorySensitivity.PRIVATE,
    ): MemoryItem
    suspend fun search(query: MemoryQuery): List<MemoryHit>
    suspend fun correct(id: String, content: String, reason: String? = null): MemoryItem
    suspend fun setPinned(id: String, pinned: Boolean)
    suspend fun forget(id: String)
    suspend fun rememberActivitySummary(
        source: ActivitySource,
        summaryId: String,
        title: String,
        content: String,
        importance: Float,
    ): MemoryItem
    suspend fun forgetActivitySource(source: ActivitySource)
    suspend fun purgeStaleIndexDocs()
}

class RoomMemoryRepository(
    private val database: AppDatabase,
    private val indexer: MemoryIndexer,
) : MemoryRepository {
    private val dao = database.memoryDao()

    private val searchDataSource = object : MemorySearchDataSource {
        override suspend fun pinnedIds(): List<String> = dao.pinnedIds()

        override suspend fun candidates(includePrivate: Boolean, limit: Int): List<MemoryItemEntity> =
            dao.candidates(includePrivate, limit)

        override suspend fun candidatesIn(includePrivate: Boolean, ids: List<String>): List<MemoryItemEntity> =
            dao.candidatesIn(includePrivate, ids)

        override suspend fun sourcesFor(memoryIds: List<String>): List<MemorySourceEntity> =
            dao.sourcesFor(memoryIds)
    }

    override val memories: Flow<List<MemoryItem>> =
        dao.observeActive().map { rows -> rows.map(MemoryItemEntity::toDomain) }

    override suspend fun rememberMessage(
        message: ChatMessage,
        conversationTemporary: Boolean,
    ) {
        if (conversationTemporary || message.role != MessageRole.USER) return
        val redacted = SensitiveTextRedactor.redact(message.content).trim()
        if (redacted.isBlank()) return
        val type = inferType(redacted)
        val title = titleFor(redacted)
        insertIfAbsent(
            type = type,
            title = title,
            content = redacted,
            confidence = if (type == MemoryType.EPISODE) 0.72f else 0.9f,
            importance = if (type == MemoryType.EPISODE) 0.45f else 0.78f,
            sensitivity = MemorySensitivity.PRIVATE,
            sourceKind = MemorySourceKind.CHAT_MESSAGE,
            sourceId = message.id,
            sourceLabel = "Chat message",
            stableId = "message:${message.id}",
        )
    }

    override suspend fun remember(
        type: MemoryType,
        title: String,
        content: String,
        importance: Float,
        sensitivity: MemorySensitivity,
    ): MemoryItem {
        val value = content.trim()
        require(value.isNotEmpty()) { "Memory cannot be empty" }
        return insertIfAbsent(
            type = type,
            title = title.trim().ifEmpty { titleFor(value) },
            content = value,
            confidence = 1f,
            importance = importance.coerceIn(0f, 1f),
            sensitivity = sensitivity,
            sourceKind = MemorySourceKind.MANUAL,
            sourceId = null,
            sourceLabel = "Manual memory",
        )
    }

    override suspend fun search(query: MemoryQuery): List<MemoryHit> {
        val normalized = normalize(query.text)
        val terms = normalized.split(' ').filter { it.length > 1 }.toSet()
        val indexedIds = runCatching {
            indexer.searchIds(normalized, (query.limit.coerceIn(1, 24) * 8).coerceAtMost(128))
        }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val memoryHits = searchMemoryRows(
            source = searchDataSource,
            queryText = query.text,
            includePrivate = query.includePrivate,
            limit = query.limit,
            indexedIds = indexedIds,
            now = now,
        )
        val activityIntent = activityRetrievalIntent(query.text, now)
        val rankedActivityHits = database.activityDao()
            .retrievalCandidates(query.includePrivate, 1_000)
            .asSequence()
            .filter { it.sensitivity != MemorySensitivity.SECRET }
            .mapNotNull { event ->
                event.toMemoryHit(normalized, terms, now, activityIntent)
                    ?.let { event.source to it }
            }
            .sortedByDescending { it.second.score }
            .toList()
        val activityHits = applyPerSourceActivityQuota(
            rankedActivityHits,
            perSourceActivityLimit(activityIntent.sources.size),
        )
        return (memoryHits + activityHits)
            .sortedByDescending(MemoryHit::score)
            .take(query.limit.coerceIn(1, 24))
    }

    override suspend fun correct(id: String, content: String, reason: String?): MemoryItem {
        val previous = requireNotNull(dao.get(id)) { "Memory not found" }
        val value = content.trim()
        require(value.isNotEmpty()) { "Memory cannot be empty" }
        val now = System.currentTimeMillis()
        val replacement = entity(
            id = UUID.randomUUID().toString(),
            type = previous.type,
            title = titleFor(value),
            content = value,
            confidence = 1f,
            importance = max(previous.importance, 0.8f),
            sensitivity = previous.sensitivity,
            supersedesId = previous.id,
            now = now,
        )
        database.withTransaction {
            dao.markSuperseded(previous.id, now)
            dao.insert(replacement)
            dao.insertSource(
                MemorySourceEntity(
                    id = UUID.randomUUID().toString(),
                    memoryId = replacement.id,
                    kind = MemorySourceKind.MANUAL,
                    sourceId = previous.id,
                    label = "Manual correction",
                    createdAt = now,
                ),
            )
            dao.insertCorrection(
                MemoryCorrectionEntity(
                    id = UUID.randomUUID().toString(),
                    memoryId = replacement.id,
                    previousContent = previous.content,
                    correctedContent = value,
                    reason = reason,
                    createdAt = now,
                ),
            )
        }
        return replacement.toDomain().also {
            runCatching { indexer.upsert(it) }
            runCatching { indexer.remove(previous.id) }
        }
    }

    override suspend fun setPinned(id: String, pinned: Boolean) {
        dao.setPinned(id, pinned, System.currentTimeMillis())
        dao.get(id)?.toDomain()?.let { runCatching { indexer.upsert(it) } }
    }

    override suspend fun forget(id: String) {
        dao.markDeleted(id, System.currentTimeMillis())
        runCatching { indexer.remove(id) }
    }

    override suspend fun rememberActivitySummary(
        source: ActivitySource,
        summaryId: String,
        title: String,
        content: String,
        importance: Float,
    ): MemoryItem = insertIfAbsent(
        type = MemoryType.EPISODE,
        title = title,
        content = content,
        confidence = 0.95f,
        importance = importance.coerceIn(0f, 1f),
        sensitivity = MemorySensitivity.PRIVATE,
        sourceKind = MemorySourceKind.ACTIVITY,
        sourceId = summaryId,
        sourceLabel = source.name,
        stableId = "activity-summary:$summaryId",
    )

    override suspend fun forgetActivitySource(source: ActivitySource) {
        val ids = dao.activityMemoryIds(source.name)
        dao.markActivitySourceDeleted(source.name, System.currentTimeMillis())
        ids.forEach { id ->
            runCatching { indexer.remove(id) }
        }
    }

    override suspend fun purgeStaleIndexDocs() {
        staleIndexDocIds(dao.idStatusRows()).forEach { id ->
            runCatching { indexer.remove(id) }
        }
    }

    private suspend fun insertIfAbsent(
        type: MemoryType,
        title: String,
        content: String,
        confidence: Float,
        importance: Float,
        sensitivity: MemorySensitivity,
        sourceKind: MemorySourceKind,
        sourceId: String?,
        sourceLabel: String?,
        stableId: String? = null,
    ): MemoryItem {
        val normalized = normalize(content)
        val hash = sha256("${type.name}:$normalized")
        dao.getByHash(hash)?.let { existing ->
            dao.insertSource(
                MemorySourceEntity(
                    id = sha256("${existing.id}:${sourceKind.name}:${sourceId.orEmpty()}").take(32),
                    memoryId = existing.id,
                    kind = sourceKind,
                    sourceId = sourceId,
                    label = sourceLabel,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            return existing.toDomain().also { runCatching { indexer.upsert(it) } }
        }
        val now = System.currentTimeMillis()
        val row = entity(
            id = stableId ?: UUID.randomUUID().toString(),
            type = type,
            title = title,
            content = content,
            confidence = confidence,
            importance = importance,
            sensitivity = sensitivity,
            now = now,
        )
        database.withTransaction {
            dao.insert(row)
            dao.insertSource(
                MemorySourceEntity(
                    id = sha256("${row.id}:${sourceKind.name}:${sourceId.orEmpty()}").take(32),
                    memoryId = row.id,
                    kind = sourceKind,
                    sourceId = sourceId,
                    label = sourceLabel,
                    createdAt = now,
                ),
            )
        }
        return row.toDomain().also { runCatching { indexer.upsert(it) } }
    }

    private fun entity(
        id: String,
        type: MemoryType,
        title: String,
        content: String,
        confidence: Float,
        importance: Float,
        sensitivity: MemorySensitivity,
        supersedesId: String? = null,
        now: Long,
    ): MemoryItemEntity {
        val normalized = normalize(content)
        val searchRowId = id.fold(1125899906842597L) { value, char -> value * 31 + char.code }
            .and(Long.MAX_VALUE)
            .coerceAtLeast(1L)
        return MemoryItemEntity(
            id = id,
            searchRowId = searchRowId,
            type = type,
            title = title.take(120),
            content = content,
            normalizedContent = normalized,
            contentHash = sha256("${type.name}:$normalized"),
            confidence = confidence.coerceIn(0f, 1f),
            importance = importance.coerceIn(0f, 1f),
            sensitivity = sensitivity,
            status = MemoryStatus.ACTIVE,
            pinned = false,
            validFrom = now,
            validTo = null,
            supersedesId = supersedesId,
            createdAt = now,
            updatedAt = now,
        )
    }
}

internal interface MemorySearchDataSource {
    suspend fun pinnedIds(): List<String>
    suspend fun candidates(includePrivate: Boolean, limit: Int): List<MemoryItemEntity>
    suspend fun candidatesIn(includePrivate: Boolean, ids: List<String>): List<MemoryItemEntity>
    suspend fun sourcesFor(memoryIds: List<String>): List<MemorySourceEntity>
}

private data class RankedMemoryRow(
    val row: MemoryItemEntity,
    val score: Float,
    val lexicalScore: Float,
)

internal suspend fun searchMemoryRows(
    source: MemorySearchDataSource,
    queryText: String,
    includePrivate: Boolean,
    limit: Int,
    indexedIds: List<String>,
    now: Long,
): List<MemoryHit> {
    val normalized = normalize(queryText)
    val terms = normalized.split(' ').filter { it.length > 1 }.toSet()
    val indexRanks = indexedIds.withIndex().associate { it.value to it.index }
    val pinnedIds = source.pinnedIds()
    // Candidates vouched for by the AppSearch index (or by pinning) earned their
    // slot and are exempt from the lexical floor applied to scan-only rows.
    val floorExemptIds = (indexedIds + pinnedIds).toSet()
    val candidates = if (indexedIds.isNotEmpty()) {
        source.candidatesIn(includePrivate, (indexedIds + pinnedIds).distinct())
    } else {
        source.candidates(includePrivate, 500)
    }
    val ranked = candidates.asSequence()
        .filter { it.sensitivity != MemorySensitivity.SECRET }
        .map { row ->
            val memoryTerms = row.normalizedContent.split(' ').filter { it.length > 1 }.toSet()
            val overlap = if (terms.isEmpty()) 0f else {
                terms.intersect(memoryTerms).size.toFloat() / terms.size
            }
            val phrase = if (normalized.isNotBlank() && normalized in row.normalizedContent) 0.35f else 0f
            val ageDays = ((now - row.updatedAt).coerceAtLeast(0L) / 86_400_000f)
            val recency = 1f / (1f + ageDays / 30f)
            val indexBoost = indexRanks[row.id]?.let { rank ->
                0.28f * (1f - rank.toFloat() / indexedIds.size.coerceAtLeast(1))
            } ?: 0f
            val lexicalScore =
                overlap * 0.48f +
                    phrase +
                    indexBoost +
                    row.importance * 0.1f +
                    row.confidence * 0.04f +
                    recency * 0.03f +
                    if (row.pinned) 0.25f else 0f
            RankedMemoryRow(
                row = row,
                score = lexicalScore,
                lexicalScore = lexicalScore,
            )
        }
        .filter {
            terms.isEmpty() || it.row.id in floorExemptIds || it.lexicalScore > 0.08f
        }
        .sortedByDescending(RankedMemoryRow::score)
        .take(limit.coerceIn(1, 24))
        .toList()
    val sourcesByMemoryId = if (ranked.isEmpty()) {
        emptyMap()
    } else {
        source.sourcesFor(ranked.map { it.row.id }).groupBy(MemorySourceEntity::memoryId)
    }
    return ranked.map { rankedRow ->
        val row = rankedRow.row
        val sources = sourcesByMemoryId[row.id].orEmpty().map(MemorySourceEntity::toDomain)
        MemoryHit(
            memory = row.toDomain(),
            score = rankedRow.score.coerceIn(0f, 1.5f),
            sources = sources,
            reason = when {
                row.pinned -> "Pinned memory"
                normalized in row.normalizedContent -> "Direct text match"
                else -> "Relevant personal memory"
            },
        )
    }
}

internal fun perSourceActivityLimit(intentSourceCount: Int): Int =
    if (intentSourceCount == 1) 8 else 3

/**
 * AppSearch documents are stale when their Room row is no longer ACTIVE
 * (superseded by a correction or deleted). Removal is idempotent: the
 * indexer tolerates ids that were never indexed.
 */
internal fun isStaleIndexStatus(status: MemoryStatus): Boolean =
    status == MemoryStatus.SUPERSEDED || status == MemoryStatus.DELETED

internal fun staleIndexDocIds(rows: List<MemoryStatusRow>): List<String> =
    rows.filter { isStaleIndexStatus(it.status) }.map(MemoryStatusRow::id)

internal fun applyPerSourceActivityQuota(
    rankedHits: List<Pair<ActivitySource, MemoryHit>>,
    limitPerSource: Int,
): List<MemoryHit> {
    val perSourceCount = mutableMapOf<ActivitySource, Int>()
    return rankedHits.mapNotNull { (source, hit) ->
        val count = perSourceCount.getOrDefault(source, 0)
        if (count >= limitPerSource) {
            null
        } else {
            perSourceCount[source] = count + 1
            hit
        }
    }
}

internal data class ActivityRetrievalIntent(
    val sources: Set<ActivitySource>,
    val periodStart: Long?,
    val periodEnd: Long?,
    val broadPhoneActivity: Boolean,
)

internal fun activityRetrievalIntent(
    query: String,
    now: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): ActivityRetrievalIntent {
    val normalized = normalize(query)
    val sources = buildSet {
        if (Regex("""\b(app|apps|application|screen time|used|opened)\b""").containsMatchIn(normalized)) {
            add(ActivitySource.APP_USAGE)
        }
        if (Regex("""\b(install|installed|uninstall|package|app inventory)\b""").containsMatchIn(normalized)) {
            add(ActivitySource.APP_INSTALL)
        }
        if (Regex("""\b(notification|notifications|alert|alerts)\b""").containsMatchIn(normalized)) {
            add(ActivitySource.NOTIFICATION)
        }
        if (Regex("""\b(on my screen|screen context|visible text|what was i reading)\b""").containsMatchIn(normalized)) {
            add(ActivitySource.ACCESSIBILITY)
        }
        if (Regex("""\b(where was i|location|locations|place|places|gps)\b""").containsMatchIn(normalized)) {
            add(ActivitySource.LOCATION)
        }
        if (Regex("""\b(sensor|sensors|temperature|light|pressure|humidity|step|steps)\b""").containsMatchIn(normalized)) {
            add(ActivitySource.SENSOR)
        }
        if (Regex("""\b(contact|contacts|address book)\b""").containsMatchIn(normalized)) {
            add(ActivitySource.CONTACT)
        }
        if (Regex("""\b(calendar|meeting|meetings|appointment|appointments|schedule|event|events)\b""").containsMatchIn(normalized)) {
            add(ActivitySource.CALENDAR)
        }
    }
    val broad = Regex(
        """\b(what did i do|my day|my week|recent phone activity|phone activity|activity today)\b""",
    ).containsMatchIn(normalized)
    val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
    val (periodStart, periodEnd) = when {
        "yesterday" in normalized -> {
            val day = today.minusDays(1)
            day.atStartOfDay(zoneId).toInstant().toEpochMilli() to
                today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        }
        "today" in normalized || "this morning" in normalized ||
            "this afternoon" in normalized || "tonight" in normalized -> {
            today.atStartOfDay(zoneId).toInstant().toEpochMilli() to now
        }
        "this week" in normalized -> {
            today.minusDays(today.dayOfWeek.value.toLong() - 1)
                .atStartOfDay(zoneId).toInstant().toEpochMilli() to now
        }
        "last week" in normalized -> {
            val thisWeek = today.minusDays(today.dayOfWeek.value.toLong() - 1)
            thisWeek.minusWeeks(1).atStartOfDay(zoneId).toInstant().toEpochMilli() to
                thisWeek.atStartOfDay(zoneId).toInstant().toEpochMilli()
        }
        "recent" in normalized || "recently" in normalized -> {
            now - java.util.concurrent.TimeUnit.DAYS.toMillis(7) to now
        }
        else -> null to null
    }
    return ActivityRetrievalIntent(
        sources = sources,
        periodStart = periodStart,
        periodEnd = periodEnd,
        broadPhoneActivity = broad,
    )
}

internal fun ActivityEventEntity.toMemoryHit(
    normalizedQuery: String,
    terms: Set<String>,
    now: Long,
    intent: ActivityRetrievalIntent,
): MemoryHit? {
    if (intent.periodStart != null && startedAt < intent.periodStart) return null
    if (intent.periodEnd != null && startedAt >= intent.periodEnd) return null
    val searchable = normalize(
        listOfNotNull(
            source.name,
            source.retrievalTerms(),
            eventType,
            packageName,
            title,
            redactedText,
            metadataJson,
        ).joinToString(" "),
    )
    val searchableTerms = searchable.split(' ').filter { it.length > 1 }.toSet()
    val overlap = if (terms.isEmpty()) 0f else {
        terms.intersect(searchableTerms).size.toFloat() / terms.size
    }
    val phrase = if (normalizedQuery.isNotBlank() && normalizedQuery in searchable) 0.35f else 0f
    val requestedSource = source in intent.sources
    val activityIntent = requestedSource || intent.broadPhoneActivity
    if (!activityIntent && terms.isNotEmpty() && overlap == 0f && phrase == 0f) return null
    if (intent.sources.isNotEmpty() && !requestedSource && overlap == 0f && phrase == 0f) return null
    val ageDays = ((now - startedAt).coerceAtLeast(0L) / 86_400_000f)
    val recency = 1f / (1f + ageDays / 14f)
    val score =
        overlap * 0.52f +
            phrase +
            recency * 0.12f +
            (if (requestedSource) 0.38f else 0f) +
            (if (intent.periodStart != null) 0.28f else 0f) +
            (if (intent.broadPhoneActivity) 0.18f else 0f) +
            (if (pinned) 0.25f else 0f)
    val sourceLabel = source.name.lowercase().replace('_', ' ')
    val content = formatActivityForPrompt()
    val memory = MemoryItem(
        id = "activity:$id",
        type = MemoryType.EPISODE,
        title = title ?: sourceLabel,
        content = content,
        confidence = 0.95f,
        importance = if (pinned) 0.9f else 0.5f,
        sensitivity = sensitivity,
        status = MemoryStatus.ACTIVE,
        pinned = pinned,
        validFrom = startedAt,
        validTo = endedAt,
        supersedesId = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
    return MemoryHit(
        memory = memory,
        score = score.coerceIn(0f, 1.5f),
        sources = listOf(
            MemorySource(
                id = "activity-source:$id",
                memoryId = memory.id,
                kind = MemorySourceKind.ACTIVITY,
                sourceId = id,
                label = sourceLabel,
                createdAt = createdAt,
            ),
        ),
        reason = "Relevant phone activity",
    )
}

internal fun ActivityEventEntity.formatActivityForPrompt(
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val time = Instant.ofEpochMilli(startedAt)
        .atZone(zoneId)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))
    val end = endedAt?.let {
        Instant.ofEpochMilli(it).atZone(zoneId).format(DateTimeFormatter.ofPattern("HH:mm z"))
    }
    val metadata = runCatching { Json.parseToJsonElement(metadataJson).jsonObject }.getOrNull()
    return when (source) {
        ActivitySource.APP_USAGE -> buildString {
            append("Used ${title ?: packageName ?: "an app"} at $time")
            end?.let { append(" until $it") }
            metadata?.get("foregroundMillis")
                ?.jsonPrimitive
                ?.longOrNull
                ?.takeIf { it > 0 }
                ?.let { duration ->
                    append(" for ${formatDuration(duration)}")
                }
            packageName?.let { append(" ($it)") }
            append('.')
        }
        ActivitySource.APP_INSTALL -> buildString {
            append("Installed-app record at $time: ${title ?: packageName ?: "unknown app"}")
            packageName?.let { append(" ($it)") }
            append(", event $eventType.")
        }
        ActivitySource.NOTIFICATION -> buildString {
            append("Notification at $time")
            packageName?.let { append(" from $it") }
            title?.takeIf(String::isNotBlank)?.let { append(": $it") }
            redactedText?.takeIf(String::isNotBlank)?.let { append(". $it") }
        }
        ActivitySource.ACCESSIBILITY -> buildString {
            append("Visible screen context at $time")
            packageName?.let { append(" in $it") }
            title?.takeIf(String::isNotBlank)?.let { append(" ($it)") }
            redactedText?.takeIf(String::isNotBlank)?.let { append(": $it") }
        }
        ActivitySource.LOCATION -> "Location snapshot at $time: $metadataJson"
        ActivitySource.SENSOR -> "Sensor snapshot at $time: ${title.orEmpty()} $metadataJson"
        ActivitySource.CONTACT -> "Contact available in the address book: ${title.orEmpty()}."
        ActivitySource.CALENDAR -> buildString {
            append("Calendar event ${title ?: "Untitled"} starts $time")
            end?.let { append(" and ends $it") }
            redactedText?.takeIf(String::isNotBlank)?.let { append(" at $it") }
            append('.')
        }
        ActivitySource.HEALTH -> "Health record at $time: ${title.orEmpty()} $metadataJson"
        ActivitySource.DOCUMENT -> "Document activity at $time: ${title.orEmpty()}."
        ActivitySource.SMS -> "SMS activity at $time: ${title.orEmpty()}. ${redactedText.orEmpty()}"
        ActivitySource.CALL -> "Call activity at $time: ${title.orEmpty()}."
    }
}

private fun formatDuration(milliseconds: Long): String {
    val totalMinutes = (milliseconds / 60_000).coerceAtLeast(1)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

private fun com.aliahad.aichat.core.ActivitySource.retrievalTerms(): String = when (this) {
    com.aliahad.aichat.core.ActivitySource.APP_USAGE ->
        "app apps application applications usage session screen time"
    com.aliahad.aichat.core.ActivitySource.APP_INSTALL ->
        "app apps application applications installed package inventory"
    com.aliahad.aichat.core.ActivitySource.NOTIFICATION ->
        "notification notifications alert alerts message messages"
    com.aliahad.aichat.core.ActivitySource.ACCESSIBILITY ->
        "screen screens visible interface activity"
    com.aliahad.aichat.core.ActivitySource.LOCATION ->
        "location locations place places position gps"
    com.aliahad.aichat.core.ActivitySource.SENSOR ->
        "sensor sensors temperature light pressure humidity steps"
    com.aliahad.aichat.core.ActivitySource.HEALTH ->
        "health fitness heart sleep exercise steps"
    com.aliahad.aichat.core.ActivitySource.CONTACT ->
        "contact contacts people person address book"
    com.aliahad.aichat.core.ActivitySource.CALENDAR ->
        "calendar calendars event events meeting meetings appointment appointments schedule"
    com.aliahad.aichat.core.ActivitySource.DOCUMENT ->
        "document documents file files folder folders"
    com.aliahad.aichat.core.ActivitySource.SMS ->
        "sms text texts message messages"
    com.aliahad.aichat.core.ActivitySource.CALL ->
        "call calls phone dialer"
}

object SensitiveTextRedactor {
    private val otp = Regex("""(?i)\b(?:otp|verification\s+code|one[- ]time\s+code)\D{0,12}(\d{4,8})\b""")
    private val password = Regex("""(?i)\b(password|passcode|pin)\s*(?:is|=|:)\s*\S+""")
    private val card = Regex("""\b(?:\d[ -]*?){13,19}\b""")

    fun redact(text: String): String = text
        .replace(otp) { match -> match.value.replace(match.groupValues[1], "[redacted]") }
        .replace(password) { match -> "${match.groupValues[1]}: [redacted]" }
        .replace(card, "[redacted payment number]")
}

private fun inferType(content: String): MemoryType {
    val normalized = content.lowercase()
    return when {
        Regex("""\b(i prefer|i like|i love|i dislike|my favorite)\b""").containsMatchIn(normalized) ->
            MemoryType.PREFERENCE
        Regex("""\b(my name is|i am called|call me)\b""").containsMatchIn(normalized) ->
            MemoryType.PERSON
        Regex("""\b(my goal|i want to|i plan to|i need to)\b""").containsMatchIn(normalized) ->
            MemoryType.GOAL
        Regex("""\b(project|working on|building)\b""").containsMatchIn(normalized) ->
            MemoryType.PROJECT
        Regex("""\b(remember that|always|never)\b""").containsMatchIn(normalized) ->
            MemoryType.FACT
        else -> MemoryType.EPISODE
    }
}

private fun titleFor(content: String): String =
    content.replace(Regex("\\s+"), " ").trim().take(96).ifEmpty { "Memory" }

private fun normalize(content: String): String =
    content.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun MemoryItemEntity.toDomain() = MemoryItem(
    id = id,
    type = type,
    title = title,
    content = content,
    confidence = confidence,
    importance = importance,
    sensitivity = sensitivity,
    status = status,
    pinned = pinned,
    validFrom = validFrom,
    validTo = validTo,
    supersedesId = supersedesId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun MemorySourceEntity.toDomain() = MemorySource(
    id = id,
    memoryId = memoryId,
    kind = kind,
    sourceId = sourceId,
    label = label,
    createdAt = createdAt,
)
