package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaInteractionReasonerTest {
    private fun control(i: Int, label: String) = EmaNodeSnapshot(i, text = label, visible = true, enabled = true, clickableSelf = true)
    private fun screen(vararg n: EmaNodeSnapshot) = EmaScreenSnapshot("any.app", n.toList())
    private fun decide(goal: EmaInteractionReasoner.Goal, s: EmaScreenSnapshot) = EmaInteractionReasoner.assess(goal, s, "any.app")

    @Test fun sameGroundingLogicWorksAcrossUnclassifiedApplications() {
        for (pkg in listOf("private.accounting", "oem.settings", "reader.unknown", "music.player", "chat.private")) {
            val s = EmaScreenSnapshot(pkg, listOf(control(0, "Library")))
            assertEquals(EmaInteractionReasoner.State.READY, EmaInteractionReasoner.assess(EmaInteractionReasoner.Goal.Activate("Library"), s, pkg).state)
        }
    }
    @Test fun duplicateLabelsAskInsteadOfChoosingTheFirstMatch() {
        val goal = EmaInteractionReasoner.Goal.Activate("Open")
        assertEquals(EmaInteractionReasoner.State.NEEDS_INPUT, decide(goal, screen(control(0, "Open"), control(1, "Open"))).state)
        val child = control(1, "Open").copy(clickableSelf = false, clickableAncestorIndex = 0)
        assertEquals(EmaInteractionReasoner.State.READY, decide(goal, screen(control(0, "Open"), child)).state)
    }
    @Test fun semanticExpansionDoesNotAuthorizeALargerOperation() {
        assertEquals(EmaInteractionReasoner.State.NEEDS_INPUT, decide(EmaInteractionReasoner.Goal.Activate("Delete"), screen(control(0, "Delete all accounts"))).state)
        assertEquals(EmaInteractionReasoner.State.READY, decide(EmaInteractionReasoner.Goal.Activate("Settings"), screen(control(0, "Settings button"))).state)
    }
    @Test fun nonemptyAndAmbiguousFieldsRequireUserInput() {
        val field = control(0, "").copy(editable = true, focused = true, viewId = "composer")
        val goal = EmaInteractionReasoner.Goal.EnterText("Hello")
        assertEquals(EmaInteractionReasoner.State.READY, decide(goal, screen(field)).state)
        assertEquals(EmaInteractionReasoner.State.SATISFIED, decide(goal, screen(field.copy(text = "Hello"))).state)
        assertEquals(EmaInteractionReasoner.State.NEEDS_INPUT, decide(goal, screen(field.copy(text = "A private draft"))).state)
        assertEquals(EmaInteractionReasoner.State.NEEDS_INPUT, decide(goal, screen(field.copy(focused = false), field.copy(index = 1, focused = false))).state)
    }
    @Test fun effectsAreTiedToTheSelectedFieldOrToggle() {
        val field = control(0, "").copy(editable = true, focused = true, viewId = "composer")
        val before = screen(field)
        assertFalse(EmaInteractionReasoner.effectObserved(EmaInteractionReasoner.Goal.EnterText("Hello"), before, field, screen(field, control(1, "Hello"))))
        assertTrue(EmaInteractionReasoner.effectObserved(EmaInteractionReasoner.Goal.EnterText("Hello"), before, field, screen(field.copy(text = "Hello"))))
        val toggle = control(0, "Feature").copy(checkable = true)
        assertFalse(EmaInteractionReasoner.effectObserved(EmaInteractionReasoner.Goal.Activate("Feature"), screen(toggle), toggle, screen(toggle, control(1, "An ad changed"))))
        assertTrue(EmaInteractionReasoner.effectObserved(EmaInteractionReasoner.Goal.Activate("Feature"), screen(toggle), toggle, screen(toggle.copy(checked = true))))
    }
    @Test fun packageOwnershipAndMissingControlsAreNotConfused() {
        val goal = EmaInteractionReasoner.Goal.Activate("Library")
        assertEquals(EmaInteractionReasoner.State.WAIT, decide(goal, screen()).state)
        assertEquals(EmaInteractionReasoner.State.BLOCKED, decide(goal, screen(control(0, "Library")).copy(packageName = "other.app")).state)
        assertEquals(EmaInteractionReasoner.fingerprint(screen(control(0, "Library"))), EmaInteractionReasoner.fingerprint(screen(control(8, "Library").copy(top = 55))))
    }
}
