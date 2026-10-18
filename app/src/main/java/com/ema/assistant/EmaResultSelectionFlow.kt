package com.ema.assistant

/**
 * Chooses and opens one item of an on-screen result list, then verifies it.
 *
 *   observe -> (return to the list if we are inside a previously opened item)
 *           -> choose (scrolling a bounded number of times if more are needed)
 *           -> tap the re-verified row -> observe again -> verify
 *
 * A tap is never reported as success by itself: for playback, the app's media
 * session must report the chosen title playing; otherwise the screen must have
 * changed away from the list. Metadata choices (longest, newest, ...) refuse
 * when the screen does not show the metadata.
 *
 * Pure: all device access goes through [Port], so the flow is unit tested with
 * recorded screen snapshots and a synchronous scheduler.
 */
class EmaResultSelectionFlow(
    private val port: Port,
    private val packageName: String,
    private val appLabel: String,
    private val selection: EmaSelection,
    private val play: Boolean,
    private val alreadyChosen: List<String>,
    private val query: String?
) {
    data class Media(val title: String, val playing: Boolean)

    interface Port {
        fun foregroundPackage(): String
        fun results(packageName: String, query: String?): List<EmaResultItem>
        fun click(packageName: String, item: EmaResultItem, query: String?): Boolean
        fun scrollForward(packageName: String): Boolean
        fun back(): Boolean
        /** Null when playback cannot be observed (e.g. no notification access). */
        fun media(packageName: String): Media?
        fun schedule(delayMs: Long, block: () -> Unit)
        /** Re-run the original search to rebuild the list; null when unknown. */
        val rerunSearch: ((done: (Boolean) -> Unit) -> Unit)?
    }

    data class Result(val outcome: EmaStepOutcome, val chosenTitle: String?)

    private var done: ((Result) -> Unit)? = null
    private var backs = 0
    private var scrolls = 0
    private var reran = false
    private val accumulated = mutableListOf<EmaResultItem>()

    fun run(callback: (Result) -> Unit) {
        done = callback
        if (port.foregroundPackage() != packageName) {
            val rerun = port.rerunSearch
            if (rerun != null && !reran) {
                reran = true
                rerun { ok -> if (ok) port.schedule(SETTLE_MS) { observe() } else finish(fail("I couldn't get back to the $appLabel results.")) }
            } else {
                finish(fail("$appLabel isn't on screen any more, so I can't see its results. Ask me to search again."))
            }
            return
        }
        observe()
    }

    private fun observe() {
        val items = port.results(packageName, query)
        val lastChosen = alreadyChosen.lastOrNull()
        // We previously opened an item from this list: the list we chose from
        // contains that item. If it is not visible we are inside the item, so
        // go back (bounded) before choosing again.
        if (lastChosen != null && accumulated.isEmpty() &&
            items.none { EmaResultExtractor.normalizeTitle(it.title) == EmaResultExtractor.normalizeTitle(lastChosen) }
        ) {
            if (backs < MAX_BACKS) {
                backs++
                port.back()
                port.schedule(SETTLE_MS) { observe() }
                return
            }
            val rerun = port.rerunSearch
            if (rerun != null && !reran) {
                reran = true
                rerun { ok -> if (ok) port.schedule(SETTLE_MS) { observeFresh() } else finish(fail("I couldn't get back to the results list.")) }
                return
            }
            finish(fail("I couldn't get back to the results list, so I didn't choose anything."))
            return
        }
        choose(items)
    }

    /** After a re-run search the old "already chosen" anchor no longer applies. */
    private fun observeFresh() = choose(port.results(packageName, query))

    private fun choose(visible: List<EmaResultItem>) {
        if (visible.isEmpty() && accumulated.isEmpty()) {
            finish(fail("I can't see a list of results on the screen, so I didn't tap anything."))
            return
        }
        // Merge newly visible rows into everything seen so far (for positions beyond one screen).
        for (item in visible) {
            if (accumulated.none { EmaResultExtractor.normalizeTitle(it.title) == EmaResultExtractor.normalizeTitle(item.title) }) accumulated += item
        }
        when (val choice = EmaResultExtractor.choose(accumulated, selection, alreadyChosen)) {
            is EmaResultExtractor.Choice.Chosen -> tap(choice, visible)
            is EmaResultExtractor.Choice.NeedMore -> {
                val canScroll = selection is EmaSelection.Ordinal || selection == EmaSelection.Another || selection is EmaSelection.Titled
                if (canScroll && scrolls < MAX_SCROLLS && port.scrollForward(packageName)) {
                    scrolls++
                    port.schedule(SCROLL_SETTLE_MS) { choose(port.results(packageName, query)) }
                } else {
                    finish(fail(choice.reason + " I didn't tap anything."))
                }
            }
            is EmaResultExtractor.Choice.Refused -> finish(fail(choice.reason))
        }
    }

    private fun tap(choice: EmaResultExtractor.Choice.Chosen, visible: List<EmaResultItem>) {
        val item = choice.item
        val title = item.title
        val before = visible.map { EmaResultExtractor.normalizeTitle(it.title) }
        if (!port.click(packageName, item, query)) {
            finish(Result(EmaStepOutcome(EmaOutcomeStatus.FAILED,
                "I found \"$title\" but couldn't tap it, possibly because the list moved. Nothing was opened."), null))
            return
        }
        val label = describeChoice(choice)
        verify(title, label, before, choice.note, 0)
    }

    private fun describeChoice(choice: EmaResultExtractor.Choice.Chosen): String = when (selection) {
        is EmaSelection.Ordinal -> "the ${EmaSelection.ordinalWord(selection.position)} result, \"${choice.item.title}\""
        EmaSelection.Last -> "the last result, \"${choice.item.title}\""
        EmaSelection.Longest -> "the longest result, \"${choice.item.title}\""
        EmaSelection.Shortest -> "the shortest result, \"${choice.item.title}\""
        EmaSelection.Newest -> "the newest result, \"${choice.item.title}\""
        EmaSelection.MostViewed -> "the most viewed result, \"${choice.item.title}\""
        else -> "\"${choice.item.title}\""
    }

    private fun verify(title: String, label: String, before: List<String>, note: String?, poll: Int) {
        val media = if (play) port.media(packageName) else null
        if (play && media != null && media.playing && EmaResultExtractor.titlesMatch(title, media.title)) {
            finish(Result(EmaStepOutcome(EmaOutcomeStatus.SUCCESS, join("Playing $label in $appLabel.", note)), title))
            return
        }
        val fg = port.foregroundPackage()
        val after = if (fg == packageName) port.results(packageName, query).map { EmaResultExtractor.normalizeTitle(it.title) } else emptyList()
        val changed = fg != packageName || after.take(3) != before.take(3)
        if (!play && changed) {
            finish(Result(EmaStepOutcome(EmaOutcomeStatus.SUCCESS, join("Opened $label in $appLabel.", note)), title))
            return
        }
        if (poll < VERIFY_POLLS) {
            port.schedule(VERIFY_INTERVAL_MS) { verify(title, label, before, note, poll + 1) }
            return
        }
        val outcome = when {
            !changed -> EmaStepOutcome(EmaOutcomeStatus.FAILED, "I tapped $label, but the screen didn't change, so it probably didn't open.")
            !play -> EmaStepOutcome(EmaOutcomeStatus.SUCCESS, join("Opened $label in $appLabel.", note))
            media == null -> EmaStepOutcome(EmaOutcomeStatus.UNVERIFIED, join("I opened $label, but I couldn't confirm it is playing because EMA can't read media playback. Turning on notification access for EMA lets me check.", note))
            media.playing -> EmaStepOutcome(EmaOutcomeStatus.UNVERIFIED, join("I opened $label. Something is playing in $appLabel, but it reports \"${media.title}\", which may be an ad before the video, so I couldn't confirm it.", note))
            else -> EmaStepOutcome(EmaOutcomeStatus.UNVERIFIED, join("I opened $label, but playback hasn't started yet.", note))
        }
        finish(Result(outcome, title))
    }

    private fun join(main: String, note: String?) = if (note.isNullOrBlank()) main else "$main $note"

    private fun fail(message: String) = Result(EmaStepOutcome(EmaOutcomeStatus.FAILED, message), null)

    private fun finish(result: Result) {
        val cb = done ?: return
        done = null
        cb(result)
    }

    companion object {
        const val MAX_BACKS = 2
        const val MAX_SCROLLS = 3
        const val SETTLE_MS = 900L
        const val SCROLL_SETTLE_MS = 700L
        const val VERIFY_POLLS = 10
        const val VERIFY_INTERVAL_MS = 500L
    }
}
