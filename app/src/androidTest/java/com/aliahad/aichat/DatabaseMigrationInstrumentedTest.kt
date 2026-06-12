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
    fun migrationOneToFourPreservesChatsAndAddsContextProfiles() {
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
                0,
                database.openHelper.writableDatabase.query(
                    "SELECT count(*) FROM model_context_profiles",
                ).use {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
        } finally {
            database.close()
            context.deleteDatabase(DATABASE_NAME)
        }
    }

    private companion object {
        const val DATABASE_NAME = "migration-test"
    }
}
