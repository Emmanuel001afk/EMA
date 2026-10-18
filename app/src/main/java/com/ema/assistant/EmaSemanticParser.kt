package com.ema.assistant

/**
 * EMA's offline semantic understanding layer.
 *
 * The older layers ([EmaNativeCommandPlanner], [EmaCommandGrammar]) match
 * whole-sentence shapes, so the same request phrased in a different order,
 * split across clauses, or using a pronoun fell through. This layer instead
 * works like a small deterministic NLU pipeline:
 *
 *  1. normalise      strip wake words, politeness and fillers; tokenise while
 *                    keeping offsets into the user's text, so a message body is
 *                    always copied verbatim, never re-assembled from tokens
 *  2. segment        split into clauses only where a new *predicate* starts
 *                    ("..., and play the first one"), never inside a message
 *                    body, so "saying play the song and call me" stays content
 *  3. label roles    per clause: predicate class (message, open, find, search,
 *                    play, select, topic, other) and semantic roles - app,
 *                    recipient, body, query, selection, media kind
 *  4. ground         app slots are grounded strictly in installed apps (or a
 *                    well-known app name); ambiguity is reported, not guessed
 *  5. resolve        pronouns ("tell him") resolve to an entity mentioned in
 *                    the same request; list references ("the third one") to the
 *                    request's own search or to the fresh active task
 *  6. compose        clauses that are sub-steps of one goal collapse into it:
 *                    "open WhatsApp" + "find Daniel" + "tell him X" is one
 *                    verified send, not three unrelated actions
 *
 * Output is a typed plan of [EmaCommand]s (plus untouched clauses that the
 * proven older layers already handle), a clarification question, or
 * [Result.NotHandled] so the router keeps its existing behaviour.
 *
 * Pure Kotlin, no Android types, no network, no model files. Everything here
 * is exercised by JVM unit tests.
 */
object EmaSemanticParser {

    data class Env(
        val installedApps: List<EmaAppResolver.App> = emptyList(),
        val activeTask: EmaActiveTask? = null,
        /** User-taught names: spoken phrase -> what it means (app label or contact). */
        val aliases: Map<String, String> = emptyMap(),
        /** Preferred app per kind ("video", "music", "messaging"), already validated by the caller. */
        val defaultApps: Map<String, String> = emptyMap(),
        /** User-taught triggers -> app or command. Only app targets ground here. */
        val shortcuts: Map<String, EmaShortcuts.Target> = emptyMap()
    )

    enum class Role { RECIPIENT, BODY, APP, LIST }

    data class Step(
        val command: EmaCommand?,
        /** Clause handed to the older layers unchanged when [command] is null. */
        val legacyText: String? = null,
        /** False when this step is a separate goal that may run even if the previous failed. */
        val dependsOnPrevious: Boolean = true,
        /** Refers to the active task from an earlier request. */
        val continuation: Boolean = false
    ) {
        fun describe(): String = command?.let { describe(it) } ?: legacyText.orEmpty()
    }

    sealed class Result {
        data class Plan(val steps: List<Step>, val confidence: Double, val evidence: List<String>) : Result()
        data class Clarify(
            val question: String,
            val missing: Role,
            val partial: EmaCommand?,
            val options: List<String> = emptyList(),
            val evidence: List<String> = emptyList()
        ) : Result()
        data class NotHandled(val reason: String) : Result()
    }

    fun describe(command: EmaCommand): String = when (command) {
        is EmaCommand.SendMessage -> buildString {
            append("Message ").append(command.recipient)
            if (!command.app.isNullOrBlank()) append(" on ").append(command.app)
        }
        is EmaCommand.SearchInApp -> "Search ${command.app ?: "the web"} for ${command.query}"
        is EmaCommand.SelectResult -> (if (command.play) "Play " else "Open ") + command.selection.phrase()
        is EmaCommand.PlayMedia -> "Play ${command.query}" + (command.app?.let { " on $it" } ?: "")
        is EmaCommand.OpenApp -> "Open ${command.app}"
        is EmaCommand.OpenAppSection -> "Go to ${command.section} in ${command.app}"
        else -> command.toCanonical().replaceFirstChar { it.uppercase() }
    }

    // ------------------------------------------------------------ lexicon

    private val MESSAGE_VERBS = setOf("message", "text", "sms", "dm", "msg", "ping", "whatsapp", "imessage")
    private val MESSAGE_NOUNS = setOf(
        "message", "messages", "text", "msg", "dm", "note", "sms", "line", "whatsapp", "reply", "voice"
    )
    private val PRONOUNS = setOf("him", "her", "them", "he", "she", "they")
    private val NOT_RECIPIENTS = setOf("me", "us", "myself", "ourselves", "a", "an", "something", "anything", "nothing")
    private val APP_PREPS = setOf("on", "in", "using", "via", "through", "over", "with", "inside")
    private val CONNECTORS = setOf("and", "then", "also", "next", "finally", "after", "that", "now", "afterwards", "so", "plus")

    /** Words that start a new clause. Content words like "play" only count at a clause boundary. */
    private val CLAUSE_VERBS = setOf(
        "open", "launch", "start", "run", "go", "switch", "bring", "pull", "take", "fire",
        "search", "find", "look", "lookup", "google", "browse", "show",
        "play", "watch", "listen", "put", "stream", "resume", "pause", "stop", "skip",
        "choose", "pick", "select", "tap", "click", "press", "hit",
        "message", "text", "sms", "dm", "msg", "ping", "send", "tell", "inform", "let", "write",
        "reply", "drop", "shoot", "call", "ring", "dial", "phone",
        "type", "enter", "scroll", "swipe", "set", "turn", "enable", "disable", "remind",
        "navigate", "read", "increase", "decrease", "raise", "lower", "mute", "unmute", "save",
        "add", "create", "make", "check", "close", "share", "copy", "paste", "download",
        "install", "record", "schedule", "wake", "toggle", "use", "answer", "decline", "subscribe",
        "like", "follow", "go", "return", "clear", "delete", "remove", "capture", "screenshot"
    )

    /**
     * Openers that begin a message body when the user gives no "that"/"saying"
     * marker ("text Daniel I'm running late"). Only consulted once at least one
     * recipient word has been seen.
     */
    private val BODY_OPENERS = setOf(
        "i", "i'm", "im", "i'll", "ill", "i've", "ive", "i'd", "id", "we", "we're", "were", "we'll",
        "you", "you're", "youre", "your", "u", "ur", "please", "pls", "hi", "hello", "hey", "yo",
        "good", "sorry", "thanks", "thank", "can", "could", "would", "will", "are", "is", "did", "do",
        "don't", "dont", "let's", "lets", "happy", "congrats", "congratulations", "see", "meet",
        "call", "come", "it's", "its", "the", "when", "what", "where", "how", "why", "who", "ok",
        "okay", "yes", "no", "love", "miss", "running", "on", "just", "have", "has", "remember",
        "don't", "dinner", "lunch", "breakfast", "be", "bring", "get", "pick", "wait", "there",
        "here", "this", "that's", "thats", "morning", "night", "evening", "afternoon", "welcome",
        "great", "nice", "cool", "sure", "alright", "all", "my", "our", "almost", "omw", "lol", "haha"
    )

