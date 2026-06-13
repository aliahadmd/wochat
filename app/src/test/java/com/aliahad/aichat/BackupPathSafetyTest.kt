package com.aliahad.aichat

import com.aliahad.aichat.backup.BackupPathSafety
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupPathSafetyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stagingDirectoryMustBeInsideOfficeBackupRoot() {
        val cache = temporaryFolder.newFolder("cache")
        val valid = File(cache, "office-backup/import-1").apply { mkdirs() }
        val sibling = File(cache.parentFile, "${cache.name}-other/import-1").apply { mkdirs() }

        assertTrue(BackupPathSafety.isManagedStagingDirectory(cache, valid))
        assertFalse(BackupPathSafety.isManagedStagingDirectory(cache, sibling))
    }

    @Test
    fun maliciousAttachmentIdCannotEscapePrivateAttachmentRoot() {
        val noBackup = temporaryFolder.newFolder("no-backup")

        val destination = BackupPathSafety.attachmentDestination(
            noBackup,
            "../../databases/aichat.db",
            "document.txt",
        )

        assertTrue(
            destination.canonicalPath.startsWith(
                File(noBackup, "attachments").canonicalPath + File.separator,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupPathSafety.attachmentDestination(noBackup, "id", "../document.txt")
        }
    }
}
