package com.ema.assistant

/**
 * Wait for observable conditions instead of sleeping for a fixed delay.
 *
 * Cold-starting an application can take seconds; a fixed 900 ms delay made EMA
 * type into whatever was on screen while Spotify was still loading. Every wait
 * here is bounded, polls a real probe, and reports whether the condition was
 * met — never assumes it.
 *
 * Time and scheduling are injected, so the policy is unit tested with a fake
 * clock exactly like [EmaPlanExecutor].
 */
class EmaConditionWaiter(
    private val scheduler: EmaPlanExecutor.Scheduler,
    private val clock: () -> Long
) {
    /**
     * Poll [probe] until it is true or [timeoutMs] elapses.
     * The probe is evaluated immediately before scheduling anything.
     */
    fun until(
        timeoutMs: Long,
        pollMs: Long = 150L,
        probe: () -> Boolean,
        done: (Boolean) -> Unit
    ): EmaPlanExecutor.Cancellable = runUntil(
        timeoutMs = timeoutMs,
        pollMs = pollMs,
        probe = { if (probe()) 1 else 0 },
        satisfied = { it == 1 },
        done = done
    )

    /**
     * Wait until [sample] is non-null and unchanged for [stableMs].
     * This is how "the screen has stopped changing" is decided.
     */
    fun untilStable(
        timeoutMs: Long,
        pollMs: Long = 150L,
        stableMs: Long = 600L,
        sample: () -> String?,
        done: (Boolean) -> Unit
    ): EmaPlanExecutor.Cancellable {
        val startedAt = clock()
        var last: String? = sample()
        var lastChangeAt = startedAt
        var cancelled = false
        var pending: EmaPlanExecutor.Cancellable? = null

        fun finish(value: Boolean) {
            if (cancelled) return
            cancelled = true
            pending = null
            done(value)
        }

        fun poll() {
            if (cancelled) return
            val now = clock()
            val current = sample()
            if (current != last) {
                last = current
                lastChangeAt = now
            }
            if (current != null && current == last && now - lastChangeAt >= stableMs) {
                finish(true)
                return
            }
            if (now - startedAt >= timeoutMs) {
                finish(false)
                return
            }
            pending = scheduler.schedule(pollMs) { poll() }
        }

        poll()
        return EmaPlanExecutor.Cancellable {
            cancelled = true
            pending?.cancel()
        }
    }

    /** A cancellable wait used only to space out retries (not as evidence). */
    fun delay(ms: Long, done: () -> Unit): EmaPlanExecutor.Cancellable {
        var cancelled = false
        val pending = scheduler.schedule(ms) { if (!cancelled) done() }
        return EmaPlanExecutor.Cancellable {
            cancelled = true
            pending.cancel()
        }
    }

    private fun runUntil(
        timeoutMs: Long,
        pollMs: Long,
        probe: () -> Int,
        satisfied: (Int) -> Boolean,
        done: (Boolean) -> Unit
    ): EmaPlanExecutor.Cancellable {
        val startedAt = clock()
        var cancelled = false
        var pending: EmaPlanExecutor.Cancellable? = null

        fun finish(value: Boolean) {
            if (cancelled) return
            cancelled = true
            pending = null
            done(value)
        }

        fun poll() {
            if (cancelled) return
            val value = probe()
            if (satisfied(value)) {
                finish(true)
                return
            }
            if (clock() - startedAt >= timeoutMs) {
                finish(false)
                return
            }
            pending = scheduler.schedule(pollMs) { poll() }
        }

        poll()
        return EmaPlanExecutor.Cancellable {
            cancelled = true
            pending?.cancel()
        }
    }
}
