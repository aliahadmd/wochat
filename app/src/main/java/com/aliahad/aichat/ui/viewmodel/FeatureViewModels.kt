package com.aliahad.aichat.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliahad.aichat.backup.OfficeBackupRepository
import com.aliahad.aichat.context.ContextProfileRepository
import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.MemoryType
import com.aliahad.aichat.diagnostics.DiagnosticsReportBuilder
import com.aliahad.aichat.inference.InferenceBenchmarkRunner
import com.aliahad.aichat.inference.InferenceEngine
import com.aliahad.aichat.memory.MemoryIndexer
import com.aliahad.aichat.memory.MemoryRepository
import com.aliahad.aichat.model.ModelRepository
import com.aliahad.aichat.residency.ModelResidencyController
import com.aliahad.aichat.settings.AppSettingsRepository
import com.aliahad.aichat.skill.SkillRepository
import com.aliahad.aichat.ui.navigation.AppRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppShellViewModel internal constructor(
    private val messages: UiMessageManager,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AppShellUiState(
            launchDestination = savedStateHandle.get<String>(KEY_LAUNCH_DESTINATION)
                ?.let(AppRoute::fromRoute),
        ),
    )
    val uiState: StateFlow<AppShellUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            messages.error.collectLatest { error -> _uiState.update { it.copy(error = error) } }
        }
    }

    fun initialize() {
        if (_uiState.value.launchDestination != null) return
        setLaunchDestination(AppRoute.CHAT, navigate = false)
    }

    fun clearError() = messages.clear()

    fun consumeNavigation() {
        _uiState.update { it.copy(pendingNavigation = null) }
    }

    private fun setLaunchDestination(destination: AppRoute, navigate: Boolean) {
        savedStateHandle[KEY_LAUNCH_DESTINATION] = destination.route
        _uiState.update {
            it.copy(
                launchDestination = destination,
                pendingNavigation = destination.takeIf { navigate },
            )
        }
    }

    private companion object {
        const val KEY_LAUNCH_DESTINATION = "app_shell_launch_destination"
    }
}

