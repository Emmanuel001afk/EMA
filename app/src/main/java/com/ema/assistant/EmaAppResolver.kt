package com.ema.assistant

/**
 * Deterministic application resolution.
 *
 * "Open any app" only works if EMA can map messy spoken text onto a real
 * installed application. Speech produces "whatsapp", "WhatsApp Messenger",
 * "you tube", "insta", "the browser", "messages", and the launcher label may
 * be any of several variants.
 *
 * This object is pure: it is handed the list of applications that are actually
 * installed on the device and returns a match plus a confidence score. Keeping
 * it free of Android types means the whole resolution table is unit testable
 * and behaves identically on every device.
 */
object EmaAppResolver {

    data class App(val label: String, val packageName: String)

    data class Match(val app: App, val score: Int, val via: String) {
        val confident: Boolean get() = score >= EXACT_SCORE - 20
    }

    private const val EXACT_SCORE = 1000
    private const val ALIAS_SCORE = 950
    private const val WORDS_SCORE = 850
    private const val PREFIX_SCORE = 700
    private const val SUBSTRING_SCORE = 500
    private const val PACKAGE_SCORE = 400

    /**
     * Spoken aliases mapped to substrings of a real application label or
     * package name. Values are matched against what is installed, never
     * hardcoded to one vendor's exact label, so this survives OEM skins and
     * regional builds.
     */
    private val ALIASES: Map<String, List<String>> = linkedMapOf(
        "whatsapp" to listOf("whatsapp"),
        "wa" to listOf("whatsapp"),
        "whatsapp messenger" to listOf("whatsapp"),
        "messenger" to listOf("messenger"),
        "fb messenger" to listOf("messenger"),
        "facebook messenger" to listOf("messenger"),
        "facebook" to listOf("facebook"),
        "fb" to listOf("facebook"),
        "telegram" to listOf("telegram"),
        "tg" to listOf("telegram"),
        "signal" to listOf("signal"),
        "instagram" to listOf("instagram"),
        "insta" to listOf("instagram"),
        "ig" to listOf("instagram"),
        "youtube" to listOf("youtube"),
        "yt" to listOf("youtube"),
        "you tube" to listOf("youtube"),
        "spotify" to listOf("spotify"),
        "apple music" to listOf("music"),
        "youtube music" to listOf("youtube music"),
        "soundcloud" to listOf("soundcloud"),
        "audible" to listOf("audible"),
        "podcasts" to listOf("podcast"),
        "chrome" to listOf("chrome"),
        "browser" to listOf("chrome", "browser", "firefox", "edge", "samsung internet", "opera", "brave"),
        "web browser" to listOf("chrome", "browser", "firefox", "edge"),
        "default browser" to listOf("chrome", "browser", "firefox", "edge"),
        "gmail" to listOf("gmail"),
        "mail" to listOf("gmail", "mail", "email", "outlook"),
        "email" to listOf("gmail", "mail", "email", "outlook"),
        "outlook" to listOf("outlook"),
        "messages" to listOf("messages", "messaging", "sms"),
        "message" to listOf("messages", "messaging", "sms"),
        "sms" to listOf("messages", "messaging", "sms"),
        "text messages" to listOf("messages", "messaging", "sms"),
        "phone" to listOf("phone", "dialer", "calls"),
        "dialer" to listOf("phone", "dialer", "calls"),
        "calls" to listOf("phone", "dialer", "calls"),
        "contacts" to listOf("contacts", "people"),
        "camera" to listOf("camera"),
        "clock" to listOf("clock", "alarm"),
        "alarm" to listOf("clock", "alarm"),
        "timer" to listOf("clock", "timer"),
        "calendar" to listOf("calendar"),
        "settings" to listOf("settings"),
        "maps" to listOf("maps", "google maps"),
        "google maps" to listOf("maps"),
        "navigation" to listOf("maps", "waze", "navigation"),
        "waze" to listOf("waze"),
        "uber" to listOf("uber"),
        "drive" to listOf("drive"),
        "google drive" to listOf("drive"),
        "photos" to listOf("photos", "gallery"),
        "gallery" to listOf("gallery", "photos"),
        "files" to listOf("files", "file manager", "my files"),
        "file manager" to listOf("files", "file manager", "my files"),
        "twitter" to listOf("twitter", "x"),
        "tiktok" to listOf("tiktok", "tik tok"),
        "snapchat" to listOf("snapchat"),
        "linkedin" to listOf("linkedin"),
        "reddit" to listOf("reddit"),
        "discord" to listOf("discord"),
        "slack" to listOf("slack"),
        "zoom" to listOf("zoom"),
        "teams" to listOf("teams"),
        "netflix" to listOf("netflix"),
        "amazon" to listOf("amazon"),
        "shazam" to listOf("shazam"),
        "calculator" to listOf("calculator"),
        "notes" to listOf("notes", "keep", "notepad"),
        "google keep" to listOf("keep"),
        "weather" to listOf("weather"),
        "play store" to listOf("play store"),
        "app store" to listOf("play store"),
        "google play" to listOf("play store"),
        "recorder" to listOf("recorder", "voice recorder"),
        "radio" to listOf("radio"),
        "wallet" to listOf("wallet"),
        "translate" to listOf("translate"),
        "duolingo" to listOf("duolingo"),
        "steam" to listOf("steam"),
        "twitch" to listOf("twitch"),
        "pinterest" to listOf("pinterest"),
        "threads" to listOf("threads")
    )

