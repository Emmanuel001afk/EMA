package com.ema.assistant

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Small, fully on-device acoustic template matcher for EMA's wake phrase.
 *
 * This is intentionally independent of cloud services and third-party keys.
 * It is an experimental speaker/phrase template baseline, not a neural model:
 * enroll several clean examples and calibrate thresholds on real devices before
 * enabling unattended activation. Input audio must be mono PCM16 at 16 kHz.
 */
internal class EmaWakeWordEngine(
    private val frameSize: Int = 320,
    private val hopSize: Int = 160,
    private val bandCount: Int = 16
) {
    data class Template(val frames: List<FloatArray>) {
        val frameCount: Int get() = frames.size
    }

    data class Match(val distance: Float, val matched: Boolean)

    /** Convert a clean spoken wake phrase into a compact spectral template. */
    fun enroll(pcm: ShortArray, sampleRate: Int = 16_000): Template? {
        if (sampleRate != 16_000 || pcm.size < sampleRate / 2 || pcm.size > sampleRate * 4) return null
        val features = features(pcm)
        if (features.size < 20 || features.size > 400) return null
        return Template(features)
    }

    /**
     * Compare a recent audio window with an enrolled phrase using band-energy
     * features and subsequence-friendly dynamic time warping. Lower is better.
     * Thresholds must be calibrated against negative audio on target devices.
     */
    fun compare(window: ShortArray, template: Template, threshold: Float): Match {
        if (template.frames.isEmpty() || window.size < frameSize || threshold <= 0f) {
            return Match(Float.POSITIVE_INFINITY, false)
        }
        val candidate = features(window)
        if (candidate.isEmpty()) return Match(Float.POSITIVE_INFINITY, false)
        val distance = subsequenceDtw(candidate, template.frames)
        return Match(distance, distance.isFinite() && distance <= threshold)
    }

    private fun features(pcm: ShortArray): List<FloatArray> {
        if (pcm.size < frameSize) return emptyList()
        val frequencies = FloatArray(bandCount) { i ->
            // Log-spaced analysis points cover voice fundamentals and formants.
            (180.0 * Math.pow(18.0, i.toDouble() / (bandCount - 1))).toFloat()
        }
        val frames = ArrayList<FloatArray>()
        var offset = 0
        while (offset + frameSize <= pcm.size) {
            var mean = 0.0
            for (i in 0 until frameSize) mean += pcm[offset + i].toDouble()
            mean /= frameSize
            val frame = DoubleArray(frameSize) { i ->
                val centered = (pcm[offset + i].toDouble() - mean) / 32768.0
                centered * (0.5 - 0.5 * cos(2.0 * Math.PI * i / (frameSize - 1)))
            }
            val energy = frame.sumOf { it * it } / frameSize
            if (energy > 1.0e-8) {
                val raw = FloatArray(bandCount)
                for (b in frequencies.indices) {
                    val omega = 2.0 * Math.PI * frequencies[b] / 16_000.0
                    val coeff = 2.0 * cos(omega)
                    var q1 = 0.0
                    var q2 = 0.0
                    for (sample in frame) {
                        val q0 = sample + coeff * q1 - q2
                        q2 = q1
                        q1 = q0
                    }
                    val power = max(1.0e-12, q1 * q1 + q2 * q2 - coeff * q1 * q2)
                    raw[b] = ln(power).toFloat()
                }
                val average = raw.average().toFloat()
                var variance = 0.0
                for (v in raw) variance += (v - average) * (v - average)
                val scale = sqrt(variance / bandCount).toFloat().coerceAtLeast(0.05f)
                for (i in raw.indices) raw[i] = (raw[i] - average) / scale
                frames += raw
            }
            offset += hopSize
        }
        return frames
    }

    private fun subsequenceDtw(input: List<FloatArray>, template: List<FloatArray>): Float {
        // Dynamic programming with a rolling row bounds memory for continuous audio.
        val inf = Float.POSITIVE_INFINITY
        var previous = FloatArray(template.size + 1) { 0f }
        var current = FloatArray(template.size + 1) { inf }
        for (i in input.indices) {
            current[0] = 0f
            for (j in template.indices) {
                val cost = frameDistance(input[i], template[j])
                current[j + 1] = cost + min(previous[j + 1], min(current[j], previous[j]))
            }
            val swap = previous
            previous = current
            current = swap
            current.fill(inf)
        }
        val endCost = previous.last()
        return endCost / max(1, template.size)
    }

    private fun frameDistance(a: FloatArray, b: FloatArray): Float {
        var total = 0f
        val size = min(a.size, b.size)
        for (i in 0 until size) total += abs(a[i] - b[i])
        return total / max(1, size)
    }
}
