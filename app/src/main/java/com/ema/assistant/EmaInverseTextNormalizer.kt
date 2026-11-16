package com.ema.assistant

import java.util.Locale

/**
 * Private, strictly on-device vocabulary snapshot used to normalize and rescore
 * spoken hypotheses against what actually exists on the owner's phone.
 *
 * Kept in memory only; never written to logs or sent over the network.
 */
data class EmaSpeechContext(
    val shortcuts: List<String> = emptyList(),
    val ownerAliases: List<String> = emptyList(),
    val learnedPhrases: List<String> = emptyList(),
    val installedApps: List<String> = emptyList(),
    val contactNames: List<String> = emptyList()
) {
    /** Flat hint list (shortcuts + apps) suitable for platform biasing APIs. */
    fun publicBiasingHints(limit: Int = 200): ArrayList<String> {
        val out = LinkedHashSet<String>()
        shortcuts.forEach { if (it.isNotBlank()) out += it.trim() }
        ownerAliases.forEach { if (it.isNotBlank()) out += it.trim() }
        installedApps.forEach { if (it.isNotBlank()) out += it.trim() }
        return ArrayList(out.take(limit))
    }

    companion object {
        val EMPTY = EmaSpeechContext()
    }
}

/**
 * Inverse Text Normalizer (ITN) for EMA's offline speech-to-text engine.
 *
 * Offline acoustic/WFST models decode pure lowercase English words:
 *  - `"set an alarm for six thirty a m"`       -> `"set an alarm for 6:30 am"`
 *  - `"set brightness to seventy five percent"` -> `"set brightness to 75%"`
 *  - `"what is fifteen percent of two thousand four hundred"` -> `"what is 15% of 2400"`
 *  - `"convert five kilometers to miles"`       -> `"convert 5 kilometers to miles"`
 *  - `"open you tube and play arcade"`          -> `"open YouTube and play arcade"`
 *  - `"call ada with sim two"`                  -> `"call Ada with SIM 2"`
 *  - `"message daniel on what's up saying what's up"` -> `"message Daniel on WhatsApp saying what's up"`
 *
 * Message bodies, search queries and reminder tails are protected so command
 * normalizations never alter the owner's actual content, and ambiguous app or
 * contact matches are never guessed.
 *
 * Pure Kotlin, zero Android types, exhaustively unit tested.
 */
object EmaInverseTextNormalizer {

