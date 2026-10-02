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
    private const val PREFS = "ema_reminders"
    private const val KEY = "items"

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

    private fun persist(context: Context, id: Int, text: String, atMillis: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = runCatching { org.json.JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrDefault(org.json.JSONArray())
        entries.put(org.json.JSONObject().apply {
            put("id", id); put("text", text); put("at", atMillis)
        })
        prefs.edit().putString(KEY, entries.toString()).apply()
    }

    fun remove(context: Context, id: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = runCatching { org.json.JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrDefault(org.json.JSONArray())
        val keep = org.json.JSONArray()
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.optInt("id", -1) != id) keep.put(item)
        }
        prefs.edit().putString(KEY, keep.toString()).apply()
    }

    fun restore(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = runCatching { org.json.JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrDefault(org.json.JSONArray())
        val keep = org.json.JSONArray()
        val now = System.currentTimeMillis()
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1)
            val text = item.optString("text", "")
            val at = item.optLong("at", 0L)
            if (id < 0 || text.isBlank() || at <= now) continue
            val intent = Intent(context, EmaReminderReceiver::class.java)
                .putExtra(EXTRA_TEXT, text).putExtra(EXTRA_ID, id)
            val pending = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarms.canScheduleExactAlarms()) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                }
            }.onSuccess { keep.put(item) }
        }
        prefs.edit().putString(KEY, keep.toString()).apply()
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
            EmaReminderScheduler.remove(context, intent.getIntExtra(EXTRA_ID, 1))
        }
    }
}
