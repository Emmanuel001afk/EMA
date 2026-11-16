package com.ema.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.roundToInt

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
    val offline: Boolean = true
) {
    /** Populate a standard Android [SpeechRecognizer] results [Bundle]. */
    fun toRecognitionBundle(): Bundle = Bundle().apply {
        val list = ArrayList(hypotheses.ifEmpty { if (text.isNotBlank()) listOf(text) else emptyList() })
        val scores = if (confidenceScores.size == list.size) {
            confidenceScores.copyOf()
        } else {
            FloatArray(list.size) { idx -> confidenceScores.getOrElse(idx) { 0.75f } }
        }
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, list)
        // Supply both keys: RecognitionListener reads SpeechRecognizer.CONFIDENCE_SCORES,
        // while activity-result callers read RecognizerIntent.EXTRA_CONFIDENCE_SCORES.
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
 * Complete on-device pipeline:
 *  1. Microphone capture (with sample-rate / source fallback + hardware noise
 *     suppression & echo cancellation where supported) or external PCM/WAV stream.
 *  2. [EmaSpeechAudioProcessor]: 75 Hz high-pass rumble/DC filter, speech-gated
 *     smooth AGC with C1 soft-knee limiting, multi-cue adaptive VAD, 360 ms
 *     pre-roll preservation and continuation-aware smart endpointing.
 *  3. Shared warm [EmaOfflineSpeechModel] + lattice N-best decoding with word
 *     timings and MBR partial posteriors.
 *  4. [EmaUtteranceAssembler]: multi-segment accumulation so brief pauses never
 *     truncate multi-step commands or dictation.
 *  5. [EmaSpeechRescorer] + [EmaInverseTextNormalizer] + [EmaPhoneticMatcher]:
 *     inverse text normalization (times, numbers, percentages, compounds,
 *     dictation punctuation), unambiguous phonetic slot grounding against
 *     installed apps and contacts, and domain-aware hypothesis ranking.
 *
 * Privacy guarantee: PCM exists only inside the active read/pre-roll buffer, is
 * never written to disk and is never transmitted over any network. Logs record
 * only shape via [EmaPrivacy].
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

            /** Extract all standard Android [RecognizerIntent] options safely. */
            fun fromIntent(intent: Intent?): Config {
                if (intent == null) return Config()
                val extras = intent.extras
                val lang = readLanguageTag(extras)
                val profileExtra = intent.getStringExtra(EXTRA_PROFILE)
                val profile = if (!profileExtra.isNullOrBlank()) {
                    EmaRecognitionProfile.fromKey(profileExtra)
                } else {
                    EmaRecognitionProfile.COMMAND
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
    @Volatile private var activeRecorder: AudioRecord? = null
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
        runCatching { activeRecorder?.stop() }
    }

    /** Immediately abort recognition and discard any pending callbacks. */
    fun cancel() {
        cancelled = true
        stopRequested = true
        sessionSeq.incrementAndGet()
        running.set(false)
        runCatching { activeRecorder?.stop() }
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

            // Build local context in parallel with wake-word release.
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

        // Ensure Hey EMA releases AudioRecord before opening capture.
        if (EmaWakeWordService.isCapturing()) {
            EmaWakeWordService.pauseForSpeech()
            EmaWakeWordService.awaitCaptureReleased(650L)
        }
        if (!isAlive(token)) return

        val opened = openMicrophone()
        if (opened == null) {
            emitError(token, SpeechRecognizer.ERROR_AUDIO, "Microphone could not be initialized.")
            return
        }

        val audio = opened.recorder
        val captureRate = opened.sampleRate
        activeRecorder = audio
        val effects = attachAudioEffects(audio.audioSessionId)
        var endSignalled = false

        try {
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                emitError(token, SpeechRecognizer.ERROR_AUDIO, "Microphone is in use by another app.")
                return
            }

            val processor = EmaSpeechAudioProcessor(
                EmaSpeechAudioProcessor.Config.forProfile(
                    profile = config.profile,
                    enableAgc = !effects.hardwareAgcActive,
                    minSpeechMsOverride = config.minSpeechMs,
                    completeSilenceMsOverride = config.completeSilenceMs,
                    possiblyCompleteSilenceMsOverride = config.possiblyCompleteSilenceMs,
                    noSpeechTimeoutMsOverride = config.noSpeechTimeoutMs,
                    maxDurationMsOverride = config.maxDurationMs
                )
            )
            val assembler = EmaUtteranceAssembler(maxAlternatives = maxOf(config.maxResults, 6))

            val readyBundle = Bundle().apply {
                putBoolean(EXTRA_ENGINE_OFFLINE, true)
                putString(RecognizerIntent.EXTRA_LANGUAGE, EmaSpeechMode.canonicalOfflineTag(config.languageTag))
            }
            emit(token) { listener.onReadyForSpeech(readyBundle) }

            // 32 ms frames: responsive partials and smooth RMS metering with low overhead.
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
                            val pretty = EmaInverseTextNormalizer.normalizePartial(preview, config.profile)
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
                                val pretty = EmaInverseTextNormalizer.normalizePartial(partial.text, config.profile)
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

            // Release microphone immediately so any follow-up turn never sees a busy device.
            releaseRecorderAndEffects(audio, effects)
            activeRecorder = null

            if (!isAlive(token)) return

            if (speechAnnounced) {
                endSignalled = true
                emit(token) { listener.onEndOfSpeech() }
            }

            if (timedOutWithoutSpeech && !assembler.hasAnyText()) {
                emitError(token, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, "No speech detected.")
                return
            }

            // Append a tiny trailing silence frame so Kaldi closes the last word cleanly.
            if (speechAnnounced || assembler.hasAnyText()) {
                runCatching { decoder.acceptWaveForm(TRAILING_FLUSH_SILENCE, TRAILING_FLUSH_SILENCE.size) }
            }
            val finalJson = runCatching { decoder.finalResult }.getOrDefault("")
            val assembled = assembler.finish(finalJson)

            if (assembled.hypotheses.isEmpty()) {
                val code = if (speechAnnounced) SpeechRecognizer.ERROR_NO_MATCH else SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                emitError(token, code, if (speechAnnounced) "No words recognized." else "No speech detected.")
                return
            }

            if (EmaVoskJsonParser.isNoiseHallucination(assembled.hypotheses, processor.voicedDurationMs)) {
                emitError(token, SpeechRecognizer.ERROR_NO_MATCH, "Only background noise was heard.")
                return
            }

            val rescored = EmaSpeechRescorer.rescore(
                decoded = assembled.hypotheses,
                context = speechContext,
                profile = config.profile,
                maxResults = config.maxResults
            )
            val primary = rescored.primary
            if (primary.isNullOrBlank()) {
                emitError(token, SpeechRecognizer.ERROR_NO_MATCH, "No words recognized.")
                return
            }

            if (!endSignalled) {
                emit(token) { listener.onEndOfSpeech() }
            }
            val durationMs = SystemClock.elapsedRealtime() - startedMs
            val result = EmaSpeechResult(
                text = primary,
                hypotheses = rescored.hypotheses,
                confidenceScores = rescored.confidenceScores,
                rawHypotheses = rescored.rawHypotheses,
                words = assembled.hypotheses.first().words,
                durationMs = durationMs,
                offline = true
            )
            android.util.Log.i(
                "EMA",
                "Offline STT ok (${EmaPrivacy.describe(primary)}, alts=${result.hypotheses.size}, segs=${assembled.segmentCount}, ${durationMs}ms)"
            )
            emit(token) { listener.onResults(result) }
        } finally {
            activeRecorder?.let { rec ->
                releaseRecorderAndEffects(rec, effects)
                activeRecorder = null
            }
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

        val assembler = EmaUtteranceAssembler(maxAlternatives = maxOf(config.maxResults, 6))
        var speechAnnounced = false
        var lastPartial = ""

        if (EmaSpeechAudioProcessor.hasWavHeader(probe, probeLen)) {
            val wav = EmaSpeechAudioProcessor.decodeWav(buffered)
            val conditioned = EmaSpeechAudioProcessor().conditionBatch(wav.pcm16kMono)
            if (conditioned.isNotEmpty()) {
                speechAnnounced = true
                emit(token) { listener.onBeginningOfSpeech() }
            }
            feedConditionedBytes(token, decoder, assembler, conditioned, config) { pretty, raw, conf ->
                if (pretty != lastPartial) {
                    lastPartial = pretty
                    emit(token) { listener.onPartialResults(pretty, raw, conf) }
                }
            }
        } else {
            val processor = EmaSpeechAudioProcessor(
                EmaSpeechAudioProcessor.Config(
                    enableHighPass = true,
                    enableAgc = true,
                    maxDurationMs = 120_000L
                )
            )
            val bytesPerSample = when (config.audioSourceEncoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                else -> 2
            }
            val frameAlign = (bytesPerSample * config.audioSourceChannels).coerceAtLeast(1)
            val chunkBytes = ((config.audioSourceSampleRate * frameAlign) / 10).coerceIn(frameAlign, 32_768)
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
                if (totalBytes > EmaSpeechAudioProcessor.MAX_BATCH_BYTES) break

                val pcm16Native = convertEncodingToPcm16(buf, usable, config.audioSourceEncoding)
                val pcm16k = EmaSpeechAudioProcessor.shortsToBytes(
                    EmaSpeechAudioProcessor.resampleToMono16k(
                        pcm16Native,
                        config.audioSourceSampleRate,
                        config.audioSourceChannels
                    )
                )
                // Save unaligned trailing bytes for next read.
                val rem = avail - usable
                if (rem > 0) System.arraycopy(buf, usable, buf, 0, rem)
                carry = rem

                if (pcm16k.isEmpty()) continue
                if (!speechAnnounced) {
                    speechAnnounced = true
                    emit(token) { listener.onBeginningOfSpeech() }
                }
                val decision = processor.process(pcm16k, pcm16k.size)
                val toFeed = if (decision.audioToDecode.isNotEmpty()) decision.audioToDecode else listOf(pcm16k)
                for (frame in toFeed) {
                    if (decoder.acceptWaveForm(frame, frame.size)) {
                        assembler.onSegment(decoder.result)
                    } else if (config.partialResults) {
                        val partial = assembler.onPartial(decoder.partialResult)
                        val pretty = EmaInverseTextNormalizer.normalizePartial(partial.text, config.profile)
                        if (pretty.isNotEmpty() && pretty != lastPartial) {
                            lastPartial = pretty
                            emit(token) { listener.onPartialResults(pretty, partial.text, partial.confidence) }
                        }
                    }
                }
            }
        }

        if (!isAlive(token)) return
        emit(token) { listener.onEndOfSpeech() }

        runCatching { decoder.acceptWaveForm(TRAILING_FLUSH_SILENCE, TRAILING_FLUSH_SILENCE.size) }
        val assembled = assembler.finish(runCatching { decoder.finalResult }.getOrDefault(""))
        val rescored = EmaSpeechRescorer.rescore(
            decoded = assembled.hypotheses,
            context = speechContext,
            profile = config.profile,
            maxResults = config.maxResults
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
                    offline = true
                )
            )
        }
    }

    private fun feedConditionedBytes(
        token: Long,
        decoder: Recognizer,
        assembler: EmaUtteranceAssembler,
        pcm16kLe: ByteArray,
        config: Config,
        onPartial: (String, String, Float) -> Unit
    ) {
        val step = 6400 // 200 ms at 16 kHz PCM16 mono
        var offset = 0
        while (offset < pcm16kLe.size && isAlive(token) && !stopRequested) {
            val len = min(step, pcm16kLe.size - offset).let { it - (it % 2) }
            if (len <= 0) break
            val slice = pcm16kLe.copyOfRange(offset, offset + len)
            if (decoder.acceptWaveForm(slice, slice.size)) {
                val acc = assembler.onSegment(decoder.result)
                if (config.partialResults && acc.isNotEmpty()) {
                    onPartial(EmaInverseTextNormalizer.normalizePartial(acc, config.profile), acc, 0.80f)
                }
            } else if (config.partialResults) {
                val p = assembler.onPartial(decoder.partialResult)
                if (p.text.isNotEmpty()) {
                    onPartial(EmaInverseTextNormalizer.normalizePartial(p.text, config.profile), p.text, p.confidence)
                }
            }
            offset += len
        }
    }

    private fun createRecognizer(model: org.vosk.Model, config: Config): Recognizer {
        val phrases = config.grammarPhrases.filter { phrase ->
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

    private data class OpenedMic(val recorder: AudioRecord, val sampleRate: Int)

    /** Try preferred voice-recognition capture first, falling back across OEM-safe configurations. */
    private fun openMicrophone(): OpenedMic? {
        val candidates = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION to 16_000,
            MediaRecorder.AudioSource.MIC to 16_000,
            MediaRecorder.AudioSource.VOICE_RECOGNITION to 48_000,
            MediaRecorder.AudioSource.VOICE_RECOGNITION to 44_100,
            MediaRecorder.AudioSource.MIC to 44_100
        )
        for ((source, rate) in candidates) {
            val minBuf = runCatching {
                AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            }.getOrDefault(-1)
            if (minBuf <= 0) continue
            // ~500 ms ring buffer prevents kernel overruns during short GC pauses.
            val bufferBytes = maxOf(minBuf * 2, rate)
            val record = runCatching {
                AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                return OpenedMic(record, rate)
            }
            runCatching { record.release() }
        }
        return null
    }

    private data class AttachedEffects(
        val noiseSuppressor: NoiseSuppressor?,
        val echoCanceler: AcousticEchoCanceler?,
        val gainControl: AutomaticGainControl?,
        val hardwareAgcActive: Boolean
    )

    private fun attachAudioEffects(sessionId: Int): AttachedEffects {
        if (sessionId <= 0) return AttachedEffects(null, null, null, false)
        val ns = runCatching {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.apply { enabled = true } else null
        }.getOrNull()
        val aec = runCatching {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } else null
        }.getOrNull()
        val agc = runCatching {
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId) else null
        }.getOrNull()
        val hwAgc = runCatching { agc?.enabled == true }.getOrDefault(false)
        return AttachedEffects(ns, aec, agc, hwAgc)
    }

    private fun releaseRecorderAndEffects(recorder: AudioRecord, effects: AttachedEffects) {
        runCatching { effects.noiseSuppressor?.release() }
        runCatching { effects.echoCanceler?.release() }
        runCatching { effects.gainControl?.release() }
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
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

        private const val RMS_INTERVAL_MS = 55L
        private const val PARTIAL_INTERVAL_MS = 85L

        // 240 ms of digital silence (16 kHz mono PCM16) to flush final decoder transitions cleanly.
        private val TRAILING_FLUSH_SILENCE = ByteArray(16_000 * 2 * 240 / 1000)

        /**
         * Offline file speech-to-text: transcribe a local RIFF/WAVE recording
         * directly on the device with zero network access.
         *
         * Worker-thread-only.
         */
        fun transcribeWav(
            context: Context,
            wavFile: File,
            profile: EmaRecognitionProfile = EmaRecognitionProfile.DICTATION,
            contextHints: EmaSpeechContext? = null
        ): EmaSpeechResult {
            require(wavFile.isFile && wavFile.canRead()) { "Audio file cannot be read" }
            require(wavFile.length() in 44L..EmaSpeechAudioProcessor.MAX_BATCH_BYTES.toLong()) {
                "Audio file size is outside the supported offline transcription range"
            }
            val decoded = wavFile.inputStream().buffered().use { EmaSpeechAudioProcessor.decodeWav(it) }
            return transcribePcm(
                context = context,
                samples = decoded.pcm16kMono,
                sampleRate = EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE,
                channels = 1,
                profile = profile,
                contextHints = contextHints
            ).copy(durationMs = decoded.durationMs)
        }

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
            val assembler = EmaUtteranceAssembler(maxAlternatives = maxOf(maxResults, 6))

            EmaOfflineSpeechModel.acquire(app).use { lease ->
                Recognizer(lease.model, EmaSpeechAudioProcessor.TARGET_SAMPLE_RATE.toFloat()).use { decoder ->
                    runCatching { decoder.setMaxAlternatives(maxOf(maxResults, 6).coerceIn(3, 8)) }
                    runCatching { decoder.setWords(true) }
                    runCatching { decoder.setPartialWords(true) }

                    val step = 6400 // 200 ms frames
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
                        offline = true
                    )
                }
            }
        }

        private fun convertEncodingToPcm16(bytes: ByteArray, length: Int, encoding: Int): ShortArray =
            when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> ShortArray(length) { i ->
                    (((bytes[i].toInt() and 0xff) - 128) shl 8).toShort()
                }
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val count = length / 4
                    val bb = ByteBuffer.wrap(bytes, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN)
                    ShortArray(count) {
                        val f = bb.float
                        val v = if (f.isFinite()) (f.coerceIn(-1f, 1f) * 32_767f).roundToInt() else 0
                        v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
                else -> EmaSpeechAudioProcessor.bytesToShorts(bytes, length)
            }
    }
}
