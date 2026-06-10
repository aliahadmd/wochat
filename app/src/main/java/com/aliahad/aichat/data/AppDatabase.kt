package com.aliahad.aichat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.AttachmentProcessingState
import com.aliahad.aichat.core.ChatQualityMode
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus

class DatabaseConverters {
    @TypeConverter fun fromMessageRole(value: MessageRole): String = value.name
    @TypeConverter fun toMessageRole(value: String): MessageRole = MessageRole.valueOf(value)
    @TypeConverter fun fromMessageStatus(value: MessageStatus): String = value.name
    @TypeConverter fun toMessageStatus(value: String): MessageStatus = MessageStatus.valueOf(value)
    @TypeConverter fun fromDownloadStatus(value: DownloadStatus): String = value.name
    @TypeConverter fun toDownloadStatus(value: String): DownloadStatus = DownloadStatus.valueOf(value)
    @TypeConverter fun fromChatQualityMode(value: ChatQualityMode): String = value.name
    @TypeConverter fun toChatQualityMode(value: String): ChatQualityMode = ChatQualityMode.valueOf(value)
    @TypeConverter fun fromAttachmentKind(value: AttachmentKind): String = value.name
    @TypeConverter fun toAttachmentKind(value: String): AttachmentKind = AttachmentKind.valueOf(value)
    @TypeConverter fun fromAttachmentState(value: AttachmentProcessingState): String = value.name
    @TypeConverter fun toAttachmentState(value: String): AttachmentProcessingState =
        AttachmentProcessingState.valueOf(value)
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ModelRecordEntity::class,
        ProjectorRecordEntity::class,
        AttachmentEntity::class,
        AttachmentChunkEntity::class,
        MessageAttachmentEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun modelDao(): ModelDao
    abstract fun projectorDao(): ProjectorDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN qualityMode TEXT NOT NULL DEFAULT 'FAST'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS projectors (" +
                        "id TEXT NOT NULL PRIMARY KEY, modelId TEXT NOT NULL, displayName TEXT NOT NULL, " +
                        "fileName TEXT NOT NULL, localPath TEXT, sourceRepo TEXT NOT NULL, " +
                        "expectedBytes INTEGER NOT NULL, sha256 TEXT NOT NULL, downloadedBytes INTEGER NOT NULL, " +
                        "status TEXT NOT NULL, error TEXT)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_projectors_modelId ON projectors(modelId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS attachments (" +
                        "id TEXT NOT NULL PRIMARY KEY, conversationId TEXT, draftKey TEXT, displayName TEXT NOT NULL, " +
                        "mimeType TEXT NOT NULL, kind TEXT NOT NULL, originalPath TEXT NOT NULL, previewPath TEXT, " +
                        "derivedImagePaths TEXT NOT NULL, byteSize INTEGER NOT NULL, pageCount INTEGER, " +
                        "selectedPages TEXT NOT NULL, imageTokenBudget INTEGER, state TEXT NOT NULL, " +
                        "progress REAL NOT NULL, error TEXT, createdAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_conversationId ON attachments(conversationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_draftKey ON attachments(draftKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_createdAt ON attachments(createdAt)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS attachment_chunks (" +
                        "id TEXT NOT NULL PRIMARY KEY, attachmentId TEXT NOT NULL, ordinal INTEGER NOT NULL, " +
                        "pageNumber INTEGER, label TEXT, content TEXT NOT NULL, " +
                        "FOREIGN KEY(attachmentId) REFERENCES attachments(id) ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachment_chunks_attachmentId ON attachment_chunks(attachmentId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_attachment_chunks_attachmentId_ordinal " +
                        "ON attachment_chunks(attachmentId, ordinal)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS message_attachments (" +
                        "messageId TEXT NOT NULL, attachmentId TEXT NOT NULL, ordinal INTEGER NOT NULL, " +
                        "PRIMARY KEY(messageId, attachmentId), " +
                        "FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE, " +
                        "FOREIGN KEY(attachmentId) REFERENCES attachments(id) ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_message_attachments_messageId ON message_attachments(messageId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_message_attachments_attachmentId ON message_attachments(attachmentId)")
            }
        }

        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "aichat.db",
        ).addMigrations(MIGRATION_1_2).build()
    }
}
