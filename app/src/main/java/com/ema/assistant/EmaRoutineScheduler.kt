package com.ema.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recurring EMA commands. Definitions are persisted so Android reboot does not
 * silently remove the routine-to-alarm connection.
 */
object EmaRoutineScheduler {
    const val EXTRA_COMMAND = "ema.routine.command"
    const val EXTRA_ID = "ema.routine.id"
    private const val PREFS = "ema_routines"
    private const val KEY = "items"

    fun scheduleDaily(context: Context, command: String, hour: Int, minute: Int, weekdaysOnly: Boolean = false): Boolean =
        scheduleRepeating(context, command, hour, minute, AlarmManager.INTERVAL_DAY, weekdaysOnly, dayOfWeek = null)

    fun scheduleWeekly(context: Context, command: String, dayOfWeek: Int, hour: Int, minute: Int): Boolean {
        val now = Calendar.getInstance()
        val first = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            while (timeInMillis <= now.timeInMillis) add(Calendar.WEEK_OF_YEAR, 1)
        }
        return scheduleRepeating(
            context, command, first.timeInMillis, 7L * 24 * 60 * 60 * 1000,
            weekdaysOnly = false, dayOfWeek = dayOfWeek
        )
    }

    private fun scheduleRepeating(
        context: Context,
        command: String,
        hour: Int,
        minute: Int,
        interval: Long,
        weekdaysOnly: Boolean,
        dayOfWeek: Int?
    ): Boolean {
        val first = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return scheduleRepeating(context, command, first.timeInMillis, interval, weekdaysOnly, dayOfWeek)
    }

    private fun scheduleRepeating(
        context: Context,
        command: String,
        firstAt: Long,
        interval: Long,
        weekdaysOnly: Boolean,
        dayOfWeek: Int?
    ): Boolean {
        if (command.isBlank()) return false
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val id = nextId(context)
        val intent = Intent(context, EmaRoutineReceiver::class.java)
            .putExtra(EXTRA_COMMAND, command)
            .putExtra(EXTRA_ID, id)
            .putExtra("weekdays_only", weekdaysOnly)
        val pending = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ok = runCatching {
            alarms.setInexactRepeating(AlarmManager.RTC_WAKEUP, firstAt, interval, pending)
            true
        }.getOrDefault(false)
        if (ok) persist(context, id, command, firstAt, interval, weekdaysOnly, dayOfWeek)
        return ok
    }

    private fun nextId(context: Context): Int =
        ((System.currentTimeMillis() xor System.nanoTime()) and 0x7fffffff).toInt()

    private fun persist(
        context: Context,
        id: Int,
        command: String,
        firstAt: Long,
        interval: Long,
        weekdaysOnly: Boolean,
        dayOfWeek: Int?
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        entries.put(JSONObject().apply {
            put("id", id)
            put("command", command)
            put("firstAt", firstAt)
            put("interval", interval)
            put("weekdaysOnly", weekdaysOnly)
            put("dayOfWeek", dayOfWeek ?: -1)
        })
        prefs.edit().putString(KEY, entries.toString()).apply()
    }

    fun remove(context: Context, id: Int) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val intent = Intent(context, EmaRoutineReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pending?.let {
            alarms?.cancel(it)
            it.cancel()
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        val keep = JSONArray()
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.optInt("id", -1) != id) keep.put(item)
        }
        prefs.edit().putString(KEY, keep.toString()).apply()
    }

    fun restore(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1)
            val command = item.optString("command", "")
            val firstAt = item.optLong("firstAt", 0L)
            val interval = item.optLong("interval", 0L)
            if (id < 0 || command.isBlank() || firstAt <= 0L || interval <= 0L) continue
            val intent = Intent(context, EmaRoutineReceiver::class.java)
                .putExtra(EXTRA_COMMAND, command)
                .putExtra(EXTRA_ID, id)
                .putExtra("weekdays_only", item.optBoolean("weekdaysOnly", false))
            val pending = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val nextAt = if (firstAt > System.currentTimeMillis()) firstAt else {
                val now = System.currentTimeMillis()
                val elapsed = now - firstAt
                firstAt + ((elapsed / interval) + 1L) * interval
            }
            runCatching {
                alarms.setInexactRepeating(AlarmManager.RTC_WAKEUP, nextAt, interval, pending)
            }
        }
    }
}
