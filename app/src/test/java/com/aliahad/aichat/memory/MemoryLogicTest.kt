package com.aliahad.aichat.memory

import com.aliahad.aichat.core.MemorySensitivity
import com.aliahad.aichat.core.MemoryStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.aliahad.aichat.core.MemoryType
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

    @Test
    fun oneWordRepliesAndQuestionsAreNotRemembered() {
        // The observed junk. Every user message used to become a memory, so the
        // store filled with rows containing "4", "26" and "What is 2 plus 2",
        // which were then injected into every turn's prompt.
        listOf(
            "4",
            "26",
            "What is 2 plus 2",
            "What is on my calendar today",
            "And what about tomorrow",
            "Explain gravity briefly",
        ).forEach { content ->
            assertFalse(
                "\"$content\" should not become a memory",
                isWorthRemembering(content, inferType(content)),
            )
        }
    }

    @Test
    fun statedFactsAndPreferencesAreStillRemembered() {
        // The guard against over-filtering: these are exactly what memory is for,
        // and two of them are shorter than the unclassified-statement threshold —
        // they qualify because they were classified, not because of their length.
        listOf(
            "My name is Ali",
            "I prefer dark roast coffee",
            "Remember that the office wifi password rotates monthly",
            "I am working on a local inference app called Offmind",
            "My goal is to ship the release this quarter",
        ).forEach { content ->
            assertTrue(
                "\"$content\" should be remembered",
                isWorthRemembering(content, inferType(content)),
            )
        }
    }

    @Test
    fun substantialUnclassifiedStatementIsKept() {
        // Not matched by any inferType pattern, but long, declarative and specific.
        val content = "The staging database migrates every Sunday at midnight UTC"
        assertTrue(isWorthRemembering(content, MemoryType.EPISODE))
    }

    @Test
    fun questionsAreNotRememberedEvenWhenTheyClassify() {
        // Found on the device: "What do I like?" was stored as a Preference, because
        // inferType matches substrings and "do I like" contains "i like". Being
        // classifiable does not make a question an assertion.
        listOf(
            "What do I like?",
            "Do I prefer tea or coffee?",
            "What is my name",
            "What am I working on right now",
        ).forEach { content ->
            assertFalse(
                "\"$content\" should not become a memory",
                isWorthRemembering(content, inferType(content)),
            )
        }
    }
}
