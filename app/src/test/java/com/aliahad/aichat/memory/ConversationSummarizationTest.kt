package com.aliahad.aichat.memory

import androidx.room.InvalidationTracker
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.GenerationEvent
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.GenerationStopReason
import com.aliahad.aichat.core.InferenceBenchmarkSample
import com.aliahad.aichat.core.InferenceExecutionProfile
import com.aliahad.aichat.core.InferenceMetrics
import com.aliahad.aichat.core.InferenceState
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
import com.aliahad.aichat.residency.ModelResidencyState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the rolling LLM summarization path: prompt assembly token caps, the
 * success path with defensive output truncation, and the synchronous fallback
 * guarantees when the engine is unavailable, fails, or returns blank output.
 * Token counting is deterministic: text.length / 4.
 */
class ConversationSummarizationTest {
    private val tokenCount: suspend (String) -> Int = { text -> text.length / 4 }

    @Test
    fun summarizationInputIncludesInstructionExistingSummaryAndHistory() = runTest {
        val input = summarizationInput(
            existingSummary = "User prefers tea.",
            trimmedMessages = listOf(
                message("m1", MessageRole.USER, "I moved to Lisbon"),
                message("m2", MessageRole.ASSISTANT, "Noted about Lisbon"),
            ),
            tokenCount = tokenCount,
        )

        assertTrue(input.startsWith(SUMMARY_EXTRACTION_INSTRUCTION))
        assertTrue(input.contains("Existing summary:\nUser prefers tea."))
        assertTrue(input.contains("User: I moved to Lisbon"))
        assertTrue(input.contains("Assistant: Noted about Lisbon"))
        assertTrue(
            "history must stay chronological",
            input.indexOf("I moved to Lisbon") < input.indexOf("Noted about Lisbon"),
        )
    }

    @Test
    fun summarizationInputCapsTokensPreferringNewestMessages() = runTest {
        // 4 tokens per 16 chars; each message line costs ~200 tokens, so only the
        // newest few fit the budget after instruction + existing summary.
        val messages = (1..10).map { index ->
            message("m$index", MessageRole.USER, "text$index ".repeat(40))
        }

        val input = summarizationInput(
            existingSummary = null,
            trimmedMessages = messages,
            tokenCount = tokenCount,
            maxTokens = 500,
        )

        assertTrue(input.contains("text10"))
        assertFalse("oldest messages must be dropped first", input.contains("text1 "))
        assertTrue(tokenCount(input) <= 500)
    }

    @Test
    fun summarizationInputCapsIndividualMessageLength() = runTest {
        val input = summarizationInput(
            existingSummary = null,
            trimmedMessages = listOf(message("m1", MessageRole.USER, "x".repeat(5_000))),
            tokenCount = tokenCount,
        )

        assertTrue(input.contains("x".repeat(MAX_SUMMARY_MESSAGE_CHARS)))
        assertFalse(input.contains("x".repeat(MAX_SUMMARY_MESSAGE_CHARS + 1)))
    }

    @Test
    fun summarizationInputWithoutMaterialIsEmpty() = runTest {
        val input = summarizationInput(
            existingSummary = "   ",
            trimmedMessages = emptyList(),
            tokenCount = tokenCount,
        )

        assertEquals(SUMMARY_EXTRACTION_INSTRUCTION, input)
    }

    @Test
    fun summarizeFromStoresGeneratedProfile() = runTest {
        val dao = InMemorySummaryDao()
        val repository = ConversationSummaryRepository(SummaryTestDatabase(dao))

        val result = repository.summarizeFrom(
            conversationId = "chat",
            existingSummary = "old profile",
            trimmedMessages = listOf(
                message("m1", MessageRole.USER, "hello"),
                message("m2", MessageRole.ASSISTANT, "hi"),
            ),
            tokenCount = tokenCount,
            generator = { "User lives in Lisbon and prefers tea." },
        )

        assertEquals("User lives in Lisbon and prefers tea.", result?.content)
        assertEquals("m2", result?.throughMessageId)
        assertEquals(dao.get("chat")?.content, result?.content)
    }

