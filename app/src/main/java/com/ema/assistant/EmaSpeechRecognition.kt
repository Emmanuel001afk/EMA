package com.ema.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Central speech recognition coordinator for EMA.
 *
 * Unifies EMA's built-in offline engine ([EmaOfflineSpeechEngine]), Android's
 * platform on-device recognizer (Android 12+), and any external system
 * [RecognitionService] behind one mode-aware planner, one intent builder, one
 * contextual N-best rescorer ([EmaSpeechRescorer]), and one session controller
 * ([Session]).
 */
object EmaSpeechRecognition {

    const val EXTRA_BIASING_STRINGS = "android.speech.extra.BIASING_STRINGS"

    private const val PREFS_NAME = "ema"
    private const val KEY_SPEECH_MODE = "speech_mode"
    private const val LEGACY_KEY_OFFLINE_ENABLED = "offline_recognition_enabled"
    private const val MAX_BIASING_STRINGS = 160
    private const val MAX_BIASING_LENGTH = 60
    private const val TRANSIENT_AUDIO_RETRY_DELAY_MS = 180L

    /** Owner's chosen speech recognition mode (defaults to EMA's built-in offline engine first). */
    fun speechMode(context: Context): EmaSpeechMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SPEECH_MODE, null)
        if (!raw.isNullOrBlank()) return EmaSpeechMode.fromKey(raw)
        if (prefs.contains(LEGACY_KEY_OFFLINE_ENABLED) && !prefs.getBoolean(LEGACY_KEY_OFFLINE_ENABLED, true)) {
            return EmaSpeechMode.SYSTEM_FIRST
        }
        return EmaSpeechMode.EMA_OFFLINE_FIRST
    }

    /** Persist the owner's chosen speech recognition mode. */
    fun setSpeechMode(context: Context, mode: EmaSpeechMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SPEECH_MODE, mode.key)
            .apply()
        if (mode.prefersEmaOffline) {
            EmaOfflineSpeechModel.warmUpAsync(context)
        }
    }

    /** Human-readable overview combining mode, offline model readiness, and fallback availability. */
    fun diagnosticsSummary(context: Context): String {
        val mode = speechMode(context)
        val modelLine = EmaOfflineSpeechModel.statusSummary(context)
        val onDevice = hasPlatformOnDevice(context)
        val external = findExternalService(context) != null
        val fallbackLine = when {
            mode == EmaSpeechMode.EMA_OFFLINE_ONLY ->
                "Strict offline mode: external speech services are disabled."
            onDevice && external ->
                "Fallback recognizers available: Android on-device and system speech."
            onDevice ->
                "Fallback recognizer available: Android on-device speech."
            external ->
                "Fallback recognizer available: system speech service."
            else ->
                "EMA's built-in offline engine is the sole speech recognizer on this phone."
        }
        return "Speech mode: ${mode.label}. $modelLine $fallbackLine"
    }

    /**
     * Phrases safe to pass as recognizer biasing hints: installed app labels
     * and owner-saved shortcuts. Contacts are intentionally excluded so they
     * are never attached to an external recognizer intent.
     */
    fun userVocabulary(context: Context): List<String> {
        val apps = runCatching { EmaAppActionEngine(context).installedAppLabels() }.getOrDefault(emptyList())
        val shortcuts = runCatching { EmaMemoryStore.load(context).shortcuts.keys }.getOrDefault(emptySet())
        return (shortcuts + apps)
            .map { it.trim() }
            .filter { it.length in 2..MAX_BIASING_LENGTH }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    /**
     * Complete local context for strictly on-device N-best rescoring and
     * phonetic grounding. Kept in process memory only; never exported.
     */
    fun speechContext(context: Context, includeContacts: Boolean = true): EmaSpeechContext {
        val apps = runCatching { EmaAppActionEngine(context).installedAppLabels() }.getOrDefault(emptyList())
        val memory = runCatching { EmaMemoryStore.load(context) }.getOrNull()
        val shortcuts = memory?.shortcuts?.keys?.toList().orEmpty()
        val aliases = memory?.aliases?.keys?.toList().orEmpty()
        val learned = runCatching {
            EmaLearningStore.model(context).links()
                .filter { it.confirmed || it.times >= 2 }
                .flatMap { listOf(it.phrase, it.meaning) }
                .distinct()
        }.getOrDefault(emptyList())
        val contacts = if (includeContacts) {
            runCatching { ContactLookup.localNamesForSpeech(context) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return EmaSpeechContext(
            shortcuts = shortcuts,
            ownerAliases = aliases,
            learnedPhrases = learned,
            installedApps = apps,
            contactNames = contacts
        )
    }

    /**
     * Pick the strongest hypothesis from a flat list of strings against the
     * owner's vocabulary (installed apps and shortcuts). Pure Kotlin.
     */
    fun chooseHypothesis(results: List<String>?, vocabulary: List<String>): String? {
        val clean = results.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return null
        if (clean.size == 1 || vocabulary.isEmpty()) return clean.first()

        val vocabNorm = vocabulary.map { it.trim() }
            .filter { it.length >= 2 }
            .map { EmaAppResolver.normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (vocabNorm.isEmpty()) return clean.first()

        return clean.withIndex().maxByOrNull { (index, candidate) ->
            val words = EmaAppResolver.normalize(candidate)
            val whole = " $words "
            val vocabScore = vocabNorm.maxOfOrNull { phrase ->
                when {
                    words == phrase -> 3.0
                    whole.contains(" $phrase ") -> 2.0
                    phrase.length >= 4 && words.contains(phrase) -> 1.0
                    else -> 0.0
                }
            } ?: 0.0
            vocabScore - index * 0.25
        }?.value
    }

    /**
     * Rescore and normalize a standard Android [SpeechRecognizer] results bundle
     * using EMA's full domain and personal context.
     */
    fun resolveResults(
        results: Bundle?,
        context: EmaSpeechContext,
        profile: EmaRecognitionProfile = EmaRecognitionProfile.COMMAND,
        maxResults: Int = 5
    ): EmaSpeechRescorer.RescoredResult {
        if (results == null) {
            return EmaSpeechRescorer.RescoredResult(emptyList(), FloatArray(0), emptyList(), emptyList())
        }
        val strings = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val confidences = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            ?: results.getFloatArray(RecognizerIntent.EXTRA_CONFIDENCE_SCORES)
        return EmaSpeechRescorer.rescoreStrings(
            candidates = strings,
            confidenceScores = confidences,
            context = context,
            profile = profile,
            maxResults = maxResults
        )
    }

    /** Build a tuned, standards-compliant [RecognizerIntent] for platform recognizers. */
    fun buildIntent(
        context: Context,
        preferOffline: Boolean = true,
        profile: EmaRecognitionProfile = EmaRecognitionProfile.COMMAND,
        vocabulary: List<String> = userVocabulary(context),
        maxResults: Int = 5,
        language: Locale = Locale.getDefault()
    ): Intent {
        val tag = language.toLanguageTag().ifBlank { "en-US" }
        val completeSilenceMs = when (profile) {
            EmaRecognitionProfile.COMMAND -> 950L
            EmaRecognitionProfile.DICTATION -> 2_200L
        }
        val possibleSilenceMs = when (profile) {
            EmaRecognitionProfile.COMMAND -> 700L
            EmaRecognitionProfile.DICTATION -> 1_400L
        }
        val minLengthMs = when (profile) {
            EmaRecognitionProfile.COMMAND -> 1_100L
            EmaRecognitionProfile.DICTATION -> 2_500L
        }
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // EXTRA_LANGUAGE is defined by Android as an IETF BCP-47 String tag.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults.coerceIn(1, 10))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possibleSilenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minLengthMs)
            putExtra(EmaOfflineSpeechEngine.Config.EXTRA_PROFILE, profile.key)
            if (preferOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && vocabulary.isNotEmpty()) {
                putStringArrayListExtra(EXTRA_BIASING_STRINGS, ArrayList(vocabulary.take(MAX_BIASING_STRINGS)))
            }
        }
    }

    /** True when Android 12+ reports a platform on-device speech engine. */
    fun hasPlatformOnDevice(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    /** True when the phone currently has a validated internet connection. */
    fun isNetworkAvailable(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val network = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    /**
     * Create a non-EMA platform [SpeechRecognizer] (for [EmaRecognitionService]
     * delegation or fallback). Never resolves back to EMA's own service.
     */
    fun createRecognizer(context: Context): SpeechRecognizer? {
        if (hasPlatformOnDevice(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
                .getOrNull()
                ?.let { return it }
        }
        val external = findExternalService(context) ?: return null
        return runCatching { SpeechRecognizer.createSpeechRecognizer(context, external) }.getOrNull()
    }

    /** Create an Android [SpeechRecognizer] for a specific platform [EmaRecognizerKind]. */
    fun createPlatformRecognizer(context: Context, kind: EmaRecognizerKind): SpeechRecognizer? = when (kind) {
        EmaRecognizerKind.EMA_OFFLINE -> null
        EmaRecognizerKind.ON_DEVICE ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            } else {
                null
            }
        EmaRecognizerKind.EXTERNAL ->
            findExternalService(context)?.let { svc ->
                runCatching { SpeechRecognizer.createSpeechRecognizer(context, svc) }.getOrNull()
            }
    }

    /** Any installed [RecognitionService] owned by another package, or null. */
    @Suppress("DEPRECATION")
    fun findExternalService(context: Context): ComponentName? {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            context.packageManager.queryIntentServices(intent, 0)
        }
        val other = services.firstOrNull { it.serviceInfo.packageName != context.packageName }
            ?: return null
        return ComponentName(other.serviceInfo.packageName, other.serviceInfo.name)
    }

    /** Compute the ordered recognizer plan for the current device, language and network state. */
    fun buildPlan(
        context: Context,
        modeOverride: EmaSpeechMode? = null,
        language: Locale = Locale.getDefault()
    ): List<EmaRecognizerKind> {
        val mode = modeOverride ?: speechMode(context)
        val emaAvailable = EmaOfflineSpeechModel.checkAvailability(
            context = context,
            languageTag = language.toLanguageTag()
        ).available
        val onDevice = hasPlatformOnDevice(context)
        val external = findExternalService(context) != null
        val online = isNetworkAvailable(context)
        val available = LinkedHashSet<EmaRecognizerKind>()
        if (emaAvailable) available += EmaRecognizerKind.EMA_OFFLINE
        if (onDevice) available += EmaRecognizerKind.ON_DEVICE
        if (external) available += EmaRecognizerKind.EXTERNAL
        return EmaRecognizerFallback.plan(
            mode = mode,
            available = available,
            networkAvailable = online
        )
    }

    /** Listener for a managed [Session] turn. All callbacks run on the main thread. */
    interface SessionListener {
        fun onPreparingOfflineModel() {}
        fun onReadyForSpeech(kind: EmaRecognizerKind) {}
        fun onBeginningOfSpeech(kind: EmaRecognizerKind) {}
        fun onRmsChanged(rmsdB: Float) {}
        fun onPartialTranscript(text: String, kind: EmaRecognizerKind) {}
        fun onEndOfSpeech(kind: EmaRecognizerKind) {}
        fun onSwitchingBackend(from: EmaRecognizerKind, to: EmaRecognizerKind, errorCode: Int) {}
        fun onResults(
            primary: String,
            alternatives: List<String>,
            confidenceScores: FloatArray,
            kind: EmaRecognizerKind
        )
        fun onFailure(errorCode: Int, userMessage: String, lastKind: EmaRecognizerKind?)
    }

    /**
     * Manages one spoken turn end-to-end across [EmaOfflineSpeechEngine],
     * Android's on-device recognizer, and the system recognizer, including
     * transient microphone handoff retry, automatic failover, and contextual
     * N-best rescoring.
     */
    class Session(
        context: Context,
        private val listener: SessionListener
    ) {
        private val appContext = context.applicationContext
        private val mainHandler = Handler(Looper.getMainLooper())

        private var plan: List<EmaRecognizerKind> = emptyList()
        private var stepIndex = 0
        private var audioRetriedOnStep = false
        private var activeProfile = EmaRecognitionProfile.COMMAND
        private var activeLanguage: Locale = Locale.getDefault()
        private var activeMode: EmaSpeechMode = EmaSpeechMode.EMA_OFFLINE_FIRST
        private var cachedContext: EmaSpeechContext? = null
        private var cachedVocabulary: List<String>? = null
        private var offlineEngine: EmaOfflineSpeechEngine? = null
        private var platformRecognizer: SpeechRecognizer? = null
        private var generation = 0
        private var active = false

        val isListening: Boolean get() = active

        val currentKind: EmaRecognizerKind?
            get() = if (active) plan.getOrNull(stepIndex) else null

        fun start(
            profile: EmaRecognitionProfile = EmaRecognitionProfile.COMMAND,
            modeOverride: EmaSpeechMode? = null,
            language: Locale = Locale.getDefault()
        ) {
            cancel()
            val gen = ++generation
            activeProfile = profile
            activeLanguage = language
            activeMode = modeOverride ?: speechMode(appContext)
            plan = buildPlan(appContext, activeMode, language)
            stepIndex = 0
            audioRetriedOnStep = false

            if (plan.isEmpty()) {
                val msg = if (activeMode == EmaSpeechMode.EMA_OFFLINE_ONLY &&
                    !EmaSpeechMode.supportsOfflineLanguage(language.toLanguageTag())
                ) {
                    "EMA's built-in offline engine currently supports English. Switch Speech mode to Offline first to use system speech for ${language.displayLanguage}, or type below."
                } else {
                    "Speech recognition isn't available right now. Check available memory or type your request below."
                }
                mainHandler.post {
                    if (gen == generation) listener.onFailure(SpeechRecognizer.ERROR_CLIENT, msg, null)
                }
                return
            }

            active = true
            startCurrentStep(gen)
        }

        /** Finish capturing and flush the transcript spoken so far. */
        fun stopListening() {
            if (!active) return
            offlineEngine?.stopListening()
            runCatching { platformRecognizer?.stopListening() }
        }

        /** Abort immediately and release all native and system audio handles. */
        fun cancel() {
            generation++
            active = false
            mainHandler.removeCallbacksAndMessages(null)
            offlineEngine?.cancel()
            offlineEngine = null
            runCatching { platformRecognizer?.cancel() }
            runCatching { platformRecognizer?.destroy() }
            platformRecognizer = null
        }

        private fun startCurrentStep(gen: Int) {
            if (!active || gen != generation) return
            val kind = plan.getOrNull(stepIndex) ?: run {
                active = false
                val fallbackKind = plan.lastOrNull() ?: EmaRecognizerKind.EMA_OFFLINE
                listener.onFailure(
                    SpeechRecognizer.ERROR_CLIENT,
                    EmaRecognizerFallback.userMessage(SpeechRecognizer.ERROR_CLIENT, fallbackKind, activeMode),
                    plan.lastOrNull()
                )
                return
            }

            when (kind) {
                EmaRecognizerKind.EMA_OFFLINE -> startOfflineEngine(gen)
                EmaRecognizerKind.ON_DEVICE,
                EmaRecognizerKind.EXTERNAL -> startPlatformEngine(gen, kind)
            }
        }

        private fun startOfflineEngine(gen: Int) {
            val kind = EmaRecognizerKind.EMA_OFFLINE
            val engine = EmaOfflineSpeechEngine(appContext, object : EmaOfflineSpeechEngine.Listener {
                override fun onPreparing() {
                    if (gen == generation && active) listener.onPreparingOfflineModel()
                }

                override fun onReadyForSpeech(params: Bundle) {
                    if (gen == generation && active) listener.onReadyForSpeech(kind)
                }

                override fun onBeginningOfSpeech() {
                    if (gen == generation && active) listener.onBeginningOfSpeech(kind)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (gen == generation && active) listener.onRmsChanged(rmsdB)
                }

                override fun onPartialResults(text: String, rawText: String, confidence: Float) {
                    if (gen == generation && active) listener.onPartialTranscript(text, kind)
                }

                override fun onEndOfSpeech() {
                    if (gen == generation && active) listener.onEndOfSpeech(kind)
                }

                override fun onResults(result: EmaSpeechResult) {
                    if (gen != generation || !active) return
                    active = false
                    offlineEngine = null
                    cachedContext = null
                    // Include raw alternatives alongside normalized alternatives so
                    // EmaLearningModel can learn from decoder mishearings too.
                    val alts = (result.hypotheses.drop(1) + result.rawHypotheses)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.equals(result.text, ignoreCase = true) }
                        .distinctBy { it.lowercase(Locale.ROOT) }
                    listener.onResults(result.text, alts, result.confidenceScores, kind)
                }

                override fun onError(errorCode: Int, message: String) {
                    if (gen != generation || !active) return
                    offlineEngine = null
                    handleStepError(gen, kind, errorCode)
                }
            })
            offlineEngine = engine
            engine.start(
                EmaOfflineSpeechEngine.Config(
                    profile = activeProfile,
                    languageTag = activeLanguage.toLanguageTag(),
                    partialResults = true,
                    maxResults = 6,
                    speechContext = cachedContext
                )
            )
        }

        private fun startPlatformEngine(gen: Int, kind: EmaRecognizerKind) {
            val sr = createPlatformRecognizer(appContext, kind)
            if (sr == null) {
                handleStepError(gen, kind, SpeechRecognizer.ERROR_CLIENT)
                return
            }
            platformRecognizer = sr
            // Warm local context off the UI thread so platform onResults never blocks on ContentResolver.
            Thread({
                val loaded = runCatching { speechContext(appContext, includeContacts = true) }.getOrNull()
                if (loaded != null) {
                    mainHandler.post {
                        if (gen == generation && active) cachedContext = loaded
                    }
                }
            }, "ema-speech-ctx").apply {
                isDaemon = true
                start()
            }
            val vocab = cachedVocabulary ?: userVocabulary(appContext).also { cachedVocabulary = it }
            val intent = buildIntent(
                context = appContext,
                preferOffline = kind == EmaRecognizerKind.ON_DEVICE,
                profile = activeProfile,
                vocabulary = vocab,
                maxResults = 6,
                language = activeLanguage
            )

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (gen == generation && active) listener.onReadyForSpeech(kind)
                }

                override fun onBeginningOfSpeech() {
                    if (gen == generation && active) listener.onBeginningOfSpeech(kind)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (gen == generation && active) listener.onRmsChanged(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    if (gen == generation && active) listener.onEndOfSpeech(kind)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (gen != generation || !active) return
                    val raw = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (raw.isNotEmpty()) {
                        val normalized = EmaInverseTextNormalizer.normalizePartial(raw, activeProfile)
                        listener.onPartialTranscript(normalized, kind)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onResults(results: Bundle?) {
                    if (gen != generation || !active) return
                    active = false
                    releasePlatform()
                    val ctx = cachedContext ?: speechContext(appContext, includeContacts = false)
                    cachedContext = null
                    val outcome = resolveResults(results, ctx, activeProfile, maxResults = 6)
                    val primary = outcome.primary
                    if (primary.isNullOrBlank()) {
                        listener.onFailure(
                            SpeechRecognizer.ERROR_NO_MATCH,
                            EmaRecognizerFallback.userMessage(
                                SpeechRecognizer.ERROR_NO_MATCH,
                                kind,
                                activeMode
                            ),
                            kind
                        )
                        return
                    }
                    val alts = (outcome.hypotheses.drop(1) + outcome.rawHypotheses)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.equals(primary, ignoreCase = true) }
                        .distinctBy { it.lowercase(Locale.ROOT) }
                    listener.onResults(primary, alts, outcome.confidenceScores, kind)
                }

                override fun onError(error: Int) {
                    if (gen != generation || !active) return
                    releasePlatform()
                    handleStepError(gen, kind, error)
                }
            })

            runCatching { sr.startListening(intent) }.onFailure { err ->
                android.util.Log.w("EMA", "Platform recognizer ($kind) failed to start: ${err.javaClass.simpleName}")
                releasePlatform()
                handleStepError(gen, kind, SpeechRecognizer.ERROR_CLIENT)
            }
        }

        private fun handleStepError(gen: Int, kind: EmaRecognizerKind, errorCode: Int) {
            if (!active || gen != generation) return

            // Brief single retry when AudioRecord collides with wake-word shutdown.
            if (EmaRecognizerFallback.shouldRetrySameBackend(errorCode, audioRetriedOnStep)) {
                audioRetriedOnStep = true
                android.util.Log.i("EMA", "Retrying $kind after transient microphone busy ($errorCode)")
                mainHandler.postDelayed({
                    if (active && gen == generation) startCurrentStep(gen)
                }, TRANSIENT_AUDIO_RETRY_DELAY_MS)
                return
            }

            if (EmaRecognizerFallback.shouldFallback(errorCode, audioRetryExhausted = true) && stepIndex + 1 < plan.size) {
                val nextKind = plan[stepIndex + 1]
                stepIndex++
                audioRetriedOnStep = false
                android.util.Log.w("EMA", "Recognizer $kind failed ($errorCode); falling back to $nextKind")
                listener.onSwitchingBackend(kind, nextKind, errorCode)
                startCurrentStep(gen)
                return
            }

            active = false
            cachedContext = null
            val message = EmaRecognizerFallback.userMessage(
                errorCode = errorCode,
                kind = kind,
                mode = activeMode
            )
            listener.onFailure(errorCode, message, kind)
        }

        private fun releasePlatform() {
            runCatching { platformRecognizer?.cancel() }
            runCatching { platformRecognizer?.destroy() }
            platformRecognizer = null
        }
    }
}
