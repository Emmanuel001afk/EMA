package com.ema.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Explicitly started, user-visible microphone service for local wake phrase detection.
 * It never uploads audio. Android/OEM restrictions can still stop background capture.
 */
class EmaWakeWordService : Service() {
    private val lock = Any()
    @Volatile private var running = false
    private var worker: Thread? = null
    private val engine = EmaWakeWordEngine()
    private val sampleRate = 16_000
    private val frameSamples = 40_000 // 2.5 s rolling window
    private val threshold = 0.72f // provisional; calibrate on real devices before production use

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopDetection()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) startDetection()
        return START_NOT_STICKY
    }

    private fun startDetection() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val pcmTemplates = prefs.getStringSet(KEY_TEMPLATES, emptySet()).orEmpty()
            .mapNotNull(::decodePcm)
            .mapNotNull { engine.enroll(it, sampleRate) }
        if (pcmTemplates.size < 3) {
            prefs.edit().putString(KEY_STATUS, "needs_enrollment").apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        running = true
        prefs.edit().putString(KEY_STATUS, "listening").apply()
        worker = Thread({
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                setStatus("microphone_unavailable")
                stopDetection()
                stopSelf()
                return@Thread
            }
            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuffer, sampleRate)
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    setStatus("microphone_unavailable")
                    return@Thread
                }
                val chunk = ShortArray(8_000)
                val rolling = ShortArray(frameSamples)
                var filled = 0
                recorder.startRecording()
                while (running) {
                    val count = recorder.read(chunk, 0, chunk.size)
                    if (count <= 0) continue
                    if (count >= rolling.size) {
                        System.arraycopy(chunk, count - rolling.size, rolling, 0, rolling.size)
                        filled = rolling.size
                    } else {
                        val keep = minOf(filled, rolling.size - count)
                        if (keep > 0) System.arraycopy(rolling, filled - keep, rolling, 0, keep)
                        System.arraycopy(chunk, 0, rolling, keep, count)
                        filled = keep + count
                    }
                    if (filled == rolling.size) {
                        val window = rolling.copyOf()
                        val best = pcmTemplates.minOfOrNull { engine.compare(window, it, threshold).distance }
                        if (best != null && best <= threshold && System.currentTimeMillis() - lastTrigger > 8_000) {
                            lastTrigger = System.currentTimeMillis()
                            setStatus("wake_detected")
                            sendBroadcast(Intent(ACTION_WAKE_DETECTED).setPackage(packageName))
                            // Clear buffer to reduce repeated matches against the same utterance.
                            rolling.fill(0)
                            filled = 0
                        }
                    }
                }
            } catch (_: SecurityException) {
                setStatus("microphone_permission_denied")
            } catch (_: IllegalStateException) {
                setStatus("microphone_interrupted")
            } finally {
                try { recorder?.stop() } catch (_: Exception) {}
                recorder?.release()
                if (running) setStatus("stopped")
                running = false
            }
        }, "EMA-local-wake-word").apply { isDaemon = true; start() }
    }

    private fun stopDetection() {
        running = false
        worker?.interrupt()
        worker = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_STATUS, "stopped").apply()
    }

    private fun setStatus(value: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_STATUS, value).apply()
    }

    private fun decodePcm(encoded: String): ShortArray? = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        if (bytes.size < 1_000 || bytes.size % 2 != 0) null else {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            ShortArray(bytes.size / 2) { buffer.short }
        }
    } catch (_: Exception) { null }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "EMA wake-word listening", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Visible notification while EMA listens for its local wake phrase" })
        }
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("EMA wake-word detection")
            .setContentText("Listening locally for Hey EMA • tap Stop in EMA to disable")
            .setOngoing(true).build()
    }

    override fun onDestroy() {
        stopDetection()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.ema.assistant.wake.START"
        const val ACTION_STOP = "com.ema.assistant.wake.STOP"
        const val ACTION_WAKE_DETECTED = "com.ema.assistant.wake.DETECTED"
        const val PREFS = "ema_wake_word"
        const val KEY_TEMPLATES = "pcm_templates"
        const val KEY_STATUS = "status"
        const val CHANNEL_ID = "ema_wake_word"
        const val NOTIFICATION_ID = 4702
        private var lastTrigger = 0L
    }
}
