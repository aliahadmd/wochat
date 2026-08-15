package com.aliahad.aichat.ui.viewmodel

import com.aliahad.aichat.data.DatabaseLockedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The defect these pin down: `report(Throwable)` used to fall back to
 * `error.javaClass.simpleName`, so a user could be shown the literal text
 * "SQLiteConstraintException" as an error message.
 */
class UserFacingMessageTest {

    @Test
    fun `an exception with no message never surfaces its class name`() {
        val message = userFacingMessage(IllegalStateException())

        assertFalse(message.contains("IllegalState"))
        assertFalse(message.contains("Exception"))
        assertTrue(message.isNotBlank())
    }

    @Test
    fun `keeps messages that were written for the user`() {
        // ChatViewModel does error("Attachments exceed the 500 MB message limit.")
        // and that text is meant to be read by a person.
        val written = "Attachments exceed the 500 MB message limit."

        assertEquals(written, userFacingMessage(IllegalStateException(written)))
    }

    @Test
    fun `keeps require messages written for the user`() {
        val written = "The private attachment file is missing."

        assertEquals(written, userFacingMessage(IllegalArgumentException(written)))
    }

    @Test
    fun `rejects developer text that mentions a class name`() {
        val developerText = "java.lang.IllegalStateException: cursor window allocation failed"

        val message = userFacingMessage(RuntimeException(developerText))

        assertFalse(message.contains("java."))
        assertFalse(message.contains("IllegalStateException"))
    }

    @Test
    fun `rejects a stack fragment`() {
        val message = userFacingMessage(
            RuntimeException("boom\n\tat com.aliahad.aichat.Thing.method(Thing.kt:42)"),
        )

        assertFalse(message.contains("at com.aliahad"))
    }

    @Test
    fun `rejects an absurdly long message`() {
        val message = userFacingMessage(RuntimeException("x".repeat(500)))

        assertFalse(message.length > 500)
        assertTrue(message.isNotBlank())
    }

    @Test
    fun `locked device gets its own explanation`() {
        val message = userFacingMessage(DatabaseLockedException())

        assertTrue(message.contains("Unlock"))
    }

    @Test
    fun `storage failures explain themselves without jargon`() {
        val ioMessage = userFacingMessage(IOException("EACCES"))
        val missing = userFacingMessage(FileNotFoundException("/data/nope"))

        assertFalse(ioMessage.contains("EACCES"))
        assertTrue(missing.contains("could not be found"))
    }

    @Test
    fun `permission failures point at settings`() {
        val message = userFacingMessage(SecurityException())

        assertTrue(message.contains("permission"))
        assertFalse(message.contains("SecurityException"))
    }

    @Test
    fun `every mapped message reads like a sentence`() {
        val cases = listOf(
            IllegalStateException(),
            SecurityException(),
            IOException("x"),
            FileNotFoundException("x"),
            RuntimeException(),
            DatabaseLockedException(),
        )

        cases.forEach { error ->
            val message = userFacingMessage(error)
            assertTrue("blank for $error", message.isNotBlank())
            assertTrue("no full stop for $error", message.trimEnd().endsWith("."))
            assertFalse("plumbing leaked for $error", message.contains("Exception"))
        }
    }
}
