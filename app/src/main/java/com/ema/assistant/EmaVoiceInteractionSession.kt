package com.ema.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * System-assistant surface. When Android invokes EMA as the selected assistant,
 * the session can accept spoken commands and route them through EMA's command
 * engine. AI-backed "Ask" remains separate and requires an AI provider.
 */
class EmaVoiceInteractionSession(context: Context) : android.service.voice.VoiceInteractionSession(context) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())
    private var sessionVisible = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        sessionVisible = true

        tts = TextToSpeech(context) { code ->
            if (code == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        root.addView(TextView(context).apply {
            text = "EMA"
            textSize = 28f
        })

        val input = EditText(context).apply {
            hint = "Tell EMA what to do…"
            minLines = 2
            maxLines = 5
        }
        root.addView(input, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val status = TextView(context).apply {
            text = "Listening…"
        }
        root.addView(status)

        fun execute(command: String) {
            if (command.isBlank()) {
                status.text = "I didn't hear a command."
                return
            }
            input.setText(command)
            status.text = "Working…"
            EmaCommandRouter(context, tts ?: TextToSpeech(context) {}).execute(command) { result ->
                status.post {
                    status.text = result
                    tts?.speak(result, TextToSpeech.QUEUE_FLUSH, null, "ema-result")
                }
            }
        }

        root.addView(Button(context).apply {
            text = "Ask"
            setOnClickListener { execute(input.text.toString().trim()) }
        })

        root.addView(Button(context).apply {
            text = "Voice"
            setOnClickListener { startListening(input, status, ::execute) }
        })

        window?.window?.decorView?.let { decor ->
            (decor as? ViewGroup)?.removeAllViews()
            (decor as? ViewGroup)?.addView(root)
        }

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening(input, status, ::execute)
        } else {
            status.text = "Microphone permission is required. Open EMA once and grant it."
        }
    }

    private fun startListening(input: EditText, status: TextView, onCommand: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status.text = "Speech recognition is unavailable."
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val command = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (command.isNullOrBlank()) {
                        status.text = "I couldn't hear that. Try again."
                    } else {
                        onCommand(command)
                    }
                    recognizer?.destroy()
                    recognizer = null
                }
                override fun onError(error: Int) {
                    status.text = "I couldn't hear that. Try again."
                    recognizer?.destroy()
                    recognizer = null
                }
                override fun onReadyForSpeech(params: Bundle?) { status.text = "Listening…" }
                override fun onBeginningOfSpeech() { status.text = "Hearing you…" }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        })
    }

    override fun onHide() {
        sessionVisible = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        super.onHide()
    }

    override fun onDestroy() {
        sessionVisible = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
