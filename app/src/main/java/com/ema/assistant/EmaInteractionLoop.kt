package com.ema.assistant

/** Observe -> ground -> act once -> observe effect. Shared across apps and interaction types. */
class EmaInteractionLoop(
    private val scheduler: EmaPlanExecutor.Scheduler,
    private val clock: () -> Long,
    private val observe: () -> EmaScreenSnapshot,
    private val perform: (EmaInteractionReasoner.Goal, EmaScreenSnapshot, EmaNodeSnapshot) -> Dispatch,
    private val readinessBudgetMs: Long = 8_000,
    private val effectBudgetMs: Long = 8_000,
    private val stableMs: Long = 400,
    private val pollMs: Long = 200
) {
    enum class Dispatch { ACCEPTED, STALE, REJECTED }
    enum class Status { VERIFIED, NEEDS_INPUT, BLOCKED, NOT_ACTED, UNVERIFIED }
    data class Result(val status: Status, val reason: String, val dispatched: Boolean, val observations: Int)
    fun run(goal: EmaInteractionReasoner.Goal, done: (Result) -> Unit): EmaPlanExecutor.Cancellable {
        val started = clock()
        var expectedPackage = ""
        var ended = false
        var pending: EmaPlanExecutor.Cancellable? = null
        var observations = 0
        var before: EmaScreenSnapshot? = null
        var target: EmaNodeSnapshot? = null
        var dispatchedAt = 0L
        var effectSignature: String? = null
        var effectSince = 0L
        fun finish(status: Status, reason: String) {
            if (ended) return
            ended = true
            pending?.cancel()
            done(Result(status, reason, before != null, observations))
        }
        fun tick() {
            if (ended) return
            val now = clock()
            val screen = try { observe() } catch (_: Exception) {
                finish(if (before == null) Status.NOT_ACTED else Status.UNVERIFIED, "The application could not be observed safely.")
                return
            }
            observations++
            if (before != null) {
                if (EmaInteractionReasoner.effectObserved(goal, before!!, target!!, screen)) {
                    val signature = if (goal is EmaInteractionReasoner.Goal.EnterText || target!!.checkable) "goal-evidence"
                        else EmaInteractionReasoner.fingerprint(screen)
                    if (signature != effectSignature) { effectSignature = signature; effectSince = now }
                    if (now - effectSince >= stableMs) {
                        finish(Status.VERIFIED, "The requested interaction produced an observed effect.")
                        return
                    }
                } else { effectSignature = null }
                if (now - dispatchedAt >= effectBudgetMs) {
                    finish(Status.UNVERIFIED, "Android accepted the interaction, but its effect could not be verified. I will not repeat it automatically.")
                    return
                }
            } else {
                if (expectedPackage.isBlank() && screen.packageName.isNotBlank()) expectedPackage = screen.packageName
                val decision = EmaInteractionReasoner.assess(goal, screen, expectedPackage)
                when (decision.state) {
                    EmaInteractionReasoner.State.BLOCKED -> { finish(Status.BLOCKED, decision.reason); return }
                    EmaInteractionReasoner.State.NEEDS_INPUT -> { finish(Status.NEEDS_INPUT, decision.reason); return }
                    EmaInteractionReasoner.State.SATISFIED -> { finish(Status.VERIFIED, decision.reason); return }
                    EmaInteractionReasoner.State.READY -> {
                        // Adapter rechecks package, lock, fingerprint and target against a fresh tree.
                        if (now - started > readinessBudgetMs) {
                            finish(Status.NOT_ACTED, "The control arrived after the readiness deadline. I did not act.")
                            return
                        }
                        val dispatch = try { perform(goal, screen, decision.node!!) } catch (_: Exception) {
                            before = screen; target = decision.node
                            finish(Status.UNVERIFIED, "Dispatch was interrupted; the effect is unknown. I will not repeat it automatically.")
                            return
                        }
                        when (dispatch) {
                            Dispatch.ACCEPTED -> { before = screen; target = decision.node; dispatchedAt = now }
                            Dispatch.REJECTED -> { finish(Status.NOT_ACTED, "Android did not accept the grounded interaction. I have not verified a change."); return }
                            Dispatch.STALE -> Unit // Re-observe; never replay an accepted action.
                        }
                    }
                    EmaInteractionReasoner.State.WAIT -> Unit
                }
                if (before == null && now - started >= readinessBudgetMs) {
                    finish(Status.NOT_ACTED, decision.reason + " I stopped waiting without acting.")
                    return
                }
            }
            pending = scheduler.schedule(pollMs.coerceAtLeast(1)) { tick() }
        }
        tick()
        return EmaPlanExecutor.Cancellable { ended = true; pending?.cancel() }
    }
}
