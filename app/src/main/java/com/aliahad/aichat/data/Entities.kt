package com.aliahad.aichat.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.AttachmentProcessingState
import com.aliahad.aichat.core.ChatQualityMode
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val qualityMode: ChatQualityMode = ChatQualityMode.FAST,
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
