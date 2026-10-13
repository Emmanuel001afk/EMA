package com.ema.assistant

/**
 * Deterministic language-to-command planner.
 *
 * This layer deliberately does not call AI and does not know provider-specific
 * apps. It normalizes common assistant phrasing and turns compound requests
 * into command forms already understood by EmaCommandRouter.
 *
 * The hard part is not splitting — it is knowing when *not* to. A message body
 * is free text that legitimately contains "and", "then" and action verbs, so
 * splitting "text Ada saying I will land then call you" would send a truncated
 * message and place a phone call the user never asked for. Every connector
 * split is therefore gated on [looksLikeMessageBody].
 */
object EmaNativeCommandPlanner {

    /** Action verbs that may begin a new step after a connector. */
    private const val ACTION_VERBS =
        "(?:open|go|search|find|play|listen|send|message|call|dial|set|turn|enable|disable|" +
            "increase|decrease|lock|unlock|read|show|dismiss|clear|tap|press|type|scroll|" +
            "navigate|restart|power|switch)"

    /** "open|go to|go into <app> and <media|search|message verb> ...". */
    private val COMPOUND_APP_GATE = Regex(
        "(?i)^(?:open|go\\s+(?:into|to))\\s+.+?\\s+and\\s+" +
            "(?:play|listen|search|find|message|send)\\b"
    )

    private val THEN_BOUNDARY = Regex("(?i)\\s+then\\s+(?=$ACTION_VERBS\\b)")
    private val AND_BOUNDARY = Regex("(?i)\\s+and\\s+(?=$ACTION_VERBS\\b)")

    /** "<send|message|text|email> … saying/that says <body>" anywhere in the utterance. */
    private val MESSAGE_SHAPE = Regex(
        "(?i)^(?:send|message|text|email)\\s+.+?\\s+(?:that says|saying|to say|that)\\s+.+"
    )

    /** "open <app> … message/send … saying <body>". */
    private val COMPOUND_MESSAGE_SHAPE = Regex(
        "(?i)^open\\s+.+?\\s+(?:message|send).+\\s+(?:that says|saying|to say|that)\\s+.+"
    )

    fun plan(raw: String): List<String> {
        val command = normalize(raw)
        if (command.isBlank()) return emptyList()

        // A single app-directed compound command keeps its semantic target
        // together. App names are resolved later by EmaAppActionEngine.
        //
        // This gate must accept every form translateCompoundAppCommand handles.
        // It used to admit only "open ... and play|search|find|message|send", so
        // "go to Spotify and play Arcade" and "open Spotify and listen to X" fell
        // through to a plain connector split and lost the "on <app>" target,
        // which is what makes the second step actionable.
        // containsMatchIn, not matches: the gate is a prefix test, and matches()
        // demands the whole utterance be consumed. With matches() this never fired
        // for "open Spotify and play Arcade", so translateCompoundAppCommand was
        // dead code and the request degraded to a bare "play Arcade" with no app —
        // losing exactly the target that makes the second step actionable.
        if (COMPOUND_APP_GATE.containsMatchIn(command)) {
            translateCompoundAppCommand(command)?.let { return it }
        }

        // Split explicit sequences only at action boundaries, and never inside a
        // message body.
        if (!looksLikeMessageBody(command)) {
            val thenParts = command.split(THEN_BOUNDARY)
                .map { cleanPart(it) }.filter { it.isNotBlank() }
            if (thenParts.size > 1) return thenParts.flatMap { plan(it) }
        }

        // If there is more than one action connector, split at the action verb.
        // A single app-directed "open X and <action>" was handled above.
        if (!looksLikeMessageBody(command)) {
            val andParts = command.split(AND_BOUNDARY)
                .map { cleanPart(it) }.filter { it.isNotBlank() }
            if (andParts.size > 1) return andParts.flatMap { plan(it) }
        }

        // Semicolon-separated commands are unambiguous — unless the whole thing
        // is one message whose body happens to contain a semicolon.
        if (!looksLikeMessageBody(command)) {
            val semicolonParts = command.split(';').map { cleanPart(it) }.filter { it.isNotBlank() }
            if (semicolonParts.size > 1) return semicolonParts.flatMap { plan(it) }
        }

        return listOf(command)
    }

    private fun translateCompoundAppCommand(command: String): List<String>? {
        val media = Regex("(?i)^(?:open|go\\s+(?:into|to))\\s+(.+?)\\s+and\\s+(?:play|listen\\s+to)\\s+(.+)$").find(command)
        if (media != null) {
            val app = media.groupValues[1].trim()
            val query = media.groupValues[2].trim()
            if (app.isNotBlank() && query.isNotBlank()) return listOf("open $app", "play $query on $app")
        }

        val search = Regex("(?i)^(?:open|go\\s+(?:into|to))\\s+(.+?)\\s+and\\s+(?:search(?:\\s+for)?|find)\\s+(.+)$").find(command)
        if (search != null) {
            val app = search.groupValues[1].trim()
            val query = search.groupValues[2].trim()
            if (app.isNotBlank() && query.isNotBlank()) return listOf("open $app", "search $query on $app")
        }

        val message = Regex(
            "(?i)^(?:open|go\\s+(?:into|to))\\s+(.+?)\\s+and\\s+(?:message|send(?:\\s+(?:a\\s+)?message)?)\\s+(.+?)\\s+(?:that says|saying|to say|that)\\s+(.+?)(?:\\s+and\\s+send)?$"
        ).find(command)
        if (message != null) {
            val app = message.groupValues[1].trim()
            val recipient = message.groupValues[2].trim()
            val body = message.groupValues[3].trim()
            if (app.isNotBlank() && recipient.isNotBlank() && body.isNotBlank()) {
                return listOf("message $recipient on $app saying $body")
            }
        }

        return null
    }

    /**
     * Whether the whole utterance is one message whose body must stay intact.
     *
     * Recognised by its opening verb, or by a "<verb> … saying/that says <body>"
     * shape anywhere in the utterance.
     */
    private fun looksLikeMessageBody(command: String): Boolean {
        val lower = command.lowercase()
        val messageOpeners = listOf(
            "message ", "send a message ", "send message ", "send a text ", "send text ",
            "text ", "tell ", "email ", "send an email ", "send email ", "whatsapp "
        )
        if (messageOpeners.any { lower.startsWith(it) }) return true
        return MESSAGE_SHAPE.containsMatchIn(command) || COMPOUND_MESSAGE_SHAPE.containsMatchIn(command)
    }

    /**
     * Trim a split fragment.
     *
     * Splitting "turn on wifi and then open YouTube" at "then" leaves a dangling
     * connector on the first fragment, which no downstream parser accepts, so
     * trailing connectors and punctuation are removed.
     */
    private fun cleanPart(part: String): String = part.trim()
        .trim(',', ';')
        .replace(Regex("(?i)[,;]?\\s*(?:and|then)$"), "")
        .trim()

    private fun normalize(raw: String): String =
        raw.trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)^(please|kindly)\\s+"), "")
            .trim()
}