    @Test
    fun summarizeFromTruncatesOversizedOutputDefensively() = runTest {
        val dao = InMemorySummaryDao()
        val repository = ConversationSummaryRepository(SummaryTestDatabase(dao))

        val result = repository.summarizeFrom(
            conversationId = "chat",
            existingSummary = null,
            trimmedMessages = listOf(message("m1", MessageRole.USER, "hello")),
            tokenCount = tokenCount,
            generator = { "z".repeat(10_000) },
        )

        assertEquals(MAX_SUMMARY_OUTPUT_CHARS, result?.content?.length)
    }

    @Test
    fun summarizeFromFallsBackToTruncationWhenGeneratorReturnsNull() = runTest {
        val dao = InMemorySummaryDao().apply {
            upserted += entity(content = "PRIOR CONTENT")
        }
        val repository = ConversationSummaryRepository(SummaryTestDatabase(dao))

        val result = repository.summarizeFrom(
            conversationId = "chat",
            existingSummary = "PRIOR CONTENT",
            trimmedMessages = listOf(message("m1", MessageRole.USER, "fresh trimmed turn")),
            tokenCount = tokenCount,
            generator = { null },
        )

        assertTrue(result!!.content.contains("PRIOR CONTENT"))
        assertTrue(result.content.contains("User: fresh trimmed turn"))
    }

    @Test
    fun summarizeFromFallsBackWhenGeneratorReturnsBlank() = runTest {
        val repository = ConversationSummaryRepository(SummaryTestDatabase(InMemorySummaryDao()))

        val result = repository.summarizeFrom(
            conversationId = "chat",
            existingSummary = null,
            trimmedMessages = listOf(message("m1", MessageRole.USER, "some trimmed text")),
            tokenCount = tokenCount,
            generator = { "   " },
        )

        assertTrue(result!!.content.contains("User: some trimmed text"))
    }

    @Test
    fun summarizeFromFallsBackWhenGeneratorThrows() = runTest {
        val repository = ConversationSummaryRepository(SummaryTestDatabase(InMemorySummaryDao()))

        val result = repository.summarizeFrom(
            conversationId = "chat",
            existingSummary = null,
            trimmedMessages = listOf(message("m1", MessageRole.ASSISTANT, "kept turn")),
            tokenCount = tokenCount,
            generator = { error("engine exploded") },
        )

        assertTrue(result!!.content.contains("Assistant: kept turn"))
    }

    @Test
    fun summarizeFromWithoutMaterialReturnsExistingSummary() = runTest {
        val dao = InMemorySummaryDao().apply {
            upserted += entity(content = "existing")
        }
        val repository = ConversationSummaryRepository(SummaryTestDatabase(dao))

        val result = repository.summarizeFrom(
            conversationId = "chat",
            existingSummary = null,
            trimmedMessages = emptyList(),
            tokenCount = tokenCount,
            generator = { error("must not be called for empty input") },
        )

        assertEquals("existing", result?.content)
        assertEquals("existing", dao.get("chat")?.content)
    }

    @Test
    fun dueForLlmRefreshReturnsOnlySummariesPastThresholdLongestFirst() = runTest {
        val dao = InMemorySummaryDao().apply {
            upserted += entity(conversationId = "short", content = "s".repeat(100))
            upserted += entity(conversationId = "long", content = "l".repeat(6_000))
            upserted += entity(conversationId = "longer", content = "m".repeat(9_000))
        }
        val repository = ConversationSummaryRepository(SummaryTestDatabase(dao))

        val due = repository.dueForLlmRefresh(limit = 5)

        assertEquals(listOf("longer", "long"), due.map { it.conversationId })
        assertEquals(listOf("longer"), repository.dueForLlmRefresh(limit = 1).map { it.conversationId })
    }

