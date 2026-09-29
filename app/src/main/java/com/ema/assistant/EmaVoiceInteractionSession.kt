package com.ema.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * EMA's system assistant surface.
 *
 * This is deliberately built from platform views so it can appear as the
 * VoiceInteractionSession supplied by Android without adding another UI stack.
 */
class EmaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var sessionVisible = false
    private var screenText = ""
    private var screenScreenshot: Bitmap? = null

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var response: TextView
    private lateinit var pulse: View
    private lateinit var input: EditText

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        sessionVisible = true
        initTts()

        val root = FrameLayout(context)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 24)
            background = GradientDrawable().apply {
                setColor(Color.rgb(25, 25, 28))
                cornerRadius = 42f
            }
            elevation = 18f
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        pulse = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(120, 90, 255))
            }
        }
        header.addView(pulse, LinearLayout.LayoutParams(22, 22).apply {
            rightMargin = 16
        })

        header.addView(TextView(context).apply {
            text = "EMA"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, 48, 1f))

        header.addView(TextView(context).apply {
            text = if (screenText.isBlank() && screenScreenshot == null) "Assistant" else "Screen ready"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_VERTICAL
        })
        panel.addView(header)

        status = TextView(context).apply {
            text = "Listening"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 22, 0, 4)
        }
        panel.addView(status)

        transcript = TextView(context).apply {
            text = "Speak naturally. I'm listening."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            minLines = 1
        }
        panel.addView(transcript)

        response = TextView(context).apply {
            text = ""
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 8)
            visibility = View.GONE
        }
        panel.addView(response)

        input = EditText(context).apply {
            hint = "Type to EMA"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            minLines = 1
            maxLines = 4
            setPadding(18, 10, 18, 10)
            background = GradientDrawable().apply {
                setColor(Color.rgb(42, 42, 47))
                cornerRadius = 28f
            }
        }
        panel.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 10 })

        val controls = LinearLayout(context).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        controls.addView(ImageButton(context).apply {
            contentDescription = "Voice"
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = transparentBackground()
            setOnClickListener { startListening() }
        }, LinearLayout.LayoutParams(52, 52))

        controls.addView(ImageButton(context).apply {
            contentDescription = "Send"
            setImageResource(android.R.drawable.ic_menu_send)
            background = transparentBackground()
            setOnClickListener { execute(input.text.toString()) }
        }, LinearLayout.LayoutParams(52, 52))

        panel.addView(controls)

        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            leftMargin = 18
            rightMargin = 18
            bottomMargin = 18
        })

        window?.window?.decorView?.let { decor ->
            (decor as? ViewGroup)?.removeAllViews()
            (decor as? ViewGroup)?.setBackgroundColor(Color.TRANSPARENT)
            (decor as? ViewGroup)?.addView(root)
        }

        startPulse()
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            setState("Ready", "Microphone permission is required for voice.")
        }

        if ((showFlags and SHOW_WITH_SCREENSHOT) != 0) {
            setState("Listening", "I can use the current screen as context.")
        }
    }

    private fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { code ->
            if (code == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }
    }

    private fun execute(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            setState("Listening", "I didn't hear a command.")
            return
        }

        recognizer?.cancel()
        transcript.text = "“$trimmed”"
        response.visibility = View.GONE
        setState("Thinking", "Working on it…")

        val speech = tts ?: TextToSpeech(context) {}
        EmaCommandRouter(context, speech).execute(trimmed) { result ->
            handler.post {
                if (!sessionVisible) return@post
                response.text = result
                response.visibility = View.VISIBLE
                setState("Ready", "What would you like me to do next?")
                tts?.speak(result, TextToSpeech.QUEUE_FLUSH, null, "ema-result")
                handler.postDelayed({
                    if (sessionVisible) startListening()
                }, 850L)
            }
        }
    }

    private fun startListening() {
        if (!sessionVisible) return
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            setState("Ready", "Microphone permission is required for voice.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            setState("Ready", "Speech recognition is unavailable on this phone.")
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    setState("Listening", "I'm listening…")
                    transcript.text = "Speak now"
                }

                override fun onBeginningOfSpeech() {
                    setState("Listening", "Hearing you…")
                }

                override fun onResults(results: Bundle?) {
                    val command = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    recognizer?.destroy()
                    recognizer = null
                    if (command.isNullOrBlank()) {
                        setState("Listening", "I couldn't hear that. Try again.")
                    } else {
                        execute(command)
                    }
                }

                override fun onError(error: Int) {
                    recognizer?.destroy()
                    recognizer = null
                    if (sessionVisible) setState("Listening", "I couldn't hear that. Try again.")
                }

                override fun onEndOfSpeech() {
                    if (sessionVisible) setState("Thinking", "Processing…")
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    private fun setState(title: String, detail: String) {
        if (!::status.isInitialized) return
        status.text = title
        transcript.text = detail
        status.contentDescription = title
    }

    private fun startPulse() {
        pulse.animate().scaleX(1.35f).scaleY(1.35f)
            .setDuration(650L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (sessionVisible) {
                    pulse.animate().scaleX(1f).scaleY(1f)
                        .setDuration(650L)
                        .withEndAction { if (sessionVisible) startPulse() }
                        .start()
                }
            }.start()
    }

    private fun transparentBackground(): android.graphics.drawable.Drawable =
        GradientDrawable().apply { setColor(Color.TRANSPARENT) }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        state.assistStructure?.let { screenText = extractAssistText(it, 12000) }
    }

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
            listOf(node.text?.toString(), node.hint?.toString(), node.contentDescription?.toString())
                .map { it?.trim().orEmpty() }
                .filter { it.isNotBlank() }
                .forEach { out.append(it.replace(Regex("\\s+"), " ")).append('\n') }
            for (i in 0 until node.childCount) {
                walk(node.getChildAt(i))
                if (out.length >= maxChars) return
            }
        }
        for (i in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(i).rootViewNode)
            if (out.length >= maxChars) break
        }
        return out.toString().trim().take(maxChars)
    }

    override fun onHide() {
        sessionVisible = false
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        handler.removeCallbacksAndMessages(null)
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
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
