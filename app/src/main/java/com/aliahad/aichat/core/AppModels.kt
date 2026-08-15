package com.aliahad.aichat.core

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

enum class MessageStatus {
    COMPLETE,
    STREAMING,
    CONTINUABLE,
    CANCELLED,
    ERROR,
}

enum class GenerationStopReason {
    EOG,
    TOKEN_LIMIT,
    CONTEXT_LIMIT,
    CANCELLED,
    PROCESS_DEATH,
    DECODE_ERROR,
    ERROR,
    REPETITION,
}

enum class TurnOrigin {
    TYPED,
}

enum class InferenceExecutionProfile {
    NORMAL,

    /**
     * Best-effort background work (rolling summarization, archive digests). Never
     * preempts interactive turns: the engine serializes it through the same runtime
     * gate and cancels it as soon as chat inference begins.
     */
    UTILITY,
}

sealed interface GenerationEvent {
    data class Phase(val state: InferenceState) : GenerationEvent
    data class ThoughtDelta(val text: String) : GenerationEvent
    data class AnswerDelta(val text: String) : GenerationEvent
    data class BackendFallback(
        val from: BackendMode,
        val to: BackendMode,
        val stage: BackendFailureStage,
        val discardPartialOutput: Boolean = true,
    ) : GenerationEvent
    data class Completed(
        val reason: GenerationStopReason,
        val answerTokens: Int,
        val continuationCount: Int,
    ) : GenerationEvent
}

enum class BackendMode {
    AUTO,
    CPU,
    VULKAN,
}

enum class BackendFailureStage {
    CONNECT,
    LOAD,
    PROJECTOR,
    RESTORE,
    PROMPT,
    MEDIA,
    DECODE,
    VERIFY,
    BENCHMARK,
    SERVICE_DIED,
    UNKNOWN,
}

enum class ChatQualityMode {
    FAST,
    BEST,
}

enum class MemoryType {
    FACT,
    PREFERENCE,
    PERSON,
    PLACE,
    PROJECT,
    GOAL,
    PROCEDURE,
    EPISODE,
}

enum class MemorySensitivity {
    NORMAL,
    PRIVATE,
    SECRET,
}

enum class MemoryStatus {
    ACTIVE,
    SUPERSEDED,
    DELETED,
}

enum class MemorySourceKind {
    CHAT_MESSAGE,
    ATTACHMENT,
    ACTIVITY,
    MANUAL,
    IMPORT,
}

enum class ActivitySource {
    APP_USAGE,
    APP_INSTALL,
    NOTIFICATION,
    ACCESSIBILITY,
    LOCATION,
    SENSOR,
    HEALTH,
    CONTACT,
    CALENDAR,
    DOCUMENT,
    SMS,
    CALL,
}

enum class PhoneSourceAccessState {
    GRANTED,
    NOT_GRANTED,
    UNAVAILABLE,
}

data class PhoneSourceStatus(
    val source: ActivitySource,
    val state: PhoneSourceAccessState,
    val detail: String,
    val actionLabel: String? = null,
)

data class ActivitySourceStats(
    val source: ActivitySource,
    val eventCount: Long,
    val lastEventAt: Long?,
)

enum class AttachmentKind {
    IMAGE,
    AUDIO,
    PDF,
    TEXT,
    CODE,
    CSV,
    JSON,
    XML,
    HTML,
    DOCX,
    XLSX,
    PPTX,
}

enum class AttachmentProcessingState {
    COPYING,
    EXTRACTING,
    RANKING,
    READY,
    FAILED,
}

data class Attachment(
    val id: String,
    val conversationId: String?,
    val draftKey: String?,
    val displayName: String,
    val mimeType: String,
    val kind: AttachmentKind,
    val originalPath: String,
    val previewPath: String?,
    val derivedImagePaths: List<String>,
    val byteSize: Long,
    val pageCount: Int?,
    val selectedPages: Set<Int>,
    val imageTokenBudget: Int?,
    val state: AttachmentProcessingState,
    val progress: Float,
    val error: String?,
    val createdAt: Long,
    val durationMillis: Long? = null,
)

