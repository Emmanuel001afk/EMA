package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A launched app must not stop the rest of the task.
 *
 * This is the regression from the last round: "open Spotify and play ..." and
 * "open WhatsApp and send ..." worked before, then stopped after the first
 * step because the screen never settled and the next step was skipped.
 *
 * The rule under test: only a definite negative (the app is not installed, it
 * never came to the foreground, the launch threw) stops a plan. A missing
 * proof - a busy screen, or screen control that is off - does not.
 */
class EmaTaskContinuationTest {

    private class FakeTime {
        var now = 0L
        private data class Task(val at: Long, val seq: Int, val block: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        private var seq = 0
        val scheduler = EmaPlanExecutor.Scheduler { delay, block ->
            val task = Task(now + delay, seq++, block)
            tasks += task
            EmaPlanExecutor.Cancellable { task.cancelled = true }
        }
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= until }
                    .minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
                tasks.remove(next)
                now = next.at
                next.block()
            }
            now = until
        }
    }

    private fun executor(time: FakeTime) = EmaPlanExecutor(time.scheduler, { time.now }, EmaDuplicateGuard())

    private val apps = listOf(
        "WhatsApp", "Spotify", "YouTube", "Messages"
    ).map { EmaAppResolver.App(it, "pkg." + it.lowercase()) }

    private fun env() = EmaSemanticParser.Env(apps, null, defaultApps = mapOf("video" to "YouTube"))

    // ------------------------------------------------------------- messages

    @Test
    fun aLaunchThatCouldNotBeConfirmedIsUnverifiedButCarriesOn() {
        val outcome = EmaOutcomeClassifier.classify(
            "I launched WhatsApp, but screen control is off, so I could not confirm it opened."
        )
        assertEquals(EmaOutcomeStatus.UNVERIFIED, outcome.status)
        assertTrue("a launched app must not stop the next step", outcome.carriesOn)
    }

    @Test
    fun aBusyScreenIsUnverifiedButCarriesOn() {
        val outcome = EmaOutcomeClassifier.classify(
            "I opened Spotify, but its screen was still changing, so I could not confirm it finished loading. I carried on."
        )
        assertEquals(EmaOutcomeStatus.UNVERIFIED, outcome.status)
        assertTrue(outcome.carriesOn)
    }

    @Test
    fun anAppThatNeverArrivedIsAFailure() {
        val outcome = EmaOutcomeClassifier.classify("I launched Spotify, but it did not come to the foreground in time.")
        assertEquals(EmaOutcomeStatus.FAILED, outcome.status)
        assertFalse(outcome.carriesOn)
    }

    @Test
    fun anAppThatIsNotInstalledIsAFailure() {
        val outcome = EmaOutcomeClassifier.classify("I couldn't open Spotify on this phone.")
        assertEquals(EmaOutcomeStatus.FAILED, outcome.status)
        assertFalse(outcome.carriesOn)
    }

    // ---------------------------------------------------------------- plans

    @Test
    fun aBusyScreenStillLetsTheSendStepRun() {
        val time = FakeTime()
        var sendRan = false
        var report: EmaPlanExecutor.Report? = null
        executor(time).execute(listOf(
            EmaPlanExecutor.Step("Open WhatsApp") {
                it(EmaOutcomeClassifier.classify(
                    "I opened WhatsApp, but its screen was still changing, so I could not confirm it finished loading. I carried on."
                ))
            },
            EmaPlanExecutor.Step("Send the message", consequential = true) {
                sendRan = true
                it(EmaStepOutcome(EmaOutcomeStatus.SUCCESS, "Message submitted to John."))
            }
        ), 60_000) { report = it }
        assertTrue("the send step must still run", sendRan)
        assertEquals(EmaOutcomeStatus.SUCCESS, report!!.outcomes[1].status)
    }

    @Test
    fun screenControlOffStillLetsTheSendStepRun() {
        val time = FakeTime()
        var sendRan = false
        executor(time).execute(listOf(
            EmaPlanExecutor.Step("Open WhatsApp") {
                it(EmaOutcomeClassifier.classify(
                    "I launched WhatsApp, but screen control is off, so I could not confirm it opened."
                ))
            },
            EmaPlanExecutor.Step("Send the message", consequential = true) {
                sendRan = true
                it(EmaStepOutcome(EmaOutcomeStatus.SUCCESS, "Message submitted to John."))
            }
        ), 60_000) { }
        assertTrue("the send step must still run", sendRan)
    }

    @Test
    fun aRealFailureStillStopsThePlan() {
        val time = FakeTime()
        var sendRan = false
        var report: EmaPlanExecutor.Report? = null
        executor(time).execute(listOf(
            EmaPlanExecutor.Step("Open Spotify") {
                it(EmaOutcomeClassifier.classify("I couldn't open Spotify on this phone."))
            },
            EmaPlanExecutor.Step("Play the song") {
                sendRan = true
                it(EmaStepOutcome(EmaOutcomeStatus.SUCCESS, "Playing."))
            }
        ), 60_000) { report = it }
        assertFalse("a missing app must stop the plan", sendRan)
        assertEquals(EmaOutcomeStatus.SKIPPED, report!!.outcomes[1].status)
    }

    // ------------------------------------------------------- understanding

    @Test
    fun openSpotifyAndPlayCalmDownIsTwoSteps() {
        val result = EmaSemanticParser.understand("open Spotify and play Calm Down", env())
        assertTrue(result is EmaSemanticParser.Result.Plan)
        val steps = (result as EmaSemanticParser.Result.Plan).steps
        assertEquals(2, steps.size)
        assertTrue(steps[0].command is EmaCommand.OpenApp)
        assertTrue(steps[1].command is EmaCommand.PlayMedia)
    }

    @Test
    fun openWhatsAppAndSendHiToJohnIsTwoStepsAndReachesTheSend() {
        val result = EmaSemanticParser.understand("open WhatsApp and send hi to John", env())
        assertTrue(result is EmaSemanticParser.Result.Plan)
        val steps = (result as EmaSemanticParser.Result.Plan).steps
        assertEquals(2, steps.size)
        assertTrue(steps[0].command is EmaCommand.OpenApp)
        // The second step is the send, either as a command or as a clause the
        // established layers already handle.
        val second = steps[1]
        val isSend = second.command is EmaCommand.SendMessage ||
            (second.legacyText?.contains("send", ignoreCase = true) == true)
        assertTrue("the second step must be the send, was $second", isSend)
    }
}
