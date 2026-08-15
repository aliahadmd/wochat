package com.aliahad.aichat.activity

import android.Manifest
import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aliahad.aichat.AiChatApplication
import com.aliahad.aichat.AppContainer
import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.data.ActivityEventEntity
import com.aliahad.aichat.data.CollectorCheckpointEntity
import com.aliahad.aichat.data.MemorySummaryEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Sources collected by the consolidated periodic worker and the collect-now one-time works. */
internal val COLLECTIBLE_SOURCES = listOf(
    ActivitySource.APP_USAGE,
    ActivitySource.APP_INSTALL,
    ActivitySource.LOCATION,
    ActivitySource.SENSOR,
    ActivitySource.CONTACT,
    ActivitySource.CALENDAR,
    ActivitySource.HEALTH,
)

/** Checkpoint collector key for the notification listener binding signal. */
const val NOTIFICATION_LISTENER_COLLECTOR = "notification-listener"

internal fun collectorName(source: ActivitySource): String = when (source) {
    ActivitySource.APP_USAGE -> "usage"
    ActivitySource.APP_INSTALL -> "installed-apps"
    ActivitySource.LOCATION -> "location"
    ActivitySource.SENSOR -> "sensor"
    ActivitySource.CONTACT -> "contacts"
    ActivitySource.CALENDAR -> "calendar"
    ActivitySource.HEALTH -> "health"
    else -> source.name.lowercase()
}

/** Natural collection interval per source, mirroring the pre-consolidation schedules. */
internal fun sourceIntervalMillis(source: ActivitySource): Long = when (source) {
    ActivitySource.APP_INSTALL -> TimeUnit.DAYS.toMillis(1)
    ActivitySource.CONTACT,
    ActivitySource.CALENDAR -> TimeUnit.HOURS.toMillis(12)
    else -> TimeUnit.HOURS.toMillis(6)
}

/**
 * Decides whether a source should run on this consolidated tick. Sources whose natural
 * interval is not longer than the shared cadence always run; longer-interval sources are
 * skipped while their checkpoint timestamp is still fresh.
 */
internal fun shouldCollectSourceNow(
    lastCollectedAt: Long?,
    intervalMillis: Long,
    cadenceMillis: Long,
    now: Long,
): Boolean {
    if (intervalMillis <= cadenceMillis) return true
    return lastCollectedAt == null || now - lastCollectedAt >= intervalMillis
}

/** Healthy periodic work has at least one ENQUEUED or RUNNING instance. */
internal fun isPeriodicWorkHealthy(states: List<String>): Boolean =
    states.any { it == "ENQUEUED" || it == "RUNNING" }

/**
 * Collects every granted source in one periodic job. Per-source freshness is enforced
 * against each source's checkpoint timestamp so longer-interval sources (contacts,
 * calendar, installed apps) keep their previous cadence even though the shared worker
 * fires every 6 hours. Per-source failures are recorded to checkpoints and never fail
 * the periodic schedule itself.
 */
class CollectAllWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AiChatApplication).container
        val dao = container.database.activityDao()
        val now = System.currentTimeMillis()
        for (source in COLLECTIBLE_SOURCES) {
            if (!container.shouldCollect(source)) continue
            val collector = collectorName(source)
            val checkpoint = try {
                dao.checkpoint(collector)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val due = shouldCollectSourceNow(
                lastCollectedAt = checkpoint?.lastCollectedAt,
                intervalMillis = sourceIntervalMillis(source),
                cadenceMillis = OfficeWorkScheduler.COLLECT_ALL_INTERVAL_MILLIS,
                now = now,
            )
            if (!due) continue
            try {
                collectSource(applicationContext, container, source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Collection failed for ${source.name.lowercase()}", error)
                container.recordCollectorError(collector, errorMessage(error))
            }
        }
        return Result.success()
    }
}