data class AttachmentContext(
    val attachmentId: String,
    val displayName: String,
    val extractedText: String,
    val imagePaths: List<String>,
    val selectedPages: Set<Int>,
    val imageTokenBudget: Int,
    val audioPaths: List<String> = emptyList(),
    val audioTokenEstimate: Int = 0,
) {
    val mediaPaths: List<String>
        get() = imagePaths + audioPaths
}

data class ModelCapabilities(
    val vision: Boolean,
    val audio: Boolean,
    val contextLimit: Int = 0,
)

data class MultimodalSettings(
    val qualityMode: ChatQualityMode,
    val imageTokenBudget: Int,
)

enum class MultimodalRequirement {
    NONE,
    VISION,
    AUDIO,
    BOTH,
}

data class UserTurn(
    val conversationId: String,
    val text: String,
    val attachments: List<AttachmentContext> = emptyList(),
)

data class ChatTurn(
    val message: ChatMessage,
    val attachments: List<AttachmentContext> = emptyList(),
)

data class SkillRecord(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long? = null,
)

data class SkillPromptBlock(
    val skillId: String?,
    val name: String,
    val description: String,
    val instructions: String,
)

data class GenerationSettings(
    val maxNewTokens: Int = 1024,
    val maxAnswerTokens: Int = 8192,
    val temperature: Float = 0.3f,
    val thinkingEnabled: Boolean = false,
    val systemPrompt: String = "You are a helpful, concise assistant.",
) {
    fun normalized(): GenerationSettings {
        val segmentLimit = maxNewTokens.coerceIn(64, 2048)
        return copy(
            maxNewTokens = segmentLimit,
            maxAnswerTokens = maxAnswerTokens.coerceIn(segmentLimit, 8192),
            temperature = temperature.coerceIn(0f, 2f),
            systemPrompt = systemPrompt.trim().ifEmpty { "You are a helpful, concise assistant." },
        )
    }

}

data class ModelLoadConfiguration(
    val contextTokens: Int,
    val declaredContextTokens: Int,
    val backend: BackendMode = BackendMode.CPU,
    val temperature: Float,
    val modelId: String? = null,
    val modelSha256: String? = null,
)

enum class ContextVerificationState {
    UNVERIFIED,
    VERIFYING,
    VERIFIED,
    LIMITED,
    FAILED,
}

data class ModelContextProfile(
    val id: String,
    val modelId: String,
    val modelSha256: String,
    val deviceFingerprint: String,
    val physicalRamBytes: Long,
    val swapBytes: Long,
    val backend: BackendMode,
    val llamaRevision: String,
    val declaredContextTokens: Int,
    val verifiedContextTokens: Int,
    val lastAttemptedTokens: Int?,
    val state: ContextVerificationState,
    val peakPssBytes: Long?,
    val peakRssBytes: Long?,
    val peakSwapBytes: Long?,
    val failureReason: String?,
    val verifiedAt: Long?,
    val updatedAt: Long,
)

data class ContextVerificationMetrics(
    val generatedTokens: Int,
    val pssBytes: Long,
    val rssBytes: Long,
    val swapBytes: Long,
)

data class BackendBenchmark(
    val id: String,
    val modelId: String,
    val modelSha256: String,
    val deviceFingerprint: String,
    val backend: BackendMode,
    val llamaRevision: String,
    val loadMillis: Long?,
    val promptTokensPerSecond: Double?,
    val generationTokensPerSecond: Double?,
    val peakPssBytes: Long?,
    val thermalDelta: Int?,
    val success: Boolean,
    val failureReason: String?,
    val measuredAt: Long,
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
    val status: MessageStatus,
    val origin: TurnOrigin = TurnOrigin.TYPED,
    val stopReason: GenerationStopReason? = null,
    val continuationCount: Int = 0,
    val promptTokens: Int? = null,
    val generatedTokens: Int? = null,
)

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val qualityMode: ChatQualityMode = ChatQualityMode.FAST,
    val temporary: Boolean = false,
)

