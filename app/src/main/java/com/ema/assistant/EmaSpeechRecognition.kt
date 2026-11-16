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
 * contextual & MBR N-best rescorer ([EmaSpeechRescorer]), and one session
 * controller ([Session]).
 */
object EmaSpeechRecognition {

    const val EXTRA_BIASING_STRINGS = "android.speech.extra.BIASING_STRINGS"
    private const val EXTRA_ENABLE_FORMATTING = "android.speech.extra.ENABLE_FORMATTING"
    private const val EXTRA_FORMATTING_OPTIMIZE_QUALITY = "quality"

    private const val PREFS_NAME = "ema"
    private const val KEY_SPEECH_MODE = "speech_mode"
    private const val LEGACY_KEY_OFFLINE_ENABLED = "offline_recognition_enabled"
    private const val MAX_BIASING_STRINGS = 160
    private const val MAX_BIASING_LENGTH = 60
    private const val MAX_SCREEN_LABELS = 80
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

    /** Human-readable overview combining mode, offline model readiness, telemetry and fallbacks. */
    fun diagnosticsSummary(context: Context): String {
        val mode = speechMode(context)
        val modelLine = EmaOfflineSpeechModel.statusSummary(context)
        val stats = EmaOfflineSpeechModel.telemetry()
        val statsLine = if (stats.completedTurns > 0) {
            " Offline turns this session: ${stats.completedTurns} (avg finalize ${stats.avgFinalizeMs} ms)."
        } else {
            ""
        }
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
        return "Speech mode: ${mode.label}. $modelLine$statsLine $fallbackLine"
    }

