package com.ema.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Speech input that is aware of what the user has taught EMA.
 *
 * Two reliability rules:
 *  - Never bind the *default* recogniser. Once EMA also offers a
 *    [EmaRecognitionService] (required for digital-assistant registration),
 *    the default resolution can point back at EMA and recurse. EMA always uses
 *    an explicit component: the on-device recogniser on API 31+, otherwise an
 *    installed recognition service that is not EMA itself.
 *  - Ask for several hypotheses and prefer one that contains a saved shortcut
 *    trigger or an installed app label, instead of blindly taking the first.
 *    On API 33+ those same strings are passed as recognition biasing strings.
 */
object EmaSpeechRecognition {

    /** RecognizerIntent.EXTRA_BIASING_STRINGS (API 33) as a stable literal. */
    const val EXTRA_BIASING_STRINGS = "android.speech.extra.BIASING_STRINGS"

    private const val MAX_HINTS = 200

    /** Shortcut triggers + installed app labels. No message or reminder text. */
    fun vocabulary(context: Context): ArrayList<String> {
        val out = LinkedHashSet<String>()
        runCatching { EmaMemoryStore.load(context).shortcuts.keys }.getOrDefault(emptySet())
            .forEach { trigger -> if (trigger.isNotBlank()) out += trigger }
        runCatching { EmaAppActionEngine(context).installedAppLabels() }.getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .forEach { label -> out += label }
        return ArrayList(out.take(MAX_HINTS))
    }

    /**
     * Prefer a hypothesis that matches a learned trigger or an installed app
     * name; otherwise keep the recogniser's own first choice.
     */
    fun chooseHypothesis(results: List<String>?, vocabulary: List<String>): String? {
        val hypotheses = results.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
        if (hypotheses.isEmpty()) return null
        val hints = vocabulary.map { EmaAppResolver.normalize(it) }.filter { it.isNotBlank() }
        if (hints.isEmpty()) return hypotheses.first()
        val normalized = hypotheses.map { EmaAppResolver.normalize(it) }
        // An exact learned phrase beats a longer sentence that merely contains
        // it, which is the whole point of biasing speech toward the trigger.
        normalized.firstOrNull { it in hints }?.let { return hypotheses[normalized.indexOf(it)] }
        normalized.firstOrNull { hypothesis -> hints.any { hint -> hypothesis.contains(hint) } }
            ?.let { return hypotheses[normalized.indexOf(it)] }
        return hypotheses.first()
    }

    /** Recognition intent with several results, and biasing where Android supports it. */
    fun intent(
        vocabulary: List<String>,
        partialResults: Boolean,
        language: java.util.Locale,
        kind: EmaRecognizerKind? = null
    ): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            if (kind == EmaRecognizerKind.VOSK_OFFLINE) putExtra(EmaRecognitionService.EXTRA_FORCE_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            if (partialResults) putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (Build.VERSION.SDK_INT >= 33 && vocabulary.isNotEmpty()) {
                putStringArrayListExtra(EXTRA_BIASING_STRINGS, ArrayList(vocabulary.take(MAX_HINTS)))
            }
        }

    /**
     * An explicit-component recogniser, or null when the phone has no
     * recognition service other than EMA. Never falls back to the default.
     */
    fun createRecognizer(context: Context): SpeechRecognizer? =
        onDeviceRecognizer(context) ?: externalRecognizer(context)

    /** A recogniser together with the kind it is, so EMA can fall back. */
    data class Recognizer(val recognizer: SpeechRecognizer, val kind: EmaRecognizerKind)

    /**
     * Create a recogniser, preferring the kind that worked last time.
     *
     * The on-device recogniser is tried first unless a previous session proved
     * that another one works better on this phone. When the preferred kind
     * cannot even be created, the other one is used: a recogniser that does
     * not exist must not stop the user from speaking to EMA.
     */
    fun create(context: Context, preferred: EmaRecognizerKind? = preferredKind(context)): Recognizer? {
        val order = when (preferred) {
            EmaRecognizerKind.EXTERNAL -> listOf(EmaRecognizerKind.EXTERNAL, EmaRecognizerKind.ON_DEVICE, EmaRecognizerKind.VOSK_OFFLINE)
            EmaRecognizerKind.VOSK_OFFLINE -> listOf(EmaRecognizerKind.VOSK_OFFLINE, EmaRecognizerKind.ON_DEVICE, EmaRecognizerKind.EXTERNAL)
            else -> listOf(EmaRecognizerKind.ON_DEVICE, EmaRecognizerKind.EXTERNAL, EmaRecognizerKind.VOSK_OFFLINE)
        }
        for (kind in order) {
            val recognizer = when (kind) {
                EmaRecognizerKind.ON_DEVICE -> onDeviceRecognizer(context)
                EmaRecognizerKind.EXTERNAL -> externalRecognizer(context)
                EmaRecognizerKind.VOSK_OFFLINE -> offlineRecognizer(context)
            }
            if (recognizer != null) return Recognizer(recognizer, kind)
        }
        return null
    }

