package com.aliahad.aichat.backup

import com.aliahad.aichat.data.migrationResidueTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Tests the migration residue sweep predicate. DatabaseEncryptionMigrator requires an Android
 * Context and this module has no mocking framework on the JVM, so the sweep decision was
 * extracted into the pure `migrationResidueTargets` function, which is tested here.
 */
class DatabaseEncryptionSweepTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val sqliteHeader = "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)

    @Test
    fun plaintextMainDatabasePreservesAllMigrationSources() {
        val database = writeFile("aichat.db", sqliteHeader + ByteArray(96))
        val backup = writeFile("aichat.db.plaintext-backup", ByteArray(64))
        val temp = writeFile("aichat.db.encrypted-tmp", ByteArray(64))

        val targets = migrationResidueTargets(database, backup, temp)

        assertTrue(targets.isEmpty())
        assertTrue(backup.exists())
        assertTrue(temp.exists())
    }

    @Test
    fun encryptedMainDatabaseSelectsLeftoverPlaintextBackup() {
        val database = writeFile("aichat.db", ByteArray(128) { 0x42 })
        val backup = writeFile("aichat.db.plaintext-backup", ByteArray(64))
        val temp = File(database.parentFile, "aichat.db.encrypted-tmp")

        val targets = migrationResidueTargets(database, backup, temp)

        assertEquals(listOf(backup), targets)
    }

    @Test
    fun encryptedMainDatabaseSelectsLeftoverEncryptedTemp() {
        val database = writeFile("aichat.db", ByteArray(128) { 0x42 })
        val backup = File(database.parentFile, "aichat.db.plaintext-backup")
        val temp = writeFile("aichat.db.encrypted-tmp", ByteArray(64))

        val targets = migrationResidueTargets(database, backup, temp)

        assertEquals(listOf(temp), targets)
    }

    @Test
    fun encryptedMainDatabaseSelectsBothResidueFiles() {
        val database = writeFile("aichat.db", ByteArray(128) { 0x42 })
        val backup = writeFile("aichat.db.plaintext-backup", ByteArray(64))
        val temp = writeFile("aichat.db.encrypted-tmp", ByteArray(64))

        val targets = migrationResidueTargets(database, backup, temp)

        assertEquals(listOf(backup, temp), targets)
    }

    @Test
    fun freshInstallWithoutAnyFilesSelectsNothing() {
        val dir = temporaryFolder.newFolder("databases")
        val database = File(dir, "aichat.db")
        val backup = File(dir, "aichat.db.plaintext-backup")
        val temp = File(dir, "aichat.db.encrypted-tmp")

        assertTrue(migrationResidueTargets(database, backup, temp).isEmpty())
    }

    private fun writeFile(name: String, content: ByteArray): File =
        File(temporaryFolder.newFolder("databases-$name"), name).also { it.writeBytes(content) }
}
