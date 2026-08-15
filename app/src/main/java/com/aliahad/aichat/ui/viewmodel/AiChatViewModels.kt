package com.aliahad.aichat.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.aliahad.aichat.AppContainer

/** Activity-scoped factory that injects explicit dependencies into every feature ViewModel. */
class AiChatViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    private val messages = UiMessageManager()
    private val projectorPrompts = ProjectorPromptCoordinator(container.modelRepository)
    private val phoneSources = PhoneSourceCoordinator(
        application = container.application,
        accessManager = container.phoneSourceAccessManager,
        settings = container.settings,
        activityRepository = container.activityRepository,
        memoryRepository = container.memoryRepository,
    )
    private val chatTurnRunner = ChatTurnRunner(
        chatRepository = container.chatRepository,
        attachmentRepository = container.attachmentRepository,
        skillRepository = container.skillRepository,
        memoryRepository = container.memoryRepository,
        modelRepository = container.modelRepository,
        promptContextPlanner = container.promptContextPlanner,
        residencyController = container.residencyController,
        inferenceEngine = container.inferenceEngine,
        settingsRepository = container.settings,
        messages = messages,
    )

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val savedStateHandle = extras.createSavedStateHandle()
        return when (modelClass) {
            AppShellViewModel::class.java -> AppShellViewModel(
                messages = messages,
                savedStateHandle = savedStateHandle,
            )
            ChatViewModel::class.java -> ChatViewModel(
                savedStateHandle = savedStateHandle,
                chatRepository = container.chatRepository,
                modelRepository = container.modelRepository,
                skillRepository = container.skillRepository,
                attachmentRepository = container.attachmentRepository,
                settings = container.settings,
                inferenceEngine = container.inferenceEngine,
                residencyController = container.residencyController,
                runner = chatTurnRunner,
                projectorPrompts = projectorPrompts,
                uiMessages = messages,
            )
            ModelSetupViewModel::class.java -> ModelSetupViewModel(
                application = container.application,
                modelRepository = container.modelRepository,
                settings = container.settings,
                inferenceEngine = container.inferenceEngine,
                residencyController = container.residencyController,
                contextProfiles = container.contextProfileRepository,
                benchmarkRunner = container.inferenceBenchmarkRunner,
                diagnostics = container.diagnosticsReportBuilder,
                chatRunner = chatTurnRunner,
                projectorPrompts = projectorPrompts,
                messages = messages,
            )
            MemoryViewModel::class.java -> MemoryViewModel(
                memoryRepository = container.memoryRepository,
                settings = container.settings,
                phoneSources = phoneSources,
                backupRepository = container.officeBackupRepository,
                memoryIndexer = container.memoryIndexer,
                messages = messages,
            )
            SkillsViewModel::class.java -> SkillsViewModel(
                repository = container.skillRepository,
                messages = messages,
            )
            else -> error("Unknown ViewModel class: ${modelClass.name}")
        } as T
    }
}
