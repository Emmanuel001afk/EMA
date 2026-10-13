package com.ema.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-enters the normal EMA command path when a scheduled action fires.
 *
 * All lifetime handling lives in [EmaReceiverRunner] so scheduled actions,
 * routines and reminders cannot diverge in how they release the broadcast.
 */
class EmaScheduledActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EmaScheduler.EXTRA_COMMAND).orEmpty()
        val id = intent.getIntExtra(EmaScheduler.EXTRA_ID, -1)
        // Clear the stored entry first: a reboot must not re-fire it.
        if (id >= 0) EmaScheduler.remove(context, id)
        if (command.isBlank()) return

        EmaReceiverRunner.run(context, command, goAsync())
    }
}
