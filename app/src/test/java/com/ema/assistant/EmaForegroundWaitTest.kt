package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The foreground wait has to behave like a real phone, not like an ideal one:
 * a cold start that takes seconds, a screen that never stops changing, and
 * screen control that is switched off.
 *
 * These are the fakes the old tests never had: they settled instantly, so
 * every task "passed" while the phone failed.
 */
class EmaForegroundWaitTest {

    /** Deterministic virtual time: scheduled blocks run only when advanced to. */
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

    private fun waiter(time: FakeTime) = EmaConditionWaiter(time.scheduler, { time.now })

    @Test
    fun aScreenThatNeverSettlesStillArrives() {
        val time = FakeTime()
        var arrival: EmaAppArrival? = null
        // A chat list: the text changes on every poll, forever.
        var tick = 0
        EmaForegroundWait.await(
            waiter = waiter(time),
            targetPackage = "com.whatsapp",
            foregroundPackage = { "com.whatsapp" },
            screenText = { "chat " + (tick++) }
        ) { arrival = it }
        time.advance(10_000)
        assertEquals(EmaAppArrival.ARRIVED_BUSY, arrival)
    }

    @Test
    fun aColdStartOfFiveSecondsStillArrives() {
        val time = FakeTime()
        var arrival: EmaAppArrival? = null
        val start = time.now
        EmaForegroundWait.await(
            waiter = waiter(time),
            targetPackage = "com.spotify.music",
            foregroundPackage = { if (time.now - start >= 5_000L) "com.spotify.music" else "com.android.launcher" },
            screenText = { "Spotify" }
        ) { arrival = it }
        // The app arrives at 5 s; the screen then has to be seen as settled.
        time.advance(6_000)
        assertEquals(EmaAppArrival.ARRIVED_SETTLED, arrival)
    }

    @Test
    fun screenControlOffIsNotAFailure() {
        val time = FakeTime()
        var arrival: EmaAppArrival? = null
        // No screen control: the foreground cannot be observed at all.
        EmaForegroundWait.await(
            waiter = waiter(time),
            targetPackage = "com.spotify.music",
            foregroundPackage = { null },
            screenText = { null }
        ) { arrival = it }
        time.advance(100)
        assertEquals(EmaAppArrival.ARRIVED_BUSY, arrival)
    }

    @Test
    fun anAppThatNeverComesToTheForegroundIsNotArrived() {
        val time = FakeTime()
        var arrival: EmaAppArrival? = null
        EmaForegroundWait.await(
            waiter = waiter(time),
            targetPackage = "com.spotify.music",
            foregroundPackage = { "com.android.launcher" },
            screenText = { "Home" }
        ) { arrival = it }
        time.advance(20_000)
        assertEquals(EmaAppArrival.NOT_ARRIVED, arrival)
    }

    @Test
    fun theStabilityWaitIsBoundedAndDoesNotBlockForever() {
        val time = FakeTime()
        var arrival: EmaAppArrival? = null
        var tick = 0
        EmaForegroundWait.await(
            waiter = waiter(time),
            targetPackage = "com.whatsapp",
            foregroundPackage = { "com.whatsapp" },
            screenText = { "typing " + (tick++) }
        ) { arrival = it }
        // The stability budget is spent long before the arrival timeout.
        time.advance(EmaForegroundWait.STABILITY_BUDGET_MS + 500L)
        assertEquals(EmaAppArrival.ARRIVED_BUSY, arrival)
        assertTrue(EmaForegroundWait.STABILITY_BUDGET_MS < EmaForegroundWait.ARRIVAL_TIMEOUT_MS)
    }
}
