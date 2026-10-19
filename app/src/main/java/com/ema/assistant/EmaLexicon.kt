package com.ema.assistant

/**
 * EMA's built-in dictionary.
 *
 * One structured place for the words EMA understands, so every parser agrees:
 * the command grammar, the semantic parser, the device-settings parser, the
 * alarm parser, the planner and the instruction parser all read their verbs,
 * targets and values from here instead of each keeping its own literal
 * strings.
 *
 * It is code, not a screen: the user never sees a vocabulary page. It is also
 * not a phrase list of whole sentences - a sentence is understood because its
 * parts are known (verb + target + value), so new wordings work without new
 * entries.
 *
 * Pure Kotlin, unit tested, and the same on every phone.
 */
object EmaLexicon {

    // ------------------------------------------------------------- intents

    /**
     * What the user can ask for. [verbs] are the openings that mean the same
     * thing; [example] is the canonical wording EMA offers back when it has
     * to explain what it can do.
     */
    data class Intent(
        val id: String,
        val verbs: Set<String>,
        val example: String,
        /** Words that must not appear inside this intent's free text. */
        val excludes: Set<String> = emptySet()
    )

    val INTENTS: List<Intent> = listOf(
        Intent(
            "send-message",
            setOf("send", "message", "text", "tell", "inform", "whatsapp", "sms", "reply", "write"),
            "send John a message saying I am on my way"
        ),
        Intent(
            "call",
            setOf("call", "dial", "phone", "ring"),
            "call Ada Lovelace"
        ),
        Intent(
            "play-media",
            setOf("play", "put", "start", "listen", "stream", "resume"),
            "open Spotify and play Calm Down"
        ),
        Intent(
            "open-app",
            setOf("open", "launch", "go", "run", "start"),
            "open WhatsApp"
        ),
        Intent(
            "search",
            setOf("search", "find", "look", "lookup", "google", "browse"),
            "search weather in London"
        ),
        Intent(
            "select",
            setOf("select", "choose", "pick", "tap", "click", "press", "hit"),
            "select the first result"
        ),
        Intent(
            "alarm",
            setOf("alarm", "wake", "wakeup"),
            "set an alarm for 7 am"
        ),
        Intent(
            "timer",
            setOf("timer", "countdown"),
            "set a timer for 10 minutes"
        ),
        Intent(
            "reminder",
            setOf("remind", "reminder", "nudge"),
            "remind me to take my medicine at 9 pm"
        ),
        Intent(
            "device-setting",
            setOf("turn", "switch", "enable", "disable", "activate", "deactivate", "increase",
                "raise", "lower", "decrease", "dim", "brighten", "darken", "set", "make", "boost"),
            "increase brightness to maximum"
        ),
        Intent(
            "screen",
            setOf("read", "scroll", "swipe", "tap", "type", "copy", "capture", "screenshot", "show"),
            "read screen"
        ),
        Intent(
            "schedule",
            setOf("schedule", "plan"),
            "at 9 pm open Spotify and play Calm Down"
        ),
        Intent(
            "remember",
            setOf("remember", "save", "store", "note"),
            "remember that whenever I say 'for me' it means open the Phormi app"
        ),
        Intent(
            "navigate",
            setOf("navigate", "directions", "route"),
            "navigate to Central Park"
        )
    )

    /**
     * The verb groups that lead a request.
     *
     * A word that fits more than one group ("start" fits opening an app and
     * playing media) belongs to the first group that claims it, and the groups
     * are ordered from the most specific to the most general.
     */
    private val VERB_TO_INTENT: Map<String, Intent> = buildMap {
        for (intent in INTENTS) for (verb in intent.verbs) putIfAbsent(verb, intent)
    }

    fun intentForVerb(verb: String): Intent? = VERB_TO_INTENT[verb.lowercase()]

    /** The intent with this id, or null. */
    fun intent(id: String): Intent? = INTENTS.firstOrNull { it.id == id }

    // ------------------------------------------------------------- targets

    /**
     * Device things the user can ask about, with the words that name them.
     * The canonical id is what the rest of EMA uses; the synonyms are what
     * the user says.
     */
    val TARGETS: Map<String, Set<String>> = linkedMapOf(
        "dnd" to setOf("dnd", "do not disturb", "silent", "silent mode", "disturb", "do-not-disturb"),
        "brightness" to setOf("brightness", "screen light", "screen brightness", "display light", "bright"),
        "volume" to setOf("volume", "sound", "media volume", "ring volume", "speaker"),
        "airplane" to setOf("airplane", "aeroplane", "flight mode", "airplane mode", "aeroplane mode", "flight"),
        "wifi" to setOf("wifi", "wi fi", "wi-fi", "wireless", "wireless network", "internet"),
        "bluetooth" to setOf("bluetooth", "bt"),
        "flashlight" to setOf("flashlight", "torch", "light"),
        "screen" to setOf("screen", "display")
    )

