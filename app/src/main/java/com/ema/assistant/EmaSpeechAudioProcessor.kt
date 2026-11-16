package com.ema.assistant

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * EMA's on-device speech audio front-end, adaptive voice-activity detector,
 * continuation-aware utterance endpointer, sample-rate converter and WAV
 * decoder.
 *
 * Pure Kotlin: no Android framework calls, no allocations per sample, no
 * network and no disk writes. Every stage is deterministic from PCM samples so
 * the full signal chain is verified in plain JVM unit tests.
 */
class EmaSpeechAudioProcessor(
    val config: Config = Config()
) {

    data class Config(
        val sampleRate: Int = TARGET_SAMPLE_RATE,
        val enableHighPass: Boolean = true,
        val enableAgc: Boolean = true,
        val minSpeechRms: Double = 145.0,
        val snrMultiplier: Double = 2.75,
        val targetSpeechRms: Double = 3600.0,
        val minGain: Float = 0.55f,
        val maxGain: Float = 3.6f,
        val preRollMs: Int = 360,
        val onsetFrames: Int = 2,
        val minSpeechMs: Long = 220L,
        val noSpeechTimeoutMs: Long = 6_500L,
        val completeSilenceMs: Long = 1_100L,
        val incompleteClauseBonusMs: Long = 750L,
        val maxDurationMs: Long = 25_000L
    ) {
        companion object {
            fun forProfile(
                profile: EmaRecognitionProfile,
                enableAgc: Boolean = true,
                minSpeechMsOverride: Long? = null,
                completeSilenceMsOverride: Long? = null,
                possiblyCompleteSilenceMsOverride: Long? = null,
                noSpeechTimeoutMsOverride: Long? = null,
                maxDurationMsOverride: Long? = null
            ): Config {
                val base = when (profile) {
                    EmaRecognitionProfile.COMMAND -> Config(
                        enableAgc = enableAgc,
                        minSpeechMs = 220L,
                        noSpeechTimeoutMs = 6_500L,
                        completeSilenceMs = 1_100L,
                        incompleteClauseBonusMs = 750L,
                        maxDurationMs = 25_000L
                    )
                    EmaRecognitionProfile.DICTATION -> Config(
                        enableAgc = enableAgc,
                        minSpeechMs = 220L,
                        noSpeechTimeoutMs = 9_000L,
                        completeSilenceMs = 2_100L,
                        incompleteClauseBonusMs = 900L,
                        maxDurationMs = 60_000L
                    )
                }
                val silenceOverride = completeSilenceMsOverride?.takeIf { it > 0L }
                    ?: possiblyCompleteSilenceMsOverride?.takeIf { it > 0L }?.let { (it * 5L) / 4L }
                return base.copy(
                    minSpeechMs = minSpeechMsOverride?.takeIf { it > 0L }?.coerceIn(80L, 10_000L) ?: base.minSpeechMs,
                    completeSilenceMs = silenceOverride?.coerceIn(450L, 10_000L) ?: base.completeSilenceMs,
                    noSpeechTimeoutMs = noSpeechTimeoutMsOverride?.takeIf { it > 0L }?.coerceIn(1_500L, 30_000L)
                        ?: base.noSpeechTimeoutMs,
                    maxDurationMs = maxDurationMsOverride?.takeIf { it > 0L }?.coerceIn(3_000L, 120_000L)
                        ?: base.maxDurationMs
                )
            }
        }
    }

    enum class Action {
        /** Waiting for speech onset; pre-roll is buffered internally. */
        WAIT_FOR_SPEECH,

        /** Feed [FrameDecision.audioToDecode] into the acoustic decoder. */
        DECODE,

        /** Speaker finished the utterance cleanly; flush final decoder state. */
        ENDPOINT_COMPLETE,

        /** No speech was heard within [Config.noSpeechTimeoutMs]. */
        TIMEOUT_NO_SPEECH,

        /** Maximum utterance duration reached; flush final decoder state. */
        MAX_DURATION_REACHED
    }

    data class FrameDecision(
        val action: Action,
        /** Processed 16-bit little-endian mono PCM chunks ready for decoding. */
        val audioToDecode: List<ByteArray>,
        /** True on the single frame that confirms the beginning of speech. */
        val speechJustStarted: Boolean,
        /** True when this individual frame contained speech-like energy. */
        val isVoiced: Boolean,
        /** Smoothed level in [-2, 10] dB matching SpeechRecognizer.onRmsChanged. */
        val rmsDb: Float,
        /** Current active AGC gain factor. */
        val gain: Float
    )

    // High-pass filter state (~75 Hz cutoff at 16 kHz)
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f

    // Speech-band monitor state (~300 Hz - 3600 Hz, used for VAD only)
    private var vbHpPrevIn = 0f
    private var vbHpPrevOut = 0f
    private var vbLpPrevOut = 0f

    // AGC & meter state
    private var currentGain = 1.0f
    private var smoothedRmsDb = -2.0f

    // Adaptive VAD & endpointing state
    private var noiseFloor = 55.0
    private val preRoll = ArrayDeque<ByteArray>()
    private var preRollSamples = 0
    private val maxPreRollSamples = (config.sampleRate * config.preRollMs / 1000).coerceAtLeast(config.sampleRate / 10)

    private var consecutiveVoicedFrames = 0
    private var speechStarted = false
    private var totalSamples = 0L
    private var voicedSamples = 0L
    private var trailingSilenceSamples = 0L
    private var continuousVoicedWithoutTextSamples = 0L
    private var incompleteClause = false
    private var decoderHeardWords = false
    private var lastKnownTranscript = ""

    /** Adaptive background RMS estimate (exposed for tests and diagnostics). */
    val backgroundRms: Double get() = noiseFloor

    /** Current AGC gain (exposed for tests and diagnostics). */
    val activeGain: Float get() = currentGain

    /** Whether speech onset has been confirmed in this session. */
    val hasSpeechStarted: Boolean get() = speechStarted

    /** Total milliseconds of voiced speech accumulated so far. */
    val voicedDurationMs: Long get() = voicedSamples * 1000L / config.sampleRate

    /** Total elapsed audio duration processed so far in milliseconds. */
    val elapsedDurationMs: Long get() = totalSamples * 1000L / config.sampleRate

    /** Reset all filter, AGC, VAD and endpointer state for a new utterance. */
    fun reset() {
        hpPrevIn = 0f
        hpPrevOut = 0f
        vbHpPrevIn = 0f
        vbHpPrevOut = 0f
        vbLpPrevOut = 0f
        currentGain = 1.0f
        smoothedRmsDb = -2.0f
        noiseFloor = 55.0
        preRoll.clear()
        preRollSamples = 0
        consecutiveVoicedFrames = 0
        speechStarted = false
        totalSamples = 0L
        voicedSamples = 0L
        trailingSilenceSamples = 0L
        continuousVoicedWithoutTextSamples = 0L
        incompleteClause = false
        decoderHeardWords = false
        lastKnownTranscript = ""
    }

    /**
     * Inform the endpointer of the decoder's current accumulated transcript.
     *
     * Serves two roles:
     *  1. Quiet/whispered speech that cleared the acoustic model confirms
     *     speech onset and resets the silence timer even below nominal RMS.
     *  2. Utterances ending in an open clause ("open WhatsApp and", "saying",
     *     "set an alarm for") receive extra pause allowance before completing.
     */
    fun onDecoderTranscript(transcript: String): Boolean {
        val cleaned = transcript.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return false
        incompleteClause = isIncompleteClause(cleaned)
        if (cleaned == lastKnownTranscript) return false
        lastKnownTranscript = cleaned
        decoderHeardWords = true
        continuousVoicedWithoutTextSamples = 0L
        trailingSilenceSamples = 0L
        if (!speechStarted) {
            speechStarted = true
            // Count at least minimal voiced time so endpointing can finish cleanly.
            val minSamples = config.sampleRate * config.minSpeechMs / 1000L
            if (voicedSamples < minSamples) voicedSamples = minSamples
            return true
        }
        return false
    }

    /**
     * Process one frame of 16 kHz mono PCM16 little-endian bytes ([byteCount]
     * must be even and in `0..pcmLe.size`).
     */
    fun process(pcmLe: ByteArray, byteCount: Int = pcmLe.size): FrameDecision {
        require(byteCount in 0..pcmLe.size && byteCount % 2 == 0) {
            "Expected complete PCM16 little-endian samples (got $byteCount bytes)"
        }
        val sampleCount = byteCount / 2
        if (sampleCount == 0) {
            return FrameDecision(
                action = if (speechStarted) Action.DECODE else Action.WAIT_FOR_SPEECH,
                audioToDecode = emptyList(),
                speechJustStarted = false,
                isVoiced = false,
                rmsDb = smoothedRmsDb,
                gain = currentGain
            )
        }

        // 1. High-pass filter + acoustic feature measurement in one pass.
        val filtered = FloatArray(sampleCount)
        var sumSq = 0.0
        var voiceBandSq = 0.0
        var zeroCrossings = 0
        var prevSign = 0

        for (i in 0 until sampleCount) {
            val lo = pcmLe[i * 2].toInt() and 0xff
            val hi = pcmLe[i * 2 + 1].toInt()
            val raw = ((hi shl 8) or lo).toShort().toFloat()

            val y = if (config.enableHighPass) {
                val out = HP_ALPHA * (hpPrevOut + raw - hpPrevIn)
                hpPrevIn = raw
                hpPrevOut = out
                out
            } else {
                raw
            }
            filtered[i] = y
            sumSq += y.toDouble() * y.toDouble()

            // Speech-band monitor (~300 - 3600 Hz)
            val hp300 = VB_HP_ALPHA * (vbHpPrevOut + y - vbHpPrevIn)
            vbHpPrevIn = y
            vbHpPrevOut = hp300
            val bp = vbLpPrevOut + VB_LP_ALPHA * (hp300 - vbLpPrevOut)
            vbLpPrevOut = bp
            voiceBandSq += bp.toDouble() * bp.toDouble()

            // Zero-crossing count with a tiny dead-zone so quantization dither is ignored.
            val sign = when {
                y > 12f -> 1
                y < -12f -> -1
                else -> 0
            }
            if (sign != 0) {
                if (prevSign != 0 && sign != prevSign) zeroCrossings++
                prevSign = sign
            }
        }

        val rms = sqrt(sumSq / sampleCount)
        val voiceBandRms = sqrt(voiceBandSq / sampleCount)
        val voiceBandRatio = if (rms > 1.0) voiceBandRms / rms else 0.0
        val zcr = if (sampleCount > 1) zeroCrossings.toDouble() / (sampleCount - 1) else 0.0

        // 2. Multi-cue adaptive Voice Activity Detection.
        val voicedThreshold = max(config.minSpeechRms, noiseFloor * config.snrMultiplier)
        val fricativeThreshold = max(config.minSpeechRms * 0.68, noiseFloor * max(2.1, config.snrMultiplier * 0.78))

        val isVowelOrSonorant = rms >= voicedThreshold &&
            zcr in 0.007..0.55 &&
            (voiceBandRatio >= 0.28 || rms >= max(config.minSpeechRms * 2.2, noiseFloor * 4.5))
        val isFricative = rms >= fricativeThreshold && zcr in 0.16..0.68
        val voiced = isVowelOrSonorant || isFricative

        // Update noise floor asymmetrically.
        if (!voiced) {
            val blend = if (rms < noiseFloor) 0.10 else 0.015
            noiseFloor = (noiseFloor * (1.0 - blend) + rms * blend).coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
            continuousVoicedWithoutTextSamples = 0L
        } else {
            continuousVoicedWithoutTextSamples += sampleCount
            // If steady loud noise (fan, generator, road hum) persists > 6 s
            // with zero new decoded words, slowly absorb it into the floor.
            val sustainedLimit = config.sampleRate * 6L
            if (continuousVoicedWithoutTextSamples > sustainedLimit) {
                noiseFloor = (noiseFloor * 0.98 + rms * 0.02).coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
            }
        }

        // 3. Speech-gated smooth AGC + soft-knee limiting.
        if (config.enableAgc) {
            if (voiced && rms in 180.0..22_000.0 && rms >= noiseFloor * 2.4) {
                val desired = (config.targetSpeechRms / rms).toFloat().coerceIn(config.minGain, config.maxGain)
                val speed = if (desired < currentGain) 0.28f else 0.08f
                currentGain += (desired - currentGain) * speed
            } else if (!voiced) {
                // Gently relax toward unity gain during silence; never amplify room noise.
                currentGain = currentGain * 0.992f + 0.008f
            }
        } else {
            currentGain = 1.0f
        }

        val processed = ByteArray(byteCount)
        val gain = currentGain
        for (i in 0 until sampleCount) {
            val scaled = softLimit(filtered[i] * gain)
            val s = scaled.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            processed[i * 2] = (s and 0xff).toByte()
            processed[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }

        // 4. Calibrated SpeechRecognizer RMS dB in [-2.0, 10.0].
        val floorRef = max(noiseFloor, 40.0)
        val rawDb = if (rms <= 1.0) -2.0f else (10.0 * log10(rms / floorRef) - 2.0).toFloat().coerceIn(-2.0f, 10.0f)
        val dbAlpha = if (rawDb > smoothedRmsDb) 0.45f else 0.20f
        smoothedRmsDb += (rawDb - smoothedRmsDb) * dbAlpha

        // 5. Onset confirmation, pre-roll flush & smart endpointing.
        totalSamples += sampleCount
        val elapsedMs = totalSamples * 1000L / config.sampleRate

        if (!speechStarted) {
            consecutiveVoicedFrames = if (voiced) consecutiveVoicedFrames + 1 else 0
            if (consecutiveVoicedFrames >= config.onsetFrames) {
                speechStarted = true
                voicedSamples += sampleCount.toLong() * consecutiveVoicedFrames
                trailingSilenceSamples = 0L
                val out = ArrayList<ByteArray>(preRoll.size + 1)
                out.addAll(preRoll)
                preRoll.clear()
                preRollSamples = 0
                out.add(processed)
                return FrameDecision(
                    action = if (elapsedMs >= config.maxDurationMs) Action.MAX_DURATION_REACHED else Action.DECODE,
                    audioToDecode = out,
                    speechJustStarted = true,
                    isVoiced = true,
                    rmsDb = smoothedRmsDb,
                    gain = currentGain
                )
            }
            preRoll.addLast(processed)
            preRollSamples += sampleCount
            while (preRollSamples > maxPreRollSamples && preRoll.size > 1) {
                val removed = preRoll.removeFirst()
                preRollSamples -= removed.size / 2
            }
            val timedOut = elapsedMs >= config.noSpeechTimeoutMs
            return FrameDecision(
                action = if (timedOut) Action.TIMEOUT_NO_SPEECH else Action.WAIT_FOR_SPEECH,
                audioToDecode = emptyList(),
                speechJustStarted = false,
                isVoiced = voiced,
                rmsDb = smoothedRmsDb,
                gain = currentGain
            )
        }

        // Speech is active: route every frame through the decoder until endpoint.
        if (voiced) {
            voicedSamples += sampleCount
            trailingSilenceSamples = 0L
        } else {
            trailingSilenceSamples += sampleCount
        }

        // If onset happened on a brief transient (< minSpeechMs) with zero words
        // and long trailing silence, keep listening rather than ending on a cough,
        // up to the overall deadline.
        val voicedMs = voicedSamples * 1000L / config.sampleRate
        val silenceMs = trailingSilenceSamples * 1000L / config.sampleRate
        val requiredSilenceMs = config.completeSilenceMs +
            (if (incompleteClause) config.incompleteClauseBonusMs else 0L)

        val chunks = listOf(processed)
        if (elapsedMs >= config.maxDurationMs) {
            return FrameDecision(Action.MAX_DURATION_REACHED, chunks, false, voiced, smoothedRmsDb, currentGain)
        }
        if ((voicedMs >= config.minSpeechMs || decoderHeardWords) && silenceMs >= requiredSilenceMs) {
            return FrameDecision(Action.ENDPOINT_COMPLETE, chunks, false, voiced, smoothedRmsDb, currentGain)
        }
        return FrameDecision(Action.DECODE, chunks, false, voiced, smoothedRmsDb, currentGain)
    }

    /**
     * Condition an offline batch recording (already 16 kHz mono PCM16) with
     * DC/rumble removal and safe peak/RMS normalization before decoding.
     */
    fun conditionBatch(samples: ShortArray): ByteArray {
        if (samples.isEmpty()) return ByteArray(0)
        val filtered = FloatArray(samples.size)
        var prevIn = 0f
        var prevOut = 0f
        var peak = 0f
        // Measure active-frame RMS so long silence does not exaggerate batch gain.
        val window = min(samples.size, TARGET_SAMPLE_RATE / 25) // 40 ms
        var winSq = 0.0
        var winCount = 0
        val activeRms = ArrayList<Double>(samples.size / max(1, window) + 1)

        for (i in samples.indices) {
            val raw = samples[i].toFloat()
            val y = if (config.enableHighPass) {
                val out = HP_ALPHA * (prevOut + raw - prevIn)
                prevIn = raw
                prevOut = out
                out
            } else raw
            filtered[i] = y
            val ay = abs(y)
            if (ay > peak) peak = ay
            winSq += y.toDouble() * y.toDouble()
            winCount++
            if (winCount == window) {
                val r = sqrt(winSq / winCount)
                if (r >= config.minSpeechRms) activeRms += r
                winSq = 0.0
                winCount = 0
            }
        }
        if (winCount > 0) {
            val r = sqrt(winSq / winCount)
            if (r >= config.minSpeechRms) activeRms += r
        }

        val gain = if (config.enableAgc && activeRms.isNotEmpty() && peak > 1f) {
            activeRms.sort()
            // 70th-percentile active RMS avoids boosting noise while lifting quiet recordings.
            val ref = activeRms[(activeRms.size * 7 / 10).coerceIn(0, activeRms.lastIndex)]
            val desired = (config.targetSpeechRms / ref).toFloat().coerceIn(config.minGain, config.maxGain)
            // Also respect headroom so strong consonants stay below soft-limit onset.
            min(desired, 29_500f / peak).coerceIn(config.minGain, config.maxGain)
        } else {
            1.0f
        }

        val out = ByteArray(samples.size * 2)
        for (i in filtered.indices) {
            val s = softLimit(filtered[i] * gain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (s and 0xff).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        return out
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 16_000

        /** Maximum WAV/raw stream accepted for offline batch transcription (64 MB). */
        const val MAX_BATCH_BYTES = 64 * 1024 * 1024

        // ~75 Hz 1st-order high-pass at 16 kHz: 1 / (1 + 2*pi*75/16000)
        private const val HP_ALPHA = 0.9714f

        // Speech-band monitor filters at 16 kHz: HP ~300 Hz, LP ~3600 Hz
        private const val VB_HP_ALPHA = 0.8946f
        private const val VB_LP_ALPHA = 0.5858f

        private const val MIN_NOISE_FLOOR = 20.0
        private const val MAX_NOISE_FLOOR = 6_000.0

        private const val LIMIT_KNEE = 26_000f
        private const val LIMIT_HEADROOM = 6_700f

        /** Words that strongly indicate the speaker paused mid-thought. */
        private val TRAILING_CONTINUATION_WORDS = setOf(
            // Conjunctions & sequence links
            "and", "then", "or", "but", "also", "plus", "because", "while", "until", "before", "after",
            // Content introducers & prepositions
            "saying", "that", "to", "for", "on", "in", "at", "with", "from", "by", "about", "of",
            "into", "via", "using", "through", "called", "named", "titled", "between",
            // Articles / determiners / possessives waiting for a noun
            "a", "an", "the", "my", "our", "your", "his", "her", "this", "these", "those",
            // Bare leading command verbs with no argument yet
            "open", "launch", "play", "search", "find", "call", "dial", "phone", "ring",
            "send", "message", "text", "tell", "remind", "set", "turn", "switch", "enable", "disable",
            "increase", "decrease", "raise", "lower", "select", "choose", "pick", "tap", "click",
            "type", "write", "navigate", "remember", "convert", "calculate", "transcribe",
            // Mid-number scales / decimals
            "point", "hundred", "thousand", "million", "plus", "minus", "times", "divided", "multiplied",
            // Dictation connector
            "comma"
        )

        private val TRAILING_CONTINUATION_BIGRAMS = setOf(
            "remind me", "that says", "which says", "to say", "that reads",
            "search for", "look up", "look for", "go to", "navigate to",
            "hey ema", "hey emma", "hi ema", "hi emma", "okay ema", "okay emma", "ok ema", "ok emma",
            "percent of", "square root", "divided by", "multiplied by", "with sim", "using sim"
        )

        /**
         * Smooth C1-continuous soft-knee limiter. Linear up to +/-26,000 and
         * asymptotically bounded below +/-32,700 so loud close-talk never clips.
         */
        fun softLimit(sample: Float): Float {
            val magnitude = abs(sample)
            if (magnitude <= LIMIT_KNEE) return sample
            val excess = magnitude - LIMIT_KNEE
            val compressed = LIMIT_KNEE + LIMIT_HEADROOM * (excess / (excess + LIMIT_HEADROOM))
            return if (sample >= 0f) compressed else -compressed
        }

        /** True when [transcript] ends with an unfinished clause or bare verb. */
        fun isIncompleteClause(transcript: String): Boolean {
            val words = transcript.lowercase()
                .replace(Regex("[^a-z0-9' ]+"), " ")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() && it != "[unk]" && it != "unk" }
            if (words.isEmpty()) return false
            val last = words.last()
            if (words.size >= 2) {
                val bigram = "${words[words.size - 2]} $last"
                if (bigram in TRAILING_CONTINUATION_BIGRAMS) return true
                // "turn on" / "turn off" with a target BEFORE it ("turn the flashlight on") is complete;
                // "turn on" / "switch on" alone is incomplete ("turn on ... wifi").
                if ((last == "on" || last == "off") && words.size >= 3 &&
                    words[words.size - 2] !in setOf("turn", "switch", "put", "log", "sign")) {
                    return false
                }
            }
            // Standalone "play" / "stop" / "pause" can be a complete single-word media command,
            // except right after "and"/"then" ("open Spotify and play ...").
            if (last == "play" && words.size == 1) return false
            return last in TRAILING_CONTINUATION_WORDS
        }

        /** Convert 16-bit little-endian PCM bytes to a [ShortArray]. */
        fun bytesToShorts(pcmLe: ByteArray, byteCount: Int = pcmLe.size): ShortArray {
            val count = min(byteCount, pcmLe.size) / 2
            return ShortArray(count) { i ->
                val lo = pcmLe[i * 2].toInt() and 0xff
                val hi = pcmLe[i * 2 + 1].toInt()
                ((hi shl 8) or lo).toShort()
            }
        }

        /** Convert a [ShortArray] to 16-bit little-endian PCM bytes. */
        fun shortsToBytes(samples: ShortArray, count: Int = samples.size): ByteArray {
            val n = min(count, samples.size).coerceAtLeast(0)
            val out = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = samples[i].toInt()
                out[i * 2] = (v and 0xff).toByte()
                out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
            }
            return out
        }

        /**
         * Convert interleaved PCM16 audio at any sample rate (4 kHz - 192 kHz)
         * and channel count (1 - 8) into 16 kHz mono PCM16 samples.
         *
         * Downsampling averages over each target bin to suppress aliasing above
         * 8 kHz; upsampling interpolates linearly between adjacent frames.
         */
        fun resampleToMono16k(
            interleaved: ShortArray,
            sourceSampleRate: Int,
            channels: Int = 1,
            frameCount: Int = if (channels > 0) interleaved.size / channels else 0
        ): ShortArray {
            require(sourceSampleRate in 4_000..192_000) { "Unsupported sample rate: $sourceSampleRate" }
            require(channels in 1..8) { "Unsupported channel count: $channels" }
            val frames = min(frameCount, interleaved.size / channels).coerceAtLeast(0)
            if (frames == 0) return ShortArray(0)

            // Downmix to mono without int16 overflow.
            val mono: ShortArray = if (channels == 1 && frames == interleaved.size) {
                interleaved
            } else {
                ShortArray(frames) { f ->
                    var sum = 0L
                    val base = f * channels
                    for (c in 0 until channels) sum += interleaved[base + c].toLong()
                    (sum / channels).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }

            if (sourceSampleRate == TARGET_SAMPLE_RATE) {
                return if (mono === interleaved) mono.copyOf() else mono
            }

            val outLength = ((frames.toLong() * TARGET_SAMPLE_RATE) / sourceSampleRate).toInt().coerceAtLeast(1)
            val ratio = sourceSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
            return if (sourceSampleRate > TARGET_SAMPLE_RATE) {
                // Anti-aliasing box-average downsampler (e.g. 48 kHz / 44.1 kHz -> 16 kHz).
                ShortArray(outLength) { i ->
                    val startPos = i * ratio
                    val endPos = min(frames.toDouble(), (i + 1) * ratio)
                    val first = startPos.toInt().coerceIn(0, frames - 1)
                    val last = (endPos - 1e-9).toInt().coerceIn(first, frames - 1)
                    var acc = 0.0
                    var weightSum = 0.0
                    for (idx in first..last) {
                        val left = max(startPos, idx.toDouble())
                        val right = min(endPos, (idx + 1).toDouble())
                        val w = (right - left).coerceAtLeast(0.0)
                        acc += mono[idx].toDouble() * w
                        weightSum += w
                    }
                    val v = if (weightSum > 0.0) (acc / weightSum).roundToInt() else mono[first].toInt()
                    v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            } else {
                // Linear interpolation upsampler (e.g. 8 kHz telephony -> 16 kHz).
                ShortArray(outLength) { i ->
                    val pos = i * ratio
                    val idx0 = pos.toInt().coerceIn(0, frames - 1)
                    val idx1 = min(idx0 + 1, frames - 1)
                    val frac = pos - idx0
                    val v = (mono[idx0] * (1.0 - frac) + mono[idx1] * frac).roundToInt()
                    v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
        }

        /** Convenience overload returning 16 kHz mono PCM16 little-endian bytes. */
        fun resampleBytesToMono16k(
            pcmLe: ByteArray,
            byteCount: Int,
            sourceSampleRate: Int,
            channels: Int = 1
        ): ByteArray {
            if (sourceSampleRate == TARGET_SAMPLE_RATE && channels == 1) {
                val safe = min(byteCount, pcmLe.size) - (min(byteCount, pcmLe.size) % 2)
                return pcmLe.copyOf(safe)
            }
            val frameBytes = channels * 2
            val safeBytes = min(byteCount, pcmLe.size).let { it - (it % frameBytes) }
            if (safeBytes <= 0) return ByteArray(0)
            val shorts = bytesToShorts(pcmLe, safeBytes)
            return shortsToBytes(resampleToMono16k(shorts, sourceSampleRate, channels))
        }

        data class DecodedAudio(
            val pcm16kMono: ShortArray,
            val originalSampleRate: Int,
            val originalChannels: Int,
            val bitsPerSample: Int,
            val durationMs: Long
        )

        /** Read a bounded [InputStream] and parse it as a RIFF/WAVE recording. */
        fun decodeWav(stream: InputStream, maxBytes: Int = MAX_BATCH_BYTES): DecodedAudio {
            val out = ByteArrayOutputStream(min(maxBytes, 65_536))
            val buf = ByteArray(16_384)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                require(total <= maxBytes) { "Audio file exceeds offline transcription size limit" }
                out.write(buf, 0, n)
            }
            return decodeWav(out.toByteArray())
        }

        /**
         * Parse a RIFF/WAVE byte array (PCM 8/16/24/32-bit or IEEE 32-bit float,
         * mono or multichannel, 4-192 kHz) into 16 kHz mono PCM16 samples.
         */
        fun decodeWav(wav: ByteArray): DecodedAudio {
            require(wav.size >= 44) { "Invalid WAV file: header is too short" }
            require(ascii(wav, 0, 4) == "RIFF" && ascii(wav, 8, 4) == "WAVE") {
                "Unsupported audio container: expected RIFF/WAVE"
            }

            var audioFormat = -1
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataOffset = -1
            var dataLength = 0

            var offset = 12
            while (offset + 8 <= wav.size) {
                val chunkId = ascii(wav, offset, 4)
                val rawChunkSize = leInt(wav, offset + 4).toLong() and 0xffff_ffffL
                val chunkStart = offset + 8
                val available = (wav.size - chunkStart).coerceAtLeast(0).toLong()
                val chunkSize = min(rawChunkSize, available).toInt()

                when (chunkId) {
                    "fmt " -> {
                        require(chunkSize >= 16) { "Invalid WAV fmt chunk" }
                        var fmt = leUShort(wav, chunkStart)
                        channels = leUShort(wav, chunkStart + 2)
                        sampleRate = leInt(wav, chunkStart + 4)
                        bitsPerSample = leUShort(wav, chunkStart + 14)
                        // WAVE_FORMAT_EXTENSIBLE stores the real format tag at offset 24.
                        if (fmt == 0xFFFE && chunkSize >= 40) {
                            fmt = leUShort(wav, chunkStart + 24)
                            val validBits = leUShort(wav, chunkStart + 18)
                            if (bitsPerSample == 0 && validBits > 0) bitsPerSample = validBits
                        }
                        audioFormat = fmt
                    }
                    "data" -> {
                        dataOffset = chunkStart
                        dataLength = chunkSize
                        if (audioFormat != -1) break
                    }
                }
                val padded = rawChunkSize + (rawChunkSize and 1L)
                val next = chunkStart.toLong() + padded
                if (next <= offset || next > wav.size) break
                offset = next.toInt()
            }

            require(audioFormat == 1 || audioFormat == 3) {
                "Unsupported WAV encoding ($audioFormat): only uncompressed PCM or float WAV is supported offline"
            }
            require(channels in 1..8) { "Unsupported WAV channel count: $channels" }
            require(sampleRate in 4_000..192_000) { "Unsupported WAV sample rate: $sampleRate Hz" }
            require(dataOffset >= 0 && dataLength > 0) { "WAV file contains no audio samples" }

            val bytesPerSample = bitsPerSample / 8
            val validPcm = audioFormat == 1 && bitsPerSample in setOf(8, 16, 24, 32)
            val validFloat = audioFormat == 3 && bitsPerSample == 32
            require(validPcm || validFloat) { "Unsupported WAV bit depth: $bitsPerSample-bit" }

            val frameSize = bytesPerSample * channels
            val frameCount = dataLength / frameSize
            require(frameCount > 0) { "WAV file contains no complete sample frames" }

            // Downmix directly to mono PCM16 at the original sample rate, then resample to 16 kHz.
            val monoNative = ShortArray(frameCount) { f ->
                var sum = 0.0
                val frameBase = dataOffset + f * frameSize
                for (c in 0 until channels) {
                    val p = frameBase + c * bytesPerSample
                    val normalized: Double = when {
                        audioFormat == 3 -> {
                            val fVal = Float.fromBits(leInt(wav, p))
                            if (fVal.isFinite()) fVal.toDouble().coerceIn(-1.0, 1.0) else 0.0
                        }
                        bitsPerSample == 8 -> ((wav[p].toInt() and 0xff) - 128) / 128.0
                        bitsPerSample == 16 -> {
                            val s = ((wav[p + 1].toInt() shl 8) or (wav[p].toInt() and 0xff)).toShort()
                            s.toDouble() / 32_768.0
                        }
                        bitsPerSample == 24 -> {
                            val b0 = wav[p].toInt() and 0xff
                            val b1 = wav[p + 1].toInt() and 0xff
                            val b2 = wav[p + 2].toInt() // sign-extended
                            val s24 = (b2 shl 16) or (b1 shl 8) or b0
                            s24.toDouble() / 8_388_608.0
                        }
                        else -> leInt(wav, p).toDouble() / 2_147_483_648.0
                    }
                    sum += normalized
                }
                val avg = (sum / channels * 32_767.0).roundToInt()
                avg.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            val pcm16k = resampleToMono16k(monoNative, sampleRate, channels = 1)
            val durationMs = frameCount * 1000L / sampleRate
            return DecodedAudio(
                pcm16kMono = pcm16k,
                originalSampleRate = sampleRate,
                originalChannels = channels,
                bitsPerSample = bitsPerSample,
                durationMs = durationMs
            )
        }

        /** True when [prefix] begins with a RIFF/WAVE file signature. */
        fun hasWavHeader(prefix: ByteArray, length: Int = prefix.size): Boolean =
            length >= 12 && ascii(prefix, 0, 4) == "RIFF" && ascii(prefix, 8, 4) == "WAVE"

        private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
            String(bytes, offset, length, Charsets.US_ASCII)

        private fun leUShort(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun leInt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }
}
