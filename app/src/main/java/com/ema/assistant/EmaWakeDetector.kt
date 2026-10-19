package com.ema.assistant

/**
 * Decides when the local Vosk decoder has heard "Hey EMA".
 *
 * Root causes this replaces (see the audit in docs/WAKE_WORD.md):
 *  1. The service compared the whole hypothesis for equality with "hey emma".
 *     Any earlier sound in the same decoder window turns the hypothesis into
 *     "[unk] hey emma" or "hey hey emma", which never matched, so the phrase
 *     was routinely missed after background noise or a false start.
 *  2. The grammar held a single phrase plus [unk], so every similar sound was
 *     forced onto "hey emma" (false accepts) or onto [unk]. In-vocabulary
 *     competitors ("hey", "emily", "gemma", ...) give the decoder somewhere
 *     else to put near-misses.
 *
 * Detection now searches the hypothesis for the consecutive pair
 * hey + (emma | ema). A partial hypothesis must show the pair on two
 * consecutive updates (partials flicker); a final result counts at once.
 *
 * Pure: the service feeds it decoder text; unit tests feed it strings.
 */
class EmaWakeDetector(
    /**
     * Lenient mode: one partial is enough to produce a candidate.
     *
     * It is only used when the owner has enrolled their voice, because then a
     * second stage (the personal check) throws the false candidates away.
     * Without a profile the old rule applies, so nothing changes for a phone
     * that never enrolled.
     */
    private val lenient: Boolean = false
) {
    private var partialStreak = 0

    /** Returns true when the wake phrase is detected. */
    fun onPartial(hypothesis: String): Boolean {
        if (containsWakePhrase(hypothesis)) {
            partialStreak++
            if (lenient || partialStreak >= 2) { partialStreak = 0; return true }
        } else {
            partialStreak = 0
        }
        return false
    }

    fun onFinal(hypothesis: String): Boolean {
        partialStreak = 0
        return containsWakePhrase(hypothesis)
    }

    fun reset() { partialStreak = 0 }

    companion object {
        private val WAKE_NAMES = setOf("emma", "ema")

        /**
         * Phrases and competitors for the decoder grammar. Every word here is
         * in the vocabulary of the bundled vosk-model-small-en-us-0.15 (checked
         * against the model's words.txt); out-of-vocabulary words would be
         * dropped by Vosk.
         */
        val GRAMMAR_PHRASES = listOf(
            "hey emma", "hey ema",
            "hey", "hi", "hay", "okay", "emma", "emmy", "gemma", "emily", "ella", "anna", "emmet", "amen",
            "hey emmy", "hey gemma", "hey emily", "hey ella", "hey anna"
        )

        /** JSON grammar string for org.vosk.Recognizer. */
        fun grammarJson(): String = (GRAMMAR_PHRASES + "[unk]").joinToString(", ", "[", "]") { "\"$it\"" }

        fun containsWakePhrase(hypothesis: String): Boolean {
            val tokens = hypothesis.lowercase().split(Regex("[^a-z\\[\\]]+")).filter { it.isNotBlank() && it != "[unk]" }
            for (i in 0 until tokens.size - 1) {
                if (tokens[i] == "hey" && tokens[i + 1] in WAKE_NAMES) return true
            }
            return false
        }
    }
}