    private val KIND_NOUNS = mapOf(
        "video" to "video", "videos" to "video", "clip" to "video", "clips" to "video",
        "movie" to "video", "movies" to "video", "film" to "video", "films" to "video",
        "song" to "music", "songs" to "music", "track" to "music", "tracks" to "music",
        "music" to "music", "album" to "music", "albums" to "music", "playlist" to "music",
        "podcast" to "podcast", "podcasts" to "podcast", "episode" to "podcast", "episodes" to "podcast",
        "result" to null, "results" to null, "one" to null, "ones" to null
    )
    private val TOPIC_PREPS = setOf("about", "on", "of", "for", "called", "titled", "named", "featuring", "with", "by")

    // ---------------------------------------------------------- tokens

    private data class Tok(val text: String, val lower: String, val start: Int, val end: Int, val isWord: Boolean)

    private val TOKEN = Regex("""#?[\p{L}\p{N}]+(?:['’][\p{L}]+)*|[,;:"“”!?.]""")

    private fun tokenize(s: String): List<Tok> = TOKEN.findAll(s).map { m ->
        val t = m.value
        val word = t[0].isLetterOrDigit() || t[0] == '#'
        Tok(t, t.lowercase().replace('’', '\''), m.range.first, m.range.last + 1, word)
    }.toList()

    private val LEADING_NOISE = Regex(
        "(?i)^(?:" +
            "hey\\s+(?:ema|emma)|ok(?:ay)?\\s+(?:ema|emma)|hi\\s+(?:ema|emma)|ema|" +
            "please|kindly|pls|plz|" +
            "(?:can|could|would|will)\\s+(?:you|u)(?:\\s+please)?|" +
            "i\\s+(?:need|want|would\\s+like|'d\\s+like|’d\\s+like)\\s+you\\s+to|" +
            "i\\s+(?:need|want|have|got|would\\s+like|'d\\s+like|’d\\s+like)\\s+to|i\\s+(?:wanna|gotta)|" +
            "help\\s+me|go\\s+ahead\\s+and|just|quickly|um+|uh+|erm|so|ok(?:ay)?\\s+so|ok(?:ay)?|alright|yo|hey" +
            ")(?:\\s*[,;:!.]\\s*|\\s+)"
    )

    /** Strip leading wake words, politeness and fillers. Returns the cleaned text (original casing). */
    fun normalise(raw: String): String {
        var s = raw.replace('\u00a0', ' ').replace(Regex("[\\t\\r\\n]+"), " ").replace(Regex(" {2,}"), " ").trim()
        s = s.replace('“', '"').replace('”', '"')
        var guard = 0
        while (guard++ < 8) {
            val m = LEADING_NOISE.find(s) ?: break
            val next = s.substring(m.range.last + 1).trimStart()
            if (next.isBlank()) break
            s = next
        }
        return s
    }

    // ---------------------------------------------------------- frames

    private enum class Act { MESSAGE, OPEN, FIND, SEARCH, PLAY, SELECT, TOPIC, OTHER }

    private class Frame(var act: Act, val text: String) {
        var app: String? = null
        var appAmbiguous: List<String> = emptyList()
        var recipient: String? = null
        var recipientPronoun = false
        var body: String? = null
        var query: String? = null
        var selection: EmaSelection? = null
        var play: Boolean? = null
        var instead = false
        var pronounObject = false
        var kind: String? = null
        var person: String? = null
        var target: String? = null
        var continuationCue = false
        /** This frame began a new, explicitly linked clause ("and"/"then"/punct). */
        var chained = false
        var confidence = 0.9
        val evidence = mutableListOf<String>()
    }

    private class Ctx(val text: String, val toks: List<Tok>, val env: Env) {
        fun word(i: Int): String? = toks.getOrNull(i)?.takeIf { it.isWord }?.lower
        fun lower(i: Int): String? = toks.getOrNull(i)?.lower
        fun slice(from: Int, toExclusive: Int): String {
            if (from >= toExclusive || from >= toks.size) return ""
            val end = toks[(toExclusive - 1).coerceAtMost(toks.size - 1)].end
            return text.substring(toks[from].start, end).trim()
        }
        fun rest(from: Int): String = if (from >= toks.size) "" else text.substring(toks[from].start).trim()
    }

    private data class AppSpan(val label: String?, val end: Int, val ambiguous: List<String> = emptyList(), val notInstalled: Boolean = false)

    // ---------------------------------------------------------- entry

    fun understand(raw: String, env: Env = Env()): Result {
        val cleaned = normalise(raw)
        if (cleaned.isBlank()) return Result.NotHandled("empty")
        val corrected = applyCorrections(cleaned)
        val toks = tokenize(corrected)
        if (toks.none { it.isWord }) return Result.NotHandled("no words")
        val ctx = Ctx(corrected, toks, env)
        val frames = mutableListOf<Frame>()
        segment(ctx).forEachIndexed { index, range ->
            parseClause(ctx, range)?.let { frame ->
                // A separate clause is an explicit step, not a decoration of the
                // previous one: "open Spotify AND play X" has an open step.
                frame.chained = index > 0
                frames += frame
            }
        }
        if (frames.isEmpty()) return Result.NotHandled("no clauses")
        return compose(frames, env)
    }

    // ------------------------------------------------------ corrections

    private val CORRECTION_MARKERS = listOf(
        "no wait", "no sorry", "no no", "i mean", "or rather", "actually", "sorry", "correction", "make that", "rather", "no"
    )

