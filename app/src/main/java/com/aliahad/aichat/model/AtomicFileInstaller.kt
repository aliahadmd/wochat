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
}
