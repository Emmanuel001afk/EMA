package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaSearchPolicyTest {
    private fun node(i: Int, text: String = "", id: String = "", editable: Boolean = false, focused: Boolean = false) =
        EmaNodeSnapshot(i, text = text, viewId = id, visible = true, enabled = true,
            clickableSelf = !editable, editable = editable, focused = focused)
    private fun screen(vararg nodes: EmaNodeSnapshot) = EmaScreenSnapshot("test.app", nodes.toList())

    @Test fun choosesLabeledSearchInsteadOfArbitraryInput() {
        val s = screen(node(0, "Message", editable = true, focused = true), node(1, id = "app:id/search_src_text", editable = true))
        assertEquals(EmaSearchPolicy.Decision.Type(1), EmaSearchPolicy.decide(s))
    }
    @Test fun refusesChatLoginAndAmbiguousInputs() {
        assertEquals(EmaSearchPolicy.Decision.Wait, EmaSearchPolicy.decide(screen(node(0, "Message", editable = true, focused = true))))
        assertEquals(EmaSearchPolicy.Decision.Wait, EmaSearchPolicy.decide(screen(node(0, "Search", editable = true), node(1, "Search", editable = true))))
        assertEquals(EmaSearchPolicy.Decision.Wait, EmaSearchPolicy.decide(screen(node(0, "Email", editable = true))))
    }
    @Test fun revealsOnlyExplicitSearchNotVoiceOrDestructiveControls() {
        assertEquals(EmaSearchPolicy.Decision.Reveal(1), EmaSearchPolicy.decide(screen(node(0, "Voice search"), node(1, "Search"))))
        assertEquals(EmaSearchPolicy.Decision.Wait, EmaSearchPolicy.decide(screen(node(0, "Clear search history"))))
    }
    @Test fun navigationTabIsNeverASubmitButton() {
        assertNull(EmaSearchPolicy.submitIndex(screen(node(0, "Search"), node(1, "Search tab"))))
        assertEquals(2, EmaSearchPolicy.submitIndex(screen(node(0, "Search"), node(2, "Go"))))
    }
    @Test fun usesClickableAncestorAndIgnoresInvisibleControls() {
        val s = screen(node(0, "Search").copy(clickableSelf = false, clickableAncestorIndex = 1), node(1))
        assertEquals(EmaSearchPolicy.Decision.Reveal(1), EmaSearchPolicy.decide(s))
        assertEquals(EmaSearchPolicy.Decision.Wait, EmaSearchPolicy.decide(screen(node(0, "Search").copy(visible = false))))
    }
    @Test fun blockersMustBeObserved() {
        assertNull(EmaSearchPolicy.blocker(screen(node(0, "My playlist"))))
        assertTrue(EmaSearchPolicy.blocker(screen(node(0, "Sign in"), node(1, "Email")))!!.contains("Sign in"))
        assertTrue(EmaSearchPolicy.blocker(screen(node(0, "No internet")))!!.contains("network"))
    }
    @Test fun titleOnlyRowIsNotDiscardedAsAQueryEcho() {
        val b = EmaResultScreens.Builder("music.app")
        b.chrome("Arcade")
        val row = b.node(click = true, l = 0, t = 600, r = 1080, b = 800)
        b.node(text = "Arcade", parent = row, l = 40, t = 630, r = 900, b = 700)
        val results = EmaResultExtractor.extract(b.build(), "Arcade")
        assertEquals(1, results.size)
        assertEquals("Arcade", results.single().title)
    }

    @Test fun separateTitleAndArtistAreMatchedAsOneRow() {
        val b = EmaResultScreens.Builder("music.app")
        b.chrome("Arcade by Duncan Laurence")
        b.row("Arcade", "Duncan Laurence", null, 600)
        val results = EmaResultExtractor.extract(b.build(), "Arcade by Duncan Laurence")
        assertEquals(1, results.size)
        assertTrue(EmaActionEvidence.mediaQueryMatches("Arcade by Duncan Laurence", results.single().text))
    }

    @Test fun alreadyPopulatedSearchWithReadOnlyQueryCanContinue() {
        val b = EmaResultScreens.Builder("music.app")
        // Screenshot shape: custom-drawn query label, no editable semantics.
        b.node(text = "arcade", l = 120, t = 60, r = 860, b = 160)
        b.node(text = "Search", desc = "Search", click = true, l = 220, t = 2280, r = 410, b = 2400)
        b.row("Arcade", "Song · Duncan Laurence", null, 600)
        assertTrue(EmaSearchPolicy.resultsReady(b.build(), "arcade"))
        assertFalse(EmaSearchPolicy.resultsReady(b.build(), "another song"))
        assertFalse(EmaSearchPolicy.resultsReady(b.build(), ""))
    }

    @Test fun queryEchoWithoutResultsOrRecommendationWithoutQueryIsNotReady() {
        val b = EmaResultScreens.Builder("music.app")
        b.chrome("arcade")
        assertFalse(EmaSearchPolicy.resultsReady(b.build(), "arcade"))
        val home = EmaResultScreens.Builder("music.app")
        home.chrome("other query")
        home.row("Arcade", "Duncan Laurence", null, 600)
        assertFalse(EmaSearchPolicy.resultsReady(home.build(), "arcade"))
    }

    @Test fun duplicatedSearchTextAndDescriptionStillIdentifiesFocusedField() {
        val s = screen(node(0, "arcade", editable = true, focused = true), node(1, "Search").copy(description = "Search"))
        assertEquals(EmaSearchPolicy.Decision.Type(0), EmaSearchPolicy.decide(s))
    }

    @Test fun fuzzyAppMatchCannotDiscardUnrecognizedContinuation() {
        assertEquals("am pt arcade", EmaAppRequestReview.unresolvedTail("Spotify am pt arcade", "Spotify"))
        assertNull(EmaAppRequestReview.unresolvedTail("Spotify app", "Spotify"))
        assertNull(EmaAppRequestReview.unresolvedTail("Spotify", "Spotify"))
        assertNull(EmaAppRequestReview.unresolvedTail("Music", "Music Player"))
        assertEquals("and find report", EmaAppRequestReview.unresolvedTail("Portal and find report", "Portal"))
    }

    @Test fun windowPolicyIgnoresKeyboardAndAssistantButHonorsModals() {
        val app = EmaWindowPolicy.Window(0, "music.app", 1, EmaWindowPolicy.Kind.APPLICATION, false)
        val ime = EmaWindowPolicy.Window(1, "keyboard", 3, EmaWindowPolicy.Kind.IGNORE, true)
        val assistant = EmaWindowPolicy.Window(2, "ema", 4, EmaWindowPolicy.Kind.SYSTEM, true)
        assertEquals(0, EmaWindowPolicy.choose(listOf(app, ime, assistant), "ema", "music.app"))
        assertNull(EmaWindowPolicy.choose(listOf(app), "ema", "other.app"))
        val permission = EmaWindowPolicy.Window(3, "android", 5, EmaWindowPolicy.Kind.SYSTEM, true)
        assertNull(EmaWindowPolicy.choose(listOf(app, permission), "ema", "music.app"))
        assertEquals(3, EmaWindowPolicy.choose(listOf(app, permission), "ema"))
        val another = EmaWindowPolicy.Window(4, "other.app", 6, EmaWindowPolicy.Kind.APPLICATION, true)
        assertNull(EmaWindowPolicy.choose(listOf(app, another), "ema", "music.app"))
    }

    @Test fun mediaMatchesCombineTitleAndArtistWithoutSubstringGuessing() {
        assertTrue(EmaActionEvidence.mediaQueryMatches("Arcade by Duncan Laurence", "Arcade Duncan Laurence Song"))
        assertFalse(EmaActionEvidence.mediaQueryMatches("Arcade", "Arcades"))
        assertFalse(EmaActionEvidence.mediaQueryMatches("Arcade by Duncan Laurence", "Arcade by another artist"))
        assertFalse(EmaActionEvidence.mediaQueryMatches("song", "anything"))
    }
}
