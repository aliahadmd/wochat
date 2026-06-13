package com.aliahad.aichat.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.AttachmentProcessingState
import com.aliahad.aichat.core.ChatQualityMode
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.core.ActionRisk
import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.core.DeviceActionKind
import com.aliahad.aichat.core.GenerationStopReason
import com.aliahad.aichat.core.MemorySensitivity
import com.aliahad.aichat.core.MemorySourceKind
import com.aliahad.aichat.core.MemoryStatus
import com.aliahad.aichat.core.MemoryType
import com.aliahad.aichat.core.ContextVerificationState
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.TurnOrigin

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val qualityMode: ChatQualityMode = ChatQualityMode.FAST,
    val temporary: Boolean = false,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index(value = ["conversationId", "createdAt"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
    val status: MessageStatus,
    @ColumnInfo(defaultValue = "'TYPED'")
    val origin: TurnOrigin = TurnOrigin.TYPED,
    val stopReason: GenerationStopReason? = null,
    val continuationCount: Int = 0,
    val promptTokens: Int? = null,
    val generatedTokens: Int? = null,
)

@Entity(
    tableName = "skills",
    indices = [Index("updatedAt"), Index("enabled")],
)
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long?,
)

@Entity(
    tableName = "message_skill_invocations",
    primaryKeys = ["messageId", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId"), Index("skillId")],
)
data class MessageSkillInvocationEntity(
    val messageId: String,
    val skillId: String?,
    val ordinal: Int,
    val snapshotName: String,
    val snapshotDescription: String,
    val snapshotInstructions: String,
    val createdAt: Long,
)

@Entity(tableName = "models")
data class ModelRecordEntity(
    @PrimaryKey val id: String,
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

@Entity(
    tableName = "model_context_profiles",
    indices = [
        Index("modelId"),
        Index(value = ["modelSha256", "deviceFingerprint"], unique = true),
    ],
)
data class ModelContextProfileEntity(
    @PrimaryKey val id: String,
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

@Entity(tableName = "projectors", indices = [Index("modelId", unique = true)])
data class ProjectorRecordEntity(
    @PrimaryKey val id: String,
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

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index("draftKey"), Index("createdAt")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val conversationId: String?,
    val draftKey: String?,
    val displayName: String,
    val mimeType: String,
    val kind: AttachmentKind,
    val originalPath: String,
    val previewPath: String?,
    val derivedImagePaths: String,
    val byteSize: Long,
    val pageCount: Int?,
    val selectedPages: String,
    val imageTokenBudget: Int?,
    val state: AttachmentProcessingState,
    val progress: Float,
    val error: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "attachment_chunks",
    foreignKeys = [
        ForeignKey(
            entity = AttachmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["attachmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("attachmentId"), Index(value = ["attachmentId", "ordinal"], unique = true)],
)
data class AttachmentChunkEntity(
    @PrimaryKey val id: String,
    val attachmentId: String,
    val ordinal: Int,
    val pageNumber: Int?,
    val label: String?,
    val content: String,
)

@Entity(
    tableName = "message_attachments",
    primaryKeys = ["messageId", "attachmentId"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AttachmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["attachmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId"), Index("attachmentId")],
)
data class MessageAttachmentEntity(
    val messageId: String,
    val attachmentId: String,
    val ordinal: Int,
)

@Entity(
    tableName = "conversation_summaries",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId", unique = true)],
)
data class ConversationSummaryEntity(
    @PrimaryKey val conversationId: String,
    val throughMessageId: String?,
    val content: String,
    val tokenCount: Int,
    val updatedAt: Long,
)

@Entity(
    tableName = "memory_items",
    indices = [
        Index("type"),
        Index("status"),
        Index("updatedAt"),
        Index("supersedesId"),
        Index("searchRowId", unique = true),
        Index("contentHash", unique = true),
    ],
)
data class MemoryItemEntity(
    @PrimaryKey val id: String,
    val searchRowId: Long,
    val type: MemoryType,
    val title: String,
    val content: String,
    val normalizedContent: String,
    val contentHash: String,
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

@Entity(
    tableName = "memory_sources",
    foreignKeys = [
        ForeignKey(
            entity = MemoryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("memoryId"), Index(value = ["kind", "sourceId"])],
)
data class MemorySourceEntity(
    @PrimaryKey val id: String,
    val memoryId: String,
    val kind: MemorySourceKind,
    val sourceId: String?,
    val label: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "memory_corrections",
    foreignKeys = [
        ForeignKey(
            entity = MemoryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["memoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("memoryId"), Index("createdAt")],
)
data class MemoryCorrectionEntity(
    @PrimaryKey val id: String,
    val memoryId: String,
    val previousContent: String,
    val correctedContent: String,
    val reason: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "activity_events",
    indices = [
        Index("source"),
        Index("startedAt"),
        Index("packageName"),
        Index("compactedIntoId"),
    ],
)
data class ActivityEventEntity(
    @PrimaryKey val id: String,
    val source: ActivitySource,
    val eventType: String,
    val startedAt: Long,
    val endedAt: Long?,
    val packageName: String?,
    val title: String?,
    val redactedText: String?,
    val metadataJson: String,
    val sensitivity: MemorySensitivity,
    val pinned: Boolean = false,
    val compactedIntoId: String?,
    val createdAt: Long,
)

data class ActivitySourceStatsRow(
    val source: ActivitySource,
    val eventCount: Long,
    val lastEventAt: Long?,
)

@Entity(tableName = "memory_summaries", indices = [Index("periodStart"), Index("source")])
data class MemorySummaryEntity(
    @PrimaryKey val id: String,
    val source: ActivitySource?,
    val periodStart: Long,
    val periodEnd: Long,
    val content: String,
    val eventCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "collector_checkpoints")
data class CollectorCheckpointEntity(
    @PrimaryKey val collector: String,
    val cursor: String?,
    val lastCollectedAt: Long,
    val lastCompactedAt: Long?,
    val error: String?,
)

@Entity(tableName = "action_audits", indices = [Index("createdAt"), Index("packageName")])
data class ActionAuditEntity(
    @PrimaryKey val id: String,
    val actionKind: DeviceActionKind,
    val packageName: String?,
    val target: String?,
    val risk: ActionRisk,
    val planJson: String,
    val result: String?,
    val success: Boolean?,
    val createdAt: Long,
    val completedAt: Long?,
)
