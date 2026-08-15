package com.aliahad.aichat.data

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The database key exists but cannot be used right now because the device is
 * locked.
 *
 * The wrapping key is created with `setUnlockedDeviceRequired(true)`, so
 * Keystore refuses to operate with it while the keyguard is up. That is the
 * intended security property — this exception exists so callers can wait for
 * an unlock instead of treating it as corruption and taking the process down.
 */
class DatabaseLockedException(cause: Throwable? = null) : IllegalStateException(
    "The database key is unavailable while the device is locked.",
    cause,
)

/**
 * Whether the keyguard is currently up.
 *
 * Note this is deliberately **not** `UserManager.isUserUnlocked()`, which is
 * the Direct Boot signal and stays true once the user has unlocked at any
 * point since boot. `setUnlockedDeviceRequired(true)` is enforced against the
 * *current* lock state, which is what [KeyguardManager.isDeviceLocked] reports.
 */
internal fun Context.isDeviceCurrentlyLocked(): Boolean =
    getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

class DatabaseKeyManager(
    private val context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /**
     * @throws DatabaseLockedException when the device is locked. Any other
     *   Keystore failure propagates unchanged — those mean real key trouble
     *   and must stay loud.
     */
    fun passphrase(): ByteArray = try {
        readOrCreatePassphrase()
    } catch (error: GeneralSecurityException) {
        // Ask the keyguard rather than pattern-matching the exception text:
        // the message wording is OEM- and version-specific.
        if (context.isDeviceCurrentlyLocked()) throw DatabaseLockedException(error) else throw error
    }

    private fun readOrCreatePassphrase(): ByteArray {
        val stored = preferences.getString(KEY_CIPHERTEXT, null)
        if (stored != null) return decrypt(Base64.decode(stored, Base64.NO_WRAP))
        val passphrase = Base64.encode(
            ByteArray(32).also(SecureRandom()::nextBytes),
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
        )
        val encrypted = encrypt(passphrase)
        check(
            preferences.edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit(),
        ) { "Unable to store the encrypted database key" }
        return passphrase
    }

    private fun encrypt(value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        return cipher.iv + cipher.doFinal(value)
    }

    private fun decrypt(value: ByteArray): ByteArray {
        require(value.size > IV_BYTES) { "Invalid encrypted database key" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateWrappingKey(),
            GCMParameterSpec(128, value.copyOfRange(0, IV_BYTES)),
        )
        return cipher.doFinal(value.copyOfRange(IV_BYTES, value.size))
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUnlockedDeviceRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "office_database_key"
        const val KEY_CIPHERTEXT = "wrapped_passphrase"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "aichat.office.database.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

class DatabaseEncryptionMigrator(
    private val context: Context,
    private val databaseName: String,
) {
    private val databaseFile: File = context.getDatabasePath(databaseName)
    private val backupFile: File = File(databaseFile.parentFile, "$databaseName.plaintext-backup")
    private val encryptedTemp: File = File(databaseFile.parentFile, "$databaseName.encrypted-tmp")

    fun migratePlaintextIfNeeded(passphrase: ByteArray) {
        if (!databaseFile.exists() || !isPlaintextDatabase(databaseFile)) return
        databaseFile.parentFile?.mkdirs()
        checkpointPlaintext()
        deleteDatabaseFiles(encryptedTemp)
        System.loadLibrary("sqlcipher")
        val password = Base64.encodeToString(passphrase, Base64.NO_WRAP or Base64.URL_SAFE)
        val source = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            ByteArray(0),
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            null,
        )
        try {
            val escapedPath = encryptedTemp.absolutePath.replace("'", "''")
            val escapedPassword = password.replace("'", "''")
            source.rawExecSQL(
                "ATTACH DATABASE '$escapedPath' AS encrypted KEY '$escapedPassword'",
            )
            source.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            source.rawExecSQL("DETACH DATABASE encrypted")
        } finally {
            source.close()
        }
        verifyEncrypted(encryptedTemp, password)
        replacePlaintextWithEncrypted()
    }

    fun finishVerifiedMigration() {
        if (backupFile.exists()) deleteDatabaseFiles(backupFile)
    }

    fun sweepResidueFromFailedMigration() {
        if (recoverInterruptedMigration(databaseFile, backupFile)) return
        migrationResidueTargets(databaseFile, backupFile, encryptedTemp)
            .forEach { deleteDatabaseFiles(it) }
    }

    private fun checkpointPlaintext() {
        val database = android.database.sqlite.SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            database.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        } finally {
            database.close()
        }
    }

    private fun verifyEncrypted(file: File, password: String) {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            password.toByteArray(StandardCharsets.UTF_8),
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
        )
        try {
            database.rawQuery("SELECT count(*) FROM sqlite_master").use {
                check(it.moveToFirst() && it.getInt(0) > 0) {
                    "Encrypted database verification returned no schema"
                }
            }
            check(database.isDatabaseIntegrityOk) { "Encrypted database integrity check failed" }
        } finally {
            database.close()
        }
    }

    private fun replacePlaintextWithEncrypted() {
        deleteDatabaseFiles(backupFile)
        check(databaseFile.renameTo(backupFile)) { "Unable to preserve the plaintext database" }
        deleteSidecars(databaseFile)
        if (!encryptedTemp.renameTo(databaseFile)) {
            check(backupFile.renameTo(databaseFile)) {
                "Unable to restore the plaintext database after encryption failed"
            }
            error("Unable to activate the encrypted database")
        }
    }

    private fun isPlaintextDatabase(file: File): Boolean = isPlaintextSqliteHeader(file)

    private fun deleteDatabaseFiles(file: File) {
        file.delete()
        deleteSidecars(file)
    }

    private fun deleteSidecars(file: File) {
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        File(file.path + "-journal").delete()
    }
}

/**
 * Recovers from a process death between the two renames inside
 * `replacePlaintextWithEncrypted` (plaintext -> backup succeeded, encrypted-temp -> database
 * did not). In that state the main database is absent and the backup file is the only
 * surviving copy, so it must be restored — never swept — or the next residue sweep would
 * delete it and lose all data. Returns true when the backup was restored, in which case the
 * plaintext-to-encrypted migration simply re-runs on the next open.
 */
internal fun recoverInterruptedMigration(databaseFile: File, backupFile: File): Boolean {
    if (databaseFile.exists() || !backupFile.exists()) return false
    check(backupFile.renameTo(databaseFile)) { "Unable to recover the plaintext database" }
    return true
}

/**
 * Residue files left by an interrupted plaintext-to-encrypted migration that may be deleted
 * before opening the database. While the main database is still plaintext the migration is
 * pending and every source file must be preserved.
 */
internal fun migrationResidueTargets(
    databaseFile: File,
    backupFile: File,
    encryptedTemp: File,
): List<File> {
    if (isPlaintextSqliteHeader(databaseFile)) return emptyList()
    return buildList {
        if (backupFile.exists()) add(backupFile)
        if (encryptedTemp.exists()) add(encryptedTemp)
    }
}

internal fun isPlaintextSqliteHeader(file: File): Boolean {
    if (file.length() < SQLITE_HEADER_BYTES.size) return false
    return file.inputStream().use { input ->
        val header = ByteArray(SQLITE_HEADER_BYTES.size)
        input.read(header) == header.size && header.contentEquals(SQLITE_HEADER_BYTES)
    }
}

private val SQLITE_HEADER_BYTES = "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)
