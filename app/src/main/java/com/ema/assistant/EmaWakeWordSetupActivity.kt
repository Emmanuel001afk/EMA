package com.ema.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Guided local enrollment and explicit start/stop controls for Hey EMA. */
class EmaWakeWordSetupActivity : Activity() {
    private lateinit var status: TextView
    private val samples = ArrayList<ShortArray>()
    private val sampleRate = 16_000
    private val sampleCount = 40_000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 32)
        }
        root.addView(TextView(this).apply {
            text = "EMA • Local wake phrase"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "Enroll “Hey EMA” three times in a quiet room. Samples stay on this device. After enrollment, start local listening; Android shows a persistent microphone notification."
            textSize = 16f
            setPadding(0, 18, 0, 20)
        })
        status = TextView(this).apply { text = "Enrollment: 0 of 3 samples"; textSize = 16f }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Record sample"
            setOnClickListener { recordSample() }
        })
        root.addView(Button(this).apply {
            text = "Save enrollment"
            setOnClickListener { saveEnrollment() }
        })
        root.addView(Button(this).apply {
            text = "Start Hey EMA listening"
            setOnClickListener { startListening() }
        })
        root.addView(Button(this).apply {
            text = "Stop listening"
            setOnClickListener {
                startService(Intent(this@EmaWakeWordSetupActivity, EmaWakeWordService::class.java).setAction(EmaWakeWordService.ACTION_STOP))
                status.text = "Stopped. You can still open EMA normally."
            }
        })
        root.addView(Button(this).apply {
            text = "Refresh status"
            setOnClickListener { showStatus() }
        })
        setContentView(root, ViewGroup.LayoutParams(-1, -1))
        showStatus()
    }

    private fun recordSample() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            status.text = "Allow microphone access, then tap Record sample again."
            return
        }
        status.text = "Recording for 2.5 seconds. Say “Hey EMA” clearly now."
        Thread({
            var recorder: AudioRecord? = null
            try {
                val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuffer <= 0) throw IllegalStateException("AudioRecord unavailable")
                recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, sampleCount * 2))
                if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microphone unavailable")
                val pcm = ShortArray(sampleCount)
                recorder.startRecording()
                var offset = 0
                while (offset < pcm.size) {
                    val read = recorder.read(pcm, offset, pcm.size - offset)
                    if (read <= 0) throw IllegalStateException("Microphone read failed")
                    offset += read
                }
                recorder.stop()
                val template = EmaWakeWordEngine().enroll(pcm, sampleRate)
                    ?: throw IllegalStateException("Sample was too quiet or invalid; try again.")
                runOnUiThread {
                    samples.add(pcm)
                    status.text = "Captured sample ${samples.size}/3 (template ${template.frameCount} frames)."
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Recording failed: ${e.message ?: "microphone unavailable"}" }
            } finally {
                try { recorder?.release() } catch (_: Exception) {}
            }
        }, "EMA-wake-enrollment").start()
    }

    private fun saveEnrollment() {
        if (samples.size < 3) {
            status.text = "Record three samples before saving."
            return
        }
        val encoded = samples.map { pcm ->
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { bytes.putShort(it) }
            Base64.encodeToString(bytes.array(), Base64.NO_WRAP)
        }.toSet()
        getSharedPreferences(EmaWakeWordService.PREFS, MODE_PRIVATE).edit()
            .putStringSet(EmaWakeWordService.KEY_TEMPLATES, encoded)
            .putString(EmaWakeWordService.KEY_STATUS, "enrolled")
            .apply()
        status.text = "Saved ${encoded.size} local samples. Tap Start Hey EMA listening."
    }

    private fun startListening() {
        val templates = getSharedPreferences(EmaWakeWordService.PREFS, MODE_PRIVATE)
            .getStringSet(EmaWakeWordService.KEY_TEMPLATES, emptySet()).orEmpty()
        if (templates.size < 3) {
            status.text = "Enroll and save three samples first."
            return
        }
        try {
            val intent = Intent(this, EmaWakeWordService::class.java).setAction(EmaWakeWordService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            status.text = "Starting local listener. Keep the persistent notification visible."
        } catch (e: Exception) {
            status.text = "Could not start listening: ${e.message ?: "Android blocked microphone service"}"
        }
    }

    private fun showStatus() {
        val prefs = getSharedPreferences(EmaWakeWordService.PREFS, MODE_PRIVATE)
        status.text = "Status: ${prefs.getString(EmaWakeWordService.KEY_STATUS, "not configured")} • samples: ${prefs.getStringSet(EmaWakeWordService.KEY_TEMPLATES, emptySet()).orEmpty().size}"
    }

    companion object { private const val REQUEST_MIC = 481 }
}
