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
import com.aliahad.aichat.MainActivity
import com.aliahad.aichat.R

/**
 * Holds the microphone for the duration of a call.
 *
 * Without a foreground service of type `microphone`, Android hands a backgrounded
 * app **silence** rather than an error, so a call survived the screen locking in
 * every visible respect and simply stopped hearing anything. Measured on the device:
 * two seconds after the screen went off, `peak` fell from 0.2256 to exactly 0.0 and
 * stayed there for thousands of windows while the capture loop ran on, the process
 * awake and `dropped=0` throughout. Nothing failed; the audio was just gone.
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
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice call", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the microphone open while you are on a call"
            },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("On a call")
            .setContentText("wochat is listening")
            .setSmallIcon(R.drawable.ic_stat_aichat)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "voice_call"
        private const val NOTIFICATION_ID = 2002
        private const val TAG = "VoiceCallService"

        /**
         * Started only from a call the user just began, which is a foreground moment —
         * Android forbids starting a microphone service from the background, and
         * failing to start must never take the call down with it.
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, VoiceCallService::class.java))
            }.onFailure { Log.w(TAG, "Could not hold the microphone for this call", it) }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, VoiceCallService::class.java))
            }.onFailure { Log.w(TAG, "Could not release the microphone", it) }
        }
    }
}
