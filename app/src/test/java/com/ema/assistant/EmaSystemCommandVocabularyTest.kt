package com.ema.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-action gate.
 *
 * This is the check that decides whether an AI-proposed plan is really a device
 * action EMA should perform, or a knowledge answer that merely mentions an app.
 * Getting it wrong is expensive in both directions: too strict and valid plans
 * are silently downgraded to spoken answers; too loose and EMA starts performing
 * actions the user only asked about.
 */
class EmaSystemCommandVocabularyTest {

    // -------------------------------------------------------------- exact forms

    @Test
    fun literalDeviceCommandsAreRecognised() {
        listOf(
            "back", "home", "read screen", "copy screen text", "read notifications",
            "list my reminders", "cancel all reminders", "what time is it",
            "what is today's date", "what is my battery level", "take a screenshot",
            "turn on flashlight", "turn off dnd", "increase brightness",
            "set volume to 50 percent", "scroll down", "close ema", "what can you do"
        ).forEach { command ->
            assertTrue("not recognised: $command", EmaSystemCommandVocabulary.recognises(command))
        }
    }

    @Test
    fun recognitionIsInsensitiveToSurroundingWhitespace() {
        assertTrue(EmaSystemCommandVocabulary.recognises("  back  "))
        assertTrue(EmaSystemCommandVocabulary.recognises("\nread screen\n"))
    }

    @Test
    fun blankInputIsNotACommand() {
        assertFalse(EmaSystemCommandVocabulary.recognises(""))
        assertFalse(EmaSystemCommandVocabulary.recognises("   "))
    }

    // ------------------------------------------------------------- prefix forms

    @Test
    fun argumentBearingCommandsAreRecognisedByPrefix() {
        listOf(
            "type hello there",
            "set brightness to 40 percent",
            "list files in downloads",
            "cancel my reminder about medicine",
            "create a calendar event Meeting at 5 pm",
            "create a calendar task Buy milk",
            "schedule my day: 8 AM class"
        ).forEach { command ->
            assertTrue("prefix not recognised: $command", EmaSystemCommandVocabulary.recognises(command))
        }
    }

    @Test
    fun aPrefixAloneWithoutAnArgumentIsNotACompleteCommand() {
        // "type " with nothing after it is not something EMA can execute.
        assertFalse(EmaSystemCommandVocabulary.recognises("type"))
        assertFalse(EmaSystemCommandVocabulary.recognises("set brightness to"))
    }

    @Test
    fun unrelatedUtterancesAreNotLiteralCommands() {
        listOf(
            "what is the capital of Ghana",
            "explain photosynthesis",
            "i am feeling tired today",
            "thanks"
        ).forEach { utterance ->
            assertFalse("wrongly recognised: $utterance", EmaSystemCommandVocabulary.recognises(utterance))
        }
    }

    // ---------------------------------------------------------------- the gate

    @Test
    fun theGateAcceptsLiteralDeviceCommands() {
        assertTrue(EmaSystemCommandVocabulary.looksLikeDeviceAction("list my reminders"))
        assertTrue(EmaSystemCommandVocabulary.looksLikeDeviceAction("read screen"))
        assertTrue(EmaSystemCommandVocabulary.looksLikeDeviceAction("what time is it"))
    }

    @Test
    fun theGateAcceptsVerbLedGrammarCommands() {
        listOf(
            "open spotify",
            "play arcade on spotify",
            "call ada lovelace",
            "send a message to ada on whatsapp saying i am on my way",
            "set an alarm for 7 am",
            "set a timer for 10 minutes",
            "remind me to take my medicine at 9 pm",
            "navigate to central park",
            "search weather in london",
            "save 5551234 as ada",
            "every day at 7 am: open spotify"
        ).forEach { command ->
            assertTrue("gate rejected a valid device action: $command",
                EmaSystemCommandVocabulary.looksLikeDeviceAction(command))
        }
    }

    @Test
    fun theGateRejectsKnowledgeQuestions() {
        // The whole point of the gate: mentioning an app is not a request to act.
        listOf(
            "what is the capital of ghana",
            "how does photosynthesis work",
            "who wrote things fall apart",
            "why is the sky blue"
        ).forEach { question ->
            assertFalse("gate accepted a knowledge question: $question",
                EmaSystemCommandVocabulary.looksLikeDeviceAction(question))
        }
    }

    @Test
    fun theGateRejectsQuestionsAboutAppsThatAreNotActionRequests() {
        listOf(
            "which app can edit pdf files",
            "is spotify worth paying for",
            "does whatsapp work without internet"
        ).forEach { question ->
            assertFalse("gate accepted an app question: $question",
                EmaSystemCommandVocabulary.looksLikeDeviceAction(question))
        }
    }

    @Test
    fun theGateRejectsBlankInput() {
        assertFalse(EmaSystemCommandVocabulary.looksLikeDeviceAction(""))
        assertFalse(EmaSystemCommandVocabulary.looksLikeDeviceAction("   "))
    }

    @Test
    fun theVocabularyIsNonTrivial() {
        assertTrue(EmaSystemCommandVocabulary.EXACT.size >= 50)
        assertTrue(EmaSystemCommandVocabulary.PREFIXES.size >= 10)
    }

    @Test
    fun exactEntriesAreNormalizedLowercaseAndTrimmed() {
        // The router compares against lowercased input, so an entry with capitals
        // or padding could never match.
        EmaSystemCommandVocabulary.EXACT.forEach { entry ->
            assertTrue("entry is not lowercase: $entry", entry == entry.lowercase())
            assertTrue("entry is not trimmed: $entry", entry == entry.trim())
        }
    }

    @Test
    fun prefixEntriesAreNormalized() {
        EmaSystemCommandVocabulary.PREFIXES.forEach { prefix ->
            assertTrue("prefix is not lowercase: $prefix", prefix == prefix.lowercase())
            assertTrue("prefix has leading padding: $prefix", prefix == prefix.trimStart())
            assertTrue("prefix is empty", prefix.isNotEmpty())
            // A trailing space is meaningful (it stops "type " matching
            // "typewriter"); doubled spaces never are.
            assertFalse("prefix has doubled spaces: $prefix", prefix.contains("  "))
        }
    }

    @Test
    fun argumentBearingPrefixesCannotClipLongerWords() {
        // "type " carries a trailing space so it cannot match "typewriter".
        assertFalse(EmaSystemCommandVocabulary.recognises("typewriter settings"))
        assertFalse(EmaSystemCommandVocabulary.recognises("set brightness tomorrow"))
        assertFalse(EmaSystemCommandVocabulary.recognises("calendar tasklist"))
    }
}
