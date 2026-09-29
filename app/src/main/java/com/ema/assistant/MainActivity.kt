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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        requestNotificationPermission()
        router = EmaCommandRouter(this, tts)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 32)
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
            text = "System assistant: ${if (isAssistantRoleHeld()) "enabled" else "not enabled"}"
            textSize = 14f
        })

        status = TextView(this).apply {
            text = "EMA is ready."
            textSize = 18f
            setPadding(0, 28, 0, 12)
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

    private fun isAssistantRoleHeld(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    } else false

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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun requestAssistantRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
            !rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
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
            status.text = if (isAssistantRoleHeld()) "EMA is the system assistant." else "EMA is ready. System assistant is not enabled yet."
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
                        EmaCommandRouter(this@MainActivity, tts).execute(command) { result ->
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
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            status.text = "Microphone ready. Press Voice again to speak."
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
        private const val REQUEST_NOTIFICATIONS = 11
        private const val REQUEST_ASSISTANT = 20
    }
}
