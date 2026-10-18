package com.ema.assistant

/**
 * Log-safe descriptions of user text. Commands can contain message bodies,
 * names and other private content, so logs record shape, never content.
 */
object EmaPrivacy {
    fun describe(text: String?): String {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return "empty"
        val words = t.split(Regex("\\s+")).size
        return "$words words, ${t.length} chars"
    }
}
