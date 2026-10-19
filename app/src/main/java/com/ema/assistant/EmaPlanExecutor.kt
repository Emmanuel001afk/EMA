package com.ema.assistant

/** What actually happened when a step ran. Tapping is not success; evidence is. */
enum class EmaOutcomeStatus {
    /** Done and verified (or verification is inherent, e.g. an alarm intent accepted). */
    SUCCESS,
    /** The action was performed but EMA could not confirm the result. */
    UNVERIFIED,
    FAILED,
    /** EMA needs an answer from the user before it can continue. */
    NEEDS_INPUT,
    /** A permission or system setting is missing. */
    NEEDS_PERMISSION,
    /** Refused for safety, e.g. an identical message was just sent. */
    BLOCKED,
    TIMED_OUT,
    CANCELLED,
    /** Not attempted because an earlier step it depends on did not succeed. */
    SKIPPED;

    val isSuccess: Boolean get() = this == SUCCESS
    val isTerminalProblem: Boolean get() = this != SUCCESS && this != UNVERIFIED
}

data class EmaStepOutcome(
    val status: EmaOutcomeStatus,
    val message: String,
    /** A non-consequential step may be retried when this is true. */
    val retryable: Boolean = false,
    /**
     * True when the action really happened but only the proof is missing, so
     * the next step must still run.
     *
     * A launched app whose screen never settled is the case that matters: EMA
     * knows the app was started, it just could not watch it stop moving. The
     * next step decides with its own evidence whether it worked.
     */
    val carriesOn: Boolean = false
)

/**
 * Classify the human-readable results produced by EMA's existing executors.
 *
 * Executors that know better pass an explicit status; this is the fallback
 * for the many handlers that report through a sentence. It is ordered so the
 * most specific reading wins: "I couldn't confirm playback" is UNVERIFIED,
 * not FAILED; "EMA needs Contacts access" is NEEDS_PERMISSION.
 */
object EmaOutcomeClassifier {
    fun classify(message: String, ok: Boolean? = null): EmaStepOutcome {
        val text = message.trim()
        val l = text.lowercase()
        // An app that was launched but whose screen never settled (or that EMA
        // could not watch because screen control is off) is not a failure and
        // must not stop the rest of a plan: the action happened, only the proof
        // is missing, and the next step judges its own result.
        val launchedUnconfirmed = LAUNCHED_UNCONFIRMED.containsMatchIn(l)
        val status = when {
            l.isBlank() -> EmaOutcomeStatus.FAILED
            launchedUnconfirmed -> EmaOutcomeStatus.UNVERIFIED
            Regex("needs? (?:contacts|microphone|phone|calendar|notification|camera)? ?(?:access|permission)|grant it|" +
                "turn on (?:ema'?s? )?(?:accessibility|screen control)|accessibility (?:service )?is (?:off|not enabled)|" +
                "screen control is off|enable ema in accessibility|notification access").containsMatchIn(l) -> EmaOutcomeStatus.NEEDS_PERMISSION
            l.endsWith("?") || l.contains("tell me which one") || l.contains("which one do you mean") ||
                l.contains("what should i say") -> EmaOutcomeStatus.NEEDS_INPUT
            l.contains("did not send it again") || l.contains("won't send it twice") -> EmaOutcomeStatus.BLOCKED
            Regex("could(?:n't| not) (?:verify|confirm)|not (?:been )?verified|was not confirmed|may not have|" +
                "but could(?:n't| not)|unable to confirm|appears to have|could not be confirmed").containsMatchIn(l) -> EmaOutcomeStatus.UNVERIFIED
            Regex("^(?:i couldn't|i could not|i cannot|i can't|couldn't|could not|sorry, i couldn't)|failed|" +
                "is not installed|could(?:n't| not) find|i did not|i didn't|won't guess|was not sent|not available|" +
                "no (?:app|result|contact)s? (?:was |were )?found|timed out|in time").containsMatchIn(l) -> EmaOutcomeStatus.FAILED
            else -> EmaOutcomeStatus.SUCCESS
        }
        val finalStatus = if (ok == false && status == EmaOutcomeStatus.SUCCESS) EmaOutcomeStatus.FAILED else status
        return EmaStepOutcome(finalStatus, text, retryable = false, carriesOn = launchedUnconfirmed)
    }

