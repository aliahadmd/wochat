package com.aliahad.aichat.memory

import com.aliahad.aichat.core.MemorySensitivity
import com.aliahad.aichat.core.MemoryStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class MemoryLogicTest {
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

    @Test
    fun purgeCutoffIsThirtyDaysBeforeNow() {
        val now = 1_781_280_000_000L

        assertEquals(now - TimeUnit.DAYS.toMillis(30), memoryPurgeCutoff(now))
        assertEquals(now - TimeUnit.DAYS.toMillis(7), memoryPurgeCutoff(now, 7))
    }

    @Test
    fun purgePredicateSelectsOnlyExpiredNonActiveMemories() {
        val cutoff = 1_000_000L

        assertTrue(isMemoryPurgeable(MemoryStatus.DELETED, cutoff - 1, cutoff))
        assertTrue(isMemoryPurgeable(MemoryStatus.SUPERSEDED, cutoff - 1, cutoff))
        // Exactly at the cutoff the row is still inside the grace period.
        assertFalse(isMemoryPurgeable(MemoryStatus.DELETED, cutoff, cutoff))
        assertFalse(isMemoryPurgeable(MemoryStatus.DELETED, cutoff + 1, cutoff))
        assertFalse(isMemoryPurgeable(MemoryStatus.SUPERSEDED, cutoff + 1, cutoff))
        // Active memories are never physically purged regardless of age.
        assertFalse(isMemoryPurgeable(MemoryStatus.ACTIVE, cutoff - 1, cutoff))
    }
}
