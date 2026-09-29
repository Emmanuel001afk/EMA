package com.ema.assistant

class EmaReminderReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        EmaReminderScheduler.Receiver().onReceive(context, intent)
    }
}