    @Test
    fun distilledOutputBelowThresholdIsNotImmediatelyDueAgain() = runTest {
        val dao = InMemorySummaryDao()
        val repository = ConversationSummaryRepository(SummaryTestDatabase(dao))

        repository.summarizeFrom(
            conversationId = "chat",
            existingSummary = null,
            trimmedMessages = listOf(message("m1", MessageRole.USER, "hello")),
            tokenCount = tokenCount,
            generator = { "w".repeat(MAX_SUMMARY_OUTPUT_CHARS) },
        )

        assertTrue(repository.dueForLlmRefresh(limit = 5).isEmpty())
    }

    @Test
    fun summarizerUnavailableEngineReturnsNullWithoutGeneration() = runTest {
        val engine = ScriptedSummarizationEngine(
            initialState = InferenceState.Idle,
            modelPath = null,
        )
        val summarizer = BackgroundConversationSummarizer(
            engine,
            MutableStateFlow(residencyReady()),
        )

        assertNull(summarizer.generateText("prompt"))
        assertEquals(0, engine.restoreCalls)
        assertEquals(0, engine.generateCalls)
    }

    @Test
    fun summarizerUnavailableResidencyReturnsNullWithoutGeneration() = runTest {
        val engine = ScriptedSummarizationEngine(
            initialState = InferenceState.Ready("model", BackendMode.CPU),
            modelPath = "/model.gguf",
        )
        val summarizer = BackgroundConversationSummarizer(
            engine,
            MutableStateFlow(ModelResidencyState.Idle),
        )

        assertNull(summarizer.generateText("prompt"))
        assertEquals(0, engine.restoreCalls)
    }

    @Test
    fun summarizerGeneratesWithUtilityProfileAndThinkingDisabled() = runTest {
        val engine = ScriptedSummarizationEngine(
            initialState = InferenceState.Ready("model", BackendMode.CPU),
            modelPath = "/model.gguf",
            events = listOf(
                GenerationEvent.AnswerDelta("User "),
                GenerationEvent.AnswerDelta("likes tea."),
                GenerationEvent.Completed(GenerationStopReason.EOG, 12, 0),
            ),
        )
        val summarizer = BackgroundConversationSummarizer(
            engine,
            MutableStateFlow(residencyReady()),
        )

        assertEquals("User likes tea.", summarizer.generateText("prompt"))
        assertEquals(1, engine.restoreCalls)
        assertEquals(BackgroundConversationSummarizer.UTILITY_CONVERSATION_ID, engine.restoredConversationId)
        assertTrue(engine.restoredHistory!!.isEmpty())
        assertFalse(engine.restoredSettings!!.thinkingEnabled)
        assertEquals(InferenceExecutionProfile.UTILITY, engine.profile)
        assertFalse(engine.generatedSettings!!.thinkingEnabled)
    }

    @Test
    fun summarizerReturnsNullWhenGenerationEndsInError() = runTest {
        val engine = ScriptedSummarizationEngine(
            initialState = InferenceState.Ready("model", BackendMode.CPU),
            modelPath = "/model.gguf",
            events = listOf(
                GenerationEvent.AnswerDelta("partial"),
                GenerationEvent.Completed(GenerationStopReason.DECODE_ERROR, 1, 0),
            ),
        )
        val summarizer = BackgroundConversationSummarizer(
            engine,
            MutableStateFlow(residencyReady()),
        )

        assertNull(summarizer.generateText("prompt"))
    }

    @Test
    fun summarizerReturnsNullWhenEngineThrows() = runTest {
        val engine = ScriptedSummarizationEngine(
            initialState = InferenceState.Ready("model", BackendMode.CPU),
            modelPath = "/model.gguf",
            restoreError = IllegalStateException("restore failed"),
        )
        val summarizer = BackgroundConversationSummarizer(
            engine,
            MutableStateFlow(residencyReady()),
        )

        assertNull(summarizer.generateText("prompt"))
        assertFalse(summarizer.isActive)
    }

    private fun residencyReady() = ModelResidencyState.Ready(
        modelName = "model",
        contextSize = 4_096,
        declaredContextSize = 4_096,
        loadMillis = 1L,
    )

