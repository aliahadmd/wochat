package com.aliahad.aichat.voice

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
import androidx.core.app.Person
import com.aliahad.aichat.MainActivity
import com.aliahad.aichat.R

/**
 * Holds the microphone for the duration of a call, and represents that call to the
 * system.
 *
 * Without a foreground service of type `microphone`, Android hands a backgrounded
 * app **silence** rather than an error, so a call survived the screen locking in
 * every visible respect and simply stopped hearing anything. Measured: two seconds
 * after the screen went off, `peak` fell from 0.2256 to exactly 0.0 and stayed
 * there for thousands of windows while capture ran on, `dropped=0` throughout.
 *
 * The notification uses [NotificationCompat.CallStyle], which is the platform's own
 * representation of an ongoing call rather than anything vendor-specific. That is
 * deliberate: it ranks the notification as a call, carries a hang-up action, and
 * earns whatever call treatment the device's system UI offers — a status-bar chip
 * on stock Android, and on HyperOS the notification system is what Xiaomi's island
 * is built on. One implementation, and nothing to unpick on a different phone.
 *
 * Deliberately separate from [com.aliahad.aichat.residency.ModelResidencyService]
 * rather than adding `microphone` to its types. A service declaring that type must
 * hold RECORD_AUDIO whenever it starts, and model residency has to keep working for
 * someone who has never made a call and never granted the microphone.
 */
class VoiceCallService : Service() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        // A channel's importance is fixed at creation, and 1.3.2 shipped this call
        // as IMPORTANCE_LOW. A call has to rank above ambient notifications, so this
        // is a new channel rather than an edit to one already on people's phones.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice call", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shows an ongoing call and keeps the microphone open"
                // High importance for ranking, but a call in progress is a state, not
                // an alert — it should never buzz at someone already talking to it.
                setSound(null, null)
                enableVibration(false)
            },
        )
        goForeground(callNotification())
    }

    private fun callNotification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangUp = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceCallService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val caller = Person.Builder().setName("wochat").setImportant(true).build()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_aichat)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, hangUp))
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun goForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) {
            // Ends the call itself, rather than only dropping the notification —
            // otherwise the microphone stays open behind a dismissed call.
            runCatching { hangUp?.invoke() }
                .onFailure { Log.w(TAG, "Hang up from the notification failed", it) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "voice_call_ongoing"
        private const val LEGACY_CHANNEL_ID = "voice_call"
        private const val NOTIFICATION_ID = 2002
        private const val TAG = "VoiceCallService"
        const val ACTION_HANG_UP = "com.aliahad.aichat.action.HANG_UP_CALL"

        /**
         * How the notification's hang-up button reaches the live call.
         *
         * Held for the length of the call only, and cleared on [stop]. Both sides live
         * in this process while a call is running, so this needs no binder — but a
         * stale reference would keep a ViewModel alive, hence the explicit clear.
         */
        @Volatile
        private var hangUp: (() -> Unit)? = null

        /**
         * Started only from a call the user just began, which is a foreground moment —
         * Android forbids starting a microphone service from the background, and
         * failing to start must never take the call down with it.
         */
        fun start(context: Context, onHangUp: () -> Unit) {
            hangUp = onHangUp
            runCatching {
                context.startForegroundService(Intent(context, VoiceCallService::class.java))
            }.onFailure { Log.w(TAG, "Could not hold the microphone for this call", it) }
        }

        fun stop(context: Context) {
            hangUp = null
            runCatching {
                context.stopService(Intent(context, VoiceCallService::class.java))
            }.onFailure { Log.w(TAG, "Could not release the microphone", it) }
        }
    }
}
