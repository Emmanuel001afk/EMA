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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable one-shot reminders.
 *
 * Two properties matter here and both were previously broken:
 *
 * 1. A scheduled reminder is **persisted at the moment it is scheduled**, not
 *    only in memory. Android drops all alarms on reboot, so without this the
 *    boot receiver had nothing to restore and every reminder silently vanished.
 * 2. Cancelling a reminder cancels the alarm *and* the stored entry, so a
 *    cancelled reminder cannot come back after a reboot.
 *
 * Entries are stored as JSON in shared preferences: no database dependency, and
 * small enough that reminder counts stay readable.
 */
object EmaReminderScheduler {
    const val EXTRA_TEXT = "ema.reminder.text"
    const val EXTRA_ID = "ema.reminder.id"
    private const val PREFS = "ema_reminders"
    private const val KEY = "items"
    private const val CHANNEL_ID = "ema_reminders"

    data class PendingReminder(val id: Int, val text: String, val atMillis: Long)

    /**
     * Schedule a reminder and store it.
     *
     * @return true only when the alarm was accepted *and* persisted.
     */
    fun schedule(context: Context, text: String, atMillis: Long): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        // AlarmManager rejects past trigger times; callers translate times
        // through EmaTimeParser, which always yields a future moment.
        if (atMillis <= System.currentTimeMillis()) return false
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false

        val id = (System.currentTimeMillis() and 0x7fffffff).toInt()
        if (!arm(alarms, context, id, trimmed, atMillis)) return false

        // Persist only after the alarm is armed, so storage never claims a
        // reminder the system refused.
        val entries = readEntries(context)
        entries.put(
            JSONObject().apply {
                put("id", id); put("text", trimmed); put("at", atMillis)
            }
        )
        writeEntries(context, entries)
        return true
    }

    /** Reminders still waiting to fire, soonest first. */
    fun pending(context: Context): List<PendingReminder> {
        val now = System.currentTimeMillis()
        return readEntries(context).items().mapNotNull { item ->
            val id = item.optInt("id", -1)
            val text = item.optString("text", "")
            val at = item.optLong("at", 0L)
            if (id < 0 || text.isBlank() || at <= now) null else PendingReminder(id, text, at)
        }.sortedBy { it.atMillis }
    }

    /**
     * Cancel reminders whose text contains any of [words] (case-insensitive).
     * @return how many were cancelled.
     */
    fun cancelMatching(context: Context, words: String): Int {
        val needle = words.trim().lowercase()
        val targets = pending(context).filter { reminder ->
            if (needle.isBlank()) true
            else needle.split(Regex("\\s+")).filter { it.isNotBlank() }
                .any { reminder.text.lowercase().contains(it) }
        }
        targets.forEach { cancel(context, it.id) }
        return targets.size
    }

    /** Cancel one reminder by id: alarm, pending intent and stored entry. */
    fun cancel(context: Context, id: Int) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pending = pendingIntentFor(context, id)
        runCatching { alarms?.cancel(pending) }
        runCatching { pending.cancel() }
        remove(context, id)
    }

    /** Drop a stored entry once its reminder has fired. */
    fun remove(context: Context, id: Int) {
        val entries = readEntries(context)
        val keep = JSONArray()
        entries.items().forEach { item ->
            if (item.optInt("id", -1) != id) keep.put(item)
        }
        writeEntries(context, keep)
    }

    /**
     * Re-arm every stored reminder after a reboot or an app update.
     *
     * Entries whose time has already passed are dropped rather than fired, so a
     * phone that was off at the reminder time does not suddenly announce stale
     * reminders.
     */
    fun restore(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val entries = readEntries(context)
        val keep = JSONArray()
        val now = System.currentTimeMillis()
        entries.items().forEach { item ->
            val id = item.optInt("id", -1)
            val text = item.optString("text", "")
            val at = item.optLong("at", 0L)
            if (id < 0 || text.isBlank() || at <= now) return@forEach
            if (arm(alarms, context, id, text, at)) keep.put(item)
        }
        writeEntries(context, keep)
    }

    // ---------------------------------------------------------------- internals

    private fun arm(alarms: AlarmManager, context: Context, id: Int, text: String, atMillis: Long): Boolean {
        val pending = pendingIntentFor(context, id, text)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }
            true
        }.getOrDefault(false)
    }

    /**
     * PendingIntent identity is the component plus action/data/categories — extras
     * are ignored — so the same id resolves to the same alarm whether or not the
     * reminder text is supplied.
     */
    private fun pendingIntentFor(context: Context, id: Int, text: String? = null): PendingIntent {
        val intent = Intent(context, EmaReminderReceiver::class.java).putExtra(EXTRA_ID, id)
        if (text != null) intent.putExtra(EXTRA_TEXT, text)
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * org.json.JSONArray is not Iterable, so iteration goes through this helper
     * rather than Kotlin's collection operators.
     */
    private fun JSONArray.items(): List<JSONObject> =
        (0 until length()).mapNotNull { index -> optJSONObject(index) }

    private fun readEntries(context: Context): JSONArray {
        val raw = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "[]")
        }.getOrNull() ?: "[]"
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun writeEntries(context: Context, entries: JSONArray) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, entries.toString()).apply()
        }
    }

    /** Announces a stored reminder as a heads-up notification. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
            val id = intent.getIntExtra(EXTRA_ID, 1)
            // Always clear the stored entry: the alarm has fired, and leaving it
            // would make a reboot re-announce a reminder from the past.
            EmaReminderScheduler.remove(context, id)
            if (text.isBlank()) return

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "EMA reminders", NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = "Reminders EMA schedules for you" }
                )
            }
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val open = if (launch == null) null else PendingIntent.getActivity(
                context, id, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                id,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_ema_logo)
                    .setContentTitle("EMA reminder")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .apply { if (open != null) setContentIntent(open) }
                    .build()
            )
        }
    }
}
