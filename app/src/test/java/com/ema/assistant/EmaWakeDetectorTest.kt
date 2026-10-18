package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaWakeDetectorTest {
    @Test fun phraseInsideNoisyHypothesisIsFound() {
        // The old check compared the whole hypothesis with "hey emma" and missed these.
        assertTrue(EmaWakeDetector.containsWakePhrase("[unk] hey emma"))
        assertTrue(EmaWakeDetector.containsWakePhrase("hey ema [unk]"))
        assertTrue(EmaWakeDetector.containsWakePhrase("hey emma"))
    }

    @Test fun lookalikesAreRejected() {
        for (h in listOf("hey emmy", "hey gemma", "emma", "hey", "hey anna", "[unk]", "", "emma hey", "hey emily"))
            assertFalse(h, EmaWakeDetector.containsWakePhrase(h))
    }

    @Test fun partialsMustBeStableBeforeTriggering() {
        val d = EmaWakeDetector()
        assertFalse(d.onPartial("hey emma"))
        assertTrue(d.onPartial("hey emma"))
        // A single flickering partial does not trigger.
        assertFalse(d.onPartial("hey emma"))
        assertFalse(d.onPartial("hey emmy"))
        assertFalse(d.onPartial("hey emma"))
    }

    @Test fun finalResultTriggersImmediately() {
        assertTrue(EmaWakeDetector().onFinal("[unk] hey emma"))
        assertFalse(EmaWakeDetector().onFinal("hey gemma"))
    }

    @Test fun grammarContainsBothSpellingsCompetitorsAndUnknown() {
        val g = EmaWakeDetector.grammarJson()
        assertTrue(g.startsWith("[") && g.endsWith("]"))
        assertTrue(g.contains("\"hey emma\"") && g.contains("\"hey ema\"") && g.contains("\"[unk]\""))
        // Competitors give near-misses somewhere to go instead of being forced into the wake phrase.
        assertTrue(g.contains("\"hey gemma\"") && g.contains("\"emmy\""))
        // Only single words known to the bundled model: no punctuation or apostrophes.
        EmaWakeDetector.GRAMMAR_PHRASES.forEach { assertTrue(it, Regex("^[a-z ]+$").matches(it)) }
    }

    @Test fun phraseGateAcceptsBothSpellingsAndStripsWakeWords() {
        val gate = EmaWakePhraseGate()
        assertEquals("open YouTube", gate.accept("Hey Emma, open YouTube", true, 0))
        assertEquals("open YouTube", gate.accept("hey ema open YouTube", true, 0))
        assertNull(gate.accept("Hey Emmanuel, open YouTube", true, 0))
        assertNull(gate.accept("open YouTube", true, 0))
    }
}
