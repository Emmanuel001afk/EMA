package com.ema.assistant

/**
 * One selectable item in a result list, as observed on screen.
 *
 * Every field comes from the accessibility snapshot. [durationSeconds],
 * [views] and [ageDays] are null when the screen does not show them; callers
 * must treat null as "unknown", never as zero.
 */
data class EmaResultItem(
    /** Snapshot index of the node to click (the row's click target). */
    val clickIndex: Int,
    val title: String,
    /** All readable text of the row, for matching and verification. */
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val durationSeconds: Int? = null,
    val views: Long? = null,
    val ageDays: Double? = null,
    val sponsored: Boolean = false,
    val live: Boolean = false
)

/**
 * Generic result-list understanding over [EmaScreenSnapshot]s.
 *
 * Works for any app that exposes its UI to accessibility: rows are found by
 * grouping readable nodes under their click target, removing app chrome
 * (toolbars, tabs, chips, icon buttons, the search box) by geometry and role,
 * and ordering by on-screen position. No app-specific view ids and no fixed
 * coordinates are used, so the same code serves YouTube, a music app, a
 * shopping app or a private company app.
 */
object EmaResultExtractor {

    private val CHROME_LABELS = setOf(
        "back", "navigate up", "home", "search", "clear", "clear query", "voice search", "search with your voice",
        "menu", "more", "more options", "action menu", "settings", "filter", "filters", "search filters", "sort",
        "cast", "notifications", "account", "profile", "share", "close", "cancel", "done", "ok", "all", "shorts",
        "subscriptions", "library", "you", "explore", "create", "upload", "videos", "channels", "playlists",
        "live", "music", "next", "previous", "play", "pause", "send", "attach", "camera", "microphone", "mic"
    )

    // ------------------------------------------------------- metadata

