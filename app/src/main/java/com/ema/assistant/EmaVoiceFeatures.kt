package com.ema.assistant

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The voice features used by the personal wake-word check.
 *
 * The wake word is a sound, so the check that decides "was that really my
 * voice?" has to look at the sound, not at the words the decoder produced.
 * EMA uses the classic pipeline: pre-emphasis, framing with a Hann window,
 * a power spectrum, a mel filterbank, the logarithm and a DCT, which gives
 * the MFCCs. Only the coefficients that describe the *shape* of the sound are
 * kept (the loudness coefficient is dropped), so saying the same words at a
 * different volume still matches.
 *
 * Everything here is plain arithmetic on samples: no Android, no model file,
 * no network. The unit tests feed synthetic tones and check that the pipeline
 * produces stable, comparable features.
 */
object EmaVoiceFeatures {

    const val SAMPLE_RATE = 16000

    /** 25 ms at 16 kHz. */
    const val FRAME = 400

    /** 10 ms at 16 kHz. */
    const val HOP = 160

    /** The FFT size (a power of two, at least twice the frame). */
    const val FFT_SIZE = 512

    /** How many mel bands the filterbank has. */
    const val MEL_BANDS = 26

    /** How many MFCCs are computed per frame. */
    const val COEFFICIENTS = 13

    /** The frame size used for a stored template. */
    const val TEMPLATE_FRAMES = 32

    private val hann: FloatArray by lazy {
        FloatArray(FRAME) { (0.5 - 0.5 * cos(2.0 * PI * it / (FRAME - 1))).toFloat() }
    }

    private val melBank: Array<FloatArray> by lazy { buildMelBank() }

    private val dct: FloatArray by lazy {
        // DCT-II matrix, [k][n] = cos(pi * k * (n + 0.5) / N)
        FloatArray(COEFFICIENTS * MEL_BANDS) { index ->
            val k = index / MEL_BANDS
            val n = index % MEL_BANDS
            cos(PI * k * (n + 0.5) / MEL_BANDS).toFloat()
        }
    }

    /**
     * The MFCCs of a 16 kHz PCM buffer.
     *
     * Each returned frame holds the 12 shape coefficients (coefficient 0, the
     * loudness, is dropped on purpose). An empty or too-short buffer gives no
     * frames.
     */
    fun mfcc(pcm: ShortArray): List<FloatArray> {
        if (pcm.size < FRAME) return emptyList()
        val emphasised = FloatArray(pcm.size)
        emphasised[0] = pcm[0].toFloat()
        for (i in 1 until pcm.size) {
            emphasised[i] = pcm[i].toFloat() - 0.97f * pcm[i - 1].toFloat()
        }
        val frames = mutableListOf<FloatArray>()
        var start = 0
        while (start + FRAME <= emphasised.size) {
            val frame = FloatArray(FRAME)
            for (i in 0 until FRAME) frame[i] = emphasised[start + i] * hann[i]
            frames += frameToMfcc(frame)
            start += HOP
        }
        return frames
    }

    /** The MFCCs of the last [millis] milliseconds of a PCM buffer. */
    fun mfccOfTail(pcm: ShortArray, millis: Int): List<FloatArray> {
        val wanted = (SAMPLE_RATE * millis / 1000).coerceAtLeast(FRAME)
        val tail = if (pcm.size <= wanted) pcm else pcm.copyOfRange(pcm.size - wanted, pcm.size)
        return mfcc(tail)
    }