data class ConversationSummary(
    val conversationId: String,
    val throughMessageId: String?,
    val content: String,
    val tokenCount: Int,
    val updatedAt: Long,
)

data class MemoryItem(
    val id: String,
    val type: MemoryType,
    val title: String,
    val content: String,
    val confidence: Float,
    val importance: Float,
    val sensitivity: MemorySensitivity,
    val status: MemoryStatus,
    val pinned: Boolean,
    val validFrom: Long?,
    val validTo: Long?,
    val supersedesId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class MemorySource(
    val id: String,
    val memoryId: String,
    val kind: MemorySourceKind,
    val sourceId: String?,
    val label: String?,
    val createdAt: Long,
)

data class MemoryQuery(
    val text: String,
    val limit: Int = 8,
    val includePrivate: Boolean = true,
    /**
     * Extra recall-only text (e.g. recent conversation history). It widens the
     * AppSearch candidate lookup but never influences retrieval semantics: intent
     * detection, lexical scoring, and phrase matching use [text] only.
     */
    val expansion: String = "",
)

data class MemoryHit(
    val memory: MemoryItem,
    val score: Float,
    val sources: List<MemorySource>,
    val reason: String,
)

data class ContextPlan(
    val systemPrompt: String,
    val summary: ConversationSummary?,
    val history: List<ChatTurn>,
    val memories: List<MemoryHit>,
    val skills: List<SkillPromptBlock> = emptyList(),
    val estimatedTokens: Int,
    val outputReserveTokens: Int,
)

data class BackupManifest(
    val formatVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    val databaseVersion: Int,
    val recordCounts: Map<String, Long>,
)

data class BackupPreview(
    val stagingPath: String,
    val createdAt: Long,
    val conversations: Long,
    val messages: Long,
    val memories: Long,
    val activities: Long,
    val attachments: Long,
)

enum class DownloadStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    READY,
    FAILED,
}

data class ModelRecord(
    val id: String,
    val displayName: String,
    val fileName: String,
    val localPath: String?,
    val sourceRepo: String?,
    val expectedBytes: Long?,
    val sha256: String?,
    val downloadedBytes: Long,
    val status: DownloadStatus,
    val error: String?,
    val selected: Boolean,
    val bytesPerSecond: Long = 0,
    val etaSeconds: Long? = null,
    val retryAttempt: Int = 0,
)

data class ProjectorRecord(
    val id: String,
    val modelId: String,
    val displayName: String,
    val fileName: String,
    val localPath: String?,
    val sourceRepo: String,
    val expectedBytes: Long,
    val sha256: String,
    val downloadedBytes: Long,
    val status: DownloadStatus,
    val error: String?,
    val bytesPerSecond: Long = 0,
    val etaSeconds: Long? = null,
    val retryAttempt: Int = 0,
)

sealed interface InferenceState {
    data object Uninitialized : InferenceState
    data object Idle : InferenceState
    data class Loading(val modelName: String) : InferenceState
    data class Ready(val modelName: String, val backend: BackendMode) : InferenceState
    data object PreparingHistory : InferenceState
    data object EvaluatingPrompt : InferenceState
    data object EncodingMedia : InferenceState
    data object Generating : InferenceState
    data class Recovering(
        val modelName: String,
        val from: BackendMode,
        val to: BackendMode,
        val stage: BackendFailureStage,
    ) : InferenceState
    data class Error(val message: String) : InferenceState
}

data class InferenceMetrics(
    val modelLoadMillis: Long? = null,
    val historyRestoreMillis: Long? = null,
    val promptEvaluationMillis: Long? = null,
    val firstTokenMillis: Long? = null,
)

data class InferenceBenchmarkSample(
    val loadMillis: Long,
    val promptTokensPerSecond: Double,
    val generationTokensPerSecond: Double,
    val peakPssBytes: Long? = null,
)