    /** "I launched X, but ... could not confirm ..." - the action happened. */
    private val LAUNCHED_UNCONFIRMED = Regex(
        "(?i)\\b(?:launched|opened|started)\\b[^.]*\\b(?:could not|couldn't|cannot|can't|unable to)\\s+(?:confirm|verify)"
    )
}

/**
 * Remembers consequential actions (sending, calling) for a short window so a
 * repeated callback, a double tap, or the same utterance arriving twice can
 * never submit the same message twice. Only a hash of the action is kept;
 * message text is not stored.
 */
class EmaDuplicateGuard(private val windowMs: Long = 60_000L) {
    private val recent = LinkedHashMap<Int, Long>()

    @Synchronized
    fun recentlyDone(key: String, nowMs: Long): Boolean {
        prune(nowMs)
        val at = recent[key.lowercase().trim().hashCode()] ?: return false
        return nowMs - at < windowMs
    }

    @Synchronized
    fun record(key: String, nowMs: Long) {
        prune(nowMs)
        recent[key.lowercase().trim().hashCode()] = nowMs
    }

    @Synchronized
    fun clear() = recent.clear()

    private fun prune(nowMs: Long) {
        val it = recent.entries.iterator()
        while (it.hasNext()) if (nowMs - it.next().value >= windowMs) it.remove()
        while (recent.size > 64) recent.remove(recent.keys.first())
    }

    companion object {
        val shared = EmaDuplicateGuard()
        fun messageKey(app: String?, recipient: String, body: String) = "send|${app.orEmpty()}|$recipient|$body"
        fun callKey(recipient: String) = "call|$recipient"
    }
}

/**
 * Runs a plan step by step: execute -> observe outcome -> decide.
 *
 *  - Steps run strictly in order; a step that depends on the previous one is
 *    SKIPPED when that one did not succeed (UNVERIFIED counts as "carry on
 *    carefully" only for non-consequential follow-ups).
 *  - Each step has its own timeout; the whole plan has a deadline.
 *  - Only non-consequential steps are retried, a bounded number of times.
 *  - A consequential step (send, call) is never retried and is checked against
 *    [EmaDuplicateGuard] before it runs.
 *  - Results that arrive after a timeout or cancellation are ignored, so a late
 *    callback can never advance a plan the user was told had stopped.
 *
 * Pure: time and scheduling are injected, so the whole policy is unit tested
 * on the JVM. Android code supplies a Handler-backed [Scheduler].
 */
