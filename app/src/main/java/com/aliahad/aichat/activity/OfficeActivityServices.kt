package com.aliahad.aichat.activity

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.aliahad.aichat.AiChatApplication
import com.aliahad.aichat.core.ActivitySource
import com.aliahad.aichat.core.DeviceAction
import com.aliahad.aichat.core.DeviceActionKind
import com.aliahad.aichat.overlay.ScreenContextProvider
import com.aliahad.aichat.overlay.ScreenBounds
import com.aliahad.aichat.overlay.ScreenNode
import com.aliahad.aichat.overlay.ScreenScanPage
import com.aliahad.aichat.overlay.ScreenScanResult
import com.aliahad.aichat.overlay.ScreenSnapshot
import com.aliahad.aichat.overlay.ScreenSnapshotSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import kotlin.coroutines.resume

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

class OfficeAccessibilityService : AccessibilityService(), ScreenContextProvider {
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
        val visibleText = buildVisibleSnapshot(root, now).visibleText
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

    override fun captureVisibleSnapshot(): ScreenSnapshot {
        val root = activeTargetRoot()
            ?: return ScreenSnapshot.unavailable("No active window is available.")
        return buildVisibleSnapshot(root, System.currentTimeMillis(), pageIndex = 0)
    }

    override suspend fun captureScrollableContext(
        maxPages: Int,
        restorePosition: Boolean,
    ): ScreenScanResult =
        withContext(Dispatchers.Main.immediate) {
            val startedAt = System.currentTimeMillis()
            val pageLimit = maxPages.coerceIn(1, 3)
            val pages = mutableListOf<ScreenScanPage>()
            val seenHashes = mutableSetOf<String>()
            var forwardScrolls = 0

            for (pageIndex in 0 until pageLimit) {
                val root = activeTargetRoot()
                    ?: return@withContext ScreenScanResult.unavailable("No active window is available.")
                val snapshot = buildVisibleSnapshot(root, System.currentTimeMillis(), pageIndex)
                val contentHash = snapshot.contentHash()
                if (pageIndex > 0 && !seenHashes.add(contentHash)) break
                if (pageIndex == 0) seenHashes.add(contentHash)
                pages += ScreenScanPage(
                    index = pageIndex,
                    snapshot = snapshot,
                    contentHash = contentHash,
                )
                if (snapshot.blockedReason != null || snapshot.visibleText.isBlank()) break
                if (pageIndex == pageLimit - 1) break

                if (!performDirectionalScroll(root, forward = true)) break
                forwardScrolls++
                delay(SCROLL_SETTLE_MS)
            }

            var restoredCount = 0
            if (restorePosition && forwardScrolls > 0) {
                for (ignored in 0 until forwardScrolls) {
                    val root = activeTargetRoot() ?: break
                    if (!performDirectionalScroll(root, forward = false)) break
                    restoredCount++
                    delay(RESTORE_SETTLE_MS)
                }
            }

            ScreenScanResult(
                pages = pages.ifEmpty {
                    listOf(
                        ScreenScanPage(
                            index = 0,
                            snapshot = ScreenSnapshot.unavailable("No readable target window is available."),
                            contentHash = "",
                        ),
                    )
                },
                startedAt = startedAt,
                completedAt = System.currentTimeMillis(),
                attemptedForwardScrolls = forwardScrolls,
                restorationAttempted = restorePosition && forwardScrolls > 0,
                restored = !restorePosition || forwardScrolls == 0 || restoredCount == forwardScrolls,
            )
    }

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
            DeviceActionKind.SCROLL -> {
                val root = findNode(action.target) ?: activeTargetRoot()
                root?.let { performDirectionalScroll(it, forward = true) } == true
            }
            DeviceActionKind.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            DeviceActionKind.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            else -> false
        }
    }

    private fun findNode(target: String?): AccessibilityNodeInfo? {
        val value = target?.trim().orEmpty()
        if (value.isEmpty()) return null
        val root = activeTargetRoot() ?: return null
        if (value.contains(':') && value.contains('/')) {
            root.findAccessibilityNodeInfosByViewId(value).firstOrNull()?.let { return it }
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val node = queue.removeFirst()
            if (!node.isPassword && node.isVisibleToUser) {
                if (nodeRefFor(node) == value ||
                    node.text?.toString()?.contains(value, ignoreCase = true) == true ||
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

    private fun activeTargetRoot(): AccessibilityNodeInfo? {
        val screenArea = resources.displayMetrics.widthPixels * resources.displayMetrics.heightPixels
        val candidates = runCatching { windows }.getOrNull().orEmpty()
            .mapNotNull(::targetRootCandidate)
            .filter { it.root.packageName?.toString()?.isAllowedTargetPackage() == true }
            .filter { hasUsefulTargetContent(it.root) }
            .sortedWith(
                compareByDescending<TargetRootCandidate> { it.isApplicationWindow }
                    .thenByDescending { it.area }
                    .thenByDescending { it.layer },
            )

        return candidates.firstOrNull { it.area >= screenArea / 4 }?.root
            ?: candidates.firstOrNull()?.root
            ?: rootInActiveWindow?.takeIf {
                it.packageName?.toString()?.isAllowedTargetPackage() == true &&
                    hasUsefulTargetContent(it)
            }
    }

    private fun targetRootCandidate(window: AccessibilityWindowInfo): TargetRootCandidate? {
        val root = runCatching { window.root }.getOrNull() ?: return null
        val bounds = Rect().also(window::getBoundsInScreen)
        return TargetRootCandidate(
            root = root,
            area = bounds.width().coerceAtLeast(0) * bounds.height().coerceAtLeast(0),
            layer = window.layer,
            isApplicationWindow = window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
        )
    }

    private fun String.isAllowedTargetPackage(): Boolean =
        isNotBlank() &&
            this != packageName &&
            !isSystemChromePackage(this) &&
            !isKeyboardPackage(this) &&
            !isSensitiveUiPackage(this)

    private fun isSystemChromePackage(value: String): Boolean {
        val name = value.lowercase()
        return name == "android" ||
            name == "com.android.systemui" ||
            name == "com.miui.systemui"
    }

    private fun hasUsefulTargetContent(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 120) {
            val node = queue.removeFirst()
            if (!node.isPassword && node.isVisibleToUser) {
                if (!node.text.isNullOrBlank() ||
                    !node.contentDescription.isNullOrBlank() ||
                    !node.viewIdResourceName.isNullOrBlank() ||
                    node.isClickable ||
                    node.isEditable ||
                    node.isScrollable
                ) {
                    return true
                }
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return false
    }

    private suspend fun performDirectionalScroll(root: AccessibilityNodeInfo, forward: Boolean): Boolean {
        if (dispatchDirectionalScrollGesture(forward)) return true
        val actionIds = scrollActionIds(forward)
        return findScrollableNodes(root, actionIds).any { node ->
            actionIds.any { actionId -> node.performAction(actionId) }
        }
    }

    private suspend fun dispatchDirectionalScrollGesture(forward: Boolean): Boolean =
        withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val width = resources.displayMetrics.widthPixels.toFloat()
                val height = resources.displayMetrics.heightPixels.toFloat()
                val x = width * 0.50f
                val startY = if (forward) height * 0.78f else height * 0.32f
                val endY = if (forward) height * 0.32f else height * 0.78f
                val path = Path().apply {
                    moveTo(x, startY)
                    lineTo(x, endY)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION_MS))
                    .build()
                val accepted = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                    null,
                )
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
        } == true

    private fun findScrollableNodes(
        root: AccessibilityNodeInfo,
        actionIds: List<Int>,
    ): List<AccessibilityNodeInfo> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val node = queue.removeFirst()
            if (!node.isPassword && node.isVisibleToUser) {
                val nodeActionIds = node.actionList.map { it.id }.toSet()
                val supportsDirectionalScroll = actionIds.any(nodeActionIds::contains)
                if (node.isScrollable || supportsDirectionalScroll) {
                    candidates += node
                }
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return candidates.sortedWith(
            compareByDescending<AccessibilityNodeInfo> { it.isFocused || it.isAccessibilityFocused }
                .thenByDescending(::nodeArea),
        )
    }

    private fun scrollActionIds(forward: Boolean): List<Int> =
        if (forward) {
            listOf(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
        } else {
            listOf(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            )
        }.distinct()

    private fun nodeArea(node: AccessibilityNodeInfo): Int {
        val bounds = Rect().also(node::getBoundsInScreen)
        return (bounds.width().coerceAtLeast(0) * bounds.height().coerceAtLeast(0))
    }

    private fun buildVisibleSnapshot(
        root: AccessibilityNodeInfo,
        capturedAt: Long,
        pageIndex: Int = 0,
    ): ScreenSnapshot {
        val nodes = mutableListOf<ScreenNode>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val node = queue.removeFirst()
            val bounds = Rect().also(node::getBoundsInScreen)
            val nodeRef = nodeRefFor(node)
            nodes += ScreenNode(
                id = runCatching { node.viewIdResourceName }.getOrNull()
                    ?: nodeRef,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                viewIdResourceName = runCatching { node.viewIdResourceName }.getOrNull(),
                className = node.className?.toString(),
                isPassword = node.isPassword,
                isVisibleToUser = node.isVisibleToUser,
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isScrollable = node.isScrollable,
                isFocused = node.isFocused || node.isAccessibilityFocused,
                pageIndex = pageIndex,
                nodeRef = nodeRef,
                boundsInScreen = ScreenBounds(
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                ),
                supportedActions = supportedActions(node),
            )
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return ScreenSnapshotSanitizer.sanitize(
            packageName = root.packageName?.toString(),
            ownPackageName = packageName,
            windowTitle = runCatching { root.window?.title?.toString() }.getOrNull(),
            rootClassName = root.className?.toString(),
            nodes = nodes,
            capturedAt = capturedAt,
        )
    }

    private fun nodeRefFor(node: AccessibilityNodeInfo): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        val signature = listOf(
            runCatching { node.viewIdResourceName }.getOrNull(),
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.className?.toString(),
            bounds.flattenToString(),
        ).joinToString("|")
        return "node-${Integer.toHexString(signature.hashCode())}"
    }

    private fun supportedActions(node: AccessibilityNodeInfo): Set<String> {
        val ids = node.actionList.map { it.id }.toSet()
        return buildSet {
            if (AccessibilityNodeInfo.ACTION_CLICK in ids || node.isClickable) add("CLICK")
            if (AccessibilityNodeInfo.ACTION_SET_TEXT in ids || node.isEditable) add("SET_TEXT")
            if (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids) add("SCROLL_FORWARD")
            if (AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids) add("SCROLL_BACKWARD")
        }
    }

    private fun ScreenSnapshot.contentHash(): String =
        listOf(packageName, windowTitle, rootClassName, visibleText)
            .joinToString("|")
            .hashCode()
            .let(Integer::toHexString)

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
        private const val SCROLL_SETTLE_MS = 350L
        private const val RESTORE_SETTLE_MS = 200L
        private const val GESTURE_DURATION_MS = 320L
        private const val GESTURE_TIMEOUT_MS = 1_200L
        private var activeService = WeakReference<OfficeAccessibilityService>(null)

        internal fun active(): OfficeAccessibilityService? = activeService.get()
    }

    private data class TargetRootCandidate(
        val root: AccessibilityNodeInfo,
        val area: Int,
        val layer: Int,
        val isApplicationWindow: Boolean,
    )
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
