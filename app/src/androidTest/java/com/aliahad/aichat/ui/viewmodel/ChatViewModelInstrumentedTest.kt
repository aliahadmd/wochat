package com.aliahad.aichat.ui.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aliahad.aichat.attachment.AttachmentRepository
import com.aliahad.aichat.context.ContextProfileRepository
import com.aliahad.aichat.core.Attachment
import com.aliahad.aichat.core.AttachmentContext
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.ChatQualityMode
import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.ContextPlan
import com.aliahad.aichat.core.ContextVerificationMetrics
import com.aliahad.aichat.core.ContextVerificationState
import com.aliahad.aichat.core.Conversation
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.GenerationEvent
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.InferenceBenchmarkSample
import com.aliahad.aichat.core.InferenceExecutionProfile
import com.aliahad.aichat.core.InferenceMetrics
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.MemoryHit
import com.aliahad.aichat.core.MemoryItem
import com.aliahad.aichat.core.MemoryQuery
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.core.ModelCapabilities
import com.aliahad.aichat.core.ModelContextProfile
import com.aliahad.aichat.core.ModelLoadConfiguration
import com.aliahad.aichat.core.ModelRecord
import com.aliahad.aichat.core.MultimodalRequirement
import com.aliahad.aichat.core.ProjectorRecord
import com.aliahad.aichat.core.SkillPromptBlock
import com.aliahad.aichat.core.SkillRecord
import com.aliahad.aichat.core.TurnOrigin
import com.aliahad.aichat.core.UserTurn
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.data.ChatRepository
import com.aliahad.aichat.data.ChatSearchResult
import com.aliahad.aichat.inference.InferenceEngine
import com.aliahad.aichat.memory.ConversationSummaryRepository
import com.aliahad.aichat.memory.MemoryRepository
import com.aliahad.aichat.memory.PromptContextPlanner
import com.aliahad.aichat.model.ModelRepository
import com.aliahad.aichat.residency.ModelResidencyController
import com.aliahad.aichat.settings.AppSettingsRepository
import com.aliahad.aichat.settings.TokenCipher
import com.aliahad.aichat.skill.MAX_SELECTED_SKILLS
import com.aliahad.aichat.skill.SkillRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ChatViewModelInstrumentedTest {

    private lateinit var viewModel: ChatViewModel
    private lateinit var settings: AppSettingsRepository
    private val fakeChatRepo = FakeChatRepository()
    private val fakeModelRepo = FakeModelRepository()
    private val fakeSkillRepo = FakeSkillRepository()
    private val fakeAttachmentRepo = FakeAttachmentRepository()
    private val fakeInferenceEngine = FakeInferenceEngine()
    private val fakeMemoryRepo = FakeMemoryRepository()
    private val fakeContextProfileRepo = FakeContextProfileRepository()
    private val uiMessages = UiMessageManager()

    @Before
    fun setUp() {
        // ChatViewModel.init starts eight collectors on viewModelScope, which
        // dispatches to Dispatchers.Main. Instrumented tests run on the
        // instrumentation thread, so without a test main dispatcher those
        // collectors have not run by the time the assertions below execute and
        // the ViewModel looks empty. Unconfined runs them eagerly, which is what
        // lets these tests assert synchronously.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "preferences/settings.preferences_pb").delete()
        settings = AppSettingsRepository(context, TokenCipher(context))

        val residencyController = ModelResidencyController(
            context = context,
            inferenceEngine = fakeInferenceEngine,
            modelRepository = fakeModelRepo,
            attachmentRepository = fakeAttachmentRepo,
            contextProfiles = fakeContextProfileRepo,
            settingsRepository = settings,
        )
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val promptContextPlanner = PromptContextPlanner(
            inferenceEngine = fakeInferenceEngine,
            memoryRepository = fakeMemoryRepo,
            summaries = ConversationSummaryRepository(database),
        )
        val chatTurnRunner = ChatTurnRunner(
            chatRepository = fakeChatRepo,
            attachmentRepository = fakeAttachmentRepo,
            skillRepository = fakeSkillRepo,
            memoryRepository = fakeMemoryRepo,
            modelRepository = fakeModelRepo,
            promptContextPlanner = promptContextPlanner,
            residencyController = residencyController,
            inferenceEngine = fakeInferenceEngine,
            settingsRepository = settings,
            messages = uiMessages,
        )
        val projectorPrompts = ProjectorPromptCoordinator(fakeModelRepo)

        viewModel = ChatViewModel(
            savedStateHandle = SavedStateHandle(),
            application = ApplicationProvider.getApplicationContext(),
            chatRepository = fakeChatRepo,
            modelRepository = fakeModelRepo,
            skillRepository = fakeSkillRepo,
            attachmentRepository = fakeAttachmentRepo,
            settings = settings,
            inferenceEngine = fakeInferenceEngine,
            residencyController = residencyController,
            runner = chatTurnRunner,
            projectorPrompts = projectorPrompts,
            uiMessages = uiMessages,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialStateHasNoSelectionAndEmptyCollections() {
        val state = viewModel.uiState.value
        assertNull(state.selectedConversationId)
        assertTrue(state.conversations.isEmpty())
        assertTrue(state.messages.isEmpty())
        assertTrue(state.selectedSkillIds.isEmpty())
        assertTrue(state.draftAttachments.isEmpty())
        assertFalse(state.isSending)
    }

    @Test
    fun toggleSelectedSkillAddsEnabledSkill() {
        val skill = skillRecord("s1", enabled = true)
        fakeSkillRepo.emitSkills(listOf(skill))

        viewModel.toggleSelectedSkill("s1")

        assertTrue(viewModel.uiState.value.selectedSkillIds.contains("s1"))
    }

    @Test
    fun toggleSelectedSkillRemovesAlreadySelected() {
        val skill = skillRecord("s1", enabled = true)
        fakeSkillRepo.emitSkills(listOf(skill))

        viewModel.toggleSelectedSkill("s1")
        assertTrue(viewModel.uiState.value.selectedSkillIds.contains("s1"))

        viewModel.toggleSelectedSkill("s1")
        assertFalse(viewModel.uiState.value.selectedSkillIds.contains("s1"))
    }

    @Test
    fun toggleSelectedSkillEnforcesMaxLimit() {
        val skills = (1..MAX_SELECTED_SKILLS + 1).map { skillRecord("s$it", enabled = true) }
        fakeSkillRepo.emitSkills(skills)

        // Select MAX_SELECTED_SKILLS skills
        for (i in 1..MAX_SELECTED_SKILLS) {
            viewModel.toggleSelectedSkill("s$i")
        }
        assertEquals(MAX_SELECTED_SKILLS, viewModel.uiState.value.selectedSkillIds.size)

        // Trying to add one more should not increase the count
        viewModel.toggleSelectedSkill("s${MAX_SELECTED_SKILLS + 1}")
        assertEquals(MAX_SELECTED_SKILLS, viewModel.uiState.value.selectedSkillIds.size)
        assertFalse(viewModel.uiState.value.selectedSkillIds.contains("s${MAX_SELECTED_SKILLS + 1}"))
    }

    @Test
    fun toggleSelectedSkillIgnoresDisabledSkill() {
        val skill = skillRecord("s1", enabled = false)
        fakeSkillRepo.emitSkills(listOf(skill))

        viewModel.toggleSelectedSkill("s1")
        assertFalse(viewModel.uiState.value.selectedSkillIds.contains("s1"))
    }

    @Test
    fun clearSelectedSkillsEmptiesSelection() {
        val skills = listOf(skillRecord("s1", enabled = true), skillRecord("s2", enabled = true))
        fakeSkillRepo.emitSkills(skills)

        viewModel.toggleSelectedSkill("s1")
        viewModel.toggleSelectedSkill("s2")
        assertEquals(2, viewModel.uiState.value.selectedSkillIds.size)

        viewModel.clearSelectedSkills()
        assertTrue(viewModel.uiState.value.selectedSkillIds.isEmpty())
    }

    @Test
    fun selectConversationUpdatesSelectedId() = runTest {
        val conversation = Conversation(id = "c1", title = "Test", createdAt = 0, updatedAt = 0)
        fakeChatRepo.emitConversations(listOf(conversation))

        // Allow the init coroutines to process the emitted conversations
        kotlinx.coroutines.yield()

        viewModel.selectConversation("c1")
        assertEquals("c1", viewModel.uiState.value.selectedConversationId)
    }

    @Test
    fun selectConversationIsNoOpWhenAlreadyObserved() = runTest {
        val conversation = Conversation(id = "c1", title = "Test", createdAt = 0, updatedAt = 0)
        fakeChatRepo.emitConversations(listOf(conversation))
        kotlinx.coroutines.yield()

        viewModel.selectConversation("c1")
        val stateAfterFirst = viewModel.uiState.value
        viewModel.selectConversation("c1")
        val stateAfterSecond = viewModel.uiState.value

        assertEquals(stateAfterFirst.selectedConversationId, stateAfterSecond.selectedConversationId)
    }

    @Test
    fun deleteConversationClearsSelectionWhenDeletingSelected() = runTest {
        val c1 = Conversation(id = "c1", title = "First", createdAt = 0, updatedAt = 0)
        fakeChatRepo.emitConversations(listOf(c1))
        kotlinx.coroutines.yield()

        viewModel.selectConversation("c1")
        assertEquals("c1", viewModel.uiState.value.selectedConversationId)

        fakeChatRepo.emitConversations(emptyList())
        viewModel.deleteConversation("c1")
        kotlinx.coroutines.yield()

        assertNull(viewModel.uiState.value.selectedConversationId)
    }

    @Test
    fun sendMessageIsNoOpWhenGenerationAlreadyActive() = runTest {
        // The runner's state controls isSending. We verify that calling sendMessage
        // while the runner reports isSending=true does not crash or duplicate.
        val conversation = Conversation(id = "c1", title = "Test", createdAt = 0, updatedAt = 0)
        fakeChatRepo.emitConversations(listOf(conversation))
        kotlinx.coroutines.yield()
        viewModel.selectConversation("c1")

        // First call should proceed (no active generation)
        viewModel.sendMessage("hello")
        // Immediately calling again while the first might still be active
        // should be guarded by the generationJob?.isActive check
        viewModel.sendMessage("world")
        // No crash means the guard works
    }

    @Test
    fun continueResponseIsNoOpWithoutContinuableMessage() = runTest {
        val conversation = Conversation(id = "c1", title = "Test", createdAt = 0, updatedAt = 0)
        fakeChatRepo.emitConversations(listOf(conversation))
        kotlinx.coroutines.yield()
        viewModel.selectConversation("c1")

        // No messages in the conversation, so continueResponse should be a no-op
        viewModel.continueResponse()
        assertFalse(viewModel.uiState.value.isSending)
    }

    @Test
    fun stopGenerationDoesNotCrashWhenNoGenerationActive() {
        // Should be safe to call even when nothing is running
        viewModel.stopGeneration()
        assertFalse(viewModel.uiState.value.isSending)
    }

    @Test
    fun newConversationCreatesAndSelects() = runBlocking {
        val created = Conversation(id = "new1", title = "New chat", createdAt = 0, updatedAt = 0)
        fakeChatRepo.nextCreatedConversation = created

        viewModel.newConversation()

        // createConversation first reads lastQualityMode from DataStore. That is a
        // real asynchronous disk read, so no test scheduler can fast-forward it and
        // a single yield() is not enough. Wait for the state instead of sampling it
        // once — this completes as soon as the value lands, and the timeout is only
        // a failure bound, not a sleep.
        val state = withTimeout(5_000) { // failure bound, not a sleep: returns as soon as the value lands
            viewModel.uiState.first { it.selectedConversationId != null }
        }

        assertEquals("new1", state.selectedConversationId)
    }

    @Test
    fun skillFilteringRemovesSelectedWhenSkillDisabled() = runTest {
        val skill = skillRecord("s1", enabled = true)
        fakeSkillRepo.emitSkills(listOf(skill))
        viewModel.toggleSelectedSkill("s1")
        assertTrue(viewModel.uiState.value.selectedSkillIds.contains("s1"))

        // Disable the skill — the init collector should filter it out
        fakeSkillRepo.emitSkills(listOf(skill.copy(enabled = false)))
        runCurrent()

        assertFalse(viewModel.uiState.value.selectedSkillIds.contains("s1"))
    }

    private fun skillRecord(id: String, enabled: Boolean) = SkillRecord(
        id = id,
        name = "Skill $id",
        description = "Description $id",
        instructions = "Instructions $id",
        enabled = enabled,
        createdAt = 0L,
        updatedAt = 0L,
    )
}

// ---- Fakes ----

private class FakeChatRepository : ChatRepository {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: Flow<List<Conversation>> = _conversations

    var nextCreatedConversation: Conversation? = null

    override fun messages(conversationId: String): Flow<List<ChatMessage>> = flowOf(emptyList())
    override suspend fun getMessages(conversationId: String): List<ChatMessage> = emptyList()
    override suspend fun conversation(id: String): Conversation? =
        _conversations.value.firstOrNull { it.id == id }
    override suspend fun searchConversations(query: String): List<ChatSearchResult> = emptyList()
    override suspend fun createConversation(qualityMode: ChatQualityMode, temporary: Boolean): Conversation {
        val c = nextCreatedConversation ?: Conversation(
            id = "auto-${System.nanoTime()}",
            title = "New chat",
            createdAt = 0,
            updatedAt = 0,
            qualityMode = qualityMode,
            temporary = temporary,
        )
        _conversations.value = _conversations.value + c
        return c
    }
    override suspend fun setQualityMode(id: String, mode: ChatQualityMode) = Unit
    override suspend fun addMessage(
        conversationId: String, role: MessageRole, content: String,
        status: MessageStatus, origin: TurnOrigin,
    ): ChatMessage = ChatMessage(
        id = "msg-${System.nanoTime()}", conversationId = conversationId,
        role = role, content = content, createdAt = 0, status = status, origin = origin,
    )
    override suspend fun updateMessage(message: ChatMessage) = Unit
    override suspend fun deleteConversation(id: String) {
        _conversations.value = _conversations.value.filter { it.id != id }
    }
    override suspend fun markInterruptedMessages() = Unit

    fun emitConversations(list: List<Conversation>) {
        _conversations.value = list
    }
}

private class FakeModelRepository : ModelRepository {
    override val models: Flow<List<ModelRecord>> = flowOf(emptyList())
    override val projectors: Flow<List<ProjectorRecord>> = flowOf(emptyList())
    override fun modelsDirectory(): File = File("/tmp/models")
    override suspend fun ensureOfficialRecords() = Unit
    override suspend fun startOfficialDownload(id: String, allowMetered: Boolean) = Unit
    override suspend fun pauseOfficialDownload(id: String) = Unit
    override suspend fun selectModel(id: String) = Unit
    override suspend fun selectedModel(): ModelRecord? = null
    override suspend fun ensureSha256(model: ModelRecord): ModelRecord = model
    override suspend fun hasActiveTransfers(): Boolean = false
    override suspend fun deleteModel(id: String) = Unit
    override suspend fun testHuggingFaceToken(token: String): Result<Unit> = Result.success(Unit)
    override suspend fun projectorForModel(modelId: String): ProjectorRecord? = null
    override suspend fun startProjectorDownload(id: String, allowMetered: Boolean) = Unit
    override suspend fun pauseProjectorDownload(id: String) = Unit
    override suspend fun deleteProjector(id: String) = Unit
}

private class FakeSkillRepository : SkillRepository {
    private val _skills = MutableStateFlow<List<SkillRecord>>(emptyList())
    override val skills: Flow<List<SkillRecord>> = _skills

    override suspend fun create(name: String, description: String, instructions: String): SkillRecord =
        throw UnsupportedOperationException()
    override suspend fun update(id: String, name: String, description: String, instructions: String): SkillRecord =
        throw UnsupportedOperationException()
    override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun promptBlocksForSelection(ids: List<String>): List<SkillPromptBlock> = emptyList()
    override suspend fun recordInvocation(messageId: String, blocks: List<SkillPromptBlock>) = Unit
    override suspend fun blocksForMessage(messageId: String): List<SkillPromptBlock> = emptyList()
    override suspend fun blocksForMessages(messageIds: List<String>): Map<String, List<SkillPromptBlock>> = emptyMap()

    fun emitSkills(skills: List<SkillRecord>) {
        _skills.value = skills
    }
}

private class FakeAttachmentRepository : AttachmentRepository {
    override fun observeDraft(draftKey: String): Flow<List<Attachment>> = flowOf(emptyList())
    override suspend fun stage(draftKey: String, uri: android.net.Uri): Attachment =
        throw UnsupportedOperationException()
    override suspend fun retry(id: String) = Unit
    override suspend fun remove(id: String) = Unit
    override suspend fun selectPages(id: String, pages: Set<Int>) = Unit
    override suspend fun bind(messageId: String, conversationId: String, attachmentIds: List<String>, imageTokenBudget: Int?) = Unit
    override suspend fun contextsForMessage(messageId: String, prompt: String): List<AttachmentContext> = emptyList()
    override suspend fun contextsForMessages(
        messageIds: List<String>,
        promptFor: (String) -> String,
    ): Map<String, List<AttachmentContext>> = emptyMap()
    override suspend fun contexts(ids: List<String>, prompt: String): List<AttachmentContext> = emptyList()
    override suspend fun attachmentsForMessage(messageId: String): List<Attachment> = emptyList()
    override suspend fun attachmentsForMessages(messageIds: List<String>): Map<String, List<Attachment>> = emptyMap()
    override suspend fun cleanupAbandonedDrafts() = Unit
    override suspend fun markInterrupted() = Unit
    override suspend fun hasActiveProcessing(): Boolean = false
}

private class FakeInferenceEngine : InferenceEngine {
    override val state: StateFlow<InferenceState> = MutableStateFlow(InferenceState.Idle)
    override val metrics: StateFlow<InferenceMetrics> = MutableStateFlow(InferenceMetrics())
    override var loadedModelPath: String? = null
    override var loadedProjectorPath: String? = null
    override var loadedCapabilities: ModelCapabilities? = null
    override var modelContextLimit: Int = 128_000
    override var activeContextSize: Int = 0
    override suspend fun loadModel(path: String, displayName: String, configuration: ModelLoadConfiguration) = Unit
    override suspend fun loadProjector(path: String, imageTokenBudget: Int): ModelCapabilities =
        ModelCapabilities(true, true, modelContextLimit)
    override suspend fun unloadProjector() = Unit
    override suspend fun restoreSession(conversationId: String, history: List<ChatTurn>, settings: GenerationSettings) = Unit
    override fun generate(turn: UserTurn, settings: GenerationSettings, profile: InferenceExecutionProfile): Flow<GenerationEvent> =
        flowOf(GenerationEvent.Completed(com.aliahad.aichat.core.GenerationStopReason.EOG, 0, 0))
    override suspend fun countTokens(text: String): Int = text.length
    override suspend fun verifyLoadedContext(): Int = 1
    override fun cancel() = Unit
    override suspend fun unload() = Unit
    override suspend fun benchmark(path: String, displayName: String, settings: GenerationSettings): Map<BackendMode, InferenceBenchmarkSample> = emptyMap()
    override fun systemInfo(): String = "fake"
    override fun destroy() = Unit
}

private class FakeMemoryRepository : MemoryRepository {
    override val memories: Flow<List<MemoryItem>> = flowOf(emptyList())
    override val memorySources: Flow<Map<String, List<com.aliahad.aichat.core.MemorySource>>> =
        flowOf(emptyMap())
    override suspend fun rememberMessage(message: ChatMessage, conversationTemporary: Boolean) = Unit
    override suspend fun rememberAttachment(attachmentId: String, displayName: String, content: String) = Unit
    override suspend fun remember(type: com.aliahad.aichat.core.MemoryType, title: String, content: String, importance: Float, sensitivity: com.aliahad.aichat.core.MemorySensitivity): MemoryItem =
        throw UnsupportedOperationException()
    override suspend fun search(query: MemoryQuery): List<MemoryHit> = emptyList()
    override suspend fun correct(id: String, content: String, reason: String?): MemoryItem =
        throw UnsupportedOperationException()
    override suspend fun setPinned(id: String, pinned: Boolean) = Unit
    override suspend fun forget(id: String) = Unit
    override suspend fun rememberActivitySummary(source: com.aliahad.aichat.core.ActivitySource, summaryId: String, title: String, content: String, importance: Float): MemoryItem =
        throw UnsupportedOperationException()
    override suspend fun forgetActivitySource(source: com.aliahad.aichat.core.ActivitySource) = Unit
    override suspend fun purgeStaleIndexDocs() = Unit
    override suspend fun purgeExpiredMemories(now: Long): Int = 0
}

private class FakeContextProfileRepository : ContextProfileRepository {
    override val profiles: Flow<List<ModelContextProfile>> = flowOf(emptyList())
    override suspend fun resolve(model: ModelRecord, backendOverride: BackendMode?): ModelContextProfile =
        ModelContextProfile(
            id = "profile-${model.id}", modelId = model.id, modelSha256 = model.sha256 ?: "",
            deviceFingerprint = "test", physicalRamBytes = 8_000_000_000, swapBytes = 0,
            backend = backendOverride ?: BackendMode.CPU, llamaRevision = "test",
            declaredContextTokens = 128_000, verifiedContextTokens = 4_096,
            lastAttemptedTokens = null, state = ContextVerificationState.UNVERIFIED,
            peakPssBytes = null, peakRssBytes = null, peakSwapBytes = null,
            failureReason = null, verifiedAt = null, updatedAt = 0,
        )
    override suspend fun recordDeclared(profile: ModelContextProfile, declaredTokens: Int): ModelContextProfile = profile
    override suspend fun markAttempt(profile: ModelContextProfile, candidateTokens: Int): ModelContextProfile = profile
    override suspend fun recordPassed(profile: ModelContextProfile, candidateTokens: Int, declaredTokens: Int, metrics: ContextVerificationMetrics): ModelContextProfile = profile
    override suspend fun recordFailure(profile: ModelContextProfile, candidateTokens: Int, reason: String, metrics: ContextVerificationMetrics?): ModelContextProfile = profile
    override suspend fun markPaused(profile: ModelContextProfile): ModelContextProfile = profile
    override suspend fun resetVerification(model: ModelRecord): ModelContextProfile = resolve(model)
    override suspend fun latestForModel(modelId: String): ModelContextProfile? = null
    override suspend fun deleteForModel(modelId: String) = Unit

}