    /**
     * The kinds of app a request can point at, with the words that name the
     * kind instead of an app. "play my playlist" names a kind, not an app.
     */
    val APP_KINDS: Map<String, Set<String>> = linkedMapOf(
        "media" to setOf("music", "song", "songs", "playlist", "playlists", "podcast", "podcasts", "audio", "album", "music player"),
        "message" to setOf("message", "messages", "chat", "chats", "text", "texts", "sms", "messaging"),
        "call" to setOf("call", "calls", "phone", "dialer", "contacts"),
        "browser" to setOf("browser", "web", "internet", "web browser"),
        "camera" to setOf("camera", "photo", "photos", "gallery", "image", "images"),
        "files" to setOf("files", "file", "file manager", "documents"),
        "notes" to setOf("notes", "note", "notepad"),
        "maps" to setOf("maps", "map", "navigation", "directions")
    )

    /** The kind a word or phrase names, or null. Longer category phrases win. */
    fun appKindFor(word: String): String? {
        val clean = word.trim().lowercase().replace(Regex("\\s+"), " ")
        for ((kind, words) in APP_KINDS) if (clean in words) return kind
        val tokens = clean.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
        for ((kind, words) in APP_KINDS) {
            if (words.any { alias -> alias.contains(' ') && alias.split(' ').all { it in tokens } }) return kind
        }
        return appKindIn(clean)
    }

    /** Category mentioned anywhere in an utterance, for learned app usage. */
    fun appKindIn(text: String): String? {
        val normalized = text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        return APP_KINDS.entries
            .flatMap { (kind, aliases) -> aliases.map { alias -> kind to alias } }
            .sortedByDescending { it.second.length }
            .firstOrNull { (_, alias) -> Regex("\\b" + Regex.escape(alias) + "\\b").containsMatchIn(normalized) }
            ?.first
    }

    /** The canonical id for a phrase, or null when it names nothing known. */
    fun canonicalTarget(phrase: String): String? {
        val text = " ${phrase.lowercase().replace(Regex("[^a-z0-9% ]"), " ").replace(Regex("\\s+"), " ").trim()} "
        var best: String? = null
        var bestLength = 0
        for ((id, names) in TARGETS) {
            for (name in names) {
                val needle = " ${name.replace('-', ' ')} "
                if (needle.isNotBlank() && text.contains(needle) && name.length > bestLength) {
                    best = id
                    bestLength = name.length
                }
            }
        }
        return best
    }

    // -------------------------------------------------------------- values

    val MAX_VALUES = setOf("max", "maximum", "full", "brightest", "highest", "all the way up", "100", "100 percent", "100%")
    val MIN_VALUES = setOf("min", "minimum", "lowest", "dimmest", "all the way down", "0", "0 percent", "0%")
    val HALF_VALUES = setOf("half", "50", "50 percent", "50%", "halfway")
    val QUARTER_VALUES = setOf("quarter", "25", "25 percent", "25%")

    /** Words that mean "as high as it goes". */
    val MAX_WORDS = setOf("maximum", "max", "full", "brightest", "highest", "all the way up")

    /** Words that mean "as low as it goes". */
    val MIN_WORDS = setOf("minimum", "min", "lowest", "dimmest", "all the way down")

    /** Verbs that raise a value. */
    val INCREASE_WORDS = setOf("increase", "raise", "turn up", "bump up", "brighter", "louder", "higher", "up", "boost")

    /** Verbs that lower a value. */
    val DECREASE_WORDS = setOf("decrease", "lower", "turn down", "dim", "darker", "quieter", "down")

    /** Verbs that switch something on. */
    val ON_WORDS = setOf("turn on", "switch on", "enable", "activate", "on")

    /** Verbs that switch something off. */
    val OFF_WORDS = setOf("turn off", "switch off", "disable", "deactivate", "off")