    /** One frame of PCM to 12 MFCCs. */
    private fun frameToMfcc(frame: FloatArray): FloatArray {
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)
        for (i in frame.indices) real[i] = frame[i]
        fft(real, imag)
        val power = FloatArray(FFT_SIZE / 2 + 1)
        for (k in power.indices) power[k] = real[k] * real[k] + imag[k] * imag[k]
        val logMel = FloatArray(MEL_BANDS)
        for (band in 0 until MEL_BANDS) {
            var sum = 0f
            val filter = melBank[band]
            for (k in power.indices) sum += filter[k] * power[k]
            logMel[band] = if (sum > 1e-10f) ln(sum.toDouble()).toFloat() else -20f
        }
        val coefficients = FloatArray(COEFFICIENTS)
        for (k in 0 until COEFFICIENTS) {
            var sum = 0f
            for (n in 0 until MEL_BANDS) {
                sum += dct[k * MEL_BANDS + n] * logMel[n]
            }
            coefficients[k] = sum
        }
        // Drop the loudness coefficient: the same words at another volume must
        // still look the same.
        return coefficients.copyOfRange(1, COEFFICIENTS)
    }

    /**
     * Resample a frame sequence to [EmaVoiceFeatures.TEMPLATE_FRAMES] frames.
     *
     * Two recordings of the same words are never the same length, so every
     * template is brought to one length before it is compared or averaged.
     */
    fun resample(frames: List<FloatArray>, target: Int = TEMPLATE_FRAMES): List<FloatArray> {
        if (frames.isEmpty()) return emptyList()
        if (frames.size == target) return frames
        val width = frames[0].size
        val out = ArrayList<FloatArray>(target)
        for (j in 0 until target) {
            val position = if (target == 1) 0.0 else j.toDouble() * (frames.size - 1) / (target - 1)
            val low = position.toInt().coerceIn(0, frames.size - 1)
            val high = (low + 1).coerceAtMost(frames.size - 1)
            val mix = (position - low).toFloat()
            val a = frames[low]
            val b = frames[high]
            val frame = FloatArray(width)
            for (i in 0 until width) frame[i] = a[i] + (b[i] - a[i]) * mix
            out += frame
        }
        return out
    }

    /** The mean of several frame sequences of the same length. */
    fun average(samples: List<List<FloatArray>>, target: Int = TEMPLATE_FRAMES): List<FloatArray> {
        if (samples.isEmpty()) return emptyList()
        val normalised = samples.map { resample(it, target) }
        val width = normalised[0].first().size
        val out = ArrayList<FloatArray>(target)
        for (j in 0 until target) {
            val frame = FloatArray(width)
            for (sample in normalised) {
                val source = sample[j]
                for (i in 0 until width) frame[i] += source[i]
            }
            for (i in 0 until width) frame[i] /= normalised.size.toFloat()
            out += frame
        }
        return out
    }

    private fun buildMelBank(): Array<FloatArray> {
        val lowMel = hzToMel(80.0)
        val highMel = hzToMel(SAMPLE_RATE / 2.0)
        val points = FloatArray(MEL_BANDS + 2)
        for (i in points.indices) {
            val mel = lowMel + (highMel - lowMel) * i / (MEL_BANDS + 1)
            points[i] = melToHz(mel).toFloat()
        }
        val bins = FFT_SIZE / 2 + 1
        val bank = Array(MEL_BANDS) { FloatArray(bins) }
        for (band in 0 until MEL_BANDS) {
            val left = points[band]
            val centre = points[band + 1]
            val right = points[band + 2]
            for (k in 0 until bins) {
                val hz = k * SAMPLE_RATE.toFloat() / FFT_SIZE
                val weight = when {
                    hz in left..centre && centre > left -> (hz - left) / (centre - left)
                    hz in centre..right && right > centre -> (right - hz) / (right - centre)
                    else -> 0f
                }
                bank[band][k] = weight
            }
        }
        return bank
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    /** An in-place radix-2 FFT. */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                var swap = real[i]; real[i] = real[j]; real[j] = swap
                swap = imag[i]; imag[i] = imag[j]; imag[j] = swap
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                val half = len shr 1
                val ur = real[i]
                val ui = imag[i]
                val vr = real[i + half]
                val vi = imag[i + half]
                val tr = wr * vr - wi * vi
                val ti = wr * vi + wi * vr
                real[i] = ur + tr
                imag[i] = ui + ti
                real[i + half] = ur - tr
                imag[i + half] = ui - ti
                i += len
            }
            len = len shl 1
        }
    }

    /** The root mean square of a buffer, used by the tests. */
    fun rms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var energy = 0.0
        for (sample in pcm) energy += sample.toDouble() * sample.toDouble()
        return sqrt(energy / pcm.size)
    }

    /** A tone, used by the tests to make synthetic speech-like material. */
    fun tone(frequency: Double, millis: Int, amplitude: Double = 0.3, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val count = (sampleRate * millis / 1000).toInt()
        return ShortArray(count) { index ->
            val value = amplitude * sin(2.0 * PI * frequency * index / sampleRate)
            (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
