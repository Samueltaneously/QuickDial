
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

class TimerAlarmService : Service() {

    companion object {
        const val ACTION_START = "com.example.quickdial.ALARM_START"
        const val ACTION_STOP = "com.example.quickdial.ALARM_STOP"
        private const val CHANNEL = "quickdial_alarm"
        private const val NOTIFICATION_ID = 402

        fun stop(context: Context) {
            context.startService(
                Intent(context, TimerAlarmService::class.java)
                    .setAction(ACTION_STOP)
            )
        }
    }

    private var player: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopAlarm()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startAlarm()

        return START_NOT_STICKY
    }

    private fun startAlarm() {
        if (player?.isPlaying == true) return

        try {
            val uri = RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_ALARM
            ) ?: RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_NOTIFICATION
            ) ?: return

            player = MediaPlayer().apply {
                setDataSource(this@TimerAlarmService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_SONIFICATION
                        )
                        .build()
                )
                isLooping = true
                setOnPreparedListener { it.start() }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    player = null
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            player?.release()
            player = null
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL,
                "QuickDial ringing alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Sounds when a QuickDial timer finishes"
                setSound(null, null)
                setBypassDnd(false)
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
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
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }
}
