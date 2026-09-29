package com.ema.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar
import java.util.Locale

object EmaRoutineScheduler {
    const val EXTRA_COMMAND = "ema.routine.command"
    const val EXTRA_ID = "ema.routine.id"

    fun scheduleDaily(context: Context, command: String, hour: Int, minute: Int, weekdaysOnly: Boolean = false): Boolean =
        scheduleRepeating(context, command, hour, minute, AlarmManager.INTERVAL_DAY, weekdaysOnly)

    fun scheduleWeekly(context: Context, command: String, dayOfWeek: Int, hour: Int, minute: Int): Boolean {
        val now = Calendar.getInstance()
        val first = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            while (timeInMillis <= now.timeInMillis) add(Calendar.WEEK_OF_YEAR, 1)
        }
        return scheduleRepeating(context, command, first.timeInMillis, 7L * 24 * 60 * 60 * 1000, false)
    }

    private fun scheduleRepeating(context: Context, command: String, hour: Int, minute: Int, interval: Long, weekdaysOnly: Boolean): Boolean {
        val first = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return scheduleRepeating(context, command, first.timeInMillis, interval, weekdaysOnly)
    }

    private fun scheduleRepeating(context: Context, command: String, firstAt: Long, interval: Long, weekdaysOnly: Boolean): Boolean {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val id = (System.currentTimeMillis() and 0x7fffffff).toInt()
        val intent = Intent(context, EmaRoutineReceiver::class.java)
            .putExtra(EXTRA_COMMAND, command).putExtra(EXTRA_ID, id).putExtra("weekdays_only", weekdaysOnly)
        val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return runCatching {
            alarms.setInexactRepeating(AlarmManager.RTC_WAKEUP, firstAt, interval, pending)
            true
        }.getOrDefault(false)
    }
}
