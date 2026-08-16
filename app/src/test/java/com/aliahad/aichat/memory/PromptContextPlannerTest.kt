package com.aliahad.aichat.memory

import androidx.room.InvalidationTracker
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.GenerationEvent
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.InferenceBenchmarkSample
import com.aliahad.aichat.core.InferenceExecutionProfile
import com.aliahad.aichat.core.InferenceMetrics
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.MemoryHit
import com.aliahad.aichat.core.MemoryItem
import com.aliahad.aichat.core.MemoryQuery
import com.aliahad.aichat.core.MemorySensitivity
import com.aliahad.aichat.core.MemorySource
import com.aliahad.aichat.core.MemorySourceKind
import com.aliahad.aichat.core.MemoryStatus
import com.aliahad.aichat.core.MemoryType
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.core.ModelCapabilities
import com.aliahad.aichat.core.ModelLoadConfiguration
import com.aliahad.aichat.core.UserTurn
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.data.AttachmentDao
import com.aliahad.aichat.data.BackupImportInvalidationDao
import com.aliahad.aichat.data.ConversationDao
import com.aliahad.aichat.data.ConversationSummaryDao
import com.aliahad.aichat.data.ConversationSummaryEntity
import com.aliahad.aichat.data.MemoryDao
import com.aliahad.aichat.data.MessageDao
import com.aliahad.aichat.data.ModelBenchmarkDao
import com.aliahad.aichat.data.ModelContextProfileDao
import com.aliahad.aichat.data.ModelDao
import com.aliahad.aichat.data.ProjectorDao
import com.aliahad.aichat.data.SkillDao
import com.aliahad.aichat.inference.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Characterization tests for [PromptContextPlanner]. Expected values pin the current
 * greedy newest-first fit loop, memory/summary selection, and the final budget check.
 * Token counting is deterministic: text.length / 4.
 */
class PromptContextPlannerTest {
    private val settings = GenerationSettings(
        maxNewTokens = 1024,
        systemPrompt = "systemprompt",
    )

    @Test
    fun everythingFitsKeepsAllHistoryInOriginalOrder() = runTest {
        val engine = FakeInferenceEngine()
        val planner = PromptContextPlanner(engine, FakeMemoryRepository(), summariesDatabase())
        val history = listOf(
            turn("m1", MessageRole.USER, "a".repeat(400)),
            turn("m2", MessageRole.ASSISTANT, "b".repeat(200)),
        )

        val plan = planner.plan(
            conversationId = "chat",
            history = history,
            currentText = "hello",
            settings = settings,
            contextTokens = 6_000,
            memoryEnabled = false,
        )

        assertEquals(history, plan.history)
        assertEquals("systemprompt", plan.systemPrompt)
        assertNull(plan.summary)
        assertTrue(plan.memories.isEmpty())
    }

    @Test
    fun oversizedOldTurnIsDroppedAndSummaryIsUpdated() = runTest {
        val engine = FakeInferenceEngine()
        val dao = FakeConversationSummaryDao()
        val summaries = ConversationSummaryRepository(FakeAppDatabase(dao))
        val planner = PromptContextPlanner(engine, FakeMemoryRepository(), summaries)
        val oversized = turn("old", MessageRole.USER, "x".repeat(20_000))
        val newest = turn("new", MessageRole.ASSISTANT, "y".repeat(40))

        val plan = planner.plan(
            conversationId = "chat",
            history = listOf(oversized, newest),
            currentText = "hello",
            settings = settings,
            contextTokens = 6_000,
            memoryEnabled = false,
        )

        assertEquals(listOf(newest), plan.history)
        assertNotNull(plan.summary)
        assertTrue(plan.summary!!.content.contains("User:"))
        assertTrue(plan.turnPreamble.contains("Conversation summary:"))
        assertEquals(1, dao.upserted.size)
    }

