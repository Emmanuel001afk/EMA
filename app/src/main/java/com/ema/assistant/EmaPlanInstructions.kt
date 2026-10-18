package com.ema.assistant

/**
 * Instruction clauses inside a spoken request are plan *options*, not steps.
 *
 * "Open Spotify and play Burna Boy, make sure one action does not hinder
 * another, then go home" contains two commands and two instructions. Passing
 * the instructions to the grammar/semantic layers made them fake steps ("make
 * sure ...") that failed and stopped the real work. This parser strips them,
 * records what they mean, and leaves the actual requests untouched.
 *
 * Pure Kotlin: unit tested, no Android types.
 */
object EmaPlanInstructions {

    data class Parsed(
        /** The request with instruction clauses removed. */
        val cleaned: String,
        /** Perform a final Home global action after the request. */
        val goHome: Boolean,
        /** Strict sequential execution; this is already the default. */
        val strictSequential: Boolean
    )

    private val STRICT_SEQUENCE = listOf(
        Regex(
            "(?i)[,;]?\\s*(?:and\\s+|then\\s+)?(?:please\\s+)?make\\s+sure\\s+(?:that\\s+)?" +
                "(?:one|each|every|the)\\s+(?:action|step|thing|task)s?\\s+" +
                "(?:does|do|will|would)\\s*(?:not|n't)\\s+" +
                "(?:hinder|interfere\\s+with|interrupt|block|get\\s+in\\s+the\\s+way\\s+of|overlap\\s+with)\\s+" +
                "(?:one\\s+another|each\\s+other|the\\s+other|another)"
        ),
        Regex(
            "(?i)[,;]?\\s*(?:and\\s+|then\\s+)?(?:please\\s+)?make\\s+sure\\s+(?:that\\s+)?" +
                "(?:the\\s+)?(?:actions|steps|things|tasks)\\s+(?:run|happen|go)\\s+" +
                "(?:one\\s+at\\s+a\\s+time|in\\s+order|sequentially)"
        ),
        Regex("(?i)[,;]?\\s*(?:and\\s+|then\\s+)?(?:please\\s+)?one\\s+(?:action|step|thing)\\s+at\\s+a\\s+time"),
        Regex("(?i)[,;]?\\s*(?:and\\s+|then\\s+)?(?:please\\s+)?(?:do|run)\\s+(?:it|them|things|actions|steps)\\s+one\\s+at\\s+a\\s+time")
    )

    private val GO_HOME = listOf(
        Regex(
            "(?i)[,;]?\\s*(?:and\\s+|then\\s+)?(?:when\\s+(?:you'?re|you\\s+are|it'?s)\\s+(?:done|finished)\\s*,?\\s*)?" +
                "(?:please\\s+)?(?:go|return|come)\\s+(?:back\\s+)?home" +
                "(?:\\s+(?:when\\s+(?:you'?re|you\\s+are)\\s+(?:done|finished)|after(?:wards|\\s+that|\\s+you'?re\\s+done)?|at\\s+the\\s+end|when\\s+finished|once\\s+(?:done|finished)))?"
        )
    )

    /** Commands that mean "stop the running plan". */
    private val CANCEL = setOf(
        "stop", "stop it", "stop that", "stop now", "cancel", "cancel it", "cancel that",
        "abort", "abort it", "halt", "never mind", "nevermind", "forget it", "quit it"
    )

    fun isCancel(command: String): Boolean =
        command.lowercase().trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ") in CANCEL

    /** Major steps are worth an optional "continue?" prompt. Home is not. */
    fun isMajorStep(text: String): Boolean {
        val first = text.trim().substringBefore(' ').lowercase()
        if (first == "go" && text.lowercase().contains("home")) return false
        return first in setOf(
            "open", "launch", "start", "run", "play", "search", "find", "send", "message",
            "text", "call", "set", "turn", "enable", "disable", "increase", "decrease"
        )
    }

    fun parse(command: String): Parsed {
        val original = command.trim()
        var text = original
        var goHome = false
        var strict = false
        // Instructions must never be removed from a message body or reminder text.
        val bodyStart = bodyStart(text)
        var guard = 0
        while (guard++ < 6) {
            var changed = false
            for (pattern in STRICT_SEQUENCE + GO_HOME) {
                val match = pattern.find(text) ?: continue
                if (bodyStart >= 0 && match.range.first > bodyStart) continue
                text = remove(text, match.range)
                if (pattern in GO_HOME) goHome = true else strict = true
                changed = true
                break
            }
            if (!changed) break
        }
        val cleaned = tidy(text)
        // The whole utterance was the instruction: leave it as the command.
        if (cleaned.isBlank()) return Parsed(original, false, false)
        return Parsed(cleaned, goHome, strict)
    }

    /** Index where free text (a message body or reminder) begins, or -1. */
    private fun bodyStart(text: String): Int {
        val marker = Regex("(?i)\\b(?:saying|that\\s+says|which\\s+says|to\\s+say|with\\s+the\\s+message|with\\s+the\\s+text)\\b").find(text)
        val quote = text.indexOf('"')
        return when {
            marker == null && quote < 0 -> -1
            marker == null -> quote
            quote < 0 -> marker.range.first
            else -> minOf(marker.range.first, quote)
        }
    }

    private fun remove(text: String, range: IntRange): String =
        (text.substring(0, range.first) + " " + text.substring(range.last + 1))

    private fun tidy(text: String): String =
        text.replace(Regex("\\s+"), " ")
            .replace(Regex("^[,;:\\s]+"), "")
            .replace(Regex("[,;:\\s]+$"), "")
            .replace(Regex("\\s+,"), ",")
            .replace(Regex(",+"), ",")
            .replace(Regex("(?i)\\s+(?:and|then)\\s*$"), "")
            .replace(Regex("(?i)^\\s*(?:and|then)\\s+"), "")
            .trim()
}
