package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaSpeechIntentSafetyTest {
    @Test fun rescoringMustNotReversePolarityOrChangeTheDevice() {
        for ((a, b) in listOf(
            "turn off wifi" to "turn on wifi",
            "enable bluetooth" to "disable bluetooth",
            "turn on wifi" to "turn on bluetooth",
            "don't open Spotify" to "open Spotify",
            "set brightness to 20 percent" to "set brightness to 80 percent",
            "is wifi on" to "turn on wifi"
        )) assertFalse("$a -> $b", EmaSpeechIntentSafety.preservesIntent(a, b))
        assertTrue(EmaSpeechIntentSafety.preservesIntent("turn off wi fi", "disable wifi"))
        assertTrue(EmaSpeechIntentSafety.preservesIntent("open you tube", "open YouTube"))
        assertTrue(EmaSpeechIntentSafety.preservesIntent("message Ada saying don't go", "message Ada saying don't go"))
    }
    @Test fun endpointerWaitsForMissingTargetsAndMessageContents() {
        for (raw in listOf("turn on", "connect to", "pair with", "message Daniel", "send a message to Ada on WhatsApp"))
            assertTrue(raw, EmaSpeechAudioProcessor.isIncompleteClause(raw))
        for (raw in listOf("wifi on", "bluetooth off", "message Ada saying I am coming", "open wifi settings"))
            assertFalse(raw, EmaSpeechIntentSafety.needsContinuation(raw))
    }
    @Test fun rescorerRetainsAcousticSettingEvenWhenContextFavorsTheOpposite() {
        val result = EmaSpeechRescorer.rescore(listOf(
            EmaVoskJsonParser.Hypothesis("turn off wifi", 0.8f, 1.0),
            EmaVoskJsonParser.Hypothesis("turn on wifi", 0.79f, 0.99)
        ), EmaSpeechContext.EMPTY, EmaRecognitionProfile.COMMAND)
        assertEquals("turn off wi-fi", result.primary?.lowercase())
    }
}