    /** Number words EMA understands in a value slot. */
    val NUMBER_WORDS: Map<String, Int> = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90, "hundred" to 100
    )

    fun numberValue(word: String): Int? = NUMBER_WORDS[word.lowercase().trim()]

    // ----------------------------------------------------------- time words

    val TIME_WORDS = setOf(
        "today", "tonight", "tomorrow", "morning", "afternoon", "evening", "night",
        "noon", "midnight", "now", "later", "next", "last", "this", "every", "each",
        "weekdays", "weekends", "weekday", "weekend", "daily", "once", "only", "twice"
    )

    /** Words that mean the request repeats rather than happening once. */
    val REPEAT_WORDS = setOf("every", "each", "daily", "weekdays", "weekend", "weekends", "always")

    // ------------------------------------------------- fillers and politeness

    val POLITENESS = setOf("please", "kindly", "pls", "plz", "thanks", "thank", "sorry")

    val FILLERS = setOf(
        "um", "uh", "erm", "er", "ah", "hmm", "mhm", "well", "so", "like", "just",
        "actually", "basically", "honestly", "quickly", "please", "okay", "ok", "alright",
        "right", "anyway", "whatever", "hmm", "hey", "hi", "hello"
    )

    /** Words that are noise at the start of a request. */
    private val LEADING_NOISE = Regex(
        "(?i)^(?:" +
            "hey\\s+(?:ema|emma)|ok(?:ay)?\\s+(?:ema|emma)|hi\\s+(?:ema|emma)|ema|" +
            "please|kindly|pls|plz|" +
            "(?:can|could|would|will)\\s+(?:you|u)(?:\\s+please)?|" +
            "i\\s+(?:need|want|would\\s+like|'d\\s+like|’d\\s+like)\\s+you\\s+to|" +
            "i\\s+(?:need|want|have\\s+got|would\\s+like|'d\\s+like|’d\\s+like)\\s+to|" +
            "help\\s+me|go\\s+ahead\\s+and|quickly|um+|uh+|erm|" +
            "okay|ok|alright|yo|hey|so|just|well" +
            ")(?:\\s*[,;:!.]\\s*|\\s+)"
    )

    /**
     * Remove the wake word, politeness and fillers from the front of a
     * request. The words themselves are never removed from the inside of a
     * message body, a search query or a reminder.
     */
    fun stripLeadings(text: String): String {
        var out = text.trim()
        var guard = 0
        while (guard++ < 8) {
            val match = LEADING_NOISE.find(out) ?: break
            val rest = out.substring(match.range.last + 1).trimStart()
            if (rest.isBlank()) break
            out = rest
        }
        return out.trim()
    }

    /** The first meaningful word of a request, lower case. */
    fun firstVerb(text: String): String? =
        stripLeadings(text).split(Regex("[\\s,;:!?]+")).firstOrNull { it.isNotBlank() }?.lowercase()

    // ------------------------------------------------------- mishearings

    /** Small on-device seed equivalences; user-taught aliases take precedence later. */
    val BASE_SYNONYMS: Map<String, String> = linkedMapOf(
        "mommy" to "mummy",
        "web browser" to "browser",
        "photo gallery" to "gallery",
        "music player" to "music"
    )

    /**
     * Words the speech recogniser produces when it heard something else.
     * The mapping is to the word EMA meant, not to a command: the request
     * still has to be understood by the normal layers.
     */
    val MISHEARINGS: Map<String, String> = linkedMapOf(
        "a mma" to "ema",
        "amma" to "ema",
        "emma" to "ema",
        "em a" to "ema",
        "ammar" to "ema",
        "e m a" to "ema",
        "what's up" to "whatsapp",
        "whats app" to "whatsapp",
        "what sup" to "whatsapp",
        "watsapp" to "whatsapp",
        "spot if i" to "spotify",
        "spot ify" to "spotify",
        "spotfy" to "spotify",
        "play spot" to "play spotify"
    )

    /** The words that open the content of a message, a search or a reminder. */
    private val CONTENT_MARKER = Regex("(?i)\\b(?:that says|saying|to say|which says|that reads)\\b")

    /**
     * True when the request carries content the owner wrote: the body of a
     * message, a search or a reminder.
     *
     * Content is never a command. A setting, a shortcut or a learned link
     * must not fire inside it.
     */
    fun isContentCommand(command: String): Boolean {
        val verb = firstVerb(command) ?: return false
        return intentForVerb(verb)?.id in setOf("send-message", "search", "reminder")
    }

    /** Also catch a content action following an app clause or sequence connector. */
    private fun hasContentIntent(command: String): Boolean = isContentCommand(command) ||
        Regex("(?i)\\b(?:send|message|text|email|search|find|look\\s+up|remind(?:er)?)\\b")
            .containsMatchIn(command)

    /**
     * The content of a request: the body of a message, a search or a
     * reminder. Null when the request has none.
     */
    fun contentOf(command: String): String? {
        if (!isContentCommand(command)) return null
        val match = CONTENT_MARKER.find(command) ?: return null
        return command.substring(match.range.last + 1).trim().ifBlank { null }
    }

    /**
     * The command without the content body.
     *
     * "send John a message saying hi" becomes "send John a message saying",
     * so a parser can work on the command and never on the content.
     */
    fun withoutContent(command: String): String {
        if (!isContentCommand(command)) return command
        val match = CONTENT_MARKER.find(command) ?: return command
        return command.substring(0, match.range.last + 1).trim()
    }

    /**
     * Replace a misheard phrase with the word EMA meant. Only whole phrases
     * are replaced, and never inside a message body, a query or a reminder.
     */
    fun applyMishearings(text: String, inFreeText: Boolean = false): String {
        if (inFreeText) return text
        // Search and reminder tails are free text even when their query happens
        // to contain a word such as "saying"; leave the entire utterance intact.
        if (Regex("(?i)\\b(?:search|find|look\\s+up|remind(?:er)?)\\b").containsMatchIn(text)) return text
        // When a message verb appears after an app clause, protect everything
        // after the content marker as well (e.g. "on WhatsApp, message X saying Y").
        val marker = CONTENT_MARKER.find(text)
        if (hasContentIntent(text) && marker == null) return text
        val head = if (hasContentIntent(text) && marker != null) text.substring(0, marker.range.last + 1) else withoutContent(text)
        val content = text.substring(head.length)
        var out = head
        for ((heard, meant) in BASE_SYNONYMS + MISHEARINGS) {
            if (out.lowercase().contains(heard)) {
                out = out.replace(Regex("(?i)\\b" + Regex.escape(heard) + "\\b"), meant)
            }
        }
        return out + content
    }

    /**
     * Apply explicitly taught surface forms to the command portion only.
     * Multi-word phrases are replaced longest-first and mappings can chain,
     * with cycle and depth guards. Message/search/reminder content is never
     * rewritten here; contact aliases are resolved in their dedicated slot.
     */
    fun applyLearnedAliases(text: String, aliases: Map<String, String>): String {
        if (aliases.isEmpty()) return text
        val marker = CONTENT_MARKER.find(text)
        // Search queries and reminder bodies have no universal boundary marker,
        // so leave those utterances whole. For a message with a content marker,
        // aliases may still normalize the command prefix, never the body.
        val protectWhole = Regex("(?i)\\b(?:search|find|look\\s+up|remind(?:er)?)\\b").containsMatchIn(text) ||
            (hasContentIntent(text) && marker == null)
        if (protectWhole) return text
        val splitAt = if (hasContentIntent(text)) marker?.range?.last?.plus(1) ?: text.length else text.length
        val prefix = text.substring(0, splitAt)
        val content = text.substring(splitAt)
        var result = prefix
        val seen = mutableSetOf<String>()
        repeat(4) {
            val key = result.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()
            if (!seen.add(key)) return result + content
            var changed = false
            for ((surface, meaning) in aliases.entries.sortedByDescending { it.key.length }) {
                val from = surface.trim().replace(Regex("\\s+"), " ")
                val to = meaning.trim()
                if (from.isBlank() || to.isBlank() || from.equals(to, ignoreCase = true)) continue
                val phrasePattern = from.split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
                val optionalLead = if (from.startsWith("my ") || from.startsWith("our ") || from.startsWith("the ")) "" else "(?:(?:my|our|the)\\s+)?"
                val pattern = Regex("(?i)(?<![\\p{L}\\p{N}])" + optionalLead + phrasePattern + "(?![\\p{L}\\p{N}])")
                if (pattern.containsMatchIn(result)) {
                    val next = pattern.replace(result, to)
                    val nextKey = next.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()
                    if (next != result && nextKey !in seen) { result = next; seen += nextKey; changed = true }
                }
            }
            if (!changed) return result + content
        }
        return result + content
    }

    // -------------------------------------------------------- suggestions

    /**
     * The closest things EMA can actually do for a request it did not
     * understand. Used for the honest answer when no layer matched: a bare
     * "I can't" is a dead end, while "here is what I can do with that" is
     * help.
     */
    fun suggestionsFor(command: String): List<String> {
        val cleaned = stripLeadings(command)
        val words = cleaned.lowercase().split(Regex("[^a-z0-9' ]+")).filter { it.isNotBlank() }
        val intent = words.firstOrNull()?.let(::intentForVerb)
        return if (intent != null) {
            listOf("I can help with ${intent.id} requests when the target and needed details are clear.")
        } else {
            listOf("EMA can control installed apps, handle media, messages and calls, manage alarms and reminders, and read or control the screen.")
        }
    }
}
