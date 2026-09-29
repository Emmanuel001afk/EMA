package com.ema.assistant

import android.content.Intent
import android.speech.RecognitionService

/**
 * RecognitionService required by Android's voice-interaction assistant
 * registration. EMA's assistant session performs its own speech recognition;
 * this service completes the assistant declaration required by Android.
 */
class EmaRecognitionService : RecognitionService() {

    override fun onStartListening(intent: Intent?, callback: Callback?) {
        // EMA performs recognition from its VoiceInteractionSession.
    }

    override fun onCancel(callback: Callback?) {}

    override fun onStopListening(callback: Callback?) {}
}
