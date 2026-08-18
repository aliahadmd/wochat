package com.aliahad.aichat.ui.viewmodel

import android.net.Uri
import com.aliahad.aichat.ThinkingUiState
import com.aliahad.aichat.core.Attachment
import com.aliahad.aichat.core.BackendBenchmark
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.ThemeMode
import com.aliahad.aichat.core.BackupPreview
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.Conversation
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.InferenceMetrics
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.MemoryItem
import com.aliahad.aichat.core.MemorySource
import com.aliahad.aichat.core.ModelContextProfile
import com.aliahad.aichat.core.ModelRecord
import com.aliahad.aichat.core.ProjectorRecord
import com.aliahad.aichat.core.SkillPromptBlock
import com.aliahad.aichat.core.SkillRecord
import com.aliahad.aichat.data.ChatSearchResult
import com.aliahad.aichat.residency.ModelResidencyState
import com.aliahad.aichat.ui.navigation.AppRoute

data class ChatUiState(
    val conversations: List<Conversation> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val models: List<ModelRecord> = emptyList(),
    val projectors: List<ProjectorRecord> = emptyList(),
    val draftAttachments: List<Attachment> = emptyList(),
    val messageAttachments: Map<String, List<Attachment>> = emptyMap(),
    val skills: List<SkillRecord> = emptyList(),
    val selectedSkillIds: List<String> = emptyList(),
    val messageSkills: Map<String, List<SkillPromptBlock>> = emptyMap(),
    val draftKey: String = "",
    /**
     * The composer's text. Owned here rather than by the composable so it
     * survives configuration changes and process death, and so it is cleared
     * with the rest of the draft when the conversation changes.
     */
    val input: String = "",
    val selectedConversationId: String? = null,
    val inferenceState: InferenceState = InferenceState.Uninitialized,
    val inferenceMetrics: InferenceMetrics = InferenceMetrics(),
    val residencyState: ModelResidencyState = ModelResidencyState.Idle,
    val thinking: ThinkingUiState? = null,
    val thinkingEnabled: Boolean = false,
    val usedMemoryCount: Int = 0,
    val memoryEnabled: Boolean = true,
    val actionsEnabled: Boolean = false,
    val isSending: Boolean = false,
    val modelCatalogLoaded: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<ChatSearchResult> = emptyList(),
)

data class ModelSetupUiState(
    val models: List<ModelRecord> = emptyList(),
    val projectors: List<ProjectorRecord> = emptyList(),
    val backendMode: BackendMode = BackendMode.AUTO,
    val generationSettings: GenerationSettings = GenerationSettings(),
    val inferenceState: InferenceState = InferenceState.Uninitialized,
    val inferenceMetrics: InferenceMetrics = InferenceMetrics(),
    val residencyState: ModelResidencyState = ModelResidencyState.Idle,
    val contextProfiles: List<ModelContextProfile> = emptyList(),
    val tokenMasked: String? = null,
    val tokenTesting: Boolean = false,
    val pendingProjectorId: String? = null,
    val isSending: Boolean = false,
    val benchmarks: List<BackendBenchmark> = emptyList(),
    val isOptimizingBackend: Boolean = false,
    val allowMeteredModelDownloads: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val semanticRecall: SemanticRecallUiState = SemanticRecallUiState(),
    val voiceModels: VoiceModelsUiState = VoiceModelsUiState(),
)

data class MemoryUiState(
    val memories: List<MemoryItem> = emptyList(),
    val memorySources: Map<String, List<MemorySource>> = emptyMap(),
    val memoryEnabled: Boolean = true,
    val pendingBackupImportUri: Uri? = null,
    val backupPreview: BackupPreview? = null,
    val backupBusy: Boolean = false,
)

data class SkillsUiState(val skills: List<SkillRecord> = emptyList())

data class AppShellUiState(
    val launchDestination: AppRoute? = null,
    val error: UiMessage? = null,
    val pendingNavigation: AppRoute? = null,
)

/**
 * The optional sentence embedder behind semantic recall.
 *
 * Carries the whole record rather than a flattened status/bytes pair, so it can be
 * rendered by the same card as the chat model and the projector — the progress
 * rate, ETA and retry-attempt were always present, they just had nowhere to go.
 */
data class SemanticRecallUiState(
    val record: com.aliahad.aichat.core.ModelRecord? = null,
)

/**
 * The VAD, recogniser and voice artifacts behind call mode, presented as one thing.
 *
 * They are six separate downloads because that is how the vendor publishes them,
 * but they are useless individually — call mode needs all six or none — so the UI
 * shows a single card and the aggregate below drives it.
 */
data class VoiceModelsUiState(
    val records: List<com.aliahad.aichat.core.ModelRecord> = emptyList(),
) {
    val expectedBytes: Long get() = records.sumOf { it.expectedBytes ?: 0L }
    val downloadedBytes: Long get() = records.sumOf { it.downloadedBytes }
    val bytesPerSecond: Long get() = records.sumOf { it.bytesPerSecond }
    val error: String? get() = records.firstNotNullOfOrNull { it.error }
    val status: DownloadStatus get() = aggregateDownloadStatus(records.map { it.status })
}

/**
 * Worst-news-first: a set of downloads is only READY when every part is, and a
 * single failure has to surface rather than being averaged away by five successes.
 */
internal fun aggregateDownloadStatus(statuses: List<DownloadStatus>): DownloadStatus = when {
    statuses.isEmpty() -> DownloadStatus.NOT_DOWNLOADED
    statuses.all { it == DownloadStatus.READY } -> DownloadStatus.READY
    statuses.any { it == DownloadStatus.FAILED } -> DownloadStatus.FAILED
    statuses.any {
        it == DownloadStatus.DOWNLOADING || it == DownloadStatus.QUEUED || it == DownloadStatus.VERIFYING
    } -> DownloadStatus.DOWNLOADING
    statuses.any { it == DownloadStatus.PAUSED } -> DownloadStatus.PAUSED
    else -> DownloadStatus.NOT_DOWNLOADED
}
