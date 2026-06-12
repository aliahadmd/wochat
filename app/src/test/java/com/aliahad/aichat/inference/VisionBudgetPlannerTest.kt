package com.aliahad.aichat.inference

import com.aliahad.aichat.core.AttachmentContext
import com.aliahad.aichat.core.ChatQualityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VisionBudgetPlannerTest {
    private val image = AttachmentContext(
        attachmentId = "image",
        displayName = "image.jpg",
        extractedText = "",
        imagePaths = listOf("/private/image.jpg"),
        selectedPages = emptySet(),
        imageTokenBudget = 560,
    )

    @Test
    fun fastModeScalesDetailFromBriefToOcr() {
        assertEquals(70, allocate(ChatQualityMode.FAST, "Describe this briefly"))
        assertEquals(140, allocate(ChatQualityMode.FAST, "What is in this image?"))
        assertEquals(280, allocate(ChatQualityMode.FAST, "Analyze this chart"))
        assertEquals(560, allocate(ChatQualityMode.FAST, "Read the exact text"))
    }

    @Test
    fun bestModeKeepsHigherVisualDetail() {
        assertEquals(280, allocate(ChatQualityMode.BEST, "Give a quick overview"))
        assertEquals(560, allocate(ChatQualityMode.BEST, "What is in this image?"))
        assertEquals(1120, allocate(ChatQualityMode.BEST, "Transcribe the exact text"))
    }

    @Test
    fun availableContextReducesBudgetWithoutDroppingImage() {
        assertEquals(
            140,
            VisionBudgetPlanner.allocate(
                ChatQualityMode.FAST,
                listOf(image),
                "Read the exact text",
                availableTokens = 200,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            VisionBudgetPlanner.allocate(
                ChatQualityMode.FAST,
                listOf(image, image),
                "Describe these",
                availableTokens = 100,
            )
        }
    }

    private fun allocate(mode: ChatQualityMode, prompt: String): Int =
        VisionBudgetPlanner.allocate(mode, listOf(image), prompt, availableTokens = 2048)
}
