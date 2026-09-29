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
        val pending = goAsync()
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
            EmaCommandRouter(context.applicationContext, tts ?: TextToSpeech(context.applicationContext) {}).execute(command) {
                tts?.shutdown()
                pending.finish()
            }
        }
    }
}
