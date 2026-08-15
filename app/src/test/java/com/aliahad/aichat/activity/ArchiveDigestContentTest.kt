package com.aliahad.aichat.activity

import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.core.MemorySensitivity
import com.aliahad.aichat.data.ActivityEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the archive compaction summary helpers: the deterministic fallback,
 * the digest selection fallback guarantees, and the capped extraction prompt.
 */
class ArchiveDigestContentTest {

    @Test
    fun fallbackContentIsDeterministic() {
        val first = archiveFallbackContent(ActivitySource.APP_USAGE, 42, "com.app.one (9), com.app.two (3)")
        val second = archiveFallbackContent(ActivitySource.APP_USAGE, 42, "com.app.one (9), com.app.two (3)")

        assertEquals(first, second)
        assertEquals(
            "42 app_usage events were archived. Most frequent apps: com.app.one (9), com.app.two (3).",
            first,
        )
        assertEquals(
            "7 notification events were archived.",
            archiveFallbackContent(ActivitySource.NOTIFICATION, 7, ""),
        )
    }

    @Test
    fun topPackagesTextRanksByCountAndLimitsToFive() {
        val events = buildList {
            repeat(4) { add(event(packageName = "com.fourth")) }
            repeat(9) { add(event(packageName = "com.first")) }
            repeat(6) { add(event(packageName = "com.second")) }
            repeat(5) { add(event(packageName = "com.third")) }
            repeat(3) { add(event(packageName = "com.fifth")) }
            repeat(2) { add(event(packageName = "com.sixth")) }
            repeat(2) { add(event(packageName = null)) }
        }

        val text = archiveTopPackagesText(events)

        assertEquals(
            "com.first (9), com.second (6), com.third (5), com.fourth (4), com.fifth (3)",
            text,
        )
    }

    @Test
    fun selectArchiveContentKeepsFallbackWhenEnrichedMissingOrBlank() {
        val fallback = "12 sensor events were archived."

        assertEquals(fallback, selectArchiveContent(fallback, null))
        assertEquals(fallback, selectArchiveContent(fallback, "   "))
    }

    @Test
    fun selectArchiveContentAppendsDigestAndCapsIt() {
        val fallback = "12 app_usage events were archived."

        val enriched = selectArchiveContent(fallback, "  Heavy messaging and map usage.  ")
        assertTrue(enriched.startsWith("$fallback\nDigest: Heavy messaging and map usage."))

        val capped = selectArchiveContent(fallback, "d".repeat(5_000))
        val digest = capped.substringAfter("Digest: ")
        assertEquals(ARCHIVE_DIGEST_MAX_CHARS, digest.length)
    }

    @Test
    fun digestPromptIncludesEventDetailsAndCapsInput() {
        val events = (1..2_000).map { index ->
            event(title = "Calendar event number $index with a fairly long descriptive title")
        }

        val prompt = archiveDigestPrompt(ActivitySource.CALENDAR, events)!!

        assertTrue(prompt.contains("archived calendar activity events"))
        assertTrue(prompt.contains("Calendar event number 1 "))
        assertTrue(prompt.length < 13_500)
    }

    @Test
    fun digestPromptIsNullWhenEventsCarryNoDescribableDetail() {
        val events = listOf(
            event(eventType = "", title = null, packageName = null),
            event(eventType = "", title = "  ", packageName = null),
        )

        assertNull(archiveDigestPrompt(ActivitySource.SENSOR, events))
        assertNull(archiveDigestPrompt(ActivitySource.SENSOR, emptyList()))
    }

    @Test
    fun digestPromptFallsBackToPackageNameWhenTitleMissing() {
        val prompt = archiveDigestPrompt(
            ActivitySource.APP_USAGE,
            listOf(event(title = null, packageName = "com.example.app")),
        )

        assertTrue(prompt!!.contains("foreground_session com.example.app"))
    }

    private fun event(
        eventType: String = "foreground_session",
        title: String? = "Title",
        packageName: String? = "com.example",
    ) = ActivityEventEntity(
        id = "id",
        source = ActivitySource.APP_USAGE,
        eventType = eventType,
        startedAt = 1L,
        endedAt = 2L,
        packageName = packageName,
        title = title,
        redactedText = null,
        metadataJson = "{}",
        sensitivity = MemorySensitivity.NORMAL,
        pinned = false,
        compactedIntoId = null,
        createdAt = 1L,
    )
}
