package com.aliahad.aichat.inference.remote

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VulkanRequestCodecTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun oversizedWriteThrowsAndLeavesNoFilesBehind() {
        val requestDir = tempFolder.newFolder()
        val codec = VulkanRequestCodec(requestDir)

        val oversizedText = "x".repeat(8 * 1024 * 1024 + 1)
        try {
            codec.writeTokenCount(oversizedText)
            fail("Expected the oversized request to be rejected.")
        } catch (expected: IllegalStateException) {
            assertEquals("Inference request is too large.", expected.message)
        }

        val leftovers = requestDir.listFiles().orEmpty()
        assertTrue(
            "Failed write left files behind: ${leftovers.map { it.name }}",
            leftovers.isEmpty(),
        )
    }

    @Test
    fun successfulWriteLeavesExactlyOneJsonFileAndNoPartFile() {
        val requestDir = tempFolder.newFolder()
        val codec = VulkanRequestCodec(requestDir)

        val path = codec.writeTokenCount("hello")

        val written = File(path)
        assertTrue(written.isFile)
        assertTrue(written.name.endsWith(".json"))
        val files = requestDir.listFiles().orEmpty()
        assertEquals(1, files.size)
        assertEquals(written, files.single())
        assertFalse(files.any { it.name.endsWith(".part") })
    }

    @Test
    fun constructionSweepsStalePartFiles() {
        val requestDir = tempFolder.newFolder()
        val stale = File(requestDir, "stale.json.part").apply { writeText("partial") }
        val kept = File(requestDir, "kept.json").apply { writeText("complete") }

        VulkanRequestCodec(requestDir)

        assertFalse(stale.exists())
        assertTrue(kept.exists())
        assertEquals(listOf("kept.json"), requestDir.listFiles().orEmpty().map { it.name })
    }
}
