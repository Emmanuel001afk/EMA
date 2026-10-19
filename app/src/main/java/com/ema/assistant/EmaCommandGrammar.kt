package com.ema.assistant

/**
 * EMA's deterministic command understanding.
 *
 * THIS IS THE DIGITAL ASSISTANT. It is the first layer and it is the layer that
 * does the work: pure Kotlin, no network, no AI, no provider SDKs, no Android
 * framework types. Given a spoken or typed sentence it produces a structured
 * command with the semantic roles kept separate (app / target / content), which
 * the execution layer then performs with ordinary Android APIs.
 *
 * The AI tunnel is only ever consulted for requests this file cannot resolve,
 * and only when the user has not disabled it. Nothing here depends on it.
 *
 * Because the whole brain is pure it is exhaustively unit testable on a JVM and
 * behaves identically on every device and OEM skin.
 */
sealed class EmaCommand {

    /** Canonical text form, used for scheduling and for AI-produced actions. */
    abstract fun toCanonical(): String

    data class SendMessage(
        val recipient: String,
        val app: String?,
        val body: String
    ) : EmaCommand() {
        override fun toCanonical(): String = buildString {
            append("send a message to ").append(recipient)
            if (!app.isNullOrBlank()) append(" on ").append(app)
            if (body.isNotBlank()) append(" saying ").append(body)
        }
    }

    data class CallContact(val name: String, val sim: Int?) : EmaCommand() {
        override fun toCanonical(): String =
            if (sim == null) "call $name" else "call $name with SIM $sim"
    }

    data class PlayMedia(val query: String, val app: String?) : EmaCommand() {
        override fun toCanonical(): String =
            if (app.isNullOrBlank()) "play $query" else "play $query on $app"
    }

    data class MediaControl(val action: Action) : EmaCommand() {
        enum class Action { TOGGLE, PLAY, PAUSE, RESUME, STOP, NEXT, PREVIOUS, WHATS_PLAYING }
        override fun toCanonical(): String = when (action) {
            Action.TOGGLE -> "play or pause"
            Action.PLAY -> "play"
            Action.PAUSE -> "pause"
            Action.RESUME -> "resume"
            Action.STOP -> "stop"
            Action.NEXT -> "next track"
            Action.PREVIOUS -> "previous track"
            Action.WHATS_PLAYING -> "what is playing"
        }
    }

    data class SearchInApp(
        val query: String,
        val app: String?,
        val selectFirst: Boolean = false
    ) : EmaCommand() {
        override fun toCanonical(): String = buildString {
            append("search ").append(query)
            if (!app.isNullOrBlank()) append(" on ").append(app)
            if (selectFirst) append(" and open the first result")
        }
    }

    data class WebSearch(val query: String) : EmaCommand() {
        override fun toCanonical(): String = "search with google $query"
    }

    data class OpenApp(val app: String) : EmaCommand() {
        override fun toCanonical(): String = "open $app"
    }

    /** Open an app, then activate one of its visible sections. */
    data class OpenAppSection(val app: String, val section: String) : EmaCommand() {
        override fun toCanonical(): String = "go to $section in $app"
    }

    data class OpenSettingsPage(val target: String) : EmaCommand() {
        override fun toCanonical(): String =
            if (target.isBlank()) "go to settings" else "go to $target in settings"
    }

    data class NavigateTo(val destination: String) : EmaCommand() {
        override fun toCanonical(): String = "navigate to $destination"
    }

    data class SetAlarm(val timeText: String) : EmaCommand() {
        override fun toCanonical(): String = "set an alarm for $timeText"
    }

    data class SetTimer(val durationText: String) : EmaCommand() {
        override fun toCanonical(): String = "set a timer for $durationText"
    }

    data class SetReminder(val text: String, val timeText: String) : EmaCommand() {
        override fun toCanonical(): String =
            if (timeText.isBlank()) "remind me to $text" else "remind me to $text at $timeText"
    }

    data class SaveContact(val name: String, val number: String) : EmaCommand() {
        override fun toCanonical(): String = "save $number as $name"
    }

    /** Open the phone's SMS composer for a recipient, no third-party app. */
    data class OpenSmsComposer(val recipient: String, val body: String) : EmaCommand() {
        override fun toCanonical(): String = "text $recipient"
    }

