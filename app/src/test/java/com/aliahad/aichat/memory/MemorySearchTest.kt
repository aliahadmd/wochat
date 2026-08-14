package com.aliahad.aichat.memory

import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.core.MemoryHit
import com.aliahad.aichat.core.MemoryItem
import com.aliahad.aichat.core.MemorySensitivity
import com.aliahad.aichat.core.MemorySourceKind
import com.aliahad.aichat.core.MemoryStatus
import com.aliahad.aichat.core.MemoryType
import com.aliahad.aichat.data.MemoryItemEntity
import com.aliahad.aichat.data.MemorySourceEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_781_280_000_000L

class MemorySearchTest {
    @Test
    fun pinnedMemoryReturnedEvenWhenAbsentFromIndexedIds() = runTest {
        val pinned = memoryRow("pinned-1", "weekend trip plans to kyoto", pinned = true)
        val indexed = memoryRow("indexed-1", "favorite tea is green")
        val source = FakeMemorySearchSource(listOf(pinned, indexed))

        val hits = searchMemoryRows(
            source = source,
            queryText = "favorite tea",
            includePrivate = true,
            limit = 8,
            indexedIds = listOf("indexed-1"),
            now = NOW,
        )

        val ids = hits.map { it.memory.id }
        assertTrue("pinned memory missing from results", "pinned-1" in ids)
        assertTrue("indexed memory missing from results", "indexed-1" in ids)
        assertEquals(1, source.candidatesInCalls)
        assertEquals(0, source.candidatesCalls)
    }

    @Test
    fun secretMemoryNeverReturned() = runTest {
        val secret = memoryRow(
            id = "secret-1",
            content = "bank pin is my favorite number",
            sensitivity = MemorySensitivity.SECRET,
        )
        val normal = memoryRow("normal-1", "favorite number is seven")
        val source = FakeMemorySearchSource(listOf(secret, normal))

        val hits = searchMemoryRows(
            source = source,
            queryText = "favorite number",
            includePrivate = true,
            limit = 8,
            indexedIds = listOf("secret-1", "normal-1"),
            now = NOW,
        )

        assertTrue("secret memory leaked", hits.none { it.memory.id == "secret-1" })
        assertTrue("normal-1" in hits.map { it.memory.id })
    }

    @Test
    fun emptyIndexedIdsFallsBackToFullCandidateScan() = runTest {
        val matching = memoryRow("m-1", "favorite tea is oolong")
        val belowThreshold = memoryRow("m-2", "xyzzy quorum plinth", importance = 0f, confidence = 0f)
        val source = FakeMemorySearchSource(listOf(matching, belowThreshold))

        val hits = searchMemoryRows(
            source = source,
            queryText = "favorite tea",
            includePrivate = true,
            limit = 8,
            indexedIds = emptyList(),
            now = NOW,
        )

        assertEquals(1, source.candidatesCalls)
        assertEquals(0, source.candidatesInCalls)
        val ids = hits.map { it.memory.id }
        assertTrue("fallback scan should return term-matching row", "m-1" in ids)
        assertTrue("below-threshold row should be filtered", "m-2" !in ids)
    }

    @Test
    fun phraseMatchOutranksPureOverlap() = runTest {
        val phraseRow = memoryRow("phrase", "my coffee shop routine every saturday")
        val overlapRow = memoryRow("overlap", "shop for coffee beans weekly")
        val source = FakeMemorySearchSource(listOf(phraseRow, overlapRow))

        val hits = searchMemoryRows(
            source = source,
            queryText = "coffee shop",
            includePrivate = true,
            limit = 8,
            indexedIds = emptyList(),
            now = NOW,
        )

        assertEquals(listOf("phrase", "overlap"), hits.map { it.memory.id })
    }

    @Test
    fun perSourceActivityQuotaCapsThreeForMultipleSourcesAndEightForSingle() {
        val ranked = (1..10).map { index ->
            ActivitySource.APP_USAGE to activityHit("usage-$index", 1f - index * 0.01f)
        } + (1..10).map { index ->
            ActivitySource.NOTIFICATION to activityHit("notif-$index", 0.5f - index * 0.01f)
        }

        assertEquals(8, perSourceActivityLimit(1))
        assertEquals(3, perSourceActivityLimit(2))
        assertEquals(3, perSourceActivityLimit(0))

        val multiQuota = applyPerSourceActivityQuota(ranked, perSourceActivityLimit(2))
        assertEquals(6, multiQuota.size)
        assertEquals(3, multiQuota.count { it.memory.id.startsWith("usage-") })
        assertEquals(3, multiQuota.count { it.memory.id.startsWith("notif-") })

        val singleQuota = applyPerSourceActivityQuota(ranked, perSourceActivityLimit(1))
        assertEquals(16, singleQuota.size)
        assertEquals(8, singleQuota.count { it.memory.id.startsWith("usage-") })
        assertEquals(8, singleQuota.count { it.memory.id.startsWith("notif-") })
    }

