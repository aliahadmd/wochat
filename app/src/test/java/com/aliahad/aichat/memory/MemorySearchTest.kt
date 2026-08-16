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
    fun semanticMatchIsRetrievedWhenNoWordsAreShared() = runTest {
        // The reason embeddings exist here: "what do I drink in the mornings" shares
        // no content word with the stored preference, so the lexical half scores 0
        // and the memory would be missed entirely.
        val stored = memoryRow("coffee", "I prefer dark roast coffee from Ethiopia")
            .copy(embedding = MemoryVectors.encode(unitVector(0)))
        val source = FakeMemorySearchSource(listOf(stored))

        val hits = searchMemoryRows(
            source = source,
            queryText = "what do I drink in the mornings",
            includePrivate = true,
            limit = 4,
            indexedIds = emptyList(),
            now = NOW,
            // Near-identical direction: a strong paraphrase.
            queryEmbedding = unitVector(0, similarity = 0.95f),
        )

        assertEquals(listOf("coffee"), hits.map { it.memory.id })
    }

    @Test
    fun weakSemanticSimilarityDoesNotClearTheFloor() = runTest {
        // Unrelated text measured at ~0.29 and a true paraphrase at ~0.58 on the real
        // model, so 0.45 is the genuinely ambiguous middle: it must not be admitted.
        val stored = memoryRow("unrelated", "The staging database migrates on Sundays")
            .copy(embedding = MemoryVectors.encode(unitVector(0)))
        val source = FakeMemorySearchSource(listOf(stored))

        val hits = searchMemoryRows(
            source = source,
            queryText = "what do I drink in the mornings",
            includePrivate = true,
            limit = 4,
            indexedIds = emptyList(),
            now = NOW,
            queryEmbedding = unitVector(0, similarity = 0.45f),
        )

        assertTrue("weak semantic match was injected", hits.isEmpty())
    }

    @Test
    fun rowsWithoutEmbeddingsStillRankLexically() = runTest {
        // Backfill is incremental, so un-embedded rows are normal and must keep
        // working exactly as they did before embeddings existed.
        val stored = memoryRow("coffee", "I prefer dark roast coffee from Ethiopia")
        val source = FakeMemorySearchSource(listOf(stored))

        val hits = searchMemoryRows(
            source = source,
            queryText = "What coffee do I like",
            includePrivate = true,
            limit = 4,
            indexedIds = emptyList(),
            now = NOW,
            queryEmbedding = unitVector(0),
        )

        assertEquals(listOf("coffee"), hits.map { it.memory.id })
    }


    @Test
    fun statedPreferenceIsRecalledDespiteStopWordsInTheQuestion() = runTest {
        // Regression caught on the device, not in review. "What coffee do I like"
        // shares exactly one content word with the stored preference; counting the
        // stop words what/do/like in the denominator dropped overlap to 0.25 and the
        // memory — the whole point of the feature — was silently not retrieved.
        val preference = memoryRow("coffee", "I prefer dark roast coffee from Ethiopia")
        val source = FakeMemorySearchSource(listOf(preference))

        val hits = searchMemoryRows(
            source = source,
            queryText = "What coffee do I like",
            includePrivate = true,
            limit = 4,
            indexedIds = emptyList(),
            now = NOW,
        )

        assertEquals(listOf("coffee"), hits.map { it.memory.id })
    }

    @Test
    fun queryOfOnlyStopWordsDoesNotMatchEverything() = runTest {
        val row = memoryRow("row", "I prefer dark roast coffee from Ethiopia")
        val source = FakeMemorySearchSource(listOf(row))

        val hits = searchMemoryRows(
            source = source,
            queryText = "what about the",
            includePrivate = true,
            limit = 4,
            indexedIds = emptyList(),
            now = NOW,
        )

        assertTrue("stop-word-only query matched an unrelated memory", hits.isEmpty())
    }

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
    fun highImportanceCannotBuyAdmissionWithoutRelevance() = runTest {
        // Replaces a test that asserted the old floor's semantics directly: rows were
        // admitted or rejected on importance alone, with zero overlap with the query.
        // That was the defect, not the contract. Importance now only orders rows that
        // already earned their place, so even a maximally important, freshly updated
        // memory is excluded when it shares nothing with the question.
        val important = memoryRow(
            "important",
            "unrelated content alpha",
            importance = 1f,
            confidence = 1f,
            updatedAt = NOW,
        )
        val source = FakeMemorySearchSource(listOf(important))

        val hits = searchMemoryRows(
            source = source,
            queryText = "zebra quartz",
            includePrivate = true,
            limit = 8,
            indexedIds = emptyList(),
            now = NOW,
        )

        assertTrue("priors bought admission without relevance", hits.isEmpty())
    }

    @Test
    fun indexedCandidateWithNoSharedTermIsRejected() = runTest {
        // Inverted deliberately. This used to assert the opposite — that anything
        // AppSearch returned was exempt from the relevance floor. That exemption is
        // why 16 unrelated memories were injected into every single turn: AppSearch
        // is asked for limit * 8 loose candidates, so "vouched for by the index" was
        // never a relevance verdict. A hit sharing no term with the query and
        // matching no phrase must now be dropped.
        val indexedWeak = memoryRow(
            "indexed-weak",
            "unrelated content omega",
            importance = 0f,
            confidence = 0f,
        )
        val source = FakeMemorySearchSource(listOf(indexedWeak))

        val hits = searchMemoryRows(
            source = source,
            queryText = "zebra quartz",
            includePrivate = true,
            limit = 8,
            indexedIds = listOf("indexed-weak"),
            now = NOW,
        )

        assertTrue("irrelevant indexed row was injected", hits.isEmpty())
    }

    @Test
    fun indexedCandidateSharingATermIsStillReturned() = runTest {
        // The guard against over-correcting: a retrieval system that returns nothing
        // is fast and useless. One shared term out of two must still be admitted.
        val relevant = memoryRow("relevant", "zebra migration notes")
        val source = FakeMemorySearchSource(listOf(relevant))

        val hits = searchMemoryRows(
            source = source,
            queryText = "zebra quartz",
            includePrivate = true,
            limit = 8,
            indexedIds = listOf("relevant"),
            now = NOW,
        )

        assertEquals(listOf("relevant"), hits.map { it.memory.id })
    }

    @Test
    fun recentEpisodeWithNoOverlapNoLongerPassesOnPriorsAlone() = runTest {
        // The concrete regression. A typical episode's priors — importance 0.45,
        // confidence 0.72, full recency — summed to ~0.104, above the old 0.08
        // floor, so every recent memory qualified regardless of the question.
        val episode = memoryRow(
            "recent-episode",
            "26",
            importance = 0.45f,
            confidence = 0.72f,
            updatedAt = NOW,
        )
        val source = FakeMemorySearchSource(listOf(episode))

        val hits = searchMemoryRows(
            source = source,
            queryText = "explain gravity briefly",
            includePrivate = true,
            limit = 8,
            indexedIds = emptyList(),
            now = NOW,
        )

        assertTrue("recent-but-irrelevant episode still injected", hits.isEmpty())
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

/**
 * A unit vector along [axis], optionally rotated so its cosine against the pure
 * axis vector equals [similarity].
 */
private fun unitVector(axis: Int, similarity: Float = 1f, dimensions: Int = 8): FloatArray {
    val values = FloatArray(dimensions)
    values[axis] = similarity
    val remainder = kotlin.math.sqrt((1f - similarity * similarity).coerceAtLeast(0f))
    values[(axis + 1) % dimensions] = remainder
    return values
}