    @Test
    fun budgetPressurePrefersNewestTurnsOverOlderOnes() = runTest {
        val engine = FakeInferenceEngine()
        val planner = PromptContextPlanner(engine, FakeMemoryRepository(), summariesDatabase())
        val older = turn("older", MessageRole.USER, "o".repeat(12_000))
        val newest = turn("newest", MessageRole.ASSISTANT, "n".repeat(8_000))

        val plan = planner.plan(
            conversationId = "chat",
            history = listOf(older, newest),
            currentText = "hello",
            settings = settings,
            contextTokens = 6_000,
            memoryEnabled = false,
        )

        assertEquals(listOf(newest), plan.history)
    }

    @Test
    fun memoriesIncludedOnlyWhenMemoryEnabled() = runTest {
        val repository = FakeMemoryRepository(hits = listOf(memoryHit()))

        val withMemory = PromptContextPlanner(
            FakeInferenceEngine(),
            repository,
            summariesDatabase(),
        ).plan(
            conversationId = "chat",
            history = emptyList(),
            currentText = "hello",
            settings = settings,
            contextTokens = 6_000,
            memoryEnabled = true,
        )

        assertEquals(1, withMemory.memories.size)
        assertTrue(withMemory.turnPreamble.contains("Personal Office Memory follows"))
        assertTrue(withMemory.turnPreamble.contains("remembered fact"))
        assertEquals(1, repository.searchCalls)

        val repository2 = FakeMemoryRepository(hits = listOf(memoryHit()))
        val withoutMemory = PromptContextPlanner(
            FakeInferenceEngine(),
            repository2,
            summariesDatabase(),
        ).plan(
            conversationId = "chat",
            history = emptyList(),
            currentText = "hello",
            settings = settings,
            contextTokens = 6_000,
            memoryEnabled = false,
        )

        assertTrue(withoutMemory.memories.isEmpty())
        assertFalse(withoutMemory.turnPreamble.contains("Personal Office Memory follows"))
        assertEquals(0, repository2.searchCalls)
    }

    @Test
    fun memoryQueryExpandsWithRecentTurnsWhilePromptUsesCurrentTextOnly() = runTest {
        val repository = FakeMemoryRepository()
        val planner = PromptContextPlanner(FakeInferenceEngine(), repository, summariesDatabase())
        val history = listOf(
            turn("m1", MessageRole.USER, "oldest turn MARKER-OLD ".repeat(20)),
            turn("m2", MessageRole.ASSISTANT, "previous turn MARKER-PREVIOUS ".repeat(20)),
            turn("m3", MessageRole.USER, "latest turn MARKER-LATEST ".repeat(20)),
        )

        val plan = planner.plan(
            conversationId = "chat",
            history = history,
            currentText = "what about tea",
            settings = settings,
            contextTokens = 60_000,
            memoryEnabled = true,
        )

        assertEquals(1, repository.queries.size)
        val query = repository.queries.single()
        assertEquals(16, query.limit)
        assertEquals(
            "query text must be exactly the current message",
            "what about tea",
            query.text,
        )
        assertTrue("expansion should include the latest turn", query.expansion.contains("MARKER-LATEST"))
        assertTrue("expansion should include the previous turn", query.expansion.contains("MARKER-PREVIOUS"))
        assertFalse("expansion must not reach past the last two turns", query.expansion.contains("MARKER-OLD"))
        // Expansion must not leak into prompt assembly: no memories selected,
        // so the system prompt is exactly the configured base prompt.
        assertEquals("systemprompt", plan.systemPrompt)
    }

    @Test
    fun memoryQueryExpansionAppendsTrimmedRecentTurnsOnly() {
        val history = listOf(
            turn("m1", MessageRole.USER, "first turn text ".repeat(40)),
            turn("m2", MessageRole.ASSISTANT, "b".repeat(500)),
            turn("m3", MessageRole.USER, "c".repeat(500)),
        )

        val expansion = memoryQueryExpansion(history)

        assertTrue(expansion.startsWith("b".repeat(200)))
        assertTrue(expansion.contains("c".repeat(200)))
        assertFalse("each appended turn must be capped at 200 chars", expansion.contains("b".repeat(201)))
        assertFalse("only the last two turns may expand the query", expansion.contains("first turn"))
        assertEquals(200 + 1 + 200, expansion.length)
    }

