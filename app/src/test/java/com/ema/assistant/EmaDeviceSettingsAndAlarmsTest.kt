package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Priority 4: the generic device-settings parser (no AI, no phrase list). */
class EmaDeviceSettingParserTest {

    private fun parse(text: String) = EmaSystemCommandVocabulary.parseDeviceSetting(text)

    @Test
    fun parsesDoNotDisturbInBothDirections() {
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting(EmaSystemCommandVocabulary.DeviceSetting.Kind.DND,
                EmaSystemCommandVocabulary.DeviceSetting.Action.ON),
            parse("turn on do not disturb")
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Action.OFF,
            parse("switch silent mode off")!!.action
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Kind.DND,
            parse("enable dnd")!!.kind
        )
    }

    @Test
    fun parsesBrightnessVerbsAndValues() {
        val maximum = parse("increase brightness to maximum")!!
        assertEquals(EmaSystemCommandVocabulary.DeviceSetting.Kind.BRIGHTNESS, maximum.kind)
        assertEquals(EmaSystemCommandVocabulary.DeviceSetting.Action.MAX, maximum.action)

        val percent = parse("set brightness to 40 percent")!!
        assertEquals(EmaSystemCommandVocabulary.DeviceSetting.Action.SET, percent.action)
        assertEquals(40, percent.value)

        val dim = parse("dim the screen")!!
        assertEquals(EmaSystemCommandVocabulary.DeviceSetting.Kind.BRIGHTNESS, dim.kind)
        assertEquals(EmaSystemCommandVocabulary.DeviceSetting.Action.DECREASE, dim.action)

        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Action.MIN,
            parse("lower the brightness to minimum")!!.action
        )
    }

    @Test
    fun parsesRadioTogglesWithCommonSpellings() {
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting(
                EmaSystemCommandVocabulary.DeviceSetting.Kind.AIRPLANE,
                EmaSystemCommandVocabulary.DeviceSetting.Action.ON
            ),
            parse("turn on aeroplane mode")
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Action.ON,
            parse("turn airplane mode on")!!.action
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Action.OFF,
            parse("switch off flight mode")!!.action
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Kind.WIFI,
            parse("turn wi-fi on")!!.kind
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Action.OFF,
            parse("disable bluetooth")!!.action
        )
    }

    @Test
    fun doesNotHijackUnrelatedCommands() {
        assertNull(parse("turn on the flashlight"))
        assertNull(parse("open wifi settings"))
        assertNull(parse("increase the volume"))
        assertNull(parse("play silent night"))
        assertNull(parse("open settings"))
    }
}

