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
import com.aliahad.aichat.core.sha256
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.data.MemoryCorrectionEntity
import com.aliahad.aichat.data.MemoryItemEntity
import com.aliahad.aichat.data.MemorySourceEntity
import com.aliahad.aichat.data.MemoryStatusRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max

interface MemoryRepository {
    val memories: Flow<List<MemoryItem>>
    val memorySources: Flow<Map<String, List<MemorySource>>>
    suspend fun rememberMessage(message: ChatMessage, conversationTemporary: Boolean)
    suspend fun rememberAttachment(attachmentId: String, displayName: String, content: String)
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
    suspend fun purgeStaleIndexDocs()
    suspend fun purgeExpiredMemories(now: Long): Int
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

    override val memorySources: Flow<Map<String, List<MemorySource>>> =
        dao.observeActive().map { rows ->
            if (rows.isEmpty()) {
                emptyMap()
            } else {
                dao.sourcesFor(rows.map(MemoryItemEntity::id))
                    .groupBy(MemorySourceEntity::memoryId)
                    .mapValues { (_, sources) -> sources.map(MemorySourceEntity::toDomain) }
            }
        }

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

    override suspend fun rememberAttachment(
        attachmentId: String,
        displayName: String,
        content: String,
    ) {
        val redacted = SensitiveTextRedactor.redact(content).trim()
        if (redacted.isBlank()) return
        val type = inferType(redacted)
        insertIfAbsent(
            type = type,
            title = titleFor(redacted),
            content = redacted,
            confidence = if (type == MemoryType.EPISODE) 0.72f else 0.9f,
            importance = if (type == MemoryType.EPISODE) 0.45f else 0.78f,
            sensitivity = MemorySensitivity.PRIVATE,
            sourceKind = MemorySourceKind.ATTACHMENT,
            sourceId = attachmentId,
            sourceLabel = displayName.takeIf(String::isNotBlank) ?: "Attachment",
            stableId = "attachment:$attachmentId",
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
        // The expansion widens AppSearch candidate recall only; retrieval intent,
        // lexical terms/floor, and phrase matching below use query.text alone.
        val indexedIds = runCatching {
            indexer.searchIds(
                memoryIndexQueryText(query.text, query.expansion),
                (query.limit.coerceIn(1, 24) * 8).coerceAtMost(128),
            )
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
        return memoryHits.take(query.limit)
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

    /** Best-effort AppSearch upsert for a memory written outside the index path. */
    suspend fun indexMemory(item: MemoryItem) {
        runCatching { indexer.upsert(item) }
    }

    override suspend fun purgeStaleIndexDocs() {
        staleIndexDocIds(dao.idStatusRows()).forEach { id ->
            runCatching { indexer.remove(id) }
        }
    }

    override suspend fun purgeExpiredMemories(now: Long): Int {
        val cutoff = memoryPurgeCutoff(now)
        var purged = 0
        while (true) {
            val ids = dao.purgeableIds(cutoff, MEMORY_PURGE_BATCH_SIZE)
            if (ids.isEmpty()) break
            // Remove index docs BEFORE deleting the rows: a crash between the two
            // steps then leaves a missing doc for a dead row (visible to the
            // startup sweep) instead of an orphan doc that nothing references.
            // Removal is idempotent; the rows are already non-ACTIVE.
            ids.forEach { id ->
                runCatching { indexer.remove(id) }
            }
            database.withTransaction {
                dao.deleteSourcesFor(ids)
                dao.deleteCorrectionsFor(ids)
                dao.deleteByIds(ids)
            }
            purged += ids.size
        }
        return purged
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
        upsertIndex: Boolean = true,
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
            return existing.toDomain().also {
                if (upsertIndex) runCatching { indexer.upsert(it) }
            }
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
        return row.toDomain().also {
            if (upsertIndex) runCatching { indexer.upsert(it) }
        }
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
        return MemoryItemEntity(
            id = id,
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

/**
 * Normalized AppSearch query text: the current message plus the recall-only
 * expansion. Used exclusively for [MemoryIndexer.searchIds] candidate lookup.
 */
internal fun memoryIndexQueryText(text: String, expansion: String): String =
    normalize(
        if (expansion.isBlank()) text else "$text $expansion",
    )

/**
 * Merges memory and activity hits into the final ranked result. Stable sort: at
 * equal (capped) scores memory hits keep precedence over activity hits because
 * they are concatenated first.
 */
internal fun mergeSearchHits(
    memoryHits: List<MemoryHit>,
    activityHits: List<MemoryHit>,
    limit: Int,
): List<MemoryHit> = (memoryHits + activityHits)
    .sortedByDescending(MemoryHit::score)
    .take(limit.coerceIn(1, 24))

/**
 * AppSearch documents are stale when their Room row is no longer ACTIVE
 * (superseded by a correction or deleted). Removal is idempotent: the
 * indexer tolerates ids that were never indexed.
 */
internal fun isStaleIndexStatus(status: MemoryStatus): Boolean =
    status == MemoryStatus.SUPERSEDED || status == MemoryStatus.DELETED

internal fun staleIndexDocIds(rows: List<MemoryStatusRow>): List<String> =
    rows.filter { isStaleIndexStatus(it.status) }.map(MemoryStatusRow::id)

/** Physical deletion grace period for DELETED/SUPERSEDED memories, in days. */
internal const val MEMORY_PURGE_RETENTION_DAYS = 30L

/** Batch size used when purging expired memory rows in a single transaction. */
internal const val MEMORY_PURGE_BATCH_SIZE = 500

/** Timestamp before which a non-ACTIVE memory is eligible for physical deletion. */
internal fun memoryPurgeCutoff(now: Long, retentionDays: Long = MEMORY_PURGE_RETENTION_DAYS): Long =
    now - TimeUnit.DAYS.toMillis(retentionDays)

/**
 * A memory is purgeable once it is no longer ACTIVE (deleted or superseded) and its
 * last update predates the retention cutoff. Active memories are never purged.
 */
internal fun isMemoryPurgeable(
    status: MemoryStatus,
    updatedAt: Long,
    cutoff: Long,
): Boolean = isStaleIndexStatus(status) && updatedAt < cutoff

// Compiled once: retrieval intent runs on every memory-enabled chat turn.
private val APP_USAGE_INTENT = Regex("""\b(app|apps|application|screen time|used|opened)\b""")
private val APP_INSTALL_INTENT = Regex("""\b(install|installed|uninstall|package|app inventory)\b""")
private val NOTIFICATION_INTENT = Regex("""\b(notification|notifications|alert|alerts)\b""")
private val ACCESSIBILITY_INTENT =
    Regex("""\b(on my screen|screen context|visible text|what was i reading)\b""")
private val LOCATION_INTENT = Regex("""\b(where was i|location|locations|place|places|gps)\b""")
private val SENSOR_INTENT =
    Regex("""\b(sensor|sensors|temperature|light|pressure|humidity|step|steps)\b""")
private val CONTACT_INTENT = Regex("""\b(contact|contacts|address book)\b""")
private val CALENDAR_INTENT =
    Regex("""\b(calendar|meeting|meetings|appointment|appointments|schedule|event|events)\b""")
private val BROAD_PHONE_ACTIVITY_INTENT =
    Regex("""\b(what did i do|my day|my week|recent phone activity|phone activity|activity today)\b""")

object SensitiveTextRedactor {
    private val otp = Regex(
        """(?i)\b(?:otp|2fa|mfa|totp|passcode|pin|verify|verification|""" +
            """verification\s+code|one[- ]time\s+code|backup\s+code|recovery\s+code|""" +
            """code|secret|token|login)\D{0,12}(\d{4,8})\b""",
    )
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

// Compiled once: normalize runs per candidate row on every memory search.
private val NORMALIZE_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

private fun normalize(content: String): String =
    content.lowercase().replace(NORMALIZE_SEPARATOR, " ").trim()

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
