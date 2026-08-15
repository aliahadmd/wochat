package com.aliahad.aichat.model

import java.io.File

internal object AtomicFileInstaller {
    fun replace(source: File, destination: File) {
        require(source.isFile) { "Installation source is unavailable" }
        val backup = File(destination.parentFile, "${destination.name}.old")
        backup.delete()
        if (destination.exists()) {
            require(destination.renameTo(backup)) {
                "Unable to preserve the existing file"
            }
        }
        try {
            require(source.renameTo(destination)) { "Unable to finalize the file" }
        } catch (error: Throwable) {
            destination.delete()
            if (backup.exists()) {
                check(backup.renameTo(destination)) {
                    "Unable to restore the previous file after installation failed"
                }
            }
            throw error
        }
        backup.delete()
    }

    /**
     * Recovers from a process death between the backup and final renames inside [replace]:
     * the destination is absent and the `.old` backup holds the previously installed file.
     * Restoring it avoids losing a multi-gigabyte model to a crash window.
     */
    fun recoverInterrupted(destination: File) {
        val backup = File(destination.parentFile, "${destination.name}.old")
        if (!destination.exists() && backup.exists()) {
            check(backup.renameTo(destination)) { "Unable to recover ${destination.name}" }
        }
    }
}
