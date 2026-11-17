package com.ema.assistant

/** Observe -> choose one safe action -> re-observe. No app/provider assumptions. */
object EmaSearchPolicy {
    sealed class Decision {
        data class Type(val index: Int) : Decision()
        data class Reveal(val index: Int) : Decision()
        object Wait : Decision()
    }

    private val searchWords = Regex("\\b(search|find|query)\\b", RegexOption.IGNORE_CASE)
    private val unsafe = Regex("\\b(voice|microphone|camera|scan|clear|delete|history|settings)\\b", RegexOption.IGNORE_CASE)

    fun decide(screen: EmaScreenSnapshot): Decision {
        val visible = screen.nodes.filter { it.visible && it.enabled }
        val fields = visible.filter { it.editable }
        val identified = fields.filter { searchWords.containsMatchIn("${it.label} ${it.viewIdLeaf.replace('_', ' ')}") }
        // Never type into the first arbitrary editable: it could be a chat or login field.
        val chosen = identified.singleOrNull() ?: fields.singleOrNull()?.takeIf {
            it.focused && visible.any { n -> n.label.trim().equals("search", true) }
        }
        if (chosen != null) return Decision.Type(chosen.index)
        val entry = visible.filter { !it.editable && it.actionable &&
            !unsafe.containsMatchIn("${it.label} ${it.viewIdLeaf.replace('_', ' ')}") &&
            (it.text.trim().equals("search", true) || it.description.trim().let { d ->
                d.equals("search", true) || d.equals("search tab", true) || d.equals("open search", true)
            } || it.viewIdLeaf.lowercase() in setOf("search", "search_button", "search_tab", "search_bar", "search_src_text"))
        }.distinctBy { it.clickTargetIndex }.firstOrNull()
        return entry?.let { Decision.Reveal(it.clickTargetIndex) } ?: Decision.Wait
    }

    /** Submit is different from navigating to Search. Never click a Search tab after typing. */
    fun submitIndex(screen: EmaScreenSnapshot): Int? = screen.nodes.firstOrNull {
        it.visible && it.enabled && it.actionable && !it.editable &&
            (it.text.trim().lowercase() in setOf("go", "submit") ||
                it.description.trim().lowercase() in setOf("submit search", "search now", "execute search") ||
                it.viewIdLeaf.lowercase() in setOf("search_go_btn", "submit_search", "search_submit"))
    }?.clickTargetIndex

    /** In-app failures should identify an observed blocker, not a guessed inability. */
    fun blocker(screen: EmaScreenSnapshot): String? {
        val labels = screen.nodes.filter { it.visible && !it.editable }.joinToString(" ") { it.label }.lowercase()
        return when {
            Regex("\\b(log in|sign in|create account)\\b").containsMatchIn(labels) &&
                Regex("\\b(password|email|username)\\b").containsMatchIn(labels) -> "The app is showing a sign-in screen. Sign in yourself, then ask me again."
            Regex("\\b(no internet|you.re offline|check your connection|network error)\\b").containsMatchIn(labels) -> "The app reports a network connection problem."
            Regex("\\b(premium required|requires premium|subscribe to play)\\b").containsMatchIn(labels) -> "The app says playback requires a subscription. I won't purchase one."
            else -> null
        }
    }
}
