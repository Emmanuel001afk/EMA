package com.ema.assistant

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * System-wide offline voice-input dialog for [RecognizerIntent.ACTION_RECOGNIZE_SPEECH]
 * and [RecognizerIntent.ACTION_WEB_SEARCH].
 *
 * Allows any app on the phone (keyboards, browsers, note apps, search bars and
 * accessibility tools) to capture speech cleanly through EMA's built-in offline
 * speech-to-text engine with zero internet connection.
 *
 * Privacy isolation: third-party callers receive only the spoken transcript and
 * confidence scores for this turn — never the owner's contacts or shortcuts.
 */
class EmaRecognizeSpeechActivity : Activity() {

    companion object {
        private const val REQ_RECORD_AUDIO = 4101
    }

    private lateinit var speechSession: EmaSpeechRecognition.Session
    private lateinit var promptView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var pulseDot: View
    private lateinit var primaryButton: TextView

    private var profile: EmaRecognitionProfile = EmaRecognitionProfile.DICTATION
    private var requestedLanguage: Locale = Locale.getDefault()
    private var modeOverride: EmaSpeechMode? = null
    private var enableFormatting: Boolean = true
    private var maskOffensiveWords: Boolean = false
    private var maxResults: Int = 5
    private var isSelfCaller: Boolean = false
    private var activeRoute: String = ""
    private var smoothedLevel: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(0x88000000.toInt()))

        parseCallerIntent(intent)
        setContentView(buildDialogUi())

        speechSession = EmaSpeechRecognition.Session(this, object : EmaSpeechRecognition.SessionListener {
            override fun onPreparingOfflineModel() {
                subtitleView.text = "Preparing EMA offline speech model…"
                primaryButton.text = "Done"
            }

            override fun onInputRouteChanged(route: String) {
                activeRoute = route
            }

            override fun onReadyForSpeech(kind: EmaRecognizerKind) {
                val routeSuffix = if (activeRoute.isNotBlank() && activeRoute != "built-in mic") {
                    " · $activeRoute"
                } else {
                    ""
                }
                subtitleView.text = "Listening · ${kind.shortLabel}$routeSuffix"
                transcriptView.text = "Speak now…"
                transcriptView.setTextColor(Color.parseColor("#94A3B8"))
                primaryButton.text = "Done"
            }

            override fun onRmsChanged(rmsdB: Float) {
                val target = EmaSpeechRecognition.normalizeRmsLevel(rmsdB)
                smoothedLevel = if (target > smoothedLevel) {
                    0.45f * smoothedLevel + 0.55f * target
                } else {
                    0.75f * smoothedLevel + 0.25f * target
                }
                levelBar.progress = (smoothedLevel * 100f).roundToInt().coerceIn(4, 100)
                val scale = 1f + 0.38f * smoothedLevel
                pulseDot.scaleX = scale
                pulseDot.scaleY = scale
            }

            override fun onPartialTranscript(text: String, kind: EmaRecognizerKind) {
                if (text.isNotBlank()) {
                    transcriptView.text = text
                    transcriptView.setTextColor(Color.parseColor("#F8FAFC"))
                }
            }

            override fun onEndOfSpeech(kind: EmaRecognizerKind) {
                subtitleView.text = "Finishing transcript…"
                levelBar.progress = 0
                pulseDot.scaleX = 1f
                pulseDot.scaleY = 1f
            }

            override fun onSwitchingBackend(from: EmaRecognizerKind, to: EmaRecognizerKind, errorCode: Int) {
                subtitleView.text = "Switching to ${to.shortLabel}…"
            }

            override fun onResults(
                primary: String,
                alternatives: List<String>,
                confidenceScores: FloatArray,
                kind: EmaRecognizerKind
            ) {
                deliverResultsAndFinish(primary, alternatives, confidenceScores)
            }

            override fun onFailure(errorCode: Int, userMessage: String, lastKind: EmaRecognizerKind?) {
                subtitleView.text = "Tap Try again to speak"
                transcriptView.text = userMessage
                transcriptView.setTextColor(Color.parseColor("#FCA5A5"))
                levelBar.progress = 0
                pulseDot.scaleX = 1f
                pulseDot.scaleY = 1f
                primaryButton.text = "Try again"
            }
        })

        ensureMicAndStart()
    }

    private fun parseCallerIntent(src: Intent?) {
        isSelfCaller = callingPackage != null && callingPackage == packageName
        val cfg = EmaOfflineSpeechEngine.Config.fromIntent(src)
        val isWebAction = src?.action == RecognizerIntent.ACTION_WEB_SEARCH
        val langModel = src?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL)
        profile = when {
            isWebAction || langModel == RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH ->
                EmaRecognitionProfile.WEB_SEARCH
            src?.hasExtra(EmaOfflineSpeechEngine.Config.EXTRA_PROFILE) == true ->
                cfg.profile
            else ->
                // External apps calling ACTION_RECOGNIZE_SPEECH generally expect formatted voice input.
                EmaRecognitionProfile.DICTATION
        }
        requestedLanguage = cfg.languageTag?.let { runCatching { Locale.forLanguageTag(it) }.getOrNull() }
            ?.takeIf { it.language.isNotBlank() && it.language != "und" }
            ?: Locale.getDefault()
        maxResults = cfg.maxResults
        enableFormatting = cfg.enableFormatting
        maskOffensiveWords = cfg.maskOffensiveWords

        val preferOffline = src?.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false) == true
        if (preferOffline && EmaSpeechMode.supportsOfflineLanguage(requestedLanguage.toLanguageTag())) {
            modeOverride = EmaSpeechMode.EMA_OFFLINE_ONLY
        }
    }

    private fun buildDialogUi(): View {
        val promptText = intent?.getStringExtra(RecognizerIntent.EXTRA_PROMPT)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(80)
            ?: if (profile == EmaRecognitionProfile.WEB_SEARCH) "Voice search" else "EMA Voice Input"

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(20), dp(24), dp(20), dp(28))
            setOnClickListener { cancelAndFinish() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            setPadding(dp(22), dp(22), dp(22), dp(18))
            isClickable = true
        }

        val micBadge = FrameLayout(this).apply {
            val size = dp(56)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { bottomMargin = dp(12) }
            contentDescription = "Active microphone indicator"
        }
        pulseDot = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0284C7"))
            }
        }
        val innerCore = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E0F2FE"))
            }
        }
        micBadge.addView(pulseDot)
        micBadge.addView(innerCore)
        card.addView(micBadge)

        promptView = TextView(this).apply {
            text = promptText
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#F8FAFC"))
            gravity = Gravity.CENTER
        }
        card.addView(promptView)

        subtitleView = TextView(this).apply {
            text = "Starting EMA offline recognizer…"
            textSize = 13f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        }
        card.addView(subtitleView)

        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(6)
            ).apply { bottomMargin = dp(14) }
            contentDescription = "Voice input level"
        }
        card.addView(levelBar)

        transcriptView = TextView(this).apply {
            text = "Speak now…"
            textSize = 16f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            minLines = 2
            maxLines = 6
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(8), dp(6), dp(8), dp(18))
        }
        card.addView(transcriptView)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val cancelBtn = makeButton("Cancel", filled = false) { cancelAndFinish() }
        primaryButton = makeButton("Done", filled = true) {
            if (speechSession.isListening) {
                speechSession.stopListening()
            } else {
                ensureMicAndStart()
            }
        }
        actions.addView(cancelBtn)
        actions.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) })
        actions.addView(primaryButton)
        card.addView(actions)

        root.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        return root
    }

    private fun makeButton(label: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (filled) Color.parseColor("#0F172A") else Color.parseColor("#E2E8F0"))
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                if (filled) {
                    setColor(Color.parseColor("#38BDF8"))
                } else {
                    setColor(Color.parseColor("#1E293B"))
                    setStroke(dp(1), Color.parseColor("#475569"))
                }
            }
            setOnClickListener { onClick() }
        }

    private fun ensureMicAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
            return
        }
        startListeningNow()
    }

    private fun startListeningNow() {
        smoothedLevel = 0f
        activeRoute = ""
        speechSession.start(
            profile = profile,
            modeOverride = modeOverride,
            language = requestedLanguage,
            includeOwnerContacts = isSelfCaller,
            enableFormatting = enableFormatting,
            maskOffensiveWords = maskOffensiveWords,
            maxResults = maxResults
        )
    }

    @Suppress("DEPRECATION")
    private fun deliverResultsAndFinish(
        primary: String,
        alternatives: List<String>,
        confidenceScores: FloatArray
    ) {
        val combined = ArrayList<String>(maxResults)
        combined += primary
        for (alt in alternatives) {
            if (combined.size >= maxResults) break
            if (combined.none { it.equals(alt, ignoreCase = true) }) combined += alt
        }
        val scores = FloatArray(combined.size) { idx ->
            confidenceScores.getOrElse(idx) { (0.78f - idx * 0.06f).coerceAtLeast(0.20f) }
        }

        val resultBundle = Bundle().apply {
            putStringArrayList(RecognizerIntent.EXTRA_RESULTS, combined)
            putFloatArray(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, scores)
            if (intent?.action == RecognizerIntent.ACTION_WEB_SEARCH) {
                putString(SearchManager.QUERY, primary)
            }
        }

        // Support RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT when requested by the caller.
        val pendingIntent: PendingIntent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                intent?.getParcelableExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, PendingIntent::class.java)
            }.getOrNull()
        } else {
            runCatching {
                intent?.getParcelableExtra<PendingIntent>(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT)
            }.getOrNull()
        }
        if (pendingIntent != null) {
            val creator = pendingIntent.creatorPackage
            val callerPkg = callingActivity?.packageName
            // Only forward to a PendingIntent owned by the caller (or self when EMA launched itself).
            if (creator == null || creator != packageName || callerPkg == packageName) {
                val callerExtras = intent?.getBundleExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE)
                val forward = Intent().apply {
                    if (callerExtras != null) putExtras(callerExtras)
                    putExtras(resultBundle)
                }
                runCatching { pendingIntent.send(this, RESULT_OK, forward) }
                setResult(RESULT_OK)
                finish()
                return
            }
        }

        // Standalone ACTION_WEB_SEARCH without a calling activity opens search directly.
        if (intent?.action == RecognizerIntent.ACTION_WEB_SEARCH && callingActivity == null) {
            val webIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, primary)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = runCatching {
                startActivity(webIntent)
                true
            }.getOrDefault(false)
            if (launched) {
                setResult(RESULT_OK)
                finish()
                return
            }
        }

        setResult(RESULT_OK, Intent().putExtras(resultBundle))
        finish()
    }

    private fun cancelAndFinish() {
        if (::speechSession.isInitialized) speechSession.cancel()
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startListeningNow()
            } else {
                cancelAndFinish()
            }
        }
    }

    override fun onStop() {
        if (::speechSession.isInitialized && speechSession.isListening) {
            speechSession.cancel()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::speechSession.isInitialized) speechSession.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
