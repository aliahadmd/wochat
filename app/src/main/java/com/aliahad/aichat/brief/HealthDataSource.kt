package com.aliahad.aichat.brief

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.Duration

data class HealthBriefSnapshot(
    val stepCount: Long? = null,
    val sleepMinutes: Long? = null,
    val exerciseSessions: List<HealthExerciseSession> = emptyList(),
    val available: Boolean = false,
)

/**
 * One exercise session with its own start time so collection stable keys can be
 * derived from the session identity instead of a positional index.
 */
data class HealthExerciseSession(
    val title: String,
    val startedAtMillis: Long,
)

interface HealthDataSource {
    val readPermissions: Set<String>
    fun isAvailable(): Boolean
    suspend fun grantedPermissions(): Set<String>
    suspend fun read(fromInclusive: Instant, toExclusive: Instant): HealthBriefSnapshot
}

class HealthConnectDataSource(
    private val context: Context,
) : HealthDataSource {
    override val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    override fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    override suspend fun grantedPermissions(): Set<String> =
        if (isAvailable()) client().permissionController.getGrantedPermissions() else emptySet()

    override suspend fun read(
        fromInclusive: Instant,
        toExclusive: Instant,
    ): HealthBriefSnapshot {
        if (!isAvailable()) return HealthBriefSnapshot()
        val granted = grantedPermissions()
        val timeRange = TimeRangeFilter.between(fromInclusive, toExclusive)
        val health = client()
        val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
        val sleepPermission = HealthPermission.getReadPermission(SleepSessionRecord::class)
        val exercisePermission = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        val steps = if (stepsPermission in granted) {
            health.readRecords(ReadRecordsRequest(StepsRecord::class, timeRange))
                .records.sumOf(StepsRecord::count)
        } else null
        val sleepMinutes = if (sleepPermission in granted) {
            health.readRecords(ReadRecordsRequest(SleepSessionRecord::class, timeRange))
                .records.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
        } else null
        val exercises = if (exercisePermission in granted) {
            health.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, timeRange))
                .records.map { session ->
                    val minutes = Duration.between(session.startTime, session.endTime).toMinutes()
                    HealthExerciseSession(
                        title = "Exercise session · ${minutes} min",
                        startedAtMillis = session.startTime.toEpochMilli(),
                    )
                }
        } else emptyList()
        return HealthBriefSnapshot(
            stepCount = steps,
            sleepMinutes = sleepMinutes,
            exerciseSessions = exercises,
            available = true,
        )
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)
}
