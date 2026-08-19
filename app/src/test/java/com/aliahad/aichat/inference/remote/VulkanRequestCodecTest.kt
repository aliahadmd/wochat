package com.aliahad.aichat.inference.remote

import com.aliahad.aichat.core.AttachmentContext
import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.core.UserTurn
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun restoreRoundTripPreservesConversationSettingsHistoryAndAttachments() {
        val codec = VulkanRequestCodec(tempFolder.newFolder())
        val settings = GenerationSettings(
            maxNewTokens = 512,
            maxAnswerTokens = 4096,
            temperature = 0.7f,
            thinkingEnabled = true,
            systemPrompt = "Be brief.",
        )
        val attachment = AttachmentContext(
            attachmentId = "attachment-1",
            displayName = "notes.pdf",
            extractedText = "page text",
            imagePaths = listOf("/images/page-1.png", "/images/page-3.png"),
            selectedPages = setOf(3, 1),
            imageTokenBudget = 1500,
            audioPaths = listOf("/audio/clip.ogg"),
            audioTokenEstimate = 220,
        )
        val history = listOf(
            ChatTurn(
                message = ChatMessage(
                    id = "original-system",
                    conversationId = "conversation-9",
                    role = MessageRole.SYSTEM,
                    content = "system prompt",
                    createdAt = 100L,
                    status = MessageStatus.COMPLETE,
                ),
            ),
            ChatTurn(
                message = ChatMessage(
                    id = "original-user",
                    conversationId = "conversation-9",
                    role = MessageRole.USER,
                    content = "look at this",
                    createdAt = 200L,
                    status = MessageStatus.COMPLETE,
                ),
                attachments = listOf(attachment),
            ),
            ChatTurn(
                message = ChatMessage(
                    id = "original-assistant",
                    conversationId = "conversation-9",
                    role = MessageRole.ASSISTANT,
                    content = "here is my answer",
                    createdAt = 300L,
                    status = MessageStatus.COMPLETE,
                ),
            ),
        )

        val path = codec.writeRestore("conversation-9", history, settings)
        val restored = codec.readRestore(path)

        assertEquals("conversation-9", restored.conversationId)
        assertEquals(settings, restored.settings)
        assertEquals(3, restored.history.size)
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT),
            restored.history.map { it.message.role },
        )
        assertEquals(
            listOf("system prompt", "look at this", "here is my answer"),
            restored.history.map { it.message.content },
        )

        val originalIds = history.map { it.message.id }
        restored.history.forEachIndexed { index, turn ->
            assertTrue(turn.message.id.startsWith("remote-history-"))
            assertNotEquals(originalIds[index], turn.message.id)
            assertEquals("remote", turn.message.conversationId)
            assertEquals(0L, turn.message.createdAt)
            assertEquals(MessageStatus.COMPLETE, turn.message.status)
        }
        val restoredIds = restored.history.map { it.message.id }.toSet()
        assertEquals(3, restoredIds.size)

        val restoredAttachment = restored.history[1].attachments.single()
        assertEquals(attachment, restoredAttachment)
    }

    @Test
    fun generationRoundTripPreservesTurnTextAndAttachments() {
        val codec = VulkanRequestCodec(tempFolder.newFolder())
        val settings = GenerationSettings()
        val turn = UserTurn(
            conversationId = "conversation-4",
            text = "summarize these files",
            // The preamble carries retrieved memories and the conversation summary;
            // dropping it in the codec made the Vulkan backend prompt-blind to them.
            preamble = "Conversation summary: earlier topics\n- remembered fact: likes green tea",
            attachments = listOf(
                AttachmentContext(
                    attachmentId = "attachment-a",
                    displayName = "a.txt",
                    extractedText = "contents of a",
                    imagePaths = emptyList(),
                    selectedPages = emptySet(),
                    imageTokenBudget = 0,
                ),
                AttachmentContext(
                    attachmentId = "attachment-b",
                    displayName = "b.png",
                    extractedText = "",
                    imagePaths = listOf("/images/b.png"),
                    selectedPages = emptySet(),
                    imageTokenBudget = 800,
                ),
            ),
        )

        val path = codec.writeGeneration(turn, settings)
        val restored = codec.readGeneration(path)

        assertEquals(turn, restored.turn)
        assertEquals("summarize these files", restored.turn.text)
        assertEquals(turn.preamble, restored.turn.preamble)
        assertEquals(2, restored.turn.attachments.size)
        assertEquals(settings.normalized(), restored.settings)
    }

    @Test
    fun tokenCountRoundTripPreservesMultiLineTextVerbatim() {
        val codec = VulkanRequestCodec(tempFolder.newFolder())
        val text = "line one\nline two\twith tab\n\u2014 unicode, too\nfinal line"

        val path = codec.writeTokenCount(text)

        assertEquals(text, codec.readTokenCount(path))
    }

    @Test
    fun decodedSettingsAreNormalized() {
        val codec = VulkanRequestCodec(tempFolder.newFolder())
        val outOfRange = GenerationSettings(
            maxNewTokens = 10_000,
            maxAnswerTokens = 100,
            temperature = 9f,
            thinkingEnabled = true,
            systemPrompt = "   ",
        )

        val path = codec.writeGeneration(UserTurn("c", "t"), outOfRange)
        val restored = codec.readGeneration(path)

        assertEquals(outOfRange.normalized(), restored.settings)
        assertEquals(2048, restored.settings.maxNewTokens)
        assertEquals(2048, restored.settings.maxAnswerTokens)
        assertEquals(2f, restored.settings.temperature, 0f)
        assertEquals("You are a helpful, concise assistant.", restored.settings.systemPrompt)
    }

    @Test
    fun emptySelectedPagesRoundTripAsEmptySet() {
        val codec = VulkanRequestCodec(tempFolder.newFolder())
        val turn = UserTurn(
            conversationId = "conversation-1",
            text = "hi",
            attachments = listOf(
                AttachmentContext(
                    attachmentId = "attachment-empty",
                    displayName = "empty.pdf",
                    extractedText = "",
                    imagePaths = emptyList(),
                    selectedPages = emptySet(),
                    imageTokenBudget = 0,
                ),
            ),
        )

        val path = codec.writeGeneration(turn, GenerationSettings())
        val restored = codec.readGeneration(path)

        assertTrue(restored.turn.attachments.single().selectedPages.isEmpty())
    }

    @Test
    fun readingMismatchedKindThrowsIllegalArgument() {
        val codec = VulkanRequestCodec(tempFolder.newFolder())
        val restorePath = codec.writeRestore("conversation-2", emptyList(), GenerationSettings())

        try {
            codec.readGeneration(restorePath)
            fail("Expected reading a restore file as generation to throw.")
        } catch (expected: IllegalArgumentException) {
            assertEquals("Unexpected inference request type.", expected.message)
        }
    }

    @Test
    fun readDeletesTheRequestFile() {
        val codec = VulkanRequestCodec(tempFolder.newFolder())
        val path = codec.writeTokenCount("transient")
        assertTrue(File(path).isFile)

        codec.readTokenCount(path)

        assertFalse(File(path).exists())
    }

    @Test
    fun readingFileOutsideRequestDirectoryThrowsIllegalArgument() {
        val codec = VulkanRequestCodec(tempFolder.newFolder())

        try {
            codec.readRestore("/etc/passwd")
            fail("Expected reading outside the request directory to throw.")
        } catch (expected: IllegalArgumentException) {
            assertEquals("Invalid inference request path.", expected.message)
        }
    }
}
