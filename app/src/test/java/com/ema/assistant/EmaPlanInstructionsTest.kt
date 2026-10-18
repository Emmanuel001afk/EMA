package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmaPlanInstructionsTest {

    @Test
    fun stripsStrictSequencingAndGoHomeFromARealRequest() {
        val parsed = EmaPlanInstructions.parse(
            "Open Spotify and play Burna Boy, make sure one action does not hinder another, then go home"
        )
        assertEquals("Open Spotify and play Burna Boy", parsed.cleaned)
        assertTrue(parsed.goHome)
        assertTrue(parsed.strictSequential)
    }

    @Test
    fun acceptsCommonStrictSequencingPhrasings() {
        for (text in listOf(
            "open Spotify and play Burna Boy, make sure one action doesn't hinder another",
            "open Spotify and play Burna Boy, make sure the actions do not interfere with each other",
            "open Spotify and play Burna Boy, one action at a time"
        )) {
            val parsed = EmaPlanInstructions.parse(text)
            assertFalse("instruction survived: ${parsed.cleaned}", parsed.cleaned.contains("make sure", true))
            assertFalse(parsed.cleaned.contains("one action at a time", true))
            assertTrue(parsed.strictSequential)
            assertEquals("open Spotify and play Burna Boy", parsed.cleaned)
        }
    }

    @Test
    fun acceptsGoHomePhrasingsWithoutTurningThemIntoAStep() {
        for (text in listOf(
            "open Spotify and play Burna Boy then go home",
            "open Spotify and play Burna Boy, go home when you're done",
            "open Spotify and play Burna Boy and return home when finished"
        )) {
            val parsed = EmaPlanInstructions.parse(text)
            assertTrue(parsed.goHome)
            assertFalse(parsed.cleaned.lowercase().contains("home"))
        }
    }

    @Test
    fun leavesOrdinaryRequestsUntouched() {
        for (text in listOf(
            "open Spotify",
            "set a timer for 10 minutes",
            "play Burna Boy on Spotify",
            "go home"
        )) {
            assertEquals(text, EmaPlanInstructions.parse(text).cleaned)
            assertFalse(EmaPlanInstructions.parse(text).goHome)
        }
    }

    @Test
    fun neverStripsInsideAMessageBody() {
        val text = "send John a message saying make sure one action does not hinder another"
        val parsed = EmaPlanInstructions.parse(text)
        assertEquals(text, parsed.cleaned)
        assertFalse(parsed.strictSequential)
    }

    @Test
    fun recognisesCancelCommands() {
        for (text in listOf("stop", "Stop it.", "cancel", "NEVER MIND", "abort")) {
            assertTrue(text, EmaPlanInstructions.isCancel(text))
        }
        assertFalse(EmaPlanInstructions.isCancel("stop the music"))
    }

    @Test
    fun identifiesMajorSteps() {
        assertTrue(EmaPlanInstructions.isMajorStep("Open Spotify"))
        assertTrue(EmaPlanInstructions.isMajorStep("Send the message"))
        assertFalse(EmaPlanInstructions.isMajorStep("Go home"))
    }
}

class EmaConditionWaiterTest {

    /** Deterministic virtual time, same model as EmaPlanExecutorTest. */
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

    @Test
    fun succeedsImmediatelyWhenTheConditionAlreadyHolds() {
        val time = FakeTime()
        var result: Boolean? = null
        EmaConditionWaiter(time.scheduler) { time.now }.until(8_000, probe = { true }) { result = it }
        assertEquals(true, result)
    }

    @Test
    fun pollsUntilTheConditionHolds() {
        val time = FakeTime()
        var attempts = 0
        var result: Boolean? = null
        EmaConditionWaiter(time.scheduler) { time.now }.until(8_000, pollMs = 200, probe = { ++attempts >= 4 }) { result = it }
        time.advance(1_000)
        assertEquals(true, result)
        assertEquals(4, attempts)
    }

    @Test
    fun timesOutBoundedInsteadOfHanging() {
        val time = FakeTime()
        var result: Boolean? = null
        EmaConditionWaiter(time.scheduler) { time.now }.until(8_000, pollMs = 200, probe = { false }) { result = it }
        time.advance(7_800)
        assertEquals(null, result)
        time.advance(400)
        assertEquals(false, result)
    }

    @Test
    fun stabilityRequiresTheScreenToStopChanging() {
        val time = FakeTime()
        val samples = ArrayDeque(listOf("a", "a", "b", "b", "b", "b", "b"))
        var result: Boolean? = null
        EmaConditionWaiter(time.scheduler) { time.now }.untilStable(
            timeoutMs = 5_000, pollMs = 150, stableMs = 600, sample = { samples.removeFirstOrNull() }
        ) { result = it }
        time.advance(1_400)
        assertEquals(true, result)
        assertTrue(time.now >= 600)
    }

    @Test
    fun stabilityTimesOutWhenTheScreenKeepsChanging() {
        val time = FakeTime()
        var counter = 0
        var result: Boolean? = null
        EmaConditionWaiter(time.scheduler) { time.now }.untilStable(
            timeoutMs = 3_000, pollMs = 150, stableMs = 600, sample = { "frame${counter++}" }
        ) { result = it }
        time.advance(3_000)
        assertEquals(false, result)
    }

    @Test
    fun cancelStopsFurtherProbes() {
        val time = FakeTime()
        var attempts = 0
        val handle = EmaConditionWaiter(time.scheduler) { time.now }.until(8_000, pollMs = 200, probe = { attempts++; false }) { }
        handle.cancel()
        time.advance(2_000)
        assertEquals(1, attempts)
    }
}
