package com.ema.assistant

import java.text.Normalizer
import java.util.Locale

/** Explicit, read-only analysis of text supplied by the user; never an action plan. */
object EmaSentenceAnalysis {
    private const val LIMIT = 4000
    private val prefix = Regex("(?is)^(?:analy[sz]e|interpret) (?:this |the )?sentence\\s*:\\s*(.*)$")
    private val token = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’][\\p{L}\\p{M}\\p{N}]+)*")
    private val negate = Regex("(?i)\\b(?:not|never|no|don't|dont|doesn't|doesnt|didn't|didnt|cannot|can't|cant|won't|wont|without)\\b")
    private val request = Regex("(?i)^(?:please\\s+|(?:can|could|would|will) you(?: please)?\\s+|(?:i want|i need|i'd like|i would like) you to\\s+)")
    private val command = Regex("(?i)^(?:open|launch|send|message|text|call|search|find|play|stop|pause|read|show|turn|set|delete|remove|save|write|copy|tap|select|scroll|navigate|take)\\b")
    private val conditional = Regex("(?i)\\b(?:if|unless|provided that)\\b")
    private val explicitSarcasm = Regex("(?i)(?:/s(?:\\s|$)|#sarcasm\\b|\\[sarcasm\\])")
    private val quotes = Regex("\"[^\"]*\"|“[^”]*”")

    data class Analysis(val words: List<String>, val sentenceType: String, val notes: List<String>)

    /** Null means this is not an analysis request. Invalid input is still intercepted. */
    fun parse(raw: String): String? = prefix.matchEntire(raw.trim())?.groupValues?.get(1)

    fun analyze(text: String): Analysis? {
        if (text.isBlank() || text.length > LIMIT) return null
        // NFC gives consistent token boundaries for decomposed accents. It is never used to rewrite commands.
        val normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFC)
        val words = token.findAll(normalized).map { it.value }.take(201).toList()
        if (words.size > 200) return null
        // Quoted content is not evidence that the outer sentence is an instruction.
        val outer = quotes.replace(normalized, " ").trim().replace('’', '\'')
        val lowered = outer.lowercase(Locale.ROOT)
        val isQuestion = outer.trimEnd().endsWith('?') ||
            Regex("(?i)^(?:who|what|where|when|why|how|is|are|was|were|do|does|did|can|could|would|will|should)\\b").containsMatchIn(outer)
        val isRequest = request.containsMatchIn(outer)
        val isCommand = command.containsMatchIn(outer)
        val kind = when {
            isRequest -> "possible request"
            isQuestion -> "question"
            isCommand -> "possible instruction"
            else -> "statement or other sentence"
        }
        val notes = mutableListOf<String>()
        if (negate.containsMatchIn(outer)) notes += "Negation appears outside quoted text; its exact scope depends on context."
        if (conditional.containsMatchIn(outer)) notes += "A conditional marker appears; the condition may limit what is being said or requested."
        if (quotes.containsMatchIn(normalized)) notes += "Quoted words are content; they are not instructions to EMA."
        if (explicitSarcasm.containsMatchIn(lowered)) notes += "An explicit sarcasm marker appears; the intended meaning still depends on context."
        else notes += "Sarcasm cannot be determined reliably from wording alone."
        return Analysis(words, kind, notes)
    }

    fun respond(text: String): String {
        val result = analyze(text) ?: return "Please provide a sentence of at most $LIMIT characters and 200 words. I haven't taken any action."
        val wordSummary = if (result.words.isEmpty()) "No words recognized." else
            "Words (${result.words.size}): ${result.words.take(30).joinToString(", ")}${if (result.words.size > 30) ", …" else ""}."
        return "Offline, this looks like a ${result.sentenceType}; this is a tentative surface-level classification. $wordSummary " +
            result.notes.joinToString(" ") + " I haven't executed the sentence. This English-oriented analysis does not translate or establish the intended meaning."
    }
}