class ModelSetupViewModel internal constructor(
    private val application: Application,
    private val modelRepository: ModelRepository,
    private val settings: AppSettingsRepository,
    private val inferenceEngine: InferenceEngine,
    private val residencyController: ModelResidencyController,
    private val contextProfiles: ContextProfileRepository,
    private val benchmarkRunner: InferenceBenchmarkRunner,
    private val diagnostics: DiagnosticsReportBuilder,
    private val chatRunner: ChatTurnRunner,
    private val projectorPrompts: ProjectorPromptCoordinator,
    private val messages: UiMessageManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        ModelSetupUiState(tokenMasked = settings.maskedToken()),
    )
    val uiState: StateFlow<ModelSetupUiState> = _uiState.asStateFlow()
    private var configurationReloadJob: Job? = null

    init {
        collect(modelRepository.models) { models -> copy(models = models) }
        collect(modelRepository.projectors) { projectors -> copy(projectors = projectors) }
        collect(settings.backendMode) { backendMode -> copy(backendMode = backendMode) }
        collect(settings.generationSettings) { generationSettings ->
            copy(generationSettings = generationSettings)
        }
        collect(settings.allowMeteredModelDownloads) { enabled ->
            copy(allowMeteredModelDownloads = enabled)
        }
        collect(inferenceEngine.state) { inferenceState -> copy(inferenceState = inferenceState) }
        collect(inferenceEngine.metrics) { inferenceMetrics -> copy(inferenceMetrics = inferenceMetrics) }
        collect(residencyController.state) { residencyState -> copy(residencyState = residencyState) }
        collect(contextProfiles.profiles) { profiles -> copy(contextProfiles = profiles) }
        collect(chatRunner.state) { run -> copy(isSending = run.isSending) }
        collect(projectorPrompts.pendingProjectorId) { id -> copy(pendingProjectorId = id) }
        viewModelScope.launch {
            runCatching { modelRepository.ensureOfficialRecords() }.onFailure(messages::report)
        }
    }

    fun setAllowMeteredModelDownloads(enabled: Boolean) {
        _uiState.update { it.copy(allowMeteredModelDownloads = enabled) }
        launchCatching { settings.setAllowMeteredModelDownloads(enabled) }
    }

    fun startDownload(id: String) = launchCatching {
        modelRepository.startOfficialDownload(id, _uiState.value.allowMeteredModelDownloads)
    }

    fun pauseDownload(id: String) = launchCatching { modelRepository.pauseOfficialDownload(id) }

    fun selectModel(id: String) {
        if (!allowModelMutation()) return
        launchCatching {
            val previous = modelRepository.selectedModel()
            residencyController.unload()
            modelRepository.selectModel(id)
            try {
                residencyController.ensureLoaded()
            } catch (error: Throwable) {
                if (previous?.status == DownloadStatus.READY && previous.localPath != null) {
                    modelRepository.selectModel(previous.id)
                    runCatching { residencyController.ensureLoaded() }
                }
                throw error
            }
        }
    }

    fun deleteModel(id: String) {
        if (!allowModelMutation()) return
        launchCatching {
            val model = _uiState.value.models.firstOrNull { it.id == id }
            if (model?.localPath?.let(residencyController::isPathInUse) == true) {
                residencyController.unload()
            }
            modelRepository.deleteModel(id)
            contextProfiles.deleteForModel(id)
        }
    }

    fun startProjectorDownload(id: String) {
        projectorPrompts.dismiss()
        launchCatching {
            modelRepository.startProjectorDownload(
                id,
                _uiState.value.allowMeteredModelDownloads,
            )
        }
    }

    fun pauseProjectorDownload(id: String) =
        launchCatching { modelRepository.pauseProjectorDownload(id) }

    fun deleteProjector(id: String) {
        if (!allowModelMutation()) return
        launchCatching {
            if (_uiState.value.projectors.firstOrNull { it.id == id }?.localPath
                    ?.let(residencyController::isPathInUse) == true
            ) {
                residencyController.unload()
            }
            modelRepository.deleteProjector(id)
        }
    }

    fun dismissProjectorPrompt() = projectorPrompts.dismiss()

    fun updateBackend(mode: BackendMode) {
        if (!allowModelMutation()) return
        launchCatching {
            settings.setBackend(mode)
            residencyController.reloadForConfigurationChange()
        }
    }

    fun updateGeneration(value: GenerationSettings) {
        val previous = _uiState.value.generationSettings
        viewModelScope.launch {
            settings.updateGeneration(value)
            if (previous.temperature != value.normalized().temperature && !chatRunner.state.value.isSending) {
                configurationReloadJob?.cancel()
                configurationReloadJob = viewModelScope.launch {
                    delay(750)
                    runCatching { residencyController.reloadForConfigurationChange() }
                        .onFailure(messages::report)
                }
            }
        }
    }

    fun optimizeForThisPhone() {
        if (_uiState.value.isOptimizingBackend || chatRunner.state.value.isSending) return
        viewModelScope.launch {
            _uiState.update { it.copy(isOptimizingBackend = true) }
            runCatching { benchmarkRunner.optimizeSelectedModel() }
                .onSuccess { result -> _uiState.update { it.copy(benchmarks = result) } }
                .onFailure(messages::report)
            _uiState.update { it.copy(isOptimizingBackend = false) }
        }
    }

    fun retryPreload() {
        if (allowModelMutation()) launchCatching { residencyController.ensureLoaded() }
    }

    fun reverifyContext() {
        if (allowModelMutation()) launchCatching { residencyController.reverifySelectedModel() }
    }

    fun unloadModel() {
        if (allowModelMutation()) launchCatching { residencyController.unload() }
    }

    fun saveToken(token: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(tokenTesting = true) }
            modelRepository.testHuggingFaceToken(token)
                .onSuccess {
                    settings.saveToken(token)
                    _uiState.update {
                        it.copy(tokenMasked = settings.maskedToken(), tokenTesting = false)
                    }
                }
                .onFailure {
                    _uiState.update { state -> state.copy(tokenTesting = false) }
                    messages.report(it)
                }
        }
    }

    fun clearToken() {
        settings.clearToken()
        _uiState.update { it.copy(tokenMasked = null) }
    }

    fun exportDiagnostics(uri: Uri) = launchCatching {
        val report = diagnostics.build()
        requireNotNull(application.contentResolver.openOutputStream(uri)) {
            "Unable to open the diagnostics destination."
        }.bufferedWriter().use { it.write(report) }
    }

    private fun allowModelMutation(): Boolean {
        if (!chatRunner.state.value.isSending) return true
        messages.report("Stop the current response before changing the model.")
        return false
    }

    private fun launchCatching(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() }.onFailure(messages::report) }
    }

    private fun <T> collect(
        flow: kotlinx.coroutines.flow.Flow<T>,
        reducer: ModelSetupUiState.(T) -> ModelSetupUiState,
    ) {
        viewModelScope.launch {
            flow.collectLatest { value -> _uiState.update { it.reducer(value) } }
        }
    }
}

