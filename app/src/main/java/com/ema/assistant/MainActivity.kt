package com.ema.assistant

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        requestRequiredPermissions()
        router = EmaCommandRouter(this) { tts }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "EMA"
            textSize = 32f
        })

        root.addView(TextView(this).apply {
            text = "Digital assistant • Type by default"
            textSize = 16f
        })

        commandInput = EditText(this).apply {
            hint = "Tell EMA what to do…"
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
            text = "Ask"
            setOnClickListener { executeTypedCommand() }
        })
        actions.addView(Button(this).apply {
            text = "Voice"
            setOnClickListener { listenOnce() }
        })
        root.addView(actions)

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

        // The assistant brain is the primary control on this screen: it makes the
        // "native first, AI second" guarantee visible and switchable.
        root.addView(TextView(this).apply {
            text = "Assistant brain"
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

        root.addView(Button(this).apply {
            text = "Set up Hey EMA (local wake word)"
            setOnClickListener { startActivity(Intent(this@MainActivity, EmaWakeWordSetupActivity::class.java)) }
        })

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

        status = TextView(this).apply {
            text = "EMA is ready."
            textSize = 18f
            setPadding(0, dp(14), 0, dp(6))
        }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun executeTypedCommand() {
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
            runCatching { VoiceInteractionService.isActiveService(this, componentName) }.getOrDefault(false)
        } else false

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
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            status.text = "Microphone permission is required for Voice."
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Speech recognition is unavailable."
            return
        }

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
                }

                override fun onError(e: Int) {
                    status.text = "I couldn't hear that. Try again."
                    recognizer?.destroy()
                    recognizer = null
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
        tts.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_MIC = 10
        private const val REQUEST_CORE_PERMISSIONS = 12
        private const val REQUEST_NOTIFICATIONS = 11
        private const val REQUEST_ASSISTANT = 20
    }
}
