package com.ema.assistant

import java.util.Locale

/**
 * How EMA turns spoken audio into text.
 *
 * Parallel to [EmaAssistantMode]: explicit, persisted, inspectable by voice and
 * in Settings, and pure Kotlin so every scheduling and fallback rule is unit
 * tested without a device.
 */
enum class EmaSpeechMode(val key: String, val label: String, val explanation: String) {

    /**
     * Default. EMA uses its own built-in offline speech-to-text engine first:
     * no internet, no external speech app, no audio sent off the phone. If the
     * built-in engine cannot serve the request (for example a non-English
     * language or severe device memory pressure), EMA falls back to Android's
     * on-device or installed speech recognizer in the same session.
     */
    EMA_OFFLINE_FIRST(
        "ema_offline_first",
        "EMA offline first (built-in, default)",
        "EMA uses its own built-in offline speech recognizer first. Android speech is used only if the local engine is unavailable."
    ),

    /**
     * Strict private offline mode. Only EMA's own built-in offline engine is
     * ever used; voice audio never goes to any external or cloud service.
     */
    EMA_OFFLINE_ONLY(
        "ema_offline_only",
        "EMA offline only (private, no system speech)",
        "EMA uses only its built-in offline speech recognizer. Audio never leaves EMA or touches cloud speech services."
    ),

    /**
     * Platform recognizer first, with EMA's built-in offline engine ready as an
     * automatic fallback when the phone is offline or a system recognizer fails.
     */
    SYSTEM_FIRST(
        "system_first",
        "System recognizer first (EMA offline fallback)",
        "Android's speech recognizer runs first, and EMA switches automatically to its built-in offline engine when offline or if system speech fails."
    );

    /** Whether EMA's built-in offline recognizer may run in this mode. */
    val allowsEmaOffline: Boolean get() = true

    /** Whether external/platform recognizers may run in this mode. */
    val allowsSystemRecognizers: Boolean get() = this != EMA_OFFLINE_ONLY

    /** Whether EMA's built-in offline engine is the first choice. */
    val prefersEmaOffline: Boolean get() = this != SYSTEM_FIRST

    companion object {
        const val DEFAULT_KEY = "ema_offline_first"

        /**
         * Regional English BCP-47 tags advertised by [EmaRecognitionService] for
         * Android 13+ offline support discovery. The bundled acoustic model is
         * English and handles these regional tags directly offline.
         */
        val SUPPORTED_OFFLINE_LOCALES: List<String> = listOf(
            "en-US",
            "en-GB",
            "en-NG",
            "en-AU",
            "en-CA",
            "en-IN",
            "en-IE",
            "en-NZ",
            "en-ZA",
            "en-GH",
            "en-KE",
            "en-SG",
            "en"
        )

        fun fromKey(key: String?): EmaSpeechMode =
            values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: EMA_OFFLINE_FIRST

        /**
         * Normalize a raw BCP-47 / Java locale string (`"en_NG"`, `"en-us"`,
         * `"eng-USA"`) and check whether the bundled English model serves it.
         *
         * A blank tag means "caller did not specify a language", so it falls
         * back to [defaultLanguageTag].
         */
        fun supportsOfflineLanguage(
            requestedTag: String?,
            defaultLanguageTag: String = Locale.getDefault().toLanguageTag()
        ): Boolean {
            val effective = requestedTag?.trim()?.takeIf { it.isNotEmpty() } ?: defaultLanguageTag
            val normalized = effective.replace('_', '-').lowercase(Locale.ROOT)
            val primary = normalized.substringBefore('-').trim()
            return primary == "en" || primary == "eng"
        }

        /** Canonical BCP-47 tag for an English request, defaulting to `"en-US"`. */
        fun canonicalOfflineTag(requestedTag: String?): String {
            val cleaned = requestedTag?.trim()?.replace('_', '-').orEmpty()
            if (cleaned.isBlank()) return "en-US"
            val parts = cleaned.split('-').filter { it.isNotBlank() }
            val primary = parts.firstOrNull()?.lowercase(Locale.ROOT) ?: return "en-US"
            if (primary != "en" && primary != "eng") return "en-US"
            val region = parts.getOrNull(1)?.takeIf { it.length == 2 }?.uppercase(Locale.ROOT)
            return if (region != null) "en-$region" else "en-US"
        }
    }
}

/**
 * Tailors endpointing and text normalization to what the owner is doing.
 */
enum class EmaRecognitionProfile(val key: String) {
    /**
     * Interactive assistant requests: commands, multi-step plans, messaging,
     * calls, alarms, calculations and questions. Snappy after a complete
     * sentence, patient after a dangling clause ("saying", "and", "for").
     */
    COMMAND("command"),

    /**
     * Continuous offline speech-to-text dictation: longer natural pauses,
     * multi-sentence accumulation, sentence casing and spoken punctuation.
     */
    DICTATION("dictation");

    companion object {
        fun fromKey(key: String?): EmaRecognitionProfile =
            values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: COMMAND
    }
}
