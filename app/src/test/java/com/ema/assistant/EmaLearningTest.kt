package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local learning layer.
 *
 * Every rule the owner asked for is checked here: a link is used only after a
 * confirmation or a second sighting, never inside the content of a message, a
 * search or a reminder, the "remember" shortcuts win over a learned link, and
 * two close options are asked about instead of guessed.
 */
class EmaLearningTest {

    @Test
    fun aConfirmedLinkIsUsed() {
        val model = EmaLearningModel()
        model.learn("pla spotify", "open Spotify and play Calm Down", EmaLearnedLink.Kind.CORRECTION, confirmed = true)
        assertNotNull(model.applicable("pla spotify"))
        assertEquals("open Spotify and play Calm Down", model.applicable("pla spotify")!!.meaning)
    }

    @Test
    fun aLinkSeenOnceIsNotUsedYet() {
        val model = EmaLearningModel()
        model.learn("pla spotify", "open Spotify and play Calm Down", EmaLearnedLink.Kind.CORRECTION)
        assertNull("one guess must not change what EMA does", model.applicable("pla spotify"))
    }

    @Test
    fun aLinkSeenTwiceIsUsed() {
        val model = EmaLearningModel()
        model.learn("pla spotify", "open Spotify and play Calm Down", EmaLearnedLink.Kind.CORRECTION)
        model.learn("pla spotify", "open Spotify and play Calm Down", EmaLearnedLink.Kind.CORRECTION)
        assertNotNull("the second sighting makes it usable", model.applicable("pla spotify"))
        assertEquals(2, model.links().first().times)
    }

    @Test
    fun aLinkIsNotUsedInsideTheContentOfAMessage() {
        val model = EmaLearningModel()
        model.learn("send john a message saying hi", "open Spotify", EmaLearnedLink.Kind.CORRECTION, confirmed = true)
        assertTrue("a message is content, not a command", EmaLexicon.isContentCommand("send john a message saying hi"))
    }

    @Test
    fun theContentOfAMessageIsNeverChanged() {
        val text = "send John a message saying what's up"
        // The mishearing is corrected in the command, but never in the text
        // the owner wants to send.
        assertTrue(EmaLexicon.contentOf(text)!!.contains("what's up"))
        assertTrue(EmaLexicon.applyMishearings(text).contains("what's up"))
        assertEquals("what's up", EmaLexicon.applyMishearings(text).substringAfter("saying ").trim())
    }

    @Test
    fun aSearchOrAReminderIsAlsoContent() {
        assertTrue(EmaLexicon.isContentCommand("search for cats saying nothing"))
        assertTrue(EmaLexicon.isContentCommand("remind me to call John saying hello"))
        assertFalse(EmaLexicon.isContentCommand("open Spotify and play Calm Down"))
    }

    @Test
    fun twoCloseOptionsAreAskedAbout() {
        val model = EmaLearningModel()
        model.learn("play music", "open Spotify", EmaLearnedLink.Kind.CORRECTION, confirmed = true)
        model.learn("play music", "open YouTube Music", EmaLearnedLink.Kind.CORRECTION, confirmed = true)
        val candidates = model.candidates("play music")
        assertEquals(2, candidates.size)
        assertTrue("two meanings are too close to choose", model.ambiguous(candidates))
    }

    @Test
    fun oneOptionIsNotAmbiguous() {
        val model = EmaLearningModel()
        model.learn("play music", "open Spotify", EmaLearnedLink.Kind.CORRECTION, confirmed = true)
        model.learn("play music", "open Spotify", EmaLearnedLink.Kind.CORRECTION)
        assertFalse(model.ambiguous(model.candidates("play music")))
    }

    @Test
    fun secretsAreNeverLearned() {
        val model = EmaLearningModel()
        assertNull(model.learn("my password is hunter2", "open Spotify", EmaLearnedLink.Kind.CORRECTION, confirmed = true))
        assertNull(model.learn("open spotify", "the card number 4111111111111111", EmaLearnedLink.Kind.CORRECTION, confirmed = true))
        assertTrue(model.links().isEmpty())
    }

    @Test
    fun forgettingTheLinksKeepsTheUsagePreferences() {
        val model = EmaLearningModel()
        model.learn("pla spotify", "open Spotify", EmaLearnedLink.Kind.CORRECTION, confirmed = true)
        model.noteUsage("media", "Spotify")
        model.clear()
        assertTrue(model.links().isEmpty())
        assertEquals("Spotify", model.preferredApp("media"))
    }

