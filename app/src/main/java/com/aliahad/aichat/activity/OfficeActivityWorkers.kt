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
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aliahad.aichat.AiChatApplication
import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.data.CollectorCheckpointEntity
import com.aliahad.aichat.data.MemorySummaryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class UsageCollectionWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AiChatApplication
        val container = app.container
        if (!container.shouldCollect(ActivitySource.APP_USAGE)) return Result.success()
        val dao = container.database.activityDao()
        val now = System.currentTimeMillis()
        val checkpoint = dao.checkpoint(COLLECTOR)
        val start = maxOf(
            checkpoint?.lastCollectedAt?.minus(TimeUnit.HOURS.toMillis(24))
                ?: now - TimeUnit.HOURS.toMillis(24),
            now - TimeUnit.DAYS.toMillis(7),
        )
        val manager = applicationContext.getSystemService(UsageStatsManager::class.java)
        val packageManager = applicationContext.packageManager
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
                collector = COLLECTOR,
                cursor = null,
                lastCollectedAt = now,
                lastCompactedAt = checkpoint?.lastCompactedAt,
                error = null,
            ),
        )
        return Result.success()
    }

    private companion object {
        const val COLLECTOR = "usage"
        const val MIN_SESSION_MILLIS = 1_000L
    }
}

class InstalledAppsCollectionWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AiChatApplication).container
        if (!container.shouldCollect(ActivitySource.APP_INSTALL)) return Result.success()
        val packageManager = applicationContext.packageManager
        packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            .asSequence()
            .filter { it.packageName != applicationContext.packageName }
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
        return Result.success()
    }
}

class LocationCollectionWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val container = (applicationContext as AiChatApplication).container
        if (!container.shouldCollect(ActivitySource.LOCATION)) return Result.success()
        if (!hasAnyPermission(
                applicationContext,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        ) {
            return Result.success()
        }
        val manager = applicationContext.getSystemService(LocationManager::class.java)
        val location = manager.allProviders
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?: return Result.success()
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
        return Result.success()
    }
}

class SensorCollectionWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AiChatApplication).container
        if (!container.shouldCollect(ActivitySource.SENSOR)) return Result.success()
        val manager = applicationContext.getSystemService(SensorManager::class.java)
        val includeSteps = hasAnyPermission(
            applicationContext,
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
        return Result.success()
    }
}

class ContactsCollectionWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AiChatApplication).container
        if (!container.shouldCollect(ActivitySource.CONTACT)) return Result.success()
        if (!hasAnyPermission(applicationContext, Manifest.permission.READ_CONTACTS)) {
            return Result.success()
        }
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.STARRED,
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
        )
        applicationContext.contentResolver.query(
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
        return Result.success()
    }
}

class CalendarCollectionWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AiChatApplication).container
        if (!container.shouldCollect(ActivitySource.CALENDAR)) return Result.success()
        if (!hasAnyPermission(applicationContext, Manifest.permission.READ_CALENDAR)) {
            return Result.success()
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
        applicationContext.contentResolver.query(
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
        return Result.success()
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
        RETENTION_DAYS.forEach { (source, days) ->
            val cutoff = now - TimeUnit.DAYS.toMillis(days)
            while (true) {
                val events = dao.uncompactedBefore(source, cutoff, 500)
                if (events.isEmpty()) break
                val summaryId = UUID.randomUUID().toString()
                val topPackages = events.mapNotNull { it.packageName }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .joinToString { "${it.key} (${it.value})" }
                val content = buildString {
                    append("${events.size} ${source.name.lowercase()} events were archived.")
                    if (topPackages.isNotEmpty()) append(" Most frequent apps: $topPackages.")
                }
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
                container.memoryRepository.rememberActivitySummary(
                    source = source,
                    summaryId = summaryId,
                    title = "${source.name.lowercase()} archive",
                    content = content,
                    importance = 0.35f,
                )
                val ids = events.map { it.id }
                dao.markCompacted(ids, summaryId)
                dao.deleteByIds(ids)
            }
        }
        return Result.success()
    }

    private companion object {
        val RETENTION_DAYS = mapOf(
            ActivitySource.ACCESSIBILITY to 90L,
            ActivitySource.NOTIFICATION to 90L,
            ActivitySource.LOCATION to 180L,
            ActivitySource.APP_USAGE to 365L,
            ActivitySource.SENSOR to 7L,
        )
    }
}

object OfficeWorkScheduler {
    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniquePeriodicWork(
            "office-usage-collection",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UsageCollectionWorker>(6, TimeUnit.HOURS).build(),
        )
        workManager.enqueueUniquePeriodicWork(
            "office-installed-apps-collection",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<InstalledAppsCollectionWorker>(1, TimeUnit.DAYS).build(),
        )
        workManager.enqueueUniquePeriodicWork(
            "office-location-collection",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LocationCollectionWorker>(6, TimeUnit.HOURS).build(),
        )
        workManager.enqueueUniquePeriodicWork(
            "office-sensor-collection",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SensorCollectionWorker>(6, TimeUnit.HOURS).build(),
        )
        workManager.enqueueUniquePeriodicWork(
            "office-contacts-collection",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ContactsCollectionWorker>(12, TimeUnit.HOURS).build(),
        )
        workManager.enqueueUniquePeriodicWork(
            "office-calendar-collection",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CalendarCollectionWorker>(12, TimeUnit.HOURS).build(),
        )
        workManager.enqueueUniquePeriodicWork(
            "office-memory-retention",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ActivityRetentionWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    fun collectNow(context: Context, source: ActivitySource) {
        val request = when (source) {
            ActivitySource.APP_USAGE -> OneTimeWorkRequestBuilder<UsageCollectionWorker>()
            ActivitySource.APP_INSTALL -> OneTimeWorkRequestBuilder<InstalledAppsCollectionWorker>()
            ActivitySource.LOCATION -> OneTimeWorkRequestBuilder<LocationCollectionWorker>()
            ActivitySource.SENSOR -> OneTimeWorkRequestBuilder<SensorCollectionWorker>()
            ActivitySource.CONTACT -> OneTimeWorkRequestBuilder<ContactsCollectionWorker>()
            ActivitySource.CALENDAR -> OneTimeWorkRequestBuilder<CalendarCollectionWorker>()
            else -> null
        }?.build() ?: return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "office-collect-now-${source.name.lowercase()}",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

private suspend fun com.aliahad.aichat.AppContainer.shouldCollect(
    source: ActivitySource,
): Boolean =
    !settings.collectionPaused.first() && phoneSourceAccessManager.hasAccess(source)

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
