package com.ema.assistant

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional, private Vosk command recognizer. Android's SpeechRecognizer remains
 * EMA's first choice; this engine is invoked only when the owner enables the
 * local fallback and a system recognizer is unavailable or fails.
 *
 * Uses the already-bundled English wake model, prepares it in private storage,
 * and keeps PCM only in the current read buffer. It never writes audio or
 * transcripts to disk. Low-memory devices and an active background wake service
 * decline this backend rather than loading a second large model.
 */
internal class EmaOfflineCommandRecognition(
    context: Context,
    private val callbacks: android.speech.RecognitionService.Callback
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var stopAfterCurrentRead = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        cancelled.set(false)
        worker = Thread(::recognize, "ema-offline-command-stt").also { it.start() }
    }

    fun stopListening() {
        stopAfterCurrentRead = true
        runCatching { recorder?.stop() }
    }

    fun cancel() {
        cancelled.set(true)
        running.set(false)
        stopAfterCurrentRead = true
        runCatching { recorder?.stop() }
        worker?.interrupt()
    }

    private fun recognize() {
        try {
            val modelDir = EmaWakeModel.prepare(appContext)
            Model(modelDir.path).use { model ->
                Recognizer(model, EmaVoiceFeatures.SAMPLE_RATE.toFloat()).use { decoder ->
                    val minSize = AudioRecord.getMinBufferSize(
                        EmaVoiceFeatures.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    check(minSize > 0) { "Microphone audio format is unavailable" }
                    val audio = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        EmaVoiceFeatures.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minSize * 2, 8192)
                    )
                    recorder = audio
                    check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable" }
                    audio.startRecording()
                    dispatch { callbacks.readyForSpeech(Bundle()) }
                    val bytes = ByteArray(4096)
                    val started = android.os.SystemClock.elapsedRealtime()
                    var speechStarted = false
                    var lastPartial = ""
                    while (running.get() && !stopAfterCurrentRead &&
                        android.os.SystemClock.elapsedRealtime() - started < MAX_LISTEN_MS) {
                        val read = audio.read(bytes, 0, bytes.size)
                        if (read <= 0) {
                            if (stopAfterCurrentRead || !running.get()) break
                            error("Microphone read failed ($read)")
                        }
                        if (!speechStarted && isVoice(bytes, read)) {
                            speechStarted = true
                            dispatch { callbacks.beginningOfSpeech() }
                        }
                        if (decoder.acceptWaveForm(bytes, read)) {
                            val recognized = text(decoder.result)
                            if (recognized.isNotBlank()) {
                                deliverResult(recognized)
                                return
                            }
                        } else if (speechStarted) {
                            val partial = text(decoder.partialResult)
                            if (partial.isNotBlank() && partial != lastPartial) {
                                lastPartial = partial
                                val bundle = Bundle().apply {
                                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(partial))
                                }
                                dispatch { callbacks.partialResults(bundle) }
                            }
                        }
                    }
                    val finalText = runCatching { text(decoder.finalResult) }.getOrDefault("")
                    if (finalText.isNotBlank()) deliverResult(finalText)
                    else if (!speechStarted) dispatch { callbacks.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
                    else dispatch { callbacks.error(SpeechRecognizer.ERROR_NO_MATCH) }
                }
            }
        } catch (error: Throwable) {
            if (running.get()) {
                android.util.Log.w("EMA", "Optional offline recognition failed", error)
                dispatch { callbacks.error(SpeechRecognizer.ERROR_CLIENT) }
            }
        } finally {
            running.set(false)
            runCatching { recorder?.release() }
            recorder = null
            worker = null
        }
    }

    private fun deliverResult(text: String) {
        val results = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
            putFloatArray(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, floatArrayOf(0.5f))
        }
        dispatch {
            callbacks.endOfSpeech()
            callbacks.results(results)
        }
    }

    private fun dispatch(action: () -> Unit) {
        if (!cancelled.get()) main.post { runCatching(action) }
    }

    /** Small RMS gate prevents room/sensor noise from triggering Vosk activity. */
    private fun isVoice(buffer: ByteArray, count: Int): Boolean {
        val samples = count / 2
        if (samples == 0) return false
        var energy = 0.0
        var i = 0
        while (i + 1 < count) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)).toShort().toDouble()
            energy += sample * sample
            i += 2
        }
        return kotlin.math.sqrt(energy / samples) > MIN_RMS
    }

    private fun text(json: String): String = runCatching {
        JSONObject(json).optString("text").trim()
    }.getOrDefault("")


    companion object {
        private const val MAX_LISTEN_MS = 20_000L
        private const val MIN_RMS = 180.0
    }
}