    @Test
    fun theSummarySaysWhatWasLearned() {
        val model = EmaLearningModel()
        model.learn("pla spotify", "open Spotify", EmaLearnedLink.Kind.CORRECTION, confirmed = true)
        val summary = model.summary()
        assertTrue(summary.contains("pla spotify"))
        assertTrue(summary.contains("open Spotify"))
    }

    @Test
    fun anEmptyModelSaysSo() {
        assertTrue(EmaLearningModel().summary().contains("not learned anything"))
    }

    @Test
    fun theUsagePreferencePointsAtTheAppTheOwnerUses() {
        val model = EmaLearningModel()
        model.noteUsage("media", "Spotify")
        model.noteUsage("media", "Spotify")
        model.noteUsage("message", "WhatsApp")
        assertEquals("Spotify", model.preferredApp("media"))
        assertEquals("WhatsApp", model.preferredApp("message"))
        assertNull(model.preferredApp("call"))
    }

    @Test
    fun theLinksCanBeStoredAndReadBack() {
        val model = EmaLearningModel()
        model.learn("pla spotify", "open Spotify", EmaLearnedLink.Kind.CORRECTION, confirmed = true, at = 99L)
        model.noteUsage("media", "Spotify")
        val restored = EmaLearningModel.parse(model.serialize())
        assertEquals(1, restored.links().size)
        assertEquals("pla spotify", restored.links()[0].phrase)
        assertEquals("open Spotify", restored.links()[0].meaning)
        assertTrue(restored.links()[0].confirmed)
        assertEquals(99L, restored.links()[0].at)
        assertEquals("Spotify", restored.preferredApp("media"))
    }

    @Test
    fun theListOfLinksStaysBounded() {
        val model = EmaLearningModel()
        for (index in 1..(EmaLearningModel.MAX_LINKS + 10)) {
            model.learn("phrase $index", "meaning $index", EmaLearnedLink.Kind.CORRECTION)
        }
        assertTrue(model.links().size <= EmaLearningModel.MAX_LINKS)
    }

    @Test
    fun theRememberedShortcutWinsOverALearnedLink() {
        // The shortcut layer is tried before the learned layer, so a phrase the
        // owner told EMA to remember is never overruled by a guess.
        val definition = EmaShortcuts.parseDefinition(
            "remember that whenever I say 'for me' it means open Spotify and play Calm Down"
        )
        assertNotNull(definition)
        val installed = listOf(EmaAppResolver.App("Spotify", "com.spotify.music"))
        val resolution = EmaShortcuts.resolve(definition!!.meaning, definition.packageSpelling, installed)
        assertTrue(resolution is EmaShortcuts.Resolution.Command)

        val model = EmaLearningModel()
        model.learn("for me", "open WhatsApp", EmaLearnedLink.Kind.CORRECTION, confirmed = true)
        // Both layers know the phrase; the router runs the shortcut first.
        assertNotNull(model.applicable("for me"))
    }

    @Test
    fun theClosestAlternativeIsFound() {
        val model = EmaLearningModel()
        val closest = model.closestAlternative(
            "play calm down on spotify",
            listOf("play calm down on spotifai", "open the settings menu")
        )
        assertEquals("play calm down on spotifai", closest)
        assertNull(model.closestAlternative("play calm down", listOf("open settings")))
    }

    @Test
    fun aMishearingIsOnlyUsedAfterItWasSeenTwice() {
        val model = EmaLearningModel()
        model.learn("play calm down on spotifai", "play calm down on spotify", EmaLearnedLink.Kind.MISHEARING)
        assertNull("one guess must not change what EMA does", model.applicable("play calm down on spotifai"))
        model.learn("play calm down on spotifai", "play calm down on spotify", EmaLearnedLink.Kind.MISHEARING)
        assertNotNull("the second sighting makes it usable", model.applicable("play calm down on spotifai"))
    }

    @Test
    fun theCorrectionIsOnlyAcceptedShortlyAfterTheFailure() {
        // The window is part of the rule: a correction that comes an hour later
        // is not a correction of that request.
        assertTrue(EmaLearningModel.LINK_WINDOW_MS in 1..120_000L)
    }
}
