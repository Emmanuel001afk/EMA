package com.ema.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import java.util.Locale

class EmaRoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EmaRoutineScheduler.EXTRA_COMMAND).orEmpty()
        if (command.isBlank()) return
        if (intent.getBooleanExtra("weekdays_only", false)) {
            val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            if (day == java.util.Calendar.SATURDAY || day == java.util.Calendar.SUNDAY) return
        }
        val pending = goAsync()
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
            EmaCommandRouter(context.applicationContext) { tts }.execute(command) {
                tts?.shutdown()
                pending.finish()
            }
        }
    }
}
