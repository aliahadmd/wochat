package com.aliahad.aichat.backup

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase as PlainSQLiteDatabase
import android.net.Uri
import android.util.Log
import com.aliahad.aichat.BuildConfig
import com.aliahad.aichat.core.BackupPreview
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.settings.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

interface OfficeBackupRepository {
    suspend fun export(uri: Uri, passphrase: CharArray)
    suspend fun prepareImport(uri: Uri, passphrase: CharArray): BackupPreview
    suspend fun commitImport(preview: BackupPreview)
    suspend fun discardImport(preview: BackupPreview)
}

class EncryptedOfficeBackupRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val settings: AppSettingsRepository,
) : OfficeBackupRepository {
    override suspend fun export(uri: Uri, passphrase: CharArray) = withContext(Dispatchers.IO) {
        require(passphrase.size >= 8) { "Use a backup passphrase with at least 8 characters" }
        val working = createWorkingDirectory("export")
        try {
            val snapshot = File(working, DATABASE_ENTRY)
            exportPlaintextSnapshot(snapshot)
            val attachments = sanitizeSnapshotAndCollectAttachments(snapshot)
            val manifest = createManifest(snapshot, attachments.size)
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
            val key = deriveKey(passphrase, salt, KDF_ITERATIONS)
            val output = requireNotNull(context.contentResolver.openOutputStream(uri, "w")) {
                "Unable to open the selected backup destination"
            }
            DataOutputStream(BufferedOutputStream(output)).use { header ->
                header.write(MAGIC)
                header.writeInt(FORMAT_VERSION)
                header.writeInt(KDF_ITERATIONS)
                header.write(salt)
                header.write(nonce)
                header.flush()
                val cipher = Cipher.getInstance(CIPHER)
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
                ZipOutputStream(CipherOutputStream(header, cipher)).use { zip ->
                    zip.putText(MANIFEST_ENTRY, manifest.toString())
                    zip.putText(SETTINGS_ENTRY, settingsJson().toString())
                    zip.putFile(DATABASE_ENTRY, snapshot)
                    attachments.forEach { (entry, file) -> zip.putFile(entry, file) }
                }
            }
        } finally {
            passphrase.fill('\u0000')
            working.deleteRecursively()
        }
    }

    override suspend fun prepareImport(
        uri: Uri,
        passphrase: CharArray,
    ): BackupPreview = withContext(Dispatchers.IO) {
        require(passphrase.size >= 8) { "Enter the backup passphrase" }
        val working = createWorkingDirectory("import")
        try {
            decryptAndExtract(uri, passphrase, working)
            val manifestFile = File(working, MANIFEST_ENTRY)
            val snapshot = File(working, DATABASE_ENTRY)
            require(manifestFile.isFile && snapshot.isFile) { "Backup is missing required Office data" }
            val manifest = JSONObject(manifestFile.readText())
            require(manifest.getInt("formatVersion") == FORMAT_VERSION) {
                "This backup format is not supported"
            }
            val databaseVersion = manifest.getInt("databaseVersion")
            require(databaseVersion in MIN_IMPORT_DATABASE_VERSION..AppDatabase.VERSION) {
                "This Office database version is not supported"
            }
            require(snapshot.inputStream().use { input ->
                val header = ByteArray(SQLITE_HEADER.size)
                input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)
            }) { "Backup database is invalid" }
            require(validateSnapshot(snapshot) == databaseVersion) {
                "Backup database version does not match its manifest"
            }
            val counts = readCounts(snapshot)
            BackupPreview(
                stagingPath = working.absolutePath,
                createdAt = manifest.getLong("createdAt"),
                conversations = counts["conversations"] ?: 0,
                messages = counts["messages"] ?: 0,
                memories = counts["memory_items"] ?: 0,
                activities = counts["activity_events"] ?: 0,
                attachments = counts["attachments"] ?: 0,
            )
        } catch (error: Throwable) {
            working.deleteRecursively()
            throw IllegalArgumentException(
                "Unable to decrypt or validate this backup. Check the passphrase and file.",
                error,
            )
        } finally {
            passphrase.fill('\u0000')
        }
    }

    override suspend fun commitImport(preview: BackupPreview) = withContext(Dispatchers.IO) {
        val working = File(preview.stagingPath)
        val snapshot = File(working, DATABASE_ENTRY)
        require(
            snapshot.isFile &&
                BackupPathSafety.isManagedStagingDirectory(context.cacheDir, working),
        ) {
            "Import staging data is unavailable"
        }
        var dataMerged = false
        var createdAttachments = emptyList<File>()
        try {
            createdAttachments = restoreAttachmentFilesAndPaths(working, snapshot)
            mergeSnapshot(snapshot)
            dataMerged = true
            runCatching {
                database.backupImportInvalidationDao().notifyImportedTables()
                importSettings(File(working, SETTINGS_ENTRY))
            }.onFailure {
                Log.e(TAG, "Office data imported, but post-import refresh failed", it)
            }
        } finally {
            if (dataMerged) {
                working.deleteRecursively()
            } else {
                createdAttachments.forEach(File::delete)
            }
        }
        Unit
    }

    override suspend fun discardImport(preview: BackupPreview) = withContext(Dispatchers.IO) {
        val working = File(preview.stagingPath)
        if (BackupPathSafety.isManagedStagingDirectory(context.cacheDir, working)) {
            working.deleteRecursively()
        }
    }

    private fun exportPlaintextSnapshot(destination: File) {
        destination.delete()
        val sql = database.openHelper.writableDatabase as? SQLiteDatabase
            ?: error("SQLCipher database connection is unavailable")
        sql.rawQuery("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        attachPlaintext(sql, destination, "office_export")
        try {
            sql.rawExecSQL("SELECT sqlcipher_export('office_export')")
        } finally {
            sql.rawExecSQL("DETACH DATABASE office_export")
        }
    }

    private fun sanitizeSnapshotAndCollectAttachments(snapshot: File): List<Pair<String, File>> {
        val files = mutableListOf<Pair<String, File>>()
        val plain = PlainSQLiteDatabase.openDatabase(
            snapshot.absolutePath,
            null,
            PlainSQLiteDatabase.OPEN_READWRITE,
        )
        try {
            plain.delete("models", null, null)
            plain.delete("projectors", null, null)
            plain.delete("model_context_profiles", null, null)
            plain.delete("model_benchmarks", null, null)
            plain.execSQL("PRAGMA user_version = ${AppDatabase.VERSION}")
            plain.query(
                "attachments",
                arrayOf("id", "originalPath"),
                null,
                null,
                null,
                null,
                null,
            ).use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val pathIndex = cursor.getColumnIndexOrThrow("originalPath")
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex)
                    val source = File(cursor.getString(pathIndex))
                    if (!source.isFile) continue
                    val entry = "attachments/$id/${source.name}"
                    files += entry to source
                    plain.execSQL(
                        "UPDATE attachments SET originalPath = ?, previewPath = NULL, " +
                            "derivedImagePaths = '' WHERE id = ?",
                        arrayOf(entry, id),
                    )
                }
            }
        } finally {
            plain.close()
        }
        return files
    }

    private fun createManifest(snapshot: File, attachmentFiles: Int): JSONObject {
        val counts = readCounts(snapshot).toMutableMap()
        counts["attachment_files"] = attachmentFiles.toLong()
        return JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("databaseVersion", AppDatabase.VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("counts", JSONObject(counts))
    }

    private suspend fun settingsJson(): JSONObject {
        val generation = settings.generationSettings.first()
        return JSONObject()
            .put("segmentTokens", generation.maxNewTokens)
            .put("answerTokens", generation.maxAnswerTokens)
            .put("temperature", generation.temperature.toDouble())
            .put("thinking", generation.thinkingEnabled)
            .put("systemPrompt", generation.systemPrompt)
            .put("memoryEnabled", settings.memoryEnabled.first())
            .put("collectionPaused", settings.collectionPaused.first())
            .put("allowMeteredModelDownloads", settings.allowMeteredModelDownloads.first())
    }

    private fun decryptAndExtract(uri: Uri, passphrase: CharArray, working: File) {
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
            "Unable to open the selected backup"
        }
        DataInputStream(BufferedInputStream(input)).use { header ->
            val magic = ByteArray(MAGIC.size)
            header.readFully(magic)
            require(magic.contentEquals(MAGIC)) { "Not an AIchat Office backup" }
            require(header.readInt() == FORMAT_VERSION) { "Unsupported Office backup version" }
            val iterations = header.readInt()
            require(iterations in MIN_KDF_ITERATIONS..MAX_KDF_ITERATIONS) {
                "Backup key protection settings are unsupported"
            }
            val salt = ByteArray(16).also(header::readFully)
            val nonce = ByteArray(12).also(header::readFully)
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(passphrase, salt, iterations),
                GCMParameterSpec(128, nonce),
            )
            ZipInputStream(CipherInputStream(header, cipher)).use { zip ->
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val destination = safeDestination(working, entry.name)
                    if (entry.isDirectory) {
                        destination.mkdirs()
                    } else {
                        destination.parentFile?.mkdirs()
                        destination.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var entryBytes = 0L
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                total += read
                                require(entryBytes <= MAX_ENTRY_BYTES && total <= MAX_ARCHIVE_BYTES) {
                                    "Backup expands beyond the allowed safety limit"
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun restoreAttachmentFilesAndPaths(working: File, snapshot: File): List<File> {
        val created = mutableListOf<File>()
        val plain = PlainSQLiteDatabase.openDatabase(
            snapshot.absolutePath,
            null,
            PlainSQLiteDatabase.OPEN_READWRITE,
        )
        try {
            plain.query(
                "attachments",
                arrayOf("id", "originalPath"),
                null,
                null,
                null,
                null,
                null,
            ).use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val pathIndex = cursor.getColumnIndexOrThrow("originalPath")
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex)
                    val relative = cursor.getString(pathIndex)
                    require(relative.startsWith("attachments/")) {
                        "Backup attachment path is invalid"
                    }
                    val source = safeDestination(working, relative)
                    if (!source.isFile) continue
                    val destination = BackupPathSafety.attachmentDestination(
                        context.noBackupFilesDir,
                        id,
                        source.name,
                    )
                    destination.parentFile?.mkdirs()
                    if (!destination.exists()) {
                        source.copyTo(destination)
                        created += destination
                    }
                    plain.execSQL(
                        "UPDATE attachments SET originalPath = ?, previewPath = NULL, " +
                            "derivedImagePaths = '' WHERE id = ?",
                        arrayOf(destination.absolutePath, id),
                    )
                }
            }
        } finally {
            plain.close()
        }
        return created
    }

    private fun mergeSnapshot(snapshot: File) {
        val target = database.openHelper.writableDatabase as? SQLiteDatabase
            ?: error("SQLCipher database connection is unavailable")
        val source = PlainSQLiteDatabase.openDatabase(
            snapshot.absolutePath,
            null,
            PlainSQLiteDatabase.OPEN_READONLY,
        )
        try {
            target.beginTransaction()
            try {
                val memoryIds = buildMemoryIdMap(source, target)
                PRE_MEMORY_TABLES.forEach { copyTable(source, target, it) }
                OPTIONAL_USER_TABLES.forEach { copyTableIfPresent(source, target, it) }
                copyTable(source, target, "memory_items") { values ->
                    val oldId = values.getAsString("id")
                    val mappedId = memoryIds.getValue(oldId)
                    if (mappedId != oldId) return@copyTable false
                    values.put("id", mappedId)
                    values.getAsString("supersedesId")?.let { superseded ->
                        values.put("supersedesId", memoryIds[superseded] ?: superseded)
                    }
                    true
                }
                copyTable(source, target, "memory_sources") { values ->
                    values.put(
                        "memoryId",
                        memoryIds[values.getAsString("memoryId")] ?: values.getAsString("memoryId"),
                    )
                    true
                }
                copyTable(source, target, "memory_corrections") { values ->
                    values.put(
                        "memoryId",
                        memoryIds[values.getAsString("memoryId")] ?: values.getAsString("memoryId"),
                    )
                    values.getAsString("replacementMemoryId")?.let { replacement ->
                        values.put("replacementMemoryId", memoryIds[replacement] ?: replacement)
                    }
                    true
                }
                POST_MEMORY_TABLES.forEach { copyTable(source, target, it) }
                target.setTransactionSuccessful()
            } finally {
                target.endTransaction()
            }
        } finally {
            source.close()
        }
    }

    private fun buildMemoryIdMap(
        source: PlainSQLiteDatabase,
        target: SQLiteDatabase,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        source.query(
            "memory_items",
            arrayOf("id", "contentHash", "pinned", "updatedAt"),
            null,
            null,
            null,
            null,
            null,
        ).use { imported ->
            while (imported.moveToNext()) {
                val importedId = imported.getString(0)
                val contentHash = imported.getString(1)
                val pinned = imported.getInt(2) != 0
                val updatedAt = imported.getLong(3)
                val existingByHash = target.rawQuery(
                    "SELECT id, pinned, updatedAt FROM memory_items WHERE contentHash = ? LIMIT 1",
                    contentHash,
                ).use { local ->
                    if (local.moveToFirst()) {
                        Triple(local.getString(0), local.getInt(1) != 0, local.getLong(2))
                    } else {
                        null
                    }
                }
                if (existingByHash != null) {
                    result[importedId] = existingByHash.first
                    if (pinned && !existingByHash.second) {
                        target.rawExecSQL(
                            "UPDATE memory_items SET pinned = 1, updatedAt = ? WHERE id = ?",
                            maxOf(updatedAt, existingByHash.third),
                            existingByHash.first,
                        )
                    }
                } else {
                    val idCollision = target.rawQuery(
                        "SELECT 1 FROM memory_items WHERE id = ? LIMIT 1",
                        importedId,
                    ).use(Cursor::moveToFirst)
                    result[importedId] = if (idCollision) UUID.randomUUID().toString() else importedId
                }
            }
        }
        return result
    }

    private fun copyTable(
        source: PlainSQLiteDatabase,
        target: SQLiteDatabase,
        table: String,
        transform: (ContentValues) -> Boolean = { true },
    ) {
        source.rawQuery("SELECT * FROM $table", null).use { cursor ->
            while (cursor.moveToNext()) {
                val values = cursor.toContentValues()
                if (transform(values)) {
                    target.insert(table, SQLiteDatabase.CONFLICT_IGNORE, values)
                }
            }
        }
    }

    private fun copyTableIfPresent(
        source: PlainSQLiteDatabase,
        target: SQLiteDatabase,
        table: String,
    ) {
        if (source.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
                arrayOf(table),
            ).use(Cursor::moveToFirst)
        ) {
            copyTable(source, target, table)
        }
    }

    private fun Cursor.toContentValues(): ContentValues =
        ContentValues(columnCount).also { values ->
            columnNames.forEachIndexed { index, name ->
                when (getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> values.putNull(name)
                    Cursor.FIELD_TYPE_INTEGER -> values.put(name, getLong(index))
                    Cursor.FIELD_TYPE_FLOAT -> values.put(name, getDouble(index))
                    Cursor.FIELD_TYPE_STRING -> values.put(name, getString(index))
                    Cursor.FIELD_TYPE_BLOB -> values.put(name, getBlob(index))
                }
            }
        }

    private fun attachPlaintext(sql: SQLiteDatabase, file: File, alias: String) {
        require(alias == "office_export")
        val escapedPath = file.absolutePath.replace("'", "''")
        sql.rawExecSQL("ATTACH DATABASE '$escapedPath' AS $alias KEY ''")
    }

    private suspend fun importSettings(file: File) {
        if (!file.isFile) return
        val json = JSONObject(file.readText())
        val current = settings.generationSettings.first()
        settings.updateGeneration(
            GenerationSettings(
                maxNewTokens = json.optInt("segmentTokens", current.maxNewTokens),
                maxAnswerTokens = json.optInt("answerTokens", current.maxAnswerTokens),
                temperature = json.optDouble("temperature", current.temperature.toDouble()).toFloat(),
                thinkingEnabled = json.optBoolean("thinking", current.thinkingEnabled),
                systemPrompt = json.optString("systemPrompt", current.systemPrompt),
            ),
        )
        settings.setMemoryEnabled(json.optBoolean("memoryEnabled", true))
        settings.setCollectionPaused(json.optBoolean("collectionPaused", true))
        settings.setAllowMeteredModelDownloads(
            json.optBoolean("allowMeteredModelDownloads", false),
        )
    }

    private fun readCounts(snapshot: File): Map<String, Long> {
        val plain = PlainSQLiteDatabase.openDatabase(
            snapshot.absolutePath,
            null,
            PlainSQLiteDatabase.OPEN_READONLY,
        )
        return try {
            COUNT_TABLES.associateWith { table ->
                plain.rawQuery("SELECT count(*) FROM $table", null).use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                }
            }
        } finally {
            plain.close()
        }
    }

    private fun validateSnapshot(snapshot: File): Int {
        val plain = PlainSQLiteDatabase.openDatabase(
            snapshot.absolutePath,
            null,
            PlainSQLiteDatabase.OPEN_READONLY,
        )
        return try {
            val integrity = plain.rawQuery("PRAGMA quick_check(1)", null).use {
                require(it.moveToFirst()) { "Backup database integrity check returned no result" }
                it.getString(0)
            }
            require(integrity.equals("ok", ignoreCase = true)) {
                "Backup database integrity check failed"
            }
            val tables = plain.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
                null,
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            require(COUNT_TABLES.all(tables::contains)) {
                "Backup database is missing required tables"
            }
            plain.rawQuery("PRAGMA user_version", null).use {
                require(it.moveToFirst()) { "Backup database version is unavailable" }
                it.getInt(0)
            }
        } finally {
            plain.close()
        }
    }

    private fun createWorkingDirectory(prefix: String): File =
        File(context.cacheDir, "office-backup/$prefix-${UUID.randomUUID()}").apply {
            check(mkdirs()) { "Unable to prepare backup storage" }
        }

    private fun safeDestination(root: File, name: String): File {
        require(!name.startsWith('/') && !name.contains('\\')) { "Unsafe backup entry" }
        val file = File(root, name)
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            "Unsafe backup entry"
        }
        return file
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES",
            )
        } finally {
            spec.clearPassword()
        }
    }

    private fun ZipOutputStream.putText(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.putFile(name: String, file: File) {
        val entry = ZipEntry(name)
        entry.time = file.lastModified()
        putNextEntry(entry)
        file.inputStream().buffered().use { it.copyTo(this) }
        closeEntry()
    }

    private companion object {
        const val TAG = "OfficeBackup"
        val MAGIC = "AICHATOFFICE".toByteArray(Charsets.US_ASCII)
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        const val FORMAT_VERSION = 1
        const val MIN_IMPORT_DATABASE_VERSION = 3
        const val KDF_ITERATIONS = 600_000
        const val MIN_KDF_ITERATIONS = 600_000
        const val MAX_KDF_ITERATIONS = 2_000_000
        const val CIPHER = "AES/GCM/NoPadding"
        const val MANIFEST_ENTRY = "manifest.json"
        const val SETTINGS_ENTRY = "settings.json"
        const val DATABASE_ENTRY = "office.db"
        const val MAX_ENTRY_BYTES = 8L * 1024 * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024 * 1024
        val COUNT_TABLES = listOf(
            "conversations",
            "messages",
            "memory_items",
            "activity_events",
            "attachments",
        )
        val PRE_MEMORY_TABLES = listOf(
            "conversations",
            "messages",
            "attachments",
            "attachment_chunks",
            "message_attachments",
            "conversation_summaries",
        )
        val OPTIONAL_USER_TABLES = listOf(
            "skills",
            "message_skill_invocations",
        )
        val POST_MEMORY_TABLES = listOf(
            "memory_summaries",
            "activity_events",
            "collector_checkpoints",
        )
    }
}
