package com.ema.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

class EmaVoiceInteractionService : VoiceInteractionService() {
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == EmaWakeWordService.ACTION_WAKE_DETECTED) {
                // Android decides whether the assistant surface may be shown over
                // the current lock-screen state. This does not unlock the device.
                showSession(null, VoiceInteractionSession.SHOW_WITH_ASSIST)
            }
        }
    }
    private var wakeReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(EmaWakeWordService.ACTION_WAKE_DETECTED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(wakeReceiver, filter)
        }
        wakeReceiverRegistered = true
    }

    override fun onDestroy() {
        if (wakeReceiverRegistered) {
            try { unregisterReceiver(wakeReceiver) } catch (_: IllegalArgumentException) {}
            wakeReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(null, VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }

    override fun onReady() {
        super.onReady()
        // Contextual assist data is available only when Android permits it.
        setDisabledShowContext(0)
    }
}
