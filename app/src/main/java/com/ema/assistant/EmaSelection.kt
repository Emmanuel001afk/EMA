package com.ema.assistant

/**
 * Which item of a visible result list the user means.
 *
 * Pure and Android-free. A selection is only ever resolved against result
 * items that were actually observed on screen (see [EmaResultExtractor]);
 * metadata-based selections such as [Longest] refuse rather than guess when
 * the screen does not show the metadata they need.
 */
sealed class EmaSelection {
    /** 1-based position among the visible results. */
    data class Ordinal(val position: Int) : EmaSelection()
    data object Last : EmaSelection()
    data object Longest : EmaSelection()
    data object Shortest : EmaSelection()
    data object Newest : EmaSelection()
    data object MostViewed : EmaSelection()
    /** "another one", "a different one", "the next one": first not yet chosen. */
    data object Another : EmaSelection()
    /** "the one called X". */
    data class Titled(val text: String) : EmaSelection()

    /** Natural phrase used for canonical text and user-facing reports. */
    fun phrase(): String = when (this) {
        is Ordinal -> "the ${ordinalWord(position)} result"
        Last -> "the last result"
        Longest -> "the longest result"
        Shortest -> "the shortest result"
        Newest -> "the newest result"
        MostViewed -> "the most viewed result"
        Another -> "another result"
        is Titled -> "the result called $text"
    }

    /** True when choosing needs metadata the screen may not show. */
    val needsMetadata: Boolean
        get() = this == Longest || this == Shortest || this == Newest || this == MostViewed

    companion object {
        private val ORDINALS = mapOf(
            "first" to 1, "1st" to 1, "top" to 1,
            "second" to 2, "2nd" to 2,
            "third" to 3, "3rd" to 3,
            "fourth" to 4, "4th" to 4,
            "fifth" to 5, "5th" to 5,
            "sixth" to 6, "6th" to 6,
            "seventh" to 7, "7th" to 7,
            "eighth" to 8, "8th" to 8,
            "ninth" to 9, "9th" to 9,
            "tenth" to 10, "10th" to 10
        )
        private val CARDINALS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
        )

        /** Nouns that name an item in a result list. */
        val ITEM_NOUNS = setOf(
            "result", "results", "one", "ones", "video", "videos", "song", "songs",
            "track", "tracks", "item", "items", "option", "options", "link", "links",
            "clip", "clips", "episode", "episodes", "entry", "entries", "match",
            "matches", "post", "posts", "chat", "chats", "movie", "movies", "podcast",
            "podcasts", "playlist", "playlists", "channel", "channels", "album", "albums"
        )

        fun ordinalWord(position: Int): String = when (position) {
            1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; 5 -> "fifth"
            6 -> "sixth"; 7 -> "seventh"; 8 -> "eighth"; 9 -> "ninth"; 10 -> "tenth"
            else -> "${position}th"
        }

        /**
         * Parse a reference phrase ("the third one", "the last result", "it",
         * "the longest video", "number 4", "another one") into a selection.
         * Returns null when the phrase is not a reference to a list item, so
         * that "play Despacito" stays a media query.
         *
         * [pronounMeansFirst] lets "play it" after a fresh search mean the top
         * result; callers decide when that is appropriate.
         */
        fun parseReference(phrase: String, pronounMeansFirst: Boolean = false): EmaSelection? {
            val words = phrase.lowercase()
                .replace(Regex("[^a-z0-9#' ]"), " ")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .toMutableList()
            if (words.isEmpty()) return null
            // Trailing decoration: "instead", "please", "for me", "on screen".
            val trailing = setOf("instead", "please", "now", "then", "too", "again", "here")
            while (words.isNotEmpty() && words.last() in trailing) words.removeAt(words.size - 1)
            if (words.size >= 2 && words.takeLast(2) == listOf("for", "me")) repeat(2) { words.removeAt(words.size - 1) }
            if (words.size >= 2 && words.takeLast(2) == listOf("on", "screen")) repeat(2) { words.removeAt(words.size - 1) }
            if (words.isEmpty()) return null

            if (words.size == 1 && words[0] in setOf("it", "that", "this", "them")) {
                return if (pronounMeansFirst) Ordinal(1) else null
            }
            if (words == listOf("that", "one") || words == listOf("this", "one")) {
                return if (pronounMeansFirst) Ordinal(1) else null
            }
            // "another one", "a different one", "the next one", "something else"
            val joined = words.joinToString(" ")
            if (Regex("^(?:an?\\s+)?(?:another|different|other)(?:\\s+\\w+)?$").matches(joined) ||
                Regex("^(?:the\\s+)?next(?:\\s+(?:one|result|video|song|track|item))?$").matches(joined) ||
                joined == "something else" || joined == "a different one"
            ) return Another

            var index = 0
            if (words[index] == "the") index++
            if (index >= words.size) return null

            // "number 3", "result 2", "#2", "item 4"
            if (words[index] in setOf("number", "result", "item", "option", "video", "song") && index + 1 < words.size) {
                val n = words[index + 1].removePrefix("#").toIntOrNull() ?: CARDINALS[words[index + 1]]
                if (n != null && n in 1..50 && index + 2 == words.size) return Ordinal(n)
            }
            if (words[index].startsWith("#")) {
                words[index].removePrefix("#").toIntOrNull()?.let { n -> if (n in 1..50 && index + 1 == words.size) return Ordinal(n) }
            }

            val head = words[index]
            val rest = words.drop(index + 1)
            val restIsItemNoun = rest.isEmpty() || (rest.size <= 2 && rest.all { it in ITEM_NOUNS || it == "on" || it == "in" || it == "list" })
            fun restOk(requireNoun: Boolean): Boolean = if (requireNoun) rest.isNotEmpty() && restIsItemNoun else restIsItemNoun

            // "the very first one"
            if (head == "very" && rest.isNotEmpty()) return parseReference("the " + rest.joinToString(" "), pronounMeansFirst)

            ORDINALS[head]?.let { n ->
                // "first" alone is fine; "top" needs a noun ("top result").
                if (head == "top" && !restOk(true)) return null
                if (restOk(false)) return Ordinal(n)
            }
            Regex("^(\\d{1,2})(?:st|nd|rd|th)$").matchEntire(head)?.let { m ->
                val n = m.groupValues[1].toInt()
                if (n in 1..50 && restOk(false)) return Ordinal(n)
            }
            when (head) {
                "last", "final", "bottom" -> if (restOk(head == "bottom")) return Last
                "longest" -> if (restOk(false)) return Longest
                "shortest" -> if (restOk(false)) return Shortest
                "newest", "latest" -> if (restOk(false)) return Newest
                "most" -> {
                    val second = rest.firstOrNull()
                    val after = rest.drop(1)
                    val afterOk = after.isEmpty() || (after.size <= 2 && after.all { it in ITEM_NOUNS })
                    if (second == "recent" && afterOk) return Newest
                    if ((second == "viewed" || second == "popular" || second == "watched") && afterOk) return MostViewed
                }
            }
            // "the one called X" / "the video titled X"
            val calledAt = words.indexOfFirst { it == "called" || it == "titled" || it == "named" }
            if (calledAt in 1..3 && calledAt < words.size - 1 && words.subList(index, calledAt).all { it in ITEM_NOUNS }) {
                return Titled(words.drop(calledAt + 1).joinToString(" "))
            }
            return null
        }
    }
}
