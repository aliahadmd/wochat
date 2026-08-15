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

    @Test
    fun interruptedInstallRestoresThePreviousFileFromBackup() {
        val directory = createTempDirectory("atomic-installer-").toFile()
        try {
            // Crash window: destination renamed to .old, source rename never happened.
            val destination = File(directory, "model.gguf")
            File(directory, "model.gguf.old").writeText("previous")

            AtomicFileInstaller.recoverInterrupted(destination)

            assertEquals("previous", destination.readText())
            assertFalse(File(directory, "model.gguf.old").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun recoveryKeepsTheDestinationWhenBothFilesExist() {
        val directory = createTempDirectory("atomic-installer-").toFile()
        try {
            val destination = File(directory, "model.gguf").apply { writeText("current") }
            File(directory, "model.gguf.old").writeText("stale")

            AtomicFileInstaller.recoverInterrupted(destination)

            assertEquals("current", destination.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
