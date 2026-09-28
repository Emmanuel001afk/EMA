package com.ema.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * System-assistant surface. Voice is explicit here; opening the session does not
 * start continuous listening or force a voice popup.
 */
class EmaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        root.addView(TextView(context).apply {
            text = "EMA"
            textSize = 28f
        })
        root.addView(EditText(context).apply {
            hint = "Ask EMA…"
            minLines = 2
        })
        window?.window?.decorView?.let { decor ->
            (decor as? android.view.ViewGroup)?.removeAllViews()
            (decor as? android.view.ViewGroup)?.addView(root)
        }
    }
}
