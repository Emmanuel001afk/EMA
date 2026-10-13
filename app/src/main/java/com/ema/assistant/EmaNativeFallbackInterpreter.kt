package com.ema.assistant

/**
 * The native safety net.
 *
 * EmaCommandGrammar understands the phrasings EMA is designed for. This layer
 * catches the rest *without* AI, so the digital assistant never dead-ends just
 * because a sentence was worded unusually or because the AI tunnel is
 * unreachable, disabled, or not deployed.
 *
 * It works by grounding the request in what is actually installed on the device:
 * if a real application name appears in the sentence and an action verb appears
 * with it, EMA can act. Nothing is invented and nothing is guessed — when there
 * is no installed app and no device verb, this returns null and only then may
 * the optional AI layer be consulted.
 */
object EmaNativeFallbackInterpreter {

    private val OPEN_VERBS = setOf(
        "open", "launch", "start", "load", "run", "enter", "show", "bring up",
        "fire up", "switch to", "go to", "get into", "use"
    )

    private val PLAY_VERBS = setOf("play", "put on", "listen to", "stream", "start playing", "shuffle")

    private val SEARCH_VERBS = setOf("search", "search for", "find", "look up", "look for", "look", "google")

    private val MESSAGE_VERBS = setOf("message", "send", "text", "dm", "write to", "chat", "tell", "whatsapp")

    data class Result(val command: EmaCommand, val via: String)

    /**
     * @param utterance the user's sentence, already normalised
     * @param installed applications really present on this device
     */
    fun interpret(utterance: String, installed: List<EmaAppResolver.App>): Result? {
        val text = EmaCommandGrammar.normalize(utterance)
        if (text.isBlank() || installed.isEmpty()) return null

        // Knowledge questions are rejected before any app grounding happens.
        // Otherwise a question that merely mentions an installed application -
        // "what is spotify premium" - would be turned into launching it.
        if (isKnowledgeQuestion(text.lowercase())) return null

        val span = EmaAppResolver.findSpan(text, installed)

        if (span != null) {
            resolveWithApp(text, span, installed)?.let { return it }
        }

        // No installed app was named. Only a genuine device action may proceed;
        // anything else is allowed to escalate instead of becoming a guess.
        resolveWithoutApp(text)?.let { return it }
        return null
    }

