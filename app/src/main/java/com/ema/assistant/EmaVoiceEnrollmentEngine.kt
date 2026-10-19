package com.ema.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock

/**
 * Records the owner's own "Hey EMA" for the personal check.
 *
 * The owner says "Hey EMA" a number of times. Each round records until the
 * sound has stopped again, so the template holds the words and not the
 * silence around them. The recording happens on its own thread; the caller
 * is told how far the enrollment has come.
 *
 * Nothing is stored until the enrollment is finished, and the samples never
 * leave the phone.
 */
object EmaVoiceEnrollmentEngine {

    private const val SAMPLE_RATE = 16000

    /** How long one round may last at most. */
    private const val ROUND_LIMIT_MS = 4000

    /** How much sound a round needs before it is accepted. */
    private const val MIN_VOICED_MS = 300

    /** How much silence ends a round. */
    private const val SILENCE_END_MS = 300

    /** The gap between two rounds. */
    private const val GAP_MS = 350

    /** How long the spoken prompt is given before the microphone starts. */
    private const val PROMPT_DELAY_MS = 1800L

    @Volatile private var running = false
    private var worker: Thread? = null

    /** True while an enrollment is running. */
    fun isRunning(): Boolean = running

    /**
     * Start the enrollment.
     *
     * [onPrompt] is called with the sentence to show or speak before each
     * round, so the owner knows when to say "Hey EMA". [onDone] is called with
     * true when a template was stored.
     */
    fun start(
        context: Context,
        onPrompt: (String) -> Unit,
        onDone: (Boolean) -> Unit
    ) {
        if (running) {
            onDone(false)
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onDone(false)
            return
        }
        running = true
        val app = context.applicationContext
        worker = Thread({
            val enrollment = EmaVoiceEnrollment()
            var stored = false
            try {
                for (round in 1..EmaVoiceEnrollment.REQUIRED) {
                    if (!running) break
                    onPrompt("Say \"Hey EMA\", ${round} of ${EmaVoiceEnrollment.REQUIRED}.")
                    // Give the spoken prompt time to finish before the
                    // microphone starts, so the prompt itself is not learned
                    // as the owner's voice.
                    Thread.sleep(PROMPT_DELAY_MS)
                    if (!running) break
                    val pcm = recordRound()
                    if (pcm != null) {
                        enrollment.add(EmaVoiceFeatures.mfcc(pcm))
                    }
                    if (!running) break
                    Thread.sleep(GAP_MS.toLong())
                }
                if (running && enrollment.isComplete()) {
                    // Every sample is kept, not only their average: the
                    // threshold is measured from how far the owner's own
                    // samples are from each other.
                    val now = System.currentTimeMillis()
                    val samples = enrollment.samples()
                    samples.forEachIndexed { index, frames ->
                        EmaVoiceTemplateStore.add(
                            app,
                            EmaVoiceVerifier.Template(
                                id = "enrolled-$now-$index",
                                kind = EmaVoiceVerifier.Template.Kind.POSITIVE,
                                label = "enrolled",
                                frames = frames,
                                addedAt = now + index
                            )
                        )
                    }
                    stored = samples.isNotEmpty()
                }
            } catch (e: Throwable) {
                android.util.Log.e("EMA", "Voice enrollment failed", e)
            } finally {
                running = false
                onDone(stored)
            }
        }, "ema-voice-enrollment").apply { start() }
    }

    /** Stop the enrollment. */
    fun cancel() {
        running = false
    }

    /**
     * Record one "Hey EMA".
     *
     * The recording stops when the sound has been quiet for a moment, so the
     * template is the words and not the room around them. Null when nothing
     * was heard.
     */
    private fun recordRound(): ShortArray? {
        val size = maxOf(4096, AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { audio.release() }
            return null
        }
        val samples = ArrayList<Short>()
        val block = ShortArray(320) // 20 ms
        val startedAt = SystemClock.elapsedRealtime()
        var voicedSamples = 0
        var silenceSamples = 0
        var firstVoiced = -1
        var lastVoiced = -1
        var noiseFloor = 300.0
        try {
            audio.startRecording()
            while (running) {
                val read = audio.read(block, 0, block.size)
                if (read <= 0) break
                var energy = 0.0
                for (i in 0 until read) energy += block[i].toDouble() * block[i].toDouble()
                val rms = Math.sqrt(energy / read)
                val voiced = rms > maxOf(900.0, noiseFloor * 3.0)
                if (voiced) {
                    noiseFloor = noiseFloor * 0.98 + rms * 0.02
                    if (firstVoiced < 0) firstVoiced = samples.size
                    lastVoiced = samples.size
                    voicedSamples += read
                    silenceSamples = 0
                } else {
                    silenceSamples += read
                    if (voicedSamples > 0) noiseFloor = noiseFloor * 0.995 + rms * 0.005
                }
                for (i in 0 until read) samples += block[i]
                if (voicedSamples >= MIN_VOICED_MS * SAMPLE_RATE / 1000 && silenceSamples >= SILENCE_END_MS * SAMPLE_RATE / 1000) break
                if (SystemClock.elapsedRealtime() - startedAt > ROUND_LIMIT_MS) break
            }
        } catch (e: Throwable) {
            android.util.Log.e("EMA", "Voice enrollment recording failed", e)
        } finally {
            runCatching { audio.stop() }
            runCatching { audio.release() }
        }
        if (firstVoiced < 0 || lastVoiced <= firstVoiced) return null
        val from = firstVoiced
        val until = minOf(lastVoiced + SAMPLE_RATE / 10, samples.size)
        val out = ShortArray(until - from)
        for (i in out.indices) out[i] = samples[from + i]
        return out
    }
}