/** Priority 6: nearest future clock times, explicit am/pm, and alarm lists. */
class EmaAlarmRulesTest {

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.JUNE, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun hour(millis: Long): Int = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)

    @Test
    fun bareHourUsesTheNearestFutureOccurrence() {
        // 10:00 -> 19:00 is nearer than 07:00 tomorrow.
        assertEquals(19, hour(EmaTimeParser.parse("7", at(10, 10))!!.millis))
        // 20:00 -> both 07:00 and 19:00 have passed, so 07:00 tomorrow.
        assertEquals(7, hour(EmaTimeParser.parse("7", at(10, 20))!!.millis))
        assertEquals(11, Calendar.getInstance().apply {
            timeInMillis = EmaTimeParser.parse("7", at(10, 20))!!.millis
        }.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun amPmOnlyCountsNextToTheNumber() {
        // "am" in "I am" must not turn 7 into 07:00.
        assertEquals(19, hour(EmaTimeParser.parse("set an alarm for 7, I am going out", at(10, 10))!!.millis))
        // An explicit marker still wins, even with a contradicting word later.
        assertEquals(7, hour(EmaTimeParser.parse("7 am, I am sure", at(10, 10))!!.millis))
        assertEquals(19, hour(EmaTimeParser.parse("7 pm, I am sure", at(10, 10))!!.millis))
    }

    @Test
    fun resolvedAlarmsAreStatedExplicitly() {
        val now = at(10, 10)
        val evening = EmaTimeParser.parse("7", now)!!.millis
        assertTrue(EmaTimeParser.describeRelative(evening, now).startsWith("today at"))
        val tomorrowMorning = EmaTimeParser.parse("7 am", now)!!.millis
        assertTrue(EmaTimeParser.describeRelative(tomorrowMorning, now).startsWith("tomorrow at"))
    }

    @Test
    fun splitsMultipleTimesAndCarriesATrailingMeridiem() {
        val parsed = EmaAlarmRequest.parse("6 and 6:30 am", at(10, 10))!!
        assertEquals(2, parsed.times.size)
        assertEquals(listOf(6, 6), parsed.times.map { hour(it.millis) })
        assertEquals(listOf(0, 30), parsed.times.map {
            Calendar.getInstance().apply { timeInMillis = it.millis }.get(Calendar.MINUTE)
        })
        assertNull(parsed.days)
        assertFalse(parsed.explicitDate)
    }

    @Test
    fun weekdaysBecomeExtraDaysForTheClockApp() {
        val parsed = EmaAlarmRequest.parse("7 am on weekdays", at(10, 10))!!
        assertEquals(1, parsed.times.size)
        assertEquals(listOf(7), parsed.times.map { hour(it.millis) })
        assertEquals(
            listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
            parsed.days
        )
    }

    @Test
    fun theOwnersAlarmPhrasesAreUnderstood() {
        // "set an alarm next Monday at 8 pm": one alarm, on that date.
        val nextMonday = EmaAlarmRequest.parse("set an alarm next Monday at 8 pm", at(10, 10))!!
        assertNull(nextMonday.days)
        assertTrue(nextMonday.explicitDate)
        assertEquals(1, nextMonday.times.size)
        assertEquals(20, hour(nextMonday.times.single().millis))
        val weekday = Calendar.getInstance().apply { timeInMillis = nextMonday.times.single().millis }
            .get(Calendar.DAY_OF_WEEK)
        assertEquals(Calendar.MONDAY, weekday)

        // "set alarms for 6, 6:30 and 7 am": three alarms, all reported.
        val three = EmaAlarmRequest.parse("set alarms for 6, 6:30 and 7 am", at(10, 10))!!
        assertEquals(3, three.times.size)
        assertEquals(listOf(6, 6, 7), three.times.map { hour(it.millis) })

        // "alarm at 7 am on weekdays": repeats Monday to Friday.
        val weekdays = EmaAlarmRequest.parse("alarm at 7 am on weekdays", at(10, 10))!!
        assertEquals(
            listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
            weekdays.days
        )
        assertFalse(weekdays.explicitDate)
    }

    @Test
    fun onceAndOnlyMeanASingleAlarm() {
        val once = EmaAlarmRequest.parse("set an alarm once at 7 pm", at(10, 10))!!
        assertNull(once.days)
        assertFalse(once.explicitDate)
        assertEquals(1, once.times.size)
        assertEquals(19, hour(once.times.single().millis))

        val only = EmaAlarmRequest.parse("alarm only at 6 am", at(10, 10))!!
        assertNull(only.days)
        assertEquals(6, hour(only.times.single().millis))

        val onceOnly = EmaAlarmRequest.parse("set an alarm once only at 6 am", at(10, 10))!!
        assertNull(onceOnly.days)
        assertEquals(1, onceOnly.times.size)
    }

    @Test
    fun aNamedDayIsOneTimeAndAnExplicitRepeatIsNot() {
        // "monday at 7 am" names one date. The clock app has no date field, so
        // EMA schedules its own exact alarm for that day instead of asking the
        // clock app to repeat every Monday.
        val monday = EmaAlarmRequest.parse("monday at 7 am", at(10, 10))!!
        assertNull(monday.days)
        assertTrue(monday.explicitDate)
        assertEquals(7, hour(monday.times.single().millis))

        // "every monday" is a real repeat and stays with the clock app.
        val everyMonday = EmaAlarmRequest.parse("every monday at 7 am", at(10, 10))!!
        assertEquals(listOf(Calendar.MONDAY), everyMonday.days)
        assertFalse(everyMonday.explicitDate)

        val tomorrow = EmaAlarmRequest.parse("tomorrow at 7", at(10, 10))!!
        assertNull(tomorrow.days)
        assertTrue(tomorrow.explicitDate)
        assertEquals(7, hour(tomorrow.times.single().millis))
    }
}
