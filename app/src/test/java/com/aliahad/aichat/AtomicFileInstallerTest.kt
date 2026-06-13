package com.aliahad.aichat

import com.aliahad.aichat.model.AtomicFileInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AtomicFileInstallerTest {
    @Test
    fun replacementPreservesNewFileAndRemovesBackup() {
        val directory = createTempDirectory("atomic-installer-").toFile()
        try {
            val source = File(directory, "download.part").apply { writeText("new") }
            val destination = File(directory, "model.gguf").apply { writeText("old") }

            AtomicFileInstaller.replace(source, destination)

            assertEquals("new", destination.readText())
            assertFalse(source.exists())
            assertFalse(File(directory, "model.gguf.old").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun replacementInstallsWhenNoPreviousFileExists() {
        val directory = createTempDirectory("atomic-installer-").toFile()
        try {
            val source = File(directory, "download.part").apply { writeText("model") }
            val destination = File(directory, "model.gguf")

            AtomicFileInstaller.replace(source, destination)

            assertTrue(destination.isFile)
            assertEquals("model", destination.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
