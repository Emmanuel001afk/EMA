package com.ema.assistant

import android.service.voice.VoiceInteractionService

class EmaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Keep contextual assist data enabled so EMA can receive the current
        // app's assist structure/screenshot when Android permits it.
        setDisabledShowContext(0)
    }

}