/** One-time collection for a single source, used when a source is newly granted. */
class CollectSourceWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AiChatApplication).container
        val requested = inputData.getString(KEY_SOURCE)
        val source = COLLECTIBLE_SOURCES.firstOrNull { it.name == requested }
            ?: return Result.success()
        if (!container.shouldCollect(source)) return Result.success()
        return try {
            collectSource(applicationContext, container, source)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Collection failed for ${source.name.lowercase()}", error)
            container.recordCollectorError(collectorName(source), errorMessage(error))
            Result.failure()
        }
    }

    companion object {
        const val KEY_SOURCE = "source"
    }
}

class ActivityRetentionWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AiChatApplication
        val container = app.container
        val dao = container.database.activityDao()
        val now = System.currentTimeMillis()
        var digestsGenerated = 0
        RETENTION_DAYS.forEach { (source, days) ->
            val cutoff = now - TimeUnit.DAYS.toMillis(days)
            while (true) {
                val events = dao.uncompactedBefore(source, cutoff, 500)
                if (events.isEmpty()) break
                val summaryId = UUID.randomUUID().toString()
                val fallbackContent = archiveFallbackContent(
                    source,
                    events.size,
                    archiveTopPackagesText(events),
                )
                // Best-effort LLM digest over the archived events; any failure keeps
                // the deterministic fallback content so retention never stalls. The
                // per-run cap bounds worker duration when a large backlog compacts.
                val digestPrompt = archiveDigestPrompt(source, events)
                val enriched = if (digestPrompt != null && digestsGenerated < MAX_DIGESTS_PER_RUN) {
                    val digest = try {
                        container.conversationSummarizer.generateText(digestPrompt)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (digest != null) digestsGenerated++
                    digest
                } else {
                    null
                }
                val content = selectArchiveContent(fallbackContent, enriched)
                val ids = events.map { it.id }
                // The entire compaction commits atomically: digest is computed
                // first, then the summary row, the archive memory, the compacted
                // marks, and the event deletions land in one transaction. A
                // cancellation between steps can no longer leave events
                // uncompacted with the summary already published (which would
                // re-compact with a new digest and duplicate the archive memory).
                val archived = container.database.withTransaction {
                    dao.upsertSummary(
                        MemorySummaryEntity(
                            id = summaryId,
                            source = source,
                            periodStart = events.minOf { it.startedAt },
                            periodEnd = events.maxOf { it.endedAt ?: it.startedAt },
                            content = content,
                            eventCount = events.size,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    val item = container.memoryRepository.rememberActivitySummaryInTransaction(
                        source = source,
                        summaryId = summaryId,
                        title = "${source.name.lowercase()} archive",
                        content = content,
                        importance = 0.5f,
                    )
                    dao.markCompacted(ids, summaryId)
                    dao.deleteByIds(ids)
                    item
                }
                container.memoryRepository.indexMemory(archived)
            }
        }
        try {
            container.memoryRepository.purgeExpiredMemories(now)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Memory purge failed", error)
        }
        try {
            summarizeDueConversations(container)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Conversation summary refresh failed", error)
        }
        return Result.success()
    }

    /**
     * Runs the rolling LLM summarizer over conversations whose raw summary content
     * grew past the distillation threshold. Purely periodic and best-effort: chat
     * turns never trigger synchronous summarization, and PromptContextPlanner keeps
     * its synchronous truncation fallback untouched.
     */
    private suspend fun summarizeDueConversations(container: AppContainer) {
        val summarizer = container.conversationSummarizer
        if (!summarizer.isAvailable()) return
        val due = container.conversationSummaryRepository
            .dueForLlmRefresh(SUMMARY_REFRESH_CONVERSATION_LIMIT)
        for (summary in due) {
            if (!summarizer.isAvailable()) break
            container.conversationSummaryRepository.summarizeFrom(
                conversationId = summary.conversationId,
                existingSummary = summary.content,
                trimmedMessages = emptyList(),
                tokenCount = container.inferenceEngine::countTokens,
                generator = summarizer::generateText,
            )
        }
    }

    private companion object {
        const val SUMMARY_REFRESH_CONVERSATION_LIMIT = 3
        const val MAX_DIGESTS_PER_RUN = 4

        val RETENTION_DAYS = mapOf(
            ActivitySource.ACCESSIBILITY to 90L,
            ActivitySource.NOTIFICATION to 90L,
            ActivitySource.LOCATION to 180L,
            ActivitySource.APP_USAGE to 365L,
            ActivitySource.APP_INSTALL to 365L,
            ActivitySource.CONTACT to 365L,
            ActivitySource.CALENDAR to 365L,
            ActivitySource.HEALTH to 365L,
            ActivitySource.SENSOR to 7L,
        )
    }
}

/** Character cap for the batched event text fed to the archive digest prompt. */
private const val ARCHIVE_DIGEST_INPUT_CHARS = 12_000

/** Bound for the LLM digest appended to an archived activity summary. */
internal const val ARCHIVE_DIGEST_MAX_CHARS = 1_200

/** Deterministic boilerplate for compacted activity batches. Same input, same output. */
internal fun archiveFallbackContent(
    source: ActivitySource,
    eventCount: Int,
    topPackages: String,
): String = buildString {
    append("$eventCount ${source.name.lowercase()} events were archived.")
    if (topPackages.isNotEmpty()) append(" Most frequent apps: $topPackages.")
}

internal fun archiveTopPackagesText(events: List<ActivityEventEntity>): String =
    events.mapNotNull { it.packageName }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(5)
        .joinToString { "${it.key} (${it.value})" }

/**
 * Prefers the LLM digest when it produced usable text; otherwise returns the
 * deterministic fallback verbatim so archive memories never regress.
 */
internal fun selectArchiveContent(fallback: String, enriched: String?): String {
    val digest = enriched?.trim()?.takeIf(String::isNotBlank) ?: return fallback
    return "$fallback\nDigest: ${digest.take(ARCHIVE_DIGEST_MAX_CHARS)}"
}

/**
 * Batched, capped extraction prompt for archived events. Returns null when the
 * batch carries no describable detail (e.g., sensor snapshots without titles).
 */
internal fun archiveDigestPrompt(
    source: ActivitySource,
    events: List<ActivityEventEntity>,
): String? {
    val lines = events.mapNotNull { event ->
        val detail = listOfNotNull(
            event.eventType.takeIf(String::isNotBlank),
            event.title?.takeIf(String::isNotBlank) ?: event.packageName,
        ).joinToString(" ").take(120)
        detail.takeIf(String::isNotBlank)
    }
    if (lines.isEmpty()) return null
    return buildString {
        append("Summarize the following archived ")
        append(source.name.lowercase())
        append(" activity events into a dense factual digest of what the user did: ")
        append("apps used, routines, locations, events. Preserve specifics; no commentary.")
        append("\n\n")
        append(lines.joinToString("\n").take(ARCHIVE_DIGEST_INPUT_CHARS))
    }
}

object OfficeWorkScheduler {
    const val WORK_COLLECT_ALL = "office-collect-all"
    const val WORK_MEMORY_RETENTION = "office-memory-retention"

    val COLLECT_ALL_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(6)

    /** Unique names of the per-source periodic works superseded by [WORK_COLLECT_ALL]. */
    private val LEGACY_WORK_NAMES = listOf(
        "office-usage-collection",
        "office-installed-apps-collection",
        "office-location-collection",
        "office-sensor-collection",
        "office-contacts-collection",
        "office-calendar-collection",
    )

    val scheduledWorkNames: List<String> = listOf(
        WORK_COLLECT_ALL,
        WORK_MEMORY_RETENTION,
    )

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        LEGACY_WORK_NAMES.forEach(workManager::cancelUniqueWork)
        scheduledWorkNames.forEach { name ->
            workManager.enqueueUniquePeriodicWork(
                name,
                existingPolicy(name),
                periodicRequest(name),
            )
        }
    }

    /**
     * Re-asserts every expected periodic work: absent or non-ENQUEUED/RUNNING works are
     * re-enqueued with the UPDATE policy. Guards against OEM schedulers (HyperOS) dropping
     * periodic jobs while the app is closed.
     */
    suspend fun verifyAndRepair(context: Context) {
        withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            val needsRepair = scheduledWorkNames.filter { name ->
                val states = runCatching {
                    workManager.getWorkInfosForUniqueWork(name).get().map { it.state.name }
                }.getOrDefault(emptyList())
                !isPeriodicWorkHealthy(states)
            }
            if (needsRepair.isEmpty()) return@withContext
            Log.i(TAG, "Re-enqueueing periodic works: ${needsRepair.joinToString()}")
            needsRepair.forEach { name ->
                workManager.enqueueUniquePeriodicWork(
                    name,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodicRequest(name),
                )
            }
        }
    }

    fun collectNow(context: Context, source: ActivitySource) {
        if (source !in COLLECTIBLE_SOURCES) return
        val request = OneTimeWorkRequestBuilder<CollectSourceWorker>()
            .setInputData(workDataOf(CollectSourceWorker.KEY_SOURCE to source.name))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "office-collect-now-${source.name.lowercase()}",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun existingPolicy(name: String): ExistingPeriodicWorkPolicy = when (name) {
        WORK_COLLECT_ALL -> ExistingPeriodicWorkPolicy.UPDATE
        else -> ExistingPeriodicWorkPolicy.KEEP
    }

    private fun periodicRequest(name: String): PeriodicWorkRequest = when (name) {
        WORK_COLLECT_ALL -> PeriodicWorkRequestBuilder<CollectAllWorker>(
            COLLECT_ALL_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        ).build()
        WORK_MEMORY_RETENTION -> PeriodicWorkRequestBuilder<ActivityRetentionWorker>(
            1,
            TimeUnit.DAYS,
        ).build()
        else -> error("Unknown periodic work: $name")
    }
}

