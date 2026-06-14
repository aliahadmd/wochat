package com.aliahad.aichat.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aliahad.aichat.AiChatApplication
import com.aliahad.aichat.MainActivity
import com.aliahad.aichat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ActionBubbleService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { (application as AiChatApplication).container }
    private val controller by lazy { container.overlayAssistantController }
    private lateinit var windowManager: WindowManager
    private lateinit var notificationManager: NotificationManager
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var activeSnapshot: ScreenSnapshot? = null
    private var contextText: TextView? = null
    private var clearContextButton: Button? = null
    private var statusText: TextView? = null
    private var answerText: TextView? = null
    private var executeButton: Button? = null
    private var copyButton: Button? = null
    private var promptTemplates: List<FloatingPromptTemplate> = DEFAULT_FLOATING_PROMPT_TEMPLATES

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
        startForegroundCompat(notification())
        scope.launch {
            val position = kotlinx.coroutines.withContext(Dispatchers.IO) {
                container.settings.floatingAssistantBubblePosition.first()
            }
            showBubble(position)
        }
        scope.launch {
            controller.state.collectLatest(::renderState)
        }
        scope.launch {
            controller.overlayContextState.collectLatest(::renderContextState)
        }
        scope.launch {
            container.settings.floatingPromptTemplates.collectLatest { templates ->
                promptTemplates = templates
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopFromUser()
            ACTION_SHOW -> scope.launch { showPanel() }
            else -> {
                if (!Settings.canDrawOverlays(this)) stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Settings.canDrawOverlays(this) && bubbleView == null) {
            scope.launch {
                val position = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    container.settings.floatingAssistantBubblePosition.first()
                }
                showBubble(position)
            }
        }
    }

    override fun onDestroy() {
        controller.clearOverlayContext()
        removePanel()
        removeView(bubbleView)
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun stopFromUser() {
        scope.launch(Dispatchers.IO) {
            container.settings.setFloatingAssistantEnabled(false)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showBubble(position: Pair<Int, Int>) {
        if (bubbleView != null || !Settings.canDrawOverlays(this)) return
        val size = dp(56)
        val defaultX = (resources.displayMetrics.widthPixels - dp(72)).coerceAtLeast(0)
        val defaultY = dp(180)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.first.takeIf { it >= 0 } ?: defaultX
            y = position.second.takeIf { it >= 0 } ?: defaultY
        }
        val bubble = TextView(this).apply {
            text = ""
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "AIchat floating assistant"
            elevation = dp(8).toFloat()
            background = oval(Color.rgb(79, 70, 229))
            foreground = getDrawable(R.drawable.ic_floating_bubble_mark)
            setOnClickListener { showPanel() }
            installDragHandler(params)
        }
        bubbleView = bubble
        bubbleParams = params
        windowManager.addView(bubble, params)
    }

    private fun TextView.installDragHandler(params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this@ActionBubbleService).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > slop) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = (startX + dx.toInt()).coerceIn(0, resources.displayMetrics.widthPixels - width)
                        params.y = (startY + dy.toInt()).coerceIn(0, resources.displayMetrics.heightPixels - height)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        scope.launch(Dispatchers.IO) {
                            container.settings.setFloatingAssistantBubblePosition(params.x, params.y)
                        }
                    } else {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun showPanel() {
        removePanel()
        activeSnapshot = controller.captureVisibleSnapshot()
        val theme = overlayTheme()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(theme.surface, dp(22), theme.border)
            elevation = dp(12).toFloat()
        }
        val title = TextView(this).apply {
            text = "AIchat"
            textSize = 20f
            setTextColor(theme.primaryText)
        }
        val contextLabel = TextView(this).apply {
            text = "Context: empty"
            textSize = 12f
            setTextColor(theme.secondaryText)
        }
        contextText = contextLabel
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(contextLabel)
        }
        val clearButton = Button(this).apply {
            text = "Clear"
            isAllCaps = false
            minHeight = dp(36)
            minimumHeight = dp(36)
            setPadding(dp(12), 0, dp(12), 0)
            applySecondaryButtonStyle(theme)
            setOnClickListener {
                controller.clearOverlayContext()
            }
        }
        clearContextButton = clearButton
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                titleColumn,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
            addView(
                clearButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) },
            )
        }
        statusText = TextView(this).apply {
            text = snapshotStatus(activeSnapshot)
            textSize = 14f
            setTextColor(theme.secondaryText)
        }
        answerText = TextView(this).apply {
            textSize = 15f
            setTextColor(theme.primaryText)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
            text = "Write what you want, then tap Execute."
        }
        val customInput = EditText(this).apply {
            hint = "Pick a template or write your request"
            minLines = 1
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(theme.primaryText)
            setHintTextColor(theme.hintText)
            background = rounded(theme.field, dp(14), theme.border)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val templatesTitle = TextView(this).apply {
            text = if (promptTemplates.any(FloatingPromptTemplate::enabled)) {
                "Templates"
            } else {
                "Templates (none enabled)"
            }
            textSize = 13f
            setTextColor(theme.secondaryText)
        }
        fun applyTemplate(template: FloatingPromptTemplate) {
            customInput.setText(template.prompt)
            customInput.setSelection(customInput.text?.length ?: 0)
            statusText?.text = "${template.label} template ready. Tap Execute."
        }
        val templateGrid = templateGrid(
            templates = promptTemplates.filter(FloatingPromptTemplate::enabled),
            theme = theme,
            onSelect = ::applyTemplate,
        )
        val execute = Button(this).apply {
            text = "Execute"
            isAllCaps = false
            applyPrimaryButtonStyle(theme)
            setOnClickListener {
                val instruction = customInput.text?.toString().orEmpty().trim()
                if (instruction.isBlank()) {
                    statusText?.text = "Write what you want AIchat to do first."
                    return@setOnClickListener
                }
                text = "Scanning..."
                isEnabled = false
                hideKeyboard(customInput)
                removePanel()
                scope.launch {
                    delay(250)
                    controller.executeSimple(instruction)
                    showPanelWhenScanFinishes()
                }
            }
        }
        executeButton = execute
        copyButton = Button(this).apply {
            text = "Copy"
            isAllCaps = false
            isEnabled = false
            applySecondaryButtonStyle(theme)
            setOnClickListener {
                val text = answerText?.text?.toString().orEmpty()
                if (text.isBlank()) return@setOnClickListener
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("AIchat answer", text))
                statusText?.text = "Copied. Minimize AIchat and paste wherever you want."
            }
        }
        val minimizeButton = Button(this).apply {
            text = "Minimize"
            isAllCaps = false
            applySecondaryButtonStyle(theme)
            setOnClickListener {
                removePanel()
            }
        }
        val stopButton = Button(this).apply {
            text = "Stop bubble"
            isAllCaps = false
            applySecondaryButtonStyle(theme)
            setOnClickListener { stopFromUser() }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            listOf(copyButton, minimizeButton, stopButton).filterNotNull().forEach { button ->
                addActionButton(button)
            }
        }
        root.addView(header)
        root.addView(statusText)
        root.addView(templatesTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(12)
        })
        root.addView(templateGrid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(6)
        })
        root.addView(customInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(10)
        })
        root.addView(execute, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        root.addView(ScrollView(this).apply {
            background = rounded(theme.answerSurface, dp(16), theme.border)
            addView(answerText)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(220),
        ).apply {
            bottomMargin = dp(8)
        })
        root.addView(actions)
        val params = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - dp(24),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        panelView = root
        windowManager.addView(root, params)
        renderState(controller.state.value)
        renderContextState(controller.overlayContextState.value)
    }

    private fun renderState(state: OverlayAssistantState) {
        renderBubbleState(state)
        updateClearContextButton()
        when (state) {
            OverlayAssistantState.Idle -> {
                statusText?.text = snapshotStatus(activeSnapshot)
                answerText?.text = "Write what you want, then tap Execute."
                executeButton?.text = "Execute"
                executeButton?.isEnabled = true
                copyButton?.isEnabled = false
            }
            is OverlayAssistantState.Loading -> {
                statusText?.text = state.label
                answerText?.text = ""
                executeButton?.text = if (state.label.contains("Scanning", ignoreCase = true)) {
                    "Scanning..."
                } else {
                    "Generating..."
                }
                executeButton?.isEnabled = false
                copyButton?.isEnabled = false
            }
            is OverlayAssistantState.Answering -> {
                statusText?.text = "Writing..."
                answerText?.text = state.answer
                executeButton?.text = "Generating..."
                executeButton?.isEnabled = false
                copyButton?.isEnabled = state.answer.isNotBlank()
            }
            is OverlayAssistantState.AwaitingConfirmation -> {
                statusText?.text = "Action plans are disabled in simple mode."
                answerText?.text = state.plan.displayText()
                executeButton?.text = "Execute"
                executeButton?.isEnabled = false
                copyButton?.isEnabled = false
            }
            is OverlayAssistantState.Executing -> {
                statusText?.text = "Action execution is disabled in simple mode."
                answerText?.text = state.plan.displayText()
                executeButton?.text = "Execute"
                executeButton?.isEnabled = false
                copyButton?.isEnabled = false
            }
            is OverlayAssistantState.Complete -> {
                statusText?.text = "Done. Copy the answer, minimize AIchat, then paste manually."
                answerText?.text = state.result.answer.ifBlank { "No answer was generated." }
                executeButton?.text = "Execute"
                executeButton?.isEnabled = true
                copyButton?.isEnabled = state.result.answer.isNotBlank()
            }
            is OverlayAssistantState.Error -> {
                statusText?.text = state.message
                executeButton?.text = "Execute"
                executeButton?.isEnabled = true
                copyButton?.isEnabled = false
            }
        }
    }

    private fun renderContextState(state: OverlayContextState) {
        contextText?.text = if (state.count == 0) {
            "Context: empty"
        } else {
            "Context: ${state.count}"
        }
        updateClearContextButton()
    }

    private fun updateClearContextButton() {
        val hasContext = controller.overlayContextState.value.count > 0
        val isActive = controller.state.value !is OverlayAssistantState.Idle
        clearContextButton?.isEnabled = hasContext || isActive
    }

    private fun renderBubbleState(state: OverlayAssistantState) {
        val bubble = bubbleView as? TextView ?: return
        val (label, color) = when (state) {
            OverlayAssistantState.Idle -> "" to Color.rgb(79, 70, 229)
            is OverlayAssistantState.Loading -> {
                if (state.label.contains("Scanning", ignoreCase = true)) {
                    "Scan" to Color.rgb(234, 88, 12)
                } else {
                    "..." to Color.rgb(124, 58, 237)
                }
            }
            is OverlayAssistantState.Answering -> "..." to Color.rgb(124, 58, 237)
            is OverlayAssistantState.Complete -> "Done" to Color.rgb(22, 163, 74)
            is OverlayAssistantState.Error -> "!" to Color.rgb(220, 38, 38)
            is OverlayAssistantState.AwaitingConfirmation,
            is OverlayAssistantState.Executing -> "Plan" to Color.rgb(37, 99, 235)
        }
        bubble.text = label
        bubble.foreground = if (state is OverlayAssistantState.Idle) {
            getDrawable(R.drawable.ic_floating_bubble_mark)
        } else {
            null
        }
        bubble.textSize = if (label.length > 2) 13f else 17f
        bubble.background = oval(color)
        bubble.contentDescription = when (state) {
            OverlayAssistantState.Idle -> "AIchat floating assistant"
            is OverlayAssistantState.Loading -> state.label
            is OverlayAssistantState.Answering -> "AIchat is writing an answer"
            is OverlayAssistantState.Complete -> "AIchat answer is ready"
            is OverlayAssistantState.Error -> "AIchat floating assistant error"
            is OverlayAssistantState.AwaitingConfirmation -> "AIchat action plan is waiting"
            is OverlayAssistantState.Executing -> "AIchat action plan is executing"
        }
    }

    private suspend fun showPanelWhenScanFinishes() {
        repeat(SCAN_PANEL_WAIT_TICKS) {
            delay(SCAN_PANEL_WAIT_MS)
            val current = controller.state.value
            val scanning = current is OverlayAssistantState.Loading &&
                current.label.contains("Scanning", ignoreCase = true)
            if (!scanning) {
                if (panelView == null) showPanel()
                return
            }
        }
        if (panelView == null) showPanel()
    }

    private fun removePanel() {
        removeView(panelView)
        panelView = null
        activeSnapshot = null
        contextText = null
        clearContextButton = null
        statusText = null
        answerText = null
        executeButton = null
        copyButton = null
    }

    private fun removeView(view: View?) {
        if (view == null) return
        runCatching { windowManager.removeView(view) }
    }

    private fun LinearLayout.addActionButton(button: Button) {
        addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) },
        )
    }

    private fun templateGrid(
        templates: List<FloatingPromptTemplate>,
        theme: OverlayTheme,
        onSelect: (FloatingPromptTemplate) -> Unit,
    ): HorizontalScrollView =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = templates.size > VISIBLE_TEMPLATE_COUNT
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            val columnWidth = ((resources.displayMetrics.widthPixels - dp(76)) / 2).coerceAtLeast(dp(120))
            val columns = LinearLayout(this@ActionBubbleService).apply {
                orientation = LinearLayout.HORIZONTAL
                templates.chunked(TEMPLATE_ROWS).forEachIndexed { index, chunk ->
                    addView(
                        LinearLayout(this@ActionBubbleService).apply {
                            orientation = LinearLayout.VERTICAL
                            chunk.forEach { template ->
                                addTemplateGridButton(template, theme, onSelect)
                            }
                        },
                        LinearLayout.LayoutParams(
                            columnWidth,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            if (index > 0) marginStart = dp(8)
                        },
                    )
                }
            }
            addView(columns)
        }

    private fun LinearLayout.addTemplateGridButton(
        template: FloatingPromptTemplate,
        theme: OverlayTheme,
        onSelect: (FloatingPromptTemplate) -> Unit,
    ) {
        addView(
            Button(this@ActionBubbleService).apply {
                text = template.label
                isAllCaps = false
                minHeight = dp(42)
                minimumHeight = dp(42)
                setPadding(dp(8), 0, dp(8), 0)
                applyTemplateButtonStyle(theme)
                setOnClickListener { onSelect(template) }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (childCount > 0) topMargin = dp(6)
            },
        )
    }

    private fun snapshotStatus(snapshot: ScreenSnapshot?): String = when {
        snapshot == null -> "No screen snapshot is available."
        snapshot.blockedReason != null -> snapshot.blockedReason
        snapshot.visibleText.isBlank() -> "No readable text was found on this screen."
        else -> "Target: ${snapshot.packageName}. Execute scans downward and writes an answer to copy."
    }

    private fun hideKeyboard(view: View) {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun notification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ActionBubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_aichat)
            .setContentTitle("AIchat bubble is active")
            .setContentText("Tap the bubble over another app, ask, then copy the answer.")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Open AIchat", openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Floating assistant",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows the AIchat floating assistant bubble"
            },
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(color)
            strokeColor?.let { setStroke(dp(1), it) }
        }

    private fun Button.applyPrimaryButtonStyle(theme: OverlayTheme) {
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(theme.accent)
    }

    private fun Button.applySecondaryButtonStyle(theme: OverlayTheme) {
        setTextColor(theme.primaryText)
        backgroundTintList = ColorStateList.valueOf(theme.button)
    }

    private fun Button.applyTemplateButtonStyle(theme: OverlayTheme) {
        setTextColor(theme.accentText)
        backgroundTintList = ColorStateList.valueOf(theme.templateButton)
    }

    private fun overlayTheme(): OverlayTheme {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (isDark) {
            OverlayTheme(
                surface = Color.rgb(17, 24, 39),
                answerSurface = Color.rgb(31, 41, 55),
                field = Color.rgb(31, 41, 55),
                button = Color.rgb(55, 65, 81),
                templateButton = Color.rgb(49, 46, 129),
                border = Color.rgb(75, 85, 99),
                primaryText = Color.rgb(243, 244, 246),
                secondaryText = Color.rgb(209, 213, 219),
                hintText = Color.rgb(156, 163, 175),
                accent = Color.rgb(99, 102, 241),
                accentText = Color.rgb(224, 231, 255),
            )
        } else {
            OverlayTheme(
                surface = Color.rgb(255, 255, 255),
                answerSurface = Color.rgb(249, 250, 251),
                field = Color.rgb(249, 250, 251),
                button = Color.rgb(243, 244, 246),
                templateButton = Color.rgb(238, 242, 255),
                border = Color.rgb(209, 213, 219),
                primaryText = Color.rgb(17, 24, 39),
                secondaryText = Color.rgb(75, 85, 99),
                hintText = Color.rgb(107, 114, 128),
                accent = Color.rgb(79, 70, 229),
                accentText = Color.rgb(55, 48, 163),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "floating_assistant"
        private const val NOTIFICATION_ID = 2301
        private const val TAG = "ActionBubbleService"
        private const val SCAN_PANEL_WAIT_MS = 100L
        private const val SCAN_PANEL_WAIT_TICKS = 160
        private const val TEMPLATE_ROWS = 2
        private const val VISIBLE_TEMPLATE_COUNT = 4
        const val ACTION_SHOW = "com.aliahad.aichat.action.SHOW_FLOATING_ASSISTANT"
        const val ACTION_STOP = "com.aliahad.aichat.action.STOP_FLOATING_ASSISTANT"

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            runCatching {
                context.startForegroundService(
                    Intent(context, ActionBubbleService::class.java),
                )
            }.onFailure { Log.e(TAG, "Unable to start floating assistant", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ActionBubbleService::class.java))
        }
    }

    private data class OverlayTheme(
        val surface: Int,
        val answerSurface: Int,
        val field: Int,
        val button: Int,
        val templateButton: Int,
        val border: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val hintText: Int,
        val accent: Int,
        val accentText: Int,
    )
}
