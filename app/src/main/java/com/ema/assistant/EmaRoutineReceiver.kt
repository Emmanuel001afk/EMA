package com.ema.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Fires a routine. A routine is a recurring command, so it is executed through
 * exactly the same path as anything the user says.
 */
class EmaRoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EmaRoutineScheduler.EXTRA_COMMAND).orEmpty()
        if (command.isBlank()) return

        // Weekday-only routines are dropped here, before goAsync(), so no
        // broadcast lifetime is spent on work that must not happen.
        if (intent.getBooleanExtra("weekdays_only", false)) {
            when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
                Calendar.SATURDAY, Calendar.SUNDAY -> return
            }
        }

        EmaReceiverRunner.run(context, command, goAsync())
    }
}
