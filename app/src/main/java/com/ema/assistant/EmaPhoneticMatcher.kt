package com.ema.assistant

import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic phonetic and compound-word matcher for offline speech slot
 * grounding.
 *
 * Small offline acoustic models have a fixed dictionary: brand names, private
 * enterprise apps and regional personal names ("OPay", "Moniepoint",
 * "Audiomack", "Boomplay", "Phormi", "Tunde", "Okafor") are frequently decoded
 * as phonetically identical English word sequences ("oh pay", "money point",
 * "audio mac", "boom play", "for me", "tune day").
 *
 * Safety contract:
 *  - Only called on explicit app or contact slots, never on message bodies,
 *    search queries or reminder text.
 *  - Requires both a high absolute score and a clear margin over the runner-up;
 *    close ties return null so EMA asks the owner instead of guessing.
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
        "last", "next", "previous", "now", "today", "tomorrow", "tonight", "please",
        "wifi", "bluetooth", "flashlight", "torch", "volume", "brightness", "alarm",
        "timer", "reminder", "battery", "storage", "time", "date", "camera"
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
     * Joins split tokens first (so "oh pay" and "OPay", or "for me" and
     * "Phormi", map to the same sequence before consonant-cluster folding),
     * preserves leading and trailing distinctive vowels, and collapses silent
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
        // so split ASR transcripts match single-token names faithfully.
        val normalizedJoined = words.joinToString("") { w ->
            when (w) {
                "oh" -> "o"
                "eye", "aye" -> "i"
                "me" -> "mi"
                "be", "bee" -> "bi"
                "he" -> "hi"
                "she" -> "shi"
                "we" -> "wi"
                "you", "u" -> "yu"
                "four", "fore" -> "for"
                "too", "two" -> "to"
                "sea", "see" -> "si"
                "gee" -> "g"
                "jay" -> "j"
                "kay" -> "k"
                "pee" -> "p"
                "tee" -> "t"
                "why" -> "wi"
                "ex" -> "eks"
                "could" -> "kud"
                "would" -> "wud"
                "should" -> "shud"
                else -> w.removeSuffix("eh")
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
        // Silent initial consonant pairs: kn, gn, pn, wr, ps, wh
        if (len >= 2) {
            val pair = "${s[0]}${s[1]}"
            when (pair) {
                "kn", "gn", "pn", "ae" -> i = 1
                "wr" -> i = 1
                "wh" -> { emit('W'); i = 2 }
                "ps" -> i = 1
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
                        // "ay"/"ey" -> 'A', "ee"/"ie"/"i"/"y" -> 'I', "ow"/"oh"/"o" -> 'O'; silent bare 'e' dropped.
                        when {
                            c == 'y' && (prev == 'a' || prev == 'e') -> emit('A')
                            c == 'i' || c == 'y' -> emit('I')
                            c == 'a' -> emit('A')
                            c == 'o' || c == 'u' -> emit('O')
                            c == 'e' && prev == 'e' -> emit('I')
                            // Sounded final "-nde"/"-nte" in names ("Tunde", "Dante"), silent in English VCe ("tune", "home").
                            c == 'e' && (prev == 'd' || prev == 't') && at(i - 2) == 'n' -> emit('A')
                        }
                        i++
                    } else if (c == 'a' && next == 'y' && (i + 2 == len || !isVowel(at(i + 2)))) {
                        // Internal/final "-ay" diphthong ("day", "pay", "play").
                        if (i + 2 == len) emit('A')
                        i += 2
                    } else {
                        i++
                    }
                }
                'b' -> {
                    // Silent 'b' in "-mb" at end ("dumb", "climb").
                    if (!(i == len - 1 && prev == 'm')) emit('P')
                    i++
                }
                'c' -> {
                    when {
                        next == 'h' -> { emit('X'); i += 2 }
                        next == 'k' -> { emit('K'); i += 2 }
                        c == 'c' && next == 'i' && at(i + 2) == 'a' -> { emit('X'); i += 3 }
                        next == 'e' || next == 'i' || next == 'y' -> { emit('S'); i++ }
                        else -> { emit('K'); i++ }
                    }
                }
                'd' -> {
                    if (next == 'g' && (at(i + 2) == 'e' || at(i + 2) == 'i' || at(i + 2) == 'y')) {
                        emit('J')
                        i += 2
                    } else {
                        // Keep D distinct from T? No, collapse voiced/unvoiced gently in key,
                        // while compact() keeps d/t distinct as a tie-breaker.
                        emit('D')
                        i++
                    }
                }
                'f' -> { emit('F'); i++ }
                'g' -> {
                    when {
                        next == 'h' && !isVowel(at(i + 2)) -> i += 2 // silent "gh" in "light", "night"
                        next == 'h' && i == 0 -> { emit('G'); i += 2 }
                        next == 'n' && i == len - 2 -> { emit('N'); i += 2 }
                        next == 'e' || next == 'i' || next == 'y' -> { emit('J'); i++ }
                        else -> { emit('G'); i++ }
                    }
                }
                'h' -> {
                    // Sounded only before a vowel and not after a consonant cluster.
                    if (isVowel(next) && (i == 0 || isVowel(prev))) emit('H')
                    i++
                }
                'j' -> { emit('J'); i++ }
                'k' -> { emit('K'); i++ }
                'l' -> {
                    // Silent 'l' in "palm", "calm", "walk", "talk", "half".
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
     * "money point" -> "Moniepoint").
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
        // Exact phonetic match on a substantial key (>= 3 symbols) strongly rescues
        // homophones ("for me" / "Phormi", "oh pay" / "OPay", "money point" / "Moniepoint"),
        // while keeping charSim as a tie-breaker so "Daniel" beats "Danielle" for "daniel".
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

        // Deduplicate candidates case-insensitively while preserving the owner's casing.
        val unique = LinkedHashMap<String, String>()
        for (raw in candidates) {
            val c = raw.trim()
            if (c.isEmpty()) continue
            val key = c.lowercase()
            // Prefer mixed/capitalized canonical label over all-lowercase duplicate.
            val prev = unique[key]
            if (prev == null || (prev == prev.lowercase() && c != c.lowercase())) {
                unique[key] = c
            }
        }
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