    /**
     * Self-corrections before any message body: "message Daniel, no, David
     * that I'm late" -> "message David that I'm late"; "play Adele, no wait,
     * play Coldplay" -> "play Coldplay". Text after an explicit body marker is
     * never touched, so "text Daniel saying no I mean it" keeps its content.
     */
    fun applyCorrections(text: String): String {
        var s = text
        if (Regex("(?i)\\b(?:scratch that|never mind|nevermind|forget that)\\b[,.;]?\\s+").find(s)?.let { m ->
                val after = s.substring(m.range.last + 1).trim()
                if (after.isNotBlank()) { s = after; true } else false
            } == true
        ) return applyCorrections(s)
        val bodyMarker = Regex("(?i)(?:\\bthat\\b|\\bsaying\\b|\\bto say\\b|\\bwhich says\\b|:|\")").find(s)
        val limit = bodyMarker?.range?.first ?: s.length
        val pre = s.substring(0, limit)
        for (marker in CORRECTION_MARKERS) {
            val needsComma = marker in setOf("no", "sorry", "actually", "rather")
            val pattern = if (needsComma) Regex("(?i),\\s*$marker\\s*,?\\s+|\\s+$marker\\s*,\\s+")
            else Regex("(?i),?\\s+$marker\\s*,?\\s+")
            val m = pattern.find(pre) ?: continue
            val before = s.substring(0, m.range.first)
            val after = s.substring(m.range.last + 1)
            val afterFirst = Regex("^[\\p{L}']+").find(after)?.value?.lowercase() ?: return s
            if (afterFirst in CLAUSE_VERBS) {
                // Clause-level correction: drop the clause being corrected.
                val clauseStart = maxOf(before.lastIndexOf(','), before.lastIndexOf(';'))
                    .let { if (it < 0) 0 else it + 1 }
                val andIdx = Regex("(?i)\\s(?:and|then)\\s").findAll(before).lastOrNull()?.range?.last?.plus(1) ?: 0
                val cut = maxOf(clauseStart, andIdx)
                return applyCorrections((before.substring(0, cut) + " " + after).trim())
            }
            // Slot-level correction: replace the name-like run just before the marker.
            val beforeToks = before.trimEnd().split(Regex("\\s+"))
            var keep = beforeToks.size
            while (keep > 1) {
                val w = beforeToks[keep - 1].lowercase().trim(',', '.')
                if (w in CLAUSE_VERBS || w in APP_PREPS || w in setOf("to", "the", "a", "an", "my") || w in MESSAGE_NOUNS) break
                keep--
                if (beforeToks[keep - 1].firstOrNull()?.isUpperCase() != true) break
            }
            if (keep == beforeToks.size) keep--
            val rebuilt = (beforeToks.take(keep).joinToString(" ") + " " + after).trim()
            return applyCorrections(rebuilt)
        }
        return s
    }

    // --------------------------------------------------------- segment

    private fun isMultiVerb(ctx: Ctx, i: Int): Boolean {
        val w = ctx.word(i) ?: return false
        val n = ctx.word(i + 1)
        return when (w) {
            "go" -> n in setOf("to", "back", "with", "into")
            "look" -> n in setOf("up", "for")
            "listen" -> n == "to"
            "put" -> n == "on"
            "pull", "bring", "fire" -> n == "up"
            "take" -> n == "me"
            "switch" -> n in setOf("to", "on", "off")
            "turn" -> n in setOf("on", "off", "up", "down")
            "show" -> n == "me"
            "let" -> true
            else -> true
        }
    }

    private fun startsClause(ctx: Ctx, i: Int): Boolean {
        val w = ctx.word(i) ?: return false
        return w in CLAUSE_VERBS && isMultiVerb(ctx, i)
    }

    private fun skipConnectors(ctx: Ctx, from: Int): Int {
        var j = from
        while (j < ctx.toks.size) {
            val t = ctx.toks[j]
            if (!t.isWord && t.text == ",") { j++; continue }
            if (t.isWord && t.lower in CONNECTORS) {
                // "after that" is a connector, "that" alone at a clause start is not.
                if (t.lower == "that" && ctx.word(j - 1) != "after") break
                j++; continue
            }
            break
        }
        return j
    }

    private fun segment(ctx: Ctx): List<IntRange> {
        val toks = ctx.toks
        val out = mutableListOf<IntRange>()
        var start = skipConnectors(ctx, 0)
        var i = start
        while (i < toks.size) {
            if (i == start && messagePredicate(ctx, i) != null) {
                // A message clause owns the rest of the utterance: its body is content.
                out += start until toks.size
                return out
            }
            // ASR without punctuation: "on WhatsApp message Daniel ...",
            // "Daniel on WhatsApp message him that ...".
            if (i > start && ctx.toks[i].isWord && messagePredicate(ctx, i) != null && !startsClause(ctx, start)) {
                val prev = ctx.word(i - 1)
                val pronounNext = ctx.word(i + 1) in PRONOUNS
                val afterApp = prev != null && groundAppBackwards(ctx, start, i)
                if (pronounNext || afterApp) {
                    out += start until i
                    start = i
                    continue
                }
            }
            val t = toks[i]
            val boundaryNext: Int? = when {
                !t.isWord && t.text in setOf(",", ";") -> skipConnectors(ctx, i + 1).takeIf { startsClause(ctx, it) || messagePredicate(ctx, it) != null }
                !t.isWord && t.text in setOf(".", "!", "?") -> skipConnectors(ctx, i + 1).takeIf { it < toks.size && (startsClause(ctx, it) || messagePredicate(ctx, it) != null) }
                t.isWord && t.lower in setOf("and", "then") && i > start -> skipConnectors(ctx, i).takeIf { startsClause(ctx, it) || messagePredicate(ctx, it) != null }
                else -> null
            }
            if (boundaryNext != null && boundaryNext > i && boundaryNext < toks.size) {
                if (i > start) out += start until i
                start = boundaryNext
                i = boundaryNext
                continue
            }
            i++
        }
        if (start < toks.size) out += start until toks.size
        return out.filter { r -> r.any { ctx.toks[it].isWord } }
    }

    /** True when the words just before [end] (from [from]) end with "on|in <app>" or are an app. */
    private fun groundAppBackwards(ctx: Ctx, from: Int, end: Int): Boolean {
        for (k in (end - 1) downTo maxOf(from, end - 4)) {
            val span = groundAppAt(ctx, k, end) ?: continue
            if (span.end == end) return true
        }
        return false
    }

    // ------------------------------------------------- app grounding

    private fun groundPhrase(phrase: String, env: Env): AppSpan? {
        val key = EmaAppResolver.normalize(phrase)
        // A user-taught app shortcut is stronger evidence than a fuzzy label.
        (env.shortcuts[key] as? EmaShortcuts.Target.App)?.let { target ->
            env.installedApps.firstOrNull { it.packageName == target.packageName }?.let { app ->
                return AppSpan(app.label, 0)
            }
        }
        env.aliases[key]?.let { mapped ->
            val g = EmaAppResolver.ground(mapped, env.installedApps)
            if (g is EmaAppResolver.Grounding.One) return AppSpan(g.app.label, 0)
        }
        return when (val g = EmaAppResolver.ground(phrase, env.installedApps)) {
            is EmaAppResolver.Grounding.One -> AppSpan(g.app.label, 0)
            is EmaAppResolver.Grounding.Ambiguous -> AppSpan(null, 0, g.apps.map { it.label })
            is EmaAppResolver.Grounding.KnownNotInstalled -> AppSpan(g.name, 0, notInstalled = true)
            null -> null
        }
    }

