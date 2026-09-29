package com.ema.assistant

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object EmaReminderScheduler {
    const val EXTRA_TEXT = "ema.reminder.text"
    const val EXTRA_ID = "ema.reminder.id"

    fun schedule(context: Context, text: String, atMillis: Long): Boolean {
        if (text.isBlank() || atMillis <= System.currentTimeMillis()) return false
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val id = (System.currentTimeMillis() and 0x7fffffff).toInt()
        val intent = Intent(context, EmaReminderReceiver::class.java)
            .putExtra(EXTRA_TEXT, text)
            .putExtra(EXTRA_ID, id)
        val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }
            true
        }.getOrDefault(false)
    }

    fun parseTime(value: String, nowMillis: Long = System.currentTimeMillis()): Long? =
        EmaScheduler.parseTimeToFutureMillis(value, nowMillis)

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
            if (text.isBlank()) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel("ema_reminders", "EMA reminders", NotificationManager.IMPORTANCE_HIGH))
            }
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val open = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(
                intent.getIntExtra(EXTRA_ID, 1),
                NotificationCompat.Builder(context, "ema_reminders")
                    .setSmallIcon(com.ema.assistant.R.drawable.ic_ema_logo)
                    .setContentTitle("EMA reminder")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
            )
        }
    }
}
