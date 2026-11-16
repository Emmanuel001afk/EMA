package com.ema.assistant

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * EMA's studio-grade on-device speech DSP front-end, 3-band phase-coherent
 * adaptive Wiener noise suppressor, multi-cue voice-activity detector,
 * cadence- and hesitation-aware smart utterance endpointer, audio quality
 * diagnostic analyzer, anti-aliased multi-rate resampler and universal
 * RIFF/WAVE decoder (PCM 8/16/24/32-bit, IEEE 32/64-bit float, G.711 A-law
 * and μ-law, plus WAVE_FORMAT_EXTENSIBLE).
 *
 * Pure Kotlin: zero Android framework calls, zero per-sample heap allocations,
 * zero network and zero disk writes. Every stage is deterministic from PCM
 * samples and covered by pure-JVM unit tests.
 */
class EmaSpeechAudioProcessor(
    val config: Config = Config()
) {

    data class Config(
        val sampleRate: Int = TARGET_SAMPLE_RATE,
        val enableHighPass: Boolean = true,
        val enableNoiseSuppression: Boolean = true,
        val enableAgc: Boolean = true,
        val minSpeechRms: Double = 140.0,
        val snrMultiplier: Double = 2.65,
        val targetSpeechRms: Double = 3_600.0,
        val minGain: Float = 0.55f,
        val maxGain: Float = 3.8f,
        val preRollMs: Int = 360,
        val onsetFrames: Int = 2,
        val minSpeechMs: Long = 200L,
        val noSpeechTimeoutMs: Long = 6_500L,
        val completeSilenceMs: Long = 1_050L,
        val snappyCommandSilenceMs: Long = 720L,
        val incompleteClauseBonusMs: Long = 750L,
        val hesitationBonusMs: Long = 950L,
        val maxDurationMs: Long = 28_000L
    ) {
        companion object {
            fun forProfile(
                profile: EmaRecognitionProfile,
                enableAgc: Boolean = true,
                enableNoiseSuppression: Boolean = true,
                minSpeechMsOverride: Long? = null,
                completeSilenceMsOverride: Long? = null,
                possiblyCompleteSilenceMsOverride: Long? = null,
                noSpeechTimeoutMsOverride: Long? = null,
                maxDurationMsOverride: Long? = null
            ): Config {
                val base = when (profile) {
                    EmaRecognitionProfile.COMMAND -> Config(
                        enableNoiseSuppression = enableNoiseSuppression,
                        enableAgc = enableAgc,
                        minSpeechMs = 200L,
                        noSpeechTimeoutMs = 6_500L,
                        completeSilenceMs = 1_050L,
                        snappyCommandSilenceMs = 720L,
                        incompleteClauseBonusMs = 750L,
                        hesitationBonusMs = 950L,
                        maxDurationMs = 28_000L
                    )
                    EmaRecognitionProfile.CONFIRMATION -> Config(
                        enableNoiseSuppression = enableNoiseSuppression,
                        enableAgc = enableAgc,
                        minSpeechMs = 140L,
                        noSpeechTimeoutMs = 5_500L,
                        completeSilenceMs = 640L,
                        snappyCommandSilenceMs = 540L,
                        incompleteClauseBonusMs = 600L,
                        hesitationBonusMs = 750L,
                        maxDurationMs = 12_000L
                    )
                    EmaRecognitionProfile.WEB_SEARCH -> Config(
                        enableNoiseSuppression = enableNoiseSuppression,
                        enableAgc = enableAgc,
                        minSpeechMs = 180L,
                        noSpeechTimeoutMs = 6_000L,
                        completeSilenceMs = 900L,
                        snappyCommandSilenceMs = 750L,
                        incompleteClauseBonusMs = 650L,
                        hesitationBonusMs = 850L,
                        maxDurationMs = 20_000L
                    )
                    EmaRecognitionProfile.DICTATION -> Config(
                        enableNoiseSuppression = enableNoiseSuppression,
                        enableAgc = enableAgc,
                        minSpeechMs = 200L,
                        noSpeechTimeoutMs = 10_000L,
                        completeSilenceMs = 2_200L,
                        snappyCommandSilenceMs = 2_000L,
                        incompleteClauseBonusMs = 950L,
                        hesitationBonusMs = 1_100L,
                        maxDurationMs = 90_000L
                    )
                }
                val silenceOverride = completeSilenceMsOverride?.takeIf { it > 0L }
                    ?: possiblyCompleteSilenceMsOverride?.takeIf { it > 0L }?.let { (it * 5L) / 4L }
                val effectiveComplete = silenceOverride?.coerceIn(450L, 12_000L) ?: base.completeSilenceMs
                return base.copy(
                    minSpeechMs = minSpeechMsOverride?.takeIf { it > 0L }?.coerceIn(80L, 10_000L) ?: base.minSpeechMs,
                    completeSilenceMs = effectiveComplete,
                    snappyCommandSilenceMs = if (silenceOverride != null) effectiveComplete else base.snappyCommandSilenceMs,
                    noSpeechTimeoutMs = noSpeechTimeoutMsOverride?.takeIf { it > 0L }?.coerceIn(1_500L, 30_000L)
                        ?: base.noSpeechTimeoutMs,
                    maxDurationMs = maxDurationMsOverride?.takeIf { it > 0L }?.coerceIn(3_000L, 180_000L)
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
        val gain: Float,
        /** Instantaneous speech-to-noise ratio estimate in dB. */
        val estimatedSnrDb: Float = 0f
    )

    /** End-of-utterance acoustic quality summary for diagnostics and honest error hints. */
    data class AudioQuality(
        val peakAmplitude: Int,
        val clippedRatio: Float,
        val averageSpeechRms: Double,
        val backgroundRms: Double,
        val estimatedSnrDb: Float,
        val voicedDurationMs: Long,
        val elapsedDurationMs: Long,
        val appliedGain: Float
    ) {
        /**
         * Specific, honest guidance when recognition yields no speech/match due
         * to an observable physical audio problem, or null when audio was healthy.
         */
        fun diagnosticHint(): String? = when {
            elapsedDurationMs >= 800L && peakAmplitude < 85 ->
                "The microphone signal was silent. Check if a connected headset is muted or blocked."
            clippedRatio > 0.06f ->
                "The audio was distorted from clipping. Hold the microphone slightly further away and try again."
            voicedDurationMs > 0L && backgroundRms > 1_100.0 && estimatedSnrDb < 4.0f ->
                "Background noise was very loud. Speak a little closer to the microphone and try again."
            voicedDurationMs > 0L && averageSpeechRms in 1.0..240.0 ->
                "Your voice came through very softly. Speak a little louder or closer to the microphone."
            else -> null
        }
    }

    // 75 Hz high-pass filter state (DC offset & mic handling rumble)
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f

    // Exact-reconstruction 3-band filterbank state (low <310 Hz, mid 310-3550 Hz, high >3550 Hz)
    private var lpLowState = 0f
    private var lpHighState = 0f

    // Per-band adaptive noise variance & smoothed Wiener suppression gains
    private var noiseLowVar = 1_200.0
    private var noiseMidVar = 900.0
    private var noiseHighVar = 600.0
    private var bandGainLow = 1.0f
    private var bandGainMid = 1.0f
    private var bandGainHigh = 1.0f
    private var unvoicedNoiseFrames = 0

    // AGC & meter state
    private var currentGain = 1.0f
    private var smoothedRmsDb = -2.0f
    private var lastSnrDb = 0f

    // Quality telemetry accumulators
    private var peakRawAbs = 0
    private var clippedSampleCount = 0L
    private var speechRmsSum = 0.0
    private var speechFrameCount = 0

    // Adaptive VAD & smart endpointing state
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
    private var hesitationTail = false
    private var snappyCommand = false
    private var decoderHeardWords = false
    private var lastKnownTranscript = ""

    /** Adaptive background RMS estimate. */
    val backgroundRms: Double get() = noiseFloor

    /** Current AGC gain factor. */
    val activeGain: Float get() = currentGain

    /** Most recent speech-to-noise ratio estimate in dB. */
    val estimatedSnrDb: Float get() = lastSnrDb

    /** Whether speech onset has been confirmed in this session. */
    val hasSpeechStarted: Boolean get() = speechStarted

    /** Total milliseconds of voiced speech accumulated so far. */
    val voicedDurationMs: Long get() = voicedSamples * 1000L / config.sampleRate

    /** Total elapsed audio duration processed so far in milliseconds. */
    val elapsedDurationMs: Long get() = totalSamples * 1000L / config.sampleRate

    /** Snapshot end-of-turn signal diagnostics. */
    fun qualitySummary(): AudioQuality {
        val avgSpeech = if (speechFrameCount > 0) speechRmsSum / speechFrameCount else 0.0
        val snr = if (avgSpeech > 1.0 && noiseFloor > 1.0) {
            (20.0 * log10(avgSpeech / noiseFloor)).toFloat().coerceIn(-10f, 50f)
        } else {
            lastSnrDb
        }
        val clipRatio = if (totalSamples > 0L) {
            (clippedSampleCount.toDouble() / totalSamples.toDouble()).toFloat()
        } else {
            0f
        }
        return AudioQuality(
            peakAmplitude = peakRawAbs,
            clippedRatio = clipRatio,
            averageSpeechRms = avgSpeech,
            backgroundRms = noiseFloor,
            estimatedSnrDb = snr,
            voicedDurationMs = voicedDurationMs,
            elapsedDurationMs = elapsedDurationMs,
            appliedGain = currentGain
        )
    }

    /** Reset all filter, denoiser, AGC, VAD, quality and endpointer state. */
    fun reset() {
        hpPrevIn = 0f
        hpPrevOut = 0f
        lpLowState = 0f
        lpHighState = 0f
        noiseLowVar = 1_200.0
        noiseMidVar = 900.0
        noiseHighVar = 600.0
        bandGainLow = 1.0f
        bandGainMid = 1.0f
        bandGainHigh = 1.0f
        unvoicedNoiseFrames = 0
        currentGain = 1.0f
        smoothedRmsDb = -2.0f
        lastSnrDb = 0f
        peakRawAbs = 0
        clippedSampleCount = 0L
        speechRmsSum = 0.0
        speechFrameCount = 0
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
        hesitationTail = false
        snappyCommand = false
        decoderHeardWords = false
        lastKnownTranscript = ""
    }

    /**
     * Reset only the utterance endpointer while preserving calibrated room-noise
     * estimates and filter states — used for seamless multi-segment sessions.
     */
    fun resetForNextSegment() {
        preRoll.clear()
        preRollSamples = 0
        consecutiveVoicedFrames = 0
        speechStarted = false
        totalSamples = 0L
        voicedSamples = 0L
        trailingSilenceSamples = 0L
        continuousVoicedWithoutTextSamples = 0L
        incompleteClause = false
        hesitationTail = false
        snappyCommand = false
        decoderHeardWords = false
        lastKnownTranscript = ""
    }

    /**
     * Inform the endpointer of the decoder's current accumulated transcript.
     *
     * Adjusts endpointing dynamically:
     *  - Crisp complete 1-2 word commands finish faster ([Config.snappyCommandSilenceMs]).
     *  - Open clauses receive [Config.incompleteClauseBonusMs] extra patience.
     *  - Spoken hesitations ("um", "uh", "wait") receive [Config.hesitationBonusMs].
     *  - Whispered speech recognized by the decoder confirms onset immediately.
     */
    fun onDecoderTranscript(transcript: String): Boolean {
        val cleaned = transcript.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return false
        hesitationTail = isHesitationTail(cleaned)
        incompleteClause = !hesitationTail && isIncompleteClause(cleaned)
        snappyCommand = !hesitationTail && !incompleteClause && isSnappyShortCommand(cleaned)
        if (cleaned == lastKnownTranscript) return false
        lastKnownTranscript = cleaned
        decoderHeardWords = true
        continuousVoicedWithoutTextSamples = 0L
        trailingSilenceSamples = 0L
        if (!speechStarted) {
            speechStarted = true
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
                gain = currentGain,
                estimatedSnrDb = lastSnrDb
            )
        }

        // 1. High-pass filter + exact-reconstruction 3-band split (low + mid + high === y).
        val lowBand = FloatArray(sampleCount)
        val midBand = FloatArray(sampleCount)
        val highBand = FloatArray(sampleCount)
        var lowSq = 0.0
        var midSq = 0.0
        var highSq = 0.0
        var fullSq = 0.0
        var zeroCrossings = 0
        var prevSign = 0

        for (i in 0 until sampleCount) {
            val lo = pcmLe[i * 2].toInt() and 0xff
            val hi = pcmLe[i * 2 + 1].toInt()
            val rawShort = ((hi shl 8) or lo).toShort().toInt()
            val rawAbs = abs(rawShort)
            if (rawAbs > peakRawAbs) peakRawAbs = rawAbs
            if (rawAbs >= 32_600) clippedSampleCount++

            val raw = rawShort.toFloat()
            val y = if (config.enableHighPass) {
                val out = HP_ALPHA * (hpPrevOut + raw - hpPrevIn)
                hpPrevIn = raw
                hpPrevOut = out
                out
            } else {
                raw
            }

            // Exact algebraic 3-band split: low + mid + high == y.
            lpLowState += LP_310_COEFF * (y - lpLowState)
            lpHighState += LP_3550_COEFF * (y - lpHighState)
            val bLow = lpLowState
            val bMid = lpHighState - lpLowState
            val bHigh = y - lpHighState

            lowBand[i] = bLow
            midBand[i] = bMid
            highBand[i] = bHigh

            lowSq += bLow.toDouble() * bLow.toDouble()
            midSq += bMid.toDouble() * bMid.toDouble()
            highSq += bHigh.toDouble() * bHigh.toDouble()
            fullSq += y.toDouble() * y.toDouble()

            val sign = when {
                y > 24f -> 1
                y < -24f -> -1
                else -> 0
            }
            if (sign != 0) {
                if (prevSign != 0 && sign != prevSign) zeroCrossings++
                prevSign = sign
            }
        }

        val powLow = lowSq / sampleCount
        val powMid = midSq / sampleCount
        val powHigh = highSq / sampleCount
        val rms = sqrt(fullSq / sampleCount)
        val voiceBandRms = sqrt(powMid)
        val voiceBandRatio = if (rms > 1.0) voiceBandRms / rms else 0.0
        val zcr = if (sampleCount > 1) zeroCrossings.toDouble() / (sampleCount - 1) else 0.0

        // 2. Multi-cue adaptive Voice Activity Detection (immune to pure low-band hum).
        val voicedThreshold = max(config.minSpeechRms, noiseFloor * config.snrMultiplier)
        val midNoiseRms = sqrt(max(noiseMidVar, 1.0))
        val midSpeechStrong = voiceBandRms >= max(config.minSpeechRms * 0.72, midNoiseRms * 2.75)
        val fricativeThreshold = max(config.minSpeechRms * 0.65, noiseFloor * max(2.05, config.snrMultiplier * 0.76))

        val isVowelOrSonorant = zcr in 0.007..0.55 && (
            (rms >= voicedThreshold && (voiceBandRatio >= 0.27 || rms >= max(config.minSpeechRms * 2.3, noiseFloor * 4.6))) ||
                (midSpeechStrong && voiceBandRatio >= 0.30)
            )
        val isFricative = rms >= fricativeThreshold && zcr in 0.16..0.70
        val voiced = isVowelOrSonorant || isFricative

        // Update broadband & per-band noise estimates during unvoiced audio.
        if (!voiced) {
            val blend = if (unvoicedNoiseFrames < 6) 0.28 else if (rms < noiseFloor) 0.12 else 0.025
            noiseFloor = (noiseFloor * (1.0 - blend) + rms * blend).coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
            noiseLowVar = (noiseLowVar * (1.0 - blend) + powLow * blend).coerceIn(25.0, 2.5e7)
            noiseMidVar = (noiseMidVar * (1.0 - blend) + powMid * blend).coerceIn(25.0, 2.0e7)
            noiseHighVar = (noiseHighVar * (1.0 - blend) + powHigh * blend).coerceIn(16.0, 1.5e7)
            unvoicedNoiseFrames++
            continuousVoicedWithoutTextSamples = 0L
        } else {
            speechRmsSum += rms
            speechFrameCount++
            continuousVoicedWithoutTextSamples += sampleCount
            // Absorb persistent steady drone (fan, generator, engine) after > 6 s with no decoded words.
            if (continuousVoicedWithoutTextSamples > config.sampleRate * 6L) {
                noiseFloor = (noiseFloor * 0.98 + rms * 0.02).coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
                noiseLowVar = (noiseLowVar * 0.97 + powLow * 0.03).coerceIn(25.0, 2.5e7)
            }
        }

        // 3. Phase-coherent 3-band soft Wiener noise suppression (active once noise profile settles).
        var cleanSq = 0.0
        val clean = FloatArray(sampleCount)
        if (config.enableNoiseSuppression && unvoicedNoiseFrames >= 3) {
            val targetLow = wienerGain(powLow, noiseLowVar, overSub = 1.35, floor = 0.22f)
            val targetMid = wienerGain(powMid, noiseMidVar, overSub = 0.60, floor = 0.68f)
            val targetHigh = wienerGain(powHigh, noiseHighVar, overSub = 1.15, floor = 0.32f)
            bandGainLow = smoothBandGain(bandGainLow, targetLow)
            bandGainMid = smoothBandGain(bandGainMid, targetMid)
            bandGainHigh = smoothBandGain(bandGainHigh, targetHigh)
            val gL = bandGainLow
            val gM = bandGainMid
            val gH = bandGainHigh
            for (i in 0 until sampleCount) {
                val c = gL * lowBand[i] + gM * midBand[i] + gH * highBand[i]
                clean[i] = c
                cleanSq += c.toDouble() * c.toDouble()
            }
        } else {
            for (i in 0 until sampleCount) {
                val c = lowBand[i] + midBand[i] + highBand[i]
                clean[i] = c
                cleanSq += c.toDouble() * c.toDouble()
            }
        }
        val cleanRms = sqrt(cleanSq / sampleCount)

        // 4. Speech-gated smooth AGC + C1 soft-knee limiting.
        if (config.enableAgc) {
            if (voiced && cleanRms in 160.0..22_000.0 && rms >= noiseFloor * 2.2) {
                val desired = (config.targetSpeechRms / cleanRms).toFloat().coerceIn(config.minGain, config.maxGain)
                val speed = if (desired < currentGain) 0.28f else 0.09f
                currentGain += (desired - currentGain) * speed
            } else if (!voiced) {
                currentGain = currentGain * 0.992f + 0.008f
            }
        } else {
            currentGain = 1.0f
        }

        val processed = ByteArray(byteCount)
        val gain = currentGain
        for (i in 0 until sampleCount) {
            val s = softLimit(clean[i] * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            processed[i * 2] = (s and 0xff).toByte()
            processed[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }

        // 5. Calibrated SpeechRecognizer RMS dB [-2, 10] and instantaneous SNR dB.
        val floorRef = max(noiseFloor, 40.0)
        lastSnrDb = if (rms <= 1.0) -6f else (20.0 * log10(rms / floorRef)).toFloat().coerceIn(-10f, 50f)
        val rawDb = if (rms <= 1.0) -2.0f else (10.0 * log10(rms / floorRef) - 2.0).toFloat().coerceIn(-2.0f, 10.0f)
        val dbAlpha = if (rawDb > smoothedRmsDb) 0.45f else 0.20f
        smoothedRmsDb += (rawDb - smoothedRmsDb) * dbAlpha

        // 6. Onset confirmation, pre-roll flush & cadence/hesitation-aware smart endpointing.
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
                    gain = currentGain,
                    estimatedSnrDb = lastSnrDb
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
                gain = currentGain,
                estimatedSnrDb = lastSnrDb
            )
        }

        if (voiced) {
            voicedSamples += sampleCount
            trailingSilenceSamples = 0L
        } else {
            trailingSilenceSamples += sampleCount
        }

        val voicedMs = voicedSamples * 1000L / config.sampleRate
        val silenceMs = trailingSilenceSamples * 1000L / config.sampleRate
        val requiredSilenceMs = when {
            hesitationTail -> config.completeSilenceMs + config.hesitationBonusMs
            incompleteClause -> config.completeSilenceMs + config.incompleteClauseBonusMs
            snappyCommand -> config.snappyCommandSilenceMs
            else -> config.completeSilenceMs
        }

        val chunks = listOf(processed)
        if (elapsedMs >= config.maxDurationMs) {
            return FrameDecision(Action.MAX_DURATION_REACHED, chunks, false, voiced, smoothedRmsDb, currentGain, lastSnrDb)
        }
        if ((voicedMs >= config.minSpeechMs || decoderHeardWords) && silenceMs >= requiredSilenceMs) {
            return FrameDecision(Action.ENDPOINT_COMPLETE, chunks, false, voiced, smoothedRmsDb, currentGain, lastSnrDb)
        }
        return FrameDecision(Action.DECODE, chunks, false, voiced, smoothedRmsDb, currentGain, lastSnrDb)
    }

    /**
     * Stateful O(1)-memory streaming conditioner for long offline file/URI
     * transcription (applies continuous HPF, 3-band Wiener denoising, smooth
     * speech-gated AGC and soft-knee limiting without buffering whole files).
     */
    fun conditionStreamChunk(samples16kMono: ShortArray, count: Int = samples16kMono.size): ByteArray {
        val n = min(count, samples16kMono.size).coerceAtLeast(0)
        if (n == 0) return ByteArray(0)
        val out = ByteArray(n * 2)
        val frame = min(512, n) // 32 ms sub-blocks for adaptive denoiser & AGC tracking
        var pos = 0
        while (pos < n) {
            val take = min(frame, n - pos)
            val lowB = FloatArray(take)
            val midB = FloatArray(take)
            val highB = FloatArray(take)
            var lowSq = 0.0
            var midSq = 0.0
            var highSq = 0.0
            var fullSq = 0.0
            for (i in 0 until take) {
                val raw = samples16kMono[pos + i].toFloat()
                val y = if (config.enableHighPass) {
                    val o = HP_ALPHA * (hpPrevOut + raw - hpPrevIn)
                    hpPrevIn = raw
                    hpPrevOut = o
                    o
                } else raw
                lpLowState += LP_310_COEFF * (y - lpLowState)
                lpHighState += LP_3550_COEFF * (y - lpHighState)
                val bL = lpLowState
                val bM = lpHighState - lpLowState
                val bH = y - lpHighState
                lowB[i] = bL; midB[i] = bM; highB[i] = bH
                lowSq += bL.toDouble() * bL.toDouble()
                midSq += bM.toDouble() * bM.toDouble()
                highSq += bH.toDouble() * bH.toDouble()
                fullSq += y.toDouble() * y.toDouble()
            }
            val pL = lowSq / take
            val pM = midSq / take
            val pH = highSq / take
            val rms = sqrt(fullSq / take)
            val isSpeech = sqrt(pM) >= max(config.minSpeechRms * 0.72, sqrt(max(noiseMidVar, 1.0)) * 2.5) ||
                rms >= max(config.minSpeechRms * 1.4, noiseFloor * 2.8)
            if (!isSpeech) {
                val blend = if (unvoicedNoiseFrames < 6) 0.25 else 0.03
                noiseFloor = (noiseFloor * (1.0 - blend) + rms * blend).coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
                noiseLowVar = (noiseLowVar * (1.0 - blend) + pL * blend).coerceIn(25.0, 2.5e7)
                noiseMidVar = (noiseMidVar * (1.0 - blend) + pM * blend).coerceIn(25.0, 2.0e7)
                noiseHighVar = (noiseHighVar * (1.0 - blend) + pH * blend).coerceIn(16.0, 1.5e7)
                unvoicedNoiseFrames++
            }
            val useNr = config.enableNoiseSuppression && unvoicedNoiseFrames >= 3
            if (useNr) {
                bandGainLow = smoothBandGain(bandGainLow, wienerGain(pL, noiseLowVar, 1.35, 0.22f))
                bandGainMid = smoothBandGain(bandGainMid, wienerGain(pM, noiseMidVar, 0.60, 0.68f))
                bandGainHigh = smoothBandGain(bandGainHigh, wienerGain(pH, noiseHighVar, 1.15, 0.32f))
            } else {
                bandGainLow = 1f; bandGainMid = 1f; bandGainHigh = 1f
            }
            var cleanSq = 0.0
            val clean = FloatArray(take)
            for (i in 0 until take) {
                val c = bandGainLow * lowB[i] + bandGainMid * midB[i] + bandGainHigh * highB[i]
                clean[i] = c
                cleanSq += c.toDouble() * c.toDouble()
            }
            val cleanRms = sqrt(cleanSq / take)
            if (config.enableAgc && isSpeech && cleanRms in 150.0..22_000.0) {
                val desired = (config.targetSpeechRms / cleanRms).toFloat().coerceIn(config.minGain, config.maxGain)
                val speed = if (desired < currentGain) 0.30f else 0.10f
                currentGain += (desired - currentGain) * speed
            }
            val g = if (config.enableAgc) currentGain else 1f
            for (i in 0 until take) {
                val s = softLimit(clean[i] * g).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                val byteIdx = (pos + i) * 2
                out[byteIdx] = (s and 0xff).toByte()
                out[byteIdx + 1] = ((s shr 8) and 0xff).toByte()
            }
            pos += take
        }
        return out
    }

    /**
     * Condition a finite offline batch recording (already 16 kHz mono PCM16)
     * with DC/rumble removal, optional 3-band background hum/hiss reduction and
     * percentile-based RMS/headroom normalization.
     */
    fun conditionBatch(samples: ShortArray): ByteArray {
        if (samples.isEmpty()) return ByteArray(0)
        val filtered = FloatArray(samples.size)
        var prevIn = 0f
        var prevOut = 0f
        for (i in samples.indices) {
            val raw = samples[i].toFloat()
            filtered[i] = if (config.enableHighPass) {
                val o = HP_ALPHA * (prevOut + raw - prevIn)
                prevIn = raw
                prevOut = o
                o
            } else raw
        }

        // Apply 3-band stationary drone reduction when the recording includes quiet frames to profile.
        val window = min(samples.size, TARGET_SAMPLE_RATE / 25) // 40 ms
        if (config.enableNoiseSuppression && samples.size >= TARGET_SAMPLE_RATE / 2 && window >= 160) {
            val numWin = samples.size / window
            if (numWin >= 8) {
                val winRms = DoubleArray(numWin)
                val wLow = DoubleArray(numWin)
                val wMid = DoubleArray(numWin)
                val wHigh = DoubleArray(numWin)
                var lpL = 0f
                var lpH = 0f
                for (w in 0 until numWin) {
                    var lSq = 0.0; var mSq = 0.0; var hSq = 0.0; var fSq = 0.0
                    val base = w * window
                    for (j in 0 until window) {
                        val y = filtered[base + j]
                        lpL += LP_310_COEFF * (y - lpL)
                        lpH += LP_3550_COEFF * (y - lpH)
                        val bL = lpL; val bM = lpH - lpL; val bH = y - lpH
                        lSq += bL.toDouble() * bL.toDouble()
                        mSq += bM.toDouble() * bM.toDouble()
                        hSq += bH.toDouble() * bH.toDouble()
                        fSq += y.toDouble() * y.toDouble()
                    }
                    wLow[w] = lSq / window
                    wMid[w] = mSq / window
                    wHigh[w] = hSq / window
                    winRms[w] = sqrt(fSq / window)
                }
                // Estimate stationary noise from the quietest 20% of frames — only when
                // the recording shows real speech-vs-noise contrast (prevents touching pure tones).
                val order = (0 until numWin).sortedBy { winRms[it] }
                val quietCount = (numWin / 5).coerceAtLeast(2)
                val loudRef = winRms[order[(numWin * 8 / 10).coerceAtMost(numWin - 1)]]
                val quietRef = winRms[order[quietCount - 1]]
                if (quietRef > 30.0 && loudRef > quietRef * 1.45) {
                    var nL = 0.0; var nM = 0.0; var nH = 0.0
                    for (k in 0 until quietCount) {
                        val idx = order[k]
                        nL += wLow[idx]; nM += wMid[idx]; nH += wHigh[idx]
                    }
                    nL /= quietCount; nM /= quietCount; nH /= quietCount
                    var sL = 0f; var sH = 0f
                    var gL = 1f; var gM = 1f; var gH = 1f
                    for (w in 0 until numWin) {
                        gL = smoothBandGain(gL, wienerGain(wLow[w], nL, 1.35, 0.22f))
                        gM = smoothBandGain(gM, wienerGain(wMid[w], nM, 0.55, 0.72f))
                        gH = smoothBandGain(gH, wienerGain(wHigh[w], nH, 1.15, 0.34f))
                        val base = w * window
                        for (j in 0 until window) {
                            val y = filtered[base + j]
                            sL += LP_310_COEFF * (y - sL)
                            sH += LP_3550_COEFF * (y - sH)
                            filtered[base + j] = gL * sL + gM * (sH - sL) + gH * (y - sH)
                        }
                    }
                }
            }
        }

        // Measure 70th-percentile active-frame RMS for clean gain normalization.
        var peak = 0f
        var winSq = 0.0
        var winCount = 0
        val activeRms = ArrayList<Double>(samples.size / max(1, window) + 1)
        for (y in filtered) {
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
            val ref = activeRms[(activeRms.size * 7 / 10).coerceIn(0, activeRms.lastIndex)]
            val desired = (config.targetSpeechRms / ref).toFloat().coerceIn(config.minGain, config.maxGain)
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

    private fun wienerGain(bandPower: Double, noisePower: Double, overSub: Double, floor: Float): Float {
        if (noisePower <= 25.0 || bandPower <= 1.0) return 1.0f
        val postSnr = bandPower / noisePower
        if (postSnr >= 18.0) return 1.0f
        val raw = ((postSnr - overSub) / max(postSnr, 1.0)).toFloat()
        return raw.coerceIn(floor, 1.0f)
    }

    private fun smoothBandGain(current: Float, target: Float): Float {
        // Fast attack protects plosives/onsets; smooth release eliminates musical noise.
        val alpha = if (target > current) 0.58f else 0.22f
        return current + (target - current) * alpha
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 16_000

        /** Maximum WAV/raw stream accepted for in-memory batch transcription (64 MB). */
        const val MAX_BATCH_BYTES = 64 * 1024 * 1024

        // ~75 Hz 1st-order high-pass at 16 kHz: 1 / (1 + 2*pi*75/16000)
        private const val HP_ALPHA = 0.9714f

        // Exact-reconstruction 1-pole low-pass step sizes: 1 - exp(-2*pi*fc/16000)
        private const val LP_310_COEFF = 0.1146f  // ~310 Hz split (rumble/generator/fan vs F1)
        private const val LP_3550_COEFF = 0.7520f // ~3550 Hz split (formants vs sibilance/hiss)

        private const val MIN_NOISE_FLOOR = 20.0
        private const val MAX_NOISE_FLOOR = 6_000.0

        private const val LIMIT_KNEE = 26_000f
        private const val LIMIT_HEADROOM = 6_700f

        private val HESITATION_WORDS = setOf(
            "uh", "uhh", "um", "umm", "erm", "er", "hmm", "hm", "wait"
        )

        private val HESITATION_BIGRAMS = setOf(
            "hold on", "let me", "i mean", "no wait"
        )

        /** Complete standalone 1-2 word commands that deserve instant endpointing. */
        private val SNAPPY_SINGLE_COMMANDS = setOf(
            "yes", "yeah", "yep", "sure", "ok", "okay", "no", "nope", "cancel",
            "stop", "pause", "resume", "continue", "mute", "unmute", "skip",
            "next", "previous", "back", "home", "help", "screenshot"
        )

        private val SNAPPY_TWO_WORD_COMMANDS = setOf(
            "go back", "go home", "recent apps", "open recents", "read screen",
            "inspect screen", "read notifications", "show notifications",
            "list reminders", "show files", "list files", "flashlight on",
            "flashlight off", "torch on", "torch off", "volume up", "volume down",
            "max volume", "volume max", "brightness up", "brightness down",
            "scroll up", "scroll down", "next track", "previous track",
            "close ema", "exit ema", "go ahead", "do it", "not now", "sim 1",
            "sim 2", "sim one", "sim two", "first one", "second one", "third one"
        )

        /** Words that strongly indicate the speaker paused mid-thought. */
        private val TRAILING_CONTINUATION_WORDS = setOf(
            "and", "then", "or", "but", "also", "plus", "because", "while", "until", "before", "after",
            "saying", "that", "to", "for", "on", "in", "at", "with", "from", "by", "about", "of",
            "into", "via", "using", "through", "called", "named", "titled", "between", "as",
            "a", "an", "the", "my", "our", "your", "his", "her", "this", "these", "those",
            "open", "launch", "play", "search", "find", "call", "dial", "phone", "ring",
            "send", "message", "text", "tell", "remind", "set", "turn", "switch", "enable", "disable",
            "increase", "decrease", "raise", "lower", "select", "choose", "pick", "tap", "click",
            "type", "write", "navigate", "remember", "convert", "calculate", "transcribe", "save",
            "point", "hundred", "thousand", "million", "minus", "times", "divided", "multiplied",
            "double", "triple", "comma"
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

        /** True when [transcript] trails off with a spoken hesitation ("um", "uh", "no wait"). */
        fun isHesitationTail(transcript: String): Boolean {
            val words = tokenizeForEndpoint(transcript)
            if (words.isEmpty()) return false
            if (words.last() in HESITATION_WORDS) return true
            if (words.size >= 2 && "${words[words.size - 2]} ${words.last()}" in HESITATION_BIGRAMS) return true
            return false
        }

        /** True when [transcript] is a self-contained 1-2 word command ready to fire quickly. */
        fun isSnappyShortCommand(transcript: String): Boolean {
            val words = tokenizeForEndpoint(transcript)
            return when (words.size) {
                1 -> words[0] in SNAPPY_SINGLE_COMMANDS
                2 -> "${words[0]} ${words[1]}" in SNAPPY_TWO_WORD_COMMANDS
                else -> false
            }
        }

        /** True when [transcript] ends with an unfinished clause or bare verb. */
        fun isIncompleteClause(transcript: String): Boolean {
            val words = tokenizeForEndpoint(transcript)
            if (words.isEmpty()) return false
            if (isHesitationTail(transcript)) return true
            val last = words.last()
            if (words.size >= 2) {
                val bigram = "${words[words.size - 2]} $last"
                if (bigram in TRAILING_CONTINUATION_BIGRAMS) return true
                if ((last == "on" || last == "off") && words.size >= 3 &&
                    words[words.size - 2] !in setOf("turn", "switch", "put", "log", "sign")
                ) {
                    return false
                }
                if ((last == "on" || last == "off") && words.size == 2 &&
                    words[0] in setOf("flashlight", "torch", "wifi", "bluetooth", "dnd")
                ) {
                    return false
                }
            }
            if (last == "play" && words.size == 1) return false
            return last in TRAILING_CONTINUATION_WORDS
        }

        private fun tokenizeForEndpoint(transcript: String): List<String> =
            transcript.lowercase()
                .replace(Regex("[^a-z0-9' ]+"), " ")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() && it != "[unk]" && it != "unk" }

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
         * Downsampling uses a Hann-windowed sinc low-pass kernel (cutoff 7.4 kHz)
         * to prevent high-frequency noise above Nyquist from aliasing into the
         * speech band; upsampling uses smooth cubic Hermite interpolation.
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

            if (sourceSampleRate == 48_000) {
                // Fast exact 3:1 symmetric FIR anti-aliasing decimator (cutoff ~7.4 kHz, linear phase).
                val taps = FIR_48K_TO_16K
                val half = taps.size / 2
                return ShortArray(outLength) { i ->
                    val center = i * 3 + 1
                    var acc = 0.0
                    for (k in taps.indices) {
                        val idx = (center + k - half).coerceIn(0, frames - 1)
                        acc += mono[idx] * taps[k]
                    }
                    acc.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }

            if (sourceSampleRate > TARGET_SAMPLE_RATE) {
                // Arbitrary ratio Hann-windowed sinc anti-aliasing downsampler (cutoff 0.925 * 8 kHz).
                val cutoff = 0.925 / ratio // cycles per input sample * 2
                val halfWidth = (ratio * 3.0).coerceIn(3.0, 24.0)
                return ShortArray(outLength) { i ->
                    val center = (i + 0.5) * ratio - 0.5
                    val first = (center - halfWidth).toInt().coerceIn(0, frames - 1)
                    val last = (center + halfWidth).toInt().coerceIn(first, frames - 1)
                    var acc = 0.0
                    var wSum = 0.0
                    for (idx in first..last) {
                        val d = idx - center
                        val x = PI * d * cutoff
                        val sinc = if (abs(x) < 1e-7) 1.0 else sin(x) / x
                        val win = 0.5 * (1.0 + cos(PI * (d / halfWidth).coerceIn(-1.0, 1.0)))
                        val w = sinc * win
                        acc += mono[idx] * w
                        wSum += w
                    }
                    val v = if (abs(wSum) > 1e-9) (acc / wSum).roundToInt() else mono[center.toInt().coerceIn(0, frames - 1)].toInt()
                    v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }

            // Upsampler (e.g. 8 kHz telephony / AMR-NB -> 16 kHz): Catmull-Rom cubic interpolation.
            return ShortArray(outLength) { i ->
                val pos = i * ratio
                val i1 = pos.toInt().coerceIn(0, frames - 1)
                val t = pos - i1
                val p0 = mono[(i1 - 1).coerceAtLeast(0)].toDouble()
                val p1 = mono[i1].toDouble()
                val p2 = mono[(i1 + 1).coerceAtMost(frames - 1)].toDouble()
                val p3 = mono[(i1 + 2).coerceAtMost(frames - 1)].toDouble()
                val v = 0.5 * (
                    2.0 * p1 +
                        (-p0 + p2) * t +
                        (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t * t +
                        (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t * t * t
                    )
                v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        // Normalized 13-tap Hann-windowed sinc low-pass for exact 48 kHz -> 16 kHz decimation.
        private val FIR_48K_TO_16K: DoubleArray = run {
            val half = 6
            val cutoff = 0.925 / 3.0
            val raw = DoubleArray(half * 2 + 1) { k ->
                val d = (k - half).toDouble()
                val x = PI * d * cutoff
                val sinc = if (abs(x) < 1e-9) 1.0 else sin(x) / x
                val win = 0.5 * (1.0 + cos(PI * d / (half + 1)))
                sinc * win
            }
            val sum = raw.sum()
            DoubleArray(raw.size) { raw[it] / sum }
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

        /** Convert raw PCM bytes (8/16/24/32-bit signed or IEEE 32-bit float) to PCM16 shorts. */
        fun decodeRawPcmToShorts(
            bytes: ByteArray,
            byteCount: Int,
            bitsPerSample: Int,
            isFloat: Boolean = false
        ): ShortArray {
            val usable = min(byteCount, bytes.size).coerceAtLeast(0)
            if (usable == 0) return ShortArray(0)
            if (isFloat && bitsPerSample == 32) {
                val n = usable / 4
                return ShortArray(n) { i ->
                    val f = Float.fromBits(leInt(bytes, i * 4))
                    val v = if (f.isFinite()) (f.coerceIn(-1f, 1f) * 32_767f).roundToInt() else 0
                    v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            return when (bitsPerSample) {
                8 -> ShortArray(usable) { i -> (((bytes[i].toInt() and 0xff) - 128) shl 8).toShort() }
                16 -> bytesToShorts(bytes, usable)
                24 -> {
                    val n = usable / 3
                    ShortArray(n) { i ->
                        val p = i * 3
                        val s24 = (bytes[p + 2].toInt() shl 16) or
                            ((bytes[p + 1].toInt() and 0xff) shl 8) or
                            (bytes[p].toInt() and 0xff)
                        (s24 shr 8).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
                32 -> {
                    val n = usable / 4
                    ShortArray(n) { i ->
                        (leInt(bytes, i * 4) shr 16).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
                else -> bytesToShorts(bytes, usable)
            }
        }

        /** ITU-T G.711 μ-law byte -> linear 16-bit PCM sample. */
        fun ulawToPcm16(byteVal: Byte): Short {
            val u = (byteVal.toInt().inv()) and 0xff
            val sign = u and 0x80
            val exponent = (u shr 4) and 0x07
            val mantissa = u and 0x0f
            var sample = ((mantissa shl 3) + 0x84) shl exponent
            sample -= 0x84
            return (if (sign != 0) -sample else sample)
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        /** ITU-T G.711 A-law byte -> linear 16-bit PCM sample. */
        fun alawToPcm16(byteVal: Byte): Short {
            val a = (byteVal.toInt() xor 0x55) and 0xff
            val sign = a and 0x80
            val exponent = (a shr 4) and 0x07
            val mantissa = a and 0x0f
            val sample = if (exponent == 0) {
                (mantissa shl 4) + 8
            } else {
                ((mantissa shl 4) + 0x108) shl (exponent - 1)
            }
            return (if (sign != 0) sample else -sample)
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
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
         * Parse a RIFF/WAVE byte array (PCM 8/16/24/32-bit, IEEE 32/64-bit float,
         * G.711 A-law/μ-law, or WAVE_FORMAT_EXTENSIBLE, mono or multichannel,
         * 4-192 kHz) into 16 kHz mono PCM16 samples.
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

            require(audioFormat in setOf(1, 3, 6, 7)) {
                "Unsupported WAV encoding ($audioFormat): expected PCM, IEEE float, A-law or μ-law"
            }
            require(channels in 1..8) { "Unsupported WAV channel count: $channels" }
            require(sampleRate in 4_000..192_000) { "Unsupported WAV sample rate: $sampleRate Hz" }
            require(dataOffset >= 0 && dataLength > 0) { "WAV file contains no audio samples" }

            val validPcm = audioFormat == 1 && bitsPerSample in setOf(8, 16, 24, 32)
            val validFloat = audioFormat == 3 && bitsPerSample in setOf(32, 64)
            val validLaw = (audioFormat == 6 || audioFormat == 7) && bitsPerSample == 8
            require(validPcm || validFloat || validLaw) { "Unsupported WAV bit depth: $bitsPerSample-bit" }

            val bytesPerSample = bitsPerSample / 8
            val frameSize = bytesPerSample * channels
            val frameCount = dataLength / frameSize
            require(frameCount > 0) { "WAV file contains no complete sample frames" }

            val monoNative = ShortArray(frameCount) { f ->
                var sum = 0.0
                val frameBase = dataOffset + f * frameSize
                for (c in 0 until channels) {
                    val p = frameBase + c * bytesPerSample
                    val normalized: Double = when {
                        audioFormat == 6 -> alawToPcm16(wav[p]).toDouble() / 32_768.0
                        audioFormat == 7 -> ulawToPcm16(wav[p]).toDouble() / 32_768.0
                        audioFormat == 3 && bitsPerSample == 32 -> {
                            val fVal = Float.fromBits(leInt(wav, p))
                            if (fVal.isFinite()) fVal.toDouble().coerceIn(-1.0, 1.0) else 0.0
                        }
                        audioFormat == 3 -> {
                            val dVal = Double.fromBits(leLong(wav, p))
                            if (dVal.isFinite()) dVal.coerceIn(-1.0, 1.0) else 0.0
                        }
                        bitsPerSample == 8 -> ((wav[p].toInt() and 0xff) - 128) / 128.0
                        bitsPerSample == 16 -> {
                            val s = ((wav[p + 1].toInt() shl 8) or (wav[p].toInt() and 0xff)).toShort()
                            s.toDouble() / 32_768.0
                        }
                        bitsPerSample == 24 -> {
                            val b0 = wav[p].toInt() and 0xff
                            val b1 = wav[p + 1].toInt() and 0xff
                            val b2 = wav[p + 2].toInt()
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

        private fun leLong(bytes: ByteArray, offset: Int): Long =
            (leInt(bytes, offset).toLong() and 0xffff_ffffL) or
                (leInt(bytes, offset + 4).toLong() shl 32)
    }
}
