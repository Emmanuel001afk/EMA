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
    fun intent(vocabulary: List<String>, partialResults: Boolean, language: java.util.Locale): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
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
    fun createRecognizer(context: Context): SpeechRecognizer? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val onDevice = runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                .getOrDefault(false)
            if (onDevice) {
                runCatching {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                }.getOrNull()?.let { return it }
            }
        }
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
}
