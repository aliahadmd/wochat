package com.aliahad.aichat.activity

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Process
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aliahad.aichat.brief.HealthDataSource
import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.core.PhoneSourceAccessState
import com.aliahad.aichat.core.PhoneSourceStatus

class PhoneSourceAccessManager(
    private val context: Context,
    private val healthDataSource: HealthDataSource,
) {
    suspend fun snapshot(): Map<ActivitySource, PhoneSourceStatus> =
        ActivitySource.entries.associateWith { source ->
            if (source == ActivitySource.HEALTH) healthStatus() else status(source)
        }

    suspend fun hasAccess(source: ActivitySource): Boolean =
        (if (source == ActivitySource.HEALTH) healthStatus() else status(source))
            .state == PhoneSourceAccessState.GRANTED

    val healthConnectAvailable: Boolean
        get() = healthDataSource.isAvailable()

    /** Single source of truth for Health Connect availability + read permissions. */
    suspend fun healthStatus(): PhoneSourceStatus {
        if (!healthDataSource.isAvailable()) {
            return unavailable(
                ActivitySource.HEALTH,
                "Health Connect is not available on this device",
            )
        }
        val granted = runCatching { healthDataSource.grantedPermissions() }.getOrDefault(emptySet())
        val complete = healthDataSource.readPermissions.all(granted::contains)
        return PhoneSourceStatus(
            source = ActivitySource.HEALTH,
            state = if (complete) {
                PhoneSourceAccessState.GRANTED
            } else {
                PhoneSourceAccessState.NOT_GRANTED
            },
            detail = if (complete) {
                "Health Connect read access granted"
            } else {
                "Health Connect access is optional"
            },
            actionLabel = if (complete) "Manage" else "Grant access",
        )
    }

    fun status(source: ActivitySource): PhoneSourceStatus = when (source) {
        ActivitySource.APP_USAGE -> status(
            source = source,
            granted = hasUsageAccess(),
            grantedDetail = "Usage access granted",
            missingDetail = "Usage access has not been granted",
            actionLabel = "Manage",
        )
        ActivitySource.APP_INSTALL -> PhoneSourceStatus(
            source = source,
            state = PhoneSourceAccessState.GRANTED,
            detail = "Installed-app access is available",
        )
        ActivitySource.NOTIFICATION -> status(
            source = source,
            granted = context.packageName in
                NotificationManagerCompat.getEnabledListenerPackages(context),
            grantedDetail = "Notification access granted",
            missingDetail = "Notification access has not been granted",
            actionLabel = "Manage",
        )
        ActivitySource.ACCESSIBILITY -> status(
            source = source,
            granted = hasAccessibilityAccess(),
            grantedDetail = "Screen context access granted",
            missingDetail = "Screen context access has not been granted",
            actionLabel = "Manage",
        )
        ActivitySource.LOCATION -> locationStatus()
        ActivitySource.SENSOR -> sensorStatus()
        ActivitySource.CONTACT -> runtimePermissionStatus(
            source,
            Manifest.permission.READ_CONTACTS,
            "Contacts access granted",
            "Contacts access has not been granted",
        )
        ActivitySource.CALENDAR -> runtimePermissionStatus(
            source,
            Manifest.permission.READ_CALENDAR,
            "Calendar access granted",
            "Calendar access has not been granted",
        )
        ActivitySource.HEALTH -> unavailable(
            source,
            "Health Connect status is resolved through healthStatus()",
        )
        ActivitySource.DOCUMENT -> unavailable(
            source,
            "Document folders are added through the file picker",
        )
        ActivitySource.SMS -> unavailable(
            source,
            "SMS collection is not implemented",
        )
        ActivitySource.CALL -> unavailable(
            source,
            "Call-history collection is not implemented",
        )
    }

    private fun locationStatus(): PhoneSourceStatus {
        val foreground = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val background = hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val locationEnabled = context.getSystemService(LocationManager::class.java)
            ?.isLocationEnabled == true
        val detail = when {
            !foreground -> "Location access has not been granted"
            !background -> "Allow all-the-time location for background snapshots"
            !locationEnabled -> "Device location is turned off"
            else -> "Background location access granted"
        }
        return PhoneSourceStatus(
            source = ActivitySource.LOCATION,
            state = if (foreground && background && locationEnabled) {
                PhoneSourceAccessState.GRANTED
            } else {
                PhoneSourceAccessState.NOT_GRANTED
            },
            detail = detail,
            actionLabel = "Manage",
        )
    }

    private fun sensorStatus(): PhoneSourceStatus {
        val manager = context.getSystemService(SensorManager::class.java)
        val basicTypes = listOf(
            Sensor.TYPE_AMBIENT_TEMPERATURE,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_RELATIVE_HUMIDITY,
        )
        val hasBasicSensor = basicTypes.any { manager?.getDefaultSensor(it) != null }
        val hasStepSensor = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        if (!hasBasicSensor && !hasStepSensor) {
            return unavailable(ActivitySource.SENSOR, "No supported sensors were found")
        }
        val activityGranted = hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
        return PhoneSourceStatus(
            source = ActivitySource.SENSOR,
            state = PhoneSourceAccessState.GRANTED,
            detail = if (hasStepSensor && !activityGranted) {
                "Environmental sensors available; activity access adds steps"
            } else {
                "Supported sensor access is available"
            },
            actionLabel = if (hasStepSensor && !activityGranted) "Add steps" else null,
        )
    }

    private fun runtimePermissionStatus(
        source: ActivitySource,
        permission: String,
        grantedDetail: String,
        missingDetail: String,
    ): PhoneSourceStatus = status(
        source = source,
        granted = hasPermission(permission),
        grantedDetail = grantedDetail,
        missingDetail = missingDetail,
        actionLabel = "Manage",
    )

    private fun status(
        source: ActivitySource,
        granted: Boolean,
        grantedDetail: String,
        missingDetail: String,
        actionLabel: String?,
    ) = PhoneSourceStatus(
        source = source,
        state = if (granted) {
            PhoneSourceAccessState.GRANTED
        } else {
            PhoneSourceAccessState.NOT_GRANTED
        },
        detail = if (granted) grantedDetail else missingDetail,
        actionLabel = if (granted) actionLabel else "Grant access",
    )

    private fun unavailable(source: ActivitySource, detail: String) = PhoneSourceStatus(
        source = source,
        state = PhoneSourceAccessState.UNAVAILABLE,
        detail = detail,
    )

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun hasAccessibilityAccess(): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        ).any { service ->
            val info = service.resolveInfo.serviceInfo
            info.packageName == context.packageName &&
                info.name == OfficeAccessibilityService::class.java.name
        }
    }
}