    /** Longest app name starting at token [i] (word tokens only, at most 4, not past [limit]). */
    private fun groundAppAt(ctx: Ctx, i: Int, limit: Int = ctx.toks.size): AppSpan? {
        if (ctx.word(i) == null) return null
        var maxEnd = i
        while (maxEnd < limit && maxEnd - i < 4 && ctx.toks[maxEnd].isWord) maxEnd++
        var notInstalled: AppSpan? = null
        for (end in maxEnd downTo i + 1) {
            val phrase = ctx.slice(i, end)
            val span = groundPhrase(phrase, ctx.env) ?: continue
            // An installed app always beats a longer "known but not installed" name.
            if (span.notInstalled) { if (notInstalled == null) notInstalled = span.copy(end = end); continue }
            return span.copy(end = end)
        }
        return notInstalled
    }

    // ------------------------------------------------ message clause

    private enum class MsgKind { SEND, VERB, TELL, LET, WRITE, REPLY, DROP }
    private data class MsgPred(val kind: MsgKind, val recipientStart: Int, val nounSeen: Boolean)

    /** Index after "(a|an|the)? (quick)? (<app>)? message", or -1. An app modifier is reported via [appOut]. */
    private fun isMessageNounAt(ctx: Ctx, i: Int, appOut: ((AppSpan) -> Unit)? = null): Int {
        var j = i
        if (ctx.word(j) in setOf("a", "an", "the", "my", "this")) j++
        if (ctx.word(j) in setOf("quick", "short", "new", "brief")) j++
        if (j > i && ctx.word(j) !in MESSAGE_NOUNS) {
            // "a WhatsApp message", "a Telegram text", "an Acme Chat message"
            val span = groundAppAt(ctx, j)
            if (span != null && ctx.word(span.end) in MESSAGE_NOUNS) {
                appOut?.invoke(span)
                j = span.end
            }
        } else if (ctx.word(j) == "whatsapp" && ctx.word(j + 1) in MESSAGE_NOUNS) {
            groundAppAt(ctx, j)?.let { appOut?.invoke(it) }
            j++
        }
        return if (ctx.word(j) in MESSAGE_NOUNS && (j > i || ctx.word(j) in setOf("message", "text", "dm", "msg", "sms"))) j + 1 else -1
    }

    private fun messagePredicate(ctx: Ctx, i: Int): MsgPred? {
        val w = ctx.word(i) ?: return null
        val n = ctx.word(i + 1)
        return when (w) {
            in MESSAGE_VERBS -> {
                if (n == null) return null
                // "text message Daniel"; "message to Daniel"
                var r = i + 1
                if (ctx.word(r) in setOf("message", "messages") && w == "text") r++
                if (ctx.word(r) == "to") r++
                if (ctx.word(r) == null || ctx.word(r) in NOT_RECIPIENTS) return null
                // "whatsapp" is only a verb when a recipient follows, not "whatsapp settings".
                if (w == "whatsapp" && ctx.word(r) in setOf("settings", "web", "status", "business", "calls", "chats", "and", "then")) return null
                MsgPred(MsgKind.VERB, r, false)
            }
            "send", "shoot", "drop" -> {
                val nounEnd = isMessageNounAt(ctx, i + 1)
                if (nounEnd > 0) {
                    var r = nounEnd
                    if (ctx.word(r) == "to") r++
                    return MsgPred(MsgKind.SEND, r, true)
                }
                if (n == null || n in setOf("the", "this", "that", "it", "my", "a", "an", "these", "those", "me", "us", "location", "photo", "picture", "file")) return null
                if (n == "to") return MsgPred(MsgKind.SEND, i + 2, false)
                // "send Daniel a message ..." requires the noun or a body marker to follow soon.
                for (k in i + 2..minOf(i + 6, ctx.toks.size - 1)) {
                    if (isMessageNounAt(ctx, k) > 0) return MsgPred(MsgKind.SEND, i + 1, false)
                    if (ctx.word(k) in setOf("that", "saying") || ctx.lower(k) == ":") return MsgPred(MsgKind.SEND, i + 1, false)
                }
                null
            }
            "tell", "inform", "notify" -> {
                if (n == null || n in NOT_RECIPIENTS || n in setOf("the", "a") && ctx.word(i + 2) in setOf("time", "weather", "joke", "date", "news")) return null
                if (n in setOf("what", "how", "when", "where", "who", "why", "if", "whether", "about", "everyone", "jokes")) return null
                MsgPred(MsgKind.TELL, i + 1, false)
            }
            "let" -> {
                if (n == null || n in NOT_RECIPIENTS) return null
                val know = (i + 2..minOf(i + 6, ctx.toks.size - 1)).firstOrNull { ctx.word(it) == "know" } ?: return null
                if (know <= i + 1) return null
                MsgPred(MsgKind.LET, i + 1, false)
            }
            "write" -> {
                if (n == "to") return if (ctx.word(i + 2) != null && ctx.word(i + 2) !in NOT_RECIPIENTS) MsgPred(MsgKind.WRITE, i + 2, false) else null
                val nounEnd = isMessageNounAt(ctx, i + 2)
                if (n != null && n !in NOT_RECIPIENTS && nounEnd > 0) return MsgPred(MsgKind.WRITE, i + 1, false)
                if (isMessageNounAt(ctx, i + 1) > 0) {
                    val after = isMessageNounAt(ctx, i + 1)
                    if (ctx.word(after) == "to") return MsgPred(MsgKind.WRITE, after + 1, true)
                }
                null
            }
            "reply" -> if (n == "to" && ctx.word(i + 2) != null && ctx.word(i + 2) !in NOT_RECIPIENTS) MsgPred(MsgKind.REPLY, i + 2, false) else null
            else -> null
        }
    }

    private fun bodyMarkerAt(ctx: Ctx, j: Int, kind: MsgKind, recipientSeen: Boolean): Int {
        val w = ctx.lower(j) ?: return -1
        val n = ctx.word(j + 1)
        val n2 = ctx.word(j + 2)
        if (w == ":") return j + 1
        if (w == "\"") return j // keep the quote; stripped later
        if (!recipientSeen) {
            // "send a message saying hi to Daniel": the noun was the object, the body comes first.
            return if (kind == MsgKind.SEND && w in setOf("saying", "that") && ctx.word(j - 1) in MESSAGE_NOUNS) {
                if (w == "that" && n in setOf("says", "reads")) j + 2 else j + 1
            } else -1
        }
        return when (w) {
            "that" -> if (n == "says" || n == "reads") j + 2 else j + 1
            "saying", "says", "reading" -> j + 1
            "which" -> if (n == "says") j + 2 else -1
            "say" -> j + 1
            "to" -> when {
                n == "say" -> j + 2
                kind == MsgKind.TELL && n != null -> j + 1
                else -> -1
            }
            "and" -> when {
                n == "say" || n == "tell" && n2 in PRONOUNS -> if (n == "say") j + 2 else j + 3
                else -> -1
            }
            "telling" -> if (n in PRONOUNS) j + 2 else j + 1
            "with" -> if (n == "the" && ctx.word(j + 2) in setOf("message", "text")) j + 3 else -1
            "know" -> if (kind == MsgKind.LET) (if (n == "that") j + 2 else j + 1) else -1
            else -> -1
        }
    }

