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
import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.core.GenerationStopReason
import com.aliahad.aichat.core.MemorySensitivity
import com.aliahad.aichat.core.MemorySourceKind
import com.aliahad.aichat.core.MemoryStatus
import com.aliahad.aichat.core.MemoryType
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.core.ContextVerificationState
import com.aliahad.aichat.core.TurnOrigin

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
    @TypeConverter fun fromGenerationStopReason(value: GenerationStopReason?): String? = value?.name
    @TypeConverter fun toGenerationStopReason(value: String?): GenerationStopReason? =
        value?.let(GenerationStopReason::valueOf)
    @TypeConverter fun fromMemoryType(value: MemoryType): String = value.name
    @TypeConverter fun toMemoryType(value: String): MemoryType = MemoryType.valueOf(value)
    @TypeConverter fun fromMemorySensitivity(value: MemorySensitivity): String = value.name
    @TypeConverter fun toMemorySensitivity(value: String): MemorySensitivity = MemorySensitivity.valueOf(value)
    @TypeConverter fun fromMemoryStatus(value: MemoryStatus): String = value.name
    @TypeConverter fun toMemoryStatus(value: String): MemoryStatus = MemoryStatus.valueOf(value)
    @TypeConverter fun fromMemorySourceKind(value: MemorySourceKind): String = value.name
    @TypeConverter fun toMemorySourceKind(value: String): MemorySourceKind = MemorySourceKind.valueOf(value)
    @TypeConverter fun fromActivitySource(value: ActivitySource?): String? = value?.name
    @TypeConverter fun toActivitySource(value: String?): ActivitySource? = value?.let(ActivitySource::valueOf)
    @TypeConverter fun fromContextVerificationState(value: ContextVerificationState): String = value.name
    @TypeConverter fun toContextVerificationState(value: String): ContextVerificationState =
        ContextVerificationState.valueOf(value)
    @TypeConverter fun fromTurnOrigin(value: TurnOrigin): String = value.name
    @TypeConverter fun toTurnOrigin(value: String): TurnOrigin =
        if (value == "VOICE") TurnOrigin.TYPED else TurnOrigin.valueOf(value)
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        SkillEntity::class,
        MessageSkillInvocationEntity::class,
        ModelRecordEntity::class,
        ModelContextProfileEntity::class,
        ProjectorRecordEntity::class,
        AttachmentEntity::class,
        AttachmentChunkEntity::class,
        MessageAttachmentEntity::class,
        ConversationSummaryEntity::class,
        MemoryItemEntity::class,
        MemorySourceEntity::class,
        MemoryCorrectionEntity::class,
        ActivityEventEntity::class,
        MemorySummaryEntity::class,
        CollectorCheckpointEntity::class,
        ModelBenchmarkEntity::class,
    ],
    version = 16,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun skillDao(): SkillDao
    abstract fun modelDao(): ModelDao
    abstract fun modelContextProfileDao(): ModelContextProfileDao
    abstract fun projectorDao(): ProjectorDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun conversationSummaryDao(): ConversationSummaryDao
    abstract fun memoryDao(): MemoryDao
    abstract fun activityDao(): ActivityDao
    abstract fun modelBenchmarkDao(): ModelBenchmarkDao
    abstract fun backupImportInvalidationDao(): BackupImportInvalidationDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN temporary INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN stopReason TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN continuationCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN promptTokens INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN generatedTokens INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS conversation_summaries (" +
                        "conversationId TEXT NOT NULL PRIMARY KEY, throughMessageId TEXT, " +
                        "content TEXT NOT NULL, tokenCount INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_conversation_summaries_conversationId " +
                        "ON conversation_summaries(conversationId)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memory_items (" +
                        "id TEXT NOT NULL PRIMARY KEY, searchRowId INTEGER NOT NULL, type TEXT NOT NULL, " +
                        "title TEXT NOT NULL, content TEXT NOT NULL, normalizedContent TEXT NOT NULL, " +
                        "contentHash TEXT NOT NULL, confidence REAL NOT NULL, importance REAL NOT NULL, " +
                        "sensitivity TEXT NOT NULL, status TEXT NOT NULL, pinned INTEGER NOT NULL, " +
                        "validFrom INTEGER, validTo INTEGER, supersedesId TEXT, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_items_type ON memory_items(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_items_status ON memory_items(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_items_updatedAt ON memory_items(updatedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_items_supersedesId ON memory_items(supersedesId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_memory_items_searchRowId ON memory_items(searchRowId)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_memory_items_contentHash ON memory_items(contentHash)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memory_sources (" +
                        "id TEXT NOT NULL PRIMARY KEY, memoryId TEXT NOT NULL, kind TEXT NOT NULL, " +
                        "sourceId TEXT, label TEXT, createdAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(memoryId) REFERENCES memory_items(id) ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_sources_memoryId ON memory_sources(memoryId)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_memory_sources_kind_sourceId " +
                        "ON memory_sources(kind, sourceId)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memory_corrections (" +
                        "id TEXT NOT NULL PRIMARY KEY, memoryId TEXT NOT NULL, previousContent TEXT NOT NULL, " +
                        "correctedContent TEXT NOT NULL, reason TEXT, createdAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(memoryId) REFERENCES memory_items(id) ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_memory_corrections_memoryId ON memory_corrections(memoryId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_memory_corrections_createdAt ON memory_corrections(createdAt)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS activity_events (" +
                        "id TEXT NOT NULL PRIMARY KEY, source TEXT NOT NULL, eventType TEXT NOT NULL, " +
                        "startedAt INTEGER NOT NULL, endedAt INTEGER, packageName TEXT, title TEXT, " +
                        "redactedText TEXT, metadataJson TEXT NOT NULL, sensitivity TEXT NOT NULL, " +
                        "pinned INTEGER NOT NULL DEFAULT 0, " +
                        "compactedIntoId TEXT, createdAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_events_source ON activity_events(source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_events_startedAt ON activity_events(startedAt)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_activity_events_packageName ON activity_events(packageName)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_activity_events_compactedIntoId " +
                        "ON activity_events(compactedIntoId)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memory_summaries (" +
                        "id TEXT NOT NULL PRIMARY KEY, source TEXT, periodStart INTEGER NOT NULL, " +
                        "periodEnd INTEGER NOT NULL, content TEXT NOT NULL, eventCount INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_memory_summaries_periodStart " +
                        "ON memory_summaries(periodStart)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_memory_summaries_source ON memory_summaries(source)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS collector_checkpoints (" +
                        "collector TEXT NOT NULL PRIMARY KEY, cursor TEXT, lastCollectedAt INTEGER NOT NULL, " +
                        "lastCompactedAt INTEGER, error TEXT)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS action_audits (" +
                        "id TEXT NOT NULL PRIMARY KEY, actionKind TEXT NOT NULL, packageName TEXT, " +
                        "target TEXT, risk TEXT NOT NULL, planJson TEXT NOT NULL, result TEXT, " +
                        "success INTEGER, createdAt INTEGER NOT NULL, completedAt INTEGER)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_action_audits_createdAt ON action_audits(createdAt)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_action_audits_packageName ON action_audits(packageName)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS model_context_profiles (" +
                        "id TEXT NOT NULL PRIMARY KEY, modelId TEXT NOT NULL, " +
                        "modelSha256 TEXT NOT NULL, deviceFingerprint TEXT NOT NULL, " +
                        "physicalRamBytes INTEGER NOT NULL, swapBytes INTEGER NOT NULL, " +
                        "backend TEXT NOT NULL, llamaRevision TEXT NOT NULL, " +
                        "declaredContextTokens INTEGER NOT NULL, " +
                        "verifiedContextTokens INTEGER NOT NULL, " +
                        "lastAttemptedTokens INTEGER, state TEXT NOT NULL, " +
                        "peakPssBytes INTEGER, peakRssBytes INTEGER, peakSwapBytes INTEGER, " +
                        "failureReason TEXT, verifiedAt INTEGER, updatedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_model_context_profiles_modelId " +
                        "ON model_context_profiles(modelId)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_model_context_profiles_modelSha256_deviceFingerprint " +
                        "ON model_context_profiles(modelSha256, deviceFingerprint)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN origin TEXT NOT NULL DEFAULT 'TYPED'",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS speech_assets (" +
                        "id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, " +
                        "displayName TEXT NOT NULL, archiveFileName TEXT NOT NULL, " +
                        "localPath TEXT, sourceUrl TEXT NOT NULL, expectedBytes INTEGER NOT NULL, " +
                        "sha256 TEXT NOT NULL, downloadedBytes INTEGER NOT NULL, " +
                        "status TEXT NOT NULL, error TEXT)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_speech_assets_kind " +
                        "ON speech_assets(kind)",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS skills (" +
                        "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                        "description TEXT NOT NULL, instructions TEXT NOT NULL, " +
                        "enabled INTEGER NOT NULL DEFAULT 1, createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, lastUsedAt INTEGER)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_updatedAt ON skills(updatedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_enabled ON skills(enabled)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS message_skill_invocations (" +
                        "messageId TEXT NOT NULL, skillId TEXT, ordinal INTEGER NOT NULL, " +
                        "snapshotName TEXT NOT NULL, snapshotDescription TEXT NOT NULL, " +
                        "snapshotInstructions TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(messageId, ordinal), " +
                        "FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_message_skill_invocations_messageId " +
                        "ON message_skill_invocations(messageId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_message_skill_invocations_skillId " +
                        "ON message_skill_invocations(skillId)",
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_speech_assets_kind")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_speech_assets_kind ON speech_assets(kind)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_speech_assets_kind")
                db.execSQL("DROP TABLE IF EXISTS speech_assets")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE messages SET origin = 'TYPED' WHERE origin = 'VOICE'")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE models ADD COLUMN bytesPerSecond INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE models ADD COLUMN etaSeconds INTEGER")
                db.execSQL("ALTER TABLE models ADD COLUMN retryAttempt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE projectors ADD COLUMN bytesPerSecond INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE projectors ADD COLUMN etaSeconds INTEGER")
                db.execSQL("ALTER TABLE projectors ADD COLUMN retryAttempt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "DROP INDEX IF EXISTS " +
                        "index_model_context_profiles_modelSha256_deviceFingerprint",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_model_context_profiles_modelSha256_deviceFingerprint_backend " +
                        "ON model_context_profiles(modelSha256, deviceFingerprint, backend)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS model_benchmarks (" +
                        "id TEXT NOT NULL PRIMARY KEY, modelId TEXT NOT NULL, " +
                        "modelSha256 TEXT NOT NULL, deviceFingerprint TEXT NOT NULL, " +
                        "backend TEXT NOT NULL, llamaRevision TEXT NOT NULL, " +
                        "loadMillis INTEGER, promptTokensPerSecond REAL, " +
                        "generationTokensPerSecond REAL, peakPssBytes INTEGER, " +
                        "thermalDelta INTEGER, success INTEGER NOT NULL, " +
                        "failureReason TEXT, measuredAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_model_benchmarks_modelId " +
                        "ON model_benchmarks(modelId)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_model_benchmarks_modelSha256_deviceFingerprint_backend_llamaRevision " +
                        "ON model_benchmarks(modelSha256, deviceFingerprint, backend, llamaRevision)",
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS daily_briefs (" +
                        "id TEXT NOT NULL PRIMARY KEY, localDate TEXT NOT NULL, " +
                        "windowStart INTEGER NOT NULL, windowEnd INTEGER NOT NULL, " +
                        "contentJson TEXT NOT NULL, sourceCountsJson TEXT NOT NULL, " +
                        "status TEXT NOT NULL, modelSha256 TEXT, promptVersion INTEGER NOT NULL, " +
                        "generatedAt INTEGER, createdAt INTEGER NOT NULL, failureReason TEXT)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_daily_briefs_localDate " +
                        "ON daily_briefs(localDate)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_daily_briefs_generatedAt " +
                        "ON daily_briefs(generatedAt)",
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attachments ADD COLUMN durationMillis INTEGER")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE action_audits ADD COLUMN planId TEXT")
                db.execSQL("ALTER TABLE action_audits ADD COLUMN stepOrdinal INTEGER")
                db.execSQL("ALTER TABLE action_audits ADD COLUMN preconditionHash TEXT")
                db.execSQL("ALTER TABLE action_audits ADD COLUMN postconditionHash TEXT")
                db.execSQL("ALTER TABLE action_audits ADD COLUMN confirmedAt INTEGER")
                db.execSQL("ALTER TABLE action_audits ADD COLUMN failureCode TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_action_audits_planId ON action_audits(planId)",
                )
                // Earlier releases stored raw target labels. Remove them during the additive migration.
                db.execSQL("UPDATE action_audits SET target = NULL, planJson = '{\"legacy\":true}'")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS embedding_artifacts (" +
                        "id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, " +
                        "sourceRepo TEXT NOT NULL, revision TEXT NOT NULL, fileName TEXT NOT NULL, " +
                        "localPath TEXT, expectedBytes INTEGER NOT NULL, sha256 TEXT NOT NULL, " +
                        "downloadedBytes INTEGER NOT NULL, status TEXT NOT NULL, error TEXT, " +
                        "bytesPerSecond INTEGER NOT NULL, etaSeconds INTEGER, retryAttempt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memory_embeddings (" +
                        "memoryId TEXT NOT NULL PRIMARY KEY, contentHash TEXT NOT NULL, " +
                        "modelSha256 TEXT NOT NULL, runtimeRevision TEXT NOT NULL, " +
                        "dimension INTEGER NOT NULL, promptVersion INTEGER NOT NULL, " +
                        "vector BLOB NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(memoryId) REFERENCES memory_items(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_memory_embeddings_modelSha256 " +
                        "ON memory_embeddings(modelSha256)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_memory_embeddings_modelSha256_runtimeRevision_dimension_promptVersion " +
                        "ON memory_embeddings(modelSha256, runtimeRevision, dimension, promptVersion)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_memory_embeddings_updatedAt " +
                        "ON memory_embeddings(updatedAt)",
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS action_audits")
                db.execSQL("DROP TABLE IF EXISTS daily_briefs")
                db.execSQL("DELETE FROM projectors WHERE id != 'google-gemma-4-e4b-mmproj'")
                db.execSQL("DELETE FROM models WHERE id != 'google-gemma-4-e4b-it-q4'")
                db.execSQL(
                    "DELETE FROM model_context_profiles " +
                        "WHERE modelId != 'google-gemma-4-e4b-it-q4'",
                )
                db.execSQL(
                    "DELETE FROM model_benchmarks " +
                        "WHERE modelId != 'google-gemma-4-e4b-it-q4'",
                )
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS memory_embeddings")
                db.execSQL("DROP TABLE IF EXISTS embedding_artifacts")
            }
        }

        fun create(context: Context): AppDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = DatabaseKeyManager(context).passphrase()
            val migrator = DatabaseEncryptionMigrator(context, DATABASE_NAME)
            migrator.migratePlaintextIfNeeded(passphrase)
            val database = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                context.getDatabasePath(DATABASE_NAME).absolutePath,
            )
                .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                )
                .build()
            migrator.sweepResidueFromFailedMigration()
            database.openHelper.writableDatabase
            migrator.finishVerifiedMigration()
            passphrase.fill(0)
            return database
        }

        const val DATABASE_NAME = "aichat.db"
        const val VERSION = 16
    }
}
