package com.ema.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * System-assistant surface. Voice is explicit; showing the assistant does not
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

        val input = EditText(context).apply {
            hint = "Ask EMA…"
            minLines = 2
            maxLines = 5
        }
        root.addView(input, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val status = TextView(context).apply {
            text = "Type a command, then press Ask."
        }
        root.addView(status)

        root.addView(Button(context).apply {
            text = "Ask"
            setOnClickListener {
                val command = input.text.toString().trim()
                if (command.isBlank()) {
                    status.text = "Type a command first."
                    return@setOnClickListener
                }
                status.text = "Working…"
                EmaCommandRouter(context, android.speech.tts.TextToSpeech(context) {}).execute(command) { result ->
                    status.post { status.text = result }
                }
            }
        })

        window?.window?.decorView?.let { decor ->
            (decor as? ViewGroup)?.removeAllViews()
            (decor as? ViewGroup)?.addView(root)
        }
    }
}
