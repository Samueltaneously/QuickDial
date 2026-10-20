
package com.example.quickdial

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log

class TimerAlarmService : Service() {

    companion object {
        const val ACTION_START = "com.example.quickdial.ALARM_START"
        const val ACTION_STOP = "com.example.quickdial.ALARM_STOP"

        private const val CHANNEL = "quickdial_alarm"
        private const val NOTIFICATION_ID = 402
        private const val TAG = "QuickDialAlarm"

        fun stop(context: Context) {
            val intent = Intent(context, TimerAlarmService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private var player: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        Log.i(TAG, "Service started: ${intent?.action}")

        if (intent?.action == ACTION_STOP) {
            stopAlarm()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        return try {
            createChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            startAlarm()
            START_NOT_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm service", e)
            stopAlarm()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startAlarm() {
        if (player != null) return

        try {
            val uri = RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_ALARM
            ) ?: RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_RINGTONE
            ) ?: RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_NOTIFICATION
            )

            if (uri == null) {
                Log.e(TAG, "No default alarm, ringtone, or notification sound")
                return
            }

            Log.i(TAG, "Preparing alarm sound: $uri")

            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_SONIFICATION
                        )
                        .build()
                )

                setDataSource(this@TimerAlarmService, uri)
                isLooping = true

                setOnPreparedListener { mp ->
                    try {
                        mp.start()
                        Log.i(TAG, "Alarm playback started")
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not start alarm playback", e)
                        stopAlarm()
                    }
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(
                        TAG,
                        "MediaPlayer error: what=$what extra=$extra"
                    )
                    stopAlarm()
                    true
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Alarm initialization failed", e)
            stopAlarm()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(
                NotificationManager::class.java
            )

            // Android preserves existing channel settings. Do not rely
            // on changing an existing channel's sound configuration.
            val existing = manager.getNotificationChannel(CHANNEL)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL,
                    "QuickDial ringing alarm",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Sounds when a QuickDial timer finishes"
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            403,
            Intent(this, TimerAlarmService::class.java)
                .setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("QuickDial timer finished")
            .setContentText("Your timer is done. Tap Stop to silence the alarm.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "STOP ALARM",
                stopIntent
            )
            .build()
    }

    private fun stopAlarm() {
        val oldPlayer = player
        player = null

        try {
            oldPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing alarm player", e)
        }
    }

    override fun onDestroy() {
        stopAlarm()
        Log.i(TAG, "Alarm service destroyed")
        super.onDestroy()
    }
}
