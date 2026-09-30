package com.ema.assistant

/**
 * Generic post-action verification helpers.
 *
 * These helpers do not know about WhatsApp, Spotify, or any provider. They
 * inspect only the observable Android UI state so the executor can distinguish
 * "the app opened" from "the requested operation actually happened".
 */
object EmaExecutionVerifier {

    fun queryVisible(ui: String, query: String): Boolean {
        val normalizedUi = ui.lowercase().replace(Regex("\\s+"), " ")
        val words = query.lowercase()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length > 1 }
        return words.isEmpty() || words.all { normalizedUi.contains(it) }
    }

    fun recipientVisible(screenText: String, recipient: String): Boolean {
        val normalized = screenText.lowercase()
        val words = recipient.lowercase()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return words.isNotEmpty() && words.all { normalized.contains(it) }
    }

    fun inputCleared(text: String): Boolean = text.trim().isBlank()

    fun mediaPlaying(ui: String): Boolean {
        val normalized = ui.lowercase()
        return normalized.contains("pause") ||
            normalized.contains("playing") ||
            normalized.contains("now playing")
    }
}
