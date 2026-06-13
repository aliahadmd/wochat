package com.aliahad.aichat.inference

import com.aliahad.aichat.core.AttachmentContext
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
    fun mobileProfileScalesDetailFromBriefToOcr() {
        assertEquals(70, allocate(VisionDetailProfile.MOBILE, "Describe this briefly"))
        assertEquals(140, allocate(VisionDetailProfile.MOBILE, "What is in this image?"))
        assertEquals(280, allocate(VisionDetailProfile.MOBILE, "Analyze this chart"))
        assertEquals(560, allocate(VisionDetailProfile.MOBILE, "Read the exact text"))
    }

    @Test
    fun detailedProfileKeepsHigherVisualDetail() {
        assertEquals(280, allocate(VisionDetailProfile.DETAILED, "Give a quick overview"))
        assertEquals(560, allocate(VisionDetailProfile.DETAILED, "What is in this image?"))
        assertEquals(1120, allocate(VisionDetailProfile.DETAILED, "Transcribe the exact text"))
    }

    @Test
    fun availableContextReducesBudgetWithoutDroppingImage() {
        assertEquals(
            140,
            VisionBudgetPlanner.allocate(
                VisionDetailProfile.MOBILE,
                listOf(image),
                "Read the exact text",
                availableTokens = 200,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            VisionBudgetPlanner.allocate(
                VisionDetailProfile.MOBILE,
                listOf(image, image),
                "Describe these",
                availableTokens = 100,
            )
        }
    }

    private fun allocate(profile: VisionDetailProfile, prompt: String): Int =
        VisionBudgetPlanner.allocate(profile, listOf(image), prompt, availableTokens = 2048)
}