    @Test
    fun memoryQueryExpansionSkipsBlankTurnsAndEmptyHistory() {
        assertEquals(
            "",
            memoryQueryExpansion(listOf(turn("m1", MessageRole.USER, "   "))),
        )
        assertEquals("", memoryQueryExpansion(emptyList()))
    }

    @Test
    fun summaryBlockIncludedWhenItFitsAndExcludedWhenTooLarge() = runTest {
        val fitting = summariesDatabase(seedContent = "tiny")
        val fittingPlan = PromptContextPlanner(
            FakeInferenceEngine(),
            FakeMemoryRepository(),
            fitting,
        ).plan(
            conversationId = "chat",
            history = emptyList(),
            currentText = "hello",
            settings = settings,
            contextTokens = 6_000,
            memoryEnabled = false,
        )

        assertNotNull(fittingPlan.summary)
        assertTrue(fittingPlan.turnPreamble.contains("Conversation summary:\ntiny"))

        val oversized = summariesDatabase(seedContent = "s".repeat(40_000))
        val oversizedPlan = PromptContextPlanner(
            FakeInferenceEngine(),
            FakeMemoryRepository(),
            oversized,
        ).plan(
            conversationId = "chat",
            history = emptyList(),
            currentText = "hello",
            settings = settings,
            contextTokens = 6_000,
            memoryEnabled = false,
        )

        assertNull(oversizedPlan.summary)
        assertFalse(oversizedPlan.turnPreamble.contains("Conversation summary"))
    }

    @Test
    fun oversizedSystemPromptFailsFastBeforePlanning() = runTest {
        val planner = PromptContextPlanner(
            FakeInferenceEngine(),
            FakeMemoryRepository(),
            summariesDatabase(),
        )

        try {
            planner.plan(
                conversationId = "chat",
                history = emptyList(),
                currentText = "hello",
                settings = settings.copy(systemPrompt = "s".repeat(40_000)),
                contextTokens = 6_000,
                memoryEnabled = false,
            )
            fail("Expected IllegalArgumentException from the context budget check")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does not fit the selected context"))
        }
    }

    @Test
    fun assembledSystemPromptIsNotRetokenized() = runTest {
        // Budget accounting sums the per-block token counts; the assembled system
        // prompt is never re-tokenized (previously a second full tokenize of a
        // multi-KB string on the pre-inference critical path).
        val engine = FakeInferenceEngine(secondCallMultiplier = 10)
        val planner = PromptContextPlanner(engine, FakeMemoryRepository(), summariesDatabase())

        val plan = planner.plan(
            conversationId = "chat",
            history = emptyList(),
            currentText = "hello",
            settings = settings.copy(systemPrompt = "SYSMARKER " + "s".repeat(200)),
            contextTokens = 6_000,
            memoryEnabled = false,
        )

        assertEquals(1, engine.tokenCalls.count { it.contains("SYSMARKER") })
        assertTrue(plan.estimatedTokens + plan.outputReserveTokens <= 6_000)
    }

    @Test
    fun fittedPlanRespectsContextBudgetIncludingOutputReserve() = runTest {
        val contextTokens = 6_000
        val planner = PromptContextPlanner(
            FakeInferenceEngine(),
            FakeMemoryRepository(),
            summariesDatabase(),
        )
        val plan = planner.plan(
            conversationId = "chat",
            history = listOf(
                turn("m1", MessageRole.USER, "a".repeat(400)),
                turn("m2", MessageRole.ASSISTANT, "b".repeat(200)),
            ),
            currentText = "hello",
            settings = settings,
            contextTokens = contextTokens,
            memoryEnabled = false,
        )

        assertEquals(1_024, plan.outputReserveTokens)
        assertTrue(plan.estimatedTokens + plan.outputReserveTokens <= contextTokens)
    }