    /**
     * Phrases safe to pass as recognizer biasing hints: installed app labels
     * and owner-saved shortcuts. Contacts, personal corrections and screen text
     * are excluded so private data is never attached to an external intent.
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
     * Complete local context for strictly on-device N-best rescoring, personal
     * mishearing recovery and phonetic slot grounding. Kept in memory only;
     * never logged or sent off-device.
     */
    fun speechContext(
        context: Context,
        includeContacts: Boolean = true,
        screenLabels: List<String> = emptyList()
    ): EmaSpeechContext {
        val apps = runCatching { EmaAppActionEngine(context).installedAppLabels() }.getOrDefault(emptyList())
        val memory = runCatching { EmaMemoryStore.load(context) }.getOrNull()
        val shortcuts = memory?.shortcuts?.keys?.toList().orEmpty()
        val aliases = memory?.aliases?.keys?.toList().orEmpty()

        val learnedPhrases = ArrayList<String>()
        val learnedReplacements = LinkedHashMap<String, String>()
        runCatching {
            val model = EmaLearningStore.model(context)
            val eligible = model.links().filter { it.confirmed || it.times >= 2 }
            for (link in eligible) {
                learnedPhrases += link.phrase
                learnedPhrases += link.meaning
                // Apply only unambiguous verified mappings (never single guesses or split ties).
                if (!model.ambiguous(model.candidates(link.phrase))) {
                    learnedReplacements[link.phrase] = link.meaning
                }
            }
        }

        val contacts = if (includeContacts) {
            runCatching { ContactLookup.localNamesForSpeech(context) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val cleanedScreen = if (screenLabels.isEmpty()) {
            emptyList()
        } else {
            screenLabels.asSequence()
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .filter { it.length in 2..48 && it.any(Char::isLetter) }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .take(MAX_SCREEN_LABELS)
                .toList()
        }

        return EmaSpeechContext(
            shortcuts = shortcuts,
            ownerAliases = aliases,
            learnedPhrases = learnedPhrases.distinct(),
            installedApps = apps,
            contactNames = contacts,
            learnedReplacements = learnedReplacements,
            screenLabels = cleanedScreen
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
        val strings = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: results.getStringArrayList(RecognizerIntent.EXTRA_RESULTS)
            ?: arrayListOf()
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
            EmaRecognitionProfile.CONFIRMATION -> 650L
            EmaRecognitionProfile.WEB_SEARCH -> 850L
            EmaRecognitionProfile.COMMAND -> 950L
            EmaRecognitionProfile.DICTATION -> 2_200L
        }
        val possibleSilenceMs = when (profile) {
            EmaRecognitionProfile.CONFIRMATION -> 480L
            EmaRecognitionProfile.WEB_SEARCH -> 650L
            EmaRecognitionProfile.COMMAND -> 700L
            EmaRecognitionProfile.DICTATION -> 1_400L
        }
        val minLengthMs = when (profile) {
            EmaRecognitionProfile.CONFIRMATION -> 600L
            EmaRecognitionProfile.WEB_SEARCH -> 900L
            EmaRecognitionProfile.COMMAND -> 1_100L
            EmaRecognitionProfile.DICTATION -> 2_500L
        }
        val languageModel = if (profile == EmaRecognitionProfile.WEB_SEARCH) {
            RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH
        } else {
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        }
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, languageModel)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (profile == EmaRecognitionProfile.DICTATION) {
                    putExtra(EXTRA_ENABLE_FORMATTING, EXTRA_FORMATTING_OPTIMIZE_QUALITY)
                }
                if (vocabulary.isNotEmpty()) {
                    putStringArrayListExtra(EXTRA_BIASING_STRINGS, ArrayList(vocabulary.take(MAX_BIASING_STRINGS)))
                }
            }
        }
    }

    /** Extract distinct short interactive labels from a structured screen text dump. */
    fun extractScreenLabels(screenDump: String, limit: Int = MAX_SCREEN_LABELS): List<String> {
        if (screenDump.isBlank()) return emptyList()
        return screenDump.lineSequence()
            .flatMap { it.splitToSequence('·', '|', '\t') }
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in 3..42 && it.count { c -> c == ' ' } <= 5 && it.any(Char::isLetter) }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(limit)
            .toList()
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

    /** Convert Android's `onRmsChanged(rmsdB)` value into a smooth UI level in `[0f, 1f]`. */
    fun normalizeRmsLevel(rmsdB: Float): Float {
        if (!rmsdB.isFinite()) return 0f
        // EmaSpeechAudioProcessor emits [-2, +12] dB; platform recognizers emit roughly [-2, +10] dB.
        return ((rmsdB + 2f) / 13f).coerceIn(0f, 1f)
    }

    /** Listener for a managed [Session] turn. All callbacks run on the main thread. */
    interface SessionListener {
        fun onPreparingOfflineModel() {}
        fun onReadyForSpeech(kind: EmaRecognizerKind) {}
        fun onInputRouteChanged(route: String) {}
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
     * Android's on-device recognizer and the system recognizer, including
     * peripheral mic routing, transient microphone handoff retry, automatic
     * failover, audio-quality diagnostic hints and contextual N-best rescoring.
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
        private var activeScreenLabels: List<String> = emptyList()
        private var includePrivateContext = true
        private var enableFormatting = true
        private var maskOffensiveWords = false
        private var maxResults = 6
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
            language: Locale = Locale.getDefault(),
            screenLabels: List<String> = emptyList(),
            includeOwnerContacts: Boolean = true,
            enableFormatting: Boolean = true,
            maskOffensiveWords: Boolean = false,
            maxResults: Int = 6
        ) {
            cancel()
            val gen = ++generation
            activeProfile = profile
            activeLanguage = language
            activeMode = modeOverride ?: speechMode(appContext)
            activeScreenLabels = screenLabels
            includePrivateContext = includeOwnerContacts
            this.enableFormatting = enableFormatting
            this.maskOffensiveWords = maskOffensiveWords
            this.maxResults = maxResults.coerceIn(1, 10)
            cachedContext = null
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
                    if (gen == generation && active) {
                        val route = params.getString(EmaOfflineSpeechEngine.EXTRA_INPUT_ROUTE).orEmpty()
                        if (route.isNotBlank()) listener.onInputRouteChanged(route)
                        listener.onReadyForSpeech(kind)
                    }
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
                    val alts = (result.hypotheses.drop(1) + result.rawHypotheses)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.equals(result.text, ignoreCase = true) }
                        .distinctBy { it.lowercase(Locale.ROOT) }
                    listener.onResults(result.text, alts, result.confidenceScores, kind)
                }

                override fun onError(errorCode: Int, message: String) {
                    if (gen != generation || !active) return
                    offlineEngine = null
                    handleStepError(gen, kind, errorCode, diagnosticDetail = message)
                }
            })
            offlineEngine = engine
            val seedContext = when {
                cachedContext != null -> cachedContext
                !includePrivateContext -> {
                    val apps = runCatching { EmaAppActionEngine(appContext).installedAppLabels() }.getOrDefault(emptyList())
                    EmaSpeechContext(installedApps = apps)
                }
                activeScreenLabels.isNotEmpty() -> {
                    // Loaded lazily on engine worker thread with screen labels attached.
                    null
                }
                else -> null
            }
            val screenSnapshot = activeScreenLabels
            if (seedContext != null || screenSnapshot.isEmpty()) {
                engine.start(
                    EmaOfflineSpeechEngine.Config(
                        profile = activeProfile,
                        languageTag = activeLanguage.toLanguageTag(),
                        partialResults = true,
                        maxResults = maxResults,
                        enableFormatting = enableFormatting,
                        maskOffensiveWords = maskOffensiveWords,
                        speechContext = seedContext
                    )
                )
            } else {
                // Build full context with current screen labels on a background thread, then start immediately.
                Thread({
                    val full = runCatching {
                        speechContext(appContext, includeContacts = includePrivateContext, screenLabels = screenSnapshot)
                    }.getOrNull()
                    if (gen == generation && active) {
                        engine.start(
                            EmaOfflineSpeechEngine.Config(
                                profile = activeProfile,
                                languageTag = activeLanguage.toLanguageTag(),
                                partialResults = true,
                                maxResults = maxResults,
                                enableFormatting = enableFormatting,
                                maskOffensiveWords = maskOffensiveWords,
                                speechContext = full
                            )
                        )
                    }
                }, "ema-speech-screen-ctx").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        private fun startPlatformEngine(gen: Int, kind: EmaRecognizerKind) {
            val sr = createPlatformRecognizer(appContext, kind)
            if (sr == null) {
                handleStepError(gen, kind, SpeechRecognizer.ERROR_CLIENT)
                return
            }
            platformRecognizer = sr
            val wantContacts = includePrivateContext
            val screenSnapshot = if (includePrivateContext) activeScreenLabels else emptyList()
            Thread({
                val loaded = runCatching {
                    if (wantContacts) {
                        speechContext(appContext, includeContacts = true, screenLabels = screenSnapshot)
                    } else {
                        EmaSpeechContext(
                            installedApps = EmaAppActionEngine(appContext).installedAppLabels()
                        )
                    }
                }.getOrNull()
                if (loaded != null) {
                    mainHandler.post {
                        if (gen == generation && active) cachedContext = loaded
                    }
                }
            }, "ema-speech-ctx").apply {
                isDaemon = true
                start()
            }
            val vocab = if (includePrivateContext) {
                cachedVocabulary ?: userVocabulary(appContext).also { cachedVocabulary = it }
            } else {
                emptyList()
            }
            val intent = buildIntent(
                context = appContext,
                preferOffline = kind == EmaRecognizerKind.ON_DEVICE,
                profile = activeProfile,
                vocabulary = vocab,
                maxResults = maxResults,
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
                        val normalized = if (enableFormatting) {
                            EmaInverseTextNormalizer.normalize(
                                raw, activeProfile, EmaSpeechContext.EMPTY,
                                enableFormatting = true, maskOffensiveWords = maskOffensiveWords
                            )
                        } else {
                            EmaInverseTextNormalizer.normalize(
                                raw, activeProfile, EmaSpeechContext.EMPTY,
                                enableFormatting = false, maskOffensiveWords = maskOffensiveWords
                            )
                        }
                        listener.onPartialTranscript(normalized, kind)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onResults(results: Bundle?) {
                    if (gen != generation || !active) return
                    active = false
                    releasePlatform()
                    val ctx = cachedContext ?: if (includePrivateContext) {
                        speechContext(appContext, includeContacts = false, screenLabels = activeScreenLabels)
                    } else {
                        EmaSpeechContext.EMPTY
                    }
                    cachedContext = null
                    val rawList = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val rawScores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        ?: results?.getFloatArray(RecognizerIntent.EXTRA_CONFIDENCE_SCORES)
                    val hyps = rawList.mapIndexedNotNull { idx, s ->
                        val t = s.trim().takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                        val c = rawScores?.getOrNull(idx)?.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.05f, 0.99f)
                            ?: (0.82f - idx * 0.03f).coerceAtLeast(0.35f)
                        EmaVoskJsonParser.Hypothesis(t, c, c.toDouble())
                    }
                    val outcome = EmaSpeechRescorer.rescore(
                        decoded = hyps,
                        context = ctx,
                        profile = activeProfile,
                        maxResults = maxResults,
                        enableFormatting = enableFormatting,
                        maskOffensiveWords = maskOffensiveWords
                    )
                    val primary = outcome.primary
                    if (primary.isNullOrBlank()) {
                        listener.onFailure(
                            SpeechRecognizer.ERROR_NO_MATCH,
                            EmaRecognizerFallback.userMessage(SpeechRecognizer.ERROR_NO_MATCH, kind, activeMode),
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

        private fun handleStepError(
            gen: Int,
            kind: EmaRecognizerKind,
            errorCode: Int,
            diagnosticDetail: String? = null
        ) {
            if (!active || gen != generation) return

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
            val generic = EmaRecognizerFallback.userMessage(
                errorCode = errorCode,
                kind = kind,
                mode = activeMode
            )
            // Prefer specific hardware/environment hints (muted mic, clipping, heavy noise) when detected.
            val hasSpecificHint = !diagnosticDetail.isNullOrBlank() &&
                (errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || errorCode == SpeechRecognizer.ERROR_NO_MATCH) &&
                diagnosticDetail !in setOf(
                    "No speech detected.", "No words recognized.", "Only background noise was heard."
                )
            val finalMessage = if (hasSpecificHint) diagnosticDetail!! else generic
            listener.onFailure(errorCode, finalMessage, kind)
        }

        private fun releasePlatform() {
            runCatching { platformRecognizer?.cancel() }
            runCatching { platformRecognizer?.destroy() }
            platformRecognizer = null
        }
    }
}