    private val CLOCK = Regex("""(?<![\d:])(\d{1,2}):([0-5]\d)(?::([0-5]\d))?(?![\d:])(?!\s*(?:am|pm|a\.m|p\.m))""", RegexOption.IGNORE_CASE)
    private val SPOKEN_PART = Regex("""(\d+)\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)\b""", RegexOption.IGNORE_CASE)
    private val VIEWS = Regex("""([\d][\d.,]*)\s*(k|m|b|thousand|million|billion)?\s+views?\b""", RegexOption.IGNORE_CASE)
    private val AGE = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)

    /** "12:34" -> 754, "1:02:03" -> 3723, "1 hour, 2 minutes" -> 3720. Null when no length is shown. */
    fun parseDurationSeconds(text: String): Int? {
        CLOCK.find(text)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val c = m.groupValues[3]
            return if (c.isNotEmpty()) a * 3600 + b * 60 + c.toInt() else a * 60 + b
        }
        var total = 0
        var found = false
        // Only accept spoken durations that read as a length, not "5 minutes ago".
        for (m in SPOKEN_PART.findAll(text)) {
            val after = text.substring(m.range.last + 1).trimStart().lowercase()
            if (after.startsWith("ago")) continue
            found = true
            val n = m.groupValues[1].toInt()
            val unit = m.groupValues[2].lowercase()
            total += when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("m") -> n * 60
                else -> n
            }
        }
        return if (found) total else null
    }

    fun parseViews(text: String): Long? {
        val m = VIEWS.find(text) ?: return null
        val number = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].lowercase()) {
            "k", "thousand" -> 1_000.0
            "m", "million" -> 1_000_000.0
            "b", "billion" -> 1_000_000_000.0
            else -> 1.0
        }
        return (number * mult).toLong()
    }

    fun parseAgeDays(text: String): Double? {
        val m = AGE.find(text) ?: return null
        val n = m.groupValues[1].toDouble()
        return when (m.groupValues[2].lowercase()) {
            "second" -> n / 86400.0
            "minute" -> n / 1440.0
            "hour" -> n / 24.0
            "day" -> n
            "week" -> n * 7
            "month" -> n * 30
            else -> n * 365
        }
    }

    private fun isMetadataOnly(label: String): Boolean {
        val l = label.trim().lowercase()
        if (l.isBlank()) return true
        if (CLOCK.matches(l)) return true
        if (Regex("""^[\d.,]+\s*[kmb]?\s+views?$""").matches(l)) return true
        if (AGE.matches(l) || l.startsWith("streamed ")) return true
        if (l in setOf("live", "sponsored", "ad", "new", "4k", "hd", "cc", "shorts", "verified", "•", "·")) return true
        return Regex("""^(?:\d+\s*(?:hours?|minutes?|seconds?)\s*,?\s*)+$""").matches(l)
    }

    /**
     * A short imperative control label ("Pause video", "Skip ad", "Expand mini
     * player", "Like this video") names a button, not content.
     */
    private fun isControlLabel(label: String): Boolean {
        val words = label.trim().lowercase().split(Regex("\\s+"))
        if (words.isEmpty() || words.size > 4) return false
        return words[0] in CONTROL_VERBS
    }

    private val CONTROL_VERBS = setOf(
        "play", "pause", "replay", "resume", "stop", "mute", "unmute", "like", "dislike", "share", "save", "download",
        "minimize", "minimise", "expand", "collapse", "close", "dismiss", "skip", "seek", "rewind", "forward",
        "subscribe", "unsubscribe", "follow", "unfollow", "hide", "remove", "report", "add", "open", "show", "enter", "exit"
    )

    /** "Channel · 1.2M views · 2 days ago": a line made of metadata, not a title. */
    private fun isMetadataLine(label: String): Boolean {
        val parts = label.split(Regex("\\s*[·•|]\\s*")).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size < 2) return isMetadataOnly(label)
        return parts.any { isMetadataOnly(it) }
    }

    private fun isSponsored(labels: List<String>): Boolean = labels.any { label ->
        val l = label.lowercase()
        l == "ad" || l == "sponsored" || l.startsWith("sponsored") || l.contains(" · ad") ||
            l.startsWith("ad ·") || l.startsWith("ad •") || l.contains("• ad ") || l.endsWith(" sponsored")
    }

    // ------------------------------------------------------ extraction

    private data class Group(val target: EmaNodeSnapshot, val members: MutableList<EmaNodeSnapshot>)

    /**
     * Extract result rows from a screen, ordered as a person would read them.
     *
     * @param query the search text, used to drop the search box echoing it.
     */
    fun extract(screen: EmaScreenSnapshot, query: String? = null): List<EmaResultItem> {
        val nodes = screen.nodes.filter { it.visible }
        if (nodes.isEmpty()) return emptyList()
        val screenLeft = nodes.minOf { it.left }
        val screenTop = nodes.minOf { it.top }
        val screenRight = nodes.maxOf { it.right }
        val screenBottom = nodes.maxOf { it.bottom }
        val width = (screenRight - screenLeft).coerceAtLeast(1)
        val height = (screenBottom - screenTop).coerceAtLeast(1)

        val groups = linkedMapOf<Int, Group>()
        for (node in nodes) {
            if (!node.actionable || node.label.isBlank()) continue
            val target = screen.nodeAt(node.clickTargetIndex) ?: continue
            groups.getOrPut(target.index) { Group(target, mutableListOf()) }.members += node
        }

        val normalizedQuery = query?.trim()?.lowercase().orEmpty()
        val kept = groups.values.filter { g ->
            val t = g.target
            if (!t.visible || t.editable || g.members.any { it.editable }) return@filter false
            val wFrac = t.width.toDouble() / width
            val hFrac = t.height.toDouble() / height
            if (hFrac > 0.85) return@filter false                          // the whole list / page
            if (hFrac < 0.035) return@filter false                         // chips, tabs, single-line menu items
            if (wFrac < 0.25 || (wFrac < 0.4 && hFrac < 0.1)) return@filter false // icons, avatars, overflow buttons
            val labels = g.members.map { it.label.trim() }.filter { it.isNotBlank() }
            // Icon buttons ("Pause video") describe themselves only through a
            // content description; visible text is content even if it starts
            // with a verb ("Play with me").
            val meaningful = g.members.filterNot { m -> m.text.isBlank() && isControlLabel(m.description) }
                .map { it.label.trim() }.filter { it.isNotBlank() }
                .filterNot { it.lowercase() in CHROME_LABELS || isMetadataOnly(it) }
            if (meaningful.isEmpty()) return@filter false
            val joined = labels.joinToString(" ").lowercase()
            // The search box echoing the query, or a "Search for ..." suggestion row.
            if (normalizedQuery.isNotBlank() && meaningful.all { it.lowercase() == normalizedQuery }) return@filter false
            if (joined.startsWith("search ") && t.top - screenTop < height * 0.15) return@filter false
            // Toolbars: short items hugging the top edge.
            if (t.top - screenTop < height * 0.08 && hFrac < 0.1) return@filter false
            true
        }

        // Nested click targets: merge an inner target's text into its enclosing row.
        val merged = mutableListOf<Group>()
        for (g in kept.sortedByDescending { it.target.width.toLong() * it.target.height }) {
            val outer = merged.firstOrNull { contains(it.target, g.target) }
            if (outer != null) outer.members += g.members else merged += Group(g.target, g.members.toMutableList())
        }

        val items = merged.map { g ->
            val labels = g.members.sortedBy { it.index }.map { it.label.trim() }.filter { it.isNotBlank() }.distinct()
            val text = labels.joinToString(" · ")
            val textNodes = g.members.filter { it.text.isNotBlank() }.map { it.text.trim() }
            val title = chooseTitle(textNodes, labels)
            val durationLabel = labels.firstOrNull { CLOCK.matches(it.trim()) } // a bare "12:34" overlay is the most reliable
            EmaResultItem(
                clickIndex = g.target.index,
                title = title,
                text = text,
                left = g.target.left, top = g.target.top, right = g.target.right, bottom = g.target.bottom,
                durationSeconds = durationLabel?.let { parseDurationSeconds(it) } ?: labels.firstNotNullOfOrNull { lengthFrom(it) },
                views = labels.firstNotNullOfOrNull { parseViews(it) },
                ageDays = labels.firstNotNullOfOrNull { parseAgeDays(it) },
                sponsored = isSponsored(labels),
                live = labels.any { it.equals("live", true) || it.contains("watching now", true) || it.lowercase().startsWith("live ") }
            )
        }
        return items
            .sortedWith(compareBy<EmaResultItem> { it.top }.thenBy { it.left })
            .distinctBy { it.title.lowercase() + "@" + it.top / 8 }
    }

    /** Duration from a row label, ignoring clock times that are part of a title ("Top 10 at 2:30"). */
    private fun lengthFrom(label: String): Int? {
        val l = label.lowercase()
        // Descriptions read by screen readers: "..., 12 minutes, 3 seconds, ..."
        if (SPOKEN_PART.containsMatchIn(l)) return parseDurationSeconds(label)
        // A trailing or separated clock value in a combined label: "Title · 12:34"
        val m = Regex("""(?:^|[·•|\-,]\s*)(\d{1,2}:[0-5]\d(?::[0-5]\d)?)\s*(?:$|[·•|\-,])""").find(label) ?: return null
        return parseDurationSeconds(m.groupValues[1])
    }

    private fun chooseTitle(textNodes: List<String>, labels: List<String>): String {
        val candidates = textNodes.filterNot { isMetadataOnly(it) || it.lowercase() in CHROME_LABELS }
        // Prefer lines that are not "channel · views · age" metadata; among
        // those the longest is the title (channel names are short).
        val titled = candidates.filterNot { isMetadataLine(it) }
        (titled.ifEmpty { candidates }).maxByOrNull { it.length }?.let { return it.take(200) }
        val first = labels.filterNot { isMetadataOnly(it) || it.lowercase() in CHROME_LABELS }.maxByOrNull { it.length } ?: labels.firstOrNull().orEmpty()
        // Descriptions like "Title - 12 minutes - Channel - 1M views - play video".
        return first.split(Regex("""\s+[-–·•|]\s+|,\s+(?=\d)""")).first().trim().take(200)
    }

    private fun contains(a: EmaNodeSnapshot, b: EmaNodeSnapshot): Boolean =
        a.index != b.index && a.left <= b.left && a.top <= b.top && a.right >= b.right && a.bottom >= b.bottom

    // --------------------------------------------------------- choice

    sealed class Choice {
        /** [position] is 1-based among the non-sponsored results considered. */
        data class Chosen(val item: EmaResultItem, val position: Int, val note: String? = null) : Choice()
        /** The selection is valid but needs more results than are visible (scroll, then retry). */
        data class NeedMore(val reason: String) : Choice()
        /** Cannot be decided from what the screen shows. Never guessed. */
        data class Refused(val reason: String) : Choice()
    }

    fun choose(all: List<EmaResultItem>, selection: EmaSelection, alreadyChosen: List<String> = emptyList()): Choice {
        val items = all.filterNot { it.sponsored }
        if (items.isEmpty()) {
            return if (all.isNotEmpty()) Choice.Refused("The only results I can see are ads, so I did not open any of them.")
            else Choice.NeedMore("I can't see any results on the screen yet.")
        }
        val skippedAds = all.size - items.size
        val adNote = if (skippedAds > 0) "I skipped ${if (skippedAds == 1) "an ad" else "$skippedAds ads"}." else null
        fun chosen(item: EmaResultItem, note: String? = null) =
            Choice.Chosen(item, items.indexOf(item) + 1, listOfNotNull(note, adNote).joinToString(" ").ifBlank { null })

        return when (selection) {
            is EmaSelection.Ordinal ->
                if (selection.position <= items.size) chosen(items[selection.position - 1])
                else Choice.NeedMore("Only ${items.size} result${if (items.size == 1) " is" else "s are"} visible.")
            EmaSelection.Last -> chosen(items.last(), "That is the last result currently loaded on screen.")
            EmaSelection.Longest, EmaSelection.Shortest -> {
                val known = items.filter { it.durationSeconds != null }
                if (known.size < 2) {
                    Choice.Refused("I can't see the length of ${if (known.isEmpty()) "these results" else "enough of these results"}, " +
                        "so I can't tell which one is ${if (selection == EmaSelection.Longest) "longest" else "shortest"}. I won't guess.")
                } else {
                    val pick = if (selection == EmaSelection.Longest) known.maxByOrNull { it.durationSeconds!! }!!
                    else known.minByOrNull { it.durationSeconds!! }!!
                    val missing = items.size - known.size
                    val scope = "Compared ${known.size} visible results by their length (${formatDuration(pick.durationSeconds!!)})." +
                        if (missing > 0) " $missing visible result${if (missing == 1) "" else "s"} showed no length and ${if (missing == 1) "was" else "were"} not compared." else ""
                    chosen(pick, scope)
                }
            }
            EmaSelection.Newest -> {
                val known = items.filter { it.ageDays != null }
                if (known.size < 2) Choice.Refused("I can't see upload dates for these results, so I can't tell which is newest. I won't guess.")
                else chosen(known.minByOrNull { it.ageDays!! }!!, "Compared ${known.size} visible results by upload age.")
            }
            EmaSelection.MostViewed -> {
                val known = items.filter { it.views != null }
                if (known.size < 2) Choice.Refused("I can't see view counts for these results, so I can't tell which is most viewed. I won't guess.")
                else chosen(known.maxByOrNull { it.views!! }!!, "Compared ${known.size} visible results by view count.")
            }
            EmaSelection.Another -> {
                val seen = alreadyChosen.map { normalizeTitle(it) }.toSet()
                val next = items.firstOrNull { normalizeTitle(it.title) !in seen }
                if (next != null) chosen(next) else Choice.NeedMore("I've already opened every result that is visible.")
            }
            is EmaSelection.Titled -> {
                val wanted = normalizeTitle(selection.text).split(' ').filter { it.length > 1 }
                val scored = items.map { item ->
                    val have = normalizeTitle(item.title + " " + item.text)
                    item to wanted.count { have.contains(it) }
                }.filter { it.second > 0 }
                val best = scored.maxByOrNull { it.second }
                if (best != null && best.second * 10 >= wanted.size * 6) chosen(best.first)
                else Choice.NeedMore("I can't see a result called \"${selection.text}\".")
            }
        }
    }

    fun normalizeTitle(value: String): String =
        value.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /**
     * Does the title the media session reports correspond to the chosen row?
     * Used to verify that what is playing is what was selected.
     */
    fun titlesMatch(chosenTitle: String, playingTitle: String): Boolean {
        val a = normalizeTitle(chosenTitle).split(' ').filter { it.length > 2 }.toSet()
        val b = normalizeTitle(playingTitle).split(' ').filter { it.length > 2 }.toSet()
        if (a.isEmpty() || b.isEmpty()) return false
        val overlap = a.intersect(b).size
        return overlap * 10 >= minOf(a.size, b.size) * 6
    }
}