    private fun parseMessage(ctx: Ctx, range: IntRange, pred: MsgPred): Frame {
        val frame = Frame(Act.MESSAGE, ctx.slice(range.first, range.last + 1))
        frame.evidence += "predicate:${pred.kind.name.lowercase()}"
        if (pred.nounSeen) isMessageNounAt(ctx, range.first + 1) { span -> applyApp(frame, span) }
        // The verb itself can name an installed app: "whatsapp Daniel that ...".
        // An explicit "on X" later in the clause still wins.
        if (pred.kind == MsgKind.VERB) {
            val verbIndex = (pred.recipientStart - 1 downTo range.first).firstOrNull { ctx.word(it) in MESSAGE_VERBS }
            val verb = verbIndex?.let { ctx.word(it) }
            if (verb != null && verb !in setOf("message", "text", "sms", "dm", "msg", "ping")) {
                groundPhrase(verb, ctx.env)?.takeIf { it.label != null }?.let { span ->
                    applyApp(frame, span)
                    frame.evidence += "app-as-verb"
                }
            }
        }
        val end = range.last + 1
        val recipient = mutableListOf<Int>()
        var nounSeen = pred.nounSeen
        var bodyStart = -1
        var recipientComplete = false
        var j = pred.recipientStart
        var afterComma = false
        while (j < end) {
            val t = ctx.toks[j]
            if (!t.isWord) {
                val marker = bodyMarkerAt(ctx, j, pred.kind, recipient.isNotEmpty())
                if (marker >= 0) { bodyStart = marker; frame.evidence += "body-marker:${t.text}"; break }
                if (t.text == ",") afterComma = true
                j++; continue
            }
            // App slot: "on WhatsApp", "via Telegram", "using My Chat".
            if (t.lower in APP_PREPS) {
                val span = groundAppAt(ctx, j + 1, end)
                if (span != null) {
                    applyApp(frame, span)
                    j = span.end
                    if (recipient.isNotEmpty()) recipientComplete = true
                    continue
                }
            }
            if (!nounSeen) {
                val nounEnd = isMessageNounAt(ctx, j) { span -> if (ctx.word(j) in setOf("a", "an")) applyApp(frame, span) }
                if (nounEnd > 0 && recipient.isNotEmpty() && ctx.word(j) in setOf("a", "an")) {
                    nounSeen = true; recipientComplete = true; j = nounEnd; continue
                }
            }
            val marker = bodyMarkerAt(ctx, j, pred.kind, recipient.isNotEmpty())
            if (marker >= 0) {
                bodyStart = marker; frame.evidence += "body-marker:${t.lower}"
                break
            }
            if (recipient.isNotEmpty() && (recipientComplete || afterComma)) { bodyStart = j; frame.evidence += "body-after-recipient"; break }
            if (recipient.isNotEmpty() && t.lower in BODY_OPENERS) {
                bodyStart = j; frame.evidence += "body-opener:${t.lower}"; frame.confidence = minOf(frame.confidence, 0.85)
                break
            }
            if (recipient.size >= 4) {
                // A recipient longer than four words without any marker: the
                // boundary between name and content is a guess. Ask instead.
                frame.confidence = 0.4
                frame.evidence += "recipient-boundary-unclear"
                bodyStart = recipient.first() + 1
                recipient.retainAll(listOf(recipient.first()))
                break
            }
            recipient += j
            if (recipient.size == 1 && t.lower in PRONOUNS) { recipientComplete = true; frame.recipientPronoun = true }
            j++
        }
        if (recipient.isNotEmpty()) {
            val raw = ctx.slice(recipient.first(), recipient.last() + 1)
            frame.recipient = cleanRecipient(raw, ctx.env)
        }
        if (bodyStart in 0 until end && ctx.word(bodyStart) in APP_PREPS && frame.app == null) {
            val span = groundAppAt(ctx, bodyStart + 1, end)
            if (span != null && (ctx.word(span.end) == "that" || ctx.lower(span.end) == ":")) {
                applyApp(frame, span)
                bodyStart = span.end + 1
            }
        }
        var body = if (bodyStart in 0 until end) ctx.rest(bodyStart) else ""
        body = cleanBody(body)
        // Trailing messaging-app phrase: "text Daniel saying hi on WhatsApp".
        if (frame.app == null && body.isNotBlank()) {
            val m = Regex("(?i)\\s+(?:on|in|via|using|through)\\s+([\\p{L}][\\p{L}\\p{N}' ]{1,40}?)\\s*[.!]?$").find(body)
            if (m != null) {
                val phrase = m.groupValues[1].trim()
                val span = groundPhrase(phrase, ctx.env)
                val normal = EmaAppResolver.normalize(span?.label ?: "")
                val messaging = span?.label != null && (EmaAppResolver.MESSAGING_APP_WORDS.any { normal == it || normal.split(' ').contains(it) } ||
                    ctx.env.defaultApps["messaging"]?.equals(span.label, true) == true)
                if (span != null && messaging) {
                    applyApp(frame, span)
                    body = body.substring(0, m.range.first).trim()
                    frame.evidence += "trailing-app"
                }
            }
        }
        // "send a message saying hi to Daniel": recipient after the body.
        if (frame.recipient == null && body.isNotBlank()) {
            val m = Regex("(?i)^(.*\\S)\\s+to\\s+([\\p{L}][\\p{L}'\\-]*(?:\\s+[\\p{L}][\\p{L}'\\-]*){0,2})\\s*[.!]?$").find(body)
            if (m != null && pred.nounSeen) {
                body = m.groupValues[1].trim()
                frame.recipient = cleanRecipient(m.groupValues[2], ctx.env)
                frame.confidence = minOf(frame.confidence, 0.75)
                frame.evidence += "recipient-after-body"
            }
        }
        frame.body = body
        return frame
    }

    private fun cleanRecipient(raw: String, env: Env): String {
        var r = raw.trim().trim(',', '.', ';', ':').trim()
        r = r.replace(Regex("(?i)^(?:to|the|my)\\s+"), "").replace(Regex("(?i)'s\\s+(?:phone|number|whatsapp)$"), "").trim()
        env.aliases[EmaAppResolver.normalize(r)]?.let { return it }
        return r
    }

