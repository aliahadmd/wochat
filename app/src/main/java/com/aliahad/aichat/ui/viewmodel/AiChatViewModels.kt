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
    // Both come from the container: a turn keeps running when the Activity goes
    // away, so neither the runner nor the message queue may be Activity-scoped.
    private val messages = container.uiMessages
    private val projectorPrompts = ProjectorPromptCoordinator(container.modelRepository)
    private val chatTurnRunner = container.chatTurnRunner

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
                application = container.application,
                modelRepository = container.modelRepository,
                chatRepository = container.chatRepository,
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
