package com.aliahad.aichat.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object DeviceSettingsNavigator {
    fun openUsageAccess(context: Context) {
        startOrFallback(
            context,
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openAccessibility(context: Context) {
        startOrFallback(
            context,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openNotificationAccess(context: Context) {
        startOrFallback(
            context,
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openAppPermissions(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openHealthConnect(context: Context) {
        startOrFallback(
            context,
            Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun startOrFallback(context: Context, preferred: Intent) {
        runCatching { context.startActivity(preferred) }.getOrElse {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
