package com.ema.assistant

import java.util.Calendar

/**
 * One spoken alarm request, resolved before anything is sent to the clock app.
 *
 * The clock app's ACTION_SET_ALARM contract is narrow: one hour/minute, an
 * optional repeat-day set, and no calendar date. EMA therefore has to decide
 * three things itself, deterministically:
 *
 *  - how many alarms were asked for ("6 and 6:30 am" is two, not one),
 *  - whether the request repeats (weekdays / "every Monday" -> EXTRA_DAYS),
 *  - whether it names a calendar day ("tomorrow", "next Monday") that the
 *    clock app cannot represent, in which case EMA schedules its own exact
 *    alarm on that date.
 *
 * A single day name is NOT a repeat. "next Monday" and "on Friday" are one
 * alarm on that date; only "every Monday", "Mondays", "weekdays", "weekends"
 * and "daily" repeat.
 *
 * Pure Kotlin so every rule is unit tested without a device.
 */
object EmaAlarmRequest {

    data class Parsed(
        val times: List<EmaTimeParser.Moment>,
        /** Calendar days for EXTRA_DAYS, or null when it is a one-shot alarm. */
        val days: List<Int>?,
        /** A named day / "tomorrow": not expressible as a clock-app alarm date. */
        val explicitDate: Boolean,
        val spoken: String
    )

    private val DAYS = mapOf(
        "sunday" to Calendar.SUNDAY, "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY
    )

    /** "once", "only" and "once only" all mean a single alarm. */
    private val ONCE_WORDS = Regex("\\b(?:once\\s*only|only\\s*once|once|only)\\b")

    fun parse(value: String, nowMillis: Long = System.currentTimeMillis()): Parsed? {
        val text = value.trim().trimEnd('.', '!')
        if (text.isBlank()) return null
        val lower = text.lowercase()

        // "once" and "only" are removed before the time is read, so they can
        // never become part of it ("7 once" is 7 o'clock, once).
        val cleaned = lower.replace(ONCE_WORDS, " ").replace(Regex("\\s+"), " ").trim()

        val weekdays = Regex("\\bweekdays?\\b|\\bmonday\\s+(?:to|through|thru|-)\\s+friday\\b").containsMatchIn(cleaned)
        val weekends = Regex("\\bweekends?\\b").containsMatchIn(cleaned)
        val daily = Regex("\\bevery\\s*day\\b|\\bdaily\\b|\\beach\\s+day\\b").containsMatchIn(cleaned)
        val dayWords = DAYS.keys.filter { day -> Regex("\\b$day\\b").containsMatchIn(cleaned) }
        val namedDays = dayWords.mapNotNull { DAYS[it] }
        // A day name only repeats when the user asked for a repeat. "every
        // Monday" and "Mondays" repeat; "next Monday" and "on Friday" are one
        // alarm on that date.
        val pluralDay = Regex("\\b(?:mondays|tuesdays|wednesdays|thursdays|fridays|saturdays|sundays)\\b")
            .containsMatchIn(cleaned)
        val everyNamedDay = Regex("\\bevery\\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")
            .containsMatchIn(cleaned)
        val repeating = weekdays || weekends || daily || (namedDays.isNotEmpty() && (pluralDay || everyNamedDay))
        val days = when {
            weekdays -> listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
            weekends -> listOf(Calendar.SATURDAY, Calendar.SUNDAY)
            daily -> DAYS.values.toList()
            repeating -> namedDays.distinct()
            else -> null
        }

        val tomorrow = Regex("\\btomorrow\\b|\\bnext\\s+day\\b").containsMatchIn(cleaned)
        // One alarm on a named date: the clock app cannot express a date, so
        // EMA schedules its own exact alarm instead.
        val oneTimeDay = !repeating && (tomorrow || namedDays.isNotEmpty())
        val explicitDate = oneTimeDay || Regex("\\bnext\\s+week\\b").containsMatchIn(cleaned)

        // The repeat wording and the day names are removed from the time text:
        // the clock app repeats on its own, and a one-time date is resolved by
        // EMA below.
        val timeText = cleaned
            .replace(Regex("\\b(?:every\\s*day|daily|each\\s+day|weekdays?|weekends?)\\b"), " ")
            .replace(
                Regex(
                    "\\b(?:tomorrow|next\\s+day|next\\s+week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"
                ),
                " "
            )
            .replace(Regex("\\bevery\\b"), " ")
            .replace(Regex("\\bfrom\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { text }

        val pieces = timeText.split(Regex("(?i),|\\band\\b|\\bor\\b"))
            .map { it.trim().removePrefix("at ").trim() }
            .filter { it.isNotBlank() }
        val hasMeridiem = pieces.any { piece -> Regex("(?i)\\b(?:am|pm|a m|p m)\\b").containsMatchIn(piece) }
        // "6 and 6:30 am" means both are AM; carry a single trailing marker
        // across bare list items, exactly as a person would understand it.
        val trailing = Regex("(?i)\\b(am|pm|a m|p m)\\b").find(pieces.lastOrNull().orEmpty())?.groupValues?.get(1)

        val moments = LinkedHashSet<EmaTimeParser.Moment>()
        for (piece in pieces) {
            val spoken = if (
                trailing != null && hasMeridiem &&
                !Regex("(?i)\\b(?:am|pm|a m|p m)\\b").containsMatchIn(piece)
            ) "$piece $trailing" else piece
            // A one-time date is kept with the time so the moment really falls
            // on that day, not on today.
            val dated = if (oneTimeDay) {
                val dayContext = if (tomorrow) "tomorrow" else dayWords.firstOrNull().orEmpty()
                if (dayContext.isBlank()) spoken else "$spoken $dayContext"
            } else spoken
            val moment = EmaTimeParser.parse(dated, nowMillis) ?: continue
            if (moment.kind == EmaTimeParser.Moment.Kind.CLOCK || moment.kind == EmaTimeParser.Moment.Kind.DAY_NAME ||
                moment.kind == EmaTimeParser.Moment.Kind.DAY_PART
            ) {
                moments += moment
            }
        }
        if (moments.isEmpty()) return null
        return Parsed(moments.toList(), days, explicitDate, text)
    }
}