    /** Words that are decoration in a spoken app reference, never part of a name. */
    private val NOISE = setOf(
        "the", "app", "application", "for", "me", "my", "please", "open", "launch",
        "start", "up", "on", "in", "into", "some", "a", "an"
    )

    fun normalize(value: String): String = value
        .lowercase()
        .replace('\u00a0', ' ')
        .replace(Regex("[’'\\-_.]"), " ")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Strip leading/trailing decoration: "the WhatsApp app" -> "whatsapp". */
    fun stripNoise(value: String): String {
        var words = normalize(value).split(' ').filter { it.isNotBlank() }.toMutableList()
        while (words.isNotEmpty() && words.first() in NOISE) words.removeAt(0)
        while (words.isNotEmpty() && words.last() in NOISE) words.removeAt(words.size - 1)
        return words.joinToString(" ")
    }

    /**
     * Resolve a spoken app reference against the installed applications.
     *
     * Deterministic tie-breaking: highest score, then longest matching label,
     * then alphabetical. The same input always produces the same app.
     */
    fun resolve(reference: String, installed: List<App>): Match? {
        val cleaned = stripNoise(reference)
        if (cleaned.isBlank()) return null
        val fallback = normalize(reference)

        val scored = mutableListOf<Match>()
        for (app in installed) {
            val label = normalize(app.label)
            val pkg = app.packageName.lowercase()
            if (label.isBlank()) continue

            val best = mutableListOf<Pair<Int, String>>()

            if (label == cleaned) best += EXACT_SCORE to "exact-label"
            if (pkg == cleaned || pkg == cleaned.replace(' ', '.')) best += EXACT_SCORE to "exact-package"

            aliasTargets(cleaned)?.let { targets ->
                // An exact label hit outranks a containment hit. Without this
                // distinction "wa" and "you tube" resolve to whichever matching
                // app happens to have the longest label, so "you tube" lands on
                // YouTube Music and "wa" lands on WhatsApp Business.
                val exactTarget = targets.any { target ->
                    label == target || pkg == target.replace(' ', '.')
                }
                val containsTarget = targets.any { target ->
                    label.contains(target) ||
                        pkg.contains(target.replace(' ', '.')) ||
                        pkg.contains(target.replace(' ', '_'))
                }
                when {
                    exactTarget -> best += (ALIAS_SCORE + 30) to "alias-exact"
                    containsTarget -> best += ALIAS_SCORE to "alias"
                }
            }

            // Every spoken word present in the label: "youtube music" -> YouTube Music.
            val spokenWords = cleaned.split(' ').filter { it.length > 1 }
            if (spokenWords.size > 1 && spokenWords.all { label.contains(it) }) {
                best += WORDS_SCORE to "all-words"
            }
            if (label.startsWith(cleaned) || cleaned.startsWith(label)) {
                best += PREFIX_SCORE to "prefix"
            }
            if (label.contains(cleaned) || cleaned.contains(label)) {
                best += SUBSTRING_SCORE to "substring"
            }
            // Package names are the most stable identity on a device: "com.spotify.music".
            val pkgLeaf = pkg.substringAfterLast('.').replace('_', ' ')
            if (pkgLeaf.isNotBlank() && (pkgLeaf == cleaned || pkgLeaf.contains(cleaned) || cleaned.contains(pkgLeaf))) {
                best += PACKAGE_SCORE to "package-leaf"
            }
            if (best.isEmpty() && fallback != cleaned && fallback.isNotBlank()) {
                if (label == fallback || label.contains(fallback)) best += SUBSTRING_SCORE to "raw-substring"
            }

            best.maxByOrNull { it.first }?.let { scored += Match(app, it.first, it.second) }
        }

        return scored
            .sortedWith(
                compareByDescending<Match> { it.score }
                    .thenByDescending { it.app.label.length }
                    .thenBy { it.app.label.lowercase() }
            )
            .firstOrNull()
    }