private suspend fun collectSource(
    context: Context,
    container: AppContainer,
    source: ActivitySource,
) {
    when (source) {
        ActivitySource.APP_USAGE -> collectUsage(context, container)
        ActivitySource.APP_INSTALL -> collectInstalledApps(context, container)
        ActivitySource.LOCATION -> collectLocation(context, container)
        ActivitySource.SENSOR -> collectSensors(context, container)
        ActivitySource.CONTACT -> collectContacts(context, container)
        ActivitySource.CALENDAR -> collectCalendar(context, container)
        ActivitySource.HEALTH -> collectHealth(context, container)
        else -> Unit
    }
}

private suspend fun collectUsage(context: Context, container: AppContainer) {
    val dao = container.database.activityDao()
    val now = System.currentTimeMillis()
    val checkpoint = dao.checkpoint("usage")
    val start = maxOf(
        checkpoint?.lastCollectedAt?.minus(TimeUnit.HOURS.toMillis(24))
            ?: now - TimeUnit.HOURS.toMillis(24),
        now - TimeUnit.DAYS.toMillis(7),
    )
    val manager = context.getSystemService(UsageStatsManager::class.java)
    val packageManager = context.packageManager
    val activeSessions = mutableMapOf<String, Long>()
    val event = UsageEvents.Event()
    val events = manager.queryEvents(start, now)
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        val packageName = event.packageName ?: continue
        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                activeSessions[packageName] = event.timeStamp
            }
            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.ACTIVITY_STOPPED -> {
                val sessionStart = activeSessions.remove(packageName) ?: continue
                val sessionEnd = event.timeStamp.coerceAtLeast(sessionStart)
                if (sessionEnd - sessionStart < MIN_SESSION_MILLIS) continue
                val label = runCatching {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0),
                    ).toString()
                }.getOrDefault(packageName)
                val duration = sessionEnd - sessionStart
                container.activityRepository.record(
                    source = ActivitySource.APP_USAGE,
                    eventType = "foreground_session",
                    startedAt = sessionStart,
                    endedAt = sessionEnd,
                    packageName = packageName,
                    title = label,
                    metadataJson = """{"foregroundMillis":$duration}""",
                    stableKey = "$packageName:$sessionStart:$sessionEnd",
                )
            }
        }
    }
    dao.upsertCheckpoint(
        CollectorCheckpointEntity(
            collector = "usage",
            cursor = null,
            lastCollectedAt = now,
            lastCompactedAt = checkpoint?.lastCompactedAt,
            error = null,
        ),
    )
}

