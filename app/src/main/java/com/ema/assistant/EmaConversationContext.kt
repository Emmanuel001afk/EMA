package com.ema.assistant

/**
 * The active task: the one piece of short-term state a follow-up such as
 * "play the first result", "now find another one" or "choose the longest one
 * instead" may refer to.
 *
 * Deliberately narrow. It records which app holds a result list, what was
 * searched for, and which results were already chosen. It never stores
 * message recipients or bodies, so a later request can never silently reuse
 * them (consequential details always come from the current utterance or an
 * explicit clarification answer).
 */
data class EmaActiveTask(
    val app: String,
    val query: String?,
    val chosenTitles: List<String> = emptyList(),
    val lastSelection: EmaSelection? = null,
    /** The last action on this list was playback, so "choose another" should play too. */
    val lastWasPlay: Boolean = false,
    val updatedAtMs: Long
)

/**
 * Process-wide short-term context (in memory only, never persisted).
 *
 * It is process-wide rather than per router because the voice overlay closes
 * after each wake-word request: "Hey EMA, search YouTube for football" and
 * "Hey EMA, play the third result" arrive in two different sessions.
 * Entries expire after [TTL_MS] so an old list is never acted on by surprise.
 */
object EmaConversationContext {
    const val TTL_MS = 10 * 60_000L

    @Volatile
    private var active: EmaActiveTask? = null

    @Synchronized
    fun activeTask(nowMs: Long = System.currentTimeMillis()): EmaActiveTask? {
        val task = active ?: return null
        if (nowMs - task.updatedAtMs > TTL_MS || nowMs < task.updatedAtMs - 60_000L) {
            active = null
            return null
        }
        return task
    }

    /** A search produced a result list in [app]. Starts a new task. */
    @Synchronized
    fun recordSearch(app: String, query: String?, nowMs: Long = System.currentTimeMillis()) {
        active = EmaActiveTask(app = app, query = query, updatedAtMs = nowMs)
    }

    /** An item was chosen from the active list (or a list in [app]). */
    @Synchronized
    fun recordSelection(
        app: String,
        title: String?,
        selection: EmaSelection,
        play: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val current = activeTask(nowMs)
        val base = if (current != null && current.app.equals(app, ignoreCase = true)) current
        else EmaActiveTask(app = app, query = null, updatedAtMs = nowMs)
        val titles = if (title.isNullOrBlank()) base.chosenTitles else (base.chosenTitles + title).takeLast(20)
        active = base.copy(chosenTitles = titles, lastSelection = selection, lastWasPlay = play, updatedAtMs = nowMs)
    }

    /** The user opened something unrelated: an old list is no longer "the results". */
    @Synchronized
    fun clear() {
        active = null
    }
}
