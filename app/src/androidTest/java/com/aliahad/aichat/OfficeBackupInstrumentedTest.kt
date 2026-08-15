package com.aliahad.aichat

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.AttachmentProcessingState
import com.aliahad.aichat.core.SkillPromptBlock
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.data.AttachmentEntity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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
            val skillRepository = application.container.skillRepository
            val chatRepository = application.container.chatRepository
            val skill = skillRepository.create(
                name = "Backup reviewer ${System.currentTimeMillis()}",
                description = "Reviews backup round trip",
                instructions = "Check that skills survive encrypted backup import.",
            )
            val conversation = chatRepository.createConversation()
            val user = chatRepository.addMessage(
                conversationId = conversation.id,
                role = MessageRole.USER,
                content = "Use the backup skill",
            )
            skillRepository.recordInvocation(
                user.id,
                listOf(
                    SkillPromptBlock(
                        skillId = skill.id,
                        name = skill.name,
                        description = skill.description,
                        instructions = skill.instructions,
                    ),
                ),
            )
            val before = application.container.memoryRepository.memories.first()
            val attachmentId = "backup-audio-${UUID.randomUUID()}"
            val attachmentFile = File(
                application.noBackupFilesDir,
                "attachments/$attachmentId/recording.wav",
            ).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46))
            }
            application.container.database.attachmentDao().upsert(
                AttachmentEntity(
                    id = attachmentId,
                    conversationId = null,
                    draftKey = "backup-audio",
                    displayName = "recording.wav",
                    mimeType = "audio/wav",
                    kind = AttachmentKind.AUDIO,
                    originalPath = attachmentFile.absolutePath,
                    previewPath = null,
                    derivedImagePaths = "",
                    byteSize = attachmentFile.length(),
                    pageCount = null,
                    selectedPages = "",
                    imageTokenBudget = null,
                    state = AttachmentProcessingState.READY,
                    progress = 1f,
                    error = null,
                    createdAt = System.currentTimeMillis(),
                    durationMillis = 5_500L,
                ),
            )
            val backup = File(application.cacheDir, "office-round-trip.aichatoffice").apply {
                delete()
            }
            val passphrase = "instrumented-office-passphrase"

            repository.export(Uri.fromFile(backup), passphrase.toCharArray())
            assertTrue(backup.length() > 0)
            chatRepository.deleteConversation(conversation.id)
            skillRepository.delete(skill.id)
            application.container.database.attachmentDao().delete(attachmentId)
            attachmentFile.delete()

            val preview = repository.prepareImport(Uri.fromFile(backup), passphrase.toCharArray())
            assertEquals(before.size.toLong(), preview.memories)
            repository.commitImport(preview)

            val after = application.container.memoryRepository.memories.first()
            assertEquals(
                before.associate { it.id to it.content },
                after.associate { it.id to it.content },
            )
            assertTrue(skillRepository.skills.first().any { it.id == skill.id })
            assertEquals("Use the backup skill", chatRepository.getMessages(conversation.id).first().content)
            assertEquals(
                skill.name,
                skillRepository.blocksForMessage(user.id).first().name,
            )
            val restoredAttachment = application.container.database.attachmentDao().get(attachmentId)
            assertEquals(5_500L, restoredAttachment?.durationMillis ?: -1L)
            assertTrue(restoredAttachment?.originalPath?.let { File(it) }?.isFile == true)
            application.container.database.attachmentDao().delete(attachmentId)
            restoredAttachment?.originalPath?.let { File(it) }?.delete()
            backup.delete()
        }
    }

    @Test
    fun legacyArchiveWithSearchRowIdImportsIntoCurrentSchema() {
        runBlocking {
            val application = ApplicationProvider.getApplicationContext<AiChatApplication>()
            val repository = application.container.officeBackupRepository
            val memoryId = "legacy-memory-${UUID.randomUUID()}"
            val conversationId = "legacy-conversation-${UUID.randomUUID()}"
            val now = System.currentTimeMillis()
            val snapshot = File(application.cacheDir, "legacy-snapshot.db").apply { delete() }
            val backup = File(application.cacheDir, "legacy-archive.aichatoffice").apply { delete() }
            val passphrase = "legacy-archive-passphrase".toCharArray()
            try {
                createLegacySnapshot(snapshot, memoryId, conversationId, now)
                writeLegacyArchive(backup, snapshot, passphrase)

                val preview = repository.prepareImport(Uri.fromFile(backup), passphrase)
                assertEquals(1L, preview.memories)
                assertEquals(1L, preview.conversations)
                repository.commitImport(preview)

                val imported = application.container.database.openHelper.writableDatabase.query(
                    "SELECT content, title FROM memory_items WHERE id = ?",
                    arrayOf(memoryId),
                ).use { cursor ->
                    assertTrue("Legacy memory was not imported into the v17 table", cursor.moveToFirst())
                    cursor.getString(0) to cursor.getString(1)
                }
                assertEquals("Brews oolong every afternoon", imported.first)
                assertEquals("Legacy tea habit", imported.second)
            } finally {
                application.container.database.openHelper.writableDatabase.delete(
                    "memory_items",
                    "id = ?",
                    arrayOf(memoryId),
                )
                application.container.database.openHelper.writableDatabase.delete(
                    "conversations",
                    "id = ?",
                    arrayOf(conversationId),
                )
                snapshot.delete()
                backup.delete()
            }
        }
    }

    private fun createLegacySnapshot(
        snapshot: File,
        memoryId: String,
        conversationId: String,
        now: Long,
    ) {
        SQLiteDatabase.openOrCreateDatabase(snapshot, null).use { db ->
            db.execSQL(
                "CREATE TABLE conversations (id TEXT PRIMARY KEY, title TEXT, createdAt INTEGER NOT NULL)",
            )
            db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, content TEXT)")
            db.execSQL("CREATE TABLE attachments (id TEXT PRIMARY KEY, displayName TEXT)")
            db.execSQL("CREATE TABLE attachment_chunks (id TEXT PRIMARY KEY, attachmentId TEXT)")
            db.execSQL("CREATE TABLE message_attachments (messageId TEXT, attachmentId TEXT)")
            db.execSQL("CREATE TABLE conversation_summaries (id TEXT PRIMARY KEY, conversationId TEXT)")
            db.execSQL(
                "CREATE TABLE memory_items (" +
                    "id TEXT PRIMARY KEY, " +
                    "type TEXT NOT NULL, " +
                    "title TEXT NOT NULL, " +
                    "content TEXT NOT NULL, " +
                    "normalizedContent TEXT NOT NULL, " +
                    "contentHash TEXT NOT NULL, " +
                    "confidence REAL NOT NULL, " +
                    "importance REAL NOT NULL, " +
                    "sensitivity TEXT NOT NULL, " +
                    "status TEXT NOT NULL, " +
                    "pinned INTEGER NOT NULL, " +
                    "validFrom INTEGER NOT NULL, " +
                    "validTo INTEGER, " +
                    "supersedesId TEXT, " +
                    "createdAt INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, " +
                    "searchRowId INTEGER NOT NULL)",
            )
            db.execSQL("CREATE TABLE memory_sources (memoryId TEXT, source TEXT)")
            db.execSQL("CREATE TABLE memory_corrections (id TEXT PRIMARY KEY, memoryId TEXT)")
            db.execSQL("CREATE TABLE memory_summaries (id TEXT PRIMARY KEY, content TEXT)")
            db.execSQL("CREATE TABLE activity_events (id TEXT PRIMARY KEY, title TEXT)")
            db.execSQL("CREATE TABLE collector_checkpoints (collector TEXT PRIMARY KEY)")
            db.execSQL("PRAGMA user_version = 16")
            db.insertOrThrow(
                "conversations",
                null,
                ContentValues().apply {
                    put("id", conversationId)
                    put("title", "Legacy conversation")
                    put("createdAt", now)
                },
            )
            db.insertOrThrow(
                "memory_items",
                null,
                ContentValues().apply {
                    put("id", memoryId)
                    put("type", "FACT")
                    put("title", "Legacy tea habit")
                    put("content", "Brews oolong every afternoon")
                    put("normalizedContent", "brews oolong every afternoon")
                    put("contentHash", "legacy-archive-$memoryId")
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
                    put("searchRowId", 424_242L)
                },
            )
        }
    }

    private fun writeLegacyArchive(backup: File, snapshot: File, passphrase: CharArray) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val spec = PBEKeySpec(passphrase, salt, 600_000, 256)
        val key = SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
            "AES",
        )
        spec.clearPassword()
        val manifest = JSONObject()
            .put("formatVersion", 1)
            .put("databaseVersion", 16)
            .put("createdAt", System.currentTimeMillis())
            .toString()
        backup.outputStream().buffered().let { output ->
            DataOutputStream(BufferedOutputStream(output)).use { header ->
                header.write("AICHATOFFICE".toByteArray(Charsets.US_ASCII))
                header.writeInt(1)
                header.writeInt(600_000)
                header.write(salt)
                header.write(nonce)
                header.flush()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
                ZipOutputStream(CipherOutputStream(header, cipher)).use { zip ->
                    zip.putText("manifest.json", manifest)
                    zip.putText("settings.json", "{}")
                    zip.putNextEntry(ZipEntry("office.db"))
                    snapshot.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        assertNotNull(backup.takeIf { it.length() > 0 })
    }

    private fun ZipOutputStream.putText(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
