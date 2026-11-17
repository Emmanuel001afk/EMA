package com.ema.assistant

/** A fuzzy app match must not silently discard a possible second instruction. */
object EmaAppRequestReview {
    fun unresolvedTail(requestedApp: String, installedLabel: String): String? {
        val request = requestedApp.trim().replace(Regex("\\s+"), " ")
        val label = installedLabel.trim().replace(Regex("\\s+"), " ")
        if (label.isBlank() || !request.startsWith("$label ", ignoreCase = true)) return null
        val tail = request.substring(label.length).trim()
        return tail.takeUnless { it.lowercase() in setOf("app", "application", "please", "app please", "application please") }
    }
}
