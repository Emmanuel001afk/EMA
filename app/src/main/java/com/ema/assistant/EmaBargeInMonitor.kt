package com.ema.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-cost voice-activity and enrolled-speaker monitor used only during TTS.
 * It retains at most 1.2 seconds of PCM in memory, and runs the personal voice
 * verifier only after sustained speech-like energy. The listener is absent when
 * the owner has not enrolled a voice or microphone access is unavailable.
 */
internal class EmaBargeInMonitor(
    context: Context,
    private val onOwnerVoice: () -> Unit
) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var worker: Thread? = null

    fun start() {
        if (running.get() || appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!runCatching { EmaVoiceTemplateStore.isEnrolled(appContext) }.getOrDefault(false)) return
        running.set(true)
        worker = Thread(::listen, "ema-barge-in-vad").also { it.start() }
    }

    fun stop() {
        running.set(false)
        runCatching { recorder?.stop() }
        worker?.interrupt()
    }

    private fun listen() {
        val audioFrames = ArrayDeque<ShortArray>()
        var retained = 0
        var voicedSamples = 0
        var lastCompare = 0L
        try {
            val rate = EmaVoiceFeatures.SAMPLE_RATE
            val minSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minSize <= 0) return
            val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minSize * 2, 4096))
            recorder = audio
            if (audio.state != AudioRecord.STATE_INITIALIZED) return
            audio.startRecording()
            val bytes = ByteArray(640) // 20 ms PCM16 mono at 16 kHz
            while (running.get()) {
                val count = audio.read(bytes, 0, bytes.size)
                if (count <= 0) {
                    if (!running.get()) break
                    continue
                }
                val samples = ShortArray(count / 2)
                var energy = 0.0
                for (i in samples.indices) {
                    val value = ((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xff)).toShort()
                    samples[i] = value
                    energy += value.toDouble() * value.toDouble()
                }
                val rms = if (samples.isEmpty()) 0.0 else kotlin.math.sqrt(energy / samples.size)
                if (rms >= SPEECH_RMS) {
                    audioFrames.addLast(samples)
                    retained += samples.size
                    voicedSamples += samples.size
                } else if (voicedSamples > 0) {
                    // Keep a short trailing gap for natural phrase endings.
                    audioFrames.addLast(samples)
                    retained += samples.size
                }
                val maxSamples = rate * WINDOW_MS / 1000
                while (retained > maxSamples && audioFrames.isNotEmpty()) retained -= audioFrames.removeFirst().size
                if (rms < SPEECH_RMS) voicedSamples = (voicedSamples - samples.size).coerceAtLeast(0)
                val now = android.os.SystemClock.elapsedRealtime()
                if (voicedSamples >= rate * MIN_VOICED_MS / 1000 && now - lastCompare >= VERIFY_INTERVAL_MS) {
                    lastCompare = now
                    val pcm = ShortArray(retained)
                    var at = 0
                    audioFrames.forEach { frame ->
                        val take = minOf(frame.size, pcm.size - at)
                        if (take > 0) { frame.copyInto(pcm, at, 0, take); at += take }
                    }
                    val features = EmaVoiceFeatures.mfcc(pcm)
                    val accepted = runCatching { EmaVoiceTemplateStore.verifier(appContext).verdict(features).accepted }
                        .getOrDefault(false)
                    if (accepted && running.compareAndSet(true, false)) {
                        runCatching(onOwnerVoice)
                        break
                    }
                }
                if (voicedSamples == 0) {
                    audioFrames.clear()
                    retained = 0
                }
            }
        } catch (_: SecurityException) {
            // Permission was revoked while speaking: barge-in simply degrades off.
        } catch (error: Throwable) {
            if (running.get()) android.util.Log.w("EMA", "Barge-in monitor stopped safely", error)
        } finally {
            running.set(false)
            runCatching { recorder?.release() }
            recorder = null
            worker = null
        }
    }

    companion object {
        private const val SPEECH_RMS = 260.0
        private const val WINDOW_MS = 1_200
        private const val MIN_VOICED_MS = 360
        private const val VERIFY_INTERVAL_MS = 450L
    }
}
