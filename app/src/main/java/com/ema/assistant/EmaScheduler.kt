package com.ema.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

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

        return runCatching {
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
    }

    fun parseTimeToFutureMillis(value: String, nowMillis: Long = System.currentTimeMillis()): Long? {
        val cleaned = value.trim().lowercase(Locale.getDefault())
            .replace(".", "")
            .replace(Regex("\s+"), " ")

        val match = Regex(
            """^(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$"""
        ).find(cleaned) ?: return null

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
