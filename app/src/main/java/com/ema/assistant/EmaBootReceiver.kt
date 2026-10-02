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
        EmaScheduler.restore(app)
        EmaRoutineScheduler.restore(app)
        EmaReminderScheduler.restore(app)
    }
}