    private fun aliasTargets(cleaned: String): List<String>? {
        ALIASES[cleaned]?.let { return it }
        // Also try each word so "open the whatsapp" still resolves via alias.
        for (word in cleaned.split(' ')) {
            ALIASES[word]?.let { return it }
        }
        return null
    }

    data class SpanMatch(val app: App, val start: Int, val end: Int, val matched: String)

    /**
     * Find the longest installed application name mentioned anywhere inside a
     * free-form sentence.
     *
     * This is what lets the offline assistant understand requests it has no
     * explicit grammar rule for: instead of guessing, it grounds the app slot
     * in software that is really on the device. Returns the match plus its span
     * so the caller can remove it and treat the remainder as the action text.
     */
    fun findSpan(utterance: String, installed: List<App>): SpanMatch? {
        val haystack = utterance.lowercase()
        var best: SpanMatch? = null
        for (app in installed) {
            for (candidate in appCandidates(app)) {
                if (candidate.length < 2) continue
                val index = findWordBounded(haystack, candidate)
                if (index < 0) continue
                val span = SpanMatch(app, index, index + candidate.length, utterance.substring(index, index + candidate.length))
                if (best == null || candidate.length > best.matched.length) best = span
            }
        }
        return best
    }

    private fun appCandidates(app: App): Set<String> {
        val out = linkedSetOf<String>()
        val label = normalize(app.label)
        if (label.isNotBlank()) out += label
        val pkgLeaf = app.packageName.lowercase().substringAfterLast('.').replace('_', ' ').replace('.', ' ')
        if (pkgLeaf.isNotBlank() && pkgLeaf !in NOISE) out += pkgLeaf
        for ((alias, targets) in ALIASES) {
            if (targets.any { label.contains(it) || app.packageName.lowercase().contains(it.replace(' ', '.')) }) {
                if (alias.length >= 3) out += alias
            }
        }
        return out
    }

    private fun findWordBounded(haystack: String, needle: String): Int {
        if (needle.isBlank()) return -1
        var from = 0
        while (true) {
            val index = haystack.indexOf(needle, from)
            if (index < 0) return -1
            val before = if (index == 0) ' ' else haystack[index - 1]
            val afterIndex = index + needle.length
            val after = if (afterIndex >= haystack.length) ' ' else haystack[afterIndex]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return index
            from = index + 1
        }
    }

    /**
     * Remove an app span from a sentence and tidy the leftovers.
     *
     * Deleting the app name can orphan the preposition that introduced it, so
     * "play arcade on spotify now" must come back as "play arcade now" and not
     * "play arcade on now".
     */
    fun removeSpan(utterance: String, span: SpanMatch): String {
        val remainder = (utterance.substring(0, span.start) + " " + utterance.substring(span.end))
            .replace(Regex("\\s+"), " ")
            .trim()
        val preposition = "(?:on|in|inside|using|with|from|via|through)"
        val orphanFollowers = "(?:now|please|then|and|also|again|for me)"
        return remainder
            // A preposition left with nothing after it but an adverb or the end.
            .replace(Regex("(?i)\\s+$preposition\\s+(?=$orphanFollowers\\b|[,;.]|\\s*$)"), " ")
            .replace(Regex("(?i)\\s+$preposition\\s*$"), "")
            .replace(Regex("(?i)^$preposition\\s+"), "")
            .replace(Regex("(?i)^(?:the|app)\\s+"), "")
            .replace(Regex("(?i)\\s+(?:the|app)$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
