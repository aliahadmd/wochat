package com.aliahad.aichat.residency

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aliahad.aichat.AiChatApplication
import com.aliahad.aichat.MainActivity
import com.aliahad.aichat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.aliahad.aichat.data.isDeviceCurrentlyLocked
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ModelResidencyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controller by lazy {
        (application as AiChatApplication).container.residencyController
    }
    private lateinit var notificationManager: NotificationManager
    private var preloadJob: Job? = null

    /**
     * Touching [controller] forces the container's lazy chain, which opens the
     * encrypted database. That key is unavailable behind the keyguard, so while
     * the device is locked this service must not reach for it at all — the
     * throw would land on a background thread and kill the process. The app
     * starts the service again after unlock.
     */
    private fun deviceLocked(): Boolean = isDeviceCurrentlyLocked()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
        val notification = notification(
            "Preparing local model",
            "Starting persistent CPU inference",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (deviceLocked()) {
            notificationManager.notify(
                NOTIFICATION_ID,
                notification("Waiting to unlock", "wochat opens when you unlock the device"),
            )
            return
        }
        scope.launch {
            controller.state.collectLatest { state ->
                notificationManager.notify(NOTIFICATION_ID, notificationFor(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (deviceLocked()) return START_STICKY
        when (intent?.action) {
            ACTION_UNLOAD -> {
                preloadJob?.cancel()
                preloadJob = scope.launch {
                    controller.unload()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_RETRY, ACTION_PRELOAD, null -> startPreload()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        startPreload()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startPreload() {
        if (preloadJob?.isActive == true) return
        preloadJob = scope.launch {
            runCatching { controller.preloadAfterUnlock() }
                .onFailure { Log.e(TAG, "Unable to preload the selected model", it) }
        }
    }

    private fun notificationFor(state: ModelResidencyState): Notification = when (state) {
        ModelResidencyState.Idle ->
            notification("Model unloaded", "Open wochat or tap Retry to load Gemma")
        ModelResidencyState.WaitingForUnlock ->
            notification("Waiting for unlock", "Gemma will load after the phone is unlocked")
        ModelResidencyState.WaitingForModel ->
            notification("No active model", "Download or select a GGUF model in wochat")
        is ModelResidencyState.Loading ->
            notification("Loading ${state.modelName}", "Pure CPU model load is in progress")
        is ModelResidencyState.Ready ->
            notification(
                "${state.modelName} loaded",
                residencyContextDescription(state),
            )
        is ModelResidencyState.Error ->
            notification("Model preload failed", state.message)
    }

    private fun notification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val retryIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ModelResidencyService::class.java).setAction(ACTION_RETRY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val unloadIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ModelResidencyService::class.java).setAction(ACTION_UNLOAD),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_aichat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Open wochat", openIntent)
            .addAction(0, "Retry", retryIntent)
            .addAction(0, "Unload", unloadIntent)
            .build()
    }

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Persistent local model",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the selected local LLM loaded for faster replies"
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "model_residency"
        private const val NOTIFICATION_ID = 2001
        private const val TAG = "ModelResidency"
        const val ACTION_PRELOAD = "com.aliahad.aichat.action.PRELOAD_MODEL"
        const val ACTION_RETRY = "com.aliahad.aichat.action.RETRY_MODEL"
        const val ACTION_UNLOAD = "com.aliahad.aichat.action.UNLOAD_MODEL"

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(
                    Intent(context, ModelResidencyService::class.java).setAction(ACTION_PRELOAD),
                )
            }.onFailure { Log.e(TAG, "Unable to start model residency service", it) }
        }
    }
}

private fun formatContext(tokens: Int): String = when {
    tokens >= 1_024 && tokens % 1_024 == 0 -> "${tokens / 1_024}K"
    else -> tokens.toString()
}

internal fun residencyContextDescription(state: ModelResidencyState.Ready): String {
    val active = formatContext(state.contextSize)
    val contextStatus = when {
        state.verifiedContextSize >= state.contextSize ->
            "$active verified"
        state.contextVerificationState ==
            com.aliahad.aichat.core.ContextVerificationState.FAILED ->
            "$active active fallback · baseline verification failed"
        state.contextVerificationState ==
            com.aliahad.aichat.core.ContextVerificationState.VERIFYING ->
            "$active active · verification in progress"
        else ->
            "$active active · verification pending"
    }
    return "CPU · $contextStatus · ${formatContext(state.declaredContextSize)} model maximum"
}