private suspend fun collectInstalledApps(context: Context, container: AppContainer) {
    val packageManager = context.packageManager
    packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        .asSequence()
        .filter { it.packageName != context.packageName }
        .forEach { info ->
            val applicationInfo = info.applicationInfo
            val label = applicationInfo?.loadLabel(packageManager)?.toString()
                ?: info.packageName
            container.activityRepository.record(
                source = ActivitySource.APP_INSTALL,
                eventType = "inventory",
                startedAt = info.lastUpdateTime.coerceAtLeast(info.firstInstallTime),
                packageName = info.packageName,
                title = label,
                metadataJson =
                    """{"versionCode":${info.longVersionCode},"versionName":${info.versionName.json()}}""",
                stableKey = "${info.packageName}:${info.longVersionCode}:${info.lastUpdateTime}",
            )
        }
    container.recordCollectorSuccess("installed-apps")
}

@SuppressLint("MissingPermission")
private suspend fun collectLocation(context: Context, container: AppContainer) {
    if (!hasAnyPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    ) {
        Log.w(TAG, "Skipping location collection: permission missing")
        container.recordCollectorError("location", "permission missing")
        return
    }
    val manager = context.getSystemService(LocationManager::class.java)
    val location = manager.allProviders
        .mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }
        .maxByOrNull { it.time }
    if (location != null) {
        container.activityRepository.record(
            source = ActivitySource.LOCATION,
            eventType = "passive_snapshot",
            startedAt = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            title = location.provider,
            metadataJson = buildString {
                append("""{"latitude":${location.latitude},"longitude":${location.longitude}""")
                if (location.hasAccuracy()) append(""","accuracyMeters":${location.accuracy}""")
                if (location.hasAltitude()) append(""","altitudeMeters":${location.altitude}""")
                append('}')
            },
            stableKey = "${location.provider}:${location.time}:${location.latitude}:${location.longitude}",
        )
    }
    container.recordCollectorSuccess("location")
}

