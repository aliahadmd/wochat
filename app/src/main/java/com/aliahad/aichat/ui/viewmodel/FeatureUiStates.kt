package com.aliahad.aichat.ui.viewmodel

import android.net.Uri
import com.aliahad.aichat.ThinkingUiState
import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.core.ActivitySourceStats
import com.aliahad.aichat.core.Attachment
import com.aliahad.aichat.core.BackendBenchmark
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.BackupPreview
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.Conversation
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.InferenceMetrics
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.MemoryItem
import com.aliahad.aichat.core.ModelContextProfile
import com.aliahad.aichat.core.ModelRecord
import com.aliahad.aichat.core.PhoneSourceStatus
import com.aliahad.aichat.core.ProjectorRecord
import com.aliahad.aichat.core.SkillPromptBlock
import com.aliahad.aichat.core.SkillRecord
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
    val selectedConversationId: String? = null,
    val inferenceState: InferenceState = InferenceState.Uninitialized,
    val inferenceMetrics: InferenceMetrics = InferenceMetrics(),
    val residencyState: ModelResidencyState = ModelResidencyState.Idle,
    val thinking: ThinkingUiState? = null,
    val usedMemoryCount: Int = 0,
    val isSending: Boolean = false,
    val modelCatalogLoaded: Boolean = false,
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
)

data class MemoryUiState(
    val memories: List<MemoryItem> = emptyList(),
    val memoryEnabled: Boolean = true,
    val collectionPaused: Boolean = true,
    val phoneSourceStatuses: Map<ActivitySource, PhoneSourceStatus> = emptyMap(),
    val phoneSourceStats: Map<ActivitySource, ActivitySourceStats> = emptyMap(),
    val pendingBackupImportUri: Uri? = null,
    val backupPreview: BackupPreview? = null,
    val backupBusy: Boolean = false,
)

data class SkillsUiState(val skills: List<SkillRecord> = emptyList())

data class AppShellUiState(
    val launchDestination: AppRoute? = null,
    val error: String? = null,
    val pendingNavigation: AppRoute? = null,
)
