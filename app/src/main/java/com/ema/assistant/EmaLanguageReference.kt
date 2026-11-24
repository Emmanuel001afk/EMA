package com.ema.assistant

import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Explicit, read-only English spelling and offline dictionary requests. */
object EmaLanguageReference {
    sealed class Request {
        data class Spell(val word: String) : Request()
        data class CountLetters(val text: String) : Request()
        data class Define(val term: String) : Request()
        data class SenseInSentence(val term: String, val sentence: String) : Request()
        object Alphabet : Request()
        data class Invalid(val reason: String) : Request()
    }
    data class Entry(val partOfSpeech: String, val senses: List<String>)

    private const val MAX_TEXT = 160
    private fun tidy(s: String): String {
        val t = s.trim()
        val unquoted = if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') ||
                (t.first() == '“' && t.last() == '”') || (t.first() == '\'' && t.last() == '\'')))
            t.substring(1, t.length - 1) else t
        return Normalizer.normalize(unquoted.trim(), Normalizer.Form.NFC)
    }

    fun parse(raw: String): Request? {
        val text = raw.trim().trimEnd('?', '!', '.').trim()
        if (Regex("(?i)^(?:the |say the |what is the |what's the )?alphabet$").matches(text)) return Request.Alphabet
        val context = Regex("(?i)^(?:meaning of|define)\\s+([\\p{L}\\p{M}'’\\-]+)\\s+in (?:the )?sentence\\s*:\\s*(.+)$").matchEntire(text)
        if (context != null) {
            val term = tidy(context.groupValues[1]).lowercase(Locale.ROOT)
            val sentence = tidy(context.groupValues[2])
            if (term.length > 80 || sentence.length > 300 || sentence.isBlank())
                return Request.Invalid("Give me a word and a sentence of at most 300 characters.")
            return Request.SenseInSentence(term, sentence)
        }
        val spell = Regex("(?i)^(?:spell|how do (?:i|you) spell|how is .+? spelled)(?:\\s+(.+))?$").matchEntire(text)
        if (spell != null) {
            // The final form captures its target in a different position.
            val value = spell.groupValues[1].ifBlank {
                Regex("(?i)^how is (.+?) spelled$").matchEntire(text)?.groupValues?.get(1).orEmpty()
            }
            val word = tidy(value)
            if (word.isBlank()) return Request.Invalid("Tell me the word you want me to spell.")
            if (word.length > MAX_TEXT || !word.matches(Regex("^[\\p{L}\\p{M}]+(?:['’\\-][\\p{L}\\p{M}]+)*$"))) {
                return Request.Invalid("Give me one word to spell, with letters and optional hyphens or apostrophes.")
            }
            return Request.Spell(word)
        }
        val count = Regex("(?i)^how many letters (?:are )?(?:in|does) (.+?)(?: have)?$").matchEntire(text)
        if (count != null) {
            val content = tidy(count.groupValues[1])
            if (content.isBlank() || content.length > MAX_TEXT) return Request.Invalid("Give me text of at most $MAX_TEXT characters to count.")
            return Request.CountLetters(content)
        }
        val define = Regex("(?i)^(?:define|definition of|meaning of|what is the meaning of|what does)\\s+(.+?)(?:\\s+mean)?$").matchEntire(text)
        if (define != null) {
            // 'What does X' is not automatically a definition question.
            if (text.startsWith("what does ", true) && !text.endsWith(" mean", true)) return null
            val term = tidy(define.groupValues[1]).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
            if (term.length > MAX_TEXT || !term.matches(Regex("^[\\p{L}\\p{M}\\p{N}'’ .\\-]+$")) || term.split(' ').size > 5) {
                return Request.Invalid("Give me a word or short phrase to define, not a whole sentence.")
            }
            if (term.isBlank() || term in setOf("it", "this", "that", "these", "those")) {
                return Request.Invalid("Which word or phrase do you mean? Please say it explicitly.")
            }
            return Request.Define(term)
        }
        return null
    }

    fun localAnswer(request: Request): String? = when (request) {
        is Request.Spell -> {
            val chars = request.word.codePoints().toArray().map { String(Character.toChars(it)) }
            "${request.word} is spelled ${chars.joinToString(", ")}."
        }
        is Request.CountLetters -> {
            val count = request.text.codePoints().filter { Character.isLetter(it) }.count()
            "There ${if (count == 1L) "is 1 letter" else "are $count letters"} in ${request.text}. Spaces, punctuation and numbers are not counted."
        }
        Request.Alphabet -> "The English alphabet has 26 letters: " + ('A'..'Z').joinToString(", ") + "."
        is Request.Invalid -> request.reason
        is Request.Define, is Request.SenseInSentence -> null
    }

    fun bucket(term: String): String = term.firstOrNull()?.takeIf { it in 'a'..'z' }?.toString() ?: "other"

    /** Reads a bucket only. Matching is exact: no guesses at inflections or word senses. */
    fun find(term: String, openBucket: (String) -> InputStream): List<Entry> {
        val matches = mutableListOf<Entry>()
        GZIPInputStream(openBucket(bucket(term))).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (line.substringBefore('\t') == term) {
                    val fields = line.split('\t')
                    if (fields.size >= 3) matches += Entry(fields[1], fields.drop(2))
                }
            }
        }
        return matches
    }

    data class BaseForm(val word: String, val partOfSpeech: String)
    data class PossibleForm(val form: BaseForm, val entry: Entry)

    /** WordNet exception list and bounded English suffix rules. Candidates are
     * explicitly labelled possibilities, never treated as proven intent. */
    fun possibleForms(term: String, openBucket: (String) -> InputStream): List<PossibleForm> {
        if (term.contains(' ') || term.length < 3 || term.length > 80) return emptyList()
        val candidates = linkedSetOf<BaseForm>()
        val exceptionParts = mutableSetOf<String>()
        GZIPInputStream(openBucket("exceptions")).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (line.substringBefore('\t') == term) {
                    val fields = line.split('\t')
                    if (fields.size >= 3) {
                        exceptionParts += fields[1]
                        fields.drop(2).take(4).forEach { candidates += BaseForm(it, fields[1]) }
                    }
                }
            }
        }
        val rules = mapOf(
            "noun" to listOf("s" to "", "ses" to "s", "xes" to "x", "zes" to "z", "ches" to "ch", "shes" to "sh", "men" to "man", "ies" to "y"),
            "verb" to listOf("s" to "", "ies" to "y", "es" to "e", "es" to "", "ed" to "e", "ed" to "", "ing" to "e", "ing" to ""),
            "adjective" to listOf("er" to "", "er" to "e", "est" to "", "est" to "e")
        )
        for ((part, endings) in rules) if (part !in exceptionParts) {
            for ((suffix, replacement) in endings) {
                if (term.endsWith(suffix) && term.length > suffix.length + 1)
                    candidates += BaseForm(term.dropLast(suffix.length) + replacement, part)
            }
        }
        val wanted = candidates.filter { it.word != term }.take(24)
        val byBucket = wanted.groupBy { bucket(it.word) }
        val found = mutableMapOf<BaseForm, Entry>()
        for ((name, forms) in byBucket) {
            val wantedKeys = forms.toSet()
            GZIPInputStream(openBucket(name)).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val first = line.indexOf('\t')
                    if (first > 0) {
                        val second = line.indexOf('\t', first + 1)
                        if (second > first) {
                            val key = BaseForm(line.substring(0, first), line.substring(first + 1, second))
                            if (key in wantedKeys) found[key] = Entry(key.partOfSpeech, line.substring(second + 1).split('\t'))
                        }
                    }
                }
            }
        }
        return wanted.mapNotNull { form -> found[form]?.let { PossibleForm(form, it) } }.take(4)
    }

    private val CONTEXT_STOP = setOf("the", "and", "for", "with", "from", "that", "this", "there", "their", "was", "were", "are", "have", "has", "had", "into", "you", "your", "they", "them", "she", "her", "his", "him", "its", "not", "but", "what", "when", "where", "which", "who", "will", "can", "would", "could", "one", "some", "used", "use", "using")
    private fun contextTokens(text: String): Set<String> = Regex("[\\p{L}]{3,}").findAll(text.lowercase(Locale.ROOT)).map { it.value }
        .filter { it !in CONTEXT_STOP }.map {
            when {
                it.length > 5 && it.endsWith("ing") -> it.dropLast(3)
                it.length > 4 && it.endsWith("ed") -> it.dropLast(2)
                it.length > 4 && it.endsWith("s") -> it.dropLast(1)
                else -> it
            }
        }.toSet()

    /** Conservative lexical evidence, not full sentence understanding. */
    fun formatInSentence(term: String, sentence: String, entries: List<Entry>): String {
        val termPattern = Regex("(?i)(?<![\\p{L}\\p{M}])" + Regex.escape(term) + "(?![\\p{L}\\p{M}])")
        if (!termPattern.containsMatchIn(sentence)) return "I couldn't find the exact word $term in that sentence. Please repeat the word and sentence."
        if (entries.isEmpty()) return format(term, entries)
        val context = contextTokens(termPattern.replace(sentence, " "))
        val scored = entries.flatMap { entry -> entry.senses.map { sense ->
            Triple(entry.partOfSpeech, sense, context.intersect(contextTokens(sense)).size)
        } }.sortedByDescending { it.third }
        val best = scored.firstOrNull()
        if (best == null || best.third < 2 || scored.getOrNull(1)?.third == best.third) {
            return "I can't reliably choose a meaning from that sentence. " + format(term, entries)
        }
        return "One possible meaning of $term in that sentence is (${best.first}): ${best.second}. " +
            "I matched words in the sentence to the definition; this is not a certain interpretation."
    }

    fun formatWithForms(term: String, entries: List<Entry>, forms: List<PossibleForm>): String {
        if (forms.isEmpty()) return format(term, entries)
        val exact = if (entries.isEmpty()) "No exact entry for $term." else format(term, entries)
        val suggestions = forms.joinToString("\n") { "${it.form.word} (${it.form.partOfSpeech}): ${it.entry.senses.first()}" }
        return "$exact\nPossible base forms (context is needed to choose):\n$suggestions"
    }

    fun format(term: String, entries: List<Entry>): String {
        if (entries.isEmpty()) return "I couldn't find an exact offline dictionary entry for $term. Try its base form or a more specific phrase."
        val total = entries.sumOf { it.senses.size }
        val selected = entries.flatMap { entry -> entry.senses.take(2).map { "${entry.partOfSpeech}: $it" } }.take(6)
        return "WordNet 3.0 meanings for $term (context can change which meaning applies):\n" +
            selected.mapIndexed { i, sense -> "${i + 1}. $sense" }.joinToString("\n") +
            if (total > selected.size) "\nShowing ${selected.size} of $total recorded senses." else ""
    }
}
