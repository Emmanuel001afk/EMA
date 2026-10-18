package com.ema.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores EMA's persisted one-shot schedules after Android reboots.
 */
class EmaBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("ema", Context.MODE_PRIVATE)
        if (prefs.getBoolean("wake_enabled", false)) {
            prefs.edit().putString("wake_status", "Stopped after reboot. Open EMA and tap Resume listening.").apply()
            // Android 14+ prohibits starting a microphone FGS from BOOT_COMPLETED.
            // Do not use alarms/accessibility to evade that restriction.
            EmaWakeWordService.postResumeNotice(app, "Tap to resume local listening after reboot")
        }
        EmaScheduler.restore(app)
        EmaRoutineScheduler.restore(app)
        EmaReminderScheduler.restore(app)
    }
}
