package com.ema.assistant

/** Instruction questions explain a workflow; they never authorize its execution. */
object EmaInstructionKnowledge {
    /** Only claim an offline workflow explanation for specific assistant tasks.
     * A keyword alone (e.g. "send" in "how do I send a parcel") is not enough.
     */
    fun explainIfSupported(question: String): String? {
        val q = question.trim().lowercase().trimEnd('?', '.', '!').trim()
            .replace(Regex("\\s+(?:using|with|in) (?:ema|this assistant)$"), "")
        val knownWorkflow = Regex(
            "^(?:how (?:do|can|should) i|how (?:do|can) you|can you|could you) " +
                "(?:send (?:a |an |the )?(?:message|text|email)|make (?:a )?call|" +
                "play music|read (?:the )?screen|summari[sz]e (?:the )?screen|" +
                "(?:turn|switch) (?:on|off) (?:the )?(?:flashlight|torch|bluetooth|wi-?fi)|" +
                "work offline|control apps|understand languages|send messages|make calls|search apps)$"
        ).matches(q)
        return if (knownWorkflow) explain(question) else null
    }

    fun explain(question: String): String {
        val q = question.lowercase()
        val guidance = when {
            Regex("\\b(message|messages|text|whatsapp|send)\\b").containsMatchIn(q) ->
                "Name the recipient, app and exact body, for example: message Daniel on WhatsApp saying I am coming home. I must verify the recipient and draft before Send, then look for a new sent bubble. A draft or attempted send is not proof of delivery."
            Regex("\\b(play|music|song|video|youtube|spotify)\\b").containsMatchIn(q) ->
                "Name the content and app, for example: search YouTube for operating systems, then play the first video. A title identifies content; first or second identifies an observed result position. I must observe the result and check playback rather than just open the app."
            Regex("\\b(flashlight|torch|brightness|volume|bluetooth|wifi|wi-fi)\\b").containsMatchIn(q) ->
                "State the setting and desired value, for example: turn on flashlight, set volume to 40 percent, or increase brightness. Android permissions and hardware determine what can be changed; I cannot grant myself access."
            Regex("\\b(screen|page|document|read|summarize)\\b").containsMatchIn(q) ->
                "Enable EMA Accessibility access, then ask summarize screen or ask screen: followed by your question. I use accessible text and an offline OCR fallback when text is absent. Protected screens may be unreadable, and screen content is not an instruction to execute."
            Regex("\\b(language|languages|english|understand)\\b").containsMatchIn(q) ->
                "My native language layer recognizes supported English requests, roles, list references and explicit clarifications. It also maps a small set of exact French and Spanish device phrases; it is not a general translator. Offline speech recognition uses the bundled English model."
            Regex("\\b(offline|internet|data|network)\\b").containsMatchIn(q) ->
                "Supported speech recognition and native reasoning can run locally in offline-only mode. Opening an app is local, but that app may still need internet for streaming, messages or web results."
            else -> "Name the intended action and target. I can explain known app purposes, supported device settings, searches, result selections and messaging workflows. I don't have a reliable native instruction for every topic."
        }
        return "$guidance I have not executed an action in response to this question."
    }
}