    @Test
    fun selectionLoopCountsEachHistoryTurnExactlyOnce() = runTest {
        val engine = FakeInferenceEngine()
        val planner = PromptContextPlanner(engine, FakeMemoryRepository(), summariesDatabase())
        val history = listOf(
            turn("m1", MessageRole.USER, "zebra MARKER-ONE quokka ".repeat(20)),
            turn("m2", MessageRole.ASSISTANT, "panda MARKER-TWO otter ".repeat(10)),
            turn("m3", MessageRole.USER, "koala MARKER-THREE lynx ".repeat(10)),
        )

        planner.plan(
            conversationId = "chat",
            history = history,
            currentText = "hello",
            settings = settings,
            contextTokens = 60_000,
            memoryEnabled = false,
        )

        assertEquals(1, engine.tokenCalls.count { it.contains("MARKER-ONE") })
        assertEquals(1, engine.tokenCalls.count { it.contains("MARKER-TWO") })
        assertEquals(1, engine.tokenCalls.count { it.contains("MARKER-THREE") })
    }

    private fun turn(id: String, role: MessageRole, content: String) = ChatTurn(
        message = ChatMessage(
            id = id,
            conversationId = "chat",
            role = role,
            content = content,
            createdAt = 0L,
            status = MessageStatus.COMPLETE,
        ),
    )

    private fun memoryHit() = MemoryHit(
        memory = MemoryItem(
            id = "mem1",
            type = MemoryType.FACT,
            title = "Fact",
            content = "remembered fact",
            confidence = 1f,
            importance = 0.7f,
            sensitivity = MemorySensitivity.NORMAL,
            status = MemoryStatus.ACTIVE,
            pinned = false,
            validFrom = null,
            validTo = null,
            supersedesId = null,
            createdAt = 0L,
            updatedAt = 0L,
        ),
        score = 0.9f,
        sources = listOf(
            MemorySource(
                id = "src1",
                memoryId = "mem1",
                kind = MemorySourceKind.CHAT_MESSAGE,
                sourceId = "m1",
                label = "Chat",
                createdAt = 0L,
            ),
        ),
        reason = "match",
    )

    private fun summariesDatabase(seedContent: String? = null): ConversationSummaryRepository {
        val dao = FakeConversationSummaryDao()
        if (seedContent != null) {
            dao.upserted += ConversationSummaryEntity(
                conversationId = "chat",
                throughMessageId = null,
                content = seedContent,
                tokenCount = seedContent.length / 4,
                updatedAt = 0L,
            )
        }
        return ConversationSummaryRepository(FakeAppDatabase(dao))
    }
}

private class FakeInferenceEngine(
    private val secondCallMultiplier: Int? = null,
) : InferenceEngine {
    private val seen = mutableSetOf<String>()
    val tokenCalls = mutableListOf<String>()
    override val state: StateFlow<InferenceState> = MutableStateFlow(InferenceState.Idle)
    override val metrics: StateFlow<InferenceMetrics> = MutableStateFlow(InferenceMetrics())
    override val loadedModelPath: String? = null
    override val loadedProjectorPath: String? = null
    override val loadedCapabilities: ModelCapabilities? = null
    override val modelContextLimit: Int = 128_000
    override val activeContextSize: Int = 0

    override suspend fun countTokens(text: String): Int {
        tokenCalls += text
        val base = text.length / 4
        val multiplier = secondCallMultiplier
        return if (multiplier != null && !seen.add(text)) {
            base * multiplier
        } else {
            base
        }
    }

    override suspend fun loadModel(
        path: String,
        displayName: String,
        configuration: ModelLoadConfiguration,
    ): Unit = error("unused")

    override suspend fun loadProjector(path: String, imageTokenBudget: Int): ModelCapabilities =
        error("unused")

    override suspend fun unloadProjector(): Unit = error("unused")

    override suspend fun restoreSession(
        conversationId: String,
        history: List<ChatTurn>,
        settings: GenerationSettings,
    ): Unit = error("unused")

    override fun generate(
        turn: UserTurn,
        settings: GenerationSettings,
        profile: InferenceExecutionProfile,
    ): Flow<GenerationEvent> = error("unused")

    override suspend fun verifyLoadedContext(): Int = error("unused")

    override fun cancel(): Unit = error("unused")

    override fun releaseResidentPages(): Unit = error("unused")

    override suspend fun unload(): Unit = error("unused")

    override suspend fun benchmark(
        path: String,
        displayName: String,
        settings: GenerationSettings,
    ): Map<com.aliahad.aichat.core.BackendMode, InferenceBenchmarkSample> = error("unused")

    override fun systemInfo(): String = error("unused")

    override fun destroy(): Unit = error("unused")
}

