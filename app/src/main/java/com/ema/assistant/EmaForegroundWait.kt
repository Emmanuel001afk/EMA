package com.ema.assistant

/**
 * How a launched application reached the screen.
 *
 * The old rule was "the package is in front AND its screen text stopped
 * changing for 600 ms within 1.5 s". Real apps violate the second half
 * constantly: a chat list keeps scrolling indicators alive, a feed keeps
 * loading, a player shows a moving progress bar. Requiring stillness turned
 * working tasks into failures, so the screen settling is now best effort.
 */
enum class EmaAppArrival {
    /** The app is in front and its screen stopped changing. */
    ARRIVED_SETTLED,

    /**
     * The app is in front but still busy (or could not be observed because
     * screen control is off). The action happened; only the proof is missing.
     * The next step still runs.
     */
    ARRIVED_BUSY,

    /** The app never came to the foreground while screen control was on. */
    NOT_ARRIVED
}

/**
 * Waiting for a launched app to become usable.
 *
 * Rules that came out of real phones:
 *
 *  - Arrival in the foreground is what matters. A screen that never settles
 *    (a feed, "typing…", a progress bar) must not fail the task, so the
 *    stability wait is bounded and best effort: after the budget the caller
 *    continues with [EmaAppArrival.ARRIVED_BUSY].
 *  - Without screen control EMA cannot observe the foreground at all. That is
 *    not a failure of the app: the caller is told the truth and continues.
 *  - Only a definite negative - the package never came to the foreground while
 *    EMA could see the screen - is [EmaAppArrival.NOT_ARRIVED].
 *
 * Pure Kotlin with injected probes and clock, so the behaviour is unit tested
 * with fakes that behave like real phones (slow cold start, a screen that
 * never settles, screen control switched off).
 */
object EmaForegroundWait {

    /** How long EMA waits for the package to reach the foreground. */
    const val ARRIVAL_TIMEOUT_MS = 6_500L

    /** How long EMA waits for the screen to stop changing. Best effort. */
    const val STABILITY_BUDGET_MS = 3_000L

    /** How long the screen must be unchanged to count as settled. */
    const val STABLE_MS = 600L

    /**
     * @param foregroundPackage the package currently in front, or null when
     *        screen control is unavailable (nothing can be observed).
     * @param screenText the visible text of the current screen, or null when
     *        it cannot be read.
     */
    fun await(
        waiter: EmaConditionWaiter,
        targetPackage: String,
        foregroundPackage: () -> String?,
        screenText: () -> String?,
        arrivalTimeoutMs: Long = ARRIVAL_TIMEOUT_MS,
        stabilityBudgetMs: Long = STABILITY_BUDGET_MS,
        stableMs: Long = STABLE_MS,
        done: (EmaAppArrival) -> Unit
    ): EmaPlanExecutor.Cancellable {
        // Screen control off: EMA cannot tell whether the app arrived. That is
        // not a failure of the app, so the caller continues and says so.
        if (foregroundPackage() == null) {
            done(EmaAppArrival.ARRIVED_BUSY)
            return EmaPlanExecutor.Cancellable { }
        }
        return waiter.until(
            timeoutMs = arrivalTimeoutMs,
            pollMs = 150L,
            probe = { foregroundPackage() == targetPackage }
        ) { arrived ->
            if (!arrived) {
                done(EmaAppArrival.NOT_ARRIVED)
                return@until
            }
            waiter.untilStable(
                timeoutMs = stabilityBudgetMs,
                pollMs = 150L,
                stableMs = stableMs,
                sample = screenText
            ) { settled ->
                done(if (settled) EmaAppArrival.ARRIVED_SETTLED else EmaAppArrival.ARRIVED_BUSY)
            }
        }
    }
}