    private fun cleanBody(raw: String): String {
        var b = raw.trim()
        b = b.replace(Regex("(?i)[,;]?\\s+(?:and|then)\\s+send\\s+(?:it|that|the message)?\\s*[.!]?$"), "")
        b = b.replace(Regex("(?i)^(?:that|saying)\\s+"), "")
        val quoted = b.startsWith("\"")
        if (b.length >= 2 && b.first() == '"' && b.last() == '"') b = b.substring(1, b.length - 1)
        else if (b.startsWith("\"") && b.count { it == '"' } == 1) b = b.substring(1)
        b = b.replace(Regex("\"\\s*([.!?]?)$"), "$1")
        // A typed or ASR-added full stop ends the *command*, not the message.
        // "!" and "?" carry meaning and stay; an ellipsis or an abbreviation
        // ("5 p.m.") is left untouched.
        b = b.trim()
        if (!quoted && b.endsWith(".") && !b.endsWith("..") && !Regex("\\S\\.\\S+\\.$").containsMatchIn(b)) b = b.dropLast(1)
        return b.trim()
    }

    private fun applyApp(frame: Frame, span: AppSpan) {
        if (span.ambiguous.isNotEmpty()) {
            frame.appAmbiguous = span.ambiguous
            frame.evidence += "app-ambiguous"
        } else if (span.label != null) {
            frame.app = span.label
            frame.evidence += if (span.notInstalled) "app-known-not-installed:${span.label}" else "app:${span.label}"
        }
    }

    // --------------------------------------------------- other clauses

    private fun verbAt(ctx: Ctx, i: Int): Pair<String, Int>? {
        val w = ctx.word(i) ?: return null
        val n = ctx.word(i + 1)
        val two = if (n != null) "$w $n" else null
        val multi = mapOf(
            "look up" to "search", "look for" to "search", "search for" to "search", "go to" to "open",
            "switch to" to "open", "bring up" to "open", "pull up" to "open", "fire up" to "open",
            "listen to" to "play", "put on" to "play", "go with" to "select", "tap on" to "select",
            "click on" to "select", "show me" to "show", "start playing" to "play", "go back" to "other"
        )
        if (two != null && two in multi) return multi.getValue(two) to i + 2
        if (w == "take" && n == "me" && ctx.word(i + 2) == "to") return "open" to i + 3
        return when (w) {
            "open", "launch", "start", "run" -> "open" to i + 1
            "search", "lookup", "browse" -> "search" to i + 1
            "find", "locate" -> "find" to i + 1
            "play", "watch", "stream" -> "play" to i + 1
            "choose", "pick", "select" -> "select" to i + 1
            "tap", "click", "press", "hit" -> "tap" to i + 1
            else -> null
        }
    }

    private fun parseClause(ctx: Ctx, range: IntRange): Frame? {
        val first = range.first
        val end = range.last + 1
        messagePredicate(ctx, first)?.let { return parseMessage(ctx, range, it) }
        val text = ctx.slice(first, end).trimEnd('.', '!', '?', ',', ' ')
        val instead = (first until end).any { ctx.word(it) == "instead" }
        val cue = ctx.word(first - 1) in setOf("now", "also", "then") || (first until end).any { ctx.word(it) in setOf("instead", "again", "there") }
        val vb = verbAt(ctx, first)
        if (vb == null) {
            return if (startsClause(ctx, first)) Frame(Act.OTHER, text) else parseTopic(ctx, first, end, text)
        }
        val (verb, objStart) = vb
        val obj = ctx.slice(objStart, end).trimEnd('.', '!', '?', ',', ' ')
        val frame: Frame = when (verb) {
            "open" -> parseOpen(ctx, objStart, end, text, obj)
            "search", "find", "show" -> parseSearch(ctx, verb, objStart, end, text, obj)
            "play" -> parsePlay(ctx, objStart, end, text, obj, ctx.word(first) == "watch")
            "select", "tap" -> {
                val sel = EmaSelection.parseReference(obj)
                if (sel != null) Frame(Act.SELECT, text).also { it.selection = sel; it.evidence += "selection:${sel.phrase()}" }
                else Frame(Act.OTHER, text)
            }
            else -> Frame(Act.OTHER, text)
        }
        frame.instead = instead
        frame.continuationCue = cue
        return frame
    }

    private fun parseTopic(ctx: Ctx, first: Int, end: Int, text: String): Frame {
        val frame = Frame(Act.TOPIC, text)
        // "On WhatsApp", "WhatsApp", "Daniel on WhatsApp", "Daniel"
        var i = first
        val person = mutableListOf<Int>()
        while (i < end) {
            val t = ctx.toks[i]
            if (!t.isWord) { i++; continue }
            if (t.lower in APP_PREPS || person.isEmpty()) {
                val at = if (t.lower in APP_PREPS) i + 1 else i
                val span = groundAppAt(ctx, at, end)
                if (span != null) { applyApp(frame, span); i = span.end; continue }
            }
            person += i
            i++
        }
        if (person.isNotEmpty() && person.size <= 4) frame.person = cleanRecipient(ctx.slice(person.first(), person.last() + 1), ctx.env)
        if (person.size > 4) return Frame(Act.OTHER, text)
        frame.evidence += "topic"
        return frame
    }

    private fun parseOpen(ctx: Ctx, objStart: Int, end: Int, text: String, obj: String): Frame {
        EmaSelection.parseReference(obj)?.let { sel ->
            return Frame(Act.SELECT, text).also { it.selection = sel; it.play = false; it.evidence += "selection:${sel.phrase()}" }
        }
        val frame = Frame(Act.OPEN, text)
        val whole = groundPhrase(obj, ctx.env)
        if (whole != null && (whole.label != null || whole.ambiguous.isNotEmpty())) {
            applyApp(frame, whole)
            frame.evidence += "open-app"
            return frame
        }
        // "WhatsApp settings", "Reports in My Portal", "the Library tab in YouTube"
        val inApp = Regex("(?i)^(.+?)\\s+(?:in|on|inside)\\s+(.+)$").find(obj)
        if (inApp != null) {
            val span = groundPhrase(inApp.groupValues[2], ctx.env)
            if (span?.label != null) {
                frame.app = span.label
                frame.target = inApp.groupValues[1].replace(Regex("(?i)^the\\s+"), "").trim()
                return frame
            }
        }
        val lead = groundAppAt(ctx, objStart, end)
        if (lead?.label != null && lead.end < end) {
            frame.app = lead.label
            frame.target = ctx.slice(lead.end, end).trimEnd('.', '!', '?', ',', ' ')
            return frame
        }
        frame.target = obj
        return frame
    }