private suspend fun collectSensors(context: Context, container: AppContainer) {
    val manager = context.getSystemService(SensorManager::class.java)
    val includeSteps = hasAnyPermission(
        context,
        Manifest.permission.ACTIVITY_RECOGNITION,
    )
    val types = buildList {
        add(Sensor.TYPE_AMBIENT_TEMPERATURE)
        add(Sensor.TYPE_LIGHT)
        add(Sensor.TYPE_PRESSURE)
        add(Sensor.TYPE_RELATIVE_HUMIDITY)
        if (includeSteps) add(Sensor.TYPE_STEP_COUNTER)
    }
    val values = collectSensorSnapshot(manager, types)
    val now = System.currentTimeMillis()
    values.forEach { (type, reading) ->
        val sensor = manager.getDefaultSensor(type) ?: return@forEach
        container.activityRepository.record(
            source = ActivitySource.SENSOR,
            eventType = "snapshot",
            startedAt = now,
            title = sensor.name,
            metadataJson =
                """{"type":$type,"values":[${reading.joinToString(",")}]}""",
            stableKey = "$type:${now / TimeUnit.HOURS.toMillis(1)}",
        )
    }
    container.recordCollectorSuccess("sensor")
}

private suspend fun collectContacts(context: Context, container: AppContainer) {
    if (!hasAnyPermission(context, Manifest.permission.READ_CONTACTS)) {
        Log.w(TAG, "Skipping contacts collection: permission missing")
        container.recordCollectorError("contacts", "permission missing")
        return
    }
    val projection = arrayOf(
        ContactsContract.Contacts._ID,
        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        ContactsContract.Contacts.STARRED,
        ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
    )
    context.contentResolver.query(
        ContactsContract.Contacts.CONTENT_URI,
        projection,
        null,
        null,
        null,
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
        val nameColumn =
            cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        val starredColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.STARRED)
        val updatedColumn = cursor.getColumnIndexOrThrow(
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
        )
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val updatedAt = cursor.getLong(updatedColumn).coerceAtLeast(0L)
            container.activityRepository.record(
                source = ActivitySource.CONTACT,
                eventType = "contact",
                startedAt = updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                title = cursor.getString(nameColumn),
                metadataJson = """{"starred":${cursor.getInt(starredColumn) != 0}}""",
                stableKey = "$id:$updatedAt",
            )
        }
    }
    container.recordCollectorSuccess("contacts")
}

