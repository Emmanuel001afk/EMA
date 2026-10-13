package com.ema.assistant

import java.util.Calendar
import java.util.Locale

/**
 * Deterministic time understanding for alarms, timers, reminders and routines.
 *
 * Pure function of (spoken text, current time) so it is fully unit testable and
 * needs no AI and no network. Handles the forms people actually say:
 *
 *   clock      "7", "7 am", "7:30 pm", "19:30", "half past six", "quarter to seven"
 *   relative   "in 10 minutes", "in 2 hours", "after 30 seconds", "45 mins from now"
 *   day parts  "noon", "midnight", "tonight", "this evening", "this afternoon"
 *   days       "tomorrow", "tomorrow morning", "monday", "next friday"
 *
 * Always returns a moment strictly in the future, which is what AlarmManager
 * requires; a clock time that has already passed today rolls to tomorrow.
 */
object EmaTimeParser {

    data class Moment(val millis: Long, val kind: Kind, val spoken: String) {
        enum class Kind { CLOCK, RELATIVE, DAY_PART, DAY_NAME }
    }

    data class Duration(val seconds: Int, val spoken: String)

    private val WORD_NUMBERS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fifty" to 50, "quarter" to 15, "half" to 30
    )

    private val DAY_NAMES = mapOf(
        "sunday" to Calendar.SUNDAY, "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY
    )

    fun normalize(value: String): String = value
        .lowercase(Locale.getDefault())
        .replace('\u00a0', ' ')
        .replace(".", "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** "ten" -> "10", "twenty five" -> "25". Leaves real digits untouched. */
    fun wordsToDigits(value: String): String {
        val words = normalize(value).split(' ')
        if (words.isEmpty()) return value
        // "twenty five" is one number expressed as two words.
        val joined = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val a = words[i]
            val b = words.getOrNull(i + 1)
            if (a == "twenty" || a == "thirty" || a == "forty" || a == "fifty") {
                val bv = b?.let { WORD_NUMBERS[it] }
                if (bv != null && bv in 1..9) {
                    joined += (WORD_NUMBERS.getValue(a) + bv).toString()
                    i += 2
                    continue
                }
            }
            val mapped = WORD_NUMBERS[a]
            if (mapped != null) joined += mapped.toString() else joined += a
            i++
        }
        return joined.joinToString(" ")
    }

    /** Duration form: "in 10 minutes", "30 seconds", "2 hours". */
    fun parseDuration(value: String): Duration? {
        val text = wordsToDigits(value)
        val match = Regex("""(\d+)\s*(second|sec|minute|min|hour|hr|day)s?""").find(text) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        if (amount <= 0) return null
        val seconds = when (match.groupValues[2]) {
            "second", "sec" -> amount
            "minute", "min" -> amount * 60
            "hour", "hr" -> amount * 3600
            else -> amount * 86400
        }
        return Duration(seconds, value.trim())
    }

    /**
     * Resolve any supported time expression to a future instant.
     * Returns null when the text genuinely contains no time information.
     */
    fun parse(value: String, nowMillis: Long = System.currentTimeMillis()): Moment? {
        // Two normalisations are kept: `raw` preserves "half"/"quarter" so the
        // "half past six" phrasing can be matched, `digits` converts number
        // words so arithmetic parsing works. Converting first would destroy the
        // phrase form, which is exactly why both are carried here.
        val raw = normalize(value)
        val text = wordsToDigits(value)
        if (text.isBlank()) return null

        parseDuration(text)?.let { duration ->
            val relative = Regex("""(?i)\b(?:in|after|within|from now)\b""").containsMatchIn(text) ||
                Regex("""\d""").containsMatchIn(text)
            if (relative) {
                return Moment(nowMillis + duration.seconds * 1000L, Moment.Kind.RELATIVE, value.trim())
            }
        }

        namedDayPart(text, nowMillis)?.let { return Moment(it, Moment.Kind.DAY_PART, value.trim()) }
        namedDay(text, nowMillis)?.let { return Moment(it, Moment.Kind.DAY_NAME, value.trim()) }
        clock(raw, text, nowMillis)?.let { return Moment(it, Moment.Kind.CLOCK, value.trim()) }
        return null
    }

    /** Convenience for callers that only need the epoch millis. */
    fun parseToFutureMillis(value: String, nowMillis: Long = System.currentTimeMillis()): Long? =
        parse(value, nowMillis)?.millis

    private fun clock(raw: String, text: String, nowMillis: Long): Long? {
        // "half past six" -> 6:30, "quarter to seven" -> 6:45, "quarter past nine" -> 9:15.
        // Matched against `raw` (words intact) and `text` (words already numeric).
        val phrase = Regex("""(half|quarter)\s+(past|after|to|before)\s+([a-z0-9]+)""").find(raw)
            ?: Regex("""(30|15)\s+(past|after|to|before)\s+(\d{1,2})""").find(text)
        if (phrase != null) {
            val isHalf = phrase.groupValues[1] == "half" || phrase.groupValues[1] == "30"
            val offset = if (isHalf) 30 else 15
            val hourWord = phrase.groupValues[3]
            val hour = hourWord.toIntOrNull() ?: WORD_NUMBERS[hourWord] ?: return null
            val toward = phrase.groupValues[2]
            var baseHour = hour
            val minute = if (toward == "to" || toward == "before") {
                baseHour -= 1
                60 - offset
            } else offset
            if (baseHour < 0) baseHour += 12
            val meridiem = meridiemOf(raw)
            return atTime(nowMillis, normalizeHour(baseHour, meridiem), minute)
        }

        val match = Regex("""(\d{1,2})(?::(\d{2}))?\s*(a\s*m|p\s*m|am|pm|in the morning|in the afternoon|in the evening|at night)?""")
            .find(text) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: 0
        if (minute !in 0..59) return null
        val meridiem = match.groupValues[3].replace(Regex("\\s+"), "").ifBlank { meridiemOf(raw) }
        if (hour !in 0..23) return null
        return atTime(nowMillis, normalizeHour(hour, meridiem), minute)
    }

    private fun meridiemOf(text: String): String = when {
        Regex("""\b(am|a\s*m|in the morning)\b""").containsMatchIn(text) -> "am"
        Regex("""\b(pm|p\s*m|in the afternoon|in the evening|at night|tonight)\b""").containsMatchIn(text) -> "pm"
        else -> ""
    }

    /** Apply am/pm, and disambiguate a bare 1-7 as an evening hour when the text says so. */
    private fun normalizeHour(hour: Int, meridiem: String): Int {
        if (hour !in 0..23) return hour
        return when {
            meridiem == "am" -> if (hour == 12) 0 else hour
            meridiem == "pm" -> if (hour < 12) hour + 12 else hour
            else -> hour
        }
    }

    private fun namedDayPart(text: String, nowMillis: Long): Long? {
        val hour = when {
            Regex("""\bnoon\b""").containsMatchIn(text) -> 12
            Regex("""\bmidnight\b""").containsMatchIn(text) -> 0
            Regex("""\btonight\b""").containsMatchIn(text) || Regex("""\bthis night\b""").containsMatchIn(text) -> 20
            Regex("""\bthis evening\b|\bevening\b""").containsMatchIn(text) -> 18
            Regex("""\bthis afternoon\b|\bafternoon\b""").containsMatchIn(text) -> 15
            Regex("""\bthis morning\b|\bmorning\b""").containsMatchIn(text) -> 8
            else -> return null
        }
        val minute = 0
        // Combine with an explicit clock time when both are present: "tonight at 9".
        val explicit = Regex("""(\d{1,2})(?::(\d{2}))?\s*(?:am|pm)?""").find(
            text.replace(Regex("""\b(noon|midnight|tonight|this evening|evening|this afternoon|afternoon|this morning|morning|night)\b"""), " ")
        )
        val finalHour: Int
        val finalMinute: Int
        if (explicit != null && explicit.groupValues[1].isNotBlank()) {
            var h = explicit.groupValues[1].toIntOrNull() ?: hour
            val m = explicit.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: 0
            // "tonight at 9" -> 21:00, "this afternoon at 3" -> 15:00, while
            // "this morning at 9" must stay 09:00.
            if (hour >= 18 && h in 1..11) h += 12
            else if (hour == 15 && h in 1..7) h += 12
            finalHour = h.coerceIn(0, 23)
            finalMinute = m.coerceIn(0, 59)
        } else {
            finalHour = hour
            finalMinute = minute
        }
        return atTime(nowMillis, finalHour, finalMinute)
    }

    private fun namedDay(text: String, nowMillis: Long): Long? {
        val tomorrow = Regex("""\btomorrow\b|\bnext day\b""").containsMatchIn(text)
        val dayEntry = DAY_NAMES.entries.firstOrNull { Regex("""\b${it.key}\b""").containsMatchIn(text) }
        if (!tomorrow && dayEntry == null) return null

        val timePart = text.replace(Regex("""\b(tomorrow|next day|on|the|at|by)\b"""), " ")
        val hourMinute = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(timePart)
        val part = namedDayPartOnly(text)

        val hour = when {
            hourMinute != null && hourMinute.groupValues[1].isNotBlank() -> {
                var h = hourMinute.groupValues[1].toIntOrNull() ?: 9
                val meridiem = hourMinute.groupValues[3]
                if (meridiem == "pm" && h < 12) h += 12
                if (meridiem == "am" && h == 12) h = 0
                if (meridiem.isBlank() && part != null && h in 1..7 && part >= 12) h += 12
                h
            }
            part != null -> part
            else -> 9
        }
        val minute = hourMinute?.groupValues?.get(2)?.ifBlank { "0" }?.toIntOrNull() ?: 0

        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        if (dayEntry != null) {
            val target = dayEntry.value
            val current = calendar.get(Calendar.DAY_OF_WEEK)
            var delta = target - current
            if (delta <= 0) delta += 7
            if (delta == 0) delta = 7
            calendar.add(Calendar.DAY_OF_YEAR, delta)
        } else if (tomorrow) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        calendar.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        calendar.set(Calendar.MINUTE, minute.coerceIn(0, 59))
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val result = calendar.timeInMillis
        return if (result > nowMillis) result else result + 86400000L
    }

    private fun namedDayPartOnly(text: String): Int? = when {
        Regex("""\bnoon\b""").containsMatchIn(text) -> 12
        Regex("""\bmidnight\b""").containsMatchIn(text) -> 0
        Regex("""\btonight\b|\bevening\b|\bnight\b""").containsMatchIn(text) -> 20
        Regex("""\bafternoon\b""").containsMatchIn(text) -> 15
        Regex("""\bmorning\b""").containsMatchIn(text) -> 8
        else -> null
    }

    /** Set today's clock; roll to tomorrow when the moment has already passed. */
    private fun atTime(nowMillis: Long, hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= nowMillis) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }

    fun describe(millis: Long): String =
        java.text.SimpleDateFormat("EEEE h:mm a", Locale.getDefault()).format(java.util.Date(millis))

    fun describeShort(millis: Long): String =
        java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(millis))
}
