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
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EMA's own command recogniser: Vosk, the bundled English model, the phone's
 * microphone, and nothing else. No network, no Google service, no API key.
 *
 * Used two ways:
 *  - as the fallback when Android's recogniser fails, and
 *  - as the *only* engine when the owner selects [EmaSpeechEngine.LOCAL_ONLY],
 *    which is what makes the assistant's microphone fully offline.
 *
 * The decoder runs under a command grammar ([EmaCommandVocabulary]) so a small
 * model is not asked to invent free-form prose, and per-word confidences are
 * reported instead of a hardcoded placeholder.
 *
 * The model itself is shared with the screen-off wake service through
 * [EmaLocalModel], so both can be alive at once without a second copy in RAM.
 * Only one of them ever captures the microphone, because capture is paused on
 * handoff. PCM stays in the current read buffer; nothing is written to disk.
 */
internal class EmaOfflineCommandRecognition(
    context: Context,
    private val callbacks: android.speech.RecognitionService.Callback,
    /** Learned triggers and app labels, so the decoder may spell them. */
    private val learnedVocabulary: List<String> = emptyList()
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
            val model = EmaLocalModel.acquire(appContext)
            try {
                // A constrained decode is only safe with words this model knows;
                // without a readable word list the grammar is dropped and the
                // decoder runs free-form, exactly as before.
                val grammar = EmaCommandVocabulary.grammarFor(
                    EmaLocalModel.vocabulary(appContext),
                    learnedVocabulary
                )
                val decoder = if (grammar == null) {
                    Recognizer(model, EmaVoiceFeatures.SAMPLE_RATE.toFloat())
                } else {
                    Recognizer(model, EmaVoiceFeatures.SAMPLE_RATE.toFloat(), grammar)
                }
                decoder.use { decoder ->
                    // Per-word confidences are off by default; without them the
                    // result carries no honest score at all.
                    runCatching { decoder.setWords(true) }
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
                            val json = decoder.result
                            val recognized = text(json)
                            if (recognized.isNotBlank()) {
                                deliverResult(recognized, json = json)
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
                    val finalJson = runCatching { decoder.finalResult }.getOrDefault("")
                    val finalText = text(finalJson)
                    if (finalText.isNotBlank()) deliverResult(finalText, json = finalJson)
                    else if (!speechStarted) dispatch { callbacks.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
                    else dispatch { callbacks.error(SpeechRecognizer.ERROR_NO_MATCH) }
                }
            } finally {
                EmaLocalModel.release()
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

    /**
     * Deliver one hypothesis with the confidence the decoder actually measured.
     *
     * A hardcoded score would make EMA unable to tell "call Emma" from a guess,
     * so a result whose weakest word is below [EmaDecodeConfidence.MIN_USABLE]
     * is reported as no-match instead of as a command.
     */
    private fun deliverResult(text: String, json: String) {
        val confidence = EmaDecodeConfidence.score(EmaDecodeConfidence.weakest(json))
        if (confidence < EmaDecodeConfidence.MIN_USABLE) {
            dispatch { callbacks.error(SpeechRecognizer.ERROR_NO_MATCH) }
            return
        }
        val results = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
            putFloatArray(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, floatArrayOf(confidence))
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