private suspend fun collectCalendar(context: Context, container: AppContainer) {
    if (!hasAnyPermission(context, Manifest.permission.READ_CALENDAR)) {
        Log.w(TAG, "Skipping calendar collection: permission missing")
        container.recordCollectorError("calendar", "permission missing")
        return
    }
    val now = System.currentTimeMillis()
    val start = now - TimeUnit.DAYS.toMillis(30)
    val end = now + TimeUnit.DAYS.toMillis(365)
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, start)
        ContentUris.appendId(it, end)
    }.build()
    val projection = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
    )
    context.contentResolver.query(
        uri,
        projection,
        null,
        null,
        "${CalendarContract.Instances.BEGIN} ASC",
    )?.use { cursor ->
        val eventId = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
        val begin = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
        val endColumn = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
        val title = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
        val location = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
        val allDay = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
        val calendar =
            cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
        while (cursor.moveToNext()) {
            val eventStart = cursor.getLong(begin)
            val eventEnd = cursor.getLong(endColumn)
            val eventLocation = cursor.getString(location)
            container.activityRepository.record(
                source = ActivitySource.CALENDAR,
                eventType = "event",
                startedAt = eventStart,
                endedAt = eventEnd,
                title = cursor.getString(title),
                text = eventLocation,
                metadataJson = buildString {
                    append("""{"allDay":${cursor.getInt(allDay) != 0},"calendar":""")
                    append(cursor.getString(calendar).json())
                    append('}')
                },
                stableKey = "${cursor.getLong(eventId)}:$eventStart:$eventEnd",
            )
        }
    }
    container.recordCollectorSuccess("calendar")
}

/**
 * Reads the last 24 hours of Health Connect data (steps, sleep, exercise) and
 * records it as HEALTH activity events. Stable keys are scoped to the data
 * itself: metrics key off the local date of the collection window start, so
 * the overlapping 24-hour windows sampled every few hours collapse into one
 * row per day instead of one row per collection run, and exercise sessions key
 * off the session start time plus title (not a positional index, so a shifting
 * session set cannot remap records). Repeated collections of the same day stay
 * no-ops at the DAO level (insert IGNORE).
 */
private suspend fun collectHealth(
    @Suppress("UNUSED_PARAMETER") context: Context,
    container: AppContainer,
) {
    val dataSource = container.healthDataSource
    if (!dataSource.isAvailable()) {
        Log.w(TAG, "Skipping health collection: Health Connect unavailable")
        container.recordCollectorError("health", "health connect unavailable")
        return
    }
    val granted = try {
        dataSource.grantedPermissions()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        emptySet()
    }
    if (!dataSource.readPermissions.all(granted::contains)) {
        Log.w(TAG, "Skipping health collection: permission missing")
        container.recordCollectorError("health", "permission missing")
        return
    }
    val now = System.currentTimeMillis()
    val windowStart = now - TimeUnit.HOURS.toMillis(24)
    val snapshot = dataSource.read(Instant.ofEpochMilli(windowStart), Instant.ofEpochMilli(now))
    snapshot.stepCount?.let { steps ->
        container.activityRepository.record(
            source = ActivitySource.HEALTH,
            eventType = "steps",
            startedAt = windowStart,
            endedAt = now,
            title = "Steps",
            metadataJson = """{"count":$steps}""",
            stableKey = healthStepsStableKey(windowStart),
        )
    }
    snapshot.sleepMinutes?.let { minutes ->
        container.activityRepository.record(
            source = ActivitySource.HEALTH,
            eventType = "sleep",
            startedAt = windowStart,
            endedAt = now,
            title = "Sleep",
            metadataJson = """{"minutes":$minutes}""",
            stableKey = healthSleepStableKey(windowStart),
        )
    }
    snapshot.exerciseSessions.forEach { session ->
        container.activityRepository.record(
            source = ActivitySource.HEALTH,
            eventType = "exercise",
            startedAt = session.startedAtMillis,
            endedAt = now,
            title = session.title,
            metadataJson = """{"windowStart":$windowStart}""",
            stableKey = healthExerciseStableKey(session.startedAtMillis, session.title),
        )
    }
    container.recordCollectorSuccess("health")
}

