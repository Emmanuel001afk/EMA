package com.ema.assistant

/** Routes explicit generic web searches, never message bodies or app-targeted searches. */
object EmaWebSearchRouting {
    enum class Provider(val key: String, val label: String) {
        EMA("ema", "EMA search (SearXNG)"), GOOGLE("google", "Google"), BOTH("both", "EMA and Google");
        companion object { fun fromKey(key: String?): Provider = values().firstOrNull { it.key == key } ?: EMA }
    }
    data class Request(val query: String, val override: Provider? = null, val forceSearchEngine: Boolean = false)

    fun parse(raw: String, installed: List<EmaAppResolver.App>): Request? {
        val text = raw.trim()
        if (text.length > 400 || text.any { it.isISOControl() }) return null
        // Sequenced instructions remain in the action planner; never turn their
        // later commands into query text or perform just the first action.
        if (Regex("(?i)\\b(?:and then|then|and)\\s+(?:open|send|message|call|play|tap|select|click|set|turn|read)\\b").containsMatchIn(text)) return null
        val choices = listOf(
            Provider.GOOGLE to Regex("(?i)^(?:search (?:with|on) google(?: for)?|search google(?: for)?|google search(?: for)?|google)\\s+(.+)$"),
            Provider.EMA to Regex("(?i)^(?:search (?:with|on) ema(?: for)?|search ema(?: for)?|search (?:with|on) searxng(?: for)?|search searxng(?: for)?|searxng search)\\s+(.+)$"),
            Provider.BOTH to Regex("(?i)^(?:search (?:with|on) both(?: for)?|search both(?: for)?)\\s+(.+)$")
        )
        for ((provider, pattern) in choices) {
            val match = pattern.matchEntire(text) ?: continue
            return match.groupValues[1].trim().takeIf { it.isNotEmpty() && it.length <= 300 }
                ?.let { Request(it, provider, forceSearchEngine = Regex("(?i)^(?:search (?:with|on) searxng|search searxng|searxng search)\\b").containsMatchIn(text)) }
        }
        val googleSuffix = Regex("(?i)^search\\s+(?:for\\s+)?(.+?)\\s+(?:on|with|using) google$").matchEntire(text)
        if (googleSuffix != null) return Request(googleSuffix.groupValues[1].trim(), Provider.GOOGLE)
        val namedPrefix = Regex("(?i)^search\\s+(.+?)\\s+for\\s+.+$").matchEntire(text)
        if (namedPrefix != null && isApp(namedPrefix.groupValues[1], installed)) return null
        val match = Regex("(?i)^search\\s+(?:(?:the web|online)\\s+for\\s+|(?:for|on)\\s+)?(.+)$").matchEntire(text) ?: return null
        val query = match.groupValues[1].trim()
        if (query.isBlank() || query.length > 300) return null
        val suffix = Regex("(?i)^(.+?)\\s+(?:in|on|using|via|within)\\s+(.+)$").matchEntire(query)
        if (suffix != null && isApp(suffix.groupValues[2], installed)) return null
        return Request(query)
    }

    private fun isApp(phrase: String, installed: List<EmaAppResolver.App>): Boolean =
        EmaAppResolver.ground(phrase.trim(), installed) != null
}