class MemoryViewModel internal constructor(
    private val memoryRepository: MemoryRepository,
    private val settings: AppSettingsRepository,
    private val phoneSources: PhoneSourceCoordinator,
    private val backupRepository: OfficeBackupRepository,
    private val memoryIndexer: MemoryIndexer,
    private val messages: UiMessageManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    init {
        collect(memoryRepository.memories) { memories -> copy(memories = memories) }
        collect(settings.memoryEnabled) { enabled -> copy(memoryEnabled = enabled) }
        collect(settings.collectionPaused) { paused -> copy(collectionPaused = paused) }
        collect(phoneSources.statuses) { statuses -> copy(phoneSourceStatuses = statuses) }
        collect(phoneSources.stats) { stats ->
            copy(phoneSourceStats = stats.associateBy { it.source })
        }
        viewModelScope.launch {
            runCatching {
                phoneSources.refresh()
            }.onFailure(messages::report)
        }
    }

    fun setMemoryEnabled(enabled: Boolean) = launchCatching { settings.setMemoryEnabled(enabled) }

    fun setCollectionPaused(paused: Boolean) = launchCatching {
        settings.setCollectionPaused(paused)
        if (!paused) phoneSources.collectGrantedSources()
    }

    fun refreshPhoneSourceAccess() = launchCatching { phoneSources.refresh() }

    fun clearCollectedSource(source: ActivitySource) = launchCatching { phoneSources.clear(source) }

    fun addMemory(content: String) = launchCatching {
        memoryRepository.remember(MemoryType.FACT, content, content)
    }

    fun correctMemory(id: String, content: String) =
        launchCatching { memoryRepository.correct(id, content) }

    fun pinMemory(id: String, pinned: Boolean) =
        launchCatching { memoryRepository.setPinned(id, pinned) }

    fun forgetMemory(id: String) = launchCatching { memoryRepository.forget(id) }

    fun exportOfficeBackup(uri: Uri, passphrase: CharArray) {
        if (_uiState.value.backupBusy) {
            passphrase.fill('\u0000')
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(backupBusy = true) }
            runCatching { backupRepository.export(uri, passphrase) }
                .onFailure(messages::report)
            _uiState.update { it.copy(backupBusy = false) }
        }
    }

    fun selectOfficeBackup(uri: Uri?) {
        if (_uiState.value.backupBusy) return
        _uiState.update { it.copy(pendingBackupImportUri = uri, backupPreview = null) }
    }

    fun prepareOfficeImport(passphrase: CharArray) {
        val uri = _uiState.value.pendingBackupImportUri
        if (uri == null || _uiState.value.backupBusy) {
            passphrase.fill('\u0000')
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(backupBusy = true) }
            runCatching { backupRepository.prepareImport(uri, passphrase) }
                .onSuccess { preview ->
                    _uiState.update {
                        it.copy(pendingBackupImportUri = null, backupPreview = preview)
                    }
                }
                .onFailure(messages::report)
            _uiState.update { it.copy(backupBusy = false) }
        }
    }

    fun commitOfficeImport() {
        val preview = _uiState.value.backupPreview ?: return
        if (_uiState.value.backupBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(backupBusy = true) }
            runCatching {
                backupRepository.commitImport(preview)
                runCatching { memoryIndexer.rebuild(memoryRepository.memories.first()) }
            }.onSuccess {
                _uiState.update { it.copy(backupPreview = null) }
            }.onFailure(messages::report)
            _uiState.update { it.copy(backupBusy = false) }
        }
    }

    fun discardOfficeImport() {
        val preview = _uiState.value.backupPreview
        _uiState.update { it.copy(pendingBackupImportUri = null, backupPreview = null) }
        if (preview != null) launchCatching { backupRepository.discardImport(preview) }
    }

    private fun launchCatching(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() }.onFailure(messages::report) }
    }

    private fun <T> collect(
        flow: kotlinx.coroutines.flow.Flow<T>,
        reducer: MemoryUiState.(T) -> MemoryUiState,
    ) {
        viewModelScope.launch {
            flow.collectLatest { value -> _uiState.update { it.reducer(value) } }
        }
    }
}

class SkillsViewModel internal constructor(
    private val repository: SkillRepository,
    private val messages: UiMessageManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SkillsUiState())
    val uiState: StateFlow<SkillsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.skills.collectLatest { skills -> _uiState.value = SkillsUiState(skills) }
        }
    }

    fun createSkill(name: String, description: String, instructions: String) = launchCatching {
        repository.create(name, description, instructions)
    }

    fun updateSkill(id: String, name: String, description: String, instructions: String) =
        launchCatching { repository.update(id, name, description, instructions) }

    fun setSkillEnabled(id: String, enabled: Boolean) =
        launchCatching { repository.setEnabled(id, enabled) }

    fun deleteSkill(id: String) = launchCatching { repository.delete(id) }

    private fun launchCatching(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() }.onFailure(messages::report) }
    }
}