    private fun message(id: String, role: MessageRole, content: String) = ChatMessage(
        id = id,
        conversationId = "chat",
        role = role,
        content = content,
        createdAt = 0L,
        status = MessageStatus.COMPLETE,
    )

    private fun entity(
        conversationId: String = "chat",
        content: String,
    ) = ConversationSummaryEntity(
        conversationId = conversationId,
        throughMessageId = null,
        content = content,
        tokenCount = content.length / 4,
        updatedAt = 0L,
    )
}

private class ScriptedSummarizationEngine(
    private val initialState: InferenceState,
    private val modelPath: String?,
    private val events: List<GenerationEvent> = emptyList(),
    private val restoreError: Throwable? = null,
) : InferenceEngine {
    var restoreCalls = 0
    var generateCalls = 0
    var restoredConversationId: String? = null
    var restoredHistory: List<ChatTurn>? = null
    var restoredSettings: GenerationSettings? = null
    var generatedSettings: GenerationSettings? = null
    var profile: InferenceExecutionProfile? = null

    override val state: StateFlow<InferenceState> = MutableStateFlow(initialState)
    override val metrics: StateFlow<InferenceMetrics> = MutableStateFlow(InferenceMetrics())
    override val loadedModelPath: String? = modelPath
    override val loadedProjectorPath: String? = null
    override val loadedCapabilities: ModelCapabilities? = null
    override val modelContextLimit: Int = 8_192
    override val activeContextSize: Int = 0

    override suspend fun persistSession(
        conversationId: String,
        settings: GenerationSettings,
        history: List<ChatTurn>,
    ) = Unit

    override suspend fun restoreSession(
        conversationId: String,
        history: List<ChatTurn>,
        settings: GenerationSettings,
    ) {
        restoreCalls++
        restoreError?.let { throw it }
        restoredConversationId = conversationId
        restoredHistory = history
        restoredSettings = settings
    }

    override fun generate(
        turn: UserTurn,
        settings: GenerationSettings,
        profile: InferenceExecutionProfile,
    ): Flow<GenerationEvent> {
        generateCalls++
        generatedSettings = settings
        this.profile = profile
        return flowOf(*events.toTypedArray())
    }

    override suspend fun countTokens(text: String): Int = text.length / 4

    override suspend fun loadModel(
        path: String,
        displayName: String,
        configuration: ModelLoadConfiguration,
    ): Unit = error("unused")

    override suspend fun loadProjector(path: String, imageTokenBudget: Int): ModelCapabilities =
        error("unused")

    override suspend fun unloadProjector(): Unit = error("unused")

    override suspend fun verifyLoadedContext(): Int = error("unused")

    override fun cancel(): Unit = error("unused")

    override fun releaseResidentPages(): Unit = error("unused")
    override suspend fun loadEmbedder(path: String): Unit = error("unused")
    override suspend fun unloadEmbedder(): Unit = error("unused")
    override val embeddingDimensions: Int = 0
    override suspend fun embed(text: String): FloatArray? = null

    override suspend fun unload(): Unit = error("unused")

    override suspend fun benchmark(
        path: String,
        displayName: String,
        settings: GenerationSettings,
    ): Map<BackendMode, InferenceBenchmarkSample> = error("unused")

    override fun systemInfo(): String = error("unused")

    override fun destroy(): Unit = error("unused")
}

private class InMemorySummaryDao : ConversationSummaryDao {
    val upserted = mutableListOf<ConversationSummaryEntity>()

    override suspend fun get(conversationId: String): ConversationSummaryEntity? =
        upserted.lastOrNull { it.conversationId == conversationId }

    override suspend fun all(): List<ConversationSummaryEntity> = upserted.toList()

    override suspend fun upsert(summary: ConversationSummaryEntity) {
        upserted.removeAll { it.conversationId == summary.conversationId }
        upserted += summary
    }
}

private class SummaryTestDatabase(
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
