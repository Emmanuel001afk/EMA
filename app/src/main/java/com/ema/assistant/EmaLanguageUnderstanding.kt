package com.ema.assistant

import java.util.Locale

/** Speech-act assessment before parsing/execution. Payload suffixes are never rewritten. */
object EmaLanguageUnderstanding {
    enum class Act { REQUEST, QUESTION, NEGATED, CONSTRAINED, EMPTY }
    data class Assessment(
        val original: String,
        val canonical: String,
        val act: Act,
        val explanation: String,
        val rules: List<String> = emptyList()
    )

    private val verbs = "(?:open|launch|start|search|find|play|watch|send|message|text|call|turn|switch|enable|disable|set|read|show|take|copy|delete|remove|clear|save|write|draft|compose|count|calculate|convert|summarize|stop|pause|resume|navigate|go|tap|select|choose|pick|scroll|on|off)"
    private val indirect = Regex("(?i)^(?:can|could|would|will) you(?: please)?\\s+(?=$verbs\\b)")
    private val desire = Regex("(?i)^(?:i(?:'d| would) like you to|i want you to|i need you to|please help me(?: to)?|help me to)\\s+(?=$verbs\\b)")
    private val negative = Regex("(?i)^(?:please\\s+)?(?:do not|don't|dont|never|no need to|you must not|you shouldn't|you should not)\\s+")
    private val negativeClause = Regex("(?i)(?:,|\\bbut\\b|\\band\\b|\\bthen\\b)\\s*(?:please\\s+)?(?:do not|don't|dont|never)\\s+")
    private val negativeDesire = Regex("(?i)^(?:i\\s+(?:do not|don't|dont|never)\\s+(?:want|need|wish)\\s+(?:you\\s+)?to|i(?:'d| would)\\s+rather\\s+you\\s+(?:did not|didn't|not)|(?:avoid|refrain from))\\s+")
    private val uncertainAction = Regex("(?i)^(?:(?:what if|suppose|imagine|let's say|if|unless|when|after|before)\\b|(?:maybe|perhaps|possibly)\\s+(?:you\\s+)?(?=$verbs\\b))")
    private val trailingCondition = Regex("(?i)\\b(?:if|unless|provided that|only when)\\b")
    private val question = Regex("(?i)^(?:how (?:do|can|would|should) (?:i|you)|what (?:can (?:i|you)|does .+ mean|is .+ (?:for|used for))|why (?:do|does|did|can't|can not)|are you able to|is it possible to)\\b")
    private val capability = Regex("(?i)^(?:can|could) you (?:send messages|make calls|play music|search apps|read (?:the )?screen|work offline|control apps|understand languages)[?!.]*$")

    /** Boundary of content explicitly introduced by the owner. */
    private fun contentStart(text: String): Int {
        val candidates = listOfNotNull(
            Regex("(?i)\\b(?:saying|that says|with the message|with the text|that|titled|called)\\s+").find(text)?.range?.first,
            Regex("(?i)\\b(?:search|look|find)\\b.*?\\bfor\\s+").find(text)?.range?.last,
            Regex("(?i)^(?:proofread|rewrite|draft|compose|take a note|note to self)[^:]*:").find(text)?.range?.last,
            Regex("(?i)^(?:meaning of|define)\\s+[\\p{L}\\p{M}'’\\-]+\\s+in (?:the )?sentence\\s*:").find(text)?.range?.last,
            text.indexOf('"').takeIf { it >= 0 }
        )
        return candidates.minOrNull() ?: text.length
    }

