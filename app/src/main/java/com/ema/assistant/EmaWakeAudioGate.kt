package com.ema.assistant

import kotlin.math.max
import kotlin.math.sqrt

/** Cheap 16 kHz PCM16 gate before decoding, not a wake-word detector itself.
 * Keeps pre-roll and trailing silence so the start/end of "Hey EMA" survive.
 * No microphone permission, Android API, network or inference is needed here.
 */
class EmaWakeAudioGate(
    private val minimumRms: Double = 140.0,
    private val tailSamples: Int = 19_200, // 1.2 seconds at 16 kHz
    private val preRollFrames: Int = 6 // 300 ms with the service's 50 ms frames
) {
    private val history = ArrayDeque<ByteArray>()
    private var remaining = 0
    private var noiseFloor = 40.0
    private var decodedWindowSamples = 0

    data class Frame(val audio: List<ByteArray>, val resetDecoderAfter: Boolean)

    fun accept(pcm: ByteArray, count: Int): Frame {
        require(count in 0..pcm.size && count % 2 == 0) { "Expected complete PCM16 samples" }
        if (count == 0) return Frame(emptyList(), false)
        var energy = 0.0
        for (i in 0 until count step 2) {
            val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            energy += sample.toDouble() * sample
        }
        val samples = count / 2
        val rms = sqrt(energy / samples)
        val voiced = rms >= max(minimumRms, noiseFloor * 3.0)
        val wasActive = remaining > 0
        if (voiced) remaining = tailSamples else remaining = max(0, remaining - samples)
        if (!wasActive && !voiced) {
            noiseFloor = noiseFloor * 0.98 + rms * 0.02
            history.addLast(pcm.copyOf(count))
            while (history.size > preRollFrames) history.removeFirst()
            return Frame(emptyList(), false)
        }
        val frames = ArrayList<ByteArray>(history.size + 1)
        if (!wasActive) {
            frames.addAll(history)
            history.clear()
        }
        frames.add(pcm.copyOf(count))
        decodedWindowSamples += samples
        // Limit a decoder window in sustained noise to ten seconds. This bounds
        // accumulated recognizer state; it does not promise hardware-level power.
        val reset = remaining == 0 || decodedWindowSamples >= 160_000
        if (reset) decodedWindowSamples = 0
        return Frame(frames, reset)
    }
}
