package com.ema.assistant

import java.text.Normalizer
import java.util.Locale

/** Read-only comparison of visible wording, not a semantic equivalence test. */
object EmaSentenceComparison {
    private const val MAX_LENGTH = 4000
    private const val MAX_WORDS = 200
    private val prefix = Regex("(?is)^compare (?:these |the )?sentences\\s*:\\s*(.*)$")
    private val wordPattern = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’][\\p{L}\\p{M}\\p{N}]+)*")
    private val markers = setOf("not", "never", "no", "only", "just", "all", "some", "every", "if", "unless", "but")

    /** Null means not a comparison request. The returned text is data only. */
    fun parse(raw: String): String? = prefix.matchEntire(raw.trim())?.groupValues?.get(1)

    private fun words(s: String): List<String> = wordPattern.findAll(Normalizer.normalize(s, Normalizer.Form.NFC))
        .map { it.value.lowercase(Locale.ROOT).replace('’', '\'') }.take(MAX_WORDS + 1).toList()

    private fun markersIn(tokens: List<String>): String {
        val found = tokens.mapIndexedNotNull { i, word ->
            if (word in markers || word in setOf("don't", "doesn't", "didn't", "can't", "won't")) "'$word' at word ${i + 1}" else null
        }
        return if (found.isEmpty()) "none detected" else found.take(12).joinToString(", ") + if (found.size > 12) ", …" else ""
    }

    fun respond(body: String): String {
        if (body.length > MAX_LENGTH) return invalid()
        val parts = body.split('|')
        if (parts.size != 2 || parts.any { it.isBlank() }) return invalid()
        val first = words(parts[0]); val second = words(parts[1])
        if (first.isEmpty() || second.isEmpty() || first.size > MAX_WORDS || second.size > MAX_WORDS) return invalid()
        val relation = when {
            first == second -> "The recognized words are the same in the same order, ignoring case and punctuation."
            first.size == second.size && first.groupingBy { it }.eachCount() == second.groupingBy { it }.eachCount() ->
                "The same recognized words appear in a different order; word placement can change meaning."
            else -> "The recognized wording differs."
        }
        return "$relation Sentence 1 markers: ${markersIn(first)}. Sentence 2 markers: ${markersIn(second)}. " +
            "These are surface cues, not proof of meaning or equivalence; scope, punctuation and context can matter. " +
            "I haven't executed either sentence."
    }

    private fun invalid() = "Give two nonempty sentences separated by one |, up to $MAX_LENGTH characters total and $MAX_WORDS words per sentence. I haven't executed either sentence."
}
