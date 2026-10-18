package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaConversationContextTest {
    @Test fun taskExpiresAndSelectionsAccumulate() {
        EmaConversationContext.clear()
        EmaConversationContext.recordSearch("YouTube", "football", nowMs = 1_000)
        EmaConversationContext.recordSelection("YouTube", "Derby day", EmaSelection.Ordinal(1), true, nowMs = 2_000)
        EmaConversationContext.recordSelection("YouTube", "Every goal", EmaSelection.Longest, true, nowMs = 3_000)
        val t = EmaConversationContext.activeTask(nowMs = 4_000)!!
        assertEquals("football", t.query)
        assertEquals(listOf("Derby day", "Every goal"), t.chosenTitles)
        assertTrue(t.lastWasPlay)
        assertNull(EmaConversationContext.activeTask(nowMs = 3_000 + EmaConversationContext.TTL_MS + 1))
    }

    @Test fun anotherAppStartsAFreshTask() {
        EmaConversationContext.clear()
        EmaConversationContext.recordSearch("YouTube", "football", nowMs = 0)
        EmaConversationContext.recordSelection("My Portal", "Invoice 1", EmaSelection.Ordinal(1), false, nowMs = 10)
        val t = EmaConversationContext.activeTask(nowMs = 20)!!
        assertEquals("My Portal", t.app)
        assertNull(t.query)
        assertEquals(listOf("Invoice 1"), t.chosenTitles)
        EmaConversationContext.clear()
    }

    @Test fun referencesAreParsedAsSelections() {
        fun p(s: String) = EmaSelection.parseReference(s)
        assertEquals(EmaSelection.Ordinal(3), p("the third result"))
        assertEquals(EmaSelection.Ordinal(2), p("number 2"))
        assertEquals(EmaSelection.Last, p("the last one"))
        assertEquals(EmaSelection.Longest, p("the longest one instead"))
        assertEquals(EmaSelection.Another, p("another one"))
        assertNull(p("Daniel"))
    }
}
