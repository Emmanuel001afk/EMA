package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaSentenceComparisonTest {
    @Test fun comparisonIsExplicitAndNeverExecutesItsContents() {
        assertNull(EmaSentenceComparison.parse("message Ada saying compare sentences: open Spotify | call Ada"))
        val body = EmaSentenceComparison.parse("compare sentences: open Spotify | call Ada")!!
        assertTrue(EmaSentenceComparison.respond(body).contains("haven't executed either"))
        assertTrue(EmaSentenceComparison.respond("one | two | three").contains("one |"))
        assertTrue(EmaSentenceComparison.respond(" | ").contains("nonempty"))
    }

    @Test fun positionsHighlightChangesWithoutClaimingEquivalentMeaning() {
        val moved = EmaSentenceComparison.respond("Only Ada called Bob | Ada only called Bob")
        assertTrue(moved.contains("different order"))
        assertTrue(moved.contains("'only' at word 1"))
        assertTrue(moved.contains("'only' at word 2"))
        assertTrue(moved.contains("not proof"))
        val negative = EmaSentenceComparison.respond("Ada called Bob | Ada did not call Bob")
        assertTrue(negative.contains("'not' at word 3"))
        assertTrue(negative.contains("wording differs"))
    }

    @Test fun normalizationLimitsAndPunctuationAreExplicit() {
        assertTrue(EmaSentenceComparison.respond("CAFÉ! | cafe\u0301?").contains("same order"))
        assertTrue(EmaSentenceComparison.respond("café | dog").contains("wording differs"))
        assertTrue(EmaSentenceComparison.respond("x".repeat(4001)).contains("4"))
        assertTrue(EmaSentenceComparison.respond(List(201) { "x" }.joinToString(" ") + " | a").contains("200 words"))
        assertTrue(EmaSentenceComparison.respond("Hi! | Hi?").contains("punctuation"))
    }
}
