package com.ema.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Structured output of EMA's offline speech-to-text engine.
 */
data class EmaSpeechResult(
    val text: String,
    val hypotheses: List<String>,
    val confidenceScores: FloatArray,
    val rawHypotheses: List<String> = emptyList(),
    val words: List<EmaVoskJsonParser.WordTiming> = emptyList(),
    val durationMs: Long = 0L,
    val offline: Boolean = true,
    val inputRoute: String = "",
    val wordsPerMinute: Int = 0,
    val segmentCount: Int = 1,
    val mimeType: String = "",
    val audioQuality: EmaSpeechAudioProcessor.AudioQuality? = null
) {
    val primaryConfidence: Float get() = confidenceScores.firstOrNull() ?: 0f

    /** Populate a standard Android [SpeechRecognizer] / [RecognizerIntent] results [Bundle]. */
    fun toRecognitionBundle(): Bundle = Bundle().apply {
        val list = ArrayList(hypotheses.ifEmpty { if (text.isNotBlank()) listOf(text) else emptyList() })
        val scores = if (confidenceScores.size == list.size) {
            confidenceScores.copyOf()
        } else {
            FloatArray(list.size) { idx -> confidenceScores.getOrElse(idx) { 0.75f } }
        }
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, list)
        putStringArrayList(RecognizerIntent.EXTRA_RESULTS, ArrayList(list))
        putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, scores)
        putFloatArray(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, scores)
    }

    companion object {
        fun partialBundle(text: String, confidence: Float): Bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
            if (confidence > 0f) {
                putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(confidence.coerceIn(0.05f, 0.99f)))
            }
        }
    }
}

/**
 * EMA's own built-in offline speech-to-text and live speech recognition engine.
 *
 * Full on-device pipeline:
 *  1. [EmaAudioCaptureRouter]: exclusive transient assistant audio focus,
 *     Bluetooth BLE/SCO / USB / wired-headset mic selection, OEM-safe sample-rate
 *     fallback and hardware NS/AEC/AGC attachment — or streaming audio pipe /
 *     universal local file decoding via [EmaOfflineAudioDecoder] (`.wav`, `.m4a`,
 *     `.mp3`, `.ogg`, `.opus`, `.flac`, `.aac`, `.amr`, `.3gp`, `.webm`, `.mp4`).
 *  2. [EmaSpeechAudioProcessor]: 75 Hz high-pass rumble/DC filter, exact-reconstruction
 *     3-band adaptive Wiener noise suppressor, speech-gated smooth AGC with C1
 *     soft-knee limiter, Hann-windowed sinc anti-aliased resampling, multi-cue
 *     adaptive VAD, 360 ms pre-roll, cadence/hesitation-aware smart endpointing
 *     and audio-quality diagnostics (muted mic, clipping, low SNR).
 *  3. Shared warm [EmaOfflineSpeechModel] + lattice N-best decoding with word
 *     timings and MBR partial posteriors.
 *  4. [EmaUtteranceAssembler]: multi-segment accumulation, boundary-echo
 *     deduplication, pause-aware paragraph punctuation and Android 13/14
 *     continuous segmented-session delivery.
 *  5. [EmaSpeechRescorer] + [EmaInverseTextNormalizer] + [EmaPhoneticMatcher]:
 *     MBR/ROVER N-best consensus, verified owner speech corrections, spoken
 *     times/numbers/phones/emails/domains, disfluency & `"scratch that"` cleanup,
 *     phonetic slot grounding (apps, contacts, screen controls) and calibrated
 *     confidences.
 *
 * Privacy guarantee: PCM exists only inside active bounded buffers, is never
 * written to disk and is never transmitted over any network. Logs record only
 * shape via [EmaPrivacy].
 */
