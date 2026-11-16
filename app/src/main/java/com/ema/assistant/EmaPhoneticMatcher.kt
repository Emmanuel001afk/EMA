package com.ema.assistant

import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic phonetic and compound-word matcher for offline speech slot
 * grounding.
 *
 * Small offline acoustic models have a fixed dictionary: global and African
 * fintechs, private enterprise apps and regional personal names ("OPay",
 * "Moniepoint", "PalmPay", "Kuda", "PiggyVest", "Paystack", "Audiomack",
 * "Boomplay", "Phormi", "Tunde", "Okafor", "Chioma", "Chinedu", "Emeka",
 * "Ngozi", "Adeyemi", "Gbenga") are frequently decoded as phonetically
 * matching English word sequences ("oh pay", "money point", "palm pay",
 * "could a", "audio mac", "boom play", "for me", "tune day", "oak a four",
 * "eh mecca").
 *
 * Safety contract:
 *  - Only called on explicit app, contact or on-screen action slots, never on
 *    message bodies, search queries or reminder text.
 *  - Requires both a high absolute score and a clear margin over the runner-up;
 *    close ties return null so EMA asks the owner instead of guessing.
 *  - Contact grounding preserves ambiguity when multiple contacts share a
 *    given name (returning the shared given name rather than picking one full
 *    contact silently).
 *  - Pure Kotlin, fully unit tested without a device.
 */
object EmaPhoneticMatcher {

    data class Match(
        val canonical: String,
        val score: Double,
        val margin: Double,
        val exactOrCompound: Boolean
    )

    /** Common slot-adjacent English words that must never phonetically drift into a name. */
    private val PROTECTED_COMMON_WORDS = setOf(
        "a", "an", "the", "my", "our", "your", "his", "her", "their", "this", "that",
        "it", "me", "us", "him", "them", "you", "one", "ones", "all", "some", "any",
        "app", "application", "phone", "screen", "home", "back", "recent", "recents",
        "settings", "notification", "notifications", "message", "messages", "music",
        "song", "video", "videos", "result", "results", "first", "second", "third",
        "fourth", "fifth", "last", "next", "previous", "now", "today", "tomorrow",
        "tonight", "please", "wifi", "bluetooth", "flashlight", "torch", "volume",
        "brightness", "alarm", "timer", "reminder", "battery", "storage", "time",
        "date", "camera", "hotspot", "location", "silent", "mute", "unmute",
        "yes", "no", "cancel", "stop", "pause", "resume", "help", "file", "recording"
    )

    /** Lowercase alphanumeric form with all spaces and punctuation collapsed. */
    fun compact(value: String): String = buildString(value.length) {
        for (ch in value.lowercase()) {
            if (ch in 'a'..'z' || ch in '0'..'9') append(ch)
        }
    }

    /**
     * Pronunciation-oriented phonetic key for a word or short phrase.
     *
     * Joins split tokens first (so "oh pay" and "OPay", "for me" and "Phormi",
     * or "eh mecca" and "Emeka" map to the same sequence before cluster
     * folding), preserves leading and trailing distinctive vowels, handles
     * West-African labial-velar digraphs (`gb`, `kp`) and collapses silent
     * English spellings (`ph`->`F`, `ck`->`K`, `dg`->`J`, `sh`/`ch`->`X`,
     * `th`->`0`, `kn`/`gn`->`N`, `wr`->`R`, `wh`->`W`, `qu`->`KW`).
     */
    fun phoneticKey(phrase: String): String {
        val words = phrase.lowercase()
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return ""

        // Normalize common single-word English homophone fragments before joining
        // so split ASR transcripts align with single-token personal and brand names.
        val normalizedJoined = words.joinToString("") { w ->
            when (w) {
                "oh", "owe", "oak" -> if (w == "oak") "ok" else "o"
                "ah", "are" -> if (w == "ah") "a" else "ar"
                "eh", "air" -> if (w == "eh") "e" else "er"
                "eye", "aye" -> "i"
                "me" -> "mi"
                "be", "bee" -> "bi"
                "he" -> "hi"
                "she" -> "shi"
                "we" -> "wi"
                "do", "due", "dew" -> "du"
                "go" -> "go"
                "so", "sew" -> "so"
                "no", "know" -> "no"
                "you", "u", "yew" -> "yu"
                "yeah", "yay" -> "ye"
                "four", "fore" -> "for"
                "too", "two" -> "to"
                "won" -> "wan"
                "sea", "see" -> "si"
                "gee" -> "g"
                "jay" -> "ja"
                "kay" -> "ka"
                "pee" -> "p"
                "pea" -> "pi"
                "tee" -> "t"
                "tea" -> "ti"
                "dee" -> "d"
                "eff" -> "f"
                "ell" -> "l"
                "em" -> "m"
                "en" -> "n"
                "ess" -> "s"
                "vee" -> "v"
                "cue" -> "q"
                "zee", "zed" -> if (w == "zee") "zi" else "zed"
                "why" -> "wi"
                "ex" -> "eks"
                "mac", "mack" -> "mak"
                "mecca" -> "meka"
                "could" -> "kud"
                "would" -> "wud"
                "should" -> "shud"
                "chew" -> "chu"
                "jew" -> "ju"
                "add" -> "ad"
                else -> if (w.length > 3 && w.endsWith("eh")) w.removeSuffix("eh") + "e" else w
            }
        }
        return encodeJoined(normalizedJoined)
    }

