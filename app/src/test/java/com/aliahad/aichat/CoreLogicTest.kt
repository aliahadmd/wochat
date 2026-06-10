package com.aliahad.aichat

import com.aliahad.aichat.core.ChatMessage
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.inference.HistoryTrimmer
import com.aliahad.aichat.model.GgufValidator
import com.aliahad.aichat.model.ModelConstants
import com.aliahad.aichat.attachment.AttachmentTypeDetector
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.residency.ModelLoadSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreLogicTest {
    @Test
    fun generationSettingsAreBoundedForPhoneMemory() {
        val settings = GenerationSettings(
            contextSize = 100_000,
            maxNewTokens = 50_000,
            temperature = 9f,
            systemPrompt = " ",
        ).normalized()

        assertEquals(8192, settings.contextSize)
        assertEquals(2048, settings.maxNewTokens)
        assertEquals(2f, settings.temperature)
        assertTrue(settings.systemPrompt.isNotBlank())
    }

    @Test
    fun historyTrimmerKeepsNewestMessagesInOrder() {
        val messages = (1..8).map { index ->
            ChatMessage(
                id = index.toString(),
                conversationId = "chat",
                role = if (index % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER,
                content = "x".repeat(900),
                createdAt = index.toLong(),
                status = MessageStatus.COMPLETE,
            )
        }

        val trimmed = HistoryTrimmer.trim(messages, contextSize = 1024)

        assertTrue(trimmed.size < messages.size)
        assertEquals(messages.last().id, trimmed.last().id)
        assertEquals(trimmed.sortedBy { it.createdAt }, trimmed)
    }

    @Test
    fun ggufValidatorChecksMagicHeader() {
        val valid = File.createTempFile("valid", ".gguf").apply {
            writeBytes(
                byteArrayOf(
                    'G'.code.toByte(),
                    'G'.code.toByte(),
                    'U'.code.toByte(),
                    'F'.code.toByte(),
                ) + ByteArray(20),
            )
        }
        val invalid = File.createTempFile("invalid", ".gguf").apply {
            writeBytes(ByteArray(24))
        }
        try {
            assertTrue(GgufValidator.validate(valid).isSuccess)
            assertTrue(GgufValidator.validate(invalid).isFailure)
        } finally {
            valid.delete()
            invalid.delete()
        }
    }

    @Test
    fun residencyReloadSignatureOnlyTracksNativeLoadSettings() {
        val original = ModelLoadSignature(
            path = "/models/gemma.gguf",
            contextSize = 4096,
            temperature = 0.3f,
        )

        assertEquals(original, original.copy())
        assertTrue(original != original.copy(contextSize = 8192))
        assertTrue(original != original.copy(temperature = 0.6f))
        assertTrue(original != original.copy(path = "/models/other.gguf"))
    }

    @Test
    fun officialModelCatalogContainsIndependentVerifiedArtifacts() {
        val models = ModelConstants.OFFICIAL_MODELS

        assertEquals(2, models.size)
        assertEquals(models.size, models.map { it.id }.distinct().size)
        assertEquals(models.size, models.map { it.fileName }.distinct().size)
        assertEquals(5_154_939_136L, ModelConstants.GEMMA_4_E4B.sizeBytes)
        assertEquals(
            "e8b6a059ba86947a44ace84d6e5679795bc41862c25c30513142588f0e9dba1d",
            ModelConstants.GEMMA_4_E4B.sha256,
        )
        assertTrue(ModelConstants.GEMMA_4_E4B.downloadUrl.endsWith("gemma-4-E4B_q4_0-it.gguf"))
        assertTrue(ModelConstants.GEMMA_4_E4B.workName != ModelConstants.GEMMA_4_12B.workName)
    }

    @Test
    fun officialProjectorCatalogMatchesModelsAndChecksums() {
        val projectors = ModelConstants.OFFICIAL_PROJECTORS

        assertEquals(2, projectors.size)
        assertEquals(ModelConstants.GEMMA_4_E4B.id, ModelConstants.GEMMA_4_E4B_PROJECTOR.modelId)
        assertEquals(991_551_904L, ModelConstants.GEMMA_4_E4B_PROJECTOR.sizeBytes)
        assertEquals(
            "c6398448d84a4836fdedf58f9775979e69ae0cc4dfdf4d697b5597693a555b12",
            ModelConstants.GEMMA_4_E4B_PROJECTOR.sha256,
        )
        assertEquals(175_115_264L, ModelConstants.GEMMA_4_12B_PROJECTOR.sizeBytes)
        assertTrue(projectors.all { it.downloadUrl.startsWith("https://huggingface.co/google/") })
    }

    @Test
    fun attachmentMimeDetectionRejectsLegacyOfficeAndArchives() {
        assertEquals(AttachmentKind.IMAGE, AttachmentTypeDetector.detect("photo.heic", "image/heic"))
        assertEquals(AttachmentKind.PDF, AttachmentTypeDetector.detect("notes.pdf", "application/pdf"))
        assertEquals(AttachmentKind.DOCX, AttachmentTypeDetector.detect("report.docx", "application/octet-stream"))
        assertEquals(null, AttachmentTypeDetector.detect("legacy.doc", "application/msword"))
        assertEquals(null, AttachmentTypeDetector.detect("archive.zip", "application/zip"))
    }
}