    /**
     * Act on one item of a result list that is (or was just) on screen:
     * "play the third result", "choose the longest one instead",
     * "find another one". Produced by [EmaSemanticParser]; the selection is
     * resolved against what the accessibility layer can really observe.
     *
     * @param play the user wants playback (verified), not just to open it.
     * @param replacesPrevious "instead": go back to the list before choosing.
     */
    data class SelectResult(
        val selection: EmaSelection,
        val app: String?,
        val play: Boolean,
        val replacesPrevious: Boolean = false
    ) : EmaCommand() {
        override fun toCanonical(): String = buildString {
            append(if (play) "play " else "open ").append(selection.phrase())
            if (!app.isNullOrBlank()) append(" on ").append(app)
        }
    }
}

object EmaCommandGrammar {

    /**
     * Spoken decoration. A separator is REQUIRED after each word so that a real
     * word can never be eaten: "emailed Daniel" keeps its "ema", and "play Hey
     * Jude" keeps its "hey", because neither is followed by a separator at the
     * start of the sentence.
     */
    private val POLITE_PIECE = Regex(
        """(?i)^(?:hey|ok|okay|hi|hello|yo|ema|please|kindly|could you|can you|would you|""" +
            """will you|i want you to|i need you to|i'd like you to|i would like you to|""" +
            """i want to|help me|let's|lets)\s*[,;:!?]?\s+"""
    )

    private val BODY_MARKERS = listOf(
        "that says", "which says", "that reads", "to say", "saying",
        "with the message", "with the text", "with a message", "reading"
    )

    /**
     * Message nouns. When one of these follows the verb it is the object being
     * sent ("send a message to Daniel"), never the recipient. Recognising this
     * is what stops "send a message to X" from being parsed as body="a message".
     */
    private val MESSAGE_NOUN = Regex(
        """(?i)^(?:a\s+|an\s+|the\s+|this\s+|that\s+)?(?:message|text|dm|note|sms|chat|mail|email|voice note)\s*"""
    )

    private val MESSAGE_VERB = Regex("""(?i)^(?:send|message|text|dm|ping|whatsapp|chat|write|mail|email)\b""")

    private val APP_PREP = """(?:on|in|using|via|through)"""
    private val TO_PREFIX = Regex("""(?i)^to\s+""")

    /** Words that decorate a media request but are not part of the title. */
    private val MEDIA_DECORATION = Regex(
        """(?i)^(?:some|the|a|an|my|that|this|please)\s+""" +
            """(?:song|track|music|tune|album|playlist|video|episode|podcast|something|anything)\s+""" +
            """(?:called|named|titled)?\s*"""
    )

    /** Search verbs, longest alternative first so "search for" is not split. */
    private val SEARCH_VERB = """(?:search for|search|look up|look for|find)"""

