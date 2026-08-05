package com.aliahad.aichat.residency

import com.aliahad.aichat.core.BackendMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelResidencyLogicTest {
    @Test
    fun cachedVulkanSignatureDoesNotMatchEngineRecoveredToCpu() {
        val signature = ModelLoadSignature(
            path = "/models/e2b.gguf",
            contextSize = 8_192,
            temperature = 0.2f,
            backend = BackendMode.VULKAN,
        )

        assertFalse(
            signature.matchesLoadedBase(
                expectedPath = signature.path,
                expectedContextSize = signature.contextSize,
                expectedTemperature = signature.temperature,
                expectedBackend = BackendMode.VULKAN,
                actualBackend = BackendMode.CPU,
                loadedPath = signature.path,
                activeContextSize = signature.contextSize,
            ),
        )
        assertTrue(
            signature.matchesLoadedBase(
                expectedPath = signature.path,
                expectedContextSize = signature.contextSize,
                expectedTemperature = signature.temperature,
                expectedBackend = BackendMode.VULKAN,
                actualBackend = BackendMode.VULKAN,
                loadedPath = signature.path,
                activeContextSize = signature.contextSize,
            ),
        )
    }
}
