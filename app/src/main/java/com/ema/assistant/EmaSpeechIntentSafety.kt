package com.ema.assistant

/** Rescoring may improve names, never reverse a heard instruction's safety polarity. */
object EmaSpeechIntentSafety {
    fun preservesIntent(acousticFirst: String, candidate: String): Boolean {
        val a = EmaLanguageUnderstanding.assess(acousticFirst)
        val b = EmaLanguageUnderstanding.assess(candidate)
        val protected = setOf(EmaLanguageUnderstanding.Act.NEGATED, EmaLanguageUnderstanding.Act.CONSTRAINED)
        if ((a.act in protected) != (b.act in protected)) return false
        val original = EmaDeviceKnowledge.parse(a.canonical)
        val replacement = EmaDeviceKnowledge.parse(b.canonical)
        val mutations = setOf(EmaDeviceKnowledge.Operation.ENABLE, EmaDeviceKnowledge.Operation.DISABLE)
        if (original?.operation in mutations || replacement?.operation in mutations) {
            return original?.feature?.id == replacement?.feature?.id && original?.operation == replacement?.operation
        }
        // The older numeric/radio grammar also understands switch X off / percentages.
        val old = EmaSystemCommandVocabulary.parseDeviceSetting(a.canonical)
        val next = EmaSystemCommandVocabulary.parseDeviceSetting(b.canonical)
        if (old != null || next != null) return old == next
        return true
    }

    /** Extra endpoint patience for requests with an unfilled target or message-content slot. */
    fun needsContinuation(text: String): Boolean {
        val t = text.lowercase().trim().trimEnd('.', ',', '?', '!')
        if (EmaDeviceKnowledge.parse(t) != null) return false
        if (Regex("^(?:turn|switch) (?:on|off)$|^(?:enable|disable|activate|deactivate|connect to|pair with|search settings for)$").matches(t)) return true
        return Regex("^(?:message|text|tell|send a message to) .{1,100}$").matches(t) &&
            !Regex("\\b(?:saying|that|with the (?:text|message))\\s+\\S").containsMatchIn(t) &&
            !t.contains('"')
    }
}
