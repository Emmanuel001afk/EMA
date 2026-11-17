package com.ema.assistant

/** Suggestions are fixed, read-only commands, never commands supplied by screen content. */
object EmaFollowUpSuggestions {
    val starters = listOf("Brief me", "Show my lists", "Summarize screen", "Start stopwatch")

    fun forReply(reply: String): List<String> = when {
        reply.startsWith("Saved note") || reply.startsWith("Updated your last note") ->
            listOf("Read my last note", "Show my notes", "Undo that")
        reply.startsWith("Added ") && reply.contains(" list") ->
            listOf("Show my lists", "Undo that")
        reply.startsWith("Draft only") -> listOf("Repeat that")
        else -> emptyList()
    }
}
