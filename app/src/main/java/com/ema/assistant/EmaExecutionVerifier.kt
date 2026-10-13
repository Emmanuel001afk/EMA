package com.ema.assistant

/**
 * Generic post-action verification helpers.
 *
 * These helpers do not know about WhatsApp, Spotify, or any provider. They
 * inspect only observable Android state so the executor can distinguish "the app
 * opened" from "the requested operation actually happened".
 *
 * Pure and unit testable: no Android types.
 */
object EmaExecutionVerifier {

    fun queryVisible(ui: String, query: String): Boolean {
        val normalizedUi = normalize(ui)
        val words = query.lowercase()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length > 1 }
        return words.isEmpty() || words.all { normalizedUi.contains(it) }
    }

    fun recipientVisible(screenText: String, recipient: String): Boolean {
        val normalized = normalize(screenText)
        val words = recipient.lowercase()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return words.isNotEmpty() && words.all { normalized.contains(it) }
    }

    fun inputCleared(text: String): Boolean = text.trim().isBlank()

    /**
     * Whether playback looks active from the visible UI alone.
     *
     * Deliberately conservative. The previous version treated any occurrence of
     * the word "pause" as proof of playback, which is wrong: a media app shows a
     * Pause control in its transport bar even while stopped.
     *
     * Explicit stop evidence wins over a loose "playing" match, so "Not playing"
     * or a paused transport bar is not reported as playback.
     */
    fun mediaPlaying(ui: String): Boolean {
        val normalized = normalize(ui)
        val positive = normalized.contains("now playing") ||
            normalized.contains("pause playback") ||
            normalized.contains("pause button") ||
            normalized.contains("playing")
        if (!positive) return false

        val stopped = normalized.contains("paused") ||
            normalized.contains("not playing") ||
            normalized.contains("playback stopped") ||
            normalized.contains("stopped")
        // "now playing" is a screen title players keep while paused, so it only
        // overrides stop evidence when nothing says the transport is halted.
        return !stopped || (normalized.contains("now playing") && !normalized.contains("paused"))
    }

    /**
     * Combine the two independent playback signals.
     *
     * Android's audio state is authoritative when available; the visible UI is
     * only a fallback, because video playback and some players keep the audio
     * session inactive while genuinely playing.
     */
    fun playbackLikely(ui: String, audioActive: Boolean): Boolean =
        audioActive || mediaPlaying(ui)

    /**
     * Whether the foreground application is the one EMA intended to act on.
     * Prevents reporting success for an action performed in the wrong app.
     */
    fun foregroundIs(screenPackage: String, expectedPackage: String): Boolean {
        if (expectedPackage.isBlank()) return true
        return screenPackage.trim() == expectedPackage.trim()
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("\\s+"), " ")
}