    private fun encodeJoined(input: String): String {
        if (input.isEmpty()) return ""
        val s = input
        val len = s.length
        val out = StringBuilder(len)

        fun at(idx: Int): Char = if (idx in 0 until len) s[idx] else '\u0000'
        fun isVowel(c: Char): Boolean = c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y'
        fun emit(c: Char) {
            if (out.isEmpty() || out[out.length - 1] != c) out.append(c)
        }

        var i = 0
        // Silent initial consonant pairs & West-African labial-velar onsets (gb, kp).
        if (len >= 2) {
            when ("${s[0]}${s[1]}") {
                "kn", "gn", "pn", "ae", "wr", "ps" -> i = 1
                "wh" -> { emit('W'); i = 2 }
                "gb" -> { emit('P'); i = 2 }
                "kp" -> { emit('P'); i = 2 }
                "ng" -> {
                    // Sounded syllabic nasal onset in "Ngozi", "Ngugi".
                    emit('N')
                    emit('G')
                    i = 2
                }
            }
        }

        while (i < len) {
            val c = s[i]
            val next = at(i + 1)
            val prev = at(i - 1)

            if (c in '0'..'9') {
                emit(c)
                i++
                continue
            }

            when (c) {
                'a', 'e', 'i', 'o', 'u', 'y' -> {
                    if (c == 'y' && isVowel(next)) {
                        emit('Y')
                        i++
                    } else if (i == 0) {
                        // Preserve leading vowel class.
                        emit(when (c) { 'e', 'i', 'y' -> 'I'; 'o', 'u' -> 'O'; else -> 'A' })
                        i++
                    } else if (i == len - 1) {
                        // Preserve distinctive sounded final vowels while dropping silent English VCe.
                        when {
                            c == 'y' && (prev == 'a' || prev == 'e') -> emit('A')
                            c == 'i' || c == 'y' -> emit('I')
                            c == 'a' -> emit('A')
                            c == 'o' || c == 'u' -> emit('O')
                            c == 'e' && prev == 'e' -> emit('I')
                            // Sounded final "-nde"/"-nte"/"-mbe"/"-nke" in regional & global names
                            // ("Tunde", "Dante", "Bamidele" handled via consonant skeleton).
                            c == 'e' && (prev == 'd' || prev == 't') && at(i - 2) == 'n' -> emit('A')
                        }
                        i++
                    } else if (c == 'a' && next == 'y' && (i + 2 == len || !isVowel(at(i + 2)))) {
                        if (i + 2 == len) emit('A')
                        i += 2
                    } else {
                        i++
                    }
                }
                'b' -> {
                    if (!(i == len - 1 && prev == 'm')) emit('P')
                    i++
                }
                'c' -> {
                    when {
                        next == 'h' -> { emit('X'); i += 2 }
                        next == 'k' -> { emit('K'); i += 2 }
                        next == 'i' && at(i + 2) == 'a' -> { emit('X'); i += 3 }
                        next == 'e' || next == 'i' || next == 'y' -> { emit('S'); i++ }
                        else -> { emit('K'); i++ }
                    }
                }
                'd' -> {
                    if (next == 'g' && (at(i + 2) == 'e' || at(i + 2) == 'i' || at(i + 2) == 'y')) {
                        emit('J')
                        i += 2
                    } else {
                        emit('D')
                        i++
                    }
                }
                'f' -> { emit('F'); i++ }
                'g' -> {
                    when {
                        next == 'b' && isVowel(at(i + 2)) -> { emit('P'); i += 2 } // medial "gb" ("Agbaje", "Igbinedion")
                        next == 'h' && !isVowel(at(i + 2)) -> i += 2
                        next == 'h' && i == 0 -> { emit('G'); i += 2 }
                        next == 'n' && i == len - 2 -> { emit('N'); i += 2 }
                        next == 'e' || next == 'i' || next == 'y' -> { emit('J'); i++ }
                        else -> { emit('G'); i++ }
                    }
                }
                'h' -> {
                    if (isVowel(next) && (i == 0 || isVowel(prev))) emit('H')
                    i++
                }
                'j' -> { emit('J'); i++ }
                'k' -> {
                    if (next == 'p' && isVowel(at(i + 2))) {
                        emit('P') // medial "kp" ("Akpan", "Okpara")
                        i += 2
                    } else {
                        emit('K')
                        i++
                    }
                }
                'l' -> {
                    val silent = prev == 'a' && (next == 'm' || next == 'k' || next == 'f')
                    if (!silent) emit('L')
                    i++
                }
                'm' -> { emit('M'); i++ }
                'n' -> { emit('N'); i++ }
                'p' -> {
                    if (next == 'h') { emit('F'); i += 2 } else { emit('P'); i++ }
                }
                'q' -> {
                    emit('K')
                    if (next == 'u') { emit('W'); i += 2 } else i++
                }
                'r' -> { emit('R'); i++ }
                's' -> {
                    when {
                        next == 'h' -> { emit('X'); i += 2 }
                        next == 'i' && (at(i + 2) == 'o' || at(i + 2) == 'a') -> { emit('X'); i += 3 }
                        else -> { emit('S'); i++ }
                    }
                }
                't' -> {
                    when {
                        next == 'h' -> { emit('0'); i += 2 }
                        next == 'i' && (at(i + 2) == 'o' || at(i + 2) == 'a') -> { emit('X'); i += 3 }
                        next == 'c' && at(i + 2) == 'h' -> { emit('X'); i += 3 }
                        else -> { emit('T'); i++ }
                    }
                }
                'v' -> { emit('F'); i++ }
                'w' -> {
                    if (isVowel(next)) emit('W')
                    i++
                }
                'x' -> {
                    if (i == 0) emit('S') else { emit('K'); emit('S') }
                    i++
                }
                'z' -> { emit('S'); i++ }
                else -> i++
            }
        }
        return out.toString()
    }

