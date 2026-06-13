package com.aliahad.aichat.activity

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aliahad.aichat.AiChatApplication
import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.core.DeviceAction
import com.aliahad.aichat.core.DeviceActionKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
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
                val text = listOfNotNull(
                    extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                    extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                    extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                ).distinct().joinToString("\n")
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
        val packageName = intent.data?.schemeSpecificPart ?: return
        val pending = goAsync()
        val application = context.applicationContext as AiChatApplication
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    val settings = application.container.settings
                    if (!settings.collectionPaused.first()) {
                        application.container.activityRepository.record(
                            source = ActivitySource.APP_INSTALL,
                            eventType = when (action) {
                                Intent.ACTION_PACKAGE_ADDED -> "installed"
                                Intent.ACTION_PACKAGE_REMOVED -> "removed"
                                else -> "updated"
                            },
                            startedAt = System.currentTimeMillis(),
                            packageName = packageName,
                            title = packageName,
                            stableKey = "$action:$packageName:${System.currentTimeMillis() / 60_000}",
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

class OfficeAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastRecordedByPackage = mutableMapOf<String, Long>()

    override fun onServiceConnected() {
        activeService = WeakReference(this)
    }

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
        val minimumDelay = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) 500L else 2_500L
        if (now - (lastRecordedByPackage[sourcePackage] ?: 0L) < minimumDelay) return
        lastRecordedByPackage[sourcePackage] = now
        val root = rootInActiveWindow ?: return
        val visibleText = collectVisibleText(root)
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
        activeService.clear()
        scope.cancel()
        super.onDestroy()
    }

    internal suspend fun perform(action: DeviceAction): Boolean = withContext(Dispatchers.Main) {
        when (action.kind) {
            DeviceActionKind.TAP_NODE -> findNode(action.target)?.let(::clickNode) == true
            DeviceActionKind.SET_TEXT -> {
                val node = findNode(action.target) ?: return@withContext false
                val arguments = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        action.value.orEmpty(),
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
            DeviceActionKind.SCROLL ->
                findNode(action.target)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
            DeviceActionKind.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            DeviceActionKind.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            else -> false
        }
    }

    private fun findNode(target: String?): AccessibilityNodeInfo? {
        val value = target?.trim().orEmpty()
        if (value.isEmpty()) return null
        val root = rootInActiveWindow ?: return null
        if (value.contains(':') && value.contains('/')) {
            root.findAccessibilityNodeInfosByViewId(value).firstOrNull()?.let { return it }
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val node = queue.removeFirst()
            if (!node.isPassword && node.isVisibleToUser) {
                if (node.text?.toString()?.contains(value, ignoreCase = true) == true ||
                    node.contentDescription?.toString()?.contains(value, ignoreCase = true) == true ||
                    node.viewIdResourceName == value
                ) {
                    return node
                }
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return null
    }

    private fun clickNode(start: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = start
        repeat(6) {
            val current = node ?: return false
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = current.parent
        }
        return false
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo): String {
        val values = linkedSetOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES && values.sumOf(String::length) < MAX_TEXT) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && !node.isPassword) {
                node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(values::add)
                node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(values::add)
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return values.joinToString("\n").take(MAX_TEXT)
    }

    private fun isKeyboardPackage(value: String): Boolean {
        val name = value.lowercase()
        return name.contains("inputmethod") ||
            name.contains("keyboard") ||
            name.contains("com.sohu.inputmethod") ||
            name.contains("com.baidu.input") ||
            name.contains("com.miui.securityinputmethod")
    }

    companion object {
        private const val TAG = "OfficeActivity"
        private const val MAX_NODES = 800
        private const val MAX_TEXT = 8_000
        private var activeService = WeakReference<OfficeAccessibilityService>(null)

        internal fun active(): OfficeAccessibilityService? = activeService.get()
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