    /** The on-device recogniser, or null when this phone has none. */
    fun onDeviceRecognizer(context: Context): SpeechRecognizer? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val available = runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
            .getOrDefault(false)
        if (!available) return null
        return runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
    }

    /**
     * Another installed recognition service, addressed by its explicit
     * component name. EMA's own service is never one of them.
     */
    fun externalRecognizer(context: Context): SpeechRecognizer? {
        val candidates = runCatching {
            context.packageManager.queryIntentServices(
                Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0
            ).mapNotNull { it.serviceInfo }
                .filter { it.packageName != context.packageName }
        }.getOrDefault(emptyList())
        // Prefer the system/Google recogniser when several are installed, but
        // any installed service is valid; this is not a per-app special case.
        val ordered = candidates.sortedByDescending { info ->
            if (info.packageName.contains("google")) 1 else 0
        }
        for (info in ordered) {
            val component = ComponentName(info.packageName, info.name)
            runCatching { SpeechRecognizer.createSpeechRecognizer(context, component) }
                .getOrNull()?.let { return it }
        }
        return null
    }

    /**
     * The optional Vosk command backend. It is English-only, user-enabled, and
     * refused on low-memory phones or while the background Vosk wake service
     * owns a model, avoiding a second large model load.
     */
    fun offlineRecognizer(context: Context): SpeechRecognizer? {
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OFFLINE_ENABLED, false)) return null
        if (!Locale.getDefault().language.equals("en", ignoreCase = true)) return null
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        if (EmaWakeWordService.isRunning()) return null
        val manager = context.getSystemService(android.app.ActivityManager::class.java) ?: return null
        if (manager.isLowRamDevice) return null
        val memory = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        if (memory.availMem < MIN_OFFLINE_MEMORY_BYTES) return null
        val component = ComponentName(context, EmaRecognitionService::class.java)
        return runCatching { SpeechRecognizer.createSpeechRecognizer(context, component) }.getOrNull()
    }

    fun isOfflineEnabled(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OFFLINE_ENABLED, false)
    }.getOrDefault(false)

    fun setOfflineEnabled(context: Context, enabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_OFFLINE_ENABLED, enabled).apply()
        }
    }

    /** The next distinct backend after a failure; never returns the same backend. */
    fun nextAfterFailure(context: Context, current: EmaRecognizerKind): Recognizer? {
        val next = when (current) {
            EmaRecognizerKind.ON_DEVICE -> externalRecognizer(context)?.let { Recognizer(it, EmaRecognizerKind.EXTERNAL) }
                ?: offlineRecognizer(context)?.let { Recognizer(it, EmaRecognizerKind.VOSK_OFFLINE) }
            EmaRecognizerKind.EXTERNAL -> offlineRecognizer(context)?.let { Recognizer(it, EmaRecognizerKind.VOSK_OFFLINE) }
            EmaRecognizerKind.VOSK_OFFLINE -> null
        }
        return next
    }

    /** The kind of recogniser that worked last time on this phone. */
    fun preferredKind(context: Context): EmaRecognizerKind? {
        val stored = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECOGNIZER, null)
        }.getOrNull() ?: return null
        return runCatching { EmaRecognizerKind.valueOf(stored) }
            .getOrNull()?.takeUnless { it == EmaRecognizerKind.VOSK_OFFLINE }
    }

    /** Remember the kind that worked, so the next request starts with it. */
    fun rememberKind(context: Context, kind: EmaRecognizerKind) {
        if (kind == EmaRecognizerKind.VOSK_OFFLINE) return
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_RECOGNIZER, kind.name).apply()
        }
    }

    private const val PREFS = "ema_speech"
    private const val KEY_RECOGNIZER = "recognizer_kind"
    private const val KEY_OFFLINE_ENABLED = "offline_command_stt_enabled"
    private const val MIN_OFFLINE_MEMORY_BYTES = 220L * 1024L * 1024L
}
