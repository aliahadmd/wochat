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
    fun migrationOneToSixteenPreservesChatsAndRemovesRetiredTables() {
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
    fun migrationNineToSixteenPreservesAttachmentAndRemovesRetiredTables() {
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

    private companion object {
        const val DATABASE_NAME = "migration-test"
    }
}
