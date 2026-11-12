package com.ema.assistant


/**
 * The word set the local command decoder is allowed to produce.
 *
 * Free-form decoding with a *small* model is the main reason local dictation
 * feels worse than the network recogniser: nothing constrains it, so it invents
 * plausible-sounding words. Vosk accepts a JSON grammar, and the wake word path
 * already uses one. Restricting the command decoder to the vocabulary EMA can
 * actually act on is free - no extra model, no extra download - and removes
 * most of the nonsense.
 *
 * The grammar is deliberately generous: it is made of whole phrases, and
 * `[unk]` is included as an escape hatch so a word outside the set is reported
 * as unknown instead of being bent into a command the owner never said.
 *
 * Pure: no Android and no native calls, so it is unit tested on the JVM.
 */
object EmaCommandVocabulary {

    /** A phrase the decoder may output. */
    private val PHRASES = linkedSetOf(
        // Wake word and politeness, because the owner often repeats them.
        "hey emma", "hey ema", "emma",
        // Assistant mode and state.
        "what mode are you in", "turn on ai", "turn off ai", "turn on the ai", "turn off the ai",
        "stop listening", "cancel", "never mind", "wait", "help", "what can you do",
        // App and navigation verbs.
        "open", "launch", "close", "go back", "go home", "switch to",
        // Communication.
        "call", "dial", "phone", "send a message to", "send a message", "message", "text",
        "reply", "read my messages", "read my notifications", "clear notifications",
        // Media.
        "play", "pause", "resume", "stop", "next", "previous", "next song", "previous song",
        "skip", "volume up", "volume down", "mute", "unmute", "play music",
        // Device settings.
        "turn on", "turn off", "turn on wifi", "turn off wifi", "turn on bluetooth",
        "turn off bluetooth", "turn on airplane mode", "turn off airplane mode",
        "turn on do not disturb", "turn off do not disturb", "turn on the flashlight",
        "turn off the flashlight", "torch on", "torch off", "increase brightness",
        "decrease brightness", "set brightness", "set volume", "take a screenshot",
        // Time, alarms and reminders.
        "set an alarm", "set an alarm for", "wake me up", "wake me up at", "cancel the alarm",
        "set a timer", "set a timer for", "cancel the timer", "remind me", "remind me to",
        "remind me at", "what time is it", "what is the date",
        // Knowledge and memory.
        "remember", "remember that", "forget what you learned", "learn my voice", "why did that fail",
        "search for", "search", "look up", "what is", "who is", "how much is"
    )

    /** The fixed words a name, app label or number slot still needs. */
    private val WORDS = linkedSetOf(
        "a", "an", "the", "to", "for", "on", "off", "in", "at", "and", "from", "with",
        "my", "me", "i", "you", "is", "are", "it", "of", "please", "now", "about",
        "am", "pm", "percent", "one", "two", "three", "four", "five", "six", "seven",
        "eight", "nine", "ten", "zero", "hundred", "half", "minute", "minutes", "hour",
        "hours", "second", "seconds", "today", "tomorrow", "tonight", "morning",
        "afternoon", "evening", "night", "monday", "tuesday", "wednesday", "thursday",
        "friday", "saturday", "sunday", "o'clock", "message", "that", "this",
        "song", "music", "playlist", "album", "artist", "call", "text"
    )

    /**
     * Words the owner can add at runtime: learned shortcut triggers and app
     * labels. They are already the strings EMA is asked to recognise, so letting
     * the decoder spell them is not a new capability, it is the point.
     */
    private fun dynamic(learned: List<String>): List<String> =
        learned.flatMap { words(it) }.distinct()

    /** Every phrase and word the decoder may produce, in a stable order. */
    fun phrases(learned: List<String> = emptyList()): List<String> {
        val out = LinkedHashSet<String>()
        out += PHRASES
        out += WORDS
        out += dynamic(learned)
        return out.toList()
    }

    /**
     * JSON grammar string for `org.vosk.Recognizer`.
     *
     * Vosk reads this as a list of alternatives; `[unk]` keeps a word outside
     * the set reportable instead of forcing a nearby in-set word.
     */
    fun grammarJson(learned: List<String> = emptyList()): String = json(phrases(learned))

    private fun json(all: List<String>): String =
        (all + "[unk]").joinToString(", ", "[", "]") { "\"$it\"" }

    /**
     * The phrases [modelWords] can actually decode.
     *
     * A grammar may only contain words the model knows: a learned app name such
     * as "WhatsApp" is usually not in a small English model's vocabulary, and an
     * out-of-vocabulary alternative is at best dropped and at worst damages the
     * decoder. A phrase is kept only when *every* word in it is known.
     */
    fun decodable(candidates: List<String>, modelWords: Set<String>): List<String> =
        candidates.filter { phrase -> words(phrase).all { it in modelWords } }

    /** The lowercase words in a phrase, ignoring spacing and punctuation-only tokens. */
    fun words(phrase: String): List<String> = phrase.lowercase()
        .split(Regex("\\s+"))
        .map { word -> word.trim { !it.isLetterOrDigit() } }
        .filter { it.isNotBlank() }

    /**
     * A grammar for this model, or null when a grammar would do more harm than
     * good - no word list available, or too few usable phrases left.
     *
     * Null means "decode free-form", which is the old behaviour, so a model
     * without a readable `graph/words.txt` loses the accuracy gain but never
     * loses the ability to listen.
     */
    fun grammarFor(modelWords: Set<String>?, learned: List<String> = emptyList()): String? {
        if (modelWords == null || modelWords.isEmpty()) return null
        val usable = decodable(phrases(learned), modelWords)
        // A grammar of a handful of phrases would make everything else "unknown".
        if (usable.size < MIN_GRAMMAR_PHRASES) return null
        return json(usable)
    }

    /** Below this many usable phrases, decoding is left free-form. */
    const val MIN_GRAMMAR_PHRASES = 12

    /** Phrases that must survive any refactor, because the parsers rely on them. */
    fun contains(phrase: String): Boolean = phrase.lowercase() in PHRASES
}

/**
 * Turns Vosk's word timings into a confidence score the platform interface can
 * carry.
 *
 * Vosk reports one entry per recognised word with a `conf` value. Taking the
 * weakest word - not the average - is deliberate: "call emma" is only a reliable
 * command if *every* word was heard, and an average would let one confident word
 * hide one guessed word.
 */
object EmaDecodeConfidence {

    /** Below this, a local result is treated as a guess and not acted on. */
    const val MIN_USABLE = 0.45f

    /**
     * Weakest word confidence in [json], or [fallback] when the decoder did not
     * return word timings (words are only included when `setWords(true)` is on).
     *
     * Parsed with [EmaJson] rather than `org.json`, so this is real JVM code
     * covered by real unit tests instead of the platform's throwing stubs.
     */
    fun weakest(json: String, fallback: Float = 0.0f): Float {
        val parsed = EmaJson.readObject(json) ?: return fallback
        val words = parsed["result"] as? List<*> ?: return fallback
        var weakest: Float? = null
        for (entry in words) {
            val word = entry as? Map<*, *> ?: continue
            val confidence = (word["conf"] as? Number)?.toFloat() ?: continue
            if (confidence < 0f) continue
            weakest = weakest?.let { minOf(it, confidence) } ?: confidence
        }
        return weakest ?: fallback
    }

    /** Clamp a raw decoder confidence into the 0..1 range the platform expects. */
    fun score(raw: Float): Float = raw.coerceIn(0f, 1f)
}
