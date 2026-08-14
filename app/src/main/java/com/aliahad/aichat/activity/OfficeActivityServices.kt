package com.aliahad.aichat.activity

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aliahad.aichat.AiChatApplication
import com.aliahad.aichat.core.ActivitySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class OfficeNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (sbn.packageName == packageName || isSensitiveUiPackage(sbn.packageName)) return
        val container = (application as AiChatApplication).container
        scope.launch {
            runCatching {
                if (container.settings.collectionPaused.first()) return@runCatching
                val extras = notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                    ?.let(OneTimeCodeRedactor::redact)
                val text = OneTimeCodeRedactor.redact(
                    listOfNotNull(
                        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                    ).distinct().joinToString("\n"),
                )
                container.activityRepository.record(
                    source = ActivitySource.NOTIFICATION,
                    eventType = "posted",
                    startedAt = sbn.postTime,
                    packageName = sbn.packageName,
                    title = title,
                    text = text,
                    stableKey = sbn.key + ":" + sbn.postTime,
                )
            }.onFailure { Log.e(TAG, "Unable to record notification activity", it) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "OfficeActivity"
    }
}

class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in SUPPORTED_ACTIONS) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) &&
            action != Intent.ACTION_PACKAGE_REPLACED
        ) {
            return
        }
        val changedPackage = intent.data?.schemeSpecificPart ?: return
        val pending = goAsync()
        val application = context.applicationContext as AiChatApplication
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    if (!application.container.settings.collectionPaused.first()) {
                        application.container.activityRepository.record(
                            source = ActivitySource.APP_INSTALL,
                            eventType = when (action) {
                                Intent.ACTION_PACKAGE_ADDED -> "installed"
                                Intent.ACTION_PACKAGE_REMOVED -> "removed"
                                else -> "updated"
                            },
                            startedAt = System.currentTimeMillis(),
                            packageName = changedPackage,
                            title = changedPackage,
                            stableKey = "$action:$changedPackage:${System.currentTimeMillis() / 60_000}",
                        )
                    }
                }.onFailure { Log.e(TAG, "Unable to record package change", it) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "OfficeActivity"
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED,
        )
    }
}

/** Collects opt-in visible text for Office Memory; it never performs screen actions. */
class OfficeAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastRecordedByPackage = mutableMapOf<String, Long>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val sourcePackage = event.packageName?.toString() ?: return
        if (sourcePackage == packageName ||
            isKeyboardPackage(sourcePackage) ||
            isSensitiveUiPackage(sourcePackage)
        ) {
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        val now = System.currentTimeMillis()
        val minimumDelay = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            500L
        } else {
            2_500L
        }
        if (now - (lastRecordedByPackage[sourcePackage] ?: 0L) < minimumDelay) return
        lastRecordedByPackage[sourcePackage] = now
        val root = rootInActiveWindow ?: return
        val visibleText = buildVisibleText(root)
        val title = event.className?.toString()
        val container = (application as AiChatApplication).container
        scope.launch {
            runCatching {
                if (container.settings.collectionPaused.first()) return@runCatching
                container.activityRepository.record(
                    source = ActivitySource.ACCESSIBILITY,
                    eventType = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                        "window"
                    } else {
                        "content"
                    },
                    startedAt = now,
                    packageName = sourcePackage,
                    title = title,
                    text = visibleText,
                    stableKey = "$sourcePackage:$title:${visibleText.hashCode()}:${now / 2_000}",
                )
            }.onFailure { Log.e(TAG, "Unable to record accessibility activity", it) }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildVisibleText(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val text = linkedSetOf<String>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && !node.isPassword) {
                node.text?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(text::add)
                node.contentDescription?.toString()?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(text::add)
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return text.joinToString("\n").take(MAX_CAPTURE_CHARS)
    }

    private fun isKeyboardPackage(value: String): Boolean {
        val name = value.lowercase()
        return name.contains("inputmethod") ||
            name.contains("keyboard") ||
            name.contains("com.sohu.inputmethod") ||
            name.contains("com.baidu.input") ||
            name.contains("com.miui.securityinputmethod")
    }

    private companion object {
        const val TAG = "OfficeActivity"
        const val MAX_NODES = 800
        const val MAX_CAPTURE_CHARS = 20_000
    }
}

private fun isSensitiveUiPackage(packageName: String): Boolean {
    val normalized = packageName.lowercase()
    return SENSITIVE_PACKAGE_TERMS.any(normalized::contains)
}

private val SENSITIVE_PACKAGE_TERMS = listOf(
    "authenticator",
    "password",
    "keychain",
    "keystore",
    "wallet",
    "bank",
    "payment",
    "securityinput",
    "permissioncontroller",
)
