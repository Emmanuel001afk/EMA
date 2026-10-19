package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class EmaKnowledgeTest {
    private val today = LocalDate.of(2026, 10, 18) // a Sunday
    private fun a(q: String) = EmaKnowledge.answer(q, today)?.text

    @Test fun percentages() {
        assertEquals("15% of 2,400 is 360.", a("what is 15% of 2400"))
        assertEquals("15% of 2,400 is 360.", a("15 percent of 2,400?"))
        assertEquals("30 is 25% of 120.", a("what percent is 30 of 120"))
        assertEquals("200 increased by 15% is 230.", a("increase 200 by 15%"))
    }

    @Test fun arithmeticTypedAndSpoken() {
        assertEquals("12 * 7 = 84.", a("what is 12 * 7"))
        assertTrue(a("what is twelve times seven")!!.endsWith("= 84."))
        assertTrue(a("calculate 2 + 3 * 4")!!.endsWith("= 14."))
        assertTrue(a("what's 10 divided by 4")!!.endsWith("= 2.5."))
        assertTrue(a("what is the square root of 144")!!.endsWith("= 12."))
        assertTrue(a("5 factorial")!!.endsWith("= 120."))
        assertEquals("That result is undefined.", a("what is 5 / 0"))
    }

    @Test fun conversions() {
        assertEquals("5 km is 3.11 miles.", a("convert 5 km to miles"))
        assertEquals("100°C is 212°F.", a("what is 100 degrees celsius in fahrenheit"))
        assertEquals("1 kg is 2.2 pounds.", a("1 kg in pounds"))
        assertEquals("I can't convert km to kg: one is length and the other is mass.", a("convert 3 km to kg"))
        assertEquals("1 hours is 60 minutes.", a("how many minutes in 1 hours"))
    }

    @Test fun dates() {
        assertEquals("68 days until Friday, 25 December 2026.", a("how many days until christmas"))
        assertEquals("It will be Sunday, 1 November 2026.", a("what is the date in 2 weeks"))
        assertEquals("1 January 2027 is a Friday.", a("what day is 1 January 2027"))
        assertEquals("Yes, 2028 is a leap year.", a("is 2028 a leap year"))
    }

    @Test fun thisAndNextWeekdayDiffer() {
        val monday = LocalDate.of(2026, 10, 19)
        assertEquals("This friday is Friday, 23 October 2026.", EmaKnowledge.answer("date this friday", monday)?.text)
        assertEquals("Next friday is Friday, 30 October 2026.", EmaKnowledge.answer("date next friday", monday)?.text)
        // On Sunday the coming Friday is already in next week.
        assertEquals("Next friday is Friday, 23 October 2026.", a("date next friday"))
    }

    @Test fun commandsAndChatAreNotKnowledge() {
        for (q in listOf(
            "set a timer for 5 minutes", "remind me in 10 minutes to stretch", "message Daniel I'll be there in 10 min",
            "volume to 50 percent", "call 911", "tell me about cats and dogs", "play songs from 2010 to 2020", "open youtube"
        )) assertNull(q, a(q))
    }

    @Test fun externalInformationIsRecognised() {
        assertTrue(EmaKnowledge.needsExternalInformation("what's the weather in Lagos"))
        assertTrue(EmaKnowledge.needsExternalInformation("who won the match last night"))
        assertFalse(EmaKnowledge.needsExternalInformation("what is 15% of 2400"))
    }

    @Test fun selfDescriptionIsHonest() {
        val s = a("do you need the internet")!!
        assertTrue(s.contains("without the internet"))
        assertFalse(a("are you chatgpt")!!.contains("I am ChatGPT"))
    }
}
