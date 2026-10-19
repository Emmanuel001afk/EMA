package com.ema.assistant

/** Which speech recogniser EMA is talking to. */
enum class EmaRecognizerKind {
    /** Android's on-device recogniser: offline, but it can be missing. */
    ON_DEVICE,

    /** Another installed recognition service, addressed by component name. */
    EXTERNAL
}

/**
 * The recogniser fallback rule.
 *
 * The on-device recogniser is preferred because it works without a network,
 * but it fails in ways the phone never explains well: the language is not
 * available, the model is not supported, the service returns a server or
 * network error. When that happens EMA retries the *same* listening session
 * once with another installed recogniser, addressed by its explicit component
 * name, so the user does not have to speak again.
 *
 * EMA's own recognition service is never used: it would make EMA talk to
 * itself.
 *
 * Pure Kotlin, so the rule is unit tested without a phone.
 */
object EmaRecognizerFallback {

    /**
     * The recogniser to try next in the same listening session, or null when
     * the session must not retry.
     *
     * @param alreadyRetried true once this session has already fallen back.
     */
    fun next(current: EmaRecognizerKind, alreadyRetried: Boolean): EmaRecognizerKind? {
        if (alreadyRetried) return null
        return when (current) {
            EmaRecognizerKind.ON_DEVICE -> EmaRecognizerKind.EXTERNAL
            // An external recogniser that failed is the end of the line: the
            // user is told, and the next request starts a fresh session.
            EmaRecognizerKind.EXTERNAL -> null
        }
    }
}
