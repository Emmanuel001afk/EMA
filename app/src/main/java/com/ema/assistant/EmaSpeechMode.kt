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
     * Optional fallback-enabled mode. EMA uses its own built-in offline speech-to-text engine first:
     * no internet, no external speech app, no audio sent off the phone. If the
     * built-in engine cannot serve the request (for example a non-English
     * language or severe device memory pressure), EMA falls back to Android's
     * on-device or installed speech recognizer in the same session.
     */
    EMA_OFFLINE_FIRST(
        "ema_offline_first",
        "EMA offline first (allows system fallback)",
        "EMA uses its own built-in offline speech recognizer first. Android speech is used only if the local engine is unavailable."
    ),

    /**
     * Strict private offline mode. Only EMA's own built-in offline engine is
     * ever used; voice audio never goes to any external or cloud service.
     */
    EMA_OFFLINE_ONLY(
        "ema_offline_only",
        "EMA offline only (default, private, no system speech)",
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
        const val DEFAULT_KEY = "ema_offline_only"

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
            "en-TZ",
            "en-UG",
            "en-SG",
            "en-PH",
            "en-HK",
            "en-JM",
            "en-001",
            "en"
        )

        fun fromKey(key: String?): EmaSpeechMode =
            values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: EMA_OFFLINE_ONLY

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
            val region = parts.getOrNull(1)?.let { sub ->
                when {
                    sub.length == 2 && sub.all(Char::isLetter) -> sub.uppercase(Locale.ROOT)
                    sub.length == 3 && sub.all(Char::isDigit) -> sub
                    else -> null
                }
            }
            return if (region != null) "en-$region" else "en-US"
        }
    }
}

/**
 * Tailors endpointing, normalization and rescoring to the exact interaction.
 */
enum class EmaRecognitionProfile(val key: String) {
    /**
     * Interactive assistant requests: commands, multi-step plans, messaging,
     * calls, alarms, calculations and questions. Snappy after a complete
     * phrase, patient after a dangling clause or spoken hesitation.
     */
    COMMAND("command"),

    /**
     * Continuous offline speech-to-text dictation and audio transcription:
     * generous pause tolerance, sentence/proper-noun casing, spoken punctuation,
     * spoken emails/domains and voice editing ("scratch that").
     */
    DICTATION("dictation"),

    /**
     * Concise search queries (Android `LANGUAGE_MODEL_WEB_SEARCH`): preserves
     * natural search phrasing while normalizing numbers, times and percentages.
     */
    WEB_SEARCH("web_search"),

    /**
     * Fast follow-up answers when EMA asked a clarifying question (yes, no,
     * stop, cancel, ordinal option choices, SIM 1/2, or a missing time/name).
     */
    CONFIRMATION("confirmation");

    companion object {
        fun fromKey(key: String?): EmaRecognitionProfile =
            values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: COMMAND
    }
}
