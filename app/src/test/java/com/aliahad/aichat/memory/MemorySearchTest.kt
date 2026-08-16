package com.aliahad.aichat.memory

import com.aliahad.aichat.core.MemoryHit
import com.aliahad.aichat.core.MemoryItem
import com.aliahad.aichat.core.MemorySensitivity
import com.aliahad.aichat.core.MemorySourceKind
import com.aliahad.aichat.core.MemoryStatus
import com.aliahad.aichat.core.MemoryType
import com.aliahad.aichat.data.MemoryItemEntity
import com.aliahad.aichat.data.MemorySourceEntity
import com.aliahad.aichat.data.MemoryStatusRow
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

    @Test
    fun lexicalFloorAtPointZeroEightFiltersWeakRows() = runTest {
        // The floor applies to scan-only (non-indexed, non-pinned) rows. With
        // zero overlap, zero phrase, zero importance/confidence the only
        // contribution is recency * 0.03. Rows whose lexical score is not
        // strictly greater than 0.08 must be filtered out.
        val aboveFloor = memoryRow("above", "unrelated content alpha", importance = 0.51f, confidence = 0f)
        val atFloor = memoryRow("at-floor", "unrelated content gamma", importance = 0.5f, confidence = 0f)
        val belowFloor = memoryRow("below", "unrelated content beta", importance = 0.49f, confidence = 0f)
        val source = FakeMemorySearchSource(listOf(aboveFloor, atFloor, belowFloor))

        val hits = searchMemoryRows(
            source = source,
            queryText = "zebra quartz",
            includePrivate = true,
            limit = 8,
            indexedIds = emptyList(),
            now = NOW,
        )

        val ids = hits.map { it.memory.id }
        assertTrue("row scoring ~0.081 must pass the 0.08 floor", "above" in ids)
        assertTrue("row scoring exactly 0.08 is not strictly above the floor", "at-floor" !in ids)
        assertTrue("row scoring ~0.079 must be filtered", "below" !in ids)
    }

    @Test
    fun indexedCandidateSurvivesLexicalFloor() = runTest {
        // Eight ghost index ids push indexed-weak to the last rank, so its
        // score is index boost 0.28 * (1 - 8/9) ~= 0.031 plus recency 0.03,
        // i.e. ~0.061 — strictly below the 0.08 floor. Candidates vouched for
        // by the AppSearch index are exempt from the floor and must survive.
        val indexedWeak = memoryRow(
            "indexed-weak",
            "unrelated content omega",
            importance = 0f,
            confidence = 0f,
        )
        val source = FakeMemorySearchSource(listOf(indexedWeak))
        val indexedIds = (1..8).map { index -> "ghost-$index" } + "indexed-weak"

        val hits = searchMemoryRows(
            source = source,
            queryText = "zebra quartz",
            includePrivate = true,
            limit = 8,
            indexedIds = indexedIds,
            now = NOW,
        )

        assertEquals(listOf("indexed-weak"), hits.map { it.memory.id })
    }

    @Test
    fun secretIndexedCandidateStillFilteredDespiteFloorExemption() = runTest {
        val secret = memoryRow(
            id = "secret-indexed",
            content = "zebra quartz vault code",
            sensitivity = MemorySensitivity.SECRET,
        )
        val normal = memoryRow("normal-indexed", "zebra quartz garden")
        val source = FakeMemorySearchSource(listOf(secret, normal))

        val hits = searchMemoryRows(
            source = source,
            queryText = "zebra quartz",
            includePrivate = true,
            limit = 8,
            indexedIds = listOf("secret-indexed", "normal-indexed"),
            now = NOW,
        )

        assertTrue("secret memory leaked despite sensitivity filter", hits.none { it.memory.id == "secret-indexed" })
        assertEquals(listOf("normal-indexed"), hits.map { it.memory.id })
    }

    @Test
    fun pinnedRowSurvivesFloorEvenWithoutIndexMembership() = runTest {
        // Pinned rows are floor-exempt like indexed candidates; with zero
        // importance/confidence the pinned bonus alone keeps them stable.
        val pinnedWeak = memoryRow(
            "pinned-weak",
            "unrelated content epsilon",
            pinned = true,
            importance = 0f,
            confidence = 0f,
        )
        val indexed = memoryRow("indexed-1", "favorite tea is jasmine")
        val source = FakeMemorySearchSource(listOf(pinnedWeak, indexed))

        val hits = searchMemoryRows(
            source = source,
            queryText = "favorite tea",
            includePrivate = true,
            limit = 8,
            indexedIds = listOf("indexed-1"),
            now = NOW,
        )

        val ids = hits.map { it.memory.id }
        assertTrue("pinned memory dropped by the lexical floor", "pinned-weak" in ids)
        assertTrue("indexed-1" in ids)
    }

    @Test
    fun lexicalFloorDoesNotApplyWhenQueryHasNoTerms() = runTest {
        val weak = memoryRow("weak", "unrelated content delta", importance = 0f, confidence = 0f)
        val source = FakeMemorySearchSource(listOf(weak))

        val hits = searchMemoryRows(
            source = source,
            queryText = "x",
            includePrivate = true,
            limit = 8,
            indexedIds = emptyList(),
            now = NOW,
        )

        assertEquals(listOf("weak"), hits.map { it.memory.id })
    }

    @Test
    fun indexQueryTextCombinesCurrentTextAndExpansionForAppSearch() {
        assertEquals(
            "tea ceremony matcha history",
            memoryIndexQueryText("Tea ceremony", "matcha history"),
        )
        assertEquals("tea ceremony", memoryIndexQueryText("Tea ceremony", ""))
        assertEquals("tea ceremony", memoryIndexQueryText("Tea ceremony", "   "))
    }

    /**
     * Merge-level freeze: a fully-bonused activity event scores raw
     * 0.52 + 0.35 + 0.12 + 0.38 + 0.28 + 0.18 + 0.25 = 2.08, but the activity
     * hit is capped at the 1.5 memory ceiling and a memory hit at that ceiling
     * keeps precedence at equal scores.
     */
}

/** Pure-predicate coverage for the AppSearch stale-document startup sweep. */
class StaleIndexPurgeTest {
    @Test
    fun onlySupersededAndDeletedRowsAreStale() {
        assertTrue(isStaleIndexStatus(MemoryStatus.SUPERSEDED))
        assertTrue(isStaleIndexStatus(MemoryStatus.DELETED))
        assertEquals(false, isStaleIndexStatus(MemoryStatus.ACTIVE))
    }

    @Test
    fun staleIndexDocIdsSelectsOnlyInactiveRowsPreservingOrder() {
        val rows = listOf(
            MemoryStatusRow("active-1", MemoryStatus.ACTIVE),
            MemoryStatusRow("superseded-1", MemoryStatus.SUPERSEDED),
            MemoryStatusRow("active-2", MemoryStatus.ACTIVE),
            MemoryStatusRow("deleted-1", MemoryStatus.DELETED),
        )

        assertEquals(listOf("superseded-1", "deleted-1"), staleIndexDocIds(rows))
        assertEquals(emptyList<String>(), staleIndexDocIds(emptyList()))
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


