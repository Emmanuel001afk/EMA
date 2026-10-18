package com.ema.assistant

/**
 * Chooses the text-to-speech voice EMA speaks with.
 *
 * Android exposes installed voices with a name, locale, quality, latency, a
 * network flag and a "notInstalled" feature, but *no* gender or timbre field.
 * Picking `voices.first()` or the engine default is how users end up with a
 * voice they dislike. This selector ranks what is actually installed:
 *
 *  - never a voice that is not downloaded, and never a network voice unless
 *    the user allowed online voices (EMA must keep speaking offline)
 *  - the user's language first, then higher synthesis quality, lower latency
 *  - the requested style: "warm" prefers lower-pitched voices. Because the API
 *    has no gender, this relies on published Google voice codes whose speakers
 *    are known (en-US iol/iom/tpd and en-GB rjs are male, measured around
 *    130-140 Hz; sfg/iob/iog/tpc/tpf, en-GB fis and en-NG tfn are female) and on
 *    "#male"/"#female" variant names. Unknown voices are neutral, never guessed.
 *
 * Pure Kotlin; the Android side converts android.speech.tts.Voice into
 * [VoiceInfo] and applies the result together with [Style.pitch]/[Style.rate].
 */
object EmaVoiceSelector {

    data class VoiceInfo(
        val name: String,
        /** BCP-47 style tag, e.g. "en-US". */
        val locale: String,
        /** android.speech.tts.Voice.QUALITY_* (100..500). */
        val quality: Int = 300,
        /** android.speech.tts.Voice.LATENCY_* (100..500). */
        val latency: Int = 300,
        val requiresNetwork: Boolean = false,
        val notInstalled: Boolean = false
    ) {
        val language: String get() = locale.substringBefore('-').substringBefore('_').lowercase()
        val region: String get() = locale.substringAfter('-', "").substringAfter('_', "").uppercase()
    }

    enum class Style(val key: String, val label: String, val pitch: Float, val rate: Float, val prefersMale: Boolean?) {
        WARM("warm", "Warm and deep (recommended)", 0.92f, 0.97f, true),
        CLEAR("clear", "Clear and bright", 1.0f, 1.0f, false),
        SYSTEM("system", "Phone default voice", 1.0f, 1.0f, null);

        companion object {
            fun fromKey(key: String?): Style = values().firstOrNull { it.key == key } ?: WARM
        }
    }

    private val MALE_CODES = setOf("iol", "iom", "tpd", "rjs")
    private val FEMALE_CODES = setOf("sfg", "iob", "iog", "tpc", "tpf", "fis", "tfn")

    /** true = known lower-pitched (male) speaker, false = known female, null = unknown. */
    fun knownMale(name: String): Boolean? {
        val n = name.lowercase()
        if (n.contains("#male")) return true
        if (n.contains("#female")) return false
        val code = Regex("^[a-z]{2,3}-[a-z]{2}-x-([a-z]{3})").find(n)?.groupValues?.get(1) ?: return null
        return when (code) { in MALE_CODES -> true; in FEMALE_CODES -> false; else -> null }
    }

    fun usable(v: VoiceInfo, allowNetwork: Boolean): Boolean = !v.notInstalled && (allowNetwork || !v.requiresNetwork)

    fun score(v: VoiceInfo, style: Style, userLocale: String): Int {
        val userLang = userLocale.substringBefore('-').substringBefore('_').lowercase()
        val userRegion = userLocale.substringAfter('-', "").substringAfter('_', "").uppercase()
        var s = 0
        if (v.language == userLang) s += 200
        if (v.language == "en" && userLang != "en") s += 20 // EMA's replies are English
        if (userRegion.isNotBlank() && v.region == userRegion) s += 30
        s += (v.quality / 100) * 20
        s += ((500 - v.latency).coerceAtLeast(0) / 100) * 3
        when (style.prefersMale) {
            true -> s += when (knownMale(v.name)) { true -> 60; false -> -20; null -> 0 }
            false -> s += when (knownMale(v.name)) { false -> 60; true -> -10; null -> 0 }
            null -> Unit
        }
        if (v.name.endsWith("-language")) s -= 5
        if (v.name.contains("-local")) s += 5
        return s
    }

    data class Choice(val voice: VoiceInfo?, val reason: String)

    /**
     * @param saved a voice the user picked by name; honoured when still usable.
     * @return null voice means "leave the engine default" (style SYSTEM or nothing usable).
     */
    fun choose(
        voices: List<VoiceInfo>,
        saved: String?,
        style: Style,
        userLocale: String,
        allowNetwork: Boolean
    ): Choice {
        val usable = voices.filter { usable(it, allowNetwork) }
        if (!saved.isNullOrBlank()) {
            usable.firstOrNull { it.name == saved }?.let { return Choice(it, "your chosen voice") }
        }
        if (style == Style.SYSTEM) return Choice(null, "the phone's default voice")
        if (usable.isEmpty()) return Choice(null, if (voices.isEmpty()) "the phone's default voice (no voices reported)" else "the phone's default voice (no installed offline voice)")
        val best = usable.maxWithOrNull(compareBy<VoiceInfo> { score(it, style, userLocale) }.thenBy { it.name })!!
        val why = buildString {
            append(describe(best))
            if (saved != null && saved.isNotBlank()) append(" (your saved voice is no longer available)")
        }
        return Choice(best, why)
    }

    fun ranked(voices: List<VoiceInfo>, style: Style, userLocale: String, allowNetwork: Boolean): List<VoiceInfo> =
        voices.filter { usable(it, allowNetwork) }
            .sortedWith(compareByDescending<VoiceInfo> { score(it, style, userLocale) }.thenBy { it.name })

    /** Honest, checked description: only facts the engine reported. */
    fun describe(v: VoiceInfo): String {
        val quality = when {
            v.quality >= 500 -> "very high quality"
            v.quality >= 400 -> "high quality"
            v.quality >= 300 -> "normal quality"
            else -> "basic quality"
        }
        val tone = when (knownMale(v.name)) { true -> "deeper voice"; false -> "higher voice"; null -> null }
        return listOfNotNull(v.locale, tone, quality, if (v.requiresNetwork) "needs internet" else "works offline").joinToString(" · ")
    }
}
