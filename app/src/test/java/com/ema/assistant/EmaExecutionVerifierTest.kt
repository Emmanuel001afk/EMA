package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playback and verification decisions must not over-claim.
 *
 * These cover the rules that decide what EMA says out loud after an action, so a
 * false "it's playing" or a false "message sent" is caught here rather than in
 * front of the user.
 */
class EmaExecutionVerifierTest {

    // ------------------------------------------------------------ mediaPlaying

    @Test
    fun barePauseControlIsNotProofOfPlayback() {
        // A stopped player still renders its transport bar with a Pause control.
        assertFalse(EmaExecutionVerifier.mediaPlaying("Spotify Home Pause Play Next Previous"))
        assertFalse(EmaExecutionVerifier.mediaPlaying("Pause"))
    }

    @Test
    fun explicitStopEvidenceBeatsLoosePlayingMatch() {
        assertFalse(EmaExecutionVerifier.mediaPlaying("Not playing"))
        assertFalse(EmaExecutionVerifier.mediaPlaying("Playback stopped"))
        assertFalse(EmaExecutionVerifier.mediaPlaying("Song queue Playing paused"))
    }

    @Test
    fun pausedScreenIsNotPlayingEvenWithNowPlayingTitle() {
        assertFalse(EmaExecutionVerifier.mediaPlaying("Now playing Paused 0:41 / 3:12"))
    }

    @Test
    fun activePlaybackSignalsAreRecognised() {
        assertTrue(EmaExecutionVerifier.mediaPlaying("Pause playback"))
        assertTrue(EmaExecutionVerifier.mediaPlaying("Pause button"))
        assertTrue(EmaExecutionVerifier.mediaPlaying("Now playing Hey Jude"))
        assertTrue(EmaExecutionVerifier.mediaPlaying("Queue Playing 2 of 14"))
    }

    @Test
    fun emptyAndIrrelevantUiIsNotPlaying() {
        assertFalse(EmaExecutionVerifier.mediaPlaying(""))
        assertFalse(EmaExecutionVerifier.mediaPlaying("   "))
        assertFalse(EmaExecutionVerifier.mediaPlaying("Recently played playlists"))
    }

    @Test
    fun mediaPlayingIsCaseAndWhitespaceInsensitive() {
        assertTrue(EmaExecutionVerifier.mediaPlaying("  NOW   PLAYING  "))
        assertTrue(EmaExecutionVerifier.mediaPlaying("PAUSE   BUTTON"))
    }

    // ----------------------------------------------------------- playbackLikely

    @Test
    fun audioStateIsAuthoritativeEvenWithUnhelpfulUi() {
        assertTrue(EmaExecutionVerifier.playbackLikely("Spotify Home Search Library", audioActive = true))
    }

    @Test
    fun audioStateAloneSufficesForVideoPlayback() {
        // Video players keep the audio session active; the UI may say nothing.
        assertTrue(EmaExecutionVerifier.playbackLikely("", audioActive = true))
    }

    @Test
    fun uiIsTheFallbackWhenAudioStateIsUnavailable() {
        assertTrue(EmaExecutionVerifier.playbackLikely("Pause playback", audioActive = false))
        assertFalse(EmaExecutionVerifier.playbackLikely("Pause playback paused", audioActive = false))
    }

    @Test
    fun noEvidenceMeansNoClaim() {
        assertFalse(EmaExecutionVerifier.playbackLikely("Home Search Library", audioActive = false))
        assertFalse(EmaExecutionVerifier.playbackLikely("", audioActive = false))
    }

    // ---------------------------------------------------------------- foreground

    @Test
    fun foregroundMatchesExactPackageOnly() {
        assertTrue(EmaExecutionVerifier.foregroundIs("com.spotify.music", "com.spotify.music"))
        assertFalse(EmaExecutionVerifier.foregroundIs("com.android.launcher", "com.spotify.music"))
        assertFalse(EmaExecutionVerifier.foregroundIs("com.whatsapp", "com.whatsapp.w4b"))
    }

    @Test
    fun foregroundIgnoresSurroundingWhitespace() {
        assertTrue(EmaExecutionVerifier.foregroundIs("  com.whatsapp \n", "com.whatsapp"))
    }

    @Test
    fun unknownExpectedPackageDoesNotFailTheAction() {
        // When EMA has no expectation, absence of evidence is not failure.
        assertTrue(EmaExecutionVerifier.foregroundIs("com.anything", ""))
    }

    // ------------------------------------------------------------- query/recipient

    @Test
    fun queryVisibleRequiresEverySignificantWord() {
        val ui = "Search results Shape of You Ed Sheeran Play"
        assertTrue(EmaExecutionVerifier.queryVisible(ui, "shape of you"))
        assertTrue(EmaExecutionVerifier.queryVisible(ui, "Shape   Of   You"))
        assertFalse(EmaExecutionVerifier.queryVisible(ui, "shape of you acoustic"))
    }

    @Test
    fun queryVisibleIgnoresSingleCharacterWords() {
        assertTrue(EmaExecutionVerifier.queryVisible("Song a Live", "a"))
        assertTrue(EmaExecutionVerifier.queryVisible("Anything here", ""))
    }

    @Test
    fun recipientVisibleRejectsBlankRecipient() {
        assertFalse(EmaExecutionVerifier.recipientVisible("Chat with Ada", ""))
        assertFalse(EmaExecutionVerifier.recipientVisible("Chat with Ada", "   "))
    }

    @Test
    fun recipientVisibleNeedsAllNameParts() {
        assertTrue(EmaExecutionVerifier.recipientVisible("Chat with Ada Lovelace", "ada lovelace"))
        assertFalse(EmaExecutionVerifier.recipientVisible("Chat with Ada", "ada lovelace"))
    }

    @Test
    fun inputClearedOnlyForBlankFields() {
        assertTrue(EmaExecutionVerifier.inputCleared(""))
        assertTrue(EmaExecutionVerifier.inputCleared("   \t "))
        assertFalse(EmaExecutionVerifier.inputCleared("on my way"))
    }
}
