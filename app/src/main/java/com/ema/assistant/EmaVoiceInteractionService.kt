package com.ema.assistant

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

class EmaVoiceInteractionService : VoiceInteractionService() {
    companion object {
        @Volatile private var active: EmaVoiceInteractionService? = null
        fun showFromWake(): Boolean {
            val service = active ?: return false
            return runCatching {
                val args = android.os.Bundle().apply { putBoolean("wake_detected", true) }
                service.showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST)
                true
            }.getOrDefault(false)
        }
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    override fun onShutdown() {
        if (active === this) active = null
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(null, VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }

    override fun onReady() {
        super.onReady()
        active = this
        // Keep contextual assist data enabled so EMA can receive the current
        // app's assist structure/screenshot when Android permits it.
        setDisabledShowContext(0)
        // Warm EMA's built-in offline speech engine so assistant activations start immediately.
        if (EmaSpeechRecognition.speechMode(this).prefersEmaOffline) {
            EmaOfflineSpeechModel.warmUpAsync(this)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        EmaOfflineSpeechModel.onTrimMemory(level)
    }

}