package com.ema.assistant

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * EMA's own exact alarm, used only for the cases the clock app cannot express:
 * a specific calendar day ("tomorrow at 7") rather than a repeating time.
 *
 * The clock app's ACTION_SET_ALARM has no date field. Pretending otherwise
 * would ring on the wrong day, so EMA schedules an AlarmManager alarm clock and
 * says which mechanism it used. It never claims the system Clock app did it.
 */
object EmaAlarmScheduler {

    const val EXTRA_LABEL = "ema.extra.ALARM_LABEL"
    const val EXTRA_ID = "ema.extra.ALARM_ID"
    private const val CHANNEL_ID = "ema_alarms"
    const val REQUEST_EXACT_ALARM = 7100

    fun canScheduleExact(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.canScheduleExactAlarms() else true
    }.getOrDefault(false)

    /** Opens Android's exact-alarm grant screen. Returns true when it opened. */
    fun requestExactAlarmPermission(context: Context): Boolean = runCatching {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun schedule(context: Context, triggerAtMillis: Long, label: String): Boolean = runCatching {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val id = (triggerAtMillis xor (triggerAtMillis ushr 32)).toInt()
        val intent = Intent(context, EmaAlarmReceiver::class.java).apply {
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_ID, id)
        }
        val pending = PendingIntent.getBroadcast(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val show = PendingIntent.getActivity(
            context, id, context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, show), pending)
        true
    }.getOrDefault(false)
}

/** Fires an EMA-scheduled alarm and posts the alarm notification. */
class EmaAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EmaAlarmScheduler.EXTRA_LABEL).orEmpty().ifBlank { "Wake up" }
        val id = intent.getIntExtra(EmaAlarmScheduler.EXTRA_ID, 1)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("ema_alarms", "EMA alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alarms EMA schedules when the clock app cannot take a date"
                }
            )
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val open = if (launch == null) null else PendingIntent.getActivity(
            context, id, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // API 34 added canUseFullScreenIntent(); called reflectively so the
        // module still compiles against older SDKs. Without the grant Android
        // shows an ordinary heads-up notification instead, which EMA states.
        val fullScreenAllowed = Build.VERSION.SDK_INT < 34 ||
            runCatching {
                manager.javaClass.getMethod("canUseFullScreenIntent").invoke(manager) as? Boolean
            }.getOrNull() ?: false
        manager.notify(
            id,
            androidx.core.app.NotificationCompat.Builder(context, "ema_alarms")
                .setSmallIcon(R.drawable.ic_ema_logo)
                .setContentTitle("EMA alarm")
                .setContentText(label)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .apply {
                    if (open != null) setContentIntent(open)
                    if (open != null && fullScreenAllowed) setFullScreenIntent(open, true)
                }
                .build()
        )
    }
}
