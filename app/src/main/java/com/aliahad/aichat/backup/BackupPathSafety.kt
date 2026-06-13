package com.aliahad.aichat.backup

import java.io.File
import java.security.MessageDigest

internal object BackupPathSafety {
    fun isManagedStagingDirectory(cacheDirectory: File, candidate: File): Boolean {
        val root = File(cacheDirectory, "office-backup").canonicalFile
        val path = candidate.canonicalFile
        return path.path.startsWith(root.path + File.separator)
    }

    fun attachmentDestination(
        noBackupFilesDirectory: File,
        attachmentId: String,
        fileName: String,
    ): File {
        require(attachmentId.isNotBlank()) { "Backup attachment ID is empty" }
        require(
            fileName.isNotBlank() &&
                fileName == File(fileName).name &&
                !fileName.contains('/') &&
                !fileName.contains('\\'),
        ) { "Backup attachment file name is unsafe" }
        val root = File(noBackupFilesDirectory, "attachments").canonicalFile
        val directoryName = "import-${sha256(attachmentId).take(32)}"
        val destination = File(File(root, directoryName), fileName).canonicalFile
        require(destination.path.startsWith(root.path + File.separator)) {
            "Backup attachment destination is unsafe"
        }
        return destination
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
