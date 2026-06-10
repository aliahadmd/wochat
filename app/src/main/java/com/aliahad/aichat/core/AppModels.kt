package com.aliahad.aichat.core

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

enum class MessageStatus {
    COMPLETE,
    STREAMING,
    CANCELLED,
    ERROR,
}

enum class BackendMode {
    AUTO,
    CPU,
    VULKAN,
}

enum class ChatQualityMode {
    FAST,
    BEST,
}

enum class AttachmentKind {
    IMAGE,
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
)

data class AttachmentContext(
    val attachmentId: String,
    val displayName: String,
    val extractedText: String,
    val imagePaths: List<String>,
    val selectedPages: Set<Int>,
    val imageTokenBudget: Int,
)

data class ModelCapabilities(
    val vision: Boolean,
    val audio: Boolean,
)

data class MultimodalSettings(
    val qualityMode: ChatQualityMode,
    val imageTokenBudget: Int,
)

data class UserTurn(
    val conversationId: String,
    val text: String,
    val attachments: List<AttachmentContext> = emptyList(),
)

data class ChatTurn(
    val message: ChatMessage,
    val attachments: List<AttachmentContext> = emptyList(),
)

data class GenerationSettings(
    val contextSize: Int = 4096,
    val maxNewTokens: Int = 512,
    val temperature: Float = 0.3f,
    val thinkingEnabled: Boolean = false,
    val systemPrompt: String = "You are a helpful, concise assistant.",
) {
    fun normalized(): GenerationSettings = copy(
        contextSize = contextSize.coerceIn(1024, 8192),
        maxNewTokens = maxNewTokens.coerceIn(64, 2048),
        temperature = temperature.coerceIn(0f, 2f),
        systemPrompt = systemPrompt.trim().ifEmpty { "You are a helpful, concise assistant." },
    )
}

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
    val status: MessageStatus,
)

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val qualityMode: ChatQualityMode = ChatQualityMode.FAST,
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
    data class Error(val message: String) : InferenceState
}

data class InferenceMetrics(
    val modelLoadMillis: Long? = null,
    val historyRestoreMillis: Long? = null,
    val promptEvaluationMillis: Long? = null,
    val firstTokenMillis: Long? = null,
)
