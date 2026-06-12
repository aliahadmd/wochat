package com.aliahad.aichat.inference

import com.aliahad.aichat.core.AttachmentContext
import com.aliahad.aichat.core.ChatQualityMode

internal object VisionBudgetPlanner {
    fun allocate(
        mode: ChatQualityMode,
        attachments: List<AttachmentContext>,
        prompt: String,
        availableTokens: Int,
    ): Int {
        val imageCount = attachments.sumOf { it.imagePaths.size }
        if (imageCount <= 0) return MIN_VISUAL_TOKENS
        require(availableTokens >= imageCount * MIN_VISUAL_TOKENS) {
            "All current attachments cannot fit. Increase context or remove an attachment."
        }

        val keywords = prompt.lowercase()
            .split(Regex("[^\\p{L}\\p{N}_]+"))
            .filter { it.length >= 3 }
            .toSet()
        val preferred = when (mode) {
            ChatQualityMode.FAST -> when {
                keywords.any(HIGH_DETAIL_KEYWORDS::contains) -> 560
                keywords.any(ANALYSIS_KEYWORDS::contains) -> 280
                keywords.any(COMPACT_KEYWORDS::contains) -> 70
                else -> 140
            }
            ChatQualityMode.BEST -> when {
                keywords.any(HIGH_DETAIL_KEYWORDS::contains) -> 1120
                keywords.any(COMPACT_KEYWORDS::contains) -> 280
                else -> 560
            }
        }
        val availablePerImage = (availableTokens / imageCount).coerceAtLeast(MIN_VISUAL_TOKENS)
        return SUPPORTED_VISUAL_BUDGETS.lastOrNull {
            it <= minOf(preferred, availablePerImage)
        } ?: MIN_VISUAL_TOKENS
    }

    private const val MIN_VISUAL_TOKENS = 70
    private val SUPPORTED_VISUAL_BUDGETS = listOf(70, 140, 280, 560, 1120)
    private val HIGH_DETAIL_KEYWORDS = setOf(
        "exact",
        "ocr",
        "read",
        "text",
        "transcribe",
    )
    private val ANALYSIS_KEYWORDS = setOf(
        "analyze",
        "chart",
        "code",
        "compare",
        "document",
        "extract",
        "handwriting",
        "inspect",
        "number",
        "table",
    )
    private val COMPACT_KEYWORDS = setOf(
        "brief",
        "briefly",
        "overview",
        "quick",
        "quickly",
    )
}
