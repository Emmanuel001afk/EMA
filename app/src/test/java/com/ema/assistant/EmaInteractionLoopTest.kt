package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaInteractionLoopTest {
    private class Time {
        var now = 0L
        data class Task(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Task>()
        val scheduler = EmaPlanExecutor.Scheduler { delay, run ->
            val task = Task(now + delay, run); tasks += task
            EmaPlanExecutor.Cancellable { task.cancelled = true }
        }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val task = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(task); now = task.at; task.run()
            }
            now = end
        }
    }
    private val button = EmaNodeSnapshot(0, text = "Library", visible = true, enabled = true, clickableSelf = true)
    private val goal = EmaInteractionReasoner.Goal.Activate("Library")
    private fun screen(vararg nodes: EmaNodeSnapshot) = EmaScreenSnapshot("unknown.app", nodes.toList())

    @Test fun observesSlowLoadingThenActsOnceAndVerifiesAStableTransition() {
        val t = Time(); var clicks = 0; var result: EmaInteractionLoop.Result? = null
        val loop = EmaInteractionLoop(t.scheduler, { t.now }, {
            when { t.now < 3000 -> screen(); clicks == 0 -> screen(button); else -> screen(button.copy(text = "Albums")) }
        }, { _, _, _ -> clicks++; EmaInteractionLoop.Dispatch.ACCEPTED })
        loop.run(goal) { result = it }
        t.advance(2800); assertEquals(0, clicks); assertNull(result)
        t.advance(2000); assertEquals(1, clicks); assertEquals(EmaInteractionLoop.Status.VERIFIED, result!!.status)
        assertTrue(result!!.observations > 10)
    }
    @Test fun acceptedButUnobservedActionsAreNotReplayed() {
        val t = Time(); var clicks = 0; var result: EmaInteractionLoop.Result? = null
        EmaInteractionLoop(t.scheduler, { t.now }, { screen(button) }, { _, _, _ -> clicks++; EmaInteractionLoop.Dispatch.ACCEPTED })
            .run(goal) { result = it }
        t.advance(20_000)
        assertEquals(1, clicks)
        assertEquals(EmaInteractionLoop.Status.UNVERIFIED, result!!.status)
        assertTrue(result!!.dispatched)
    }
    @Test fun staleGroundingIsReobservedBeforeAnyAction() {
        val t = Time(); var attempts = 0; var result: EmaInteractionLoop.Result? = null
        EmaInteractionLoop(t.scheduler, { t.now }, { if (attempts < 2) screen(button) else screen(button.copy(text = "Albums")) }, { _, _, _ ->
            attempts++; if (attempts == 1) EmaInteractionLoop.Dispatch.STALE else EmaInteractionLoop.Dispatch.ACCEPTED
        }).run(goal) { result = it }
        t.advance(2000)
        assertEquals(2, attempts); assertEquals(EmaInteractionLoop.Status.VERIFIED, result!!.status)
    }
    @Test fun ambiguityTerminatesWithoutAnAction() {
        val t = Time(); var result: EmaInteractionLoop.Result? = null
        EmaInteractionLoop(t.scheduler, { t.now }, { screen(button, button.copy(index = 1)) }, { _, _, _ -> fail("Must not dispatch"); EmaInteractionLoop.Dispatch.REJECTED })
            .run(goal) { result = it }
        assertEquals(EmaInteractionLoop.Status.NEEDS_INPUT, result!!.status)
    }
    @Test fun packageChangeWhileWaitingBlocksRatherThanUsingAnotherApplicationsControls() {
        val t = Time(); var result: EmaInteractionLoop.Result? = null
        EmaInteractionLoop(t.scheduler, { t.now }, { if (t.now == 0L) screen() else screen(button).copy(packageName = "different.app") },
            { _, _, _ -> fail("Wrong app"); EmaInteractionLoop.Dispatch.REJECTED }).run(goal) { result = it }
        t.advance(1000); assertEquals(EmaInteractionLoop.Status.BLOCKED, result!!.status)
    }
    @Test fun cancellationRemovesFutureObservationAndMutation() {
        val t = Time(); var reads = 0; var clicks = 0; var callbacks = 0
        val handle = EmaInteractionLoop(t.scheduler, { t.now }, { reads++; if (t.now < 500) screen() else screen(button) },
            { _, _, _ -> clicks++; EmaInteractionLoop.Dispatch.ACCEPTED }).run(goal) { callbacks++ }
        handle.cancel(); t.advance(10_000)
        assertEquals(1, reads); assertEquals(0, clicks); assertEquals(0, callbacks)
    }
    @Test fun absentTargetTimesOutWithoutMutation() {
        val t = Time(); var result: EmaInteractionLoop.Result? = null
        EmaInteractionLoop(t.scheduler, { t.now }, { screen() }, { _, _, _ -> fail("No target"); EmaInteractionLoop.Dispatch.REJECTED })
            .run(goal) { result = it }
        t.advance(9000); assertEquals(EmaInteractionLoop.Status.NOT_ACTED, result!!.status); assertFalse(result!!.dispatched)
    }
    @Test fun exactFieldReadbackDoesNotRequireAnUnrelatedFeedToStopChanging() {
        val t = Time(); var writes = 0; var result: EmaInteractionLoop.Result? = null
        val field = button.copy(text = "", editable = true, focused = true, viewId = "entry")
        EmaInteractionLoop(t.scheduler, { t.now }, {
            screen(field.copy(text = if (writes > 0) "Hello" else ""), button.copy(index = 1, text = "Feed ${t.now}"))
        }, { _, _, _ -> writes++; EmaInteractionLoop.Dispatch.ACCEPTED }).run(EmaInteractionReasoner.Goal.EnterText("Hello")) { result = it }
        t.advance(2000); assertEquals(1, writes); assertEquals(EmaInteractionLoop.Status.VERIFIED, result!!.status)
    }
    @Test fun observationFailureAfterDispatchIsUnverifiedNotAnInvitationToRetry() {
        val t = Time(); var calls = 0; var result: EmaInteractionLoop.Result? = null
        EmaInteractionLoop(t.scheduler, { t.now }, { if (calls > 0) throw IllegalStateException() else screen(button) },
            { _, _, _ -> calls++; EmaInteractionLoop.Dispatch.ACCEPTED }).run(goal) { result = it }
        t.advance(1000); assertEquals(1, calls); assertEquals(EmaInteractionLoop.Status.UNVERIFIED, result!!.status)
    }
}
