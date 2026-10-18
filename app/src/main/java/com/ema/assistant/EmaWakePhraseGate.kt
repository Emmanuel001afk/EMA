package com.ema.assistant

/** Visible-session wake gating, not an always-on hotword detector. */
class EmaWakePhraseGate {
    private var armedUntil = 0L
    private val prefix = Regex("""(?i)^hey[\s,]+ema\b[\s,:.!?]*(.*)$""")

    /** Null means ignore ambient speech; empty means wake accepted, awaiting one request. */
    fun accept(speech: String, enabled: Boolean, nowMs: Long): String? {
        if (!enabled) {
            reset()
            return speech
        }
        val wake = prefix.matchEntire(speech.trim())
        if (wake != null) {
            val request = wake.groupValues[1].trim()
            armedUntil = if (request.isEmpty()) nowMs + 10_000L else 0L
            return request
        }
        val armed = armedUntil > nowMs
        reset()
        return if (armed) speech.trim().takeIf { it.isNotEmpty() } else null
    }

    fun reset() { armedUntil = 0L }
}
