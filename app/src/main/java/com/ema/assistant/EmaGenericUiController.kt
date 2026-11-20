package com.ema.assistant

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** All generic interactions share observation, grounding, stale-state checks and effect verification. */
object EmaGenericUiController {
    private val main = Handler(Looper.getMainLooper())
    private var active: EmaPlanExecutor.Cancellable? = null
    private var generation = 0L
    private val scheduler = EmaPlanExecutor.Scheduler { delay, task ->
        val runnable = Runnable { task() }
        main.postDelayed(runnable, delay)
        EmaPlanExecutor.Cancellable { main.removeCallbacks(runnable) }
    }

    fun inspect(): String = EmaAccessibilityService.inspectCurrentUi()

    /** A cancelled/replaced command cannot perform a delayed interaction later. */
    fun cancelPendingAction() {
        generation++
        active?.cancel()
        active = null
    }

    private fun interact(goal: EmaInteractionReasoner.Goal, callback: (Boolean, String) -> Unit) {
        cancelPendingAction()
        val token = generation
        val unavailable = EmaAccessibilityService.availabilityProblem()
        if (unavailable != null) { callback(false, unavailable); return }
        val loop = EmaInteractionLoop(scheduler, { SystemClock.elapsedRealtime() },
            { EmaAccessibilityService.screenSnapshot(1200) },
            { action, before, _ -> EmaAccessibilityService.performGroundedInteraction(action, before) })
        val handle = loop.run(goal) { result ->
            if (generation != token) return@run
            active = null
            val detail = when (result.status) {
                EmaInteractionLoop.Status.VERIFIED -> when (goal) {
                    is EmaInteractionReasoner.Goal.EnterText -> "The exact requested text is verified in the intended field."
                    is EmaInteractionReasoner.Goal.Activate -> "Activated ${goal.label}; an effect was observed. This does not verify any larger task."
                    is EmaInteractionReasoner.Goal.Scroll -> "Scrolled ${goal.direction}; visible content changed."
                }
                EmaInteractionLoop.Status.NEEDS_INPUT -> "I need clarification: ${result.reason}"
                EmaInteractionLoop.Status.BLOCKED -> "I blocked this interaction: ${result.reason}"
                EmaInteractionLoop.Status.NOT_ACTED -> "I could not complete the interaction. ${result.reason}"
                EmaInteractionLoop.Status.UNVERIFIED -> "I could not verify the interaction's effect. ${result.reason}"
            }
            callback(result.status == EmaInteractionLoop.Status.VERIFIED, detail)
        }
        // Synchronous clarification may have started another interaction in the callback.
        if (generation == token) active = handle else handle.cancel()
    }

    fun tap(target: String, callback: (Boolean, String) -> Unit) =
        interact(EmaInteractionReasoner.Goal.Activate(target.trim()), callback)

    fun type(text: String, callback: (Boolean, String) -> Unit) =
        interact(EmaInteractionReasoner.Goal.EnterText(text), callback)

    fun scroll(direction: String, callback: (Boolean, String) -> Unit) =
        interact(EmaInteractionReasoner.Goal.Scroll(direction.lowercase()), callback)

    fun runSteps(steps: List<((Boolean, String) -> Unit) -> Unit>, done: (Boolean, String) -> Unit) {
        fun next(index: Int, last: String) {
            if (index >= steps.size) { done(true, last.ifBlank { "Done." }); return }
            var settled = false
            steps[index] { ok, result ->
                if (!settled) {
                    settled = true
                    if (!ok) done(false, result) else next(index + 1, result)
                }
            }
        }
        next(0, "")
    }
}