    @Test
    fun batchedSourcesMappingIdenticalToPerRowLookups() = runTest {
        val first = memoryRow("mem-a", "favorite tea is darjeeling")
        val second = memoryRow("mem-b", "favorite tea is matcha")
        val source = FakeMemorySearchSource(listOf(first, second))
        source.addSource("mem-a", 0)
        source.addSource("mem-a", 1)
        source.addSource("mem-b", 2)

        val hits = searchMemoryRows(
            source = source,
            queryText = "favorite tea",
            includePrivate = true,
            limit = 8,
            indexedIds = emptyList(),
            now = NOW,
        )

        assertEquals("expected exactly one batched sources query", 1, source.sourcesForCalls)
        assertEquals(2, hits.size)
        for (hit in hits) {
            val expected = source.allSourcesFor(hit.memory.id)
            assertEquals(expected.map { it.id }, hit.sources.map { it.id })
            assertEquals(expected.map { it.createdAt }, hit.sources.map { it.createdAt })
            assertEquals(expected.map { it.kind }, hit.sources.map { it.kind })
        }
    }

    @Test
    fun staleIndexedIdsAreSilentlyDropped() = runTest {
        val real = memoryRow("real-1", "favorite tea is sencha")
        val source = FakeMemorySearchSource(listOf(real))

        val hits = searchMemoryRows(
            source = source,
            queryText = "favorite tea",
            includePrivate = true,
            limit = 8,
            indexedIds = listOf("ghost-id", "real-1"),
            now = NOW,
        )

        assertEquals(listOf("real-1"), hits.map { it.memory.id })
    }
}

private class FakeMemorySearchSource(
    private val rows: List<MemoryItemEntity>,
) : MemorySearchDataSource {
    private val sourceRows = mutableListOf<MemorySourceEntity>()
    var candidatesCalls = 0
        private set
    var candidatesInCalls = 0
        private set
    var sourcesForCalls = 0
        private set

    fun addSource(memoryId: String, index: Int) {
        sourceRows += MemorySourceEntity(
            id = "$memoryId-source-$index",
            memoryId = memoryId,
            kind = MemorySourceKind.CHAT_MESSAGE,
            sourceId = "message-$index",
            label = "Chat message",
            createdAt = NOW + index,
        )
    }

    fun allSourcesFor(memoryId: String): List<MemorySourceEntity> =
        sourceRows.filter { it.memoryId == memoryId }.sortedBy { it.createdAt }

    override suspend fun pinnedIds(): List<String> =
        rows.filter { it.pinned && it.status == MemoryStatus.ACTIVE }.map { it.id }

    override suspend fun candidates(includePrivate: Boolean, limit: Int): List<MemoryItemEntity> {
        candidatesCalls++
        return activeRows(includePrivate).take(limit)
    }

    override suspend fun candidatesIn(
        includePrivate: Boolean,
        ids: List<String>,
    ): List<MemoryItemEntity> {
        candidatesInCalls++
        return activeRows(includePrivate).filter { it.id in ids }
    }

    override suspend fun sourcesFor(memoryIds: List<String>): List<MemorySourceEntity> {
        sourcesForCalls++
        return sourceRows.filter { it.memoryId in memoryIds }.sortedBy { it.createdAt }
    }

    private fun activeRows(includePrivate: Boolean): List<MemoryItemEntity> = rows
        .filter { it.status == MemoryStatus.ACTIVE }
        .filter { includePrivate || it.sensitivity == MemorySensitivity.NORMAL }
        .sortedWith(
            compareByDescending<MemoryItemEntity> { it.pinned }
                .thenByDescending { it.importance }
                .thenByDescending { it.updatedAt },
        )
}

private fun memoryRow(
    id: String,
    content: String,
    pinned: Boolean = false,
    sensitivity: MemorySensitivity = MemorySensitivity.PRIVATE,
    status: MemoryStatus = MemoryStatus.ACTIVE,
    importance: Float = 0.5f,
    confidence: Float = 0.9f,
    updatedAt: Long = NOW,
) = MemoryItemEntity(
    id = id,
    searchRowId = 1L,
    type = MemoryType.FACT,
    title = content.take(96),
    content = content,
    normalizedContent = content.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim(),
    contentHash = "hash-$id",
    confidence = confidence,
    importance = importance,
    sensitivity = sensitivity,
    status = status,
    pinned = pinned,
    validFrom = null,
    validTo = null,
    supersedesId = null,
    createdAt = NOW,
    updatedAt = updatedAt,
)

private fun activityHit(id: String, score: Float) = MemoryHit(
    memory = MemoryItem(
        id = id,
        type = MemoryType.EPISODE,
        title = id,
        content = id,
        confidence = 0.95f,
        importance = 0.5f,
        sensitivity = MemorySensitivity.PRIVATE,
        status = MemoryStatus.ACTIVE,
        pinned = false,
        validFrom = null,
        validTo = null,
        supersedesId = null,
        createdAt = NOW,
        updatedAt = NOW,
    ),
    score = score,
    sources = emptyList(),
    reason = "Relevant phone activity",
)
