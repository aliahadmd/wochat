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

    /**
     * Embeds up to [limit] memories that have no vector yet. Returns how many were
     * written, so a caller can loop until it returns 0.
     */
    suspend fun backfillEmbeddings(limit: Int): Int
}

/**
 * Produces sentence embeddings for memory rows and queries.
 *
 * A narrow seam rather than a direct dependency on the inference engine, so the
 * repository stays testable and so a missing or still-downloading embedder simply
 * means null — retrieval falls back to lexical matching rather than failing.
 */
fun interface MemoryEmbedder {
    suspend fun embed(text: String): FloatArray?
}

class RoomMemoryRepository(
    private val database: AppDatabase,
    private val indexer: MemoryIndexer,
    private val embedder: MemoryEmbedder? = null,
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
        if (!isWorthRemembering(redacted, type)) return
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
        // Best-effort: a failed or absent embedder must degrade to lexical-only
        // retrieval, never break the turn.
        val queryEmbedding = embedder?.let { runCatching { it.embed(query.text) }.getOrNull() }
        val memoryHits = searchMemoryRows(
            source = searchDataSource,
            queryText = query.text,
            includePrivate = query.includePrivate,
            limit = query.limit,
            indexedIds = indexedIds,
            now = now,
            queryEmbedding = queryEmbedding,
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

    override suspend fun backfillEmbeddings(limit: Int): Int {
        val embed = embedder ?: return 0
        val pending = dao.withoutEmbedding(limit)
        var written = 0
        pending.forEach { row ->
            // One row at a time, committed as it goes: the pass is resumable, and a
            // failure part-way leaves the rows it already did embedded.
            val vector = runCatching { embed.embed(row.content) }.getOrNull() ?: return@forEach
            dao.setEmbedding(row.id, MemoryVectors.encode(vector))
            written++
        }
        return written
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
        val embedding = embedder?.let { runCatching { it.embed(content) }.getOrNull() }
        val row = entity(
            id = stableId ?: UUID.randomUUID().toString(),
            type = type,
            title = title,
            content = content,
            confidence = confidence,
            importance = importance,
            sensitivity = sensitivity,
            now = now,
            embedding = embedding,
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
        embedding: FloatArray? = null,
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
            embedding = embedding?.let(MemoryVectors::encode),
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
    val relevance: Float,
)

internal suspend fun searchMemoryRows(
    source: MemorySearchDataSource,
    queryText: String,
    includePrivate: Boolean,
    limit: Int,
    indexedIds: List<String>,
    now: Long,
    queryEmbedding: FloatArray? = null,
): List<MemoryHit> {
    val normalized = normalize(queryText)
    val terms = contentTerms(normalized)
    val indexRanks = indexedIds.withIndex().associate { it.value to it.index }
    val pinnedIds = source.pinnedIds().toSet()
    val candidates = if (indexedIds.isNotEmpty()) {
        source.candidatesIn(includePrivate, (indexedIds + pinnedIds).distinct())
    } else {
        source.candidates(includePrivate, 500)
    }
    val ranked = candidates.asSequence()
        .filter { it.sensitivity != MemorySensitivity.SECRET }
        .map { row ->
            val memoryTerms = contentTerms(row.normalizedContent)
            val overlap = if (terms.isEmpty()) 0f else {
                terms.intersect(memoryTerms).size.toFloat() / terms.size
            }
            val phrase = if (normalized.isNotBlank() && normalized in row.normalizedContent) 0.35f else 0f
            val ageDays = ((now - row.updatedAt).coerceAtLeast(0L) / 86_400_000f)
            val recency = 1f / (1f + ageDays / 30f)
            val indexBoost = indexRanks[row.id]?.let { rank ->
                0.28f * (1f - rank.toFloat() / indexedIds.size.coerceAtLeast(1))
            } ?: 0f
            // Relevance is the query-dependent part only. The priors below (importance,
            // confidence, recency, pinned) used to be inside the same number that was
            // thresholded, and they sum to ~0.10 for a typical episode — above the old
            // 0.08 floor on their own. Every recent memory therefore passed the filter
            // with zero overlap with the question, which is how 16 unrelated rows were
            // injected into every turn. Priors now only order rows that are already
            // relevant; they can no longer buy admission.
            // Semantic similarity is folded into relevance, not added on top of the
            // score, so a paraphrase clears the same floor a keyword match does.
            // Lexical is kept because embeddings are weak on exact tokens — names,
            // identifiers, error codes — which is precisely what memories carry.
            val semantic = queryEmbedding?.let { query ->
                MemoryVectors.decode(row.embedding)
                    ?.let { MemoryVectors.cosine(query, it) }
                    ?.let { similarity ->
                        // EmbeddingGemma puts unrelated short texts around 0.3-0.5, so
                        // the raw cosine is rebased before it can contribute anything.
                        ((similarity - SEMANTIC_BASELINE) / (1f - SEMANTIC_BASELINE))
                            .coerceIn(0f, 1f)
                    }
            } ?: 0f
            val relevance = maxOf(
                overlap * 0.48f + phrase + indexBoost * 0.5f,
                semantic * SEMANTIC_WEIGHT,
            )
            val priors = row.importance * 0.1f +
                row.confidence * 0.04f +
                recency * 0.03f +
                if (row.pinned) 0.25f else 0f
            RankedMemoryRow(
                row = row,
                score = relevance + priors,
                relevance = relevance,
            )
        }
        .filter {
            // An AppSearch hit is a recall mechanism, not a relevance verdict: it is
            // asked for limit * 8 candidates and returns loose matches. Require a real
            // lexical anchor in the query. Pinning is the user's own explicit act and
            // still overrides.
            terms.isEmpty() || it.row.pinned || it.relevance >= MIN_MEMORY_RELEVANCE
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


/**
 * Content-bearing words of a normalized string, with stop words removed.
 *
 * Overlap is measured as a fraction of the query's terms, so stop words used to
 * dilute it: "what coffee do I like" against a stored "I prefer dark roast coffee"
 * shared only `coffee` out of four counted terms, scoring 0.12 and falling below
 * the relevance floor — the memory existed, matched on the one word that mattered,
 * and was still dropped. Measured on the device before this was added.
 *
 * Falls back to the raw terms when a query is nothing but stop words, so such a
 * query is not silently treated as empty (which would match everything).
 */
internal fun contentTerms(normalized: String): Set<String> {
    val words = normalized.split(' ').filter { it.length > 1 }.toSet()
    val content = words - STOP_WORDS
    return content.ifEmpty { words }
}

private val STOP_WORDS = setOf(
    "the", "and", "for", "with", "that", "this", "was", "were", "are", "you",
    "your", "our", "their", "his", "her", "its", "have", "has", "had", "not",
    "but", "can", "could", "will", "would", "should", "shall", "may", "might",
    "what", "who", "when", "where", "why", "how", "which", "whose", "does",
    "did", "done", "get", "got", "let", "please", "tell", "show", "give",
    "about", "from", "into", "onto", "than", "then", "there", "here", "some",
    "any", "all", "each", "very", "just", "like", "want", "need", "know",
)

/**
 * Cosine below which two texts are treated as unrelated.
 *
 * Measured on the device with EmbeddingGemma 300M Q8 against the stored memory
 * "I prefer green tea after lunch":
 *
 * | query | cosine |
 * |---|---|
 * | "How do I compile the Rust project" (unrelated) | 0.290 |
 * | "What beverage do I enjoy after eating" (paraphrase) | 0.582 |
 *
 * 0.40 sits well clear of the unrelated end while leaving a real paraphrase enough
 * headroom to clear [MIN_MEMORY_RELEVANCE]. This was first guessed at 0.55 — above
 * where genuine paraphrases actually land — and the feature silently retrieved
 * nothing until the two ends were measured rather than assumed.
 */
internal const val SEMANTIC_BASELINE = 0.40f

/**
 * Ceiling on what a purely semantic match can score, chosen so a strong paraphrase
 * comfortably clears [MIN_MEMORY_RELEVANCE] while a mediocre one does not.
 */
internal const val SEMANTIC_WEIGHT = 0.6f

/**
 * Minimum query-dependent score a memory needs before it may be injected.
 *
 * Calibrated so a row AppSearch merely surfaced, with no shared term and no phrase
 * match, scores at most `0.28 * 0.5 = 0.14` and is rejected, while one shared term
 * out of three (`0.33 * 0.48 = 0.16`) is admitted.
 */
internal const val MIN_MEMORY_RELEVANCE = 0.15f

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

internal fun inferType(content: String): MemoryType {
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

/**
 * Whether a user message asserts something worth recalling in a later conversation.
 *
 * Every user message used to become a memory, which is how rows containing `"4"`,
 * `"26"` and `"What is 2 plus 2"` ended up being retrieved and injected into
 * prompts — 16 of them on every single turn.
 *
 * [inferType] returns [MemoryType.EPISODE] as its *fallback*, so an EPISODE is by
 * definition a message that did not look like a stated fact, preference, goal or
 * project. Those are kept only when they read as a substantial statement: a
 * question is a request for information, not a durable fact about the user, and
 * short chatter carries nothing worth recalling.
 */
internal fun isWorthRemembering(content: String, type: MemoryType): Boolean {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return false
    // Questions are checked before classification, not after. inferType matches on
    // substrings, so "What do I like?" contains "i like" and classifies as a
    // PREFERENCE — it was observed stored as one on the device. A question is a
    // request for information in every case, never an assertion worth recalling.
    if (isQuestion(trimmed)) return false
    // Length is not a proxy for worth once the message has been classified:
    // "My name is Ali" is 14 characters and is precisely what memory exists for.
    if (type != MemoryType.EPISODE) return true
    if (trimmed.length < MIN_MEMORY_CHARS) return false
    return trimmed.split(WHITESPACE).size >= MIN_MEMORY_WORDS
}

private fun isQuestion(content: String): Boolean {
    if (content.endsWith("?")) return true
    val firstWord = content.substringBefore(' ').lowercase().trim('"', '\'', '(')
    return firstWord in INTERROGATIVES
}

private val WHITESPACE = Regex("\\s+")

private val INTERROGATIVES = setOf(
    "what", "who", "when", "where", "why", "how", "which", "whose",
    "is", "are", "was", "were", "do", "does", "did", "can", "could",
    "will", "would", "should", "shall", "may", "might", "am", "tell",
    "explain", "show", "list", "give", "summarize", "write", "make",
)

/** Below this a message cannot carry a fact worth recalling. */
private const val MIN_MEMORY_CHARS = 24

/** An unclassified statement needs this much substance to earn a row. */
private const val MIN_MEMORY_WORDS = 8

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
