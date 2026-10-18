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
    private val wakeGate = EmaWakePhraseGate()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var sessionVisible = false
    private var wakeInvocation = false
    private var screenText = ""
    private var screenScreenshot: Bitmap? = null
    private var micEnabled = true
    private var micMode = "automatic"
    private var processingCommand = false
    private var speakingResponse = false
    private var commandGeneration = 0L
    private var commandWatchdog: Runnable? = null
    private val commandRouter: EmaCommandRouter by lazy { EmaCommandRouter(context) { tts } }
    private val prefs by lazy { context.getSharedPreferences("ema", Context.MODE_PRIVATE) }
    private val sessionTimeoutRunnable = Runnable {
        // Never close an active voice capture. SpeechRecognizer may still be
        // delivering microphone callbacks while the assistant is visible.
        if (sessionVisible && !processingCommand && !speakingResponse && recognizer == null) closeActive()
    }
    private val commandTimeoutMs = 90_000L

    companion object {
        @Volatile private var activeSession: EmaVoiceInteractionSession? = null

        /** True while Android has an active EMA voice-assistant session. */
        fun isActiveVisible(): Boolean = activeSession?.sessionVisible == true

        fun currentScreenScreenshotCopy(): Bitmap? =
            activeSession?.screenScreenshot?.copy(Bitmap.Config.ARGB_8888, false)

        fun currentAssistText(): String =
            activeSession?.screenText.orEmpty()

        fun withAssistantUiHidden(action: () -> Unit) {
            val session = activeSession ?: run {
                action()
                return
            }
            session.handler.post {
                val decor = session.getWindow()?.window?.decorView
                val wasVisible = decor?.visibility == View.VISIBLE
                decor?.visibility = View.INVISIBLE
                session.handler.postDelayed({
                    try {
                        action()
                    } finally {
                        session.handler.postDelayed({
                            if (wasVisible && session.sessionVisible) {
                                decor?.visibility = View.VISIBLE
                            }
                        }, 80L)
                    }
                }, 80L)
            }
        }

        fun closeActive() {
            activeSession?.let { session ->
                session.handler.post {
                    session.sessionVisible = false
                    session.recognizer?.cancel()
                    session.recognizer?.destroy()
                    session.recognizer = null
                    session.tts?.stop()
                    session.commandRouter.shutdown()
                    session.handler.removeCallbacksAndMessages(null)
                    session.finish()
                }
            }
        }
    }

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var response: TextView
    private lateinit var steps: TextView
    private var stepLines: MutableList<String> = mutableListOf()
    private lateinit var pulse: View
    private lateinit var input: EditText

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        sessionVisible = true
        wakeGate.reset()
        wakeInvocation = args?.getBoolean("wake_detected", false) == true
        EmaWakeWordService.pauseForSpeech()
        if (args?.getBoolean("wake_detected", false) == true) {
            wakeGate.accept("hey ema", true, android.os.SystemClock.elapsedRealtime())
        }
        activeSession = this
        getWindow()?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        micMode = prefs.getString("mic_mode", "automatic") ?: "automatic"
        micEnabled = prefs.getBoolean("mic_enabled", true)
        initTts()
        scheduleSessionTimeout()

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

        // Live plan progress from the router; hidden for single-step requests.
        steps = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.rgb(200, 200, 215))
            setPadding(0, 6, 0, 6)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        panel.addView(steps)
        commandRouter.progressListener = object : EmaProgressListener {
            override fun onPlan(steps: List<String>) { handler.post { showSteps(steps) } }
            override fun onStepStarted(index: Int) { handler.post { markStep(index, "◐"); setState("Working", "Step ${index + 1} of ${stepLines.size}") } }
            override fun onStepFinished(index: Int, outcome: EmaStepOutcome) {
                handler.post {
                    markStep(index, when (outcome.status) {
                        EmaOutcomeStatus.SUCCESS -> "✓"; EmaOutcomeStatus.UNVERIFIED -> "!"; EmaOutcomeStatus.SKIPPED -> "–"
                        EmaOutcomeStatus.NEEDS_INPUT, EmaOutcomeStatus.NEEDS_PERMISSION -> "?"; else -> "✕"
                    })
                }
            }
            override fun onClarification(question: String, options: List<String>) {
                handler.post {
                    setState("Needs your answer", question)
                    // A question the plan is waiting on must be audible, not
                    // only displayed: "Step 2 done — continue?"
                    tts?.speak(question, TextToSpeech.QUEUE_FLUSH, null, "ema-question")
                }
            }
        }

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

        if (context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            controls.addView(android.widget.Button(context).apply {
                text = "Unlock securely"
                setOnClickListener {
                    context.startActivity(Intent(context, EmaWakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
        }

        controls.addView(ImageButton(context).apply {
            contentDescription = if (micMode == "automatic") "Automatic microphone" else if (micEnabled) "Microphone on" else "Microphone off"
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = transparentBackground()
            setOnClickListener { toggleMicrophone() }
        }, LinearLayout.LayoutParams(52, 52))

        controls.addView(ImageButton(context).apply {
            contentDescription = "Send"
            setImageResource(android.R.drawable.ic_menu_send)
            background = transparentBackground()
            setOnClickListener { execute(input.text.toString()) }
        }, LinearLayout.LayoutParams(52, 52))

        panel.addView(controls)

        val panelParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            leftMargin = 18
            rightMargin = 18
            bottomMargin = 18
        }
        root.addView(panel, panelParams)
        root.setOnApplyWindowInsetsListener { _, insets ->
            panelParams.bottomMargin = 18 + insets.systemWindowInsetBottom
            panel.layoutParams = panelParams
            insets
        }
        root.requestApplyInsets()

        getWindow()?.window?.decorView?.let { decor ->
            (decor as? ViewGroup)?.removeAllViews()
            (decor as? ViewGroup)?.setBackgroundColor(Color.TRANSPARENT)
            (decor as? ViewGroup)?.addView(root)
        }

        startPulse()
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (micMode == "automatic" || micEnabled) startListening()
        } else {
            setState("Ready", "Microphone permission is required for voice.")
        }

        val userDisabledContext = getUserDisabledShowContext()
        val requestedScreenshot = (showFlags and SHOW_WITH_SCREENSHOT) != 0
        val screenshotDisabled = (userDisabledContext and SHOW_WITH_SCREENSHOT) != 0
        val assistDisabled = (userDisabledContext and SHOW_WITH_ASSIST) != 0

        if (assistDisabled || screenshotDisabled) {
            setState("Context limited", screenContextStatus())
        } else if (requestedScreenshot) {
            setState("Listening", "I can use the current screen as context.")
        }

        android.util.Log.i(
            "EMA",
            "Screen context: " + screenContextStatusDetail() + "; showFlags=" + showFlags
        )
    }

    private fun screenContextStatus(): String {
        val userDisabled = getUserDisabledShowContext()
        val assistDisabled = (userDisabled and SHOW_WITH_ASSIST) != 0
        val screenshotDisabled = (userDisabled and SHOW_WITH_SCREENSHOT) != 0

        return when {
            assistDisabled && screenshotDisabled ->
                "Android has disabled both screen assist data and screenshots for EMA in system settings."
            assistDisabled ->
                "Android has disabled screen assist data for EMA in system settings. Screenshot context may still be available."
            screenshotDisabled ->
                "Android has disabled screenshot context for EMA in system settings. Assist data may still be available."
            else ->
                "Android has not disabled EMA's requested assist or screenshot context. App-specific or transient restrictions can still apply."
        }
    }

    private fun screenContextStatusDetail(): String {
        val userDisabled = getUserDisabledShowContext()
        val assistDisabled = (userDisabled and SHOW_WITH_ASSIST) != 0
        val screenshotDisabled = (userDisabled and SHOW_WITH_SCREENSHOT) != 0
        return "assistDisabled=$assistDisabled, screenshotDisabled=$screenshotDisabled"
    }

    private fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { code ->
            if (code == TextToSpeech.SUCCESS) {
                tts?.let { EmaVoiceSetup.apply(context, it) }
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        handler.post { speakingResponse = true }
                    }
                    override fun onDone(utteranceId: String?) {
                        handler.post {
                            if (!sessionVisible) return@post
                            speakingResponse = false
                            if (wakeInvocation) {
                                closeActive()
                                return@post
                            }
                            val autoResume = micMode == "automatic" || (micMode == "semi" && micEnabled)
                            setState(if (autoResume) "Listening" else "Ready",
                                when (micMode) {
                                    "automatic" -> "Listening for the next part of the conversation…"
                                    "semi" -> if (micEnabled) "Conversation microphone is on." else "Tap the microphone to continue."
                                    else -> if (micEnabled) "Manual microphone is on." else "Tap the microphone to speak."
                                })
                            scheduleSessionTimeout()
                            if (autoResume && !processingCommand) startListening()
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        handler.post {
                            if (!sessionVisible) return@post
                            speakingResponse = false
                            if (wakeInvocation) {
                                closeActive()
                                return@post
                            }
                            scheduleSessionTimeout()
                            val autoResume = micMode == "automatic" || (micMode == "semi" && micEnabled)
                            if (autoResume && !processingCommand) startListening()
                        }
                    }
                })
            }
        }
    }

    private fun execute(command: String) {
        if (context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            setState("Phone locked", "Unlock securely before EMA can run commands.")
            return
        }
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            setState("Listening", "I didn't hear a command.")
            return
        }

        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        processingCommand = true
        val generation = ++commandGeneration
        commandWatchdog?.let(handler::removeCallbacks)
        val watchdog = Runnable {
            if (!sessionVisible || !processingCommand || generation != commandGeneration) return@Runnable
            processingCommand = false
            commandWatchdog = null
            response.text = "I couldn't complete that task."
            response.visibility = View.VISIBLE
            setState("Ready", "The task timed out. You can try again or press Back to close EMA.")
            speakingResponse = false
            tts?.stop()
            scheduleSessionTimeout()
        }
        commandWatchdog = watchdog
        handler.postDelayed(watchdog, commandTimeoutMs)
        scheduleSessionTimeout()
        transcript.text = "“$trimmed”"
        response.visibility = View.GONE
        stepLines.clear()
        if (::steps.isInitialized) steps.visibility = View.GONE
        setState("Understanding", "Working on it…")

        commandRouter.execute(trimmed) { result ->
            handler.post {
                if (!sessionVisible || generation != commandGeneration || !processingCommand) return@post
                processingCommand = false
                commandWatchdog?.let(handler::removeCallbacks)
                commandWatchdog = null
                response.text = result
                response.visibility = View.VISIBLE
                val outcome = EmaOutcomeClassifier.classify(result).status
                val color = when (outcome) {
                    EmaOutcomeStatus.SUCCESS, EmaOutcomeStatus.BLOCKED -> Color.rgb(120, 220, 160)
                    EmaOutcomeStatus.UNVERIFIED, EmaOutcomeStatus.NEEDS_INPUT, EmaOutcomeStatus.NEEDS_PERMISSION -> Color.rgb(255, 205, 110)
                    else -> Color.rgb(255, 140, 140)
                }
                setState(EmaConversationView.stateFor(outcome).label, "Reading the answer…", color)
                speakingResponse = true
                val speechStatus = runCatching {
                    tts?.speak(result, TextToSpeech.QUEUE_FLUSH, null, "ema-result")
                }.getOrNull()
                if (speechStatus == TextToSpeech.ERROR || tts == null) {
                    speakingResponse = false
                    setState("Ready", "Command completed.")
                    if (wakeInvocation) handler.postDelayed({ if (sessionVisible) closeActive() }, 3000)
                    scheduleSessionTimeout()
                } else {
                    scheduleSessionTimeout()
                }
            }
        }
    }

    private fun toggleMicrophone() {
        if (micMode == "automatic") {
            setState("Automatic", "Microphone resumes automatically after EMA speaks.")
            return
        }
        micEnabled = !micEnabled
        prefs.edit().putBoolean("mic_enabled", micEnabled).apply()
        if (!micEnabled) {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            setState("Microphone off", if (micMode == "semi") "Conversation paused. Tap the microphone to continue." else "Microphone is off. Tap the microphone to turn it on.")
        } else if (!processingCommand && !speakingResponse) {
            setState("Listening", if (micMode == "semi") "Conversation microphone is on." else "Manual microphone is on.")
            startListening()
        }
        scheduleSessionTimeout()
    }

    private fun startListening() {
        if (context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            setState("Phone locked", "Tap Unlock securely to continue. EMA cannot bypass your lock.")
            return
        }
        if (EmaWakeWordService.isRunning() && EmaWakeWordService.isCapturing()) {
            handler.postDelayed({ if (sessionVisible) startListening() }, 100)
            return
        }
        if (!sessionVisible || (micMode != "automatic" && !micEnabled) || processingCommand || speakingResponse) return
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
        val vocabulary = EmaSpeechRecognition.vocabulary(context)
        val created = EmaSpeechRecognition.createRecognizer(context)
        if (created == null) {
            setState("Ready", "No speech recognizer other than EMA is available on this phone.")
            return
        }
        recognizer = created.also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    android.util.Log.i("EMA", "SpeechRecognizer ready; mic session active")
                    setState("Listening", "I'm listening…")
                    transcript.text = "Speak now"
                }

                override fun onBeginningOfSpeech() {
                    android.util.Log.i("EMA", "SpeechRecognizer received microphone audio")
                    handler.removeCallbacks(sessionTimeoutRunnable)
                    setState("Listening", "Hearing you…")
                }

                override fun onResults(results: Bundle?) {
                    val command = EmaSpeechRecognition.chooseHypothesis(
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION),
                        vocabulary
                    )
                    recognizer?.destroy()
                    recognizer = null
                    if (command.isNullOrBlank()) {
                        setState("Listening", "I couldn't hear that. Try again.")
                        restartListeningIfNeeded()
                    } else {
                        val request = wakeGate.accept(command,
                            prefs.getBoolean("require_wake_phrase", false),
                            android.os.SystemClock.elapsedRealtime())
                        when {
                            request == null -> {
                                setState("Waiting for Hey EMA", "Say Hey EMA, followed by your request.")
                                restartListeningIfNeeded()
                            }
                            request.isBlank() -> {
                                setState("Listening", "What would you like me to do?")
                                restartListeningIfNeeded()
                            }
                            else -> execute(request)
                        }
                    }
                }

                override fun onError(error: Int) {
                    android.util.Log.w("EMA", "SpeechRecognizer error=$error")
                    recognizer?.destroy()
                    recognizer = null
                    if (sessionVisible) {
                        setState("Listening", "I couldn't hear that. Try again.")
                        restartListeningIfNeeded()
                    }
                }

                override fun onEndOfSpeech() {
                    android.util.Log.i("EMA", "SpeechRecognizer end of speech")
                    if (sessionVisible) {
                        setState("Thinking", "Processing…")
                        scheduleSessionTimeout()
                    }
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        recognizer?.startListening(
            EmaSpeechRecognition.intent(vocabulary, partialResults = true, language = Locale.getDefault())
        )
    }

    private fun restartListeningIfNeeded() {
        if (!sessionVisible || !micEnabled || processingCommand || speakingResponse) return
        handler.postDelayed({
            if (sessionVisible && (micMode == "automatic" || micEnabled) && !processingCommand && !speakingResponse && recognizer == null) startListening()
        }, 250L)
    }

    private fun scheduleSessionTimeout() {
        handler.removeCallbacks(sessionTimeoutRunnable)
        val timeout = prefs.getLong("assistant_timeout_ms", 0L)
        if (timeout > 0L) handler.postDelayed(sessionTimeoutRunnable, timeout)
    }

    private fun setState(title: String, detail: String, color: Int = Color.WHITE) {
        if (!::status.isInitialized) return
        status.text = title
        status.setTextColor(color)
        transcript.text = detail
        status.contentDescription = title
    }

    private fun showSteps(list: List<String>) {
        if (!::steps.isInitialized || list.size < 2) return
        stepLines = list.map { "○  $it" }.toMutableList()
        steps.text = stepLines.joinToString("\n")
        steps.visibility = View.VISIBLE
    }

    private fun markStep(index: Int, mark: String) {
        if (!::steps.isInitialized || index !in stepLines.indices) return
        stepLines[index] = mark + stepLines[index].drop(1)
        steps.text = stepLines.joinToString("\n")
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
        if (sessionVisible && (getUserDisabledShowContext() and SHOW_WITH_ASSIST) != 0) {
            setState("Context limited", screenContextStatus())
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        screenScreenshot?.recycle()
        screenScreenshot = screenshot?.copy(Bitmap.Config.ARGB_8888, false)
        if (screenScreenshot != null) {
            EmaAccessibilityService.updateAssistantScreenshot(screenScreenshot)
        } else if (sessionVisible && (getUserDisabledShowContext() and SHOW_WITH_SCREENSHOT) != 0) {
            setState("Context limited", screenContextStatus())
        }
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

    override fun onBackPressed() {
        commandGeneration++
        processingCommand = false
        commandWatchdog?.let(handler::removeCallbacks)
        commandWatchdog = null
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        commandRouter.shutdown()
        speakingResponse = false
        handler.removeCallbacksAndMessages(null)
        sessionVisible = false
        if (activeSession === this) activeSession = null
        finish()
    }

    override fun onHide() {
        sessionVisible = false
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        commandRouter.shutdown()
        handler.removeCallbacksAndMessages(null)
        commandWatchdog = null
        commandGeneration++
        speakingResponse = false
        processingCommand = false
        if (activeSession === this) activeSession = null
        EmaWakeWordService.resumeAfterSession()
        super.onHide()
    }

    override fun onDestroy() {
        sessionVisible = false
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        commandRouter.shutdown()
        screenScreenshot?.recycle()
        screenScreenshot = null
        handler.removeCallbacksAndMessages(null)
        speakingResponse = false
        processingCommand = false
        if (activeSession === this) activeSession = null
        EmaWakeWordService.resumeAfterSession()
        super.onDestroy()
    }
}
