package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local engine is a small model, so it is given a grammar and asked for
 * per-word confidences. These tests hold both halves in place: the grammar must
 * still contain the commands the parsers act on, and the score must be the
 * weakest word, never an average that hides one guessed word.
 */
class EmaCommandVocabularyTest {

    @Test
    fun theGrammarIsValidJsonAndEndsWithTheUnknownEscape() {
        // Vosk reads this string as JSON, so it has to parse as JSON: a broken
        // grammar would silently disable constrained decoding.
        val grammar = EmaCommandVocabulary.grammarJson()
        val parsed = EmaJson.readObject("{\"g\": $grammar}") ?: error("grammar is not JSON")
        val alternatives = parsed["g"] as? List<*> ?: error("grammar is not a list")
        assertTrue(alternatives.size > 50)
        assertEquals("[unk]", alternatives.last())
    }

    @Test
    fun theGrammarKeepsTheCommandsTheParsersActOn() {
        for (phrase in listOf(
            "open", "play", "call", "turn on", "turn off", "remind me",
            "set an alarm", "hey emma", "send a message", "pause"
        )) {
            assertTrue("missing command phrase: $phrase", EmaCommandVocabulary.contains(phrase))
        }
    }

    @Test
    fun learnedTriggersAndAppNamesCanStillBeSpoken() {
        val grammar = EmaCommandVocabulary.grammarJson(listOf("Morning Routine", "WhatsApp"))
        assertTrue(grammar.contains("\"morning\""))
        assertTrue(grammar.contains("\"routine\""))
        assertTrue(grammar.contains("\"whatsapp\""))
    }

    @Test
    fun learningAWordTwiceDoesNotDuplicateIt() {
        val phrases = EmaCommandVocabulary.phrases(listOf("play Spotify", "spotify", "open"))
        assertEquals(phrases.size, phrases.distinct().size)
    }

    @Test
    fun onlyPhrasesTheModelKnowsEnterTheGrammar() {
        // A small English model does not know "whatsapp", so that phrase must be
        // dropped rather than corrupting the grammar.
        val modelWords = setOf("open", "play", "call", "spotify", "the", "a")
        val kept = EmaCommandVocabulary.decodable(
            listOf("open spotify", "open whatsapp", "call", "play the song"),
            modelWords
        )
        assertEquals(listOf("open spotify", "call"), kept)
    }

    @Test
    fun withoutAWordListTheDecoderStaysFreeForm() {
        // A missing graph/words.txt must never stop EMA from listening.
        assertNull(EmaCommandVocabulary.grammarFor(null))
        assertNull(EmaCommandVocabulary.grammarFor(emptySet()))
    }

    @Test
    fun aModelThatKnowsThePhrasesGetsARealGrammar() {
        assertNull(EmaCommandVocabulary.grammarFor(setOf("open", "the")))

        val modelWords = EmaCommandVocabulary.phrases()
            .flatMap { EmaCommandVocabulary.words(it) }
            .toSet()
        val grammar = EmaCommandVocabulary.grammarFor(modelWords)
        assertNotNull(grammar)
        val json = grammar!!
        assertTrue(json.contains("\"open\""))
        assertTrue(json.endsWith("[unk]\"]"))
    }

    @Test
    fun theWeakestWordDecidesTheScore() {
        val json = """
            {"text": "open spotify",
             "result": [
               {"word": "open", "conf": 0.98},
               {"word": "spotify", "conf": 0.51}
             ]}
        """.trimIndent()
        assertEquals(0.51f, EmaDecodeConfidence.weakest(json), 0.0001f)
    }

    @Test
    fun aResultWithoutWordTimingsFallsBack() {
        // No `conf` fields: the caller's fallback is used, not a made-up score.
        assertEquals(0.3f, EmaDecodeConfidence.weakest("{\"text\": \"open\"}", fallback = 0.3f), 0.0f)
        assertEquals(0.5f, EmaDecodeConfidence.weakest("not json", fallback = 0.5f), 0.0f)
        assertEquals(0.5f, EmaDecodeConfidence.weakest("{}", fallback = 0.5f), 0.0f)
    }

    @Test
    fun aGuessedCommandIsBelowTheUsableThreshold() {
        val guessed = """{"result": [{"word": "call", "conf": 0.2}]}"""
        assertTrue(EmaDecodeConfidence.weakest(guessed) < EmaDecodeConfidence.MIN_USABLE)
        val clear = """{"result": [{"word": "call", "conf": 0.9}]}"""
        assertFalse(EmaDecodeConfidence.weakest(clear) < EmaDecodeConfidence.MIN_USABLE)
    }

    @Test
    fun scoresAreClampedToThePlatformRange() {
        assertEquals(0f, EmaDecodeConfidence.score(-1f), 0.0f)
        assertEquals(1f, EmaDecodeConfidence.score(2f), 0.0f)
        assertEquals(0.7f, EmaDecodeConfidence.score(0.7f), 0.0f)
    }
}
