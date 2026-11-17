package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class EmaReasoningKnowledgeTest {
    private fun a(q: String) = EmaReasoningKnowledge.answer(q)!!
    @Test fun scientificFunctionsAndDomains() {
        assertEquals(0.5, a("sin 30 degrees").value!!, 1e-10)
        assertEquals(1.0, a("cos 0").value!!, 1e-10)
        assertEquals(3.0, a("log2 8").value!!, 1e-10)
        assertNull(a("sqrt -1").value)
        assertNull(a("ln 0").value)
        assertNull(a("tan 90 degrees").value)
    }
    @Test fun statisticsAndBoundaryConditions() {
        assertEquals(4.0, a("mean of 2,4,6").value!!, 0.0)
        assertEquals(2.5, a("median of 4,1,3,2").value!!, 0.0)
        assertEquals(5.0, a("range of -1,4,2").value!!, 0.0)
        assertNull(a("sample standard deviation of 1").value)
        assertEquals(0.0, a("population standard deviation of 2,2").value!!, 0.0)
    }
    @Test fun exactIntegerOperations() {
        assertEquals("97 is prime.", a("is 97 a prime number").text)
        assertEquals("1 is not prime.", a("is 1 prime").text)
        assertEquals("GCD(48, 18) = 6.", a("gcd of 48 and 18").text)
        assertEquals("LCM(0, 0) = 0.", a("lcm of 0 and 0").text)
        assertEquals("LCM(12, 18) = 36.", a("lcm of 12 and 18").text)
    }
    @Test fun billAndBmi() {
        assertEquals(55.0, a("split 200 between 4 people with 10 percent tip").value!!, 1e-10)
        assertNull(a("split 200 between 0 people").value)
        assertEquals(25.0, a("bmi 81 kg at 180 cm").value!!, 1e-10)
        assertTrue(a("bmi 81 kg at 180 cm").text.contains("not a diagnosis"))
    }
    @Test fun algebraIncludesDegenerateCases() {
        assertEquals(4.0, a("solve linear 2 x + 3 = 11").value!!, 0.0)
        assertEquals("No solution.", a("solve linear 0 x + 3 = 11").text)
        assertEquals("Infinitely many solutions.", a("solve linear 0 x + 3 = 3").text)
        assertEquals("x = 2 or 3.", a("solve quadratic 1 x^2 + -5 x + 6 = 0").text)
        assertTrue(a("solve quadratic 1 x^2 + 0 x + 1 = 0").text.contains("No real roots"))
    }
    @Test fun worldClockUsesDstRulesNotFixedOffsets() {
        val winter = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC)
        val summer = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC)
        assertTrue(EmaReasoningKnowledge.answer("time in london", clock = winter)!!.text.contains("12:00"))
        assertTrue(EmaReasoningKnowledge.answer("time in london", clock = summer)!!.text.contains("13:00"))
        assertTrue(EmaReasoningKnowledge.answer("time in lagos", clock = winter)!!.text.contains("13:00"))
        assertTrue(a("time in atlantis").text.contains("don't have"))
    }
    @Test fun ageAndAnaphoraHaveExplicitContext() {
        val today = LocalDate.of(2026, 11, 16)
        assertEquals("26 years, 0 months and 0 days old.", EmaReasoningKnowledge.answer("age born 2000-11-16", today)!!.text)
        assertTrue(EmaReasoningKnowledge.answer("age born 2030-01-01", today)!!.text.contains("future"))
        assertEquals(24.0, EmaReasoningKnowledge.answer("double that", previous = 12.0)!!.value!!, 0.0)
        assertEquals(3.0, EmaReasoningKnowledge.answer("split it 4 ways", previous = 12.0)!!.value!!, 0.0)
        assertNull(a("double that").value)
    }
    @Test fun commandBodiesCannotBecomeKnowledge() {
        for (q in listOf("message Ada sin 30", "play prime 97", "search for time in london", "open calculator", "set a timer for 10 minutes")) {
            assertNull(q, EmaReasoningKnowledge.answer(q))
        }
    }
    @Test fun nativeEntryPointKeepsNumericContextStructured() {
        assertEquals(84.0, EmaKnowledge.answer("12 * 7")!!.value!!, 0.0)
        assertEquals(168.0, EmaKnowledge.answer("double that", previous = 84.0)!!.value!!, 0.0)
        assertTrue(EmaKnowledge.answer("time in lagos")!!.text.contains("Lagos"))
    }
}