class EmaOfflineSpeechEngine(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        /** Invoked only when the bundled model is unpacking or loading cold. */
        fun onPreparing() {}
        fun onReadyForSpeech(params: Bundle) {}
        fun onBeginningOfSpeech() {}
        fun onRmsChanged(rmsdB: Float) {}
        fun onBufferReceived(buffer: ByteArray) {}
        fun onPartialResults(text: String, rawText: String, confidence: Float) {}
        /** Invoked for each finalized utterance when [Config.segmentedSession] is true. */
        fun onSegmentResults(result: EmaSpeechResult) {}
        /** Invoked once when a continuous [Config.segmentedSession] ends cleanly. */
        fun onEndOfSegmentedSession() {}
        fun onEndOfSpeech() {}
        fun onResults(result: EmaSpeechResult)
        fun onError(errorCode: Int, message: String)
    }

    data class Config(
        val profile: EmaRecognitionProfile = EmaRecognitionProfile.COMMAND,
        val languageTag: String? = null,
        val partialResults: Boolean = true,
        val maxResults: Int = 5,
        val deliverAudioBuffers: Boolean = false,
        val enableFormatting: Boolean = true,
        val maskOffensiveWords: Boolean = false,
        val segmentedSession: Boolean = false,
        val segmentedStopOnSilence: Boolean = false,
        val requestAudioFocus: Boolean = true,
        val biasingStrings: List<String> = emptyList(),
        val grammarPhrases: List<String> = emptyList(),
        val speechContext: EmaSpeechContext? = null,
        val minSpeechMs: Long? = null,
        val completeSilenceMs: Long? = null,
        val possiblyCompleteSilenceMs: Long? = null,
        val noSpeechTimeoutMs: Long? = null,
        val maxDurationMs: Long? = null,
        val audioSource: InputStream? = null,
        val audioSourceSampleRate: Int = EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE,
        val audioSourceChannels: Int = 1,
        val audioSourceEncoding: Int = AudioFormat.ENCODING_PCM_16BIT,
        val ignoreLanguageCheck: Boolean = false
    ) {
        companion object {
            const val EXTRA_PROFILE = "com.ema.assistant.extra.RECOGNITION_PROFILE"
            const val EXTRA_GRAMMAR_PHRASES = "com.ema.assistant.extra.GRAMMAR_PHRASES"
            private const val EXTRA_AUDIO_SOURCE_KEY = "android.speech.extra.AUDIO_SOURCE"
            private const val EXTRA_AUDIO_SOURCE_RATE_KEY = "android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE"
            private const val EXTRA_AUDIO_SOURCE_CHANNELS_KEY = "android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT"
            private const val EXTRA_AUDIO_SOURCE_ENCODING_KEY = "android.speech.extra.AUDIO_SOURCE_ENCODING"
            private const val EXTRA_GET_AUDIO_KEY = "android.speech.extra.GET_AUDIO"
            private const val EXTRA_SEGMENTED_SESSION_KEY = "android.speech.extra.SEGMENTED_SESSION"
            private const val EXTRA_ENABLE_FORMATTING_KEY = "android.speech.extra.ENABLE_FORMATTING"
            private const val EXTRA_MASK_OFFENSIVE_KEY = "android.speech.extra.MASK_OFFENSIVE_WORDS"

            /** Extract all standard Android 8–15 [RecognizerIntent] options safely. */
            @Suppress("DEPRECATION")
            fun fromIntent(intent: Intent?): Config {
                if (intent == null) return Config()
                val extras = intent.extras
                val lang = readLanguageTag(extras)
                val profileExtra = intent.getStringExtra(EXTRA_PROFILE)
                val langModel = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL)
                val segmentedKey = intent.getStringExtra(EXTRA_SEGMENTED_SESSION_KEY)
                val isSegmented = !segmentedKey.isNullOrBlank()
                val stopOnSilence = segmentedKey == RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS

                val formattingRaw = runCatching { extras?.get(EXTRA_ENABLE_FORMATTING_KEY) }.getOrNull()
                val enableFormatting = when (formattingRaw) {
                    is Boolean -> formattingRaw
                    is String -> formattingRaw.isNotBlank() && !formattingRaw.equals("none", ignoreCase = true)
                    else -> true
                }
                val wantsDictationFormatting = formattingRaw is String && formattingRaw.isNotBlank()
                val maskOffensive = intent.getBooleanExtra(EXTRA_MASK_OFFENSIVE_KEY, false)

                val profile = when {
                    !profileExtra.isNullOrBlank() -> EmaRecognitionProfile.fromKey(profileExtra)
                    langModel == RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH -> EmaRecognitionProfile.WEB_SEARCH
                    isSegmented || wantsDictationFormatting -> EmaRecognitionProfile.DICTATION
                    else -> EmaRecognitionProfile.COMMAND
                }

                val maxRes = intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5).coerceIn(1, 10)
                val partials = intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                val biasing = intent.getStringArrayListExtra(EmaSpeechRecognition.EXTRA_BIASING_STRINGS)
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.take(200)
                    .orEmpty()
                val grammar = intent.getStringArrayListExtra(EXTRA_GRAMMAR_PHRASES)
                    ?.map { it.trim().lowercase(Locale.ROOT) }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()

                val minLen = readDurationExtra(extras, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                val completeSil = readDurationExtra(extras, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
                val possibleSil = readDurationExtra(extras, RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS)
                val wantBuffers = intent.getBooleanExtra(EXTRA_GET_AUDIO_KEY, false)

                var stream: InputStream? = null
                var srcRate = EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE
                var srcChannels = 1
                var srcEncoding = AudioFormat.ENCODING_PCM_16BIT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val pfd = runCatching {
                        intent.getParcelableExtra(EXTRA_AUDIO_SOURCE_KEY, ParcelFileDescriptor::class.java)
                    }.getOrNull()
                    if (pfd != null) {
                        stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                        srcRate = intent.getIntExtra(EXTRA_AUDIO_SOURCE_RATE_KEY, EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE)
                            .coerceIn(4_000, 192_000)
                        srcChannels = intent.getIntExtra(EXTRA_AUDIO_SOURCE_CHANNELS_KEY, 1).coerceIn(1, 8)
                        srcEncoding = intent.getIntExtra(EXTRA_AUDIO_SOURCE_ENCODING_KEY, AudioFormat.ENCODING_PCM_16BIT)
                    }
                }

                return Config(
                    profile = profile,
                    languageTag = lang,
                    partialResults = partials,
                    maxResults = maxRes,
                    deliverAudioBuffers = wantBuffers,
                    enableFormatting = enableFormatting,
                    maskOffensiveWords = maskOffensive,
                    segmentedSession = isSegmented,
                    segmentedStopOnSilence = stopOnSilence,
                    biasingStrings = biasing,
                    grammarPhrases = grammar,
                    minSpeechMs = minLen,
                    completeSilenceMs = completeSil,
                    possiblyCompleteSilenceMs = possibleSil,
                    audioSource = stream,
                    audioSourceSampleRate = srcRate,
                    audioSourceChannels = srcChannels,
                    audioSourceEncoding = srcEncoding
                )
            }

            /** Read EXTRA_LANGUAGE whether the caller stored a BCP-47 String or a java.util.Locale. */
            @Suppress("DEPRECATION")
            fun readLanguageTag(extras: Bundle?): String? {
                if (extras == null) return null
                for (key in listOf(RecognizerIntent.EXTRA_LANGUAGE, RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)) {
                    val raw = runCatching { extras.get(key) }.getOrNull() ?: continue
                    val tag = when (raw) {
                        is String -> raw.trim()
                        is Locale -> raw.toLanguageTag()
                        else -> raw.toString().trim()
                    }
                    if (tag.isNotBlank() && !tag.equals("und", ignoreCase = true)) return tag
                }
                return null
            }

            @Suppress("DEPRECATION")
            private fun readDurationExtra(extras: Bundle?, key: String): Long? {
                val raw = runCatching { extras?.get(key) }.getOrNull() ?: return null
                return (raw as? Number)?.toLong()?.takeIf { it > 0L }
            }
        }
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val sessionSeq = AtomicLong(0L)

    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false
    @Volatile private var activeCapture: EmaAudioCaptureRouter.OpenedCapture? = null
    @Volatile private var activeStream: InputStream? = null
    @Volatile private var worker: Thread? = null

    val isRunning: Boolean get() = running.get()

    /** Begin recognizing asynchronously on a dedicated audio worker thread. */
    fun start(config: Config = Config()) {
        if (!running.compareAndSet(false, true)) {
            runCatching { config.audioSource?.close() }
            val msg = "Offline recognizer is already listening."
            mainHandler.post { listener.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, msg) }
            return
        }
        val token = sessionSeq.incrementAndGet()
        stopRequested = false
        cancelled = false
        activeStream = config.audioSource
        worker = Thread(
            { runSession(token, config) },
            "ema-offline-stt-$token"
        ).also { it.start() }
    }

    /**
     * Stop capturing new audio and flush the final transcript for everything
     * spoken so far (mirrors [SpeechRecognizer.stopListening]).
     */
    fun stopListening() {
        stopRequested = true
        runCatching { activeCapture?.recorder?.stop() }
    }

    /** Immediately abort recognition and discard any pending callbacks. */
    fun cancel() {
        cancelled = true
        stopRequested = true
        sessionSeq.incrementAndGet()
        running.set(false)
        runCatching { activeCapture?.recorder?.stop() }
        runCatching { activeStream?.close() }
        worker?.interrupt()
    }

    private fun runSession(token: Long, config: Config) {
        val usingStream = config.audioSource != null
        try {
            if (!usingStream &&
                appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                emitError(token, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, "Microphone permission is required.")
                return
            }

            val availability = EmaOfflineSpeechModel.checkAvailability(
                context = appContext,
                languageTag = config.languageTag,
                ignoreLanguage = config.ignoreLanguageCheck
            )
            if (!availability.available) {
                val code = if (!config.ignoreLanguageCheck && !EmaSpeechMode.supportsOfflineLanguage(config.languageTag)) {
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                } else {
                    SpeechRecognizer.ERROR_CLIENT
                }
                emitError(token, code, availability.reason)
                return
            }

            val baseContext = config.speechContext ?: EmaSpeechRecognition.speechContext(appContext)
            val speechContext = if (config.biasingStrings.isEmpty()) {
                baseContext
            } else {
                baseContext.copy(
                    installedApps = (baseContext.installedApps + config.biasingStrings).distinct()
                )
            }

            if (!isAlive(token)) return

            EmaOfflineSpeechModel.acquire(
                context = appContext,
                onPreparing = { emit(token) { listener.onPreparing() } }
            ).use { lease ->
                if (!isAlive(token)) return
                createRecognizer(lease.model, config).use { decoder ->
                    if (usingStream) {
                        recognizeStream(token, decoder, config, speechContext)
                    } else if (config.segmentedSession) {
                        recognizeSegmentedMicrophone(token, decoder, config, speechContext)
                    } else {
                        recognizeMicrophone(token, decoder, config, speechContext)
                    }
                }
            }
        } catch (_: InterruptedException) {
            // Cancelled cleanly.
        } catch (_: SecurityException) {
            emitError(token, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, "Microphone permission was revoked.")
        } catch (error: Throwable) {
            if (isAlive(token)) {
                android.util.Log.e("EMA", "Offline speech engine failed: ${error.javaClass.simpleName}", error)
                emitError(token, SpeechRecognizer.ERROR_CLIENT, error.message ?: "Offline speech error")
            }
        } finally {
            runCatching { config.audioSource?.close() }
            activeStream = null
            if (sessionSeq.get() == token) {
                running.set(false)
            }
            worker = null
        }
    }

    private fun recognizeMicrophone(
        token: Long,
        decoder: Recognizer,
        config: Config,
        speechContext: EmaSpeechContext
    ) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }

        if (EmaWakeWordService.isCapturing()) {
            EmaWakeWordService.pauseForSpeech()
            EmaWakeWordService.awaitCaptureReleased(650L)
        }
        if (!isAlive(token)) return

        val capture = EmaAudioCaptureRouter.openAndStart(appContext, requestFocus = config.requestAudioFocus)
        if (capture == null) {
            emitError(token, SpeechRecognizer.ERROR_AUDIO, "Microphone is busy or could not be opened.")
            return
        }
        activeCapture = capture

        var endSignalled = false
        try {
            val audio = capture.recorder
            val captureRate = capture.sampleRate
            val inputRoute = capture.inputRoute

            val processor = EmaSpeechAudioProcessor(
                EmaSpeechAudioProcessor.Config.forProfile(
                    profile = config.profile,
                    enableNoiseSuppression = true,
                    enableAgc = !capture.hardwareAgcActive,
                    minSpeechMsOverride = config.minSpeechMs,
                    completeSilenceMsOverride = config.completeSilenceMs,
                    possiblyCompleteSilenceMsOverride = config.possiblyCompleteSilenceMs,
                    noSpeechTimeoutMsOverride = config.noSpeechTimeoutMs,
                    maxDurationMsOverride = config.maxDurationMs
                )
            )
            val assembler = EmaUtteranceAssembler(
                maxAlternatives = maxOf(config.maxResults, 6),
                formatPausePunctuation = config.profile == EmaRecognitionProfile.DICTATION && config.enableFormatting
            )

            val readyBundle = Bundle().apply {
                putBoolean(EXTRA_ENGINE_OFFLINE, true)
                putString(EXTRA_INPUT_ROUTE, inputRoute)
                putString(RecognizerIntent.EXTRA_LANGUAGE, EmaSpeechMode.canonicalOfflineTag(config.languageTag))
            }
            emit(token) { listener.onReadyForSpeech(readyBundle) }

            val frameSamples = (captureRate * 32 / 1000).coerceAtLeast(256)
            val readBuffer = ByteArray(frameSamples * 2)
            val startedMs = SystemClock.elapsedRealtime()
            var speechAnnounced = false
            var lastRmsEmitMs = 0L
            var lastPartialQueryMs = 0L
            var lastEmittedPartial = ""
            var timedOutWithoutSpeech = false
            var consecutiveZeroReads = 0

            loop@ while (isAlive(token) && !stopRequested) {
                val read = audio.read(readBuffer, 0, readBuffer.size)
                if (read <= 0) {
                    if (stopRequested || !isAlive(token)) break
                    if (read == 0 && ++consecutiveZeroReads <= 5) continue
                    if (assembler.hasAnyText()) break
                    emitError(token, SpeechRecognizer.ERROR_AUDIO, "Microphone capture stopped ($read).")
                    return
                }
                consecutiveZeroReads = 0
                val evenBytes = read - (read % 2)
                if (evenBytes <= 0) continue

                val pcm16k = if (captureRate == EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE) {
                    readBuffer
                } else {
                    EmaSpeechAudioProcessor.resampleBytesToMono16k(readBuffer, evenBytes, captureRate, channels = 1)
                }
                val pcmLen = if (captureRate == EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE) evenBytes else pcm16k.size
                if (pcmLen <= 0) continue

                val decision = processor.process(pcm16k, pcmLen)
                val now = SystemClock.elapsedRealtime()

                if (now - lastRmsEmitMs >= RMS_INTERVAL_MS) {
                    lastRmsEmitMs = now
                    val db = decision.rmsDb
                    emit(token) { listener.onRmsChanged(db) }
                }

                if (decision.speechJustStarted && !speechAnnounced) {
                    speechAnnounced = true
                    emit(token) { listener.onBeginningOfSpeech() }
                }

                if (decision.action == EmaSpeechAudioProcessor.Action.TIMEOUT_NO_SPEECH) {
                    timedOutWithoutSpeech = true
                    break@loop
                }

                if (decision.audioToDecode.isNotEmpty()) {
                    var segmentCompleted = false
                    for (chunk in decision.audioToDecode) {
                        if (config.deliverAudioBuffers) {
                            val copy = chunk.copyOf()
                            emit(token) { listener.onBufferReceived(copy) }
                        }
                        if (decoder.acceptWaveForm(chunk, chunk.size)) {
                            val accumulated = assembler.onSegment(decoder.result)
                            if (accumulated.isNotEmpty()) {
                                segmentCompleted = true
                                val activated = processor.onDecoderTranscript(accumulated)
                                if (activated && !speechAnnounced) {
                                    speechAnnounced = true
                                    emit(token) { listener.onBeginningOfSpeech() }
                                }
                            }
                        }
                    }

                    if (segmentCompleted) {
                        if (config.partialResults) {
                            val preview = assembler.currentPreview()
                            val pretty = formatPreview(preview, config)
                            if (pretty.isNotEmpty() && pretty != lastEmittedPartial) {
                                lastEmittedPartial = pretty
                                emit(token) { listener.onPartialResults(pretty, preview, 0.80f) }
                            }
                        }
                    } else if (now - lastPartialQueryMs >= PARTIAL_INTERVAL_MS) {
                        lastPartialQueryMs = now
                        val partial = assembler.onPartial(decoder.partialResult)
                        if (partial.text.isNotEmpty()) {
                            val activated = processor.onDecoderTranscript(partial.text)
                            if (activated && !speechAnnounced) {
                                speechAnnounced = true
                                emit(token) { listener.onBeginningOfSpeech() }
                            }
                            if (config.partialResults) {
                                val pretty = formatPreview(partial.text, config)
                                if (pretty.isNotEmpty() && pretty != lastEmittedPartial) {
                                    lastEmittedPartial = pretty
                                    val conf = partial.confidence
                                    emit(token) { listener.onPartialResults(pretty, partial.text, conf) }
                                }
                            }
                        }
                    }
                }

                if (decision.action == EmaSpeechAudioProcessor.Action.ENDPOINT_COMPLETE ||
                    decision.action == EmaSpeechAudioProcessor.Action.MAX_DURATION_REACHED
                ) {
                    break@loop
                }
            }

            // Release microphone and audio focus immediately so follow-up turns or TTS never stall.
            val finalizeStartedMs = SystemClock.elapsedRealtime()
            capture.close()
            activeCapture = null

            if (!isAlive(token)) return

            if (speechAnnounced) {
                endSignalled = true
                emit(token) { listener.onEndOfSpeech() }
            }

            val quality = processor.qualitySummary()

            if (timedOutWithoutSpeech && !assembler.hasAnyText()) {
                val hint = quality.diagnosticHint()
                emitError(token, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, hint ?: "No speech detected.")
                return
            }

            if (speechAnnounced || assembler.hasAnyText()) {
                runCatching { decoder.acceptWaveForm(TRAILING_FLUSH_SILENCE, TRAILING_FLUSH_SILENCE.size) }
            }
            val finalJson = runCatching { decoder.finalResult }.getOrDefault("")
            val assembled = assembler.finish(finalJson)

            if (assembled.hypotheses.isEmpty()) {
                val code = if (speechAnnounced) SpeechRecognizer.ERROR_NO_MATCH else SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                val hint = quality.diagnosticHint()
                emitError(token, code, hint ?: if (speechAnnounced) "No words recognized." else "No speech detected.")
                return
            }

            if (EmaVoskJsonParser.isNoiseHallucination(assembled.hypotheses, processor.voicedDurationMs)) {
                val hint = quality.diagnosticHint()
                emitError(token, SpeechRecognizer.ERROR_NO_MATCH, hint ?: "Only background noise was heard.")
                return
            }

            val rescored = EmaSpeechRescorer.rescore(
                decoded = assembled.hypotheses,
                context = speechContext,
                profile = config.profile,
                maxResults = config.maxResults,
                enableFormatting = config.enableFormatting,
                maskOffensiveWords = config.maskOffensiveWords
            )
            val primary = rescored.primary
            if (primary.isNullOrBlank()) {
                emitError(token, SpeechRecognizer.ERROR_NO_MATCH, quality.diagnosticHint() ?: "No words recognized.")
                return
            }

            if (!endSignalled) {
                emit(token) { listener.onEndOfSpeech() }
            }
            val nowDone = SystemClock.elapsedRealtime()
            val durationMs = nowDone - startedMs
            EmaOfflineSpeechModel.recordTurnTelemetry(nowDone - finalizeStartedMs, inputRoute)

            val result = EmaSpeechResult(
                text = primary,
                hypotheses = rescored.hypotheses,
                confidenceScores = rescored.confidenceScores,
                rawHypotheses = rescored.rawHypotheses,
                words = assembled.hypotheses.first().words,
                durationMs = durationMs,
                offline = true,
                inputRoute = inputRoute,
                wordsPerMinute = assembled.estimatedWpm,
                segmentCount = assembled.segmentCount,
                audioQuality = quality
            )
            android.util.Log.i(
                "EMA",
                "Offline STT ok (${EmaPrivacy.describe(primary)}, alts=${result.hypotheses.size}, " +
                    "segs=${assembled.segmentCount}, route=$inputRoute, snr=${quality.estimatedSnrDb.toInt()}dB, ${durationMs}ms)"
            )
            emit(token) { listener.onResults(result) }
        } finally {
            activeCapture?.close()
            activeCapture = null
        }
    }

    /**
     * Continuous multi-utterance live capture (Android 13/14 `EXTRA_SEGMENTED_SESSION`):
     * delivers each completed sentence via [Listener.onSegmentResults] without
     * closing the microphone, keeping the noise floor adapted across pauses
     * until `stopListening()` or the configured session boundary is reached.
     */
    private fun recognizeSegmentedMicrophone(
        token: Long,
        decoder: Recognizer,
        config: Config,
        speechContext: EmaSpeechContext
    ) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        if (EmaWakeWordService.isCapturing()) {
            EmaWakeWordService.pauseForSpeech()
            EmaWakeWordService.awaitCaptureReleased(650L)
        }
        if (!isAlive(token)) return

        val capture = EmaAudioCaptureRouter.openAndStart(appContext, requestFocus = config.requestAudioFocus)
        if (capture == null) {
            emitError(token, SpeechRecognizer.ERROR_AUDIO, "Microphone is busy or could not be opened.")
            return
        }
        activeCapture = capture

        try {
            val audio = capture.recorder
            val captureRate = capture.sampleRate
            val processor = EmaSpeechAudioProcessor(
                EmaSpeechAudioProcessor.Config.forProfile(
                    profile = config.profile,
                    enableNoiseSuppression = true,
                    enableAgc = !capture.hardwareAgcActive,
                    completeSilenceMsOverride = config.completeSilenceMs ?: 1_300L,
                    possiblyCompleteSilenceMsOverride = config.possiblyCompleteSilenceMs ?: 950L,
                    noSpeechTimeoutMsOverride = config.noSpeechTimeoutMs ?: 12_000L,
                    maxDurationMsOverride = config.maxDurationMs ?: MAX_SEGMENTED_SESSION_MS
                )
            )
            val segAssembler = EmaUtteranceAssembler(maxAlternatives = maxOf(config.maxResults, 6))

            emit(token) {
                listener.onReadyForSpeech(
                    Bundle().apply {
                        putBoolean(EXTRA_ENGINE_OFFLINE, true)
                        putString(EXTRA_INPUT_ROUTE, capture.inputRoute)
                        putString(RecognizerIntent.EXTRA_LANGUAGE, EmaSpeechMode.canonicalOfflineTag(config.languageTag))
                    }
                )
            }

            val frameSamples = (captureRate * 32 / 1000).coerceAtLeast(256)
            val readBuffer = ByteArray(frameSamples * 2)
            val sessionStart = SystemClock.elapsedRealtime()
            var speechAnnounced = false
            var lastRmsMs = 0L
            var lastPartialMs = 0L
            var lastPartial = ""
            var emittedSegments = 0

            fun flushCurrentSegment() {
                runCatching { decoder.acceptWaveForm(TRAILING_FLUSH_SILENCE, TRAILING_FLUSH_SILENCE.size) }
                val assembled = segAssembler.finish(runCatching { decoder.finalResult }.getOrDefault(""))
                segAssembler.reset()
                lastPartial = ""
                runCatching { decoder.reset() }
                if (assembled.hypotheses.isEmpty()) return
                if (EmaVoskJsonParser.isNoiseHallucination(assembled.hypotheses, processor.voicedDurationMs)) return
                val rescored = EmaSpeechRescorer.rescore(
                    decoded = assembled.hypotheses,
                    context = speechContext,
                    profile = config.profile,
                    maxResults = config.maxResults,
                    enableFormatting = config.enableFormatting,
                    maskOffensiveWords = config.maskOffensiveWords
                )
                val primary = rescored.primary ?: return
                emittedSegments++
                val segResult = EmaSpeechResult(
                    text = primary,
                    hypotheses = rescored.hypotheses,
                    confidenceScores = rescored.confidenceScores,
                    rawHypotheses = rescored.rawHypotheses,
                    words = assembled.hypotheses.first().words,
                    durationMs = processor.voicedDurationMs,
                    offline = true,
                    inputRoute = capture.inputRoute,
                    wordsPerMinute = assembled.estimatedWpm,
                    segmentCount = emittedSegments
                )
                emit(token) { listener.onSegmentResults(segResult) }
            }

            while (isAlive(token) && !stopRequested &&
                SystemClock.elapsedRealtime() - sessionStart < MAX_SEGMENTED_SESSION_MS
            ) {
                val read = audio.read(readBuffer, 0, readBuffer.size)
                if (read <= 0) break
                val even = read - (read % 2)
                if (even <= 0) continue

                val pcm16k = if (captureRate == EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE) {
                    readBuffer
                } else {
                    EmaSpeechAudioProcessor.resampleBytesToMono16k(readBuffer, even, captureRate, channels = 1)
                }
                val pcmLen = if (captureRate == EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE) even else pcm16k.size
                if (pcmLen <= 0) continue

                val decision = processor.process(pcm16k, pcmLen)
                val now = SystemClock.elapsedRealtime()
                if (now - lastRmsMs >= RMS_INTERVAL_MS) {
                    lastRmsMs = now
                    val db = decision.rmsDb
                    emit(token) { listener.onRmsChanged(db) }
                }
                if (decision.speechJustStarted && !speechAnnounced) {
                    speechAnnounced = true
                    emit(token) { listener.onBeginningOfSpeech() }
                }
                for (chunk in decision.audioToDecode) {
                    if (config.deliverAudioBuffers) {
                        val copy = chunk.copyOf()
                        emit(token) { listener.onBufferReceived(copy) }
                    }
                    if (decoder.acceptWaveForm(chunk, chunk.size)) {
                        val acc = segAssembler.onSegment(decoder.result)
                        if (acc.isNotEmpty()) processor.onDecoderTranscript(acc)
                    } else if (config.partialResults && now - lastPartialMs >= PARTIAL_INTERVAL_MS) {
                        lastPartialMs = now
                        val p = segAssembler.onPartial(decoder.partialResult)
                        if (p.text.isNotEmpty()) {
                            processor.onDecoderTranscript(p.text)
                            val pretty = formatPreview(p.text, config)
                            if (pretty.isNotEmpty() && pretty != lastPartial) {
                                lastPartial = pretty
                                val conf = p.confidence
                                emit(token) { listener.onPartialResults(pretty, p.text, conf) }
                            }
                        }
                    }
                }

                when (decision.action) {
                    EmaSpeechAudioProcessor.Action.ENDPOINT_COMPLETE -> {
                        flushCurrentSegment()
                        processor.resetForNextSegment()
                        if (config.segmentedStopOnSilence) break
                    }
                    EmaSpeechAudioProcessor.Action.TIMEOUT_NO_SPEECH -> {
                        if (emittedSegments > 0 || config.segmentedStopOnSilence) break
                        processor.resetForNextSegment()
                    }
                    EmaSpeechAudioProcessor.Action.MAX_DURATION_REACHED -> break
                    EmaSpeechAudioProcessor.Action.WAIT_FOR_SPEECH,
                    EmaSpeechAudioProcessor.Action.DECODE -> Unit
                }
            }

            capture.close()
            activeCapture = null
            if (!isAlive(token)) return

            if (segAssembler.hasAnyText()) {
                flushCurrentSegment()
            }
            if (speechAnnounced) {
                emit(token) { listener.onEndOfSpeech() }
            }
            emit(token) { listener.onEndOfSegmentedSession() }
        } finally {
            activeCapture?.close()
            activeCapture = null
        }
    }

    /** Recognize audio supplied through an [InputStream] (API 33+ EXTRA_AUDIO_SOURCE or pipe). */
    private fun recognizeStream(
        token: Long,
        decoder: Recognizer,
        config: Config,
        speechContext: EmaSpeechContext
    ) {
        val rawStream = config.audioSource ?: run {
            emitError(token, SpeechRecognizer.ERROR_AUDIO, "Audio stream is missing.")
            return
        }
        val startedMs = SystemClock.elapsedRealtime()
        val buffered = BufferedInputStream(rawStream, 16_384)
        buffered.mark(16)
        val probe = ByteArray(12)
        var probeLen = 0
        while (probeLen < probe.size) {
            val r = buffered.read(probe, probeLen, probe.size - probeLen)
            if (r <= 0) break
            probeLen += r
        }
        buffered.reset()

        emit(token) {
            listener.onReadyForSpeech(Bundle().apply { putBoolean(EXTRA_ENGINE_OFFLINE, true) })
        }

        val streamConditioner = EmaSpeechAudioProcessor()
        val assembler = EmaUtteranceAssembler(
            maxAlternatives = maxOf(config.maxResults, 6),
            formatPausePunctuation = config.profile == EmaRecognitionProfile.DICTATION && config.enableFormatting
        )
        var speechAnnounced = false
        var lastPartial = ""
        var emittedSegCount = 0

        fun processMono16kChunk(mono16k: ShortArray) {
            if (mono16k.isEmpty() || !isAlive(token) || stopRequested) return
            if (!speechAnnounced) {
                speechAnnounced = true
                emit(token) { listener.onBeginningOfSpeech() }
            }
            val conditioned = streamConditioner.conditionStreamChunk(mono16k)
            if (config.deliverAudioBuffers && conditioned.isNotEmpty()) {
                val copy = conditioned.copyOf()
                emit(token) { listener.onBufferReceived(copy) }
            }
            val step = 6400 // 200 ms at 16 kHz PCM16 mono
            var offset = 0
            while (offset < conditioned.size && isAlive(token) && !stopRequested) {
                val len = min(step, conditioned.size - offset).let { it - (it % 2) }
                if (len <= 0) break
                val slice = conditioned.copyOfRange(offset, offset + len)
                if (decoder.acceptWaveForm(slice, slice.size)) {
                    val segHyps = assembler.onSegmentWithHypotheses(decoder.result)
                    if (config.segmentedSession && segHyps.isNotEmpty()) {
                        val segRescored = EmaSpeechRescorer.rescore(
                            decoded = segHyps,
                            context = speechContext,
                            profile = config.profile,
                            maxResults = config.maxResults,
                            enableFormatting = config.enableFormatting,
                            maskOffensiveWords = config.maskOffensiveWords
                        )
                        val segText = segRescored.primary
                        if (!segText.isNullOrBlank()) {
                            emittedSegCount++
                            val r = EmaSpeechResult(
                                text = segText,
                                hypotheses = segRescored.hypotheses,
                                confidenceScores = segRescored.confidenceScores,
                                rawHypotheses = segRescored.rawHypotheses,
                                words = segHyps.first().words,
                                offline = true,
                                segmentCount = emittedSegCount
                            )
                            emit(token) { listener.onSegmentResults(r) }
                        }
                    } else if (config.partialResults) {
                        val acc = assembler.currentPreview()
                        val pretty = formatPreview(acc, config)
                        if (pretty.isNotEmpty() && pretty != lastPartial) {
                            lastPartial = pretty
                            emit(token) { listener.onPartialResults(pretty, acc, 0.80f) }
                        }
                    }
                } else if (config.partialResults) {
                    val p = assembler.onPartial(decoder.partialResult)
                    if (p.text.isNotEmpty()) {
                        val pretty = formatPreview(p.text, config)
                        if (pretty.isNotEmpty() && pretty != lastPartial) {
                            lastPartial = pretty
                            val conf = p.confidence
                            emit(token) { listener.onPartialResults(pretty, p.text, conf) }
                        }
                    }
                }
                offset += len
            }
        }

        if (EmaSpeechAudioProcessor.hasWavHeader(probe, probeLen)) {
            val wav = EmaSpeechAudioProcessor.decodeWav(buffered)
            var pos = 0
            while (pos < wav.pcm16kMono.size && isAlive(token) && !stopRequested) {
                val end = min(pos + 4_000, wav.pcm16kMono.size)
                processMono16kChunk(wav.pcm16kMono.copyOfRange(pos, end))
                pos = end
            }
        } else {
            val (bits, isFloat) = encodingToBitsAndFloat(config.audioSourceEncoding)
            val bytesPerSample = (bits / 8).coerceAtLeast(1)
            val frameAlign = (bytesPerSample * config.audioSourceChannels).coerceAtLeast(1)
            val chunkBytes = ((config.audioSourceSampleRate * frameAlign) / 8)
                .coerceIn(frameAlign, 32_768)
                .let { it - (it % frameAlign) }
            val buf = ByteArray(chunkBytes)
            var totalBytes = 0L
            var carry = 0

            while (isAlive(token) && !stopRequested) {
                val n = buffered.read(buf, carry, buf.size - carry)
                if (n < 0) break
                val avail = carry + n
                val usable = avail - (avail % frameAlign)
                if (usable <= 0) {
                    carry = avail
                    continue
                }
                totalBytes += usable
                if (totalBytes > MAX_STREAM_PIPE_BYTES) break

                val nativeShorts = EmaSpeechAudioProcessor.decodeRawPcmToShorts(buf, usable, bits, isFloat)
                val rem = avail - usable
                if (rem > 0) System.arraycopy(buf, usable, buf, 0, rem)
                carry = rem

                if (nativeShorts.isEmpty()) continue
                val mono16k = EmaSpeechAudioProcessor.resampleToMono16k(
                    nativeShorts,
                    config.audioSourceSampleRate,
                    config.audioSourceChannels
                )
                processMono16kChunk(mono16k)
            }
        }

        if (!isAlive(token)) return
        emit(token) { listener.onEndOfSpeech() }

        runCatching { decoder.acceptWaveForm(TRAILING_FLUSH_SILENCE, TRAILING_FLUSH_SILENCE.size) }
        val finalJson = runCatching { decoder.finalResult }.getOrDefault("")

        if (config.segmentedSession) {
            val tailHyps = EmaVoskJsonParser.parseResult(finalJson, maxAlternatives = config.maxResults)
            if (tailHyps.isNotEmpty()) {
                val tailRescored = EmaSpeechRescorer.rescore(
                    decoded = tailHyps,
                    context = speechContext,
                    profile = config.profile,
                    maxResults = config.maxResults,
                    enableFormatting = config.enableFormatting,
                    maskOffensiveWords = config.maskOffensiveWords
                )
                tailRescored.primary?.takeIf { it.isNotBlank() }?.let { tailText ->
                    emittedSegCount++
                    val r = EmaSpeechResult(
                        text = tailText,
                        hypotheses = tailRescored.hypotheses,
                        confidenceScores = tailRescored.confidenceScores,
                        rawHypotheses = tailRescored.rawHypotheses,
                        words = tailHyps.first().words,
                        offline = true,
                        segmentCount = emittedSegCount
                    )
                    emit(token) { listener.onSegmentResults(r) }
                }
            }
            emit(token) { listener.onEndOfSegmentedSession() }
            return
        }

        val assembled = assembler.finish(finalJson)
        val rescored = EmaSpeechRescorer.rescore(
            decoded = assembled.hypotheses,
            context = speechContext,
            profile = config.profile,
            maxResults = config.maxResults,
            enableFormatting = config.enableFormatting,
            maskOffensiveWords = config.maskOffensiveWords
        )
        val primary = rescored.primary
        if (primary.isNullOrBlank()) {
            emitError(
                token,
                if (speechAnnounced) SpeechRecognizer.ERROR_NO_MATCH else SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                "No speech recognized in audio stream."
            )
            return
        }
        emit(token) {
            listener.onResults(
                EmaSpeechResult(
                    text = primary,
                    hypotheses = rescored.hypotheses,
                    confidenceScores = rescored.confidenceScores,
                    rawHypotheses = rescored.rawHypotheses,
                    words = assembled.hypotheses.first().words,
                    durationMs = SystemClock.elapsedRealtime() - startedMs,
                    offline = true,
                    inputRoute = "audio stream",
                    wordsPerMinute = assembled.estimatedWpm,
                    segmentCount = assembled.segmentCount
                )
            )
        }
    }

    private fun formatPreview(raw: String, config: Config): String {
        if (raw.isBlank()) return ""
        return if (config.enableFormatting) {
            EmaInverseTextNormalizer.normalize(
                raw = raw,
                profile = config.profile,
                context = EmaSpeechContext.EMPTY,
                enableFormatting = true,
                maskOffensiveWords = config.maskOffensiveWords
            )
        } else {
            EmaInverseTextNormalizer.normalize(
                raw = raw,
                profile = config.profile,
                context = EmaSpeechContext.EMPTY,
                enableFormatting = false,
                maskOffensiveWords = config.maskOffensiveWords
            )
        }
    }

    private fun createRecognizer(model: org.vosk.Model, config: Config): Recognizer {
        // Closed grammar is appropriate for yes/no, never for names, messages or dictation.
        val phrases = (if (config.profile == EmaRecognitionProfile.CONFIRMATION) config.grammarPhrases else emptyList()).filter { phrase ->
            phrase.isNotBlank() && phrase.all { it in 'a'..'z' || it == ' ' || it == '\'' }
        }
        val recognizer = if (phrases.isNotEmpty()) {
            val grammarJson = (phrases.distinct() + "[unk]")
                .joinToString(", ", "[", "]") { "\"" + it.replace("\"", "") + "\"" }
            runCatching { Recognizer(model, EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE.toFloat(), grammarJson) }
                .getOrElse { Recognizer(model, EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE.toFloat()) }
        } else {
            Recognizer(model, EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE.toFloat())
        }
        val alts = maxOf(config.maxResults, 6).coerceIn(3, 8)
        runCatching { recognizer.setMaxAlternatives(alts) }
        runCatching { recognizer.setWords(true) }
        runCatching { recognizer.setPartialWords(true) }
        return recognizer
    }

    private fun isAlive(token: Long): Boolean = !cancelled && sessionSeq.get() == token

    private fun emit(token: Long, action: () -> Unit) {
        if (!isAlive(token)) return
        mainHandler.post {
            if (isAlive(token)) runCatching(action)
        }
    }

    private fun emitError(token: Long, code: Int, message: String) {
        emit(token) { listener.onError(code, message) }
    }

    companion object {
        const val EXTRA_ENGINE_OFFLINE = "com.ema.assistant.extra.ENGINE_IS_OFFLINE"
        const val EXTRA_INPUT_ROUTE = "com.ema.assistant.extra.INPUT_ROUTE"

        private const val RMS_INTERVAL_MS = 50L
        private const val PARTIAL_INTERVAL_MS = 80L
        private const val MAX_SEGMENTED_SESSION_MS = 5L * 60L * 1_000L
        private const val MAX_STREAM_PIPE_BYTES = 128L * 1024L * 1024L

        // 240 ms of digital silence (16 kHz mono PCM16) to flush final decoder transitions cleanly.
        private val TRAILING_FLUSH_SILENCE = ByteArray(16_000 * 2 * 240 / 1000)

        /**
         * Universal offline file speech-to-text: transcribe any local recording
         * (`.wav`, `.m4a`, `.mp3`, `.ogg`, `.opus`, `.flac`, `.aac`, `.amr`,
         * `.3gp`, `.webm`, `.mp4`, `.mkv`) in constant memory completely on the
         * phone with zero network traffic.
         *
         * Worker-thread-only.
         */
        fun transcribeFile(
            context: Context,
            audioFile: File,
            profile: EmaRecognitionProfile = EmaRecognitionProfile.DICTATION,
            contextHints: EmaSpeechContext? = null,
            isCancelled: () -> Boolean = { false },
            onProgress: ((progress: Float, livePreview: String) -> Unit)? = null
        ): EmaSpeechResult {
            return transcribeDecodedStream(
                context = context,
                profile = profile,
                contextHints = contextHints,
                isCancelled = isCancelled,
                onProgress = onProgress
            ) { feedChunk ->
                EmaOfflineAudioDecoder.decodeFile(audioFile, isCancelled, feedChunk)
            }
        }

        /**
         * Universal offline URI speech-to-text: transcribe a user-chosen
         * `content://` or `file://` audio recording (document picker, shared
         * WhatsApp/Telegram voice note, voice recorder clip) completely offline.
         *
         * Worker-thread-only.
         */
        fun transcribeUri(
            context: Context,
            uri: Uri,
            profile: EmaRecognitionProfile = EmaRecognitionProfile.DICTATION,
            contextHints: EmaSpeechContext? = null,
            isCancelled: () -> Boolean = { false },
            onProgress: ((progress: Float, livePreview: String) -> Unit)? = null
        ): EmaSpeechResult {
            return transcribeDecodedStream(
                context = context,
                profile = profile,
                contextHints = contextHints,
                isCancelled = isCancelled,
                onProgress = onProgress
            ) { feedChunk ->
                EmaOfflineAudioDecoder.decodeUri(context.applicationContext, uri, isCancelled, feedChunk)
            }
        }

        /**
         * Offline WAV file speech-to-text (supports PCM 8/16/24/32, IEEE float,
         * G.711 A-law/μ-law and `WAVE_FORMAT_EXTENSIBLE`). Worker-thread-only.
         */
        fun transcribeWav(
            context: Context,
            wavFile: File,
            profile: EmaRecognitionProfile = EmaRecognitionProfile.DICTATION,
            contextHints: EmaSpeechContext? = null
        ): EmaSpeechResult = transcribeFile(context, wavFile, profile, contextHints)

        /**
         * Offline buffer speech-to-text: transcribe interleaved PCM16 samples
         * (any sample rate 4-192 kHz, 1-8 channels) completely on the device.
         *
         * Worker-thread-only.
         */
        fun transcribePcm(
            context: Context,
            samples: ShortArray,
            sampleRate: Int = EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE,
            channels: Int = 1,
            profile: EmaRecognitionProfile = EmaRecognitionProfile.COMMAND,
            contextHints: EmaSpeechContext? = null,
            maxResults: Int = 5
        ): EmaSpeechResult {
            val app = context.applicationContext
            val mono16k = EmaSpeechAudioProcessor.resampleToMono16k(samples, sampleRate, channels)
            val durationMs = mono16k.size * 1000L / EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE
            if (mono16k.isEmpty()) {
                return EmaSpeechResult("", emptyList(), FloatArray(0), durationMs = 0L, offline = true)
            }

            val conditioned = EmaSpeechAudioProcessor().conditionBatch(mono16k)
            val speechContext = contextHints ?: EmaSpeechRecognition.speechContext(app)
            val assembler = EmaUtteranceAssembler(
                maxAlternatives = maxOf(maxResults, 6),
                formatPausePunctuation = profile == EmaRecognitionProfile.DICTATION
            )

            EmaOfflineSpeechModel.acquire(app).use { lease ->
                Recognizer(lease.model, EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE.toFloat()).use { decoder ->
                    runCatching { decoder.setMaxAlternatives(maxOf(maxResults, 6).coerceIn(3, 8)) }
                    runCatching { decoder.setWords(true) }
                    runCatching { decoder.setPartialWords(true) }

                    val step = 6400
                    var offset = 0
                    while (offset < conditioned.size) {
                        val len = min(step, conditioned.size - offset).let { it - (it % 2) }
                        if (len <= 0) break
                        val slice = conditioned.copyOfRange(offset, offset + len)
                        if (decoder.acceptWaveForm(slice, slice.size)) {
                            assembler.onSegment(decoder.result)
                        } else {
                            assembler.onPartial(decoder.partialResult)
                        }
                        offset += len
                    }
                    decoder.acceptWaveForm(TRAILING_FLUSH_SILENCE, TRAILING_FLUSH_SILENCE.size)
                    val assembled = assembler.finish(decoder.finalResult)
                    val rescored = EmaSpeechRescorer.rescore(
                        decoded = assembled.hypotheses,
                        context = speechContext,
                        profile = profile,
                        maxResults = maxResults
                    )
                    return EmaSpeechResult(
                        text = rescored.primary.orEmpty(),
                        hypotheses = rescored.hypotheses,
                        confidenceScores = rescored.confidenceScores,
                        rawHypotheses = rescored.rawHypotheses,
                        words = assembled.hypotheses.firstOrNull()?.words.orEmpty(),
                        durationMs = durationMs,
                        offline = true,
                        inputRoute = "pcm buffer",
                        wordsPerMinute = assembled.estimatedWpm,
                        segmentCount = assembled.segmentCount,
                        mimeType = "audio/pcm"
                    )
                }
            }
        }

        private fun transcribeDecodedStream(
            context: Context,
            profile: EmaRecognitionProfile,
            contextHints: EmaSpeechContext?,
            isCancelled: () -> Boolean,
            onProgress: ((Float, String) -> Unit)?,
            decodeInto: (onChunk: (ShortArray, Float) -> Unit) -> EmaOfflineAudioDecoder.StreamMetadata
        ): EmaSpeechResult {
            val app = context.applicationContext
            val speechContext = contextHints ?: EmaSpeechRecognition.speechContext(app)
            val conditioner = EmaSpeechAudioProcessor()
            val assembler = EmaUtteranceAssembler(
                maxAlternatives = 6,
                formatPausePunctuation = profile == EmaRecognitionProfile.DICTATION
            )
            var lastProgressEmitMs = 0L

            EmaOfflineSpeechModel.acquire(app).use { lease ->
                Recognizer(lease.model, EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE.toFloat()).use { decoder ->
                    runCatching { decoder.setMaxAlternatives(6) }
                    runCatching { decoder.setWords(true) }
                    runCatching { decoder.setPartialWords(true) }

                    val meta = decodeInto { samples16k, progress ->
                        if (samples16k.isEmpty() || isCancelled()) return@decodeInto
                        val conditioned = conditioner.conditionStreamChunk(samples16k)
                        if (decoder.acceptWaveForm(conditioned, conditioned.size)) {
                            assembler.onSegment(decoder.result)
                        } else if (onProgress != null) {
                            assembler.onPartial(decoder.partialResult)
                        }
                        if (onProgress != null) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgressEmitMs >= 220L) {
                                lastProgressEmitMs = now
                                runCatching { onProgress(progress.coerceIn(0f, 0.99f), assembler.currentPreview()) }
                            }
                        }
                    }

                    if (isCancelled()) {
                        return EmaSpeechResult("", emptyList(), FloatArray(0), durationMs = meta.durationMs, offline = true)
                    }

                    decoder.acceptWaveForm(TRAILING_FLUSH_SILENCE, TRAILING_FLUSH_SILENCE.size)
                    val assembled = assembler.finish(decoder.finalResult)
                    val rescored = EmaSpeechRescorer.rescore(
                        decoded = assembled.hypotheses,
                        context = speechContext,
                        profile = profile,
                        maxResults = 5
                    )
                    val text = rescored.primary.orEmpty()
                    onProgress?.let { cb -> runCatching { cb(1f, text) } }
                    return EmaSpeechResult(
                        text = text,
                        hypotheses = rescored.hypotheses,
                        confidenceScores = rescored.confidenceScores,
                        rawHypotheses = rescored.rawHypotheses,
                        words = assembled.hypotheses.firstOrNull()?.words.orEmpty(),
                        durationMs = meta.durationMs,
                        offline = true,
                        inputRoute = "offline file",
                        wordsPerMinute = assembled.estimatedWpm,
                        segmentCount = assembled.segmentCount,
                        mimeType = meta.mimeType
                    )
                }
            }
        }

        private fun encodingToBitsAndFloat(encoding: Int): Pair<Int, Boolean> {
            if (encoding == AudioFormat.ENCODING_PCM_8BIT) return 8 to false
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) return 32 to true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) return 24 to false
                if (encoding == AudioFormat.ENCODING_PCM_32BIT) return 32 to false
            }
            return 16 to false
        }
    }
}
