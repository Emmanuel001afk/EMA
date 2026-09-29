package com.ema.assistant

import android.service.voice.VoiceInteractionService

class EmaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        setDisabledShowContext(0)
    }
}