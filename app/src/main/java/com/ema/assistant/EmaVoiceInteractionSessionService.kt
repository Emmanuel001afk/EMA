package com.ema.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Lightweight bridge between Android's VoiceInteractionService and EMA's
 * actual interaction session. Heavy interaction work belongs in the session,
 * not in the always-running VoiceInteractionService.
 */
class EmaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return EmaVoiceInteractionSession(this)
    }
}
