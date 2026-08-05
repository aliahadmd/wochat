package com.aliahad.aichat.ui.viewmodel

import android.app.Application
import com.aliahad.aichat.activity.ActivityRepository
import com.aliahad.aichat.activity.OfficeWorkScheduler
import com.aliahad.aichat.activity.PhoneSourceAccessManager
import com.aliahad.aichat.brief.HealthDataSource
import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.core.PhoneSourceAccessState
import com.aliahad.aichat.core.PhoneSourceStatus
import com.aliahad.aichat.memory.MemoryRepository
import com.aliahad.aichat.model.ModelRepository
import com.aliahad.aichat.settings.AppSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** One activity-scoped destination for actionable errors from every feature. */
class UiMessageManager {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun report(error: Throwable) {
        _error.value = error.message ?: error.javaClass.simpleName
    }

    fun report(message: String) {
        _error.value = message
    }

    fun clear() {
        _error.value = null
    }
}

/** Shares the optional projector prompt without making feature ViewModels depend on each other. */
class ProjectorPromptCoordinator(
    private val modelRepository: ModelRepository,
) {
    private val _pendingProjectorId = MutableStateFlow<String?>(null)
    val pendingProjectorId: StateFlow<String?> = _pendingProjectorId.asStateFlow()

    suspend fun requestForSelectedModel() {
        val model = modelRepository.selectedModel() ?: return
        val projector = modelRepository.projectorForModel(model.id) ?: return
        if (projector.status != com.aliahad.aichat.core.DownloadStatus.READY) {
            _pendingProjectorId.value = projector.id
        }
    }

    fun dismiss() {
        _pendingProjectorId.value = null
    }
}

/** Keeps permission/source snapshots consistent between Memory and Setup. */
class PhoneSourceCoordinator(
    private val application: Application,
    private val accessManager: PhoneSourceAccessManager,
    private val healthDataSource: HealthDataSource,
    private val settings: AppSettingsRepository,
    private val activityRepository: ActivityRepository,
    private val memoryRepository: MemoryRepository,
) {
    private val _statuses = MutableStateFlow<Map<ActivitySource, PhoneSourceStatus>>(emptyMap())
    val statuses: StateFlow<Map<ActivitySource, PhoneSourceStatus>> = _statuses.asStateFlow()
    val stats = activityRepository.sourceStats

    val healthConnectAvailable: Boolean
        get() = healthDataSource.isAvailable()

    suspend fun refresh() {
        val previous = _statuses.value
        val current = accessManager.snapshot().toMutableMap()
        if (healthDataSource.isAvailable()) {
            val granted = runCatching { healthDataSource.grantedPermissions() }.getOrDefault(emptySet())
            val complete = healthDataSource.readPermissions.all(granted::contains)
            current[ActivitySource.HEALTH] = PhoneSourceStatus(
                source = ActivitySource.HEALTH,
                state = if (complete) {
                    PhoneSourceAccessState.GRANTED
                } else {
                    PhoneSourceAccessState.NOT_GRANTED
                },
                detail = if (complete) {
                    "Health Connect read access granted"
                } else {
                    "Health Connect access is optional"
                },
                actionLabel = if (complete) "Manage" else "Grant access",
            )
        }
        _statuses.value = current
        if (!settings.collectionPaused.first()) {
            current.values
                .filter { status ->
                    status.state == PhoneSourceAccessState.GRANTED &&
                        previous[status.source]?.state != PhoneSourceAccessState.GRANTED
                }
                .forEach { OfficeWorkScheduler.collectNow(application, it.source) }
        }
    }

    fun collectGrantedSources() {
        _statuses.value.values
            .filter { it.state == PhoneSourceAccessState.GRANTED }
            .forEach { OfficeWorkScheduler.collectNow(application, it.source) }
    }

    suspend fun clear(source: ActivitySource) {
        memoryRepository.forgetActivitySource(source)
        activityRepository.deleteSource(source)
    }
}
