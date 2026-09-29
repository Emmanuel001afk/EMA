package com.ema.assistant

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

class EmaVoiceInteractionService : VoiceInteractionService() {
    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(null, VoiceInteractionSession.SHOW_WITH_ASSIST)
    }

    override fun onReady() {
        super.onReady()
        // Keep contextual assist data enabled so EMA can receive the current
        // app's assist structure/screenshot when Android permits it.
        setDisabledShowContext(0)
    }

}