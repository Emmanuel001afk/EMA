package com.ema.assistant

/**
 * What EMA learned from the owner.
 *
 * The learning is small, local and explainable:
 *
 *  - a **correction** ("no, I meant X") links what was said to what was meant;
 *  - a **rephrase** links a request that failed to the way the owner said it
 *    afterwards, when the two are close in time;
 *  - a **mishearing** links a phrase the recogniser got wrong to the phrase
 *    that worked.
 *
 * The rules that keep this honest:
 *
 *  - a link is only used when the owner confirmed it once, or when it was seen
 *    twice - a single guess never changes what EMA does;
 *  - a link is never used inside the text of a message, a search or a
 *    reminder: the content the owner wrote is content, not a command;
 *  - when two candidates are close, EMA asks instead of choosing;
 *  - nothing that looks like a secret is stored;
 *  - "Forget what you learned" clears these links and leaves the "remember"
 *    shortcuts alone.
 *
 * Pure Kotlin: every rule is unit tested without a phone.
 */
data class EmaLearnedLink(
    /** What the owner said, normalised. */
    val phrase: String,
    /** What it meant. */
    val meaning: String,
    val kind: Kind,
    /** How often the phrase was seen. */
    val times: Int,
    /** The owner confirmed the link. */
    val confirmed: Boolean,
    val at: Long
) {
    enum class Kind { CORRECTION, REPHRASE, MISHEARING }
}

/** Which app the owner uses for a kind of request, and how often. */
data class EmaUsage(val app: String, val count: Int) {
    override fun toString(): String = "$app for $count request(s)"
}

/**
 * The learned links and the usage preferences.
 */
