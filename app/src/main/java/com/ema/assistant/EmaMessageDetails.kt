package com.ema.assistant

/** Explicit boundary supplied in response to a recipient/body clarification. */
object EmaMessageDetails {
    fun parse(raw: String): Pair<String, String>? {
        val comma = raw.indexOf(',')
        if (comma <= 0) return null
        val name = raw.substring(0, comma).trim()
        val body = raw.substring(comma + 1).trim()
        if (name.isBlank() || name.length > 100 || name.split(Regex("\\s+")).size > 8 || body.isBlank()) return null
        return name to body
    }
}
