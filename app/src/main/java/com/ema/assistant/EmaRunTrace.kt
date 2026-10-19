package com.ema.assistant

/**
 * What EMA did with the last requests, kept on the phone.
 *
 * The point is that the next fix is based on facts. When a task fails, the
 * owner can look at (or copy) exactly what EMA heard, which layer took it,
 * which steps ran, what each step reported, how long it took, what was in the
 * foreground and whether screen control was on.
 *
 * Privacy rules, enforced here and not by the caller:
 *  - the recognised text and every message are cut to [MAX_TEXT] characters,
 *  - a request body is never stored beyond that cut, so a message body does
 *    not survive in the trace,
 *  - nothing that looks like a secret is stored at all.
 *
 * Pure Kotlin, so the redaction and the explanation are unit tested.
 */
data class EmaRunTrace(
    val at: Long,
    /** The text EMA understood, cut to [MAX_TEXT] characters. */
    val text: String,
    /** The other guesses the recogniser offered, cut as well. */
    val alternatives: List<String>,
    /** Which layer handled the request. */
    val layer: String,
    val steps: List<Step>,
    /** Whether EMA's screen control was on for this request. */
    val screenControl: Boolean,
    /** Foreground package samples, newest last. */
    val foreground: List<String>,
    val durationMs: Long,
    /** The reply EMA gave, cut to [MAX_TEXT] characters. */
    val result: String
) {

    data class Step(
        val description: String,
        /** The outcome status, e.g. SUCCESS, UNVERIFIED, FAILED. */
        val status: String,
        val message: String,
        val durationMs: Long
    )

    /** The first step that did not work, for "why did that fail?". */
    fun failingStep(): Step? = steps.firstOrNull { it.status in FAILING }

    /**
     * The spoken answer to "EMA, why did that fail?": the step that failed and
     * what it said, in one sentence. Null when the last request did not fail.
     */
    fun explainFailure(): String? {
        val step = failingStep() ?: return null
        val message = step.message.trim()
        return if (message.isBlank()) {
            "The last request failed at the step \"${step.description}\"."
        } else {
            "The last request failed at the step \"${step.description}\": $message"
        }
    }

    /**
     * The whole trace as plain text, ready for the clipboard.
     *
     * The format is one line per fact so it stays readable when it is pasted
     * into a bug report, and it can be read back by [parse].
     */
    fun format(): String {
        val out = StringBuilder()
        out.append("=== EMA run ").append(at).append('\n')
        out.append("text: ").append(text).append('\n')
        if (alternatives.isNotEmpty()) out.append("guesses: ").append(alternatives.joinToString(" | ")).append('\n')
        out.append("layer: ").append(layer).append('\n')
        out.append("duration: ").append(durationMs).append("ms\n")
        out.append("screen control: ").append(if (screenControl) "on" else "off").append('\n')
        if (foreground.isNotEmpty()) out.append("foreground: ").append(foreground.joinToString(" | ")).append('\n')
        steps.forEach { step ->
            out.append("step: ").append(step.description).append(FIELD)
                .append(step.status).append(FIELD)
                .append(step.message.replace(FIELD, " ")).append(FIELD)
                .append(step.durationMs).append('\n')
        }
        out.append("result: ").append(result).append('\n')
        return out.toString()
    }

    companion object {
    /**
     * Read a trace back from the plain text [format] wrote.
     *
     * Returns null when the text is not a trace.
     */
    fun parse(text: String): EmaRunTrace? {
        val lines = text.lines().map { it.trim() }
        if (lines.none { it.startsWith("=== EMA run ") }) return null
        val steps = mutableListOf<Step>()
        var at = 0L
        var body = ""
        var guesses = emptyList<String>()
        var layer = "none"
        var screenControl = false
        var durationMs = 0L
        val foreground = mutableListOf<String>()
        var result = ""
        lines.forEach { line ->
            when {
                line.startsWith("=== EMA run ") -> at = line.removePrefix("=== EMA run ").trim().toLongOrNull() ?: 0L
                line.startsWith("text: ") -> body = line.removePrefix("text: ")
                line.startsWith("guesses: ") -> guesses = line.removePrefix("guesses: ").split(FIELD).filter { it.isNotBlank() }
                line.startsWith("layer: ") -> layer = line.removePrefix("layer: ")
                line.startsWith("duration: ") -> durationMs = line.removePrefix("duration: ").filter { it.isDigit() }.toLongOrNull() ?: 0L
                line.startsWith("screen control: ") -> screenControl = line.removePrefix("screen control: ").trim() == "on"
                line.startsWith("foreground: ") -> foreground += line.removePrefix("foreground: ").split(FIELD).filter { it.isNotBlank() }
                line.startsWith("step: ") -> {
                    val parts = line.removePrefix("step: ").split(FIELD)
                    if (parts.size >= 3) {
                        steps += Step(
                            description = parts[0],
                            status = parts[1],
                            message = parts[2],
                            durationMs = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                        )
                    }
                }
                line.startsWith("result: ") -> result = line.removePrefix("result: ")
            }
        }
        return EmaRunTrace(
            at = at,
            text = body,
            alternatives = guesses,
            layer = layer,
            steps = steps,
            screenControl = screenControl,
            foreground = foreground,
            durationMs = durationMs,
            result = result
        )
    }


        /** How many runs are kept. */
        const val MAX = 20

        /** How much of a request is kept. */
        const val MAX_TEXT = 40

        /** The separator inside one line of the plain text format. */
        const val FIELD = " | "

        /** Outcomes that count as "this did not work". */
        val FAILING = setOf("FAILED", "TIMED_OUT", "NEEDS_PERMISSION", "NEEDS_INPUT", "SKIPPED", "BLOCKED", "CANCELLED")

        /**
         * Cut a piece of user text to what may be stored.
         *
         * A long message body is never kept whole, and anything that looks
         * like a secret is dropped instead of shortened.
         */
        fun redact(value: String?): String {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return ""
            if (looksSecret(text)) return "[withheld]"
            val single = text.replace(Regex("\\s+"), " ")
            return if (single.length <= MAX_TEXT) single else single.take(MAX_TEXT) + "…"
        }

        /** Passwords, PINs, codes and card details never reach the trace. */
        fun looksSecret(text: String): Boolean {
            val lower = text.lowercase()
            if (Regex("(?i)\\b(?:password|passcode|pin\\b|otp|card\\s*number|cvv|iban|account\\s*number|security\\s*code|my\\s*code\\s+is)\\b")
                    .containsMatchIn(lower)) return true
            // A long run of digits is a card number, a code or an account number.
            return Regex("\\b\\d{6,}\\b").containsMatchIn(lower)
        }
    }
}

