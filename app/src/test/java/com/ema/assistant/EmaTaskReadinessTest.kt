package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaTaskReadinessTest {
    private fun screen(body: String = "", send: Boolean = false) = EmaScreenSnapshot("chat.app", listOf(
        EmaNodeSnapshot(0, text = "Daniel", viewId = "chat.app:id/conversation_title", visible = true, enabled = true, top = 30, bottom = 90),
        EmaNodeSnapshot(1, text = body, editable = true, focused = true, visible = true, enabled = true, top = 800, bottom = 950)
    ) + if (send) listOf(EmaNodeSnapshot(2, text = "Send", viewId = "chat.app:id/send", clickable = true, visible = true, enabled = true, top = 800, bottom = 950)) else emptyList())
    private fun stage(s: EmaScreenSnapshot, waited: Long = 0, locked: Boolean = false) =
        EmaTaskReadiness.message(s, "chat.app", "Daniel", "Hello", waited, locked).stage

    @Test fun funSlowLoadingIsObservedUntilTheBoundedDeadline() {
        val loading = EmaScreenSnapshot("chat.app", emptyList())
        assertEquals(EmaTaskReadiness.Stage.WAIT, stage(loading, 7600))
        assertEquals(EmaTaskReadiness.Stage.TIMED_OUT, stage(loading, 8000))
        assertEquals(EmaTaskReadiness.Stage.READY_TO_TYPE, stage(screen(), 7600))
    }
    @Test fun funDraftAndSubmitAreDifferentStages() {
        assertEquals(EmaTaskReadiness.Stage.READY_TO_TYPE, stage(screen()))
        assertEquals(EmaTaskReadiness.Stage.WAIT, stage(screen("Hello")))
        assertEquals(EmaTaskReadiness.Stage.READY_TO_SUBMIT, stage(screen("Hello", true)))
        assertEquals(EmaTaskReadiness.Stage.BLOCKED, stage(screen("Existing private draft", true)))
    }
    @Test fun funWrongAppAndLockNeverPermitTypingOrSubmission() {
        assertEquals(EmaTaskReadiness.Stage.BLOCKED, stage(screen("Hello", true).copy(packageName = "other.app")))
        assertEquals(EmaTaskReadiness.Stage.BLOCKED, stage(screen("Hello", true), locked = true))
        val wrongRecipient = screen("Hello", true).let { it.copy(nodes = it.nodes.map { n -> if (n.text == "Daniel") n.copy(text = "Danielle") else n }) }
        assertEquals(EmaTaskReadiness.Stage.WAIT, stage(wrongRecipient))
    }
}
