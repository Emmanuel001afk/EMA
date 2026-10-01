package com.ema.assistant

/**
 * Deterministic language-to-command planner.
 *
 * This layer deliberately does not call AI and does not know provider-specific
 * apps. It normalizes common assistant phrasing and turns compound requests
 * into the command forms already understood by EmaCommandRouter.
 */
object EmaNativeCommandPlanner {

    fun plan(raw: String): List<String> {
        val command = normalize(raw)
        if (command.isBlank()) return emptyList()

        // Keep already-supported compound app actions as one semantic request.
        // Their existing parsers preserve the app/entity relationship.
        if (Regex("(?i)^open\\s+.+?\\s+and\\s+(?:play|search|find|message|send)\\b").matches(command)) {
            val translated = translateCompoundAppCommand(command)
            if (translated != null) return translated
        }

        // Explicit "then" is always a sequence boundary unless it is part of
        // a message body. The router already protects message bodies further.
        val thenParts = command.split(Regex("(?i)\\s+then\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (thenParts.size > 1) return thenParts

        // Natural assistant speech often uses "and" instead of "then".
        // Only split when the following phrase begins with an action verb.
        val actionAnd = Regex(
            "(?i)\\s+and\\s+(?=(?:open|go|search|find|play|listen|send|message|call|dial|set|turn|enable|disable|increase|decrease|lock|unlock|read|show|dismiss|clear|tap|press|type|scroll|navigate|restart|power|switch)\\b)"
        )
        val andParts = command.split(actionAnd)
            .map { it.trim().trim(',', ';') }
            .filter { it.isNotBlank() }
        if (andParts.size > 1 && !looksLikeMessageBody(command)) return andParts

        // Semicolon-separated assistant actions are unambiguous.
        val semicolonParts = command.split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (semicolonParts.size > 1) return semicolonParts

        return listOf(command)
    }

    private fun translateCompoundAppCommand(command: String): List<String>? {
        val media = Regex("(?i)^(?:open|go\\s+(?:into|to))\\s+(.+?)\\s+and\\s+(?:play|listen\\s+to)\\s+(.+)$").find(command)
        if (media != null) {
            val app = media.groupValues[1].trim()
            val query = media.groupValues[2].trim()
            if (app.isNotBlank() && query.isNotBlank()) {
                return listOf("open $app", "play $query on $app")
            }
        }

        val search = Regex("(?i)^(?:open|go\\s+(?:into|to))\\s+(.+?)\\s+and\\s+(?:search(?:\\s+for)?|find)\\s+(.+)$").find(command)
        if (search != null) {
            val app = search.groupValues[1].trim()
            val query = search.groupValues[2].trim()
            if (app.isNotBlank() && query.isNotBlank()) {
                return listOf("open $app", "search $query on $app")
            }
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

    private fun looksLikeMessageBody(command: String): Boolean {
        val lower = command.lowercase()
        return lower.startsWith("message ") ||
            lower.startsWith("send a message ") ||
            lower.startsWith("tell ") ||
            Regex("(?i)^open\\s+.+?\\s+(?:message|send).+\\s+(?:that|saying|to say)\\s+.+$").matches(command)
    }

    private fun normalize(raw: String): String {
        return raw.trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)^(please|kindly)\\s+"), "")
            .trim()
    }
}