private class FakeMemoryRepository(
    private val hits: List<MemoryHit> = emptyList(),
) : MemoryRepository {
    var searchCalls = 0
    val queries = mutableListOf<MemoryQuery>()

    override val memories: Flow<List<MemoryItem>> = MutableStateFlow(emptyList())

    override val memorySources: Flow<Map<String, List<com.aliahad.aichat.core.MemorySource>>> =
        MutableStateFlow<Map<String, List<com.aliahad.aichat.core.MemorySource>>>(emptyMap())

    override suspend fun search(query: MemoryQuery): List<MemoryHit> {
        searchCalls++
        queries += query
        return hits
    }

    override suspend fun rememberMessage(
        message: ChatMessage,
        conversationTemporary: Boolean,
    ): Unit = error("unused")

    override suspend fun rememberAttachment(
        attachmentId: String,
        displayName: String,
        content: String,
    ): Unit = error("unused")

    override suspend fun remember(
        type: MemoryType,
        title: String,
        content: String,
        importance: Float,
        sensitivity: MemorySensitivity,
    ): MemoryItem = error("unused")

    override suspend fun correct(id: String, content: String, reason: String?): MemoryItem =
        error("unused")

    override suspend fun setPinned(id: String, pinned: Boolean): Unit = error("unused")

    override suspend fun forget(id: String): Unit = error("unused")


    override suspend fun purgeStaleIndexDocs(): Unit = error("unused")

    override suspend fun purgeExpiredMemories(now: Long): Int = error("unused")
}

private class FakeConversationSummaryDao : ConversationSummaryDao {
    val upserted = mutableListOf<ConversationSummaryEntity>()

    override suspend fun get(conversationId: String): ConversationSummaryEntity? =
        upserted.lastOrNull { it.conversationId == conversationId }

    override suspend fun all(): List<ConversationSummaryEntity> = upserted.toList()

    override suspend fun upsert(summary: ConversationSummaryEntity) {
        upserted.removeAll { it.conversationId == summary.conversationId }
        upserted += summary
    }
}

private class FakeAppDatabase(
    private val summaryDao: ConversationSummaryDao,
) : AppDatabase() {
    override fun conversationDao(): ConversationDao = error("unused")
    override fun messageDao(): MessageDao = error("unused")
    override fun skillDao(): SkillDao = error("unused")
    override fun modelDao(): ModelDao = error("unused")
    override fun modelContextProfileDao(): ModelContextProfileDao = error("unused")
    override fun projectorDao(): ProjectorDao = error("unused")
    override fun attachmentDao(): AttachmentDao = error("unused")
    override fun conversationSummaryDao(): ConversationSummaryDao = summaryDao
    override fun memoryDao(): MemoryDao = error("unused")
    override fun modelBenchmarkDao(): ModelBenchmarkDao = error("unused")
    override fun backupImportInvalidationDao(): BackupImportInvalidationDao = error("unused")

    override fun createInvalidationTracker(): InvalidationTracker = error("unused")

    override fun clearAllTables(): Unit = error("unused")
}
