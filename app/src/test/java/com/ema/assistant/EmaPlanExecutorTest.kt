package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaPlanExecutorTest {

    /** Deterministic virtual time: scheduled blocks run only when [advance] reaches them. */
    private class FakeTime {
        var now = 0L
        private data class Task(val at: Long, val seq: Int, val block: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        private var seq = 0
        val scheduler = EmaPlanExecutor.Scheduler { delay, block ->
            val t = Task(now + delay, seq++, block)
            tasks += t
            EmaPlanExecutor.Cancellable { t.cancelled = true }
        }
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= until }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
                tasks.remove(next)
                now = next.at
                next.block()
            }
            now = until
        }
    }

    private fun ok(msg: String = "done") = EmaStepOutcome(EmaOutcomeStatus.SUCCESS, msg)
    private fun fail(msg: String = "I couldn't do it.", retryable: Boolean = false) = EmaStepOutcome(EmaOutcomeStatus.FAILED, msg, retryable)

    private fun executor(time: FakeTime, guard: EmaDuplicateGuard = EmaDuplicateGuard()) =
        EmaPlanExecutor(time.scheduler, { time.now }, guard)

    @Test fun stepsRunInOrderAndAllSucceed() {
        val time = FakeTime()
        val order = mutableListOf<Int>()
        var report: EmaPlanExecutor.Report? = null
        executor(time).execute(listOf(
            EmaPlanExecutor.Step("Search") { order += 1; it(ok("Searched.")) },
            EmaPlanExecutor.Step("Play") { order += 2; it(ok("Playing.")) }
        ), 60_000) { report = it }
        assertEquals(listOf(1, 2), order)
        assertEquals(EmaPlanExecutor.PlanStatus.COMPLETED, report!!.status)
        assertEquals("Searched. Playing.", report!!.summary)
    }

    @Test fun dependentStepIsSkippedAfterFailureAndNotRun() {
        val time = FakeTime()
        var sendRan = false
        var report: EmaPlanExecutor.Report? = null
        executor(time).execute(listOf(
            EmaPlanExecutor.Step("Open the chat with Daniel") { it(fail("I couldn't find Daniel in WhatsApp.")) },
            EmaPlanExecutor.Step("Send the message", consequential = true) { sendRan = true; it(ok()) }
        ), 60_000) { report = it }
        assertFalse(sendRan)
        assertEquals(EmaOutcomeStatus.SKIPPED, report!!.outcomes[1].status)
        assertEquals(EmaPlanExecutor.PlanStatus.FAILED, report!!.status)
        assertTrue(report!!.summary.contains("I couldn't find Daniel"))
        assertTrue(report!!.summary.contains("did not: send the message"))
    }

    @Test fun independentStepStillRunsAfterUnrelatedFailure() {
        val time = FakeTime()
        var report: EmaPlanExecutor.Report? = null
        executor(time).execute(listOf(
            EmaPlanExecutor.Step("Turn on wifi") { it(fail("I couldn't turn on Wi-Fi.")) },
            EmaPlanExecutor.Step("Message Daniel", dependsOnPrevious = false) { it(ok("Sent.")) }
        ), 60_000) { report = it }
        assertEquals(EmaPlanExecutor.PlanStatus.PARTIAL, report!!.status)
    }

    @Test fun unverifiedStepDoesNotUnlockAConsequentialStep() {
        val time = FakeTime()
        var sent = false
        executor(time).execute(listOf(
            EmaPlanExecutor.Step("Open the chat") { it(EmaStepOutcome(EmaOutcomeStatus.UNVERIFIED, "I couldn't confirm the chat opened.")) },
            EmaPlanExecutor.Step("Send", consequential = true) { sent = true; it(ok()) }
        ), 60_000) { }
        assertFalse(sent)
    }

    @Test fun nonConsequentialStepIsRetriedBoundedTimes() {
        val time = FakeTime()
        var attempts = 0
        var report: EmaPlanExecutor.Report? = null
        executor(time).execute(listOf(
            EmaPlanExecutor.Step("Search", maxAttempts = 3) { attempts++; it(fail(retryable = true)) }
        ), 60_000) { report = it }
        time.advance(10_000)
        assertEquals(3, attempts)
        assertEquals(EmaOutcomeStatus.FAILED, report!!.outcomes[0].status)
    }

    @Test fun consequentialStepIsNeverRetried() {
        val time = FakeTime()
        var attempts = 0
        executor(time).execute(listOf(
            EmaPlanExecutor.Step("Send", consequential = true, maxAttempts = 3) { attempts++; it(fail(retryable = true)) }
        ), 60_000) { }
        time.advance(10_000)
        assertEquals(1, attempts)
    }

    @Test fun timeoutStopsStepAndLateCallbackIsIgnored() {
        val time = FakeTime()
        var late: ((EmaStepOutcome) -> Unit)? = null
        var nextRan = false
        var report: EmaPlanExecutor.Report? = null
        executor(time).execute(listOf(
            EmaPlanExecutor.Step("Select the result", timeoutMs = 5_000) { late = it },
            EmaPlanExecutor.Step("Play it") { nextRan = true; it(ok()) }
        ), 60_000) { report = it }
        time.advance(5_000)
        assertEquals(EmaOutcomeStatus.TIMED_OUT, report!!.outcomes[0].status)
        assertFalse(nextRan)
        late!!(ok("too late"))
        assertEquals(EmaOutcomeStatus.TIMED_OUT, report!!.outcomes[0].status)
    }

    @Test fun cancellationStopsThePlan() {
        val time = FakeTime()
        var pending: ((EmaStepOutcome) -> Unit)? = null
        var secondRan = false
        var report: EmaPlanExecutor.Report? = null
        val handle = executor(time).execute(listOf(
            EmaPlanExecutor.Step("Search") { pending = it },
            EmaPlanExecutor.Step("Play") { secondRan = true; it(ok()) }
        ), 60_000) { report = it }
        handle.cancel()
        pending!!(ok())
        assertFalse(secondRan)
        assertEquals(EmaPlanExecutor.PlanStatus.CANCELLED, report!!.status)
    }

    @Test fun identicalMessageIsNotSentTwice() {
        val time = FakeTime()
        val guard = EmaDuplicateGuard()
        val key = EmaDuplicateGuard.messageKey("WhatsApp", "Daniel", "I am coming home")
        var sends = 0
        val step = EmaPlanExecutor.Step("Send", consequential = true, duplicateKey = key) { sends++; it(ok("Sent.")) }
        executor(time, guard).execute(listOf(step), 60_000) { }
        var second: EmaPlanExecutor.Report? = null
        time.advance(10_000)
        executor(time, guard).execute(listOf(step), 60_000) { second = it }
        assertEquals(1, sends)
        assertEquals(EmaOutcomeStatus.BLOCKED, second!!.outcomes[0].status)
        // After the window a deliberate repeat is allowed.
        time.advance(61_000)
        executor(time, guard).execute(listOf(step), 60_000) { }
        assertEquals(2, sends)
    }

    @Test fun aDifferentBodyIsNotBlocked() {
        val guard = EmaDuplicateGuard()
        guard.record(EmaDuplicateGuard.messageKey("WhatsApp", "Daniel", "hi"), 0)
        assertFalse(guard.recentlyDone(EmaDuplicateGuard.messageKey("WhatsApp", "Daniel", "hi again"), 1_000))
        assertTrue(guard.recentlyDone(EmaDuplicateGuard.messageKey("WhatsApp", "Daniel", "hi"), 1_000))
    }

    @Test fun needsPermissionStopsRemainingSteps() {
        val time = FakeTime()
        var report: EmaPlanExecutor.Report? = null
        executor(time).execute(listOf(
            EmaPlanExecutor.Step("Search") { it(EmaStepOutcome(EmaOutcomeStatus.NEEDS_PERMISSION, "Turn on screen control.")) },
            EmaPlanExecutor.Step("Other", dependsOnPrevious = false) { it(ok()) }
        ), 60_000) { report = it }
        assertEquals(EmaPlanExecutor.PlanStatus.NEEDS_PERMISSION, report!!.status)
        assertEquals(EmaOutcomeStatus.SKIPPED, report!!.outcomes[1].status)
    }

    @Test fun planDeadlineIsEnforced() {
        val time = FakeTime()
        var report: EmaPlanExecutor.Report? = null
        executor(time).execute(listOf(
            EmaPlanExecutor.Step("Slow", timeoutMs = 50_000) { },
            EmaPlanExecutor.Step("After") { it(ok()) }
        ), 8_000) { report = it }
        time.advance(8_000)
        assertNotNull(report)
        assertEquals(EmaOutcomeStatus.TIMED_OUT, report!!.outcomes[0].status)
    }

    @Test fun throwingStepFailsCleanly() {
        val time = FakeTime()
        var report: EmaPlanExecutor.Report? = null
        executor(time).execute(listOf(EmaPlanExecutor.Step("Crash") { throw IllegalStateException("boom") }), 10_000) { report = it }
        assertEquals(EmaOutcomeStatus.FAILED, report!!.outcomes[0].status)
        assertFalse(report!!.summary.contains("boom"))
    }

    // ---------------------------------------------------- classifier

    @Test fun classifierReadsExistingExecutorSentences() {
        fun c(s: String) = EmaOutcomeClassifier.classify(s).status
        assertEquals(EmaOutcomeStatus.SUCCESS, c("Opened YouTube."))
        assertEquals(EmaOutcomeStatus.FAILED, c("I couldn't find Daniel in your contacts."))
        assertEquals(EmaOutcomeStatus.UNVERIFIED, c("I opened the video but couldn't confirm playback."))
        assertEquals(EmaOutcomeStatus.NEEDS_PERMISSION, c("EMA needs Contacts access. Please grant it."))
        assertEquals(EmaOutcomeStatus.NEEDS_INPUT, c("I found 2 contacts named Daniel. Which one do you mean?"))
        assertEquals(EmaOutcomeStatus.BLOCKED, c("I sent that exact message to Daniel less than a minute ago, so I did not send it again."))
        assertEquals(EmaOutcomeStatus.FAILED, c(""))
        assertEquals(EmaOutcomeStatus.FAILED, EmaOutcomeClassifier.classify("Done.", ok = false).status)
    }
    @Test fun cancelAfterCurrentKeepsTheFinishedStepAndStopsTheRest() {
        val time = FakeTime()
        var nextRan = false
        var report: EmaPlanExecutor.Report? = null
        var handle: EmaPlanExecutor.Handle? = null
        var finishFirst: ((EmaStepOutcome) -> Unit)? = null
        handle = executor(time).execute(listOf(
            EmaPlanExecutor.Step("Open Spotify") { finishFirst = it },
            EmaPlanExecutor.Step("Play Burna Boy") { nextRan = true; it(ok()) }
        ), 60_000) { report = it }
        // The user answered "no" to "step 1 done - continue?": the running step
        // still settles with its real outcome, the rest is cancelled.
        handle.cancelAfterCurrent()
        finishFirst!!(ok("Opened Spotify."))
        assertEquals(EmaOutcomeStatus.SUCCESS, report!!.outcomes[0].status)
        assertEquals(EmaOutcomeStatus.CANCELLED, report!!.outcomes[1].status)
        assertEquals(EmaPlanExecutor.PlanStatus.CANCELLED, report!!.status)
        assertFalse(nextRan)
        assertTrue(report!!.summary.contains("Stopped after 1 of 2 steps"))
        assertTrue(report!!.summary.contains("play Burna Boy"))
    }
}
