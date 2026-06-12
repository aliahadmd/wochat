package com.aliahad.aichat

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aliahad.aichat.data.AppDatabase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfficeBackupInstrumentedTest {
    @Test
    fun backupStyleRawWritesInvalidateActiveRoomObservers() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val emissions = Channel<Int>(Channel.UNLIMITED)
            val collection = launch {
                database.memoryDao().observeActive().collect { emissions.send(it.size) }
            }
            try {
                assertEquals(0, withTimeout(5_000) { emissions.receive() })
                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put("id", "backup-invalidation-test")
                    put("searchRowId", 4_242_424_242L)
                    put("type", "FACT")
                    put("title", "Backup invalidation")
                    put("content", "Imported through a raw backup transaction")
                    put("normalizedContent", "imported through a raw backup transaction")
                    put("contentHash", "backup-invalidation-test-hash")
                    put("confidence", 1f)
                    put("importance", 1f)
                    put("sensitivity", "NORMAL")
                    put("status", "ACTIVE")
                    put("pinned", false)
                    put("validFrom", now)
                    putNull("validTo")
                    putNull("supersedesId")
                    put("createdAt", now)
                    put("updatedAt", now)
                }
                database.openHelper.writableDatabase.insert(
                    "memory_items",
                    SQLiteDatabase.CONFLICT_FAIL,
                    values,
                )
                database.backupImportInvalidationDao().notifyImportedTables()

                assertEquals(1, withTimeout(5_000) { emissions.receive() })
            } finally {
                collection.cancel()
                database.close()
            }
        }
    }

    @Test
    fun encryptedOfficeRoundTripPreservesAndDeduplicatesMemories() {
        runBlocking {
            val application = ApplicationProvider.getApplicationContext<AiChatApplication>()
            val repository = application.container.officeBackupRepository
            val before = application.container.memoryRepository.memories.first()
            val backup = File(application.cacheDir, "office-round-trip.aichatoffice").apply {
                delete()
            }
            val passphrase = "instrumented-office-passphrase"

            repository.export(Uri.fromFile(backup), passphrase.toCharArray())
            assertTrue(backup.length() > 0)

            val preview = repository.prepareImport(Uri.fromFile(backup), passphrase.toCharArray())
            assertEquals(before.size.toLong(), preview.memories)
            repository.commitImport(preview)

            val after = application.container.memoryRepository.memories.first()
            assertEquals(
                before.associate { it.id to it.content },
                after.associate { it.id to it.content },
            )
            backup.delete()
        }
    }
}
