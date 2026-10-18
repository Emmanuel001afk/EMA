package com.ema.assistant

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** User-tapped fallback and secure-unlock handoff. Never bypasses credentials. */
class EmaWakeActivity : Activity() {
    private var handedOff = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        panel.addView(TextView(this).apply { text = "Hey EMA\nUnlock your phone to run app actions."; textSize = 22f })
        panel.addView(Button(this).apply { text = "Unlock & continue"; setOnClickListener { unlockAndOpen() } })
        setContentView(panel)
        unlockAndOpen()
    }

    private fun unlockAndOpen() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { openAssistant() }
                override fun onDismissCancelled() { EmaWakeWordService.resumeAfterSession() }
                override fun onDismissError() { EmaWakeWordService.resumeAfterSession() }
            })
        } else openAssistant()
    }

    override fun onDestroy() {
        if (!handedOff) EmaWakeWordService.resumeAfterSession()
        super.onDestroy()
    }

    private fun openAssistant() {
        handedOff = true
        EmaWakeWordService.pauseForSpeech()
        if (!EmaVoiceInteractionService.showFromWake()) {
            startActivity(Intent(this, MainActivity::class.java).putExtra("wake_capture", true))
        }
        finish()
    }
}
