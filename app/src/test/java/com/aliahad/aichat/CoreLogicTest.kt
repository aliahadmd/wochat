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
import com.aliahad.aichat.core.ActionRisk
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.ContextVerificationState
import com.aliahad.aichat.core.DeviceAction
import com.aliahad.aichat.core.DeviceActionKind
import com.aliahad.aichat.core.ModelContextProfile
import com.aliahad.aichat.context.ContextCandidates
import com.aliahad.aichat.context.ContextMemoryPolicy
import com.aliahad.aichat.device.ActionPolicyEngine
import com.aliahad.aichat.residency.ModelLoadSignature
import com.aliahad.aichat.residency.ModelResidencyState
import com.aliahad.aichat.residency.residencyContextDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreLogicTest {
    @Test
    fun residencyNotificationDistinguishesFallbackFromVerifiedContext() {
        val failed = ModelResidencyState.Ready(
            modelName = "Gemma",
            contextSize = 4_096,
            declaredContextSize = 262_144,
            loadMillis = 1,
            verifiedContextSize = 0,
            contextVerificationState = ContextVerificationState.FAILED,
        )
        assertEquals(
            "CPU · 4K active fallback · baseline verification failed · 256K model maximum",
            residencyContextDescription(failed),
        )

        val limited = failed.copy(
            contextSize = 16_384,
            verifiedContextSize = 16_384,
            contextVerificationState = ContextVerificationState.LIMITED,
        )
        assertEquals(
            "CPU · 16K verified · 256K model maximum",
            residencyContextDescription(limited),
        )
    }

    @Test
    fun generationSettingsOnlyContainUserControlledGenerationPreferences() {
        val settings = GenerationSettings(
            maxNewTokens = 50_000,
            temperature = 9f,
            systemPrompt = " ",
        ).normalized()

        assertEquals(2048, settings.maxNewTokens)
        assertEquals(8192, settings.maxAnswerTokens)
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
    fun automaticContextCandidatesContinueAboveLargestVerifiedSize() {
        val profile = contextProfile(
            declared = 131_072,
            verified = 8_192,
            state = ContextVerificationState.VERIFIED,
        )

        assertEquals(
            listOf(16_384, 32_768, 65_536, 131_072),
            ContextCandidates.remaining(profile),
        )
        assertTrue(
            ContextCandidates.remaining(
                profile.copy(state = ContextVerificationState.LIMITED),
            ).isEmpty(),
        )
    }

    @Test
    fun automaticContextCandidatesRespectSmallDeclaredLimit() {
        val profile = contextProfile(
            declared = 2_048,
            verified = 0,
            state = ContextVerificationState.UNVERIFIED,
        )

        assertEquals(listOf(2_048), ContextCandidates.remaining(profile))
    }

    @Test
    fun contextMemoryPolicyKeepsHyperOsBelowItsProcessPssGuard() {
        val gib = 1_024L * 1_024 * 1_024
        val mib = 1_024L * 1_024

        assertEquals(
            6L * gib - 128L * mib,
            ContextMemoryPolicy.stablePssCeilingBytes("Xiaomi", 16L * gib),
        )
        assertEquals(
            16L * gib * 4 / 5,
            ContextMemoryPolicy.stablePssCeilingBytes("Google", 16L * gib),
        )
    }

    @Test
    fun officialModelCatalogContainsIndependentVerifiedArtifacts() {
        val models = ModelConstants.OFFICIAL_MODELS

        assertEquals(3, models.size)
        assertEquals(models.size, models.map { it.id }.distinct().size)
        assertEquals(models.size, models.map { it.fileName }.distinct().size)
        assertEquals(3_349_514_112L, ModelConstants.GEMMA_4_E2B.sizeBytes)
        assertEquals(
            "3646b4c147cd235a44d91df1546d3b7d8e29b547dbe4e1f80856419aa455e6fd",
            ModelConstants.GEMMA_4_E2B.sha256,
        )
        assertTrue(ModelConstants.GEMMA_4_E2B.downloadUrl.endsWith("gemma-4-E2B_q4_0-it.gguf"))
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

        assertEquals(3, projectors.size)
        assertEquals(
            ModelConstants.OFFICIAL_MODELS.map { it.id }.toSet(),
            projectors.map { it.modelId }.toSet(),
        )
        assertEquals(ModelConstants.GEMMA_4_E2B.id, ModelConstants.GEMMA_4_E2B_PROJECTOR.modelId)
        assertEquals(986_833_312L, ModelConstants.GEMMA_4_E2B_PROJECTOR.sizeBytes)
        assertEquals(
            "58c187648007cab392bd5678b87e862c3e8794017deb945feea2cf256195e96a",
            ModelConstants.GEMMA_4_E2B_PROJECTOR.sha256,
        )
        assertTrue(
            ModelConstants.GEMMA_4_E2B_PROJECTOR.downloadUrl
                .endsWith("gemma-4-E2B-it-mmproj.gguf"),
        )
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

    @Test
    fun deviceActionPolicyOnlyAllowsSafeUriSchemesWithoutConfirmation() {
        fun risk(target: String?) = ActionPolicyEngine.classify(
            DeviceAction(
                id = "action",
                kind = DeviceActionKind.OPEN_URI,
                packageName = null,
                target = target,
                value = null,
                risk = ActionRisk.LOW,
            ),
        )

        assertEquals(ActionRisk.LOW, risk("https://example.com/path"))
        assertEquals(ActionRisk.SENSITIVE, risk("tel:+15551234567"))
        assertEquals(ActionRisk.SENSITIVE, risk("mailto:person@example.com"))
        assertEquals(ActionRisk.BLOCKED, risk("intent://example/#Intent;scheme=https;end"))
        assertEquals(ActionRisk.BLOCKED, risk("file:///data/local/tmp/private"))
        assertEquals(ActionRisk.BLOCKED, risk("javascript:alert(1)"))
        assertEquals(ActionRisk.BLOCKED, risk(null))
    }

    private fun contextProfile(
        declared: Int,
        verified: Int,
        state: ContextVerificationState,
    ) = ModelContextProfile(
        id = "profile",
        modelId = "model",
        modelSha256 = "sha",
        deviceFingerprint = "device",
        physicalRamBytes = 16L * 1024 * 1024 * 1024,
        swapBytes = 16L * 1024 * 1024 * 1024,
        backend = BackendMode.CPU,
        llamaRevision = "revision",
        declaredContextTokens = declared,
        verifiedContextTokens = verified,
        lastAttemptedTokens = verified.takeIf { it > 0 },
        state = state,
        peakPssBytes = null,
        peakRssBytes = null,
        peakSwapBytes = null,
        failureReason = null,
        verifiedAt = null,
        updatedAt = 1,
    )
}
