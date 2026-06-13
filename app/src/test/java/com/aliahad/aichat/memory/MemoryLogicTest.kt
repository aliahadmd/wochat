package com.aliahad.aichat.memory

import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.core.MemorySensitivity
import com.aliahad.aichat.data.ActivityEventEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MemoryLogicTest {
    @Test
    fun redactorRemovesCredentialsAndPaymentNumbers() {
        val value = SensitiveTextRedactor.redact(
            "My OTP is 123456, password: secret123 and card 4111 1111 1111 1111",
        )

        assertFalse(value.contains("123456"))
        assertFalse(value.contains("secret123"))
        assertFalse(value.contains("4111"))
        assertTrue(value.contains("[redacted]"))
    }

    @Test
    fun calendarQuestionTargetsCalendarAndToday() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDate.of(2026, 6, 12)
            .atTime(15, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val intent = activityRetrievalIntent("What is on my calendar today?", now, zone)

        assertEquals(setOf(ActivitySource.CALENDAR), intent.sources)
        assertEquals(
            LocalDate.of(2026, 6, 12).atStartOfDay(zone).toInstant().toEpochMilli(),
            intent.periodStart,
        )
        assertEquals(now, intent.periodEnd)
    }

    @Test
    fun unrelatedQuestionDoesNotRequestPhoneActivity() {
        val intent = activityRetrievalIntent(
            query = "Explain how photosynthesis works",
            now = 1_781_280_000_000,
            zoneId = ZoneId.of("UTC"),
        )

        assertTrue(intent.sources.isEmpty())
        assertFalse(intent.broadPhoneActivity)
        assertEquals(null, intent.periodStart)
        assertEquals(null, intent.periodEnd)
    }

    @Test
    fun appUsageIsFormattedAsAReadableSession() {
        val zone = ZoneId.of("UTC")
        val start = LocalDate.of(2026, 6, 12)
            .atTime(10, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val event = ActivityEventEntity(
            id = "event",
            source = ActivitySource.APP_USAGE,
            eventType = "foreground_session",
            startedAt = start,
            endedAt = start + 12 * 60_000,
            packageName = "com.example.notes",
            title = "Notes",
            redactedText = null,
            metadataJson = """{"foregroundMillis":720000}""",
            sensitivity = MemorySensitivity.PRIVATE,
            compactedIntoId = null,
            createdAt = start,
        )

        val formatted = event.formatActivityForPrompt(zone)

        assertTrue(formatted.contains("Used Notes"))
        assertTrue(formatted.contains("12m"))
        assertFalse(formatted.contains("Metadata"))
    }

    @Test
    fun promptBudgetReservesSystemPromptBeforeSelectingHistory() {
        assertEquals(
            1_952,
            initialPromptTokensRemaining(
                contextTokens = 4_096,
                outputReserve = 1_024,
                safetyReserve = 192,
                currentTokens = 416,
                systemTokens = 512,
            ),
        )
    }

    @Test
    fun promptBudgetReservesSelectedSkillTokensBeforeSelectingHistory() {
        assertEquals(
            1_152,
            initialPromptTokensRemaining(
                contextTokens = 4_096,
                outputReserve = 1_024,
                safetyReserve = 192,
                currentTokens = 416,
                systemTokens = 1_312,
            ),
        )
    }
}