    private val ONES = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L,
        "five" to 5L, "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L,
        "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L,
        "fourteen" to 14L, "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L,
        "eighteen" to 18L, "nineteen" to 19L
    )

    private val TENS = mapOf(
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L,
        "sixty" to 60L, "seventy" to 70L, "eighty" to 80L, "ninety" to 90L
    )

    private val SCALES = mapOf(
        "hundred" to 100L,
        "thousand" to 1_000L,
        "million" to 1_000_000L,
        "billion" to 1_000_000_000L
    )

    private val SINGLE_DIGITS = mapOf(
        "zero" to "0", "oh" to "0", "o" to "0",
        "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9"
    )

    /** Words before or after standalone "one" showing it is a pronoun/idiom, not the digit 1. */
    private val PRONOUN_ONE_PREV = setOf(
        "the", "this", "that", "which", "another", "other", "first", "second", "third",
        "fourth", "fifth", "last", "next", "previous", "longest", "shortest", "newest",
        "oldest", "best", "top", "no", "any", "some", "every", "each", "only", "same",
        "wrong", "right", "new", "old", "big", "small", "good", "nice"
    )

    private val PRONOUN_ONE_NEXT = setOf(
        "instead", "please", "now", "too", "also", "again", "action", "step", "thing",
        "by", "of", "that", "which", "who", "is", "was", "more", "else", "on", "in"
    )

    /** Words adjacent to a single cardinal word confirming numeric intent. */
    private val NUMERIC_FOLLOWERS = setOf(
        "percent", "%", "second", "seconds", "sec", "secs", "minute", "minutes", "min", "mins",
        "hour", "hours", "hr", "hrs", "day", "days", "week", "weeks", "month", "months", "year", "years",
        "mm", "millimeter", "millimeters", "millimetre", "millimetres",
        "cm", "centimeter", "centimeters", "centimetre", "centimetres",
        "m", "meter", "meters", "metre", "metres",
        "km", "kilometer", "kilometers", "kilometre", "kilometres", "kms",
        "inch", "inches", "foot", "feet", "ft", "yard", "yards", "yd", "mile", "miles", "mi",
        "mg", "milligram", "milligrams", "g", "gram", "grams",
        "kg", "kilo", "kilos", "kilogram", "kilograms",
        "lb", "lbs", "pound", "pounds", "oz", "ounce", "ounces", "stone", "stones",
        "ml", "milliliter", "milliliters", "millilitre", "millilitres",
        "l", "liter", "liters", "litre", "litres", "gallon", "gallons", "cup", "cups",
        "kb", "kilobyte", "kilobytes", "mb", "megabyte", "megabytes",
        "gb", "gigabyte", "gigabytes", "tb", "terabyte", "terabytes",
        "kmh", "kph", "mph", "degree", "degrees", "celsius", "centigrade", "fahrenheit", "kelvin",
        "plus", "minus", "times", "multiplied", "divided", "over", "squared", "cubed",
        "power", "raised", "factorial", "modulo", "mod",
        "am", "pm", "o'clock",
        "naira", "dollar", "dollars", "euro", "euros", "cedis"
    )

    private val NUMERIC_LEADERS = setOf(
        "at", "for", "in", "after", "within", "around", "until", "till", "by", "to", "of",
        "plus", "add", "minus", "subtract", "less", "times", "into", "over", "mod", "modulo",
        "is", "what's", "calculate", "compute", "solve", "evaluate", "convert",
        "increase", "decrease", "reduce", "set", "sim", "alarm", "timer", "brightness", "volume",
        "number", "level", "channel", "page", "step", "ending"
    )

    /** Verbs/words after "am" proving "am" is the verb ("I am coming"), never A.M. */
    private val VERB_AM_FOLLOWERS = setOf(
        "going", "coming", "running", "leaving", "heading", "driving", "walking", "working",
        "waiting", "calling", "texting", "sending", "trying", "doing", "having", "getting",
        "being", "feeling", "looking", "ready", "busy", "here", "there", "home", "outside",
        "inside", "late", "early", "free", "back", "done", "fine", "good", "okay", "ok",
        "sorry", "not", "so", "very", "almost", "still", "just", "already", "about", "in", "at", "on", "a", "an", "the"
    )

    private val LEADING_HESITATIONS = setOf("uh", "uhh", "um", "umm", "erm", "er", "hmm", "hm")

    /** Well-known split words and common Vosk mishearings safe inside the command portion. */
    private val COMMAND_PHRASE_FIXES: List<Pair<Regex, String>> = listOf(
        // Wake & assistant name spellings
        Regex("(?i)^(?:hey|hay)\\s+(?:emma|ema|emmah|amma|e\\s+m\\s+a)\\b") to "Hey EMA",
        Regex("(?i)^(?:hi)\\s+(?:emma|ema|emmah|e\\s+m\\s+a)\\b") to "Hi EMA",
        Regex("(?i)^(?:okay|ok)\\s+(?:emma|ema|emmah|e\\s+m\\s+a)\\b") to "Okay EMA",
        Regex("(?i)\\bteach\\s+(?:emma|amma|e\\s+m\\s+a)\\s+my\\s+voice\\b") to "teach EMA my voice",
        Regex("(?i)\\be\\s+m\\s+a\\b") to "EMA",

        // SIM slot homophones ("with seem two", "with sim too", "using sim one")
        Regex("(?i)\\b(with|using|on|via)\\s+(?:sim|seem|sin)\\s+(?:one|1|won)\\b") to "$1 SIM 1",
        Regex("(?i)\\b(with|using|on|via)\\s+(?:sim|seem|sin)\\s+(?:two|2|too|to)\\b") to "$1 SIM 2",

        // Device-setting compounds & frequent offline mishearings
        Regex("(?i)\\b(?:wi\\s+fi|why\\s+fi|why\\s+five|wife\\s+i|wifi)\\b") to "Wi-Fi",
        Regex("(?i)\\bblue\\s+tooth\\b") to "Bluetooth",
        Regex("(?i)\\b(?:flash\\s+light|torch\\s+light)\\b") to "flashlight",
        Regex("(?i)\\bscreen\\s+shot\\b") to "screenshot",
        Regex("(?i)\\b(?:air\\s+plane|aero\\s+plane)\\b") to "airplane",
        Regex("(?i)\\bdo\\s+not\\s+disturb\\b") to "Do Not Disturb",
        Regex("(?i)\\bd\\s+n\\s+d\\b") to "Do Not Disturb",

        // Split compound app names in command slots
        Regex("(?i)\\byou\\s+tube\\b") to "YouTube",
        Regex("(?i)\\byoutube\\b") to "YouTube",
        Regex("(?i)\\b(?:whats\\s+app|what's\\s+app|what\\s+sup|watts\\s+app|whatsapp)\\b") to "WhatsApp",
        Regex("(?i)\\b(open|launch|start|on|in|using|via|through)\\s+what's\\s+up\\b") to "$1 WhatsApp",
        Regex("(?i)\\b(?:spot\\s+if\\s+(?:i|eye)|spot\\s+a\\s+(?:fi|five|fly)|spot\\s+defy|spot\\s+ify|spotify)\\b") to "Spotify",
        Regex("(?i)\\b(?:tick\\s+tock|tik\\s+tok|tic\\s+tac|tiktok)\\b") to "TikTok",
        Regex("(?i)\\bface\\s+book\\b") to "Facebook",
        Regex("(?i)\\binsta\\s+gram\\b") to "Instagram",
        Regex("(?i)\\btele\\s+gram\\b") to "Telegram",
        Regex("(?i)\\bsnap\\s+chat\\b") to "Snapchat",
        Regex("(?i)\\blinked\\s+in\\b") to "LinkedIn",
        Regex("(?i)\\b(?:g\\s+mail|gee\\s+mail|gmail)\\b") to "Gmail",
        Regex("(?i)\\bplay\\s+store\\b") to "Play Store",
        Regex("(?i)\\b(?:chat\\s+g\\s+p\\s+t|chat\\s+gee\\s+pee\\s+tee|chatgpt)\\b") to "ChatGPT",
        Regex("(?i)\\bcap\\s+cut\\b") to "CapCut",
        Regex("(?i)\\bgoogle\\s+maps\\b") to "Google Maps",
        Regex("(?i)\\bgoogle\\s+chrome\\b") to "Google Chrome",

        // Preposition homophone rescues where grammar is unambiguous
        Regex("(?i)\\b(alarm|timer)\\s+four\\s+(?=(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|twenty|thirty|forty|fifty|sixty|ninety|half|quarter|\\d)\\b)") to "$1 for ",
        Regex("(?i)\\b(brightness|volume)\\s+(?:two|too)\\s+(?=(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|max|maximum|min|minimum|full|half|quarter|\\d)\\b)") to "$1 to "
    )

    private val EXPLICIT_BODY_MARKER = Regex(
        "(?i)\\b(?:that\\s+says|which\\s+says|that\\s+reads|to\\s+say|saying|with\\s+the\\s+message|with\\s+the\\s+text|with\\s+a\\s+message)\\b"
    )

    private val MESSAGE_THAT_MARKER = Regex(
        "(?i)^(?:(?:hey|hi|okay|ok)\\s+ema[\\s,]+)?(?:please\\s+|could\\s+you\\s+|can\\s+you\\s+|i\\s+need\\s+to\\s+|i\\s+want\\s+to\\s+)?" +
            "(?:(?:on|in|using)\\s+[\\w'\\- ]+?\\s+)?(?:message|text|tell|inform|let|whatsapp|send)\\b[^,;]*?\\bthat\\b"
    )

    private val PACKAGE_SPELLING = Regex("(?i)\\b(package\\s+spelling\\s+)([a-z0-9 ]+)$")

    private val NON_APP_PHRASES = setOf(
        "the morning", "the afternoon", "the evening", "night", "my way", "my phone",
        "this phone", "the screen", "screen", "the background", "weekdays", "weekends",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "today", "tomorrow", "tonight", "time", "full", "half", "sim 1", "sim 2",
        "wifi", "wi-fi", "bluetooth", "airplane mode", "do not disturb", "flashlight"
    )

    /**
     * Normalize a raw decoded transcript into clean, natural written text.
     */
    fun normalize(
        raw: String,
        profile: EmaRecognitionProfile = EmaRecognitionProfile.COMMAND,
        context: EmaSpeechContext = EmaSpeechContext.EMPTY
    ): String {
        val (stripped, _) = EmaVoskJsonParser.cleanTokens(raw)
        if (stripped.isEmpty()) return ""

        // 1. Exact owner shortcut trigger wins verbatim before any rewriting.
        val normalizedWhole = EmaAppResolver.normalize(stripped)
        if (normalizedWhole.isNotEmpty()) {
            context.shortcuts.firstOrNull { EmaAppResolver.normalize(it) == normalizedWhole }
                ?.let { return it.trim() }
        }

        // 2. Preserve "package spelling P H O R M I" verbatim at the end of shortcut teaching.
        val pkgMatch = PACKAGE_SPELLING.find(stripped)
        if (pkgMatch != null) {
            val head = stripped.substring(0, pkgMatch.range.first).trimEnd()
            val prefixLabel = pkgMatch.groupValues[1].trim()
            val letters = pkgMatch.groupValues[2].trim().split(Regex("\\s+"))
                .joinToString(" ") { it.uppercase(Locale.ROOT) }
            val normalizedHead = normalizePronouns(head)
            return if (normalizedHead.isEmpty()) "$prefixLabel $letters" else "$normalizedHead $prefixLabel $letters"
        }

        if (profile == EmaRecognitionProfile.DICTATION) {
            return normalizeDictation(stripped)
        }

        // 3. Remove leading hesitation fillers ("um open youtube" -> "open youtube").
        val dehesitated = stripLeadingHesitations(stripped)
        if (dehesitated.isEmpty()) return ""

        // Re-check shortcut after stripping a leading "um"/"uh".
        val normAfterFiller = EmaAppResolver.normalize(dehesitated)
        context.shortcuts.firstOrNull { EmaAppResolver.normalize(it) == normAfterFiller }
            ?.let { return it.trim() }

        // 4. Split command portion from any protected free-text message/search/reminder tail.
        val (commandHead, protectedBody) = splitCommandAndBody(dehesitated)

        // 5. Transform the command head safely.
        var head = commandHead
        for ((pattern, replacement) in COMMAND_PHRASE_FIXES) {
            head = pattern.replace(head, replacement)
        }
        head = normalizeTimesAndNumbers(head, inBody = false)
        head = fixUnitConversionPreposition(head)
        head = groundSlots(head, context)
        head = normalizePronouns(head)

        if (protectedBody.isEmpty()) {
            return head.replace(Regex("\\s+"), " ").trim()
        }

        // 6. Protected content body: keep wording intact; apply only safe pronoun,
        //    explicit multi-word punctuation and clock/multi-word number formatting.
        var body = protectedBody
        body = normalizeTimesAndNumbers(body, inBody = true)
        body = applyExplicitPunctuation(body, fullDictation = false)
        body = normalizePronouns(body)

        return (head.trimEnd() + " " + body.trimStart())
            .replace(Regex("\\s+([?!.,;:])"), "$1")
            .replace(Regex(" {2,}"), " ")
            .trim()
    }

    /** Lightweight normalization for live streaming partials so they read naturally. */
    fun normalizePartial(raw: String, profile: EmaRecognitionProfile = EmaRecognitionProfile.COMMAND): String {
        val (stripped, _) = EmaVoskJsonParser.cleanTokens(raw)
        if (stripped.isEmpty()) return ""
        return normalize(stripped, profile, EmaSpeechContext.EMPTY)
    }

    private fun normalizeDictation(text: String): String {
        var out = normalizeTimesAndNumbers(text, inBody = true)
        out = applyExplicitPunctuation(out, fullDictation = true)
        out = normalizePronouns(out)
        out = out
            .replace(Regex("[ \\t]+([?!.,;:])"), "$1")
            .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
        return capitalizeSentences(out)
    }

    private fun stripLeadingHesitations(text: String): String {
        val words = text.split(Regex("\\s+"))
        var start = 0
        while (start < words.size && words[start].lowercase().trim(',', '.', '!') in LEADING_HESITATIONS) {
            start++
        }
        return if (start >= words.size) "" else words.subList(start, words.size).joinToString(" ")
    }

    private fun splitCommandAndBody(text: String): Pair<String, String> {
        // Explicit marker ("saying ...", "that says ...", "to say ...").
        EXPLICIT_BODY_MARKER.find(text)?.let { m ->
            val end = m.range.last + 1
            return text.substring(0, end) to text.substring(end)
        }
        // "message Daniel on WhatsApp that I am coming home"
        MESSAGE_THAT_MARKER.find(text)?.let { m ->
            val end = m.range.last + 1
            if (end < text.length) return text.substring(0, end) to text.substring(end)
        }
        // "search [on YouTube] for <query>" — keep the query free-text.
        Regex("(?i)^(?:(?:open|launch)\\s+[\\w'\\- ]+?\\s+(?:and\\s+|then\\s+)?)?(?:search|look\\s+up|find)(?:\\s+[\\w'\\- ]+?)?\\s+for\\b")
            .find(text)?.let { m ->
                val end = m.range.last + 1
                if (end < text.length) return text.substring(0, end) to text.substring(end)
            }
        // "type <text>" — everything after "type" is literal dictation.
        Regex("(?i)^(?:(?:hey|hi|okay|ok)\\s+(?:ema|emma)[\\s,]+)?(?:please\\s+)?type\\b").find(text)?.let { m ->
            val end = m.range.last + 1
            if (end < text.length) return text.substring(0, end) to text.substring(end)
        }
        return text to ""
    }

    /**
     * Convert spoken clock expressions, decimals, compound numbers and
     * percentages into digits without touching English idioms or ordinals.
     */
    fun normalizeTimesAndNumbers(input: String, inBody: Boolean = false): String {
        val rawTokens = input.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (rawTokens.isEmpty()) return input

        // Collapse split "o clock" -> "o'clock", "per cent" -> "percent", and
        // standalone single-letter "a m" / "p m" -> "am" / "pm".
        val tokens = ArrayList<String>(rawTokens.size)
        var idx = 0
        while (idx < rawTokens.size) {
            val cur = rawTokens[idx]
            val low = cur.lowercase()
            val nextLow = rawTokens.getOrNull(idx + 1)?.lowercase()
            when {
                (low == "a" || low == "a.") && (nextLow == "m" || nextLow == "m.") -> {
                    tokens += "am"
                    idx += 2
                }
                (low == "p" || low == "p.") && (nextLow == "m" || nextLow == "m.") -> {
                    tokens += "pm"
                    idx += 2
                }
                low == "per" && nextLow == "cent" -> {
                    tokens += "percent"
                    idx += 2
                }
                low == "o" && nextLow == "clock" -> {
                    tokens += "o'clock"
                    idx += 2
                }
                else -> {
                    tokens += cur
                    idx++
                }
            }
        }

        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val low = tokens[i].lowercase()
            val prevLow = out.lastOrNull()?.lowercase()

            // Preserve "half past six" / "quarter past seven" / "quarter to eight" intact.
            if ((low == "half" || low == "quarter") &&
                tokens.getOrNull(i + 1)?.lowercase() in setOf("past", "after", "to", "before")
            ) {
                out += tokens[i]
                out += tokens[i + 1]
                if (i + 2 < tokens.size) {
                    out += tokens[i + 2]
                    i += 3
                } else {
                    i += 2
                }
                continue
            }

            // 1. Spoken clock time:
            //    - "<hour 1..12> oh <1..9> [am|pm]"       -> "7:05 pm"
            //    - "<hour 1..23> <minute 10..59> [am|pm]" -> "6:30 am"
            //    - "<hour 1..12> o'clock [am|pm]"         -> "7:00 [am|pm]"
            val clockMatch = matchSpokenClock(tokens, i, prevLow)
            if (clockMatch != null) {
                out += clockMatch.formatted
                i = clockMatch.nextIndex
                continue
            }

            // 2. Spoken cardinal / compound / decimal number span + optional percent.
            val numMatch = matchSpokenNumber(tokens, i, prevLow, inBody)
            if (numMatch != null) {
                val nextLow = tokens.getOrNull(numMatch.nextIndex)?.lowercase()
                if (nextLow == "percent") {
                    out += "${numMatch.formatted}%"
                    i = numMatch.nextIndex + 1
                } else if (nextLow == "o'clock") {
                    out += "${numMatch.formatted}:00"
                    i = numMatch.nextIndex + 1
                } else {
                    out += numMatch.formatted
                    i = numMatch.nextIndex
                }
                continue
            }

            // 3. Already-numeric token followed by "percent" -> "75%".
            if (low.matches(Regex("\\d+(?:\\.\\d+)?")) && tokens.getOrNull(i + 1)?.lowercase() == "percent") {
                out += "${tokens[i]}%"
                i += 2
                continue
            }

            out += tokens[i]
            i++
        }

        return out.joinToString(" ")
    }

    private data class SpanResult(val formatted: String, val nextIndex: Int)

    private fun matchSpokenClock(tokens: List<String>, start: Int, prevLow: String?): SpanResult? {
        val hour = parseSmallHour(tokens[start].lowercase()) ?: return null
        val second = tokens.getOrNull(start + 1)?.lowercase() ?: return null

        // Pattern A: "seven oh five [pm]" -> "7:05 [pm]"
        if (hour in 1..23 && (second == "oh" || second == "o" || second == "zero")) {
            val unitWord = tokens.getOrNull(start + 2)?.lowercase()
            val minuteUnit = unitWord?.let { ONES[it] }?.toInt()
            if (minuteUnit != null && minuteUnit in 1..9) {
                val mer = matchMeridiem(tokens, start + 3)
                val base = "$hour:0$minuteUnit"
                return if (mer != null) SpanResult("$base ${mer.first}", mer.second)
                else SpanResult(base, start + 3)
            }
        }

        // Pattern B: "seven o'clock [am|pm]" -> "7:00 [am|pm]"
        if (hour in 1..12 && second == "o'clock") {
            val mer = matchMeridiem(tokens, start + 2)
            return if (mer != null) SpanResult("$hour:00 ${mer.first}", mer.second)
            else SpanResult("$hour:00", start + 2)
        }

        // Pattern C: "six thirty [am|pm]" / "eleven forty five [pm]" / "six twenty five [am]"
        if (hour in 0..23) {
            val minuteMatch = parseClockMinute(tokens, start + 1)
            if (minuteMatch != null) {
                val (minute, afterMin) = minuteMatch
                val mer = matchMeridiem(tokens, afterMin)
                val timeContext = prevLow in setOf("at", "for", "by", "until", "till", "around", "before", "after", "and", "or", "to", "from", "alarm", "wake")
                val inThePart = tokens.getOrNull(afterMin)?.lowercase() == "in" &&
                    tokens.getOrNull(afterMin + 1)?.lowercase() == "the" &&
                    tokens.getOrNull(afterMin + 2)?.lowercase() in setOf("morning", "afternoon", "evening")
                val atNight = tokens.getOrNull(afterMin)?.lowercase() == "at" &&
                    tokens.getOrNull(afterMin + 1)?.lowercase() == "night"
                // Guard against numeric expressions like "six thirty percent" or "two twenty five miles".
                val afterWord = tokens.getOrNull(afterMin)?.lowercase()
                val followedByNonTimeUnit = afterWord in NUMERIC_FOLLOWERS && afterWord != "am" && afterWord != "pm"
                if (!followedByNonTimeUnit && (mer != null || inThePart || atNight || timeContext)) {
                    val mm = minute.toString().padStart(2, '0')
                    return if (mer != null) SpanResult("$hour:$mm ${mer.first}", mer.second)
                    else SpanResult("$hour:$mm", afterMin)
                }
            }
        }

        // Pattern D: "seven am" / "nine pm" (not "seven I am going")
        if (hour in 1..12) {
            val mer = matchMeridiem(tokens, start + 1)
            if (mer != null) {
                return SpanResult("$hour ${mer.first}", mer.second)
            }
        }

        return null
    }

    private fun parseSmallHour(word: String): Int? {
        word.toIntOrNull()?.takeIf { it in 0..23 }?.let { return it }
        ONES[word]?.toInt()?.let { return it }
        if (word == "twenty") return 20
        return null
    }

    private fun parseClockMinute(tokens: List<String>, start: Int): Pair<Int, Int>? {
        val w1 = tokens.getOrNull(start)?.lowercase() ?: return null
        // 10..19: "six fifteen", "seven ten"
        val teen = ONES[w1]?.toInt()
        if (teen != null && teen in 10..19) return teen to (start + 1)
        // 20..59: "six thirty", "six forty five"
        val ten = TENS[w1]?.toInt()
        if (ten != null && ten in 20..50) {
            val w2 = tokens.getOrNull(start + 1)?.lowercase()
            val unit = w2?.let { ONES[it]?.toInt() }
            return if (unit != null && unit in 1..9) (ten + unit) to (start + 2)
            else ten to (start + 1)
        }
        return null
    }

    private fun matchMeridiem(tokens: List<String>, index: Int): Pair<String, Int>? {
        val w = tokens.getOrNull(index)?.lowercase()?.trimEnd('.', ',', '!', '?') ?: return null
        if (w == "pm") return "pm" to (index + 1)
        if (w == "am") {
            // Never absorb the verb "am" ("at 7 am going" is rare, "7 I am going" has "I" between).
            val next = tokens.getOrNull(index + 1)?.lowercase()?.trimEnd('.', ',', '!', '?')
            if (next != null && next in VERB_AM_FOLLOWERS) return null
            return "am" to (index + 1)
        }
        return null
    }

    private fun matchSpokenNumber(
        tokens: List<String>,
        start: Int,
        prevLow: String?,
        inBody: Boolean
    ): SpanResult? {
        val first = tokens[start].lowercase()
        val isNumStart = first in ONES || first in TENS ||
            ((first == "a" || first == "an") && tokens.getOrNull(start + 1)?.lowercase() in SCALES)
        if (!isNumStart) return null

        // Parse integer portion greedily while tracking valid English number grammar.
        var total = 0L
        var current = 0L
        var i = start
        var consumedWords = 0
        var lastCategory = "" // "one", "teen", "ten", "hundred", "bigscale", "article"

        while (i < tokens.size) {
            val w = tokens[i].lowercase()

            // Optional "and" purely inside a scale ("one hundred and twenty five", "two thousand and six").
            // Never cross "six and six thirty" (lastCategory is "one", not "hundred"/"bigscale").
            if (w == "and" && (lastCategory == "hundred" || lastCategory == "bigscale")) {
                val next = tokens.getOrNull(i + 1)?.lowercase()
                val next2 = tokens.getOrNull(i + 2)?.lowercase()
                val followedByClock = next != null && parseSmallHour(next) != null &&
                    (parseClockMinute(tokens, i + 2) != null || next2 in setOf("am", "pm", "o'clock", "oh"))
                if (!followedByClock && next != null && (next in ONES || next in TENS)) {
                    i++
                    continue
                }
                break
            }

            if ((w == "a" || w == "an") && i == start && tokens.getOrNull(i + 1)?.lowercase() in SCALES) {
                current = 1L
                lastCategory = "article"
                i++
                consumedWords++
                continue
            }

            val oneVal = ONES[w]
            if (oneVal != null) {
                val isTeen = oneVal >= 10L
                val canFollow = when (lastCategory) {
                    "" -> true
                    "ten" -> oneVal in 1L..9L // "twenty five", not "twenty fifteen"
                    "hundred", "bigscale" -> true // "one hundred five", "two thousand five"
                    else -> false // "six seven" are two separate numbers, not summed
                }
                if (!canFollow) break
                current += oneVal
                lastCategory = if (isTeen) "teen" else "one"
                i++
                consumedWords++
                continue
            }

            val tenVal = TENS[w]
            if (tenVal != null) {
                val canFollow = lastCategory == "" || lastCategory == "hundred" || lastCategory == "bigscale"
                if (!canFollow) break
                current += tenVal
                lastCategory = "ten"
                i++
                consumedWords++
                continue
            }

            if (w == "hundred") {
                // Accepts "one hundred" (1*100), "twenty four hundred" (24*100), "a hundred" (1*100).
                if (current <= 0L || current >= 100L) break
                current *= 100L
                lastCategory = "hundred"
                i++
                consumedWords++
                continue
            }

            val bigScale = SCALES[w]?.takeIf { it >= 1_000L }
            if (bigScale != null) {
                if (current <= 0L) break
                // Prevent ascending big scales ("one thousand million" stops cleanly).
                if (total > 0L && bigScale >= highestScaleIn(total)) break
                total += current * bigScale
                current = 0L
                lastCategory = "bigscale"
                i++
                consumedWords++
                continue
            }

            break
        }

        if (consumedWords == 0 || lastCategory == "article") return null
        val integerValue = total + current

        // Optional decimal fraction: "point five", "point zero two five"
        var endIndex = i
        var decimalDigits = ""
        if (tokens.getOrNull(endIndex)?.lowercase() == "point") {
            val digits = StringBuilder()
            var d = endIndex + 1
            while (d < tokens.size) {
                val dw = tokens[d].lowercase()
                val single = SINGLE_DIGITS[dw]
                if (single != null) {
                    digits.append(single)
                    d++
                } else if (digits.isEmpty() && (dw in ONES || dw in TENS)) {
                    // "three point twenty five" -> "3.25"
                    val v = ONES[dw] ?: TENS[dw]!!
                    val nextUnit = if (dw in TENS) tokens.getOrNull(d + 1)?.lowercase()?.let { ONES[it] }?.takeIf { it in 1..9 } else null
                    if (nextUnit != null) {
                        digits.append(v + nextUnit)
                        d += 2
                    } else {
                        digits.append(v)
                        d++
                    }
                    break
                } else {
                    break
                }
            }
            if (digits.isNotEmpty()) {
                decimalDigits = digits.toString()
                endIndex = d
            }
        }

        val multiWord = (endIndex - start) >= 2
        val nextLow = tokens.getOrNull(endIndex)?.lowercase()?.trimEnd('.', ',', '!', '?')

        // Protect natural English uses of single-word "one" ("the longest one",
        // "another one", "one action at a time", "one by one").
        if (!multiWord && first == "one") {
            val explicitlyNumeric = nextLow in NUMERIC_FOLLOWERS || prevLow in setOf(
                "plus", "minus", "times", "over", "mod", "modulo", "sim", "number", "level", "channel", "convert"
            )
            if (!explicitlyNumeric) return null
            if (prevLow in PRONOUN_ONE_PREV || nextLow in PRONOUN_ONE_NEXT) return null
        }

        // Inside free-text message bodies, convert single-word numbers only when
        // anchored by a clear numeric unit/context ("in 10 minutes", "50%"), so
        // casual idioms ("give me five", "high five", "table for two") stay natural.
        if (inBody && !multiWord) {
            val anchored = nextLow in NUMERIC_FOLLOWERS || prevLow in setOf("in", "at", "around", "by", "sim", "plus", "minus", "times")
            if (!anchored) return null
        }

        // In commands, convert single-word numbers when in any numeric/time/setting/math slot,
        // or >= 2 when preceded/followed by numeric context.
        if (!inBody && !multiWord) {
            val anchored = nextLow in NUMERIC_FOLLOWERS ||
                prevLow in NUMERIC_LEADERS ||
                (nextLow == "and" && tokens.getOrNull(endIndex + 1)?.lowercase()?.let { it in ONES || it in TENS || it.firstOrNull()?.isDigit() == true } == true) ||
                prevLow?.lastOrNull()?.isDigit() == true
            if (!anchored) return null
        }

        val formatted = if (decimalDigits.isEmpty()) integerValue.toString() else "$integerValue.$decimalDigits"
        return SpanResult(formatted, endIndex)
    }

    private fun highestScaleIn(value: Long): Long = when {
        value >= 1_000_000_000L -> 1_000_000_000L
        value >= 1_000_000L -> 1_000_000L
        else -> 1_000L
    }

    private fun fixUnitConversionPreposition(text: String): String {
        // Rescue "convert 5 kilometers 2 miles" / "two miles" -> "to miles"
        return Regex(
            "(?i)\\b(convert\\s+[\\d.]+\\s+(?:degrees\\s+)?[a-z]+)\\s+(?:2|two|too)\\s+(?=(?:degrees\\s+)?[a-z]+\\b)"
        ).replace(text, "$1 to ")
    }

    /**
     * Slot-scoped phonetic grounding against installed app labels and contact
     * display names. Runs only on isolated slot spans, never on free text, and
     * leaves ambiguous slots untouched.
     */
    private fun groundSlots(head: String, context: EmaSpeechContext): String {
        var out = head
        if (context.installedApps.isNotEmpty()) {
            // "open <app>" / "launch <app>"
            out = replaceSlot(
                text = out,
                pattern = Regex("(?i)\\b(open|launch|start)\\s+(?:the\\s+)?([a-z0-9' ]{2,34}?)(?:\\s+app)?(?=$|\\s+(?:and|then|to|search|find|look|play|message|text|send|go)\\b|[,;])"),
                candidates = context.installedApps,
                minScore = 0.88,
                minMargin = 0.09,
                skipSet = NON_APP_PHRASES + context.shortcuts.map { it.lowercase() }
            )
            // "on <app>" / "in <app>" / "using <app>"
            out = replaceSlot(
                text = out,
                pattern = Regex("(?i)\\b(on|in|using|via|through)\\s+([a-z0-9' ]{2,34}?)(?=$|\\s+(?:and|then|message|text|send|tell|inform|let|saying|that|to|for|search|find|play)\\b|[,;])"),
                candidates = context.installedApps,
                minScore = 0.89,
                minMargin = 0.09,
                skipSet = NON_APP_PHRASES + context.shortcuts.map { it.lowercase() }
            )
        }
        val people = if (context.ownerAliases.isEmpty()) context.contactNames
        else context.contactNames + context.ownerAliases
        if (people.isNotEmpty()) {
            // "call <contact>" / "dial <contact>" / "phone <contact>"
            out = replaceSlot(
                text = out,
                pattern = Regex("(?i)\\b(call|dial|phone|ring)\\s+([a-z' ]{2,34}?)(?=$|\\s+(?:with|using|on|via|and|then|now|please)\\b|[,;])"),
                candidates = people,
                minScore = 0.89,
                minMargin = 0.10,
                skipSet = setOf("me", "him", "her", "them", "back", "my wife", "my husband", "my mum", "my dad")
            )
            // "message/text/tell <contact> on/saying/that"
            out = replaceSlot(
                text = out,
                pattern = Regex("(?i)\\b(message|text|tell|inform|whatsapp)\\s+([a-z' ]{2,34}?)(?=\\s+(?:on|in|using|via|through|saying|that|to\\s+say|with\\s+the)\\b)"),
                candidates = people,
                minScore = 0.89,
                minMargin = 0.10,
                skipSet = setOf("me", "him", "her", "them", "my wife", "my husband")
            )
            // "send <contact> a message" / "send a message to <contact>"
            out = replaceSlot(
                text = out,
                pattern = Regex("(?i)\\b(send\\s+(?:a\\s+(?:\\w+\\s+)?message\\s+to|to))\\s+([a-z' ]{2,34}?)(?=\\s+(?:on|in|using|via|saying|that|to\\s+say)\\b|$)"),
                candidates = people,
                minScore = 0.89,
                minMargin = 0.10,
                skipSet = setOf("me", "him", "her", "them")
            )
        }
        return out
    }

    private fun replaceSlot(
        text: String,
        pattern: Regex,
        candidates: List<String>,
        minScore: Double,
        minMargin: Double,
        skipSet: Set<String>
    ): String {
        return pattern.replace(text) { match ->
            val lead = match.groupValues[1]
            val slot = match.groupValues[2].trim()
            val slotLow = slot.lowercase()
            if (slotLow in skipSet || slot.any { it.isDigit() }) {
                match.value
            } else {
                val resolved = EmaPhoneticMatcher.bestUnambiguousMatch(
                    spoken = slot,
                    candidates = candidates,
                    minScore = minScore,
                    minMargin = minMargin
                )
                if (resolved != null) "$lead ${resolved.canonical}" else match.value
            }
        }
    }

    private fun applyExplicitPunctuation(text: String, fullDictation: Boolean): String {
        var out = text
            .replace(Regex("(?i)\\bnew\\s+paragraph\\b"), "\n\n")
            .replace(Regex("(?i)\\bnew\\s+line\\b"), "\n")
            .replace(Regex("(?i)\\bquestion\\s+mark\\b"), "?")
            .replace(Regex("(?i)\\bexclamation\\s+(?:mark|point)\\b"), "!")
            .replace(Regex("(?i)\\bfull\\s+stop\\b"), ".")
        if (fullDictation) {
            val periodNounModifiers = setOf(
                "a", "the", "this", "that", "time", "grace", "waiting",
                "trial", "billing", "notice", "short", "long", "brief", "extended"
            )
            out = Regex("(?i)(?:(\\p{L}+)\\s+)?\\bperiod\\b(?=\\s|$)").replace(out) { m ->
                val prev = m.groupValues[1]
                when {
                    prev.isEmpty() -> "."
                    prev.lowercase() in periodNounModifiers -> m.value
                    else -> "$prev."
                }
            }
            out = out
                .replace(Regex("(?i)\\bsemicolon\\b"), ";")
                .replace(Regex("(?i)\\bsemi\\s+colon\\b"), ";")
                .replace(Regex("(?i)\\bcomma\\b"), ",")
                .replace(Regex("(?i)\\bcolon\\b"), ":")
        }
        return out
    }

    private fun normalizePronouns(text: String): String = text
        .replace(Regex("(?<![\\p{L}'])i(?![\\p{L}'])"), "I")
        .replace(Regex("(?<![\\p{L}'])i'm(?![\\p{L}])"), "I'm")
        .replace(Regex("(?<![\\p{L}'])i'll(?![\\p{L}])"), "I'll")
        .replace(Regex("(?<![\\p{L}'])i've(?![\\p{L}])"), "I've")
        .replace(Regex("(?<![\\p{L}'])i'd(?![\\p{L}])"), "I'd")

    private fun capitalizeSentences(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var capNext = true
        for (ch in text) {
            if (capNext && ch.isLetter()) {
                sb.append(ch.uppercaseChar())
                capNext = false
            } else {
                sb.append(ch)
                if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                    capNext = true
                } else if (!ch.isWhitespace()) {
                    capNext = false
                }
            }
        }
        return sb.toString()
    }
}
