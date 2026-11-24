package com.ema.assistant

/** Explicit, read-only questions about a screen or the last remembered document.
 * This parser never treats source content as instructions and never guesses a source
 * for an ordinary knowledge question. Reference resolution uses the existing
 * short-lived document state in the caller.
 */
object EmaGroundedQuestion {
    enum class Source { SCREEN, DOCUMENT }
    data class Request(val source: Source, val question: String, val topicOnly: Boolean = false)

    private val about = Regex(
        "(?i)^what (?:does|did) (this screen|the screen|this page|the page|this document|the document|it|that) (?:say|mention) about\\s+(.+?)[?.!]*$"
    )
    private val onSource = Regex(
        "(?i)^((?:what|when|where|who|which|how much|how many)\\b.+?)\\s+(?:on|in) (this screen|the screen|this page|the page|this document|the document)[?.!]*$"
    )

    fun parse(raw: String): Request? {
        val text = raw.trim()
        if (text.length > 2_000) return null
        val match = about.matchEntire(text)
        if (match != null) {
            val topic = match.groupValues[2].trim().trimEnd('?', '.', '!').trim()
            if (topic.isBlank()) return null
            return Request(source(match.groupValues[1]), topic, topicOnly = true)
        }
        val direct = onSource.matchEntire(text) ?: return null
        val question = direct.groupValues[1].trim()
        if (question.isBlank() || question.matches(Regex("(?i)^(?:what|who|where|when|which)\\s+(?:is|are|was|were)$"))) return null
        return Request(source(direct.groupValues[2]), question)
    }

    private fun source(phrase: String): Source =
        if (phrase.contains("screen", ignoreCase = true) || phrase.contains("page", ignoreCase = true)) Source.SCREEN
        else Source.DOCUMENT
}