    fun assess(raw: String): Assessment {
        if (raw.isBlank()) return Assessment(raw, "", Act.EMPTY, "No request was provided.")
        if (raw.length > 16_000) return Assessment(raw, raw, Act.CONSTRAINED, "That request is too long to assess safely. Split it into smaller requests.")
        var text = raw.trim()
        val rules = mutableListOf<String>()
        val polite = Regex("(?i)^(?:(?:hey|hi|okay|ok) ema[,!]?\\s+)?(?:please\\s+)?")
        val stripped = text.replaceFirst(polite, "")
        if (stripped != text) { text = stripped; rules += "politeness-prefix" }
        if (Regex("(?i)^do not disturb (on|off)$").matches(text))
            return Assessment(raw, "turn ${text.substringAfterLast(' ')} dnd", Act.REQUEST, "Explicit DND state request.", rules + "dnd-name")
        // Normalize punctuation only for detecting intent; never alter the owner's payload.
        val intentText = text.replace('’', '\'')
        if (negative.containsMatchIn(intentText) || negativeDesire.containsMatchIn(intentText)) return Assessment(raw, text, Act.NEGATED,
            "Understood. I won't execute that request.", rules + "negative-instruction")
        if (Regex("(?i)^(?:can|could|would|will) you(?: please)? (?:not|never)\\s+").containsMatchIn(intentText))
            return Assessment(raw, text, Act.NEGATED, "Understood. I won't execute that request.", rules + "negative-indirect-request")
        val commandHead = intentText.substring(0, contentStart(intentText))
        if (negativeClause.containsMatchIn(commandHead)) return Assessment(raw, text, Act.CONSTRAINED,
            "This request includes a negative constraint. Please state just the actions you want performed; I haven't executed any part of it.", rules + "negative-constraint")
        if ((uncertainAction.containsMatchIn(commandHead) && Regex("(?i)\\b$verbs\\b").containsMatchIn(commandHead)) ||
            (Regex("(?i)^(?:$verbs)\\b").containsMatchIn(commandHead) && trailingCondition.containsMatchIn(commandHead))) {
            return Assessment(raw, text, Act.CONSTRAINED,
                "That sounds conditional or hypothetical. Please state an unconditional action if you want me to act; I haven't executed anything.", rules + "conditional-or-hypothetical")
        }
        if (question.containsMatchIn(text) || capability.matches(text)) return Assessment(raw, text, Act.QUESTION,
            "This asks for information or instructions, not permission to execute.", rules + "information-question")
        for (pattern in listOf(indirect, desire)) {
            val transformed = text.replaceFirst(pattern, "")
            if (transformed != text) { text = transformed; rules += "indirect-request"; break }
        }
        if (negative.containsMatchIn(text)) return Assessment(raw, text, Act.NEGATED, "Understood. I won't execute that request.", rules + "negative-instruction")
        // Gerund conversion is restricted to the action immediately after this wrapper.
        Regex("(?i)^would you mind (opening|playing|searching|sending|calling|reading|showing|turning|setting|selecting)\\s+(.+)$")
            .matchEntire(text)?.let { m ->
                val verb = mapOf("opening" to "open", "playing" to "play", "searching" to "search", "sending" to "send",
                    "calling" to "call", "reading" to "read", "showing" to "show", "turning" to "turn", "setting" to "set", "selecting" to "select")[m.groupValues[1].lowercase(Locale.ROOT)]!!
                text = "$verb ${m.groupValues[2]}"; rules += "gerund-request"
            }
        // Check again after removing an indirect wrapper or converting a gerund.
        // Otherwise "could you open X if ..." bypasses the initial verb-at-start test.
        val rewrittenHead = text.replace('’', '\'').let { it.substring(0, contentStart(it)) }
        if (Regex("(?i)^$verbs\\b").containsMatchIn(rewrittenHead) && trailingCondition.containsMatchIn(rewrittenHead)) {
            return Assessment(raw, text, Act.CONSTRAINED,
                "That sounds conditional or hypothetical. Please state an unconditional action if you want me to act; I haven't executed anything.", rules + "conditional-or-hypothetical")
        }
        val setting = Regex("(?i)^(?:please\\s+)?(on|off) (?:the |my )?(flashlight|torch|bluetooth|wi-fi|wifi)$").matchEntire(text)
        if (setting != null) { text = "turn ${setting.groupValues[1].lowercase(Locale.ROOT)} ${setting.groupValues[2]}"; rules += "state-before-object" }
        // Exact short phrases only. This is NOT general multilingual translation.
        val translated = controlledPhrases[text.lowercase(Locale.ROOT).trimEnd('.', '!', '?')]
        if (translated != null) { text = translated; rules += "controlled-device-phrase" }
        return Assessment(raw, text, Act.REQUEST, "Execute only after role grounding and native safety checks.", rules)
    }

    val controlledPhrases = mapOf(
        "allume la lampe torche" to "turn on flashlight", "éteins la lampe torche" to "turn off flashlight",
        "enciende la linterna" to "turn on flashlight", "apaga la linterna" to "turn off flashlight",
        "sube el volumen" to "volume up", "baja el volumen" to "volume down",
        "augmente le volume" to "volume up", "baisse le volume" to "volume down"
    )
}