    private fun resolveWithApp(
        text: String,
        span: EmaAppResolver.SpanMatch,
        installed: List<EmaAppResolver.App>
    ): Result? {
        val app = span.app
        val remainder = EmaAppResolver.removeSpan(text, span).trim()
        val lower = remainder.lowercase()

        // Removing the app name leaves the delegation scaffolding behind:
        //   "get spotify to play some music"   -> "get to play some music"
        //   "make chrome open the news site"   -> "make open the news site"
        // Both forms are unwrapped here so the real action verb is what gets matched.
        val delegation = Regex("""(?i)^(?:get|make|have|let|ask|force|use|tell)\s+(?:it\s+|them\s+)?to\s+(.+)$""")
            .find(remainder)
        val actionText = delegation?.groupValues?.getOrNull(1)?.trim()
            ?: remainder.replaceFirst(
                Regex("""(?i)^(?:get|make|have|let|ask|force|use|tell)\s+(?:it\s+|them\s+)?(?:to\s+)?"""), ""
            ).trim()
        val actionLower = actionText.lowercase()

        // Strip the leading action verb to get the content slot.
        fun contentAfter(verbs: Set<String>): String {
            var value = actionText
            val sorted = verbs.sortedByDescending { it.length }
            for (verb in sorted) {
                val pattern = Regex("(?i)^" + Regex.escape(verb) + "\\s+")
                if (pattern.containsMatchIn(value)) {
                    value = value.replaceFirst(pattern, "").trim()
                    break
                }
            }
            return value
                .replaceFirst(Regex("""(?i)^(?:the|a|an|some|my|me|it|that|this)\s+"""), "")
                .trim()
        }

        return when {
            matchesVerb(actionLower, PLAY_VERBS) -> {
                val query = contentAfter(PLAY_VERBS)
                if (query.isBlank()) Result(EmaCommand.OpenApp(app.label), "app-only-play")
                else Result(EmaCommand.PlayMedia(query, app.label), "delegated-play")
            }

            matchesVerb(actionLower, SEARCH_VERBS) -> {
                val query = contentAfter(SEARCH_VERBS)
                if (query.isBlank()) Result(EmaCommand.OpenApp(app.label), "app-only-search")
                else Result(EmaCommand.SearchInApp(query, app.label), "delegated-search")
            }

            matchesVerb(actionLower, MESSAGE_VERBS) -> {
                // Try the structured grammar first on the app-qualified sentence;
                // that keeps recipient and body roles correct.
                val canonical = "$actionText on ${app.label}"
                val parsed = EmaCommandGrammar.interpret(canonical)
                if (parsed is EmaCommand.SendMessage && !parsed.recipient.isBlank()) {
                    Result(parsed.copy(app = parsed.app ?: app.label), "delegated-message")
                } else {
                    // No extractable recipient: opening the right app is still a
                    // useful, non-destructive native action.
                    Result(EmaCommand.OpenApp(app.label), "app-open-for-message")
                }
            }

            matchesVerb(actionLower, OPEN_VERBS) -> {
                val section = contentAfter(OPEN_VERBS)
                if (section.isBlank() || section.equals(app.label, ignoreCase = true)) {
                    Result(EmaCommand.OpenApp(app.label), "open-app")
                } else {
                    Result(EmaCommand.OpenAppSection(app.label, section), "open-app-section")
                }
            }

            // The app was named with no recognisable verb: "spotify" alone,
            // "spotify please". Opening it is the correct, safe interpretation.
            actionText.isBlank() || actionText.split(Regex("\\s+")).size <= 3 ->
                Result(EmaCommand.OpenApp(app.label), "app-mention")

            else -> null
        }
    }

    private fun resolveWithoutApp(text: String): Result? {
        val lower = text.lowercase()

        // Knowledge questions must never be turned into a device action.
        if (isKnowledgeQuestion(lower)) return null

        val playVerb = PLAY_VERBS.sortedByDescending { it.length }.firstOrNull { lower.startsWith("$it ") }
        if (playVerb != null) {
            val query = text.substring(playVerb.length).trim()
                .replaceFirst(Regex("""(?i)^(?:the|a|an|some|my)\s+"""), "")
                .trim()
            if (query.isNotBlank()) return Result(EmaCommand.PlayMedia(query, null), "play-no-app")
        }

        val searchVerb = SEARCH_VERBS.sortedByDescending { it.length }.firstOrNull { lower.startsWith("$it ") }
        if (searchVerb != null) {
            val query = text.substring(searchVerb.length).trim()
                .replaceFirst(Regex("""(?i)^(?:for|the|a|an)\s+"""), "")
                .trim()
            if (query.isNotBlank()) return Result(EmaCommand.SearchInApp(query, null), "search-no-app")
        }

        return null
    }

    private fun matchesVerb(lower: String, verbs: Set<String>): Boolean =
        verbs.any { lower == it || lower.startsWith("$it ") || lower.startsWith("$it to ") }

    private val KNOWLEDGE_PREFIXES = listOf(
        "what is", "what's", "what are", "who is", "who's", "who are", "how do",
        "how does", "how is", "how are", "why is", "why are", "why do", "when did",
        "when was", "where is", "where are", "explain", "describe", "define",
        "tell me about", "tell me what", "do you know", "can you explain",
        "what does", "what does not", "is it", "are there", "which is", "compare",
        "summarise", "summarize", "translate", "meaning of", "difference between"
    )

    private fun isKnowledgeQuestion(lower: String): Boolean {
        if (KNOWLEDGE_PREFIXES.any { lower.startsWith(it) }) return true
        // A trailing question mark with no action verb is a question, not a command.
        val hasActionVerb = (OPEN_VERBS + PLAY_VERBS + SEARCH_VERBS + MESSAGE_VERBS)
            .any { lower.startsWith("$it ") || lower.startsWith("$it to ") }
        return !hasActionVerb && lower.endsWith("?")
    }
}
