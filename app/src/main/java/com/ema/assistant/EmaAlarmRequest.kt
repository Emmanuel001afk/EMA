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
 *  - whether the request repeats (weekdays / named days -> EXTRA_DAYS),
 *  - whether it names a calendar day ("tomorrow") that the clock app cannot
 *    represent, in which case EMA schedules its own exact alarm.
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

    fun parse(value: String, nowMillis: Long = System.currentTimeMillis()): Parsed? {
        val text = value.trim().trimEnd('.', '!')
        if (text.isBlank()) return null
        val lower = text.lowercase()

        val weekdays = Regex("\\bweekdays?\\b|\\bmonday\\s+(?:to|through|thru|-)\\s+friday\\b").containsMatchIn(lower)
        val weekends = Regex("\\bweekends?\\b").containsMatchIn(lower)
        val daily = Regex("\\bevery\\s*day\\b|\\bdaily\\b|\\beach\\s+day\\b").containsMatchIn(lower)
        val namedDays = DAYS.keys.filter { day -> Regex("\\b$day\\b").containsMatchIn(lower) }.mapNotNull { DAYS[it] }
        val days = when {
            weekdays -> listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
            weekends -> listOf(Calendar.SATURDAY, Calendar.SUNDAY)
            daily -> DAYS.values.toList()
            namedDays.isNotEmpty() -> namedDays.distinct()
            else -> null
        }
        val explicitDate = Regex("\\btomorrow\\b|\\bnext\\s+(?:day|week)\\b").containsMatchIn(lower) ||
            namedDays.isNotEmpty()

        // Remove the repeat wording that the time parser does not understand,
        // then split a list of times ("6 and 6:30", "7, 8 or 9").
        val timeText = lower
            .replace(Regex("\\b(?:every\\s*day|daily|each\\s+day|weekdays?|weekends?)\\b"), " ")
            .replace(Regex("\\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"), " ")
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
            val moment = EmaTimeParser.parse(spoken, nowMillis) ?: continue
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