class EmaPlanExecutor(
    private val scheduler: Scheduler,
    private val clock: () -> Long,
    private val guard: EmaDuplicateGuard = EmaDuplicateGuard.shared
) {
    fun interface Cancellable { fun cancel() }
    fun interface Scheduler { fun schedule(delayMs: Long, block: () -> Unit): Cancellable }

    data class Step(
        val description: String,
        /** Sends, calls, purchases: never retried, never repeated. */
        val consequential: Boolean = false,
        /** When set, an identical action within the guard window is refused. */
        val duplicateKey: String? = null,
        val maxAttempts: Int = 1,
        val timeoutMs: Long = 25_000L,
        val dependsOnPrevious: Boolean = true,
        val run: (done: (EmaStepOutcome) -> Unit) -> Unit
    )

    interface Listener {
        fun onPlanStarted(descriptions: List<String>) {}
        fun onStepStarted(index: Int, attempt: Int) {}
        fun onStepFinished(index: Int, outcome: EmaStepOutcome) {}
    }

    enum class PlanStatus { COMPLETED, COMPLETED_UNVERIFIED, PARTIAL, FAILED, NEEDS_INPUT, NEEDS_PERMISSION, CANCELLED }

    data class Report(val status: PlanStatus, val outcomes: List<EmaStepOutcome>, val summary: String)

    inner class Handle internal constructor() {
        @Volatile internal var cancelled = false

        /**
         * Stop after the step currently running, keeping its real outcome.
         * Used when the user answers "no" to "Step N done — continue?".
         */
        @Volatile internal var stopAfterCurrent = false
        internal var cancelCurrent: (() -> Unit)? = null
        fun cancel() {
            if (cancelled) return
            cancelled = true
            cancelCurrent?.invoke()
        }
        fun cancelAfterCurrent() { stopAfterCurrent = true }
    }

    fun execute(steps: List<Step>, deadlineMs: Long, listener: Listener? = null, done: (Report) -> Unit): Handle {
        val handle = Handle()
        val startedAt = clock()
        val outcomes = MutableList<EmaStepOutcome?>(steps.size) { null }
        listener?.onPlanStarted(steps.map { it.description })
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
            val filled = outcomes.mapIndexed { i, o -> o ?: EmaStepOutcome(EmaOutcomeStatus.SKIPPED, steps[i].description) }
            done(Report(planStatus(filled, handle.cancelled || handle.stopAfterCurrent), filled, summarize(steps, filled)))
        }

        fun runStep(index: Int, attempt: Int) {
            if (handle.cancelled || handle.stopAfterCurrent) {
                for (k in index until steps.size) if (outcomes[k] == null) outcomes[k] = EmaStepOutcome(EmaOutcomeStatus.CANCELLED, "Cancelled.")
                finish(); return
            }
            if (index >= steps.size) { finish(); return }
            val step = steps[index]
            val previous = (index - 1 downTo 0).firstNotNullOfOrNull { outcomes[it] }
            if (index > 0 && step.dependsOnPrevious && previous != null) {
                // A step that really happened but could not be proven (a
                // launched app whose screen never settled) still lets the next
                // step run: that step checks its own evidence.
                val previousOk = previous.status == EmaOutcomeStatus.SUCCESS ||
                    (previous.status == EmaOutcomeStatus.UNVERIFIED && (!step.consequential || previous.carriesOn))
                if (!previousOk) {
                    outcomes[index] = EmaStepOutcome(EmaOutcomeStatus.SKIPPED, "Skipped: ${step.description}")
                    listener?.onStepFinished(index, outcomes[index]!!)
                    runStep(index + 1, 1); return
                }
            }
            val now = clock()
            val remaining = deadlineMs - (now - startedAt)
            if (remaining <= 0) {
                outcomes[index] = EmaStepOutcome(EmaOutcomeStatus.TIMED_OUT, "I ran out of time before: ${step.description.lowercase()}.")
                listener?.onStepFinished(index, outcomes[index]!!)
                for (k in index + 1 until steps.size) outcomes[k] = EmaStepOutcome(EmaOutcomeStatus.SKIPPED, "Skipped: ${steps[k].description}")
                finish(); return
            }
            step.duplicateKey?.let { key ->
                if (guard.recentlyDone(key, now)) {
                    outcomes[index] = EmaStepOutcome(EmaOutcomeStatus.BLOCKED,
                        "I already did exactly that less than a minute ago, so I did not do it again.")
                    listener?.onStepFinished(index, outcomes[index]!!)
                    runStep(index + 1, 1); return
                }
            }
            listener?.onStepStarted(index, attempt)
            var settled = false
            var timer: Cancellable? = null
            fun settle(outcome: EmaStepOutcome) {
                if (settled) return
                settled = true
                timer?.cancel()
                handle.cancelCurrent = null
                if (handle.cancelled && outcome.status != EmaOutcomeStatus.CANCELLED) {
                    outcomes[index] = EmaStepOutcome(EmaOutcomeStatus.CANCELLED, "Cancelled.")
                    runStep(index + 1, 1); return
                }
                step.duplicateKey?.let { key ->
                    // Anything that may have reached the other side counts as done.
                    if (outcome.status in setOf(EmaOutcomeStatus.SUCCESS, EmaOutcomeStatus.UNVERIFIED, EmaOutcomeStatus.TIMED_OUT)) guard.record(key, clock())
                }
                val canRetry = !step.consequential && outcome.retryable && attempt < step.maxAttempts &&
                    outcome.status in setOf(EmaOutcomeStatus.FAILED, EmaOutcomeStatus.TIMED_OUT) &&
                    deadlineMs - (clock() - startedAt) > 1_000
                if (canRetry) {
                    scheduler.schedule(600L * attempt) { runStep(index, attempt + 1) }
                    return
                }
                outcomes[index] = outcome
                listener?.onStepFinished(index, outcome)
                if (outcome.status == EmaOutcomeStatus.NEEDS_INPUT || outcome.status == EmaOutcomeStatus.NEEDS_PERMISSION) {
                    for (k in index + 1 until steps.size) outcomes[k] = EmaStepOutcome(EmaOutcomeStatus.SKIPPED, "Skipped: ${steps[k].description}")
                    finish(); return
                }
                runStep(index + 1, 1)
            }
            val budget = minOf(step.timeoutMs, remaining)
            timer = scheduler.schedule(budget) {
                settle(EmaStepOutcome(EmaOutcomeStatus.TIMED_OUT,
                    "I couldn't finish \"${step.description.lowercase()}\" in time, so I stopped it.", retryable = true))
            }
            handle.cancelCurrent = { settle(EmaStepOutcome(EmaOutcomeStatus.CANCELLED, "Cancelled.")) }
            try {
                step.run { outcome -> settle(outcome) }
            } catch (t: Throwable) {
                settle(EmaStepOutcome(EmaOutcomeStatus.FAILED, "Something went wrong while trying to ${step.description.lowercase()}."))
            }
        }

        runStep(0, 1)
        return handle
    }

    companion object {
        fun planStatus(outcomes: List<EmaStepOutcome>, cancelled: Boolean): PlanStatus {
            if (cancelled) return PlanStatus.CANCELLED
            val statuses = outcomes.map { it.status }
            return when {
                statuses.all { it == EmaOutcomeStatus.SUCCESS } -> PlanStatus.COMPLETED
                statuses.contains(EmaOutcomeStatus.NEEDS_INPUT) -> PlanStatus.NEEDS_INPUT
                statuses.contains(EmaOutcomeStatus.NEEDS_PERMISSION) -> PlanStatus.NEEDS_PERMISSION
                statuses.all { it == EmaOutcomeStatus.SUCCESS || it == EmaOutcomeStatus.UNVERIFIED } -> PlanStatus.COMPLETED_UNVERIFIED
                statuses.any { it == EmaOutcomeStatus.SUCCESS || it == EmaOutcomeStatus.UNVERIFIED } -> PlanStatus.PARTIAL
                else -> PlanStatus.FAILED
            }
        }

        /**
         * One honest sentence group for the user: what was done, what was not,
         * and why EMA stopped. Never reports a skipped step as done.
         */
        fun summarize(steps: List<Step>, outcomes: List<EmaStepOutcome>): String {
            if (outcomes.size == 1) return outcomes[0].message
            val parts = mutableListOf<String>()
            val firstProblem = outcomes.indexOfFirst { it.status.isTerminalProblem && it.status != EmaOutcomeStatus.SKIPPED }
            outcomes.forEachIndexed { i, o ->
                if (o.status == EmaOutcomeStatus.SUCCESS || o.status == EmaOutcomeStatus.UNVERIFIED) {
                    if (o.message.isNotBlank() && parts.none { it.equals(o.message, true) }) parts += o.message
                } else if (i == firstProblem) {
                    parts += o.message
                }
            }
            val cancelled = outcomes.indices.filter { outcomes[it].status == EmaOutcomeStatus.CANCELLED }
            val skipped = outcomes.indices.filter { outcomes[it].status == EmaOutcomeStatus.SKIPPED }
            if (cancelled.isNotEmpty()) {
                val finishedCount = outcomes.count { it.status == EmaOutcomeStatus.SUCCESS || it.status == EmaOutcomeStatus.UNVERIFIED }
                val names = cancelled.joinToString(", ") { steps[it].description.replaceFirstChar { c -> c.lowercase() } }
                parts += "Stopped after $finishedCount of ${steps.size} steps, so I did not run: $names."
            } else if (skipped.isNotEmpty()) {
                val names = skipped.joinToString(", ") { steps[it].description.replaceFirstChar { c -> c.lowercase() } }
                parts += "I stopped there, so I did not: $names."
            }
            return parts.joinToString(" ").trim()
        }
    }
}
