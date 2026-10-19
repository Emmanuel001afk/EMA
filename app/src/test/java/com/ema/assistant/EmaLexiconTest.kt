package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in dictionary: one place where the words EMA understands live, so
 * every parser agrees. It is code, never a screen.
 */
class EmaLexiconTest {

    @Test
    fun theWakeWordAndPolitenessAreStrippedFromTheFront() {
        assertEquals("open Spotify", EmaLexicon.stripLeadings("hey ema please open Spotify"))
        assertEquals("open Spotify", EmaLexicon.stripLeadings("ok ema open Spotify"))
        assertEquals("open Spotify", EmaLexicon.stripLeadings("can you open Spotify"))
        assertEquals("open Spotify", EmaLexicon.stripLeadings("open Spotify"))
    }

    @Test
    fun theFirstVerbIsFound() {
        assertEquals("open", EmaLexicon.firstVerb("hey ema open Spotify"))
        assertEquals("send", EmaLexicon.firstVerb("please send John a message"))
        assertEquals("turn", EmaLexicon.firstVerb("turn on the lights"))
    }

    @Test
    fun theTargetsHaveTheirSynonyms() {
        assertEquals("dnd", EmaLexicon.canonicalTarget("turn on do not disturb"))
        assertEquals("dnd", EmaLexicon.canonicalTarget("turn on DND"))
        assertEquals("dnd", EmaLexicon.canonicalTarget("turn on silent mode"))
        assertEquals("airplane", EmaLexicon.canonicalTarget("turn on airplane mode"))
        assertEquals("airplane", EmaLexicon.canonicalTarget("switch aeroplane mode off"))
        assertEquals("airplane", EmaLexicon.canonicalTarget("turn on flight mode"))
        assertEquals("brightness", EmaLexicon.canonicalTarget("increase brightness"))
        assertEquals("volume", EmaLexicon.canonicalTarget("set volume to 50 percent"))
    }

    @Test
    fun theValuesAreKnown() {
        assertEquals(50, EmaLexicon.numberValue("fifty"))
        assertEquals(7, EmaLexicon.numberValue("seven"))
        assertTrue(EmaLexicon.MAX_VALUES.contains("maximum"))
        assertTrue(EmaLexicon.HALF_VALUES.contains("half"))
    }

    @Test
    fun commonMishearingsAreCorrected() {
        assertEquals("spotify", EmaLexicon.applyMishearings("spot if i"))
        assertEquals("ema open Spotify", EmaLexicon.applyMishearings("a mma open Spotify"))
        assertTrue(EmaLexicon.applyMishearings("what's up John").contains("whatsapp"))
    }

    @Test
    fun aMishearingIsNeverCorrectedInsideFreeText() {
        val body = "send John a message saying spot if i is my favourite song"
        assertEquals(body, EmaLexicon.applyMishearings(body, inFreeText = true))
    }

    @Test
    fun anUnknownRequestGetsTheClosestThingEmaCanDo() {
        val suggestions = EmaLexicon.suggestionsFor("play some music on my phone")
        assertTrue("expected a suggestion, got $suggestions", suggestions.isNotEmpty())
        assertTrue(suggestions.first().contains("play", ignoreCase = true))
    }

    @Test
    fun everyIntentHasAnExampleAndVerbs() {
        for (intent in EmaLexicon.INTENTS) {
            assertTrue("intent ${intent.id} has no verbs", intent.verbs.isNotEmpty())
            assertTrue("intent ${intent.id} has no example", intent.example.isNotBlank())
            assertNotNull(EmaLexicon.intent(intent.id))
        }
    }
}
