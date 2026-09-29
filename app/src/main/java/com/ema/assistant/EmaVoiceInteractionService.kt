package com.ema.assistant

import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService

/**
 * Lightweight system-level assistant service.
 *
 * Android keeps the selected VoiceInteractionService alive. UI, speech
 * recognition, screen context and command execution belong to the session.
 */
class EmaVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()

        // Ask Android for the contextual data that this assistant can consume
        // whenever the system launches its session with contextual flags.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setDisabledShowContext(0)
        }
    }

    override fun onPrepareToShowSession(args: Bundle, flags: Int) {
        super.onPrepareToShowSession(args, flags)
    }

    override fun onShutdown() {
        super.onShutdown()
    }
}