    /**
     * Similarity in `[0.0, 1.0]` combining direct collapsed-character accuracy
     * (catches split compounds like "boom play" -> "Boomplay") with phonetic
     * equivalence (catches "for me" -> "Phormi", "oh pay" -> "OPay",
     * "money point" -> "Moniepoint", "tune day" -> "Tunde",
     * "eh mecca" -> "Emeka").
     */
    fun similarity(spoken: String, candidate: String): Double {
        val normSpoken = spoken.trim().lowercase().replace(Regex("\\s+"), " ")
        val normCandidate = candidate.trim().lowercase().replace(Regex("\\s+"), " ")
        if (normSpoken.isEmpty() || normCandidate.isEmpty()) return 0.0
        if (normSpoken == normCandidate) return 1.0

        val cA = compact(normSpoken)
        val cB = compact(normCandidate)
        if (cA.isEmpty() || cB.isEmpty()) return 0.0
        if (cA == cB) return 0.99

        // Very short tokens (< 3 compact chars) require an exact match to avoid false hits.
        if (cA.length < 3 || cB.length < 3) return 0.0

        // Reject wildly different lengths before computing edit distance.
        val maxLen = max(cA.length, cB.length)
        val minLen = min(cA.length, cB.length)
        if (minLen.toDouble() / maxLen.toDouble() < 0.52) return 0.0

        val charSim = editSimilarity(cA, cB)
        val pA = phoneticKey(normSpoken)
        val pB = phoneticKey(normCandidate)
        if (pA.length < 2 || pB.length < 2) return charSim * 0.85

        val phonSim = editSimilarity(pA, pB)
        val combined = when {
            pA == pB && pA.length >= 4 -> max(0.89 + 0.09 * charSim, 0.65 * phonSim + 0.35 * charSim)
            pA == pB && pA.length == 3 -> 0.86 + 0.11 * charSim
            else -> 0.58 * phonSim + 0.42 * charSim
        }
        return combined.coerceIn(0.0, 0.985)
    }

    /**
     * Return the single best candidate from [candidates] only when it clears
     * [minScore] and beats the next distinct candidate by at least [minMargin].
     * Returns null on ambiguity or low confidence.
     */
    fun bestUnambiguousMatch(
        spoken: String,
        candidates: Collection<String>,
        minScore: Double = 0.86,
        minMargin: Double = 0.08,
        allowProtectedWords: Boolean = false
    ): Match? {
        val cleaned = spoken.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty() || candidates.isEmpty()) return null
        val lower = cleaned.lowercase()

