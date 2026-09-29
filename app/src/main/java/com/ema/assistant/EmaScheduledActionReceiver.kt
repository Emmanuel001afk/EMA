package com.ema.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Re-enters the normal EMA command path when a scheduled action fires.
 */
class EmaScheduledActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EmaScheduler.EXTRA_COMMAND).orEmpty()
        if (command.isBlank()) return

        val pendingResult = goAsync()
        var tts: TextToSpeech? = null

        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            }

            EmaCommandRouter(context.applicationContext, tts ?: TextToSpeech(context.applicationContext) {})
                .execute(command) {
                    tts?.shutdown()
                    pendingResult.finish()
                }
        }
    }
}
