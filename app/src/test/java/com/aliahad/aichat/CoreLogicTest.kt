package com.aliahad.aichat

import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.model.GgufValidator
import com.aliahad.aichat.model.ModelConstants
import com.aliahad.aichat.attachment.AttachmentTypeDetector
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.ContextVerificationState
import com.aliahad.aichat.core.ModelContextProfile
import com.aliahad.aichat.context.ContextCandidates
import com.aliahad.aichat.context.ContextMemoryPolicy
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
    fun officialModelCatalogContainsOnlyGemmaE4b() {
        val models = ModelConstants.OFFICIAL_MODELS

        assertEquals(listOf(ModelConstants.GEMMA_4_E4B), models)
        assertEquals(models.size, models.map { it.id }.distinct().size)
        assertEquals(models.size, models.map { it.fileName }.distinct().size)
        assertEquals(5_154_941_280L, ModelConstants.GEMMA_4_E4B.sizeBytes)
        assertEquals(
            "676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee",
            ModelConstants.GEMMA_4_E4B.sha256,
        )
        assertTrue(ModelConstants.GEMMA_4_E4B.downloadUrl.endsWith("gemma-4-E4B_q4_0-it.gguf"))
        assertTrue(!ModelConstants.GEMMA_4_E4B.downloadUrl.contains("/resolve/main/"))
    }

    @Test
    fun officialProjectorCatalogMatchesModelsAndChecksums() {
        val projectors = ModelConstants.OFFICIAL_PROJECTORS

        assertEquals(listOf(ModelConstants.GEMMA_4_E4B_PROJECTOR), projectors)
        assertEquals(
            ModelConstants.OFFICIAL_MODELS.map { it.id }.toSet(),
            projectors.map { it.modelId }.toSet(),
        )
        assertEquals(ModelConstants.GEMMA_4_E4B.id, ModelConstants.GEMMA_4_E4B_PROJECTOR.modelId)
        assertEquals(991_552_256L, ModelConstants.GEMMA_4_E4B_PROJECTOR.sizeBytes)
        assertEquals(
            "7498a37cb619e55f2fcf87eb931f56e99389ed6d432e4c5c66110694c0d65578",
            ModelConstants.GEMMA_4_E4B_PROJECTOR.sha256,
        )
        assertTrue(projectors.all { it.downloadUrl.startsWith("https://huggingface.co/google/") })
    }

    @Test
    fun attachmentMimeDetectionRejectsLegacyOfficeAndArchives() {
        assertEquals(AttachmentKind.IMAGE, AttachmentTypeDetector.detect("photo.heic", "image/heic"))
        assertEquals(AttachmentKind.AUDIO, AttachmentTypeDetector.detect("recording.wav", "audio/wav"))
        assertEquals(AttachmentKind.AUDIO, AttachmentTypeDetector.detect("meeting.flac", "application/octet-stream"))
        assertEquals(AttachmentKind.PDF, AttachmentTypeDetector.detect("notes.pdf", "application/pdf"))
        assertEquals(AttachmentKind.DOCX, AttachmentTypeDetector.detect("report.docx", "application/octet-stream"))
        assertEquals(null, AttachmentTypeDetector.detect("legacy.doc", "application/msword"))
        assertEquals(null, AttachmentTypeDetector.detect("archive.zip", "application/zip"))
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
