package com.ema.assistant

/** Routing for information questions already classified as non-actions.
 * Search is never started automatically: explicitly asking to search may send
 * the query to a search provider and is handled by the normal command grammar.
 */
object EmaQuestionPolicy {
    sealed class Decision {
        data class Local(val answer: String, val value: Double? = null) : Decision()
        object AskAi : Decision()
        object Unavailable : Decision()
    }

    fun decide(question: String, mode: EmaAssistantMode, aiConfigured: Boolean, previous: Double? = null): Decision {
        runCatching { EmaKnowledge.answer(question, previous = previous) }.getOrNull()?.let {
            return Decision.Local(it.text, it.value)
        }
        EmaInstructionKnowledge.explainIfSupported(question)?.let { return Decision.Local(it) }
        return if (mode.allowsAi && aiConfigured) Decision.AskAi else Decision.Unavailable
    }

    /** Used only after all command handlers have declined the utterance. */
    fun looksLikeQuestion(text: String): Boolean = Regex(
        "(?i)^(?:what|what's|who|who's|where|when|why|how|which|is|are|does|did|can|could|would|will)\\b"
    ).containsMatchIn(text.trim()) || text.trim().endsWith('?')

    fun unavailableMessage(): String =
        "I don't have a reliable offline answer for that question. " +
            "You can say search the web for followed by your question; EMA search needs a configured server and is disabled in native-only mode. " +
            "You can explicitly say search Google for instead. Online searches send your query to the provider and answers may be wrong."
}