    /** "the longest video about football" -> (Longest, "football", "video"). */
    private data class SelInObject(val selection: EmaSelection, val query: String, val kind: String?)

    private fun selectionInObject(obj: String): SelInObject? {
        val m = Regex(
            "(?i)^(?:the\\s+|a\\s+)?(very\\s+)?(longest|shortest|newest|latest|most\\s+recent|most\\s+viewed|most\\s+popular|first|top|last|second|third)\\s+" +
                "(?:([a-z]+)\\s+)?(?:about|on|of|for|called|titled|featuring|from|by|related\\s+to)\\s+(.+)$"
        ).find(obj.trim()) ?: return null
        val noun = m.groupValues[3].lowercase()
        if (noun.isNotBlank() && noun !in KIND_NOUNS && noun !in EmaSelection.ITEM_NOUNS) return null
        val sel = EmaSelection.parseReference("the " + m.groupValues[2] + " one") ?: return null
        return SelInObject(sel, m.groupValues[4].trim(), KIND_NOUNS[noun])
    }

    private fun stripTrailingApp(ctx: Ctx, frame: Frame, objStart: Int, end: Int): Int {
        // Find "on|in <app>" ending exactly at the clause end.
        for (k in (end - 1) downTo objStart + 1) {
            if (ctx.word(k - 1) !in APP_PREPS) continue
            val span = groundAppAt(ctx, k, end) ?: continue
            if (span.end != end && !(span.end == end - 1 && !ctx.toks[end - 1].isWord)) continue
            applyApp(frame, span)
            return k - 1
        }
        return end
    }

