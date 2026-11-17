package com.ema.assistant

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Switch
import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Environment
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.os.Bundle
import android.os.Build
import android.speech.tts.TextToSpeech
import android.service.voice.VoiceInteractionService
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private lateinit var status: TextView
    private lateinit var commandInput: EditText
    private lateinit var speakButton: Button
    private lateinit var dictateButton: Button
    private lateinit var transcribeButton: Button
    private lateinit var voiceLevelBar: android.widget.ProgressBar
    private var speechSession: EmaSpeechRecognition.Session? = null
    private var activeVoiceProfile: EmaRecognitionProfile = EmaRecognitionProfile.COMMAND
    private var activeInputRoute: String = ""
    private var smoothedVoiceLevel: Float = 0f
    private var dictationPrefix: String = ""
    @Volatile private var cancelFileTranscription: Boolean = false
    @Volatile private var fileTranscriptionRunning: Boolean = false
    private lateinit var router: EmaCommandRouter
    private lateinit var modeExplanation: TextView
    private lateinit var speechModeExplanation: TextView
    private lateinit var offlineSpeechStatus: TextView
    private lateinit var hubStatus: TextView
    private lateinit var hubAddress: EditText
    private lateinit var wakeStatus: TextView
    private lateinit var conversation: EmaConversationView
    private lateinit var voiceSection: LinearLayout
    private lateinit var modeChip: TextView
    private var ttsReady = false
    private val wakeUiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshWake = object : Runnable {
        override fun run() {
            if (::wakeStatus.isInitialized) {
                val prefs = getSharedPreferences("ema", MODE_PRIVATE)
                val enabled = prefs.getBoolean("wake_enabled", false)
                wakeStatus.text = when {
                    !enabled -> "Screen-off wake word is off."
                    !EmaWakeWordService.isRunning() -> "Listening is stopped. Tap Resume listening. " + prefs.getString("wake_status", "")
                    else -> prefs.getString("wake_status", "Starting…")
                }
            }
            if (::offlineSpeechStatus.isInitialized) {
                offlineSpeechStatus.text = EmaOfflineSpeechModel.statusSummary(this@MainActivity)
            }
            wakeUiHandler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        if (EmaSpeechRecognition.speechMode(this).prefersEmaOffline) {
            EmaOfflineSpeechModel.warmUpAsync(this) {
                if (::offlineSpeechStatus.isInitialized) {
                    offlineSpeechStatus.text = EmaOfflineSpeechModel.statusSummary(this)
                }
            }
        }
        requestRequiredPermissions()
        router = EmaCommandRouter(this) { tts }

        window.statusBarColor = Color.rgb(244, 246, 251)
        window.navigationBarColor = Color.rgb(244, 246, 251)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        var root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(16))
        }

        // Identity: who EMA is and how it is running right now.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        header.addView(TextView(this).apply {
            text = "E"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(98, 84, 199)) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titles.addView(TextView(this).apply {
            text = "EMA"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(27, 34, 56))
        })
        modeChip = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(82, 91, 114))
        }
        titles.addView(modeChip)
        header.addView(titles)
        root.addView(header)
        refreshModeChip()

        conversation = EmaConversationView(this) { option -> runRequest(option) }
        root.addView(conversation.root)
        router.progressListener = object : EmaProgressListener {
            override fun onPlan(steps: List<String>) = runOnUiThread { conversation.showPlan(steps) }
            override fun onStepStarted(index: Int) = runOnUiThread { conversation.stepStarted(index) }
            override fun onStepFinished(index: Int, outcome: EmaStepOutcome) = runOnUiThread { conversation.stepFinished(index, outcome) }
            override fun onClarification(question: String, options: List<String>) = runOnUiThread { conversation.showOptions(options) }
        }

        commandInput = EditText(this).apply {
            hint = "Tell EMA what to do…"
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedSurface(Color.WHITE)
            minLines = 2
            maxLines = 5
            isSingleLine = false
        }
        root.addView(commandInput, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "Send"
            contentDescription = "Send request to EMA"
            setOnClickListener { executeTypedCommand() }
        })
        speakButton = Button(this).apply {
            text = "Speak"
            contentDescription = "Speak a request"
            setOnClickListener { toggleVoiceCapture(EmaRecognitionProfile.COMMAND) }
        }
        actions.addView(speakButton)
        dictateButton = Button(this).apply {
            text = "Dictate"
            contentDescription = "Dictate text into the input box"
            setOnClickListener { toggleVoiceCapture(EmaRecognitionProfile.DICTATION) }
        }
        actions.addView(dictateButton)
        transcribeButton = Button(this).apply {
            text = "Audio"
            isAllCaps = false
            contentDescription = "Transcribe an audio file or voice note offline"
            setOnClickListener { toggleFileTranscriptionPicker() }
        }
        actions.addView(transcribeButton)
        root.addView(actions)

        voiceLevelBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            contentDescription = "Live voice input level"
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        root.addView(voiceLevelBar, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Setup and system notices (permissions, assistant role). Replies go to the conversation.
        status = TextView(this).apply {
            text = "Ready when you are."
            textSize = 14f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(Color.rgb(82, 91, 114))
            setPadding(dp(4), dp(10), dp(4), dp(4))
        }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "Use your own words. EMA will learn the names and choices you teach it."
            textSize = 13f
            setPadding(0, dp(18), 0, dp(8))
        })
        val home = root
        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedSurface(Color.WHITE)
        }
        home.addView(Button(this).apply {
            text = "Setup & preferences  +"
            isAllCaps = false
            setOnClickListener {
                val opening = settings.visibility != View.VISIBLE
                settings.visibility = if (opening) View.VISIBLE else View.GONE
                text = if (opening) "Setup & preferences  −" else "Setup & preferences  +"
            }
        })
        home.addView(settings)
        root = settings
        root.addView(Button(this).apply {
            text = "Open EMA access & permissions"
            setOnClickListener { openAccessSettings() }
        })
        root.addView(Button(this).apply {
            text = "Set EMA as digital assistant"
            setOnClickListener { requestAssistantRole() }
        })
        root.addView(Button(this).apply {
            text = "Open assistant settings"
            setOnClickListener { openAssistantSettings() }
        })
        root.addView(Button(this).apply {
            text = "Enable EMA screen control"
            setOnClickListener { openAccessibilitySettings() }
        })
        root.addView(Button(this).apply {
            text = "Enable notification access"
            setOnClickListener { openNotificationListenerSettings() }
        })
        root.addView(Button(this).apply {
            text = "Enable broad file access"
            setOnClickListener { openAllFilesAccess() }
        })

        root.addView(TextView(this).apply { text = "Hey EMA • screen-off listening"; textSize = 18f })
        root.addView(TextView(this).apply {
            text = "Built into EMA—no extra app, model import, account or download. The local model prepares automatically the first time. Audio stays on this phone during wake detection. Screen-off listening uses the microphone and battery; Android/Go may stop it. Say Hey EMA, pause for activation, then speak your request."
            textSize = 14f
        })
        wakeStatus = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(wakeStatus)
        root.addView(Switch(this).apply {
            text = "Enable screen-off Hey EMA"
            isChecked = getSharedPreferences("ema", MODE_PRIVATE).getBoolean("wake_enabled", false)
            setOnCheckedChangeListener { _, enabled ->
                getSharedPreferences("ema", MODE_PRIVATE).edit().putBoolean("wake_enabled", enabled).apply()
                if (enabled) startWakeListening() else stopService(Intent(this@MainActivity, EmaWakeWordService::class.java))
            }
        })
        root.addView(Button(this).apply { text = "Resume listening"; setOnClickListener { startWakeListening() } })
        root.addView(Button(this).apply {
            text = "Battery settings (optional)"
            setOnClickListener { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
        })

        // AI is optional; action execution always stays native.
        root.addView(TextView(this).apply {
            text = "Optional AI fallback"
            textSize = 16f
            setPadding(0, dp(16), 0, dp(2))
        })
        val modeSpinner = Spinner(this)
        val modes = EmaAssistantMode.values().toList()
        modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes.map { it.label })
        val assistantPrefs = getSharedPreferences("ema", MODE_PRIVATE)
        val savedMode = EmaAssistantMode.fromKey(assistantPrefs.getString("assistant_mode", EmaAssistantMode.DEFAULT_KEY))
        modeExplanation = TextView(this).apply {
            textSize = 14f
            text = savedMode.explanation
            setPadding(0, dp(4), 0, dp(4))
        }
        modeSpinner.setSelection(modes.indexOf(savedMode).coerceAtLeast(0))
        modeSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selected = modes.getOrNull(position) ?: return
                assistantPrefs.edit().putString("assistant_mode", selected.key).apply()
                modeExplanation.text = selected.explanation
                refreshHubStatus()
                refreshModeChip()
            }
        })
        root.addView(modeSpinner)
        root.addView(modeExplanation)

        // Central Hub address: the AI layer is optional and must be pointable at
        // the user's own Hub without rebuilding the app.
        root.addView(TextView(this).apply {
            text = "Central Hub address (optional, AI layer only)"
            textSize = 16f
            setPadding(0, dp(12), 0, dp(2))
        })
        hubAddress = EditText(this).apply {
            hint = CentralHubClient.hubUrl(this@MainActivity)
            isSingleLine = true
            setText(CentralHubClient.hubUrl(this@MainActivity))
            textSize = 13f
        }
        root.addView(hubAddress, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val hubRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hubRow.addView(Button(this).apply {
            text = "Save Hub address"
            setOnClickListener { saveHubAddress() }
        })
        hubRow.addView(Button(this).apply {
            text = "Reset to built-in"
            setOnClickListener {
                CentralHubClient.setHubUrl(this@MainActivity, "")
                hubAddress.setText(CentralHubClient.DEFAULT_HUB_URL)
                refreshHubStatus()
            }
        })
        root.addView(hubRow)
        hubStatus = TextView(this).apply { textSize = 14f }
        root.addView(hubStatus)
        refreshHubStatus()

        root.addView(TextView(this).apply {
            text = "Microphone mode"
            textSize = 16f
            setPadding(0, dp(12), 0, dp(2))
        })
        val micModeSpinner = Spinner(this)
        val micModeLabels = listOf(
            "Automatic conversation",
            "Semi-automatic conversation",
            "Manual microphone"
        )
        val micModeValues = listOf("automatic", "semi", "manual")
        micModeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, micModeLabels)
        val prefs = getSharedPreferences("ema", MODE_PRIVATE)
        val savedMicMode = prefs.getString("mic_mode", "automatic") ?: "automatic"
        micModeSpinner.setSelection(micModeValues.indexOf(savedMicMode).coerceAtLeast(0))
        micModeSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString("mic_mode", micModeValues[position]).apply()
            }
        })
        root.addView(micModeSpinner)
        root.addView(Switch(this).apply {
            text = "Require Hey EMA in assistant session"
            isChecked = prefs.getBoolean("require_wake_phrase", false)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean("require_wake_phrase", enabled).apply()
            }
        })
        root.addView(TextView(this).apply {
            text = "Speech recognition & offline speech-to-text"
            textSize = 16f
            setPadding(0, 24, 0, 4)
        })
        val speechModes = EmaSpeechMode.values().toList()
        val speechModeSpinner = Spinner(this)
        speechModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            speechModes.map { it.label }
        )
        val initialSpeechMode = EmaSpeechRecognition.speechMode(this)
        speechModeSpinner.setSelection(speechModes.indexOf(initialSpeechMode).coerceAtLeast(0))
        speechModeExplanation = TextView(this).apply {
            text = initialSpeechMode.explanation
            textSize = 13f
            setPadding(0, 4, 0, 6)
        }
        offlineSpeechStatus = TextView(this).apply {
            text = EmaOfflineSpeechModel.statusSummary(this@MainActivity)
            textSize = 13f
            setTextColor(Color.rgb(66, 78, 102))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, 2, 0, 6)
        }
        speechModeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = speechModes[position]
                if (chosen != EmaSpeechRecognition.speechMode(this@MainActivity)) {
                    EmaSpeechRecognition.setSpeechMode(this@MainActivity, chosen)
                }
                speechModeExplanation.text = chosen.explanation
                offlineSpeechStatus.text = EmaOfflineSpeechModel.statusSummary(this@MainActivity)
            }
        }
        root.addView(speechModeSpinner)
        root.addView(speechModeExplanation)
        root.addView(offlineSpeechStatus)
        root.addView(Button(this).apply {
            text = "Prepare offline speech engine"
            isAllCaps = false
            setOnClickListener {
                offlineSpeechStatus.text = "Preparing EMA's built-in offline speech engine…"
                EmaOfflineSpeechModel.warmUpAsync(this@MainActivity) {
                    offlineSpeechStatus.text = EmaOfflineSpeechModel.statusSummary(this@MainActivity)
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Transcribe audio file offline"
            isAllCaps = false
            setOnClickListener { toggleFileTranscriptionPicker() }
        })

        root.addView(TextView(this).apply {
            text = "Assistant session timeout"
            textSize = 16f
            setPadding(0, 24, 0, 4)
        })
        val timeoutSpinner = Spinner(this)
        val timeoutLabels = listOf("Manual — stay open until I close EMA", "15 seconds", "30 seconds", "1 minute", "2 minutes", "5 minutes")
        val timeoutValues = listOf(0L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L)
        timeoutSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, timeoutLabels)
        val savedTimeout = getSharedPreferences("ema", MODE_PRIVATE).getLong("assistant_timeout_ms", 0L)
        timeoutSpinner.setSelection(timeoutValues.indexOf(savedTimeout).coerceAtLeast(0))
        timeoutSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                getSharedPreferences("ema", MODE_PRIVATE).edit().putLong("assistant_timeout_ms", timeoutValues[position]).apply()
            }
        })
        root.addView(timeoutSpinner)

        root.addView(Switch(this).apply {
            text = "Ask me before each major step of a multi-step request"
            isChecked = getSharedPreferences("ema", MODE_PRIVATE).getBoolean("confirm_plan_steps", false)
            setOnCheckedChangeListener { _, on ->
                getSharedPreferences("ema", MODE_PRIVATE).edit().putBoolean("confirm_plan_steps", on).apply()
            }
        })
        root.addView(TextView(this).apply {
            text = "Off by default. When on, a plan pauses after each major step and waits for yes, no or stop."
            textSize = 13f
        })

        voiceSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(voiceSection)
        refreshVoiceUi()

        addVoiceProfileSection(root)

        addMemorySection(root)

        val registration = EmaAssistantRegistration.current(
            this,
            roleHeld = isAssistantRoleHeld(),
            serviceActive = isVoiceInteractionServiceActive()
        )
        root.addView(TextView(this).apply {
            text = "System assistant: " + EmaAssistantRegistration.describe(registration)
            textSize = 14f
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(244, 246, 251))
            isFillViewport = true
            addView(home)
        })
        if (intent.getBooleanExtra("wake_capture", false)) {
            commandInput.postDelayed({ listenOnce() }, 220)
            intent.removeExtra("wake_capture")
        } else {
            handleIncomingSharedAudio(intent)
        }
    }

    private fun startWakeListening() {
        val prefs = getSharedPreferences("ema", MODE_PRIVATE)
        if (!prefs.getBoolean("wake_enabled", false)) {
            wakeStatus.text = "Enable screen-off Hey EMA first."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_WAKE_MIC)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_WAKE_NOTIFY)
            return
        }
        EmaWakeWordService.startFromVisibleActivity(this)
    }

    override fun onPause() {
        wakeUiHandler.removeCallbacks(refreshWake)
        super.onPause()
    }

    private fun executeTypedCommand() {
        if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            status.text = "Unlock your phone before running commands."
            return
        }
        val command = commandInput.text.toString().trim()
        if (command.isBlank()) {
            status.text = "Type a command first."
            return
        }
        commandInput.setText("")
        runRequest(command)
    }

    /**
     * One path for typed, spoken and tapped requests, so the UI always reflects
     * the router.
     *
     * [guesses] are the other things the recogniser offered. They only reach
     * the on-device run trace, so a mistake can be seen afterwards.
     */
    private fun runRequest(command: String, guesses: List<String> = emptyList()) {
        conversation.addUser(command)
        conversation.setState(EmaConversationView.State.UNDERSTANDING)
        router.execute(command, guesses) { result ->
            runOnUiThread {
                val outcome = EmaOutcomeClassifier.classify(result)
                conversation.addAssistant(result, outcome.status)
            }
        }
    }

    private fun refreshModeChip() {
        if (!::modeChip.isInitialized) return
        val mode = EmaAssistantMode.fromKey(getSharedPreferences("ema", MODE_PRIVATE).getString("assistant_mode", EmaAssistantMode.DEFAULT_KEY))
        modeChip.text = when (mode) {
            EmaAssistantMode.NATIVE_ONLY -> "On-device assistant · AI off · works offline"
            EmaAssistantMode.NATIVE_FIRST -> "On-device assistant · works offline · optional AI for questions"
            EmaAssistantMode.AI_ASSIST -> "On-device actions · AI helps with wording"
        }
    }

    /**
     * Voice settings, built only after the TTS engine is ready so the list
     * reflects voices that are really installed. Spinners ignore the callback
     * Android fires while they are being set up: it used to silently save the
     * alphabetically first voice over the user's choice.
     */
    private fun refreshVoiceUi() {
        if (!::voiceSection.isInitialized) return
        voiceSection.removeAllViews()
        val prefs = getSharedPreferences("ema", MODE_PRIVATE)
        voiceSection.addView(TextView(this).apply {
            text = "Voice"
            textSize = 16f
            setPadding(0, dp(16), 0, dp(2))
        })
        if (!ttsReady) {
            voiceSection.addView(TextView(this).apply { text = "Loading the phone's voices…"; textSize = 14f })
            return
        }
        voiceSection.addView(TextView(this).apply {
            text = prefs.getString("voice_status", "Voice: the phone's default voice")
            textSize = 14f
        })
        val styles = EmaVoiceSelector.Style.values().toList()
        val currentStyle = EmaVoiceSelector.Style.fromKey(prefs.getString(EmaVoiceSetup.PREF_STYLE, null))
        val styleSpinner = Spinner(this)
        styleSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, styles.map { it.label })
        styleSpinner.setSelection(styles.indexOf(currentStyle))
        styleSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val chosen = styles[position]
                if (chosen == currentStyle) return
                prefs.edit().putString(EmaVoiceSetup.PREF_STYLE, chosen.key).remove(EmaVoiceSetup.PREF_VOICE).apply()
                EmaVoiceSetup.apply(this@MainActivity, tts)
                refreshVoiceUi()
            }
        })
        voiceSection.addView(styleSpinner)

        val allowNetwork = prefs.getBoolean(EmaVoiceSetup.PREF_ALLOW_NETWORK, false)
        val voices = EmaVoiceSetup.voices(tts)
        val ranked = EmaVoiceSelector.ranked(voices.map { EmaVoiceSetup.info(it) }, currentStyle, Locale.getDefault().toLanguageTag(), allowNetwork).take(25)
        val saved = prefs.getString(EmaVoiceSetup.PREF_VOICE, null)
        val labels = listOf("Automatic (best installed voice for this style)") + ranked.map { "${it.name} — ${EmaVoiceSelector.describe(it)}" }
        val voiceSpinner = Spinner(this)
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val currentIndex = ranked.indexOfFirst { it.name == saved }.let { if (it < 0) 0 else it + 1 }
        voiceSpinner.setSelection(currentIndex)
        voiceSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position == currentIndex) return
                val edit = prefs.edit()
                if (position == 0) edit.remove(EmaVoiceSetup.PREF_VOICE) else edit.putString(EmaVoiceSetup.PREF_VOICE, ranked[position - 1].name)
                edit.apply()
                EmaVoiceSetup.apply(this@MainActivity, tts)
                refreshVoiceUi()
            }
        })
        voiceSection.addView(voiceSpinner)
        if (ranked.isEmpty()) {
            voiceSection.addView(TextView(this).apply {
                text = "No installed offline voices were reported by the speech engine, so EMA uses its default voice."
                textSize = 13f
            })
        }
        voiceSection.addView(Switch(this).apply {
            text = "Also offer online voices (need internet)"
            isChecked = allowNetwork
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(EmaVoiceSetup.PREF_ALLOW_NETWORK, on).apply()
                EmaVoiceSetup.apply(this@MainActivity, tts)
                refreshVoiceUi()
            }
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Preview"
            setOnClickListener {
                tts.speak("Hi, I'm EMA. This is how I'll sound when I answer you.", TextToSpeech.QUEUE_FLUSH, null, "ema-preview")
            }
        })
        row.addView(Button(this).apply {
            text = "Get more voices"
            isAllCaps = false
            setOnClickListener {
                runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    .onFailure { runCatching { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } }
            }
        })
        voiceSection.addView(row)
        voiceSection.addView(TextView(this).apply {
            text = "Higher-quality voices can be downloaded for free in Android's text-to-speech settings (Install voice data). EMA only lists voices the engine reports as installed."
            textSize = 13f
        })
    }

    /**
     * The personal voice check for the wake word.
     *
     * The owner says "Hey EMA" eight times and EMA keeps the shape of their
     * own voice, so a television saying "Hey EMA" no longer wakes the phone.
     * Everything stays on the phone.
     */
    private fun addVoiceProfileSection(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "My voice for \"Hey EMA\""
            textSize = 16f
            setPadding(0, dp(16), 0, dp(2))
        })
        root.addView(TextView(this).apply {
            text = "Teach EMA your voice and it will check the sound of a wake word against your own, " +
                "so a television or a passer-by saying \"Hey EMA\" no longer wakes the phone. " +
                "The samples stay on this phone. Without a profile EMA uses the decoder alone, exactly as before."
            textSize = 13f
        })
        val profileStatus = TextView(this).apply { textSize = 13f }
        root.addView(profileStatus)
        fun refreshProfileStatus() {
            profileStatus.text = EmaVoiceTemplateStore.statusText(this@MainActivity)
        }
        refreshProfileStatus()
        val profileRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        profileRow.addView(Button(this).apply {
            text = "Teach EMA my voice"
            isAllCaps = false
            setOnClickListener {
                if (EmaVoiceEnrollmentEngine.isRunning()) {
                    status.text = "I am already learning your voice."
                    return@setOnClickListener
                }
                status.text = "Say \"Hey EMA\" eight times, starting now."
                EmaVoiceEnrollmentEngine.start(this@MainActivity, { prompt ->
                    runOnUiThread { status.text = prompt }
                }, { stored ->
                    runOnUiThread {
                        status.text = if (stored) {
                            "I have learned your voice."
                        } else {
                            "I could not finish learning your voice. Try again when the phone is quiet."
                        }
                        refreshProfileStatus()
                    }
                })
            }
        })
        profileRow.addView(Button(this).apply {
            text = "Forget my voice"
            isAllCaps = false
            setOnClickListener {
                EmaVoiceTemplateStore.clear(this@MainActivity)
                refreshProfileStatus()
                status.text = "I have forgotten your voice."
            }
        })
        root.addView(profileRow)
    }

    /** Compact privacy controls; learned vocabulary and diagnostics stay off the main UI. */
    private fun addMemorySection(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "Privacy"
            textSize = 16f
            setPadding(0, dp(16), 0, dp(2))
        })
        root.addView(TextView(this).apply {
            text = "Personal language, app preferences and contact choices stay in private on-device storage. Clear them at any time."
            textSize = 13f
        })
        root.addView(Button(this).apply {
            text = "Clear personal data"
            isAllCaps = false
            setOnClickListener {
                EmaMemoryStore.clear(this@MainActivity)
                EmaLearningStore.forgetAll(this@MainActivity)
                EmaLearningLog.setEnabled(this@MainActivity, false)
                EmaLearningLog.clear(this@MainActivity)
                EmaVoiceTemplateStore.clear(this@MainActivity)
                EmaRunTraceStore.clear(this@MainActivity)
                EmaConversationStore.clear(this@MainActivity)
                getSharedPreferences("ema_contact_choices", MODE_PRIVATE).edit().clear().apply()
                EmaConversationContext.clear()
                status.text = "Personal data cleared."
            }
        })
    }

    /**
     * RoleManager only exists on API 29+, and getSystemService returns null for
     * unknown classes on older releases. Every caller used to dereference the
     * result directly, which crashes on API 26-28.
     */
    private fun roleManager(): RoleManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { getSystemService(RoleManager::class.java) }.getOrNull()
        } else null

    private fun isAssistantRoleHeld(): Boolean =
        runCatching { roleManager()?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true }.getOrDefault(false)

    private fun saveHubAddress() {
        val typed = hubAddress.text.toString().trim()
        if (typed.isNotEmpty() && !typed.startsWith("https://")) {
            hubStatus.text = "The Hub address must start with https:// so the AI tunnel is encrypted."
            return
        }
        CentralHubClient.setHubUrl(this, typed)
        hubAddress.setText(CentralHubClient.hubUrl(this))
        refreshHubStatus()
    }

    /** States plainly whether the optional AI layer can be reached at all. */
    private fun refreshHubStatus() {
        if (!::hubStatus.isInitialized) return
        val mode = EmaAssistantMode.fromKey(
            getSharedPreferences("ema", MODE_PRIVATE).getString("assistant_mode", EmaAssistantMode.DEFAULT_KEY)
        )
        hubStatus.text = when {
            !mode.allowsAi ->
                "AI is switched off in the mode above. EMA works fully on-device."
            !CentralHubClient.isConfigured(this) ->
                "No Hub address is configured. EMA will answer from the device only."
            else ->
                "AI questions go to ${CentralHubClient.hubUrl(this)}. Phone actions are always performed on-device."
        }
    }

    private fun isVoiceInteractionServiceActive(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching { VoiceInteractionService.isActiveService(this, android.content.ComponentName(this, EmaVoiceInteractionService::class.java)) }.getOrDefault(false)
        } else false

    private fun roundedSurface(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), Color.rgb(226, 230, 240))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAssistantSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
    }

    private fun openNotificationListenerSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uri = Uri.parse("package:$packageName")
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)) }
                .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    private fun openAccessSettings() {
        openAssistantSettings()
    }

    private fun requestRequiredPermissions() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.READ_CONTACTS
        }
        if (checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.WRITE_CONTACTS
        }
        // Needed for the torch on devices whose camera stack enforces CAMERA for
        // setTorchMode. Optional in practice: EMA reports honestly without it.
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.CAMERA
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_CORE_PERMISSIONS)
        }
        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun requestAssistantRole() {
        val rm = roleManager()
        if (rm != null &&
            runCatching { rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false) &&
            !isAssistantRoleHeld()) {
            runCatching {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), REQUEST_ASSISTANT)
            }.onFailure {
                status.text = "Android did not open the assistant-role prompt. Opening the system assistant settings."
                openAssistantSettings()
            }
        } else if (isAssistantRoleHeld()) {
            status.text = "EMA is already the system assistant."
        } else {
            status.text = "This phone is not exposing the assistant role to EMA. Opening system assistant settings."
            openAssistantSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        wakeUiHandler.removeCallbacks(refreshWake)
        wakeUiHandler.post(refreshWake)
        if (::status.isInitialized) {
            status.text = when {
                !isAssistantRoleHeld() -> "EMA is ready. System assistant is not enabled yet."
                isVoiceInteractionServiceActive() -> "EMA is the active system assistant."
                else -> "EMA is selected as assistant; Android has not activated the voice service yet."
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleIncomingSharedAudio(intent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ASSISTANT) {
            if (resultCode == Activity.RESULT_OK && isAssistantRoleHeld()) {
                status.text = "EMA is now the system assistant."
            } else {
                status.text = "The assistant role was not granted. Use the system assistant settings to select EMA."
                openAssistantSettings()
            }
        } else if (requestCode == REQUEST_TRANSCRIBE_AUDIO && resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                startOfflineUriTranscription(uri)
            }
        }
    }

    private fun toggleFileTranscriptionPicker() {
        if (fileTranscriptionRunning) {
            cancelFileTranscription = true
            status.text = "Cancelling audio transcription…"
            return
        }
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        runCatching {
            startActivityForResult(picker, REQUEST_TRANSCRIBE_AUDIO)
        }.onFailure {
            status.text = "Could not open the audio file picker on this phone."
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingSharedAudio(src: Intent?) {
        if (src?.action != Intent.ACTION_SEND) return
        val mime = src.type.orEmpty().lowercase(Locale.ROOT)
        if (mime == "text/plain") {
            val shared = src.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(12_000).orEmpty()
            src.action = Intent.ACTION_MAIN
            if (shared.isNotBlank()) {
                // Treat shared content as data, never as an executable instruction.
                EmaDialogueState.recordDocument("shared text", shared)
                conversation.addAssistant("Shared text received. Say summarize it, word count, or ask document: <question>. Sensitive text is not retained.", EmaOutcomeStatus.SUCCESS)
            }
            return
        }
        if (!mime.startsWith("audio/")) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { src.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) }.getOrNull()
        } else {
            runCatching { src.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
        }
        if (uri != null) {
            // Clear action so configuration changes never re-trigger the same transcription.
            src.action = Intent.ACTION_MAIN
            startOfflineUriTranscription(uri)
        }
    }

    private fun startOfflineUriTranscription(uri: Uri) {
        if (fileTranscriptionRunning) {
            status.text = "Already transcribing an audio recording — tap Stop to cancel it first."
            return
        }
        val availability = EmaOfflineSpeechModel.checkAvailability(this, ignoreLanguage = true)
        if (!availability.available) {
            status.text = availability.reason
            return
        }
        speechSession?.cancel()
        speechSession = null
        resetVoiceButtons()
        runCatching { tts.stop() }

        fileTranscriptionRunning = true
        cancelFileTranscription = false
        if (::transcribeButton.isInitialized) transcribeButton.text = "Stop"
        if (::voiceLevelBar.isInitialized) {
            voiceLevelBar.progress = 2
            voiceLevelBar.visibility = View.VISIBLE
        }
        conversation.addUser("Transcribe selected audio recording offline")
        conversation.setState(EmaConversationView.State.WORKING, "offline transcription")
        status.text = "Decoding and transcribing audio offline on this phone…"

        Thread({
            val outcome = runCatching {
                EmaOfflineSpeechEngine.transcribeUri(
                    context = applicationContext,
                    uri = uri,
                    profile = EmaRecognitionProfile.DICTATION,
                    isCancelled = { cancelFileTranscription },
                    onProgress = { progress, preview ->
                        val pct = (progress * 100f).toInt().coerceIn(1, 99)
                        runOnUiThread {
                            if (!fileTranscriptionRunning) return@runOnUiThread
                            if (::voiceLevelBar.isInitialized) voiceLevelBar.progress = pct
                            conversation.setState(EmaConversationView.State.WORKING, "$pct% offline")
                            if (preview.isNotBlank()) {
                                val tail = if (preview.length > 96) "…" + preview.takeLast(96) else preview
                                status.text = "Transcribing ($pct%): “$tail”"
                            }
                        }
                    }
                )
            }
            val wasCancelled = cancelFileTranscription
            runOnUiThread {
                fileTranscriptionRunning = false
                cancelFileTranscription = false
                if (::transcribeButton.isInitialized) transcribeButton.text = "Audio"
                hideVoiceMeter()
                if (wasCancelled) {
                    conversation.setState(EmaConversationView.State.READY)
                    status.text = "Audio transcription cancelled."
                    return@runOnUiThread
                }
                outcome.onSuccess { result ->
                    if (result.text.isBlank()) {
                        conversation.addAssistant(
                            "I decoded that audio recording offline, but didn't detect any spoken words in it.",
                            EmaOutcomeStatus.FAILED
                        )
                        status.text = "No spoken words detected in recording."
                    } else {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText("EMA Offline Transcript", result.text)
                        )
                        val secs = (result.durationMs / 1000L).coerceAtLeast(1L)
                        val header = "Offline transcript (${secs}s · copied to clipboard):\n\n"
                        EmaDialogueState.recordDocument("audio transcript", result.text)
                        conversation.addAssistant(header + result.text, EmaOutcomeStatus.SUCCESS)
                        status.text = "Transcribed offline and copied to clipboard."
                    }
                }.onFailure { err ->
                    val detail = err.message ?: "unsupported audio format"
                    conversation.addAssistant(
                        "I couldn't transcribe that audio offline: $detail.",
                        EmaOutcomeStatus.FAILED
                    )
                    status.text = "Transcription failed: $detail"
                }
            }
        }, "ema-uri-transcribe").apply {
            isDaemon = true
            start()
        }
    }

    private fun toggleVoiceCapture(profile: EmaRecognitionProfile) {
        val current = speechSession
        if (current != null && current.isListening) {
            val sameButton = (profile == EmaRecognitionProfile.DICTATION && activeVoiceProfile == EmaRecognitionProfile.DICTATION) ||
                (profile != EmaRecognitionProfile.DICTATION && activeVoiceProfile != EmaRecognitionProfile.DICTATION)
            if (sameButton) {
                // Flush everything spoken so far rather than throwing it away.
                status.text = "Finishing what you said…"
                current.stopListening()
                return
            }
            current.cancel()
            speechSession = null
            resetVoiceButtons()
            hideVoiceMeter()
        }
        val effectiveProfile = if (profile == EmaRecognitionProfile.COMMAND && router.hasPendingQuestion()) {
            EmaRecognitionProfile.CONFIRMATION
        } else {
            profile
        }
        listenOnce(effectiveProfile)
    }

    private fun listenOnce(profile: EmaRecognitionProfile = EmaRecognitionProfile.COMMAND) {
        if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            status.text = "Unlock your phone before running commands."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            status.text = "Microphone permission is required for Voice."
            return
        }

        runCatching { tts.stop() }
        EmaWakeWordService.pauseForSpeech()
        speechSession?.cancel()

        activeVoiceProfile = profile
        activeInputRoute = ""
        smoothedVoiceLevel = 0f
        dictationPrefix = if (profile == EmaRecognitionProfile.DICTATION) {
            commandInput.text.toString().trim()
        } else {
            ""
        }
        updateVoiceButtonsListening(profile)

        val session = EmaSpeechRecognition.Session(this, object : EmaSpeechRecognition.SessionListener {
            override fun onPreparingOfflineModel() {
                conversation.setState(EmaConversationView.State.WORKING)
                status.text = "Preparing EMA's built-in offline speech engine…"
            }

            override fun onInputRouteChanged(route: String) {
                activeInputRoute = route
            }

            override fun onReadyForSpeech(kind: EmaRecognizerKind) {
                val routeNote = if (activeInputRoute.isNotBlank() && activeInputRoute != "built-in mic") {
                    " · $activeInputRoute"
                } else {
                    ""
                }
                val tag = (if (kind.isStrictlyOffline) "offline" else kind.shortLabel) + routeNote
                conversation.setState(EmaConversationView.State.LISTENING, tag)
                if (::voiceLevelBar.isInitialized) {
                    voiceLevelBar.progress = 4
                    voiceLevelBar.visibility = View.VISIBLE
                }
                status.text = when (profile) {
                    EmaRecognitionProfile.DICTATION ->
                        "Dictating ($tag) — speak punctuation or 'scratch that'; tap Done when finished."
                    EmaRecognitionProfile.CONFIRMATION ->
                        "Listening for your answer ($tag)…"
                    else ->
                        "Listening ($tag)…"
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!::voiceLevelBar.isInitialized || voiceLevelBar.visibility != View.VISIBLE) return
                val level = EmaSpeechRecognition.normalizeRmsLevel(rmsdB)
                smoothedVoiceLevel = if (level > smoothedVoiceLevel) {
                    0.45f * smoothedVoiceLevel + 0.55f * level
                } else {
                    0.75f * smoothedVoiceLevel + 0.25f * level
                }
                voiceLevelBar.progress = (smoothedVoiceLevel * 100f).toInt().coerceIn(3, 100)
            }

            override fun onBeginningOfSpeech(kind: EmaRecognizerKind) {
                conversation.setState(EmaConversationView.State.LISTENING)
            }

            override fun onPartialTranscript(text: String, kind: EmaRecognizerKind) {
                if (text.isBlank()) return
                if (profile == EmaRecognitionProfile.DICTATION) {
                    val combined = combineDictation(dictationPrefix, text)
                    commandInput.setText(combined)
                    commandInput.setSelection(combined.length)
                } else {
                    status.text = "“$text”"
                }
            }

            override fun onEndOfSpeech(kind: EmaRecognizerKind) {
                hideVoiceMeter()
                conversation.setState(EmaConversationView.State.UNDERSTANDING)
                status.text = "Processing…"
            }

            override fun onSwitchingBackend(from: EmaRecognizerKind, to: EmaRecognizerKind, errorCode: Int) {
                status.text = if (to == EmaRecognizerKind.EMA_OFFLINE) {
                    "Switching to EMA offline speech…"
                } else {
                    "Switching to ${to.shortLabel}…"
                }
            }

            override fun onResults(
                primary: String,
                alternatives: List<String>,
                confidenceScores: FloatArray,
                kind: EmaRecognizerKind
            ) {
                speechSession = null
                resetVoiceButtons()
                hideVoiceMeter()
                EmaWakeWordService.resumeAfterSession()
                if (profile == EmaRecognitionProfile.DICTATION) {
                    val combined = combineDictation(dictationPrefix, primary)
                    commandInput.setText(combined)
                    commandInput.setSelection(combined.length)
                    conversation.setState(EmaConversationView.State.READY)
                    status.text = if (kind.isStrictlyOffline) {
                        "Transcribed offline. Tap Send to run, Dictate to add more, or edit above."
                    } else {
                        "Transcribed. Tap Send to run, Dictate to add more, or edit above."
                    }
                } else {
                    // Reuse the activity's router so pending follow-up questions survive across turns.
                    runRequest(primary, listOf(primary) + alternatives)
                }
            }

            override fun onFailure(errorCode: Int, userMessage: String, lastKind: EmaRecognizerKind?) {
                speechSession = null
                resetVoiceButtons()
                hideVoiceMeter()
                conversation.setState(EmaConversationView.State.READY)
                status.text = userMessage
                EmaWakeWordService.resumeAfterSession()
            }
        })
        speechSession = session
        session.start(
            profile = profile,
            language = Locale.getDefault()
        )
    }

    private fun combineDictation(prefix: String, spoken: String): String {
        val clean = spoken.trim()
        if (prefix.isBlank()) return clean
        if (clean.isBlank()) return prefix
        val needsSpace = !prefix.endsWith(" ") && !prefix.endsWith("\n") && !clean.first().let { it in ",.;:!?" }
        return if (needsSpace) "$prefix $clean" else "$prefix$clean"
    }

    private fun updateVoiceButtonsListening(profile: EmaRecognitionProfile) {
        val isSpeak = profile != EmaRecognitionProfile.DICTATION
        if (::speakButton.isInitialized) {
            speakButton.text = if (isSpeak) "Done" else "Speak"
        }
        if (::dictateButton.isInitialized) {
            dictateButton.text = if (profile == EmaRecognitionProfile.DICTATION) "Done" else "Dictate"
        }
    }

    private fun resetVoiceButtons() {
        if (::speakButton.isInitialized) speakButton.text = "Speak"
        if (::dictateButton.isInitialized) dictateButton.text = "Dictate"
    }

    private fun hideVoiceMeter() {
        smoothedVoiceLevel = 0f
        if (::voiceLevelBar.isInitialized) {
            voiceLevelBar.progress = 0
            voiceLevelBar.visibility = View.GONE
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WAKE_MIC || requestCode == REQUEST_WAKE_NOTIFY) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startWakeListening()
            else wakeStatus.text = "Listening is stopped. Microphone and notification permissions are required for visible screen-off listening."
        }
        if (requestCode == REQUEST_CORE_PERMISSIONS || requestCode == REQUEST_MIC) {
            val allCoreGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
            status.text = when {
                !allCoreGranted -> "EMA still needs microphone and Contacts permissions for voice and contact actions."
                isAssistantRoleHeld() -> "EMA is ready for voice assistant invocation."
                else -> "Permissions ready. Set EMA as the system assistant to invoke it from anywhere."
            }
        }
    }

    override fun onInit(code: Int) {
        if (code == TextToSpeech.SUCCESS) {
            EmaVoiceSetup.apply(this, tts)
            ttsReady = true
        }
        runOnUiThread { refreshVoiceUi() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        EmaOfflineSpeechModel.onTrimMemory(level)
    }

    override fun onDestroy() {
        cancelFileTranscription = true
        fileTranscriptionRunning = false
        speechSession?.cancel()
        speechSession = null
        wakeUiHandler.removeCallbacksAndMessages(null)
        EmaWakeWordService.resumeAfterSession()
        tts.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_WAKE_MIC = 31
        private const val REQUEST_WAKE_NOTIFY = 32
        private const val REQUEST_MIC = 10
        private const val REQUEST_CORE_PERMISSIONS = 12
        private const val REQUEST_NOTIFICATIONS = 11
        private const val REQUEST_ASSISTANT = 20
        private const val REQUEST_TRANSCRIBE_AUDIO = 25
    }
}
