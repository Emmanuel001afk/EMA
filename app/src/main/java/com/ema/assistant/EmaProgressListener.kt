package com.ema.assistant

/**
 * Real execution state, pushed from the router to whichever UI is showing
 * (the app screen or the voice overlay). The UI never invents progress: every
 * state it shows comes from one of these callbacks.
 */
interface EmaProgressListener {
    /** Native understanding produced a plan with these human-readable steps. */
    fun onPlan(steps: List<String>) {}
    fun onStepStarted(index: Int) {}
    fun onStepFinished(index: Int, outcome: EmaStepOutcome) {}
    /** EMA asked the user something and is waiting for the answer. */
    fun onClarification(question: String, options: List<String>) {}
    /** Final, classified result of the whole request. */
    fun onFinished(status: EmaOutcomeStatus, message: String) {}
}
