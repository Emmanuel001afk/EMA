package com.ema.assistant

/** Which speech recogniser EMA is talking to. */
enum class EmaRecognizerKind {
    /** Android's on-device recogniser: offline, but it can be missing. */
    ON_DEVICE,

    /** Another installed recognition service, addressed by component name. */
    EXTERNAL,

    /** Optional EMA-managed Vosk engine using the bundled local English model. */
    VOSK_OFFLINE
}

/**
 * Legacy pure-platform fallback rule kept for unit tests and older call sites.
 * The runtime chooser in [EmaSpeechRecognition] additionally considers the
 * optional EMA-managed local Vosk backend, which is user-enabled and separately
 * guarded by locale, permissions and memory checks.
 *
 * Pure Kotlin, so this platform-only rule is unit tested without a phone.
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
            EmaRecognizerKind.EXTERNAL, EmaRecognizerKind.VOSK_OFFLINE -> null
        }
    }
}