    private fun parseSearch(ctx: Ctx, verb: String, objStart: Int, end: Int, text: String, obj: String): Frame {
        EmaSelection.parseReference(obj)?.let { sel ->
            return Frame(Act.SELECT, text).also { it.selection = sel; it.evidence += "selection:${sel.phrase()}" }
        }
        val frame = Frame(if (verb == "find") Act.FIND else Act.SEARCH, text)
        var start = objStart
        var stop = end
        // "search YouTube for X"
        val lead = groundAppAt(ctx, start, end)
        if (lead != null && ctx.word(lead.end) == "for") {
            applyApp(frame, lead)
            start = lead.end + 1
            frame.act = Act.SEARCH
        } else if (lead != null && lead.end >= end) {
            // "search YouTube" with no query: open it ready to search.
            applyApp(frame, lead)
            frame.query = ""
            frame.act = Act.SEARCH
            return frame
        }
        if (ctx.word(start) == "for") start++
        stop = stripTrailingApp(ctx, frame, start, stop)
        var query = ctx.slice(start, stop).trimEnd('.', '!', '?', ',', ' ')
        query = query.replace(Regex("(?i)\\s+(?:please|for me|now)$"), "").trim()
        selectionInObject(query)?.let { s ->
            frame.selection = s.selection
            frame.kind = s.kind
            query = s.query
            frame.act = Act.SEARCH
            frame.evidence += "selection:${s.selection.phrase()}"
        }
        val kindLead = Regex("(?i)^(?:a\\s+|some\\s+|the\\s+)?(videos?|clips?|songs?|music|podcasts?|movies?)\\s+(?:about|on|of|for|by|featuring)\\s+(.+)$").find(query)
        if (kindLead != null) {
            frame.kind = KIND_NOUNS[kindLead.groupValues[1].lowercase()]
            query = kindLead.groupValues[2].trim()
            frame.act = Act.SEARCH
        }
        frame.query = query
        if (frame.act == Act.FIND) {
            val words = query.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size in 1..4) frame.person = cleanRecipient(query, ctx.env)
        }
        return frame
    }

    private fun parsePlay(ctx: Ctx, objStart: Int, end: Int, text: String, obj: String, watch: Boolean): Frame {
        val trimmed = obj.trim()
        if (trimmed.lowercase().trim('.', '!', ' ') in setOf("it", "that", "this", "that one", "this one", "it for me", "it now", "that video", "it instead")) {
            return Frame(Act.PLAY, text).also { it.pronounObject = true; it.play = true; it.evidence += "play-pronoun" }
        }
        EmaSelection.parseReference(trimmed)?.let { sel ->
            return Frame(Act.SELECT, text).also { it.selection = sel; it.play = true; it.evidence += "selection:${sel.phrase()}" }
        }
        val frame = Frame(Act.PLAY, text)
        frame.play = true
        if (watch) frame.kind = "video"
        val stop = stripTrailingApp(ctx, frame, objStart, end)
        var query = ctx.slice(objStart, stop).trimEnd('.', '!', '?', ',', ' ')
        query = query.replace(Regex("(?i)\\s+(?:please|for me|now)$"), "").trim()
        selectionInObject(query)?.let { s ->
            frame.selection = s.selection
            frame.kind = s.kind ?: frame.kind
            query = s.query
            frame.act = Act.SEARCH
        }
        frame.query = query
        return frame
    }

    // ---------------------------------------------------------- compose

    private fun compose(input: List<Frame>, env: Env): Result {
        val frames = input.toMutableList()
        val evidence = frames.flatMap { f -> listOf("clause:${f.act.name.lowercase()}") + f.evidence }.toMutableList()

        // Ambiguous app anywhere: ask before doing anything.
        frames.firstOrNull { it.appAmbiguous.isNotEmpty() }?.let { f ->
            return Result.Clarify(
                "Which app do you mean: ${f.appAmbiguous.joinToString(" or ")}?",
                Role.APP, null, f.appAmbiguous, evidence
            )
        }

        val steps = mutableListOf<Step>()
        val msgIndex = frames.indexOfFirst { it.act == Act.MESSAGE }
        var messageStep: Step? = null
        var consumedUpTo = -1
        if (msgIndex >= 0) {
            val msg = frames[msgIndex]
            val prelude = frames.subList(0, msgIndex)
            val topicPerson = prelude.lastOrNull { (it.act == Act.TOPIC || it.act == Act.FIND) && it.person != null }?.person
            val preludeApp = prelude.lastOrNull { it.app != null && it.act in setOf(Act.OPEN, Act.TOPIC, Act.FIND, Act.SEARCH) }?.app
            var recipient = msg.recipient
            if (msg.recipientPronoun) {
                recipient = topicPerson
                if (recipient == null) {
                    return Result.Clarify("Who should I send that message to?", Role.RECIPIENT,
                        EmaCommand.SendMessage("", msg.app ?: preludeApp, msg.body.orEmpty()), evidence = evidence + "pronoun-unresolved")
                }
                evidence += "pronoun-resolved:$recipient"
            }
            if (recipient.isNullOrBlank()) {
                return Result.Clarify("Who should I send that message to?", Role.RECIPIENT,
                    EmaCommand.SendMessage("", msg.app ?: preludeApp, msg.body.orEmpty()), evidence = evidence)
            }
            if (msg.confidence < 0.5) {
                return Result.Clarify("I'm not sure where the name ends and the message begins. Who is it for, and what should it say?",
                    Role.BODY, null, evidence = evidence)
            }
            val app = msg.app ?: preludeApp ?: env.defaultApps["messaging"]
            // Prelude clauses that are sub-steps of this goal are absorbed;
            // anything else stays a separate step before it.
            val remaining = prelude.filterNot { f ->
                when (f.act) {
                    Act.TOPIC -> true
                    Act.OPEN -> f.target == null && (f.app == null || f.app.equals(app, true))
                    Act.FIND, Act.SEARCH -> f.selection == null && (f.person != null && f.person.equals(recipient, true) ||
                        (f.query ?: "").equals(recipient, true)) && (f.app == null || f.app.equals(app, true))
                    else -> false
                }
            }
            frames.clear()
            frames.addAll(remaining)
            // Sending depends on earlier steps only when they prepare the same
            // app (e.g. "open WhatsApp's Archived chats, then message ...");
            // "turn on wifi and message Daniel" are independent goals.
            val preparesSameApp = remaining.any { f -> app != null && f.app.equals(app, true) }
            messageStep = Step(EmaCommand.SendMessage(recipient, app, msg.body.orEmpty()), dependsOnPrevious = preparesSameApp)
            consumedUpTo = msgIndex
        }

        // Generic chain: propagate app context, merge search + selection.
        var currentApp: String? = null
        var listInUtterance = false
        var lastPlay: Boolean? = null
        var i = 0
        while (i < frames.size) {
            val f = frames[i]
            val next = frames.getOrNull(i + 1)
            when (f.act) {
                Act.OPEN -> {
                    if (f.app != null) {
                        currentApp = f.app
                        // "open YouTube and search X" is one search goal: the
                        // search step itself opens YouTube. An explicit "open X
                        // and play Y" keeps its own open step, so the PLAN
                        // shows the real order and the open is verified first.
                        val absorbedByNext = f.target == null && next != null &&
                            next.act in setOf(Act.SEARCH, Act.FIND) &&
                            (next.app == null || next.app.equals(f.app, true)) && !next.pronounObject
                        if (!absorbedByNext) {
                            steps += if (f.target != null) Step(EmaCommand.OpenAppSection(f.app!!, f.target!!))
                            else Step(EmaCommand.OpenApp(f.app!!), dependsOnPrevious = false)
                        }
                    } else if (f.target != null && currentApp != null) {
                        steps += Step(EmaCommand.OpenAppSection(currentApp, f.target!!))
                    } else {
                        steps += Step(null, f.text, dependsOnPrevious = false)
                    }
                }
                Act.SEARCH, Act.FIND -> {
                    val app = f.app ?: currentApp ?: f.kind?.let { env.defaultApps[it] }
                        ?: if (f.continuationCue) env.activeTask?.app else null
                    if (f.app == null && app != null && currentApp == null && f.kind == null) evidence += "app-from-context:$app"
                    currentApp = app ?: currentApp
                    val query = f.query.orEmpty()
                    steps += Step(EmaCommand.SearchInApp(query, app), dependsOnPrevious = steps.isNotEmpty() && f.app == null)
                    listInUtterance = true
                    var selection = f.selection
                    var play: Boolean? = if (selection != null) f.play else null
                    // "... and play it" / "... and open it" refers to this search.
                    if (next != null && next.act == Act.PLAY && next.pronounObject) {
                        selection = selection ?: EmaSelection.Ordinal(1)
                        play = true
                        i++
                    }
                    if (selection != null) {
                        val wantsPlay = play ?: (f.act == Act.SEARCH && f.kind in setOf("video", "music", "podcast") && f.text.lowercase().startsWith("play"))
                        steps += Step(EmaCommand.SelectResult(selection, app, wantsPlay))
                        lastPlay = wantsPlay
                    }
                }
                Act.PLAY -> {
                    if (f.pronounObject) {
                        if (listInUtterance) {
                            steps += Step(EmaCommand.SelectResult(EmaSelection.Ordinal(1), currentApp, true))
                        } else {
                            // "play it" with nothing in this request to refer to: resume media.
                            steps += Step(EmaCommand.MediaControl(EmaCommand.MediaControl.Action.PLAY))
                        }
                    } else {
                        val app = f.app ?: currentApp ?: f.kind?.let { env.defaultApps[it] }
                        currentApp = app ?: currentApp
                        steps += Step(EmaCommand.PlayMedia(f.query.orEmpty(), app), dependsOnPrevious = f.app == null && steps.isNotEmpty())
                    }
                }
                Act.SELECT -> {
                    val selection = f.selection ?: EmaSelection.Ordinal(1)
                    var continuation = false
                    var app = f.app ?: currentApp
                    var play = f.play ?: lastPlay
                    if (!listInUtterance) {
                        val task = env.activeTask
                        if (task != null) {
                            continuation = true
                            app = app ?: task.app
                            play = play ?: task.lastWasPlay
                            evidence += "continues-active-task:${task.app}"
                        } else {
                            // No list from an earlier request: act on whatever list is
                            // on screen now (app = null means "the foreground app").
                            // The executor reports honestly if no results are visible.
                            evidence += "list-on-current-screen"
                        }
                    }
                    steps += Step(EmaCommand.SelectResult(selection, app, play ?: false, f.instead), continuation = continuation)
                    lastPlay = play
                    listInUtterance = true
                }
                Act.TOPIC -> {
                    // A topic with nothing to attach to ("WhatsApp, open chats" handled
                    // via app propagation). A lone topic person is not an action.
                    if (f.app != null) currentApp = f.app
                }
                Act.MESSAGE -> Unit
                Act.OTHER -> {
                    steps += Step(null, f.text, dependsOnPrevious = currentApp != null)
                }
            }
            i++
        }
        messageStep?.let { steps += it }
        if (steps.isEmpty()) return Result.NotHandled("nothing actionable")

        // Single steps the proven older layers already handle exactly as before.
        if (steps.size == 1 && msgIndex < 0) {
            val only = steps[0]
            val cmd = only.command
            val keepLegacy = when (cmd) {
                null -> true
                is EmaCommand.OpenApp -> !input[0].text.contains(Regex("(?i)\\bmy\\b"))
                is EmaCommand.OpenAppSection -> true
                is EmaCommand.PlayMedia -> cmd.app == null
                is EmaCommand.MediaControl -> true
                is EmaCommand.SearchInApp -> cmd.app == null
                else -> false
            }
            if (keepLegacy) return Result.NotHandled("single step handled by the established layers")
        }
        val confidence = input.minOf { it.confidence }
        return Result.Plan(steps, confidence, evidence)
    }
}