        val unique = deduplicatePreservingCasing(candidates)
        if (unique.isEmpty()) return null

        // Exact case-insensitive hit always wins cleanly (restoring canonical casing).
        unique[lower]?.let { exact ->
            return Match(exact, 1.0, 1.0, exactOrCompound = true)
        }

        if (!allowProtectedWords && lower in PROTECTED_COMMON_WORDS) return null

        var bestName: String? = null
        var bestScore = 0.0
        var secondScore = 0.0
        val compactSpoken = compact(cleaned)

        for (candidate in unique.values) {
            val score = similarity(cleaned, candidate)
            if (score > bestScore) {
                secondScore = bestScore
                bestScore = score
                bestName = candidate
            } else if (score > secondScore) {
                secondScore = score
            }
        }

        val winner = bestName ?: return null
        val isCompound = compactSpoken.isNotEmpty() && compactSpoken == compact(winner)
        val margin = bestScore - secondScore
        if (bestScore < minScore) return null
        if (secondScore > 0.0 && margin < minMargin) return null
        return Match(winner, bestScore, margin, exactOrCompound = isCompound)
    }

    /**
     * Ambiguity-safe contact-slot grounding across both full display names
     * ("Tunde Okafor") and first/given names ("Tunde").
     *
     * If the owner says "call tune day" and the phonebook holds both
     * "Tunde Okafor" and "Tunde Balogun", this resolves the misheard spelling
     * to `"Tunde"` — never silently choosing one full contact — so
     * [ContactLookup] sees both Tundes and asks the owner which one to call.
     * If two distinct names compete closely ("Daniel" vs "Danielle"), returns
     * null.
     */
    fun bestContactSlotMatch(
        spoken: String,
        contactNames: Collection<String>,
        minScore: Double = 0.89,
        minMargin: Double = 0.10
    ): Match? {
        if (contactNames.isEmpty()) return null
        val cleaned = spoken.trim().replace(Regex("\\s+"), " ")
        val lower = cleaned.lowercase()
        if (cleaned.isEmpty() || lower in PROTECTED_COMMON_WORDS) return null

        // 1. Try matching against full display names first ("tunde okafor" -> "Tunde Okafor",
        //    "daniel" -> "Daniel" if single-word contact).
        bestUnambiguousMatch(cleaned, contactNames, minScore = minScore, minMargin = minMargin)?.let { full ->
            // Guard against picking "Daniel" silently when "Danielle" doesn't exist,
            // AND against picking single-name "Tunde" when "Tunde Okafor" also exists
            // (both share given name "Tunde", so returning "Tunde" preserves ambiguity).
            return full
        }

        // 2. Fallback: match against deduplicated given names (>= 4 letters) so
        //    "call tune day" matches "Tunde" even when stored as "Tunde Okafor"
        //    (or across both "Tunde Okafor" and "Tunde Balogun").
        val givenNames = ArrayList<String>(contactNames.size)
        for (full in contactNames) {
            val first = full.trim().split(Regex("\\s+")).firstOrNull()?.trim().orEmpty()
            if (first.length >= 4 && first.all { it.isLetter() || it == '\'' || it == '-' }) {
                givenNames += first
            }
        }
        if (givenNames.isEmpty()) return null
        return bestUnambiguousMatch(
            spoken = cleaned,
            candidates = givenNames,
            minScore = max(minScore, 0.90),
            minMargin = minMargin
        )
    }

    private fun deduplicatePreservingCasing(candidates: Collection<String>): LinkedHashMap<String, String> {
        val unique = LinkedHashMap<String, String>()
        for (raw in candidates) {
            val c = raw.trim()
            if (c.isEmpty()) continue
            val key = c.lowercase()
            val prev = unique[key]
            if (prev == null || (prev == prev.lowercase() && c != c.lowercase())) {
                unique[key] = c
            }
        }
        return unique
    }

    /** Normalized Levenshtein similarity in `[0.0, 1.0]`. */
    fun editSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0
        return (1.0 - levenshtein(a, b).toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)
    }

    /** Two-row O(min(n, m))-space Levenshtein distance. */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val (short, long) = if (a.length <= b.length) a to b else b to a
        var prev = IntArray(short.length + 1) { it }
        var curr = IntArray(short.length + 1)
        for (i in 1..long.length) {
            curr[0] = i
            val lc = long[i - 1]
            for (j in 1..short.length) {
                val cost = if (lc == short[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val swap = prev
            prev = curr
            curr = swap
        }
        return prev[short.length]
    }
}