    fun normalize(raw: String): String {
        var text = raw.trim()
            .replace('\u00a0', ' ')
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[.!?]+$"), "")
            .replace(Regex("^[,;:\\s]+"), "")
            .trim()
        // Spoken decoration arrives in combinations ("Hey EMA, please could you"),
        // so strip repeatedly until the sentence stops changing.
        var guard = 0
        while (guard++ < 8) {
            val stripped = text
                .replaceFirst(POLITE_PIECE, "")
                .replace(Regex("^[,;:\\s]+"), "")
                .trim()
            if (stripped == text) break
            text = stripped
        }
        return text.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Interpret one utterance. Returns null only when EMA has no deterministic
     * understanding of it, which is the single condition that may escalate to
     * the optional AI layer.
     */
    fun interpret(raw: String): EmaCommand? {
        val command = normalize(raw)
        if (command.isBlank()) return null

        // "on WhatsApp, message Daniel saying hi": the app is stated up front.
        // It is extracted as a hint rather than textually reordered, because
        // moving it to the end would collide with a body that contains "on".
        val leadingApp = leadingAppClause(command)
        val remainder = leadingApp?.second ?: command
        val result = interpretCore(remainder) ?: return null
        return if (leadingApp != null) withAppHint(result, leadingApp.first) else result
    }

    private fun interpretCore(command: String): EmaCommand? {
        val lower = command.lowercase()

        // Order is load-bearing. Time-bearing commands are resolved before the
        // media and message verbs, otherwise "start the timer for 10 minutes"
        // would be read as a request to play something called "the timer".
        // Web search precedes app search so "search with google X" is not
        // swallowed as an in-app search for "with google X".
        return mediaControl(lower)
            ?: reminder(command, lower)
            ?: alarm(command, lower)
            ?: wakeMe(command, lower)
            ?: timer(command, lower)
            ?: saveContact(command, lower)
            ?: message(command, lower)
            ?: play(command, lower)
            ?: call(command, lower)
            ?: navigate(command, lower)
            ?: settings(command, lower)
            ?: webSearch(command, lower)
            ?: appSearch(command, lower)
            ?: open(command, lower)
            ?: smsComposer(command, lower)
    }

    private fun leadingAppClause(text: String): Pair<String, String>? {
        val match = Regex("""(?i)^(?:on|in|inside|using|via|through)\s+(.+?)\s*,\s*(.+)$""").find(text) ?: return null
        val app = match.groupValues[1].trim()
        val rest = match.groupValues[2].trim()
        if (app.isBlank() || rest.isBlank() || app.split(' ').size > 4) return null
        return app to rest
    }

    /** Fill an empty app slot from a leading "on <app>," clause. */
    private fun withAppHint(command: EmaCommand, app: String): EmaCommand = when (command) {
        is EmaCommand.SendMessage -> if (command.app.isNullOrBlank()) command.copy(app = app) else command
        is EmaCommand.PlayMedia -> if (command.app.isNullOrBlank()) command.copy(app = app) else command
        is EmaCommand.SearchInApp -> if (command.app.isNullOrBlank()) command.copy(app = app) else command
        else -> command
    }

    // ---------------------------------------------------------------- media

    private fun mediaControl(lower: String): EmaCommand? {
        val action = when (lower.trim()) {
            "play", "play or pause", "toggle playback", "play music", "play it", "keep playing" ->
                EmaCommand.MediaControl.Action.TOGGLE
            "pause", "pause the music", "pause playback", "pause music", "hold" ->
                EmaCommand.MediaControl.Action.PAUSE
            "resume", "resume playback", "resume music", "continue", "continue playing", "unpause" ->
                EmaCommand.MediaControl.Action.RESUME
            "stop", "stop playback", "stop the music", "stop playing", "stop music" ->
                EmaCommand.MediaControl.Action.STOP
            "next", "next track", "next song", "skip", "skip track", "skip song", "skip this",
            "play the next song", "play next song", "change song", "change the song" ->
                EmaCommand.MediaControl.Action.NEXT
            "previous", "previous track", "previous song", "last song", "go back a song",
            "play the previous song", "play previous song", "rewind", "back a song" ->
                EmaCommand.MediaControl.Action.PREVIOUS
            "what is playing", "what's playing", "what song is this", "which song is this",
            "what is this song", "current song", "what song is playing", "name this song" ->
                EmaCommand.MediaControl.Action.WHATS_PLAYING
            else -> return null
        }
        return EmaCommand.MediaControl(action)
    }

    // -------------------------------------------------------------- message

    private enum class MsgShape { APP_BODY, BODY_ONLY, APP_ONLY }

    /**
     * Message parsing.
     *
     * Patterns are ordered most-specific first, and each keeps the three
     * semantic roles apart: WHO receives it, WHERE it is sent, WHAT is said.
     * Getting this order wrong is how an assistant ends up typing a message into
     * the wrong chat, or sending the words "a message" as the body, so every
     * branch is covered by a unit test.
     */
    private fun message(command: String, lower: String): EmaCommand? {
        tellForm(command)?.let { return it }

        val verbMatch = MESSAGE_VERB.find(command) ?: return null
        val verb = verbMatch.value.lowercase()
        val verbApp = if (verb == "whatsapp") "WhatsApp" else null
        val afterVerb = command.substring(verbMatch.range.last + 1).trim()

        val nounMatch = MESSAGE_NOUN.find(afterVerb)
        val subject = if (nounMatch != null) {
            afterVerb.substring(nounMatch.range.last + 1).replaceFirst(TO_PREFIX, "").trim()
        } else afterVerb
        if (subject.isBlank()) return null

        val bodyMarkerGroup = BODY_MARKERS.joinToString("|") { Regex.escape(it) }

        // "<who> <prep> <app> <marker> <body>" — the fully explicit form.
        val explicit = Regex("""(?i)^(.+?)\s+$APP_PREP\s+(.+?)\s+(?:$bodyMarkerGroup)\s+(.+)$""").find(subject)
        if (explicit != null) {
            val who = explicit.groupValues[1].trim()
            val app = explicit.groupValues[2].trim()
            val body = explicit.groupValues[3].trim()
            if (who.isNotBlank() && app.isNotBlank() && body.isNotBlank() &&
                plausibleRecipient(who) && plausibleApp(app)
            ) {
                return EmaCommand.SendMessage(who, app, body)
            }
        }

        // "<body> to <who> <prep> <app>" — body stated first, app named.
        val bodyFirstWithApp = Regex("""(?i)^(.+?)\s+to\s+(.+?)\s+$APP_PREP\s+(.+)$""").find(subject)
        if (bodyFirstWithApp != null) {
            val body = bodyFirstWithApp.groupValues[1].trim()
            val who = bodyFirstWithApp.groupValues[2].trim()
            val app = bodyFirstWithApp.groupValues[3].trim()
            if (body.isNotBlank() && who.isNotBlank() && plausibleRecipient(who) && plausibleApp(app)) {
                return EmaCommand.SendMessage(who, app, body)
            }
        }

        // "<who> <marker> <body>" with the app implied by the verb, or stated
        // after the body ("message Daniel saying hi on WhatsApp").
        val bodyOnly = Regex("""(?i)^(.+?)\s+(?:$bodyMarkerGroup)\s+(.+)$""").find(subject)
        if (bodyOnly != null) {
            val who = bodyOnly.groupValues[1].trim()
            var body = bodyOnly.groupValues[2].trim()
            if (who.isNotBlank() && body.isNotBlank() && plausibleRecipient(who)) {
                var app = verbApp
                if (app == null) {
                    val trailing = Regex("""(?i)\s+$APP_PREP\s+([^,.!?]+)$""").find(body)
                    if (trailing != null) {
                        val candidate = trailing.groupValues[1].trim()
                        if (plausibleApp(candidate) && !startsWithArticle(candidate)) {
                            app = candidate
                            body = body.substring(0, trailing.range.first).trim()
                        }
                    }
                }
                if (body.isNotBlank()) return EmaCommand.SendMessage(who, app, body)
            }
        }

        // "send <body> to <who> [on <app>]". Requires either a named app or a
        // body that is clearly a sentence, so "send John to the store" is not
        // turned into a message addressed to "the store".
        val bodyFirst = Regex("""(?i)^(.+?)\s+to\s+(.+?)(?:\s+$APP_PREP\s+(.+))?$""").find(subject)
        if (bodyFirst != null) {
            val body = bodyFirst.groupValues[1].trim()
            val who = bodyFirst.groupValues[2].trim()
            val app = bodyFirst.groupValues[3].trim().ifBlank { null }
            val sentenceLike = body.split(Regex("\\s+")).size >= 2 || containsVerbWord(body)
            if (body.isNotBlank() && plausibleRecipient(who) && (app != null || sentenceLike)) {
                if (app == null || plausibleApp(app)) {
                    return EmaCommand.SendMessage(who, app ?: verbApp, body)
                }
            }
        }

        // "<who> on <app>" with no body: EMA opens the right chat and asks.
        val appOnly = Regex("""(?i)^(.+?)\s+$APP_PREP\s+(.+)$""").find(subject)
        if (appOnly != null) {
            val who = appOnly.groupValues[1].trim()
            val app = appOnly.groupValues[2].trim()
            if (who.isNotBlank() && plausibleRecipient(who) && plausibleApp(app)) {
                return EmaCommand.SendMessage(who, app, "")
            }
        }

        // Recipient only.
        val whoOnly = subject.replaceFirst(TO_PREFIX, "").trim()
        if (whoOnly.isNotBlank() && plausibleRecipient(whoOnly)) {
            return EmaCommand.SendMessage(whoOnly, verbApp, "")
        }
        return null
    }

    private fun tellForm(command: String): EmaCommand? {
        val match = Regex("""(?i)^(?:tell|inform|let)\s+(.+?)\s+(?:(?:$APP_PREP)\s+(.+?)\s+)?(?:that|to say|saying|that he|that she|that they)\s+(.+)$""")
            .find(command) ?: return null
        val who = match.groupValues[1].trim()
        val app = match.groupValues[2].trim().ifBlank { null }
        val body = match.groupValues[3].trim()
        if (who.isBlank() || body.isBlank() || !plausibleRecipient(who)) return null
        if (looksLikeTimeOnly(body)) return null
        return EmaCommand.SendMessage(who, app, body)
    }

    /** A recipient is a short person-ish phrase, not a sentence. */
    private fun plausibleRecipient(value: String): Boolean {
        val words = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 6) return false
        if (words.any { it.lowercase() in SENTENCE_VERBS }) return false
        if (words.any { it.length > 24 }) return false
        return true
    }

