package com.aliahad.aichat

import com.aliahad.aichat.activity.COLLECTIBLE_SOURCES
import com.aliahad.aichat.activity.OfficeWorkScheduler
import com.aliahad.aichat.activity.collectorName
import com.aliahad.aichat.activity.healthExerciseStableKey
import com.aliahad.aichat.activity.healthSleepStableKey
import com.aliahad.aichat.activity.healthStepsStableKey
import com.aliahad.aichat.activity.isPeriodicWorkHealthy
import com.aliahad.aichat.activity.shouldCollectSourceNow
import com.aliahad.aichat.activity.sourceIntervalMillis
import com.aliahad.aichat.core.ActivitySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class OfficeWorkerConsolidationTest {
    private val sixHours = TimeUnit.HOURS.toMillis(6)

    @Test
    fun collectAllCoversExactlyThePeriodicCollectionSources() {
        assertEquals(
            setOf(
                ActivitySource.APP_USAGE,
                ActivitySource.APP_INSTALL,
                ActivitySource.LOCATION,
                ActivitySource.SENSOR,
                ActivitySource.CONTACT,
                ActivitySource.CALENDAR,
                ActivitySource.HEALTH,
            ),
            COLLECTIBLE_SOURCES.toSet(),
        )
    }

    @Test
    fun scheduledWorkNamesContainConsolidatedWorkAndRetentionOnly() {
        assertEquals(
            listOf(
                OfficeWorkScheduler.WORK_COLLECT_ALL,
                OfficeWorkScheduler.WORK_MEMORY_RETENTION,
            ),
            OfficeWorkScheduler.scheduledWorkNames,
        )
    }

    @Test
    fun collectorNamesMatchExistingCheckpointKeys() {
        assertEquals("usage", collectorName(ActivitySource.APP_USAGE))
        assertEquals("installed-apps", collectorName(ActivitySource.APP_INSTALL))
        assertEquals("location", collectorName(ActivitySource.LOCATION))
        assertEquals("sensor", collectorName(ActivitySource.SENSOR))
        assertEquals("contacts", collectorName(ActivitySource.CONTACT))
        assertEquals("calendar", collectorName(ActivitySource.CALENDAR))
        assertEquals("health", collectorName(ActivitySource.HEALTH))
    }

    @Test
    fun sourcesWithCadenceIntervalsAlwaysRun() {
        val now = TimeUnit.DAYS.toMillis(10)
        listOf(ActivitySource.APP_USAGE, ActivitySource.LOCATION, ActivitySource.SENSOR, ActivitySource.HEALTH)
            .forEach { source ->
                assertEquals(sixHours, sourceIntervalMillis(source))
                assertTrue(
                    shouldCollectSourceNow(
                        lastCollectedAt = now - 1,
                        intervalMillis = sourceIntervalMillis(source),
                        cadenceMillis = sixHours,
                        now = now,
                    ),
                )
            }
    }

    @Test
    fun longIntervalSourcesSkipWhileCheckpointIsFresh() {
        val now = TimeUnit.DAYS.toMillis(10)
        listOf(ActivitySource.CONTACT, ActivitySource.CALENDAR).forEach { source ->
            assertEquals(TimeUnit.HOURS.toMillis(12), sourceIntervalMillis(source))
            assertFalse(
                "expected ${source.name} to be skipped while fresh",
                shouldCollectSourceNow(
                    lastCollectedAt = now - TimeUnit.HOURS.toMillis(7),
                    intervalMillis = sourceIntervalMillis(source),
                    cadenceMillis = sixHours,
                    now = now,
                ),
            )
        }
        assertFalse(
            shouldCollectSourceNow(
                lastCollectedAt = now - TimeUnit.HOURS.toMillis(20),
                intervalMillis = sourceIntervalMillis(ActivitySource.APP_INSTALL),
                cadenceMillis = sixHours,
                now = now,
            ),
        )
    }

    @Test
    fun longIntervalSourcesRunWhenCheckpointIsStaleOrMissing() {
        val now = TimeUnit.DAYS.toMillis(10)
        assertTrue(
            shouldCollectSourceNow(
                lastCollectedAt = null,
                intervalMillis = sourceIntervalMillis(ActivitySource.CONTACT),
                cadenceMillis = sixHours,
                now = now,
            ),
        )
        assertTrue(
            shouldCollectSourceNow(
                lastCollectedAt = now - TimeUnit.HOURS.toMillis(13),
                intervalMillis = sourceIntervalMillis(ActivitySource.CALENDAR),
                cadenceMillis = sixHours,
                now = now,
            ),
        )
        assertTrue(
            shouldCollectSourceNow(
                lastCollectedAt = now - TimeUnit.DAYS.toMillis(2),
                intervalMillis = sourceIntervalMillis(ActivitySource.APP_INSTALL),
                cadenceMillis = sixHours,
                now = now,
            ),
        )
    }

    @Test
    fun periodicWorkIsHealthyWhenEnqueuedOrRunning() {
        assertTrue(isPeriodicWorkHealthy(listOf("ENQUEUED")))
        assertTrue(isPeriodicWorkHealthy(listOf("RUNNING")))
        assertTrue(isPeriodicWorkHealthy(listOf("SUCCEEDED", "ENQUEUED")))
    }

    @Test
    fun periodicWorkNeedsRepairWhenMissingOrDead() {
        assertFalse(isPeriodicWorkHealthy(emptyList()))
        assertFalse(isPeriodicWorkHealthy(listOf("CANCELLED")))
        assertFalse(isPeriodicWorkHealthy(listOf("FAILED", "SUCCEEDED")))
        assertFalse(isPeriodicWorkHealthy(listOf("BLOCKED")))
    }

    @Test
    fun healthMetricKeysAreScopedToTheCollectionWindow() {
        val windowStart = 1_781_200_000_000L
        // Keys derive from the window itself, not from the collection instant or
        // the local day: same window -> same key, different window -> new key.
        assertEquals("health:steps:$windowStart", healthStepsStableKey(windowStart))
        assertEquals("health:sleep:$windowStart", healthSleepStableKey(windowStart))
        assertEquals(healthStepsStableKey(windowStart), healthStepsStableKey(windowStart))
        assertNotEquals(
            healthStepsStableKey(windowStart),
            healthStepsStableKey(windowStart + TimeUnit.HOURS.toMillis(6)),
        )
        assertNotEquals(
            healthSleepStableKey(windowStart),
            healthSleepStableKey(windowStart + TimeUnit.HOURS.toMillis(6)),
        )
    }

    @Test
    fun exerciseKeysFollowSessionIdentityNotPosition() {
        val title = "Exercise session · 30 min"
        val first = healthExerciseStableKey(1_781_200_000_000L, title)
        val second = healthExerciseStableKey(1_781_203_600_000L, title)
        // Same identity re-collected dedups; a different session start is distinct
        // even at the same position in the session list.
        assertEquals(first, healthExerciseStableKey(1_781_200_000_000L, title))
        assertNotEquals(first, second)
        assertNotEquals(
            healthExerciseStableKey(1_781_200_000_000L, title),
            healthExerciseStableKey(1_781_200_000_000L, "Exercise session · 45 min"),
        )
    }
}
