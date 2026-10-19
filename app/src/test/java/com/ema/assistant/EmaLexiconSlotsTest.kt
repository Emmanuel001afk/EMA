package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in dictionary, slot by slot.
 *
 * The point of the lexicon is that every parser reads the same words, so a
 * synonym works in every slot: the verb, the target, the value, the time,
 * the filler and the mishearing.
 */
class EmaLexiconSlotsTest {

    @Test
    fun theVerbSynonymsAllMeanTheSameThing() {
        val intent = EmaLexicon.intent("send-message")
        assertNotNull(intent)
        for (verb in listOf("send", "message", "text", "tell", "inform", "whatsapp")) {
            assertEquals("\"$verb\" must mean send-message", intent, EmaLexicon.intentForVerb(verb))
        }
        val media = EmaLexicon.intent("play-media")
        for (verb in listOf("play", "put", "start", "listen", "stream")) {
            assertEquals(media, EmaLexicon.intentForVerb(verb))
        }
    }

    @Test
    fun theTargetSynonymsAllNameTheSameSetting() {
        for (phrase in listOf("do not disturb", "dnd", "silent mode", "disturb")) {
            assertEquals("dnd", EmaLexicon.canonicalTarget(phrase))
        }
        for (phrase in listOf("brightness", "screen brightness", "display light")) {
            assertEquals("brightness", EmaLexicon.canonicalTarget(phrase))
        }
        for (phrase in listOf("airplane mode", "flight mode", "aeroplane mode")) {
            assertEquals("airplane", EmaLexicon.canonicalTarget(phrase))
        }
        for (phrase in listOf("wi fi", "wireless network", "internet")) {
            assertEquals("wifi", EmaLexicon.canonicalTarget(phrase))
        }
    }

    @Test
    fun theValueSynonymsAllMeanTheSameValue() {
        for (word in listOf("max", "maximum", "full", "brightest", "highest")) {
            assertTrue("$word must be a maximum", EmaLexicon.MAX_WORDS.contains(word))
            assertTrue("$word must be a maximum value", EmaLexicon.MAX_VALUES.contains(word))
        }
        for (word in listOf("min", "minimum", "lowest", "dimmest")) {
            assertTrue("$word must be a minimum", EmaLexicon.MIN_WORDS.contains(word))
            assertTrue("$word must be a minimum value", EmaLexicon.MIN_VALUES.contains(word))
        }
    }

    @Test
    fun theNumberWordsAreNumbers() {
        assertEquals(7, EmaLexicon.numberValue("seven"))
        assertEquals(40, EmaLexicon.numberValue("forty"))
        assertNull(EmaLexicon.numberValue("banana"))
    }

    @Test
    fun theTimeWordsAreTimeWords() {
        for (word in listOf("today", "tonight", "tomorrow", "morning", "weekdays", "weekends", "daily")) {
            assertTrue("$word must be a time word", EmaLexicon.TIME_WORDS.contains(word))
        }
        for (word in listOf("every", "each", "daily", "weekdays", "weekends", "always")) {
            assertTrue("$word must be a repeat word", EmaLexicon.REPEAT_WORDS.contains(word))
        }
    }

    @Test
    fun thePolitenessAndFillersAreStripped() {
        assertEquals("open Spotify", EmaLexicon.stripLeadings("please open Spotify"))
        assertEquals("open Spotify", EmaLexicon.stripLeadings("Hey EMA, open Spotify"))
        assertEquals("open Spotify", EmaLexicon.stripLeadings("um can you please open Spotify"))
        assertEquals("open Spotify", EmaLexicon.stripLeadings("okay EMA open Spotify"))
    }

    @Test
    fun theMishearingsAreCorrected() {
        assertEquals("ema open Spotify", EmaLexicon.applyMishearings("a mma open Spotify"))
        assertEquals("hey ema open Spotify", EmaLexicon.applyMishearings("hey emma open Spotify"))
        assertEquals("whatsapp John", EmaLexicon.applyMishearings("what's up John"))
        assertEquals("spotify", EmaLexicon.applyMishearings("spot if i"))
        assertEquals("play spotify", EmaLexicon.applyMishearings("play spot"))
    }

    @Test
    fun aMishearingIsNeverCorrectedInsideAMessage() {
        val text = "send John a message saying what's up"
        assertTrue("the content must stay as it is", EmaLexicon.applyMishearings(text).contains("what's up"))
    }

    @Test
    fun theSuggestionsNameSomethingThatWorks() {
        val suggestions = EmaLexicon.suggestionsFor("play something nice")
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.first().contains("media", ignoreCase = true))
    }

    @Test
    fun theAppKindsAreKnown() {
        assertEquals("media", EmaLexicon.appKindFor("music"))
        assertEquals("media", EmaLexicon.appKindFor("playlist"))
        assertEquals("message", EmaLexicon.appKindFor("chat"))
        assertNull(EmaLexicon.appKindFor("Spotify"))
    }

    @Test
    fun theDeviceSettingsWorkWithTheirSynonyms() {
        fun kind(text: String) = EmaSystemCommandVocabulary.parseDeviceSetting(text)
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Kind.DND,
            kind("turn on Do Not Disturb")!!.kind
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Kind.DND,
            kind("enable dnd")!!.kind
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Kind.AIRPLANE,
            kind("turn on airplane mode")!!.kind
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Kind.AIRPLANE,
            kind("switch on flight mode")!!.kind
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Kind.BRIGHTNESS,
            kind("increase brightness to maximum")!!.kind
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Kind.VOLUME,
            kind("set volume to 50 percent")!!.kind
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Action.INCREASE,
            kind("increase the volume")!!.action
        )
    }

    @Test
    fun aSettingNeverFiresFromInsideAnotherCommand() {
        assertNull(EmaSystemCommandVocabulary.parseDeviceSetting("send John a message saying increase the brightness"))
        assertNull(EmaSystemCommandVocabulary.parseDeviceSetting("search for how to turn on airplane mode"))
        assertNull(EmaSystemCommandVocabulary.parseDeviceSetting("remind me to turn on dnd at 9 pm"))
    }

    @Test
    fun theContentOfAMessageIsRecognised() {
        assertTrue(EmaLexicon.isContentCommand("send John a message saying hi"))
        assertTrue(EmaLexicon.isContentCommand("search for cats saying nothing"))
        assertFalse(EmaLexicon.isContentCommand("open Spotify and play Calm Down"))
        assertEquals("hi", EmaLexicon.contentOf("send John a message saying hi"))
    }
}
