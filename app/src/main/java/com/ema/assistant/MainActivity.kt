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
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
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
    private var recognizer: SpeechRecognizer? = null
    private lateinit var router: EmaCommandRouter
    private lateinit var modeExplanation: TextView
    private lateinit var hubStatus: TextView
    private lateinit var hubAddress: EditText
    private lateinit var wakeStatus: TextView
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
            wakeUiHandler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        requestRequiredPermissions()
        router = EmaCommandRouter(this) { tts }

        window.statusBarColor = Color.rgb(244, 246, 251)
        window.navigationBarColor = Color.rgb(244, 246, 251)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        var root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "EMA"
            textSize = 36f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(27, 34, 56))
        })

        root.addView(TextView(this).apply {
            text = "Your phone. One request."
            textSize = 16f
        })

        root.addView(TextView(this).apply {
            text = "Open apps, send messages, play music and plan your day. Native actions first."
            textSize = 15f
            setTextColor(Color.rgb(82, 91, 114))
            setPadding(0, dp(12), 0, dp(24))
        })
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
            text = "Run request"
            setOnClickListener { executeTypedCommand() }
        })
        actions.addView(Button(this).apply {
            text = "Voice"
            setOnClickListener { listenOnce() }
        })
        root.addView(actions)

        status = TextView(this).apply {
            text = "Ready when you are."
            textSize = 16f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(Color.rgb(56, 68, 99))
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedSurface(Color.WHITE)
        }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "TRY A REQUEST"
            textSize = 12f
            letterSpacing = 0.12f
            setPadding(0, dp(24), 0, dp(8))
        })
        listOf("Open Spotify and play Arcade", "Message Daniel on WhatsApp saying I am coming home",
            "Search for cooking ideas on YouTube", "Remind me to drink water in 30 minutes").forEach { example ->
            root.addView(Button(this).apply {
                text = example
                isAllCaps = false
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setOnClickListener { commandInput.setText(example); commandInput.requestFocus() }
            })
        }
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
            text = "While the assistant is visible, say Hey EMA followed by your request, or say Hey EMA and then one request. This is not always-on background wake-up. Speech recognition may use your phone’s speech provider."
            textSize = 13f
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

        root.addView(TextView(this).apply {
            text = "Voice"
            textSize = 16f
            setPadding(0, 24, 0, 4)
        })
        val voiceSpinner = Spinner(this)
        root.addView(voiceSpinner)
        tts.setOnUtteranceProgressListener(null)
        if (tts.engines.isNotEmpty()) {
            val voices = tts.voices.orEmpty()
                .filter { it.locale.language == Locale.getDefault().language }
                .sortedBy { it.name }
                .take(30)
            val labels = if (voices.isEmpty()) listOf("Default voice") else voices.map { it.name }
            voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            voiceSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    voices.getOrNull(position)?.let {
                        tts.voice = it
                        getSharedPreferences("ema", MODE_PRIVATE).edit().putString("voice_name", it.name).apply()
                    }
                }
            })
        }
        root.addView(TextView(this).apply {
            text = "System assistant: " + when {
                !isAssistantRoleHeld() -> "not enabled"
                isVoiceInteractionServiceActive() -> "enabled and active"
                else -> "enabled; service not active yet"
            }
            textSize = 14f
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(244, 246, 251))
            isFillViewport = true
            addView(home)
        })
        if (intent.getBooleanExtra("wake_capture", false)) {
            commandInput.postDelayed({ listenOnce() }, 400)
            intent.removeExtra("wake_capture")
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
        status.text = "Working…"
        router.execute(command) { result ->
            runOnUiThread { status.text = result }
        }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ASSISTANT) {
            if (resultCode == Activity.RESULT_OK && isAssistantRoleHeld()) {
                status.text = "EMA is now the system assistant."
            } else {
                status.text = "The assistant role was not granted. Use the system assistant settings to select EMA."
                openAssistantSettings()
            }
        }
    }

    private fun listenOnce() {
        if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            status.text = "Unlock your phone before running commands."
            return
        }
        EmaWakeWordService.pauseForSpeech()
        if (EmaWakeWordService.isCapturing()) {
            commandInput.postDelayed({ if (!isFinishing && !isDestroyed) listenOnce() }, 100)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            status.text = "Microphone permission is required for Voice."
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Speech recognition is unavailable."
            return
        }

        EmaWakeWordService.pauseForSpeech()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    val command = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (command.isNullOrBlank()) {
                        status.text = "I couldn't hear that. Try again."
                    } else {
                        commandInput.setText(command)
                        status.text = "Working…"
                        // Must reuse the activity's router: a fresh instance would
                        // drop any pending follow-up question ("What time should I
                        // remind you?") that the previous turn asked.
                        router.execute(command) { result ->
                            runOnUiThread { status.text = result }
                        }
                    }
                    recognizer?.destroy()
                    recognizer = null
                    EmaWakeWordService.resumeAfterSession()
                }

                override fun onError(e: Int) {
                    status.text = "I couldn't hear that. Try again."
                    recognizer?.destroy()
                    recognizer = null
                    EmaWakeWordService.resumeAfterSession()
                }

                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
        }

        recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        })
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
            tts.language = Locale.getDefault()
            val saved = getSharedPreferences("ema", MODE_PRIVATE).getString("voice_name", null)
            tts.voices?.firstOrNull { it.name == saved }?.let { tts.voice = it }
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
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
    }
}