class EmaLearningModel(
    links: List<EmaLearnedLink> = emptyList(),
    usage: Map<String, EmaUsage> = emptyMap()
) {

    private val stored = links.toMutableList()
    private val used = usage.toMutableMap()

    /** Every learned link, newest last. */
    fun links(): List<EmaLearnedLink> = stored.toList()

    /** Which app is used for which kind of request, and how often. */
    fun usage(): Map<String, EmaUsage> = used.toMap()

    /**
     * Learn one link.
     *
     * The same phrase and meaning strengthen the existing link instead of
     * adding a second one. The list is bounded: the oldest link goes when it
     * is full.
     */
    fun learn(
        phrase: String,
        meaning: String,
        kind: EmaLearnedLink.Kind,
        confirmed: Boolean = false,
        at: Long = 0L
    ): EmaLearnedLink? {
        val key = normalise(phrase)
        val target = normalise(meaning)
        if (key.isBlank() || target.isBlank()) return null
        if (EmaRunTrace.looksSecret(key) || EmaRunTrace.looksSecret(target)) return null
        // The meaning is kept as the owner said it, so EMA runs the request
        // the way it was written; the comparison uses the normalised form.
        val spoken = meaning.trim()
        val existing = stored.indexOfFirst { it.phrase == key && normalise(it.meaning) == target }
        val link = if (existing >= 0) {
            val old = stored[existing]
            stored[existing] = old.copy(
                times = old.times + 1,
                confirmed = old.confirmed || confirmed,
                at = at,
                kind = kind
            )
            stored[existing]
        } else {
            val fresh = EmaLearnedLink(key, spoken, kind, 1, confirmed, at)
            stored += fresh
            if (stored.size > MAX_LINKS) stored.removeAt(0)
            fresh
        }
        return link
    }

    /**
     * The link that may be applied to a phrase.
     *
     * A link is used when the owner confirmed it, or when it was seen twice.
     * One unseen guess never changes what EMA does.
     */
    fun applicable(phrase: String): EmaLearnedLink? {
        val key = normalise(phrase)
        return stored.firstOrNull { it.phrase == key && (it.confirmed || it.times >= 2) }
    }

    /** Every link for a phrase, newest first. */
    fun candidates(phrase: String): List<EmaLearnedLink> {
        val key = normalise(phrase)
        return stored.filter { it.phrase == key }.sortedByDescending { it.at }
    }

    /**
     * True when EMA cannot choose between the candidates and has to ask.
     *
     * Two different meanings for the same phrase are too close to pick one
     * silently.
     */
    fun ambiguous(candidates: List<EmaLearnedLink>): Boolean {
        val meanings = candidates.map { it.meaning }.distinct()
        return meanings.size > 1
    }

    /**
     * The alternative the recogniser offered that is closest to what was
     * said.
     *
     * It is a guess, so it is only stored as an unconfirmed link: it is used
     * after it was seen twice, or after the owner confirms it.
     */
    fun closestAlternative(chosen: String, alternatives: List<String>): String? {
        val words = normalise(chosen).split(" ").filter { it.isNotBlank() }.toSet()
        if (words.isEmpty()) return null
        var best: String? = null
        var bestScore = 0
        for (alternative in alternatives) {
            val other = normalise(alternative).split(" ").filter { it.isNotBlank() }.toSet()
            val shared = words.intersect(other).size
            if (shared > bestScore) {
                bestScore = shared
                best = alternative
            }
        }
        return if (bestScore > 0) best else null
    }

    /** Remember that an app was used for a kind of request. */
    fun noteUsage(kind: String, appLabel: String) {
        val label = appLabel.trim()
        if (label.isBlank()) return
        val key = kind.trim().lowercase()
        if (key.isBlank()) return
        val previous = used[key]
        used[key] = if (previous == null) EmaUsage(label, 1) else previous.copy(count = previous.count + 1)
        while (used.size > MAX_USAGE) {
            val oldest = used.minByOrNull { it.value.count } ?: break
            used.remove(oldest.key)
        }
    }

    /**
     * The app the owner uses most for a kind of request.
     *
     * "play my playlist" then points at the app the owner really uses, not at
     * whichever app happens to be installed.
     */
    fun preferredApp(kind: String): String? {
        val key = kind.trim().lowercase()
        return used[key]?.app
    }

    /** Forget every learned link. The "remember" shortcuts are not touched. */
    fun clear() {
        stored.clear()
    }

    /** Forget the learned links and the usage preferences. */
    fun clearAll() {
        stored.clear()
        used.clear()
    }

    /** A spoken summary of what was learned. */
    fun summary(): String {
        if (stored.isEmpty() && used.isEmpty()) {
            return "I have not learned anything yet. Say \"no, I meant ...\" after a request that did not work, and I will remember it."
        }
        val out = StringBuilder()
        if (stored.isNotEmpty()) {
            out.append("I learned ")
            out.append(stored.size)
            out.append(if (stored.size == 1) " thing: " else " things: ")
            out.append(stored.takeLast(5).joinToString("; ") { link ->
                "\"${link.phrase}\" means \"${link.meaning}\""
            })
            out.append(". ")
        }
        if (used.isNotEmpty()) {
            out.append("I also use ")
            out.append(used.entries.joinToString(", ") { (kind, usage) -> "$usage" })
            out.append(" most often. ")
        }
        out.append("Say \"forget what you learned\" to make me forget.")
        return out.toString()
    }

    /** The links as text, so they can be stored and read back. */
    fun serialize(): String {
        val out = StringBuilder()
        out.append("links=").append(stored.size).append('\n')
        for (link in stored) {
            out.append("link|").append(link.kind.name).append('|')
                .append(link.times).append('|')
                .append(if (link.confirmed) "1" else "0").append('|')
                .append(link.at).append('|')
                .append(link.phrase).append('|')
                .append(link.meaning).append('\n')
        }
        for ((kind, usage) in used) {
            out.append("use|").append(usage.count).append('|').append(kind).append('|').append(usage.app).append('\n')
        }
        return out.toString()
    }

    /** The words EMA compares phrases with. */
    fun normalise(phrase: String): String =
        phrase.trim().lowercase().replace(Regex("[^a-z0-9' ]+"), " ").replace(Regex("\\s+"), " ")

    companion object {
        /** How many links are kept. */
        const val MAX_LINKS = 30

        /** How many usage preferences are kept. */
        const val MAX_USAGE = 20

        /** How long a correction may follow the request it corrects. */
        const val LINK_WINDOW_MS = 60_000L

        /** Read a model back from [serialize] output. */
        fun parse(text: String): EmaLearningModel {
            val links = mutableListOf<EmaLearnedLink>()
            val usage = mutableMapOf<String, EmaUsage>()
            for (raw in text.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("link|") -> {
                        // link|kind|times|confirmed|at|phrase|meaning
                        val parts = line.split("|")
                        if (parts.size >= 7) {
                            val kind = runCatching { EmaLearnedLink.Kind.valueOf(parts[1]) }
                                .getOrDefault(EmaLearnedLink.Kind.CORRECTION)
                            links += EmaLearnedLink(
                                phrase = parts[5],
                                meaning = parts[6],
                                kind = kind,
                                times = parts[2].toIntOrNull() ?: 1,
                                confirmed = parts[3] == "1",
                                at = parts[4].toLongOrNull() ?: 0L
                            )
                        }
                    }
                    line.startsWith("use|") -> {
                        val parts = line.split("|")
                        if (parts.size >= 4) {
                            usage[parts[2]] = EmaUsage(parts[3], parts[1].toIntOrNull() ?: 1)
                        }
                    }
                }
            }
            return EmaLearningModel(links, usage)
        }
    }
}