/**
 * Collects one run while it happens.
 *
 * The router fills it in as the request travels through the layers, so the
 * trace says which layer actually answered.
 */
class EmaRunTraceBuilder(val at: Long) {
    var text: String = ""
    var alternatives: List<String> = emptyList()
    var layer: String = "none"
    var screenControl: Boolean = false
    var result: String = ""
    var durationMs: Long = 0L
    val steps = mutableListOf<EmaRunTrace.Step>()
    val foreground = mutableListOf<String>()

    fun addStep(description: String, status: String, message: String, durationMs: Long) {
        steps += EmaRunTrace.Step(
            description = description,
            status = status,
            message = EmaRunTrace.redact(message),
            durationMs = durationMs
        )
    }

    fun addForeground(packageName: String?) {
        val name = packageName?.trim().orEmpty()
        if (name.isEmpty()) return
        if (foreground.size >= 8) foreground.removeAt(0)
        foreground += name
    }

    fun build(): EmaRunTrace = EmaRunTrace(
        at = at,
        text = EmaRunTrace.redact(text),
        alternatives = alternatives.map { EmaRunTrace.redact(it) }.filter { it.isNotBlank() }.take(4),
        layer = layer,
        steps = steps.toList(),
        screenControl = screenControl,
        foreground = foreground.toList(),
        durationMs = durationMs,
        result = EmaRunTrace.redact(result)
    )
}
