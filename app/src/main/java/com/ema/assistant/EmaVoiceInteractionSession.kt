package com.ema.assistant

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Actual EMA assistant interaction surface.
 *
 * The system can deliver both structured assist data and a screenshot here.
 * EMA keeps those inputs available to its command engine instead of silently
 * discarding them.
 */
class EmaVoiceInteractionSession(context: Context) :
    VoiceInteractionSession(context) {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var sessionVisible = false
    private var screenText = ""
    private var screenScreenshot: Bitmap? = null

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        sessionVisible = true

        if (tts == null) {
            tts = TextToSpeech(context) { code ->
                if (code == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                }
            }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        root.addView(TextView(context).apply {
            text = "EMA"
            textSize = 28f
        })

        val contextStatus = TextView(context).apply {
            text = if (screenText.isBlank() && screenScreenshot == null) {
                "Ready"
            } else {
                "Screen context available"
            }
        }
        root.addView(contextStatus)

        val input = EditText(context).apply {
            hint = "Tell EMA what to do…"
            minLines = 2
            maxLines = 5
        }
        root.addView(
            input,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val status = TextView(context).apply {
            text = "Listening…"
        }
        root.addView(status)

        fun execute(command: String) {
            val trimmed = command.trim()
            if (trimmed.isBlank()) {
                status.text = "I didn't hear a command."
                return
            }

            input.setText(trimmed)
            status.text = "Working…"

            val speech = tts ?: TextToSpeech(context) {}
            EmaCommandRouter(context, speech).execute(trimmed) { result ->
                status.post {
                    status.text = result
                    tts?.speak(result, TextToSpeech.QUEUE_FLUSH, null, "ema-result")

                    if (sessionVisible) {
                        status.postDelayed({
                            if (sessionVisible) {
                                startListening(input, status, ::execute)
                            }
                        }, 700L)
                    }
                }
            }
        }

        root.addView(Button(context).apply {
            text = "Ask"
            setOnClickListener { execute(input.text.toString()) }
        })

        root.addView(Button(context).apply {
            text = "Voice"
            setOnClickListener { startListening(input, status, ::execute) }
        })

        window?.window?.decorView?.let { decor ->
            (decor as? ViewGroup)?.removeAllViews()
            (decor as? ViewGroup)?.addView(root)
        }

        // The system assistant invocation can arrive without RECORD_AUDIO being
        // granted to the app. In that case keep the assistant UI usable and
        // allow typed commands rather than failing the entire invocation.
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startListening(input, status, ::execute)
        } else {
            status.text = "Ready. Microphone permission is required for voice."
        }

        if ((showFlags and SHOW_WITH_ASSIST) != 0) {
            contextStatus.text = if (screenText.isBlank()) {
                "Screen context requested"
            } else {
                "Screen text available"
            }
        }
        if ((showFlags and SHOW_WITH_SCREENSHOT) != 0) {
            contextStatus.text = "Screen screenshot available"
        }
    }

    /**
     * Modern Android assist-data callback.
     */
    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)

        val structure = state.assistStructure
        if (structure != null) {
            screenText = extractAssistText(structure, 12000)
        }

        val content = state.assistContent
        if (content != null) {
            val description = content.description?.toString().orEmpty()
            if (description.isNotBlank()) {
                screenText = (screenText + "\n" + description).trim().take(12000)
            }
        }
    }

    /**
     * Receives the actual system-provided screenshot when Android allows it.
     */
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        screenScreenshot?.recycle()
        screenScreenshot = screenshot?.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun extractAssistText(
        structure: android.app.assist.AssistStructure,
        maxChars: Int
    ): String {
        val out = StringBuilder()

        fun walk(node: android.app.assist.AssistStructure.ViewNode?) {
            if (node == null || out.length >= maxChars) return

            val text = node.text?.toString()?.trim().orEmpty()
            val hint = node.hint?.toString()?.trim().orEmpty()
            val contentDescription = node.contentDescription?.toString()?.trim().orEmpty()

            listOf(text, hint, contentDescription).forEach { value ->
                if (value.isNotBlank() && out.length < maxChars) {
                    out.append(value.replace(Regex("\\s+"), " "))
                        .append('\n')
                }
            }

            for (i in 0 until node.childCount) {
                walk(node.getChildAt(i))
                if (out.length >= maxChars) return
            }
        }

        for (windowIndex in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(windowIndex).rootViewNode)
            if (out.length >= maxChars) break
        }

        return out.toString().trim().take(maxChars)
    }

    private fun startListening(
        input: EditText,
        status: TextView,
        onCommand: (String) -> Unit
    ) {
        if (!sessionVisible) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status.text = "Speech recognition is unavailable."
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val command = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()

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

                override fun onReadyForSpeech(params: Bundle?) {
                    status.text = "Listening…"
                }

                override fun onBeginningOfSpeech() {
                    status.text = "Hearing you…"
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        })
    }

    override fun onHide() {
        sessionVisible = false
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        super.onHide()
    }

    override fun onDestroy() {
        sessionVisible = false
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        screenScreenshot?.recycle()
        screenScreenshot = null
        super.onDestroy()
    }
}
