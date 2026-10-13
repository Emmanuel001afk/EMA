package com.ema.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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

    /**
     * Resolve a spoken time to a future trigger, in milliseconds.
     *
     * Delegates to [EmaTimeParser] so the whole app has exactly one time parser.
     * This used to be a second, weaker implementation that understood only
     * "7", "7 am" and "7:30 pm"; every live call site had already moved to
     * EmaTimeParser, leaving the duplicate here to silently lose capability
     * ("in 20 minutes", "half past six", "tomorrow morning") if it were ever
     * wired up again.
     */
    fun parseTimeToFutureMillis(value: String, nowMillis: Long = System.currentTimeMillis()): Long? =
        EmaTimeParser.parseToFutureMillis(value, nowMillis)
}
