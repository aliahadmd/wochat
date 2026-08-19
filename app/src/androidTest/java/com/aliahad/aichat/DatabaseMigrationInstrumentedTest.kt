package com.aliahad.aichat

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aliahad.aichat.core.ChatQualityMode
import com.aliahad.aichat.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrationOneToTwentyPreservesChatsAndRemovesRetiredTables() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                "INSERT INTO conversations(id, title, createdAt, updatedAt) " +
                    "VALUES('chat', 'Existing chat', 1, 1)",
            )
            execSQL(
                "INSERT INTO messages(id, conversationId, role, content, createdAt, status) " +
                    "VALUES('message', 'chat', 'USER', 'Keep me', 2, 'COMPLETE')",
            )
            close()
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17,
                AppDatabase.MIGRATION_17_18,
                AppDatabase.MIGRATION_18_19,
                AppDatabase.MIGRATION_19_20,
            )
            .build()
        try {
            database.openHelper.writableDatabase
            val conversation = kotlinx.coroutines.runBlocking {
                database.conversationDao().get("chat")
            }
            assertEquals("Existing chat", conversation?.title)
            assertEquals(ChatQualityMode.FAST, conversation?.qualityMode)
            assertEquals(
                1,
                kotlinx.coroutines.runBlocking {
                    database.messageDao().getForConversation("chat").size
                },
            )
            assertEquals(
                0,
                database.openHelper.writableDatabase.query(
                    "SELECT count(*) FROM memory_items",
                ).use {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
            assertEquals(
                "TYPED",
                database.openHelper.writableDatabase.query(
                    "SELECT origin FROM messages WHERE id = 'message'",
                ).use {
                    it.moveToFirst()
                    it.getString(0)
                },
            )
            assertEquals(
                0,
                database.openHelper.writableDatabase.query(
                    "SELECT count(*) FROM model_context_profiles",
                ).use {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
            assertEquals(
                0,
                database.openHelper.writableDatabase.query(
                    "SELECT count(*) FROM skills",
                ).use {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
            assertEquals(
                0,
                database.openHelper.writableDatabase.query(
                    "SELECT count(*) FROM message_skill_invocations",
                ).use {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
            assertEquals(
                0,
                database.openHelper.writableDatabase.query(
                    "SELECT count(*) FROM model_benchmarks",
                ).use { it.moveToFirst(); it.getInt(0) },
            )
            assertEquals(false, database.openHelper.writableDatabase.tableExists("daily_briefs"))
            assertEquals(false, database.openHelper.writableDatabase.tableExists("action_audits"))
            assertEquals(false, database.openHelper.writableDatabase.tableExists("embedding_artifacts"))
            assertEquals(false, database.openHelper.writableDatabase.tableExists("memory_embeddings"))
        } finally {
            database.close()
            context.deleteDatabase(DATABASE_NAME)
        }
    }

    @Test
    fun migrationElevenToTwelveAddsNullableAudioDuration() {
        val name = "migration-11-12"
        helper.createDatabase(name, 11).apply {
            insertLegacyAttachment("audio-before-v12", "recording.wav", "TEXT")
            close()
        }

        helper.runMigrationsAndValidate(name, 12, true, AppDatabase.MIGRATION_11_12).use { database ->
            database.query(
                "SELECT displayName, durationMillis FROM attachments WHERE id = 'audio-before-v12'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("recording.wav", cursor.getString(0))
                assertEquals(true, cursor.isNull(1))
            }
        }
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
    }

    @Test
    fun migrationNineToTwentyPreservesAttachmentAndRemovesRetiredTables() {
        val name = "migration-9-16"
        helper.createDatabase(name, 9).apply {
            insertLegacyAttachment("existing-attachment", "keep.txt", "TEXT")
            close()
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17,
                AppDatabase.MIGRATION_17_18,
                AppDatabase.MIGRATION_18_19,
                AppDatabase.MIGRATION_19_20,
            )
            .build()
        try {
            database.openHelper.writableDatabase.query(
                "SELECT displayName, durationMillis FROM attachments WHERE id = 'existing-attachment'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("keep.txt", cursor.getString(0))
                assertEquals(true, cursor.isNull(1))
            }
            assertEquals(false, database.openHelper.writableDatabase.tableExists("action_audits"))
            assertEquals(false, database.openHelper.writableDatabase.tableExists("daily_briefs"))
            assertEquals(false, database.openHelper.writableDatabase.tableExists("embedding_artifacts"))
            assertEquals(false, database.openHelper.writableDatabase.tableExists("memory_embeddings"))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrationSixteenToSeventeenDropsMemorySearchRowId() {
        val name = "migration-16-17"
        helper.createDatabase(name, 16).apply {
            execSQL(
                "INSERT INTO memory_items(" +
                    "id, searchRowId, type, title, content, normalizedContent, contentHash, " +
                    "confidence, importance, sensitivity, status, pinned, validFrom, validTo, " +
                    "supersedesId, createdAt, updatedAt" +
                    ") VALUES('kept', 11, 'FACT', 'Kept memory', 'Keep me', 'keep me', " +
                    "'hash-kept', 1.0, 0.9, 'PRIVATE', 'ACTIVE', 0, 5, NULL, NULL, 5, 5)",
            )
            execSQL(
                "INSERT INTO memory_items(" +
                    "id, searchRowId, type, title, content, normalizedContent, contentHash, " +
                    "confidence, importance, sensitivity, status, pinned, validFrom, validTo, " +
                    "supersedesId, createdAt, updatedAt" +
                    ") VALUES('old', 22, 'FACT', 'Old memory', 'Drop me later', 'drop me later', " +
                    "'hash-old', 1.0, 0.4, 'PRIVATE', 'SUPERSEDED', 0, 5, NULL, 'kept', 5, 5)",
            )
            close()
        }

        helper.runMigrationsAndValidate(name, 17, true, AppDatabase.MIGRATION_16_17).use { database ->
            database.query("SELECT id, title FROM memory_items ORDER BY id").use { cursor ->
                assertEquals(2, cursor.count)
                cursor.moveToFirst()
                assertEquals("kept", cursor.getString(0))
                assertEquals("Kept memory", cursor.getString(1))
            }
            val columns = mutableSetOf<String>()
            database.query("PRAGMA table_info(memory_items)").use { cursor ->
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(1))
                }
            }
            assertEquals(false, "searchRowId" in columns)
            val indexPresent = database.query(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_memory_items_searchRowId'",
            ).use { it.moveToFirst() }
            assertEquals(false, indexPresent)
        }
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertLegacyAttachment(
        id: String,
        displayName: String,
        kind: String,
    ) {
        execSQL(
            "INSERT INTO attachments(" +
                "id, conversationId, draftKey, displayName, mimeType, kind, originalPath, previewPath, " +
                "derivedImagePaths, byteSize, pageCount, selectedPages, imageTokenBudget, state, progress, " +
                "error, createdAt" +
                ") VALUES(?, NULL, 'draft', ?, 'text/plain', ?, '/tmp/source', NULL, '', 10, NULL, '', " +
                "NULL, 'READY', 1.0, NULL, 1)",
            arrayOf(id, displayName, kind),
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.tableExists(name: String): Boolean =
        query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name))
            .use { it.moveToFirst() }

    /**
     * Plan 033 removed phone sources. Dropping the event tables alone was not
     * enough: collected events had already been compacted into ordinary
     * memory_items rows, which would have kept being retrieved and injected into
     * prompts. A memory that also carries a non-ACTIVITY source is the user's own
     * content and must survive.
     */
    @Test
    fun migrationSeventeenToEighteenDropsActivityTablesAndPurelyCollectedMemories() {
        helper.createDatabase(DATABASE_NAME, 17).apply {
            execSQL(
                "INSERT INTO memory_items(id, type, title, content, normalizedContent, " +
                    "contentHash, confidence, importance, sensitivity, status, pinned, " +
                    "validFrom, validTo, supersedesId, createdAt, updatedAt) VALUES" +
                    "('collected', 'EPISODE', 'usage archive', 'scraped', 'scraped', " +
                    "'h1', 0.9, 0.5, 'PRIVATE', 'ACTIVE', 0, NULL, NULL, NULL, 1, 1)",
            )
            execSQL(
                "INSERT INTO memory_items(id, type, title, content, normalizedContent, " +
                    "contentHash, confidence, importance, sensitivity, status, pinned, " +
                    "validFrom, validTo, supersedesId, createdAt, updatedAt) VALUES" +
                    "('mixed', 'FACT', 'mine', 'user fact', 'user fact', " +
                    "'h2', 0.9, 0.5, 'NORMAL', 'ACTIVE', 0, NULL, NULL, NULL, 1, 1)",
            )
            execSQL(
                "INSERT INTO memory_sources(id, memoryId, kind, sourceId, label, createdAt) " +
                    "VALUES('s1', 'collected', 'ACTIVITY', 'sum1', 'ACCESSIBILITY', 1)",
            )
            execSQL(
                "INSERT INTO memory_sources(id, memoryId, kind, sourceId, label, createdAt) " +
                    "VALUES('s2', 'mixed', 'ACTIVITY', 'sum2', 'CALENDAR', 1)",
            )
            execSQL(
                "INSERT INTO memory_sources(id, memoryId, kind, sourceId, label, createdAt) " +
                    "VALUES('s3', 'mixed', 'CHAT_MESSAGE', 'msg1', 'Chat message', 1)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            18,
            true,
            AppDatabase.MIGRATION_17_18,
        )
        fun count(sql: String) = migrated.query(sql).use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals(0, count("SELECT count(*) FROM memory_items WHERE id = 'collected'"))
        assertEquals(1, count("SELECT count(*) FROM memory_items WHERE id = 'mixed'"))
        assertEquals(
            0,
            count(
                "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN " +
                    "('activity_events', 'memory_summaries', 'collector_checkpoints')",
            ),
        )
        migrated.close()
    }

    /**
     * 18 -> 19 adds the memory embedding column. Nullable on purpose: existing rows
     * are backfilled in the background rather than blocking the upgrade on a forward
     * pass per memory.
     */
    @Test
    fun migrationEighteenToNineteenAddsNullableEmbeddingColumn() {
        helper.createDatabase(DATABASE_NAME, 18).apply {
            execSQL(
                "INSERT INTO memory_items(id, type, title, content, normalizedContent, " +
                    "contentHash, confidence, importance, sensitivity, status, pinned, " +
                    "validFrom, validTo, supersedesId, createdAt, updatedAt) VALUES" +
                    "('existing', 'FACT', 'mine', 'user fact', 'user fact', " +
                    "'h1', 0.9, 0.5, 'NORMAL', 'ACTIVE', 0, NULL, NULL, NULL, 1, 1)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            19,
            true,
            AppDatabase.MIGRATION_18_19,
        )
        migrated.query("SELECT embedding FROM memory_items WHERE id = 'existing'").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals(true, it.isNull(0))
        }
        migrated.close()
    }

    /**
     * 19 -> 20 repairs what 17 -> 18 could not cascade (foreign keys are off during
     * a migration): orphaned memory_sources/-corrections left behind made every
     * backup unimportable, and ACTIVITY source rows spared on mixed-source
     * memories crashed the MemorySourceKind enum read. It also adds the summary
     * coverage timestamp, backfilled from the message the summary reached.
     */
    @Test
    fun migrationNineteenToTwentyRepairsOrphansActivitySourcesAndSummaryCoverage() {
        helper.createDatabase(DATABASE_NAME, 19).apply {
            execSQL(
                "INSERT INTO memory_items(id, type, title, content, normalizedContent, " +
                    "contentHash, confidence, importance, sensitivity, status, pinned, " +
                    "validFrom, validTo, supersedesId, createdAt, updatedAt) VALUES" +
                    "('kept', 'FACT', 'mine', 'user fact', 'user fact', " +
                    "'h2', 0.9, 0.5, 'NORMAL', 'ACTIVE', 0, NULL, NULL, NULL, 1, 1)",
            )
            // Leftover children of an ACTIVITY memory 17 -> 18 deleted: the cascade
            // never fired because foreign keys are off until after onUpgrade.
            execSQL(
                "INSERT INTO memory_sources(id, memoryId, kind, sourceId, label, createdAt) " +
                    "VALUES('orphan-source', 'gone', 'ACTIVITY', 'sum1', 'CALENDAR', 1)",
            )
            execSQL(
                "INSERT INTO memory_corrections(id, memoryId, previousContent, " +
                    "correctedContent, reason, createdAt) " +
                    "VALUES('orphan-correction', 'gone', 'a', 'b', NULL, 1)",
            )
            // An ACTIVITY source on a spared mixed-source memory.
            execSQL(
                "INSERT INTO memory_sources(id, memoryId, kind, sourceId, label, createdAt) " +
                    "VALUES('activity-source', 'kept', 'ACTIVITY', 'sum2', 'CALENDAR', 1)",
            )
            execSQL(
                "INSERT INTO memory_sources(id, memoryId, kind, sourceId, label, createdAt) " +
                    "VALUES('chat-source', 'kept', 'CHAT_MESSAGE', 'msg1', 'Chat message', 1)",
            )
            execSQL(
                "INSERT INTO conversations(id, title, createdAt, updatedAt, qualityMode, temporary) " +
                    "VALUES('chat', 'Chat', 1, 1, 'FAST', 0)",
            )
            execSQL(
                "INSERT INTO messages(id, conversationId, role, content, createdAt, status, " +
                    "continuationCount) VALUES('msg1', 'chat', 'USER', 'hello', 42, 'COMPLETE', 0)",
            )
            execSQL(
                "INSERT INTO conversation_summaries(conversationId, throughMessageId, content, " +
                    "tokenCount, updatedAt) VALUES('chat', 'msg1', 'User said hello.', 5, 1)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            20,
            true,
            AppDatabase.MIGRATION_19_20,
        )
        fun count(sql: String) = migrated.query(sql).use {
            it.moveToFirst()
            it.getInt(0)
        }
        assertEquals(1, count("SELECT count(*) FROM memory_items WHERE id = 'kept'"))
        assertEquals(0, count("SELECT count(*) FROM memory_sources WHERE id = 'orphan-source'"))
        assertEquals(0, count("SELECT count(*) FROM memory_corrections WHERE id = 'orphan-correction'"))
        assertEquals(0, count("SELECT count(*) FROM memory_sources WHERE kind = 'ACTIVITY'"))
        assertEquals(1, count("SELECT count(*) FROM memory_sources WHERE id = 'chat-source'"))
        migrated.query("SELECT throughCreatedAt FROM conversation_summaries WHERE conversationId = 'chat'")
            .use {
                assertEquals(1, it.count)
                it.moveToFirst()
                assertEquals(42L, it.getLong(0))
            }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "migration-test"
    }
}