/** Stable key for a health metric snapshot, scoped to its collection window. */
internal fun healthStepsStableKey(windowStartEpochMillis: Long): String =
    "health:steps:${healthWindowDayKey(windowStartEpochMillis)}"

/** Stable key for a health sleep snapshot, scoped to its collection window. */
internal fun healthSleepStableKey(windowStartEpochMillis: Long): String =
    "health:sleep:${healthWindowDayKey(windowStartEpochMillis)}"

/**
 * Day-scoped key for the rolling health window. The 24-hour window is sampled on a
 * sub-daily cadence, so every run of a given day shares one window-start date and the
 * overlapping snapshots dedupe to a single row per day.
 */
internal fun healthWindowDayKey(windowStartEpochMillis: Long): Long =
    Instant.ofEpochMilli(windowStartEpochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toEpochDay()

/** Stable key for an exercise session, keyed by session identity, not position. */
internal fun healthExerciseStableKey(startedAtMillis: Long, title: String): String =
    "health:exercise:$startedAtMillis:$title"

private const val TAG = "OfficeActivity"

private const val MIN_SESSION_MILLIS = 1_000L

internal suspend fun AppContainer.shouldCollect(
    source: ActivitySource,
): Boolean {
    if (settings.collectionPaused.first()) {
        Log.w(TAG, "Skipping ${source.name.lowercase()} collection: collection paused")
        return false
    }
    if (!phoneSourceAccessManager.hasAccess(source)) {
        Log.w(TAG, "Skipping ${source.name.lowercase()} collection: no access")
        return false
    }
    return true
}

internal suspend fun AppContainer.recordCollectorSuccess(collector: String) {
    try {
        val dao = database.activityDao()
        val existing = dao.checkpoint(collector)
        dao.upsertCheckpoint(
            CollectorCheckpointEntity(
                collector = collector,
                cursor = existing?.cursor,
                lastCollectedAt = System.currentTimeMillis(),
                lastCompactedAt = existing?.lastCompactedAt,
                error = null,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Checkpoint bookkeeping is best-effort and must never fail collection.
    }
}

internal suspend fun AppContainer.recordCollectorError(
    collector: String,
    error: String,
) {
    try {
        val dao = database.activityDao()
        val existing = dao.checkpoint(collector)
        dao.upsertCheckpoint(
            CollectorCheckpointEntity(
                collector = collector,
                cursor = existing?.cursor,
                lastCollectedAt = existing?.lastCollectedAt ?: System.currentTimeMillis(),
                lastCompactedAt = existing?.lastCompactedAt,
                error = error.take(200),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Checkpoint bookkeeping is best-effort and must never fail collection.
    }
}

private fun errorMessage(error: Throwable): String =
    (error.message ?: error.javaClass.simpleName).take(200)

private fun hasAnyPermission(context: Context, vararg permissions: String): Boolean =
    permissions.any { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

private suspend fun collectSensorSnapshot(
    manager: SensorManager,
    types: List<Int>,
): Map<Int, FloatArray> = suspendCancellableCoroutine { continuation ->
    val handler = Handler(Looper.getMainLooper())
    val values = linkedMapOf<Int, FloatArray>()
    lateinit var listener: SensorEventListener
    val finish = Runnable {
        manager.unregisterListener(listener)
        if (continuation.isActive) {
            continuation.resume(values.toMap())
        }
    }
    listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            synchronized(values) {
                values.putIfAbsent(event.sensor.type, event.values.copyOf())
                if (values.size == types.count { manager.getDefaultSensor(it) != null }) {
                    handler.removeCallbacks(finish)
                    handler.post(finish)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    types.mapNotNull(manager::getDefaultSensor).forEach { sensor ->
        manager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
            handler,
        )
    }
    handler.postDelayed(finish, 3_500)
    continuation.invokeOnCancellation {
        handler.removeCallbacks(finish)
        manager.unregisterListener(listener)
    }
}

private fun String?.json(): String =
    if (this == null) {
        "null"
    } else {
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
