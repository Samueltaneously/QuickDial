
package com.example.quickdial

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.RingtoneManager
import android.media.Ringtone
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

class QuickDialWidget : AppWidgetProvider() {

    companion object {
        private const val PREFS = "quickdial"
        private const val MINUTES = "minutes"
        private const val END_TIME = "end_time"
        private const val REMAINING = "remaining"
        private const val RUNNING = "running"
        private const val ACTION = "com.example.quickdial.ACTION"
        private const val TICK = "com.example.quickdial.TICK"
        private const val CHANNEL = "quickdial_timer"
        private const val NOTIFICATION_ID = 401

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, QuickDialWidget::class.java)
            val ids = manager.getAppWidgetIds(component)
            val provider = QuickDialWidget()
            ids.forEach { provider.updateOne(context, manager, it) }
        }

        private fun schedule(context: Context, delay: Long) {
            val intent = Intent(context, QuickDialWidget::class.java)
                .setAction(TICK)
            val pending = PendingIntent.getBroadcast(
                context, 700, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val trigger = SystemClock.elapsedRealtime() + delay
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending
                )
            } else {
                alarm.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending
                )
            }
        }

        private fun cancelTick(context: Context) {
            val pending = PendingIntent.getBroadcast(
                context, 700,
                Intent(context, QuickDialWidget::class.java).setAction(TICK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.cancel(pending)
        }

        private fun notifyFinished(context: Context) {
            val intent = Intent(context, TimerAlarmService::class.java)
                .setAction(TimerAlarmService.ACTION_START)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }



        private fun dialBitmap(
            remainingSeconds: Int,
            totalSeconds: Int,
            running: Boolean
        ): Bitmap {
            val size = 480
            val bitmap = Bitmap.createBitmap(
                size, size, Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)

            // Leave the bitmap background transparent.
            val cx = size / 2f
            val cy = size / 2f
            val radius = 174f

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 9f
                strokeCap = Paint.Cap.ROUND
            }

            // Subtle progress-ring track.
            paint.color = Color.argb(45, 160, 160, 180)
            canvas.drawCircle(cx, cy, radius, paint)

            // Purple countdown progress.
            val fraction = if (totalSeconds > 0) {
                remainingSeconds.coerceAtLeast(0).toFloat() / totalSeconds
            } else {
                0f
            }

            paint.color = if (running) {
                Color.WHITE
            } else {
                Color.LTGRAY
            }

            canvas.drawArc(
                cx - radius, cy - radius,
                cx + radius, cy + radius,
                -90f,
                fraction.coerceIn(0f, 1f) * 360f,
                false,
                paint
            )

            // Simple digital countdown.
            val minutes = remainingSeconds.coerceAtLeast(0) / 60
            val seconds = remainingSeconds.coerceAtLeast(0) % 60

            paint.style = Paint.Style.FILL
            paint.strokeCap = Paint.Cap.BUTT
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 76f
            paint.typeface = Typeface.create(
                "sans-serif-medium", Typeface.NORMAL
            )

            canvas.drawText(
                "%02d:%02d".format(minutes, seconds),
                cx,
                cy + 25f,
                paint
            )

            return bitmap
        }


    }

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray
    ) {
        ids.forEach { updateOne(context, manager, it) }
        updateTimer(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val p = prefs(context)

        when (intent.action) {

            ACTION -> {
                when (intent.getStringExtra("command")) {
                    "plus" -> {
                        if (!p.getBoolean(RUNNING, false)) {
                            val current = p.getInt(MINUTES, 0)
                            val updated = (current + 1).coerceAtMost(60)

                            p.edit()
                                .putInt(MINUTES, updated)
                                .apply()
                        }
                    }

                    "minus" -> {
                        if (!p.getBoolean(RUNNING, false)) {
                            val current = p.getInt(MINUTES, 0)
                            val updated = (current - 1).coerceAtLeast(0)

                            p.edit()
                                .putInt(MINUTES, updated)
                                .apply()
                        }
                    }

                    "start" -> {
                        if (p.getBoolean(RUNNING, false)) {
                            // PAUSE: save the remaining time.
                            val left = remainingSeconds(context)
                            cancelTick(context)

                            p.edit()
                                .putBoolean(RUNNING, false)
                                .putInt(REMAINING, left)
                                .putLong(END_TIME, 0L)
                                .apply()
                        } else {
                            // Resume a paused timer, or start a new one.
                            val saved = p.getInt(REMAINING, 0)
                            val selected = p.getInt(MINUTES, 0) * 60
                            val left = if (saved > 0) saved else selected

                            if (left > 0) {
                                p.edit()
                                    .putInt(REMAINING, left)
                                    .putLong(
                                        END_TIME,
                                        System.currentTimeMillis() + left * 1000L
                                    )
                                    .putBoolean(RUNNING, true)
                                    .apply()

                                schedule(context, 1000)
                            }
                        }
                    }

                    "reset" -> {
                        // Cancel the countdown and clear all timer state.
                        cancelTick(context)

                        p.edit()
                            .putBoolean(RUNNING, false)
                            .putInt(REMAINING, 0)
                            .putInt(MINUTES, 0)
                            .putLong(END_TIME, 0L)
                            .apply()
                    }
                }

                refresh(context)
            }


            TICK -> updateTimer(context)
        }
    }

    private fun remainingSeconds(context: Context): Int {
        val p = prefs(context)
        val end = p.getLong(END_TIME, 0L)
        return ceil(((end - System.currentTimeMillis()).coerceAtLeast(0L)) / 1000.0).toInt()
    }

    private fun updateTimer(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(RUNNING, false)) {
            refresh(context)
            return
        }

        val left = remainingSeconds(context)
        if (left <= 0) {
            p.edit()
                .putBoolean(RUNNING, false)
                .putInt(REMAINING, 0)
                .putLong(END_TIME, 0L)
                .apply()
            cancelTick(context)
            notifyFinished(context)
        } else {
            p.edit().putInt(REMAINING, left).apply()
            schedule(context, 1000)
        }
        refresh(context)
    }

    private fun updateOne(
        context: Context,
        manager: AppWidgetManager,
        id: Int
    ) {
        val p = prefs(context)

        val running = p.getBoolean(RUNNING, false)
        val selectedSeconds = p.getInt(MINUTES, 0) * 60
        val remaining = p.getInt(REMAINING, 0)

        val seconds = when {
            running -> remainingSeconds(context)
            remaining > 0 -> remaining
            else -> selectedSeconds
        }


        val views = RemoteViews(
            context.packageName,
            R.layout.widget_quick_dial
        )

        views.setImageViewBitmap(
            R.id.dial_image,
            dialBitmap(seconds, selectedSeconds, running)
        )

        val hasActiveTimer = running || remaining > 0

        views.setViewVisibility(
            R.id.reset_button,
            if (hasActiveTimer) android.view.View.VISIBLE
            else android.view.View.INVISIBLE
        )


        fun bindButton(viewId: Int, command: String) {
            val requestCode = id * 10 + when (command) {
                "minus" -> 1
                "start" -> 2
                "reset" -> 3
                "plus" -> 4
                else -> 5
            }

            val intent = Intent(context, QuickDialWidget::class.java)
                .setAction(ACTION)
                .putExtra("command", command)

            val pending = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(viewId, pending)
        }


        bindButton(R.id.minus_button, "minus")
        bindButton(R.id.start_button, "start")
        bindButton(R.id.reset_button, "reset")
        bindButton(R.id.plus_button, "plus")
        manager.updateAppWidget(id, views)
    }
}
