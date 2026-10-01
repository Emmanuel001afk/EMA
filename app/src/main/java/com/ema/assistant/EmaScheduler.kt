package com.ema.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent system-timed action layer.
 *
 * The scheduled item is the original EMA command, so execution re-enters the
 * existing command router instead of creating a second command implementation.
 */
object EmaScheduler {
    const val EXTRA_COMMAND = "ema.scheduled.command"
    const val EXTRA_ID = "ema.scheduled.id"

    fun schedule(context: Context, command: String, triggerAtMillis: Long): Boolean {
        if (command.isBlank() || triggerAtMillis <= System.currentTimeMillis()) return false

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val id = (System.currentTimeMillis() and 0x7fffffff).toInt()

        val intent = Intent(context, EmaScheduledActionReceiver::class.java).apply {
            putExtra(EXTRA_COMMAND, command)
            putExtra(EXTRA_ID, id)
        }

        val pending = PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pending
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pending
                )
            }
            true
        }.getOrDefault(false)

        if (ok) persist(context, id, command, triggerAtMillis)
        return ok
    }

    private fun persist(context: Context, id: Int, command: String, triggerAtMillis: Long) {
        val prefs = context.getSharedPreferences("ema_scheduler", Context.MODE_PRIVATE)
        val entries = runCatching {
            JSONArray(prefs.getString("one_shot", "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        entries.put(JSONObject().apply {
            put("id", id)
            put("command", command)
            put("trigger", triggerAtMillis)
        })
        prefs.edit().putString("one_shot", entries.toString()).apply()
    }

    fun remove(context: Context, id: Int) {
        val prefs = context.getSharedPreferences("ema_scheduler", Context.MODE_PRIVATE)
        val entries = runCatching {
            JSONArray(prefs.getString("one_shot", "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        val keep = JSONArray()
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            if (item.optInt("id", -1) != id) keep.put(item)
        }
        prefs.edit().putString("one_shot", keep.toString()).apply()
    }

    fun restore(context: Context) {
        val prefs = context.getSharedPreferences("ema_scheduler", Context.MODE_PRIVATE)
        val entries = runCatching {
            JSONArray(prefs.getString("one_shot", "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        val now = System.currentTimeMillis()
        val keep = JSONArray()
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1)
            val command = item.optString("command", "")
            val trigger = item.optLong("trigger", 0L)
            if (id < 0 || command.isBlank() || trigger <= now) continue
            val intent = Intent(context, EmaScheduledActionReceiver::class.java)
                .putExtra(EXTRA_COMMAND, command)
                .putExtra(EXTRA_ID, id)
            val restored = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    alarmManagerFor(context).canScheduleExactAlarms()
                ) alarmManagerFor(context).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, restored)
                else alarmManagerFor(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, restored)
                true
            }.onSuccess { keep.put(item) }
        }
        prefs.edit().putString("one_shot", keep.toString()).apply()
    }

    private fun alarmManagerFor(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun parseTimeToFutureMillis(value: String, nowMillis: Long = System.currentTimeMillis()): Long? {
        val cleaned = value.trim().lowercase(Locale.getDefault())
            .replace(".", "")
            .replace(Regex("\\s+"), " ")

        val normalized = mapOf(
            "one" to "1", "two" to "2", "three" to "3", "four" to "4",
            "five" to "5", "six" to "6", "seven" to "7", "eight" to "8",
            "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12"
        ).entries.fold(cleaned) { acc, (word, number) ->
            acc.replace(Regex("\\b" + word + "\\b"), number)
        }
        val match = Regex(
            """^(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$"""
        ).find(normalized) ?: return null

        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
        val meridiem = match.groupValues[3]

        if (minute !in 0..59) return null

        if (!meridiem.isNullOrBlank()) {
            if (hour !in 1..12) return null
            if (meridiem == "am" && hour == 12) hour = 0
            if (meridiem == "pm" && hour != 12) hour += 12
        } else if (hour !in 0..23) {
            return null
        }

        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (calendar.timeInMillis <= nowMillis) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        return max(calendar.timeInMillis, nowMillis + 1000L)
    }
}
