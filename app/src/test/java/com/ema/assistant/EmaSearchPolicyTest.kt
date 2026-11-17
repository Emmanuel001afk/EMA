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
    @Test fun mediaMatchesCombineTitleAndArtistWithoutSubstringGuessing() {
        assertTrue(EmaActionEvidence.mediaQueryMatches("Arcade by Duncan Laurence", "Arcade Duncan Laurence Song"))
        assertFalse(EmaActionEvidence.mediaQueryMatches("Arcade", "Arcades"))
        assertFalse(EmaActionEvidence.mediaQueryMatches("Arcade by Duncan Laurence", "Arcade by another artist"))
        assertFalse(EmaActionEvidence.mediaQueryMatches("song", "anything"))
    }
}
