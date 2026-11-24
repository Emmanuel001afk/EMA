package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaSentenceAnalysisTest {
    @Test fun recognizesExplicitAnalysisOnly() {
        assertEquals("Open Spotify", EmaSentenceAnalysis.parse("analyze this sentence: Open Spotify"))
        assertEquals("", EmaSentenceAnalysis.parse("interpret the sentence: "))
        assertNull(EmaSentenceAnalysis.parse("send Ada saying analyze this sentence: open Spotify"))
        assertTrue(EmaSentenceAnalysis.respond("open Spotify").contains("haven't executed"))
    }

    @Test fun distinguishesSurfaceFormsAndAcknowledgesUncertainty() {
        assertEquals("possible request", EmaSentenceAnalysis.analyze("Could you open the door?")!!.sentenceType)
        assertEquals("question", EmaSentenceAnalysis.analyze("Why is the door open?")!!.sentenceType)
        assertEquals("possible instruction", EmaSentenceAnalysis.analyze("Open the door.")!!.sentenceType)
        assertEquals("statement or other sentence", EmaSentenceAnalysis.analyze("The door is open.")!!.sentenceType)
        assertTrue(EmaSentenceAnalysis.analyze("Great job.")!!.notes.any { it.contains("cannot be determined") })
        assertTrue(EmaSentenceAnalysis.analyze("Great job. /s")!!.notes.any { it.contains("explicit sarcasm") })
    }

    @Test fun textIsNotReinterpretedAsAnActionAndNegationScopeIsNotGuessed() {
        val a = EmaSentenceAnalysis.analyze("If it rains, don't open the door.")!!
        assertTrue(a.notes.any { it.contains("Negation") && it.contains("scope") })
        assertTrue(a.notes.any { it.contains("conditional") })
        val quoted = EmaSentenceAnalysis.analyze("She said \"don't delete everything\".")!!
        assertFalse(quoted.notes.any { it.startsWith("Negation") })
        assertTrue(quoted.notes.any { it.contains("not instructions") })
        assertTrue(EmaSentenceAnalysis.analyze("Don’t call Ada")!!.notes.any { it.contains("Negation") })
    }

    @Test fun unicodeWordsAndLimits() {
        assertEquals(listOf("café", "你好", "42"), EmaSentenceAnalysis.analyze("cafe\u0301 你好 42")!!.words)
        assertNull(EmaSentenceAnalysis.analyze("x".repeat(4001)))
        assertNull(EmaSentenceAnalysis.analyze(List(201) { "word" }.joinToString(" ")))
    }
}