    private val SENTENCE_VERBS = setOf(
        "is", "are", "was", "were", "will", "would", "should", "can", "could",
        "have", "has", "had", "does", "did", "been", "being"
    )

    private fun containsVerbWord(value: String): Boolean =
        value.lowercase().split(Regex("\\s+")).any {
            it in SENTENCE_VERBS || it in setOf("i", "we", "you", "my", "our", "the", "a", "an", "to", "for")
        }

    /** An app slot must be short; long tails are body text, not app names. */
    private fun plausibleApp(value: String): Boolean {
        val words = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 4) return false
        return words.none { it.lowercase() in SENTENCE_VERBS }
    }

    private fun startsWithArticle(value: String): Boolean {
        val first = value.trim().split(Regex("\\s+")).firstOrNull()?.lowercase().orEmpty()
        return first in setOf("the", "a", "an", "my", "his", "her", "our", "their", "this", "that", "it")
    }

    /** Locate a body marker as a whole phrase, ignoring matches inside words. */
    private fun indexOfMarker(text: String, marker: String): Int {
        val lower = text.lowercase()
        var from = 0
        while (true) {
            val index = lower.indexOf(marker, from)
            if (index < 0) return -1
            val before = if (index == 0) ' ' else lower[index - 1]
            val afterIndex = index + marker.length
            val after = if (afterIndex >= lower.length) ' ' else lower[afterIndex]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return index
            from = index + 1
        }
    }

    private fun looksLikeRecipientOnly(value: String): Boolean {
        val words = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 3) return false
        return words.none { it.lowercase() in setOf("the", "i", "my", "that", "this", "we", "you", "am", "is", "are", "will", "to", "a", "an") }
    }

    private fun looksLikeTimeOnly(value: String): Boolean =
        Regex("""(?i)^[\d\s:.]*$""").matches(value.trim())

    // ----------------------------------------------------------------- play

    private val PLAY_VERB = Regex(
        """(?i)^(?:play|put on|start playing|listen to|stream|replay|shuffle play|play me)\b"""
    )

    private fun play(command: String, lower: String): EmaCommand? {
        // "open <app> and play <query>" — checked before the verb-anchored forms
        // because the sentence does not start with a play verb.
        Regex("""(?i)^(?:open|launch|go to|go into|start)\s+(.+?)\s+and\s+(?:play|listen to|put on|stream)\s+(.+)$""")
            .find(command)?.let { m ->
                val app = m.groupValues[1].trim()
                val query = cleanMediaQuery(m.groupValues[2].trim())
                if (app.isNotBlank() && query.isNotBlank()) return EmaCommand.PlayMedia(query, app)
            }

        // "in <app>, play <query>" / "on <app> play <query>"
        Regex("""(?i)^(?:in|inside|within|on|using)\s+(.+?)\s*,?\s+(?:play|listen to|put on|stream)\s+(.+)$""")
            .find(command)?.let { m ->
                val app = m.groupValues[1].trim()
                val query = cleanMediaQuery(m.groupValues[2].trim())
                if (app.isNotBlank() && query.isNotBlank() && plausibleApp(app)) {
                    return EmaCommand.PlayMedia(query, app)
                }
            }

        if (!PLAY_VERB.containsMatchIn(lower)) return null

        var rest = command.replaceFirst(PLAY_VERB, "").trim()
        rest = rest.replaceFirst(Regex("""(?i)^me\s+"""), "").trim()
        if (rest.isBlank()) return null

        var app: String? = null
        // Prefer the LAST app preposition: "play the album in my library on spotify".
        for (prep in listOf("on", "in", "using", "via", "through", "from", "inside")) {
            val match = Regex("""(?i)\s+$prep\s+(.+)$""").find(rest) ?: continue
            val candidateApp = match.groupValues[1].trim()
            val candidateQuery = rest.substring(0, match.range.first).trim()
            if (candidateQuery.isBlank() || candidateApp.isBlank()) continue
            // "play something with friends" is not an app reference.
            if (!plausibleApp(candidateApp)) continue
            if (candidateApp.lowercase() in setOf("me", "us", "him", "her", "them", "myself")) continue
            rest = candidateQuery
            app = candidateApp
            break
        }

        val query = cleanMediaQuery(rest)
        if (query.isBlank()) return null
        return EmaCommand.PlayMedia(query, app)
    }

    private fun cleanMediaQuery(value: String): String {
        val cleaned = value
            .replaceFirst(MEDIA_DECORATION, "")
            .replaceFirst(Regex("""(?i)^(?:for me|please|now)\s*"""), "")
            .replace(Regex("""(?i)\s+(?:please|now|for me)$"""), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifBlank { value.trim() }
    }

    // ----------------------------------------------------------------- call

    private val CALL_VERB = Regex("""(?i)^(?:call|dial|phone|ring|buzz|make a call to|give a call to|call up)\b""")

    private fun call(command: String, lower: String): EmaCommand? {
        if (!CALL_VERB.containsMatchIn(lower)) return null
        var rest = command.replaceFirst(CALL_VERB, "").trim()
        rest = rest.replaceFirst(TO_PREFIX, "").trim()
        if (rest.isBlank()) return null

        var sim: Int? = null
        Regex("""(?i)\s+(?:with|on|using)\s+(?:(?:sim|slot|line)\s*([12])|(?:the\s+)?(first|1st|second|2nd)\s+sim)\s*$""").find(rest)?.let { m ->
            sim = m.groupValues[1].toIntOrNull() ?: when (m.groupValues[2].lowercase()) {
                "first", "1st" -> 1
                "second", "2nd" -> 2
                else -> null
            }
            rest = rest.substring(0, m.range.first).trim()
        }
        rest = rest
            .replaceFirst(Regex("""(?i)^(?:a call to|call to)\s+"""), "")
            .replace(Regex("""(?i)\s+(?:please|now|for me)$"""), "")
            .trim()
        if (rest.isBlank()) return null
        return EmaCommand.CallContact(rest, sim)
    }

    // ------------------------------------------------------------- reminder

    private fun reminder(command: String, lower: String): EmaCommand? {
        val starts = lower.startsWith("remind me") || lower.startsWith("remind us") ||
            lower.startsWith("set a reminder") || lower.startsWith("set reminder") ||
            lower.startsWith("create a reminder") || lower.startsWith("reminder") ||
            lower.startsWith("don't let me forget") || lower.startsWith("dont let me forget")
        if (!starts) return null

        val rest = command
            .replaceFirst(
                Regex("""(?i)^(?:don't let me forget|dont let me forget|remind me|remind us|set a reminder|set reminder|create a reminder|reminder)\s*"""),
                ""
            )
            .replaceFirst(Regex("""(?i)^(?:to|that|about|for)\s+"""), "")
            .trim()
        if (rest.isBlank()) return null

        // "remind me at 5 pm to call mom" — the time comes first.
        val timeFirst = Regex("""(?i)^(.+?)\s+to\s+(.+)$""").find(rest)
        if (timeFirst != null) {
            val maybeTime = timeFirst.groupValues[1].trim()
            val text = timeFirst.groupValues[2].trim()
            if (text.isNotBlank() && EmaTimeParser.parse(maybeTime) != null && maybeTime.split(' ').size <= 4) {
                return EmaCommand.SetReminder(text, maybeTime)
            }
        }

        // "<what> at|by|on|in <when>"
        val withTime = Regex("""(?i)^(.+?)\s+(?:at|by|on|in|before)\s+(.+)$""").find(rest)
        if (withTime != null) {
            val text = withTime.groupValues[1].trim()
            val timeText = withTime.groupValues[2].trim()
            if (text.isNotBlank() && EmaTimeParser.parse(timeText) != null) {
                return EmaCommand.SetReminder(text, timeText)
            }
        }

        return EmaCommand.SetReminder(rest, "")
    }

    // ---------------------------------------------------------------- alarm

    private fun alarm(command: String, lower: String): EmaCommand? {
        if (!lower.contains("alarm")) return null
        if (lower.contains("alarm clock app") || lower == "open alarm") return null
        val match = Regex(
            """(?i)^(?:(?:set|create|make|put|schedule|add)\s+)?(?:an?\s+)?alarms?\s*(?:clock)?\s*(?:in\s+clock\s+)?(?:for|at|to|of|in)?\s*(.+)$"""
        ).find(command) ?: return null
        val value = match.groupValues[1].trim()
            .replaceFirst(Regex("""(?i)^(?:in|at|for)\s+"""), "")
            .replace(Regex("""(?i)\s+(?:please|in clock|on clock)$"""), "")
            .trim()
        if (value.isBlank()) return null
        if (EmaTimeParser.parse(value) == null) return null
        return EmaCommand.SetAlarm(value)
    }

    private fun wakeMe(command: String, lower: String): EmaCommand? {
        if (!lower.startsWith("wake me")) return null
        val match = Regex("""(?i)^wake me(?: up)?\s*(?:at|by|around|to)?\s*(.+)$""").find(command) ?: return null
        val value = match.groupValues[1].trim()
        if (value.isBlank() || EmaTimeParser.parse(value) == null) return null
        return EmaCommand.SetAlarm(value)
    }

    // ---------------------------------------------------------------- timer

    private fun timer(command: String, lower: String): EmaCommand? {
        if (!lower.contains("timer") && !lower.contains("countdown") && !lower.contains("count down")) return null
        val match = Regex(
            """(?i)^(?:(?:set|start|create|put|make)\s+)?(?:a\s+|the\s+)?(?:timer|countdown|count down)\s*(?:for|of|to|at)?\s*(.+)$"""
        ).find(command) ?: return null
        val value = match.groupValues[1].trim()
        if (value.isBlank()) return null
        if (EmaTimeParser.parseDuration(value) == null) return null
        return EmaCommand.SetTimer(value)
    }

    // -------------------------------------------------------------- contact

    private fun saveContact(command: String, lower: String): EmaCommand? {
        if (!(lower.startsWith("save") || lower.startsWith("add") ||
                lower.startsWith("create") || lower.startsWith("store"))
        ) return null
        if (!lower.contains("contact") && !lower.contains("number") && !lower.contains(" as ")) return null

        val patterns = listOf(
            Regex("""(?i)^(?:save|add|store|create)\s+(.+?)\s+as\s+(?:a\s+)?(?:contact\s+)?(?:named\s+)?(.+)$"""),
            Regex("""(?i)^(?:save|add|store|create)\s+(.+?)['’]s\s+number\s+(?:as\s+)?(.+)$"""),
            Regex("""(?i)^(?:save|add|store|create)\s+(?:contact\s+)?(.+?)\s+(?:with\s+)?number\s+(.+)$""")
        )
        val numberPattern = Regex("""[+0-9()\-\s]{7,}""")
        for (pattern in patterns) {
            val match = pattern.find(command) ?: continue
            val first = match.groupValues[1].trim()
            val second = match.groupValues[2].trim()
            if (first.isBlank() || second.isBlank()) continue
            val firstIsNumber = numberPattern.matches(first)
            val secondIsNumber = numberPattern.matches(second)
            when {
                firstIsNumber && !secondIsNumber -> return EmaCommand.SaveContact(second, first)
                secondIsNumber && !firstIsNumber -> return EmaCommand.SaveContact(first, second)
            }
        }
        return null
    }

    // ------------------------------------------------------------ navigation

    private fun navigate(command: String, lower: String): EmaCommand? {
        val match = Regex(
            """(?i)^(?:navigate to|navigate|directions to|get directions to|show me directions to|show directions to|map out|route to|drive to|walk to|find the way to|how do i get to)\s+(.+)$"""
        ).find(command) ?: return null
        val destination = match.groupValues[1].trim()
        if (destination.isBlank()) return null
        return EmaCommand.NavigateTo(destination)
    }

    // -------------------------------------------------------------- settings

    private val SETTINGS_PAGES = listOf(
        "wi fi", "wifi", "wi-fi", "bluetooth", "display", "sound", "battery", "storage",
        "notification", "notifications", "accessibility", "security", "location", "apps",
        "about phone", "developer options", "network", "data usage", "hotspot", "tethering",
        "airplane mode", "brightness", "wallpaper", "lock screen", "date and time",
        "language", "connected devices", "digital wellbeing", "privacy", "password",
        "accounts", "default apps", "assistant", "voice", "gesture", "nfc", "sim",
        "memory", "theme", "font", "ringtone", "do not disturb", "focus"
    )

    private fun settings(command: String, lower: String): EmaCommand? {
        if (Regex("""(?i)^(?:open|go to|show|launch|start|take me to|enter)?\s*(?:the\s+)?settings(?:\s+app)?$""")
                .matches(command.trim())
        ) {
            return EmaCommand.OpenSettingsPage("")
        }

        if (!lower.contains("setting")) return null

        val target = command
            .replaceFirst(Regex("""(?i)^(?:open|go to|show|launch|start|take me to|enter)\s+"""), "")
            .replace(Regex("""(?i)\s+in\s+settings\s*$"""), "")
            .replace(Regex("""(?i)\s+settings\s*$"""), "")
            .replace(Regex("""(?i)^settings\s*[>»\-]?\s*"""), "")
            .trim()
        return if (target.isBlank() || target.equals("settings", ignoreCase = true)) {
            EmaCommand.OpenSettingsPage("")
        } else EmaCommand.OpenSettingsPage(target)
    }

    // ------------------------------------------------------------ web search

    private fun webSearch(command: String, lower: String): EmaCommand? {
        val match = Regex(
            """(?i)^(?:search with google|search google for|search on google|google search for|google search|google|search the web for|search online for|web search|look up online)\s+(.+)$"""
        ).find(command) ?: return null
        val query = match.groupValues[1].trim()
        if (query.isBlank()) return null
        return EmaCommand.WebSearch(query)
    }

    // ----------------------------------------------------------- app search

    private fun appSearch(command: String, lower: String): EmaCommand? {
        if (!lower.contains("search") && !lower.contains("find") &&
            !lower.contains("look up") && !lower.contains("look for")
        ) return null

        // "open <app> and search for <query>"
        Regex("""(?i)^(?:open|launch|go to|go into|start)\s+(.+?)\s+and\s+$SEARCH_VERB\s+(.+)$""")
            .find(command)?.let { m ->
                val app = m.groupValues[1].trim()
                val query = m.groupValues[2].trim()
                if (app.isNotBlank() && query.isNotBlank()) {
                    return EmaCommand.SearchInApp(query, app, wantsFirstResult(lower))
                }
            }

        // "search for <query> on <app>" / "find <query> in <app>"
        Regex("""(?i)^$SEARCH_VERB\s+(.+?)\s+(?:on|in|inside|using|via|within)\s+(.+)$""")
            .find(command)?.let { m ->
                val query = m.groupValues[1].trim()
                val app = m.groupValues[2].trim()
                if (query.isNotBlank() && app.isNotBlank() && plausibleApp(app)) {
                    return EmaCommand.SearchInApp(query, app, wantsFirstResult(lower))
                }
            }

        // "<app> search for <query>"
        Regex("""(?i)^(.+?)\s+(?:search for|search|look up|look for|find)\s+(.+)$""").find(command)?.let { m ->
            val app = m.groupValues[1].trim()
            val query = m.groupValues[2].trim()
            val appLower = app.lowercase()
            if (app.isNotBlank() && query.isNotBlank() && app.split(' ').size <= 4 &&
                !appLower.startsWith("search") && !appLower.startsWith("find") &&
                !appLower.startsWith("look") && appLower != "google" && appLower != "the web"
            ) {
                return EmaCommand.SearchInApp(query, app, wantsFirstResult(lower))
            }
        }

        // Bare "search <query>"; the router may still attach a remembered app.
        Regex("""(?i)^$SEARCH_VERB\s+(.+)$""").find(command)?.let { m ->
            val query = m.groupValues[1].trim()
            if (query.isNotBlank()) return EmaCommand.SearchInApp(query, null, wantsFirstResult(lower))
        }

        return null
    }

    private fun wantsFirstResult(lower: String): Boolean =
        lower.contains("first result") || lower.contains("first one") ||
            lower.contains("top result") || lower.contains("and open it") ||
            lower.contains("and play it") || lower.contains("and click it")

    // ----------------------------------------------------------------- open

    private fun open(command: String, lower: String): EmaCommand? {
        val match = Regex(
            """(?i)^(?:open|open up|launch|start|load|bring up|fire up|run|go to|go into|enter|switch to)\s+(.+)$"""
        ).find(command) ?: return null
        var target = match.groupValues[1].trim()
        if (target.isBlank()) return null

        target = target
            .replaceFirst(Regex("""(?i)^(?:the|my|an|a)\s+"""), "")
            .replace(Regex("""(?i)\s+(?:app|application)$"""), "")
            .trim()
        if (target.isBlank()) return null

        // "<section> in/on <app>" -> open the app, then activate the section.
        val sectioned = Regex("""(?i)^(.+?)\s+(?:in|on|inside|within|from)\s+(.+)$""").find(target)
        if (sectioned != null) {
            val section = sectioned.groupValues[1].trim()
            val app = sectioned.groupValues[2].trim()
            if (section.isNotBlank() && app.isNotBlank() && plausibleApp(app)) {
                return EmaCommand.OpenAppSection(app, section)
            }
        }

        // "<app> <section>" -> the router resolves the longest installed app prefix.
        return EmaCommand.OpenApp(target)
    }

    // ------------------------------------------------------------------ sms

    private fun smsComposer(command: String, lower: String): EmaCommand? {
        val match = Regex("""(?i)^(?:text|sms|send a text to|send an sms to)\s+(.+)$""").find(command) ?: return null
        val rest = match.groupValues[1].trim()
        if (rest.isBlank()) return null

        var recipient = rest
        var body = ""
        for (marker in BODY_MARKERS) {
            val index = indexOfMarker(rest, marker)
            if (index <= 0) continue
            recipient = rest.substring(0, index).trim()
            body = rest.substring(index + marker.length).trim()
            break
        }
        if (recipient.isBlank() || !plausibleRecipient(recipient)) return null
        return EmaCommand.OpenSmsComposer(recipient, body)
    }
}
