package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Alarm, timer, reminder and routine scheduling all depend on this parser, and
 * it runs entirely offline.
 */
class EmaTimeParserTest {

    private fun calendar(hour: Int, minute: Int, dayOffset: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun parts(millis: Long): Pair<Int, Int> {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return c.get(Calendar.HOUR_OF_DAY) to c.get(Calendar.MINUTE)
    }

    // ------------------------------------------------------------- durations

    @Test
    fun parsesDurations() {
        assertEquals(600, EmaTimeParser.parseDuration("10 minutes")?.seconds)
        assertEquals(30, EmaTimeParser.parseDuration("30 seconds")?.seconds)
        assertEquals(7200, EmaTimeParser.parseDuration("2 hours")?.seconds)
        assertEquals(600, EmaTimeParser.parseDuration("in 10 mins")?.seconds)
    }

    @Test
    fun parsesWordDurations() {
        assertEquals(300, EmaTimeParser.parseDuration("five minutes")?.seconds)
        assertEquals(3600, EmaTimeParser.parseDuration("one hour")?.seconds)
        assertEquals(1500, EmaTimeParser.parseDuration("twenty five minutes")?.seconds)
    }

    @Test
    fun rejectsNonDurations() {
        assertNull(EmaTimeParser.parseDuration("7 am"))
        assertNull(EmaTimeParser.parseDuration("hello"))
        assertNull(EmaTimeParser.parseDuration(""))
    }

    // ----------------------------------------------------------- clock times

    @Test
    fun parsesTwelveHourClock() {
        val now = calendar(6, 0)
        val moment = EmaTimeParser.parse("7 am", now)
        assertNotNull(moment)
        assertEquals(7 to 0, parts(moment!!.millis))
    }

    @Test
    fun parsesAfternoonTime() {
        val now = calendar(6, 0)
        val moment = EmaTimeParser.parse("5:30 pm", now)!!
        assertEquals(17 to 30, parts(moment.millis))
    }

    @Test
    fun parsesTwentyFourHourClock() {
        val now = calendar(6, 0)
        assertEquals(19 to 30, parts(EmaTimeParser.parse("19:30", now)!!.millis))
    }

    @Test
    fun bareHourRollsToTomorrowWhenAlreadyPassed() {
        val now = calendar(20, 0)
        val moment = EmaTimeParser.parse("7 am", now)!!
        assertEquals(7 to 0, parts(moment.millis))
        assertTrue("must be in the future", moment.millis > now)
    }

    @Test
    fun parsesHalfPastAndQuarterForms() {
        val now = calendar(5, 0)
        assertEquals(6 to 30, parts(EmaTimeParser.parse("half past six", now)!!.millis))
        assertEquals(6 to 45, parts(EmaTimeParser.parse("quarter to seven", now)!!.millis))
        assertEquals(9 to 15, parts(EmaTimeParser.parse("quarter past nine", now)!!.millis))
    }

    @Test
    fun parsesHalfPastWithMeridiem() {
        val now = calendar(5, 0)
        assertEquals(18 to 30, parts(EmaTimeParser.parse("half past six pm", now)!!.millis))
    }

    @Test
    fun parsesNoonAndMidnight() {
        val now = calendar(5, 0)
        assertEquals(12 to 0, parts(EmaTimeParser.parse("noon", now)!!.millis))
        assertEquals(0 to 0, parts(EmaTimeParser.parse("midnight", now)!!.millis))
    }

    @Test
    fun parsesDayParts() {
        val now = calendar(5, 0)
        assertEquals(20 to 0, parts(EmaTimeParser.parse("tonight", now)!!.millis))
        assertEquals(8 to 0, parts(EmaTimeParser.parse("this morning", now)!!.millis))
        assertEquals(15 to 0, parts(EmaTimeParser.parse("this afternoon", now)!!.millis))
    }

    @Test
    fun dayPartCombinesWithAnExplicitHour() {
        val now = calendar(5, 0)
        assertEquals(21 to 0, parts(EmaTimeParser.parse("tonight at 9", now)!!.millis))
    }

    // ------------------------------------------------------------ relative

    @Test
    fun parsesRelativeTimes() {
        val now = calendar(10, 0)
        val moment = EmaTimeParser.parse("in 10 minutes", now)!!
        assertEquals(EmaTimeParser.Moment.Kind.RELATIVE, moment.kind)
        assertEquals(now + 600_000L, moment.millis)
    }

    @Test
    fun parsesRelativeHours() {
        val now = calendar(10, 0)
        assertEquals(now + 2 * 3600_000L, EmaTimeParser.parse("in 2 hours", now)!!.millis)
    }

    @Test
    fun parsesFromNowForm() {
        val now = calendar(10, 0)
        assertEquals(now + 45 * 60_000L, EmaTimeParser.parse("45 minutes from now", now)!!.millis)
    }

    // ------------------------------------------------------------- day names

    @Test
    fun parsesTomorrow() {
        val now = calendar(20, 0)
        val moment = EmaTimeParser.parse("tomorrow at 8 am", now)!!
        assertEquals(EmaTimeParser.Moment.Kind.DAY_NAME, moment.kind)
        val expected = calendar(8, 0, 1)
        assertEquals(parts(expected), parts(moment.millis))
        assertTrue(moment.millis > now)
    }

    @Test
    fun parsesTomorrowMorningWithoutAnExplicitHour() {
        val now = calendar(20, 0)
        val moment = EmaTimeParser.parse("tomorrow morning", now)!!
        assertEquals(8 to 0, parts(moment.millis))
    }

    @Test
    fun parsesWeekdayNames() {
        val now = calendar(10, 0)
        val moment = EmaTimeParser.parse("friday at 6 pm", now)!!
        val c = Calendar.getInstance().apply { timeInMillis = moment.millis }
        assertEquals(Calendar.FRIDAY, c.get(Calendar.DAY_OF_WEEK))
        assertEquals(18 to 0, parts(moment.millis))
    }

    // ------------------------------------------------------------- rejection

    @Test
    fun rejectsTextWithoutTimeInformation() {
        assertNull(EmaTimeParser.parse("hello there"))
        assertNull(EmaTimeParser.parse(""))
        assertNull(EmaTimeParser.parse("   "))
    }

    @Test
    fun everyResultIsStrictlyInTheFuture() {
        val now = System.currentTimeMillis()
        for (value in listOf("7 am", "noon", "in 5 minutes", "tomorrow at 9", "half past six")) {
            val moment = EmaTimeParser.parse(value, now)
            assertNotNull("$value should parse", moment)
            assertTrue("$value must be in the future", moment!!.millis > now)
        }
    }

    @Test
    fun wordsToDigitsKeepsNonNumbersIntact() {
        assertEquals("call 5 pm", EmaTimeParser.wordsToDigits("call five pm"))
        assertEquals("25 minutes", EmaTimeParser.wordsToDigits("twenty five minutes"))
    }

    @Test
    fun describeProducesAReadableTime() {
        val millis = calendar(17, 30)
        assertTrue(EmaTimeParser.describeShort(millis).contains("5:30"))
    }
}
