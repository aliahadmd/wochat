package com.aliahad.aichat.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppSettingsRepositoryInstrumentedTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        // Delete the DataStore backing file so each test starts from defaults.
        File(context.filesDir, "preferences/settings.preferences_pb").delete()
        settings = AppSettingsRepository(context, TokenCipher(context))
    }

    @Test
    fun defaultBackendModeIsCpu() = runBlocking {
        assertEquals(com.aliahad.aichat.core.BackendMode.CPU, settings.backendMode.first())
    }

    @Test
    fun setBackendPersistsAndReadsBack() = runBlocking {
        settings.setBackend(com.aliahad.aichat.core.BackendMode.VULKAN)
        assertEquals(com.aliahad.aichat.core.BackendMode.VULKAN, settings.backendMode.first())

        settings.setBackend(com.aliahad.aichat.core.BackendMode.CPU)
        assertEquals(com.aliahad.aichat.core.BackendMode.CPU, settings.backendMode.first())
    }

    @Test
    fun defaultLastQualityModeIsFast() = runBlocking {
        assertEquals(com.aliahad.aichat.core.ChatQualityMode.FAST, settings.lastQualityMode.first())
    }

    @Test
    fun setLastQualityModeRoundTrip() = runBlocking {
        settings.setLastQualityMode(com.aliahad.aichat.core.ChatQualityMode.BEST)
        assertEquals(com.aliahad.aichat.core.ChatQualityMode.BEST, settings.lastQualityMode.first())
    }

    @Test
    fun defaultMemoryEnabledIsTrue() = runBlocking {
        assertTrue(settings.memoryEnabled.first())
    }

    @Test
    fun setMemoryEnabledRoundTrip() = runBlocking {
        settings.setMemoryEnabled(false)
        assertFalse(settings.memoryEnabled.first())
        settings.setMemoryEnabled(true)
        assertTrue(settings.memoryEnabled.first())
    }

    @Test
    fun defaultCollectionPausedIsFalse() = runBlocking {
        assertFalse(settings.collectionPaused.first())
    }

    @Test
    fun setCollectionPausedRoundTrip() = runBlocking {
        settings.setCollectionPaused(true)
        assertTrue(settings.collectionPaused.first())
    }

    @Test
    fun defaultGenerationSettingsAreNormalized() = runBlocking {
        val defaults = settings.generationSettings.first()
        assertEquals(1024, defaults.maxNewTokens)
        assertEquals(8192, defaults.maxAnswerTokens)
        assertEquals(0.3f, defaults.temperature)
        assertFalse(defaults.thinkingEnabled)
        assertEquals("You are a helpful, concise assistant.", defaults.systemPrompt)
    }

    @Test
    fun updateGenerationPersistsNormalizedValues() = runBlocking {
        val custom = com.aliahad.aichat.core.GenerationSettings(
            maxNewTokens = 512,
            maxAnswerTokens = 4096,
            temperature = 0.7f,
            thinkingEnabled = true,
            systemPrompt = "Be terse.",
        )
        settings.updateGeneration(custom)
        val read = settings.generationSettings.first()
        assertEquals(512, read.maxNewTokens)
        assertEquals(4096, read.maxAnswerTokens)
        assertEquals(0.7f, read.temperature, 0.001f)
        assertTrue(read.thinkingEnabled)
        assertEquals("Be terse.", read.systemPrompt)
    }

    @Test
    fun updateGenerationClampsOutOfRangeValues() = runBlocking {
        val extreme = com.aliahad.aichat.core.GenerationSettings(
            maxNewTokens = 99_999,
            maxAnswerTokens = 1,
            temperature = -5f,
            systemPrompt = "   ",
        )
        settings.updateGeneration(extreme)
        val read = settings.generationSettings.first()
        // normalized() clamps maxNewTokens to 2048, maxAnswerTokens to [segmentLimit, 8192],
        // temperature to [0, 2], and empty systemPrompt to the default.
        assertEquals(2048, read.maxNewTokens)
        assertEquals(2048, read.maxAnswerTokens)
        assertEquals(0f, read.temperature, 0.001f)
        assertEquals("You are a helpful, concise assistant.", read.systemPrompt)
    }

    @Test
    fun vulkanQuarantineRoundTrip() = runBlocking {
        val model = "abc123"
        val device = "deviceX"
        val revision = "rev1"

        assertFalse(settings.isVulkanQuarantined(model, device, revision))

        settings.quarantineVulkan(model, device, revision)
        assertTrue(settings.isVulkanQuarantined(model, device, revision))

        settings.clearVulkanQuarantine(model, device, revision)
        assertFalse(settings.isVulkanQuarantined(model, device, revision))
    }

    @Test
    fun quarantineVulkanSwitchesBackendToCpu() = runBlocking {
        settings.setBackend(com.aliahad.aichat.core.BackendMode.VULKAN)
        settings.quarantineVulkan("model1", "device1", "rev1")
        assertEquals(com.aliahad.aichat.core.BackendMode.CPU, settings.backendMode.first())
    }

    @Test
    fun effectiveBackendFallsBackToCpuWhenQuarantined() = runBlocking {
        settings.setBackend(com.aliahad.aichat.core.BackendMode.VULKAN)
        val model = "sha256model"
        val device = "fp"
        val revision = "r1"
        settings.quarantineVulkan(model, device, revision)

        val effective = settings.effectiveBackend(model, device, revision)
        assertEquals(com.aliahad.aichat.core.BackendMode.CPU, effective)
    }

    @Test
    fun effectiveBackendReturnsVulkanWhenNotQuarantined() = runBlocking {
        settings.setBackend(com.aliahad.aichat.core.BackendMode.VULKAN)
        val effective = settings.effectiveBackend("clean_model", "device", "rev")
        assertEquals(com.aliahad.aichat.core.BackendMode.VULKAN, effective)
    }

    @Test
    fun effectiveBackendAutoWithNoChoiceFallsToCpu() = runBlocking {
        settings.setBackend(com.aliahad.aichat.core.BackendMode.AUTO)
        val effective = settings.effectiveBackend()
        assertEquals(com.aliahad.aichat.core.BackendMode.CPU, effective)
    }

    @Test
    fun setChosenAutoBackendRejectsAuto() = runBlocking {
        try {
            settings.setChosenAutoBackend(com.aliahad.aichat.core.BackendMode.AUTO)
            assertTrue("Should have thrown", false)
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun setChosenAutoBackendPersists() = runBlocking {
        assertNull(settings.chosenAutoBackend.first())
        settings.setChosenAutoBackend(com.aliahad.aichat.core.BackendMode.VULKAN)
        assertEquals(com.aliahad.aichat.core.BackendMode.VULKAN, settings.chosenAutoBackend.first())
    }

    @Test
    fun effectiveBackendAutoUsesChosenBackend() = runBlocking {
        settings.setBackend(com.aliahad.aichat.core.BackendMode.AUTO)
        settings.setChosenAutoBackend(com.aliahad.aichat.core.BackendMode.VULKAN)
        assertEquals(
            com.aliahad.aichat.core.BackendMode.VULKAN,
            settings.effectiveBackend("unquarantined", "dev", "rev"),
        )
    }

    @Test
    fun selectCpuAfterVulkanRejectionSwitchesFromVulkan() = runBlocking {
        settings.setBackend(com.aliahad.aichat.core.BackendMode.VULKAN)
        settings.selectCpuAfterVulkanRejection()
        assertEquals(com.aliahad.aichat.core.BackendMode.CPU, settings.backendMode.first())
    }

    @Test
    fun selectCpuAfterVulkanRejectionSwitchesChosenWhenAuto() = runBlocking {
        settings.setBackend(com.aliahad.aichat.core.BackendMode.AUTO)
        settings.setChosenAutoBackend(com.aliahad.aichat.core.BackendMode.VULKAN)
        settings.selectCpuAfterVulkanRejection()
        assertEquals(com.aliahad.aichat.core.BackendMode.CPU, settings.chosenAutoBackend.first())
    }

    @Test
    fun vulkanQuarantineIsMatchedByModelAlone() = runBlocking {
        settings.quarantineVulkan("modelSha", "devA", "revA")
        assertTrue(settings.isVulkanQuarantined("modelSha"))
        assertFalse(settings.isVulkanQuarantined("differentModel"))
    }

    @Test
    fun allowMeteredModelDownloadsDefaultAndRoundTrip() = runBlocking {
        assertFalse(settings.allowMeteredModelDownloads.first())
        settings.setAllowMeteredModelDownloads(true)
        assertTrue(settings.allowMeteredModelDownloads.first())
    }

    @Test
    fun tokenOperationsRoundTrip() {
        settings.clearToken()
        assertFalse(settings.hasToken())
        assertNull(settings.token())

        settings.saveToken("hf_testToken123")
        assertTrue(settings.hasToken())
        assertEquals("hf_testToken123", settings.token())

        val masked = settings.maskedToken()
        assertTrue(masked != null && masked!!.contains("••••"))

        settings.clearToken()
        assertFalse(settings.hasToken())
    }
}
