package com.ema.assistant

/** Which speech recognizer backend EMA is talking to. */
enum class EmaRecognizerKind(val shortLabel: String, val isStrictlyOffline: Boolean) {
    /** EMA's own built-in offline speech-to-text engine (runs directly in-process). */
    EMA_OFFLINE("EMA offline", true),

    /** Android's platform on-device recognizer (API 31+, offline when model is installed). */
    ON_DEVICE("Android on-device", true),

    /** An explicit installed recognition service other than EMA (often cloud-backed). */
    EXTERNAL("system speech", false);

    companion object {
        fun fromStored(raw: String?): EmaRecognizerKind? {
            val cleaned = raw?.trim().orEmpty()
            if (cleaned.isEmpty()) return null
            if (cleaned.equals("VOSK_OFFLINE", ignoreCase = true)) return EMA_OFFLINE
            return values().firstOrNull { it.name.equals(cleaned, ignoreCase = true) }
        }
    }
}

/**
 * Deterministic backend selection, error classification and same-session
 * failover policy for EMA's speech recognition stack.
 *
 * Pure Kotlin (error codes match [android.speech.SpeechRecognizer] constants),
 * so every transition is unit tested on a plain JVM without a device.
 */
object EmaRecognizerFallback {

    // Mirrors android.speech.SpeechRecognizer.ERROR_* compile-time constants.
    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_TOO_MANY_REQUESTS = 10
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13
    const val ERROR_CANNOT_CHECK_SUPPORT = 14
    const val ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15

    enum class ErrorCategory {
        /** User remained quiet before the timeout elapsed. Do not switch backends. */
        NO_SPEECH,

        /** Audio was captured, but no intelligible words were decoded. Do not switch backends. */
        NO_MATCH,

        /** Microphone permission is missing. Stop and prompt the owner. */
        PERMISSION,

        /** Microphone briefly contended (e.g. wake word or TTS releasing AudioRecord). Retry once, then fall back. */
        TRANSIENT_AUDIO,

        /** Network, server, language, busy or client failure: fall back to the next distinct backend. */
        BACKEND_FAILURE
    }

    /**
     * Ordered list of distinct backends to try for a single recognition session,
     * filtered to those in [available].
     */
    fun plan(
        mode: EmaSpeechMode,
        available: Set<EmaRecognizerKind>,
        networkAvailable: Boolean = true,
        rememberedSystemKind: EmaRecognizerKind? = null,
        preferOffline: Boolean = false
    ): List<EmaRecognizerKind> {
        if (available.isEmpty()) return emptyList()
        val systemOrder = when (rememberedSystemKind) {
            EmaRecognizerKind.EXTERNAL ->
                if (networkAvailable && !preferOffline) listOf(EmaRecognizerKind.EXTERNAL, EmaRecognizerKind.ON_DEVICE)
                else listOf(EmaRecognizerKind.ON_DEVICE, EmaRecognizerKind.EXTERNAL)
            else -> listOf(EmaRecognizerKind.ON_DEVICE, EmaRecognizerKind.EXTERNAL)
        }
        val desired = when (mode) {
            EmaSpeechMode.EMA_OFFLINE_ONLY ->
                listOf(EmaRecognizerKind.EMA_OFFLINE)
            EmaSpeechMode.EMA_OFFLINE_FIRST ->
                listOf(EmaRecognizerKind.EMA_OFFLINE) + systemOrder
            EmaSpeechMode.SYSTEM_FIRST -> {
                if (!networkAvailable || preferOffline) {
                    // When the phone has no connection, skip cloud-first latency/failure:
                    // try offline-capable backends first, keeping EXTERNAL only as a last resort
                    // in case an OEM recognizer has its own cached offline pack.
                    listOf(
                        EmaRecognizerKind.EMA_OFFLINE,
                        EmaRecognizerKind.ON_DEVICE,
                        EmaRecognizerKind.EXTERNAL
                    )
                } else {
                    systemOrder + EmaRecognizerKind.EMA_OFFLINE
                }
            }
        }
        return desired.distinct().filter { it in available }
    }

    /** Classify a [android.speech.SpeechRecognizer] error code. */
    fun classifyError(errorCode: Int): ErrorCategory = when (errorCode) {
        ERROR_SPEECH_TIMEOUT -> ErrorCategory.NO_SPEECH
        ERROR_NO_MATCH -> ErrorCategory.NO_MATCH
        ERROR_INSUFFICIENT_PERMISSIONS -> ErrorCategory.PERMISSION
        ERROR_AUDIO -> ErrorCategory.TRANSIENT_AUDIO
        ERROR_NETWORK_TIMEOUT,
        ERROR_NETWORK,
        ERROR_SERVER,
        ERROR_CLIENT,
        ERROR_RECOGNIZER_BUSY,
        ERROR_TOO_MANY_REQUESTS,
        ERROR_SERVER_DISCONNECTED,
        ERROR_LANGUAGE_NOT_SUPPORTED,
        ERROR_LANGUAGE_UNAVAILABLE,
        ERROR_CANNOT_CHECK_SUPPORT,
        ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> ErrorCategory.BACKEND_FAILURE
        else -> ErrorCategory.BACKEND_FAILURE
    }

    /**
     * Whether a failing backend should hand over to the next candidate in [plan].
     *
     * User silence, unintelligible audio and missing permissions are never fixed
     * by launching a second recognizer, so those return false.
     */
    fun shouldFallback(errorCode: Int, audioRetryExhausted: Boolean = true): Boolean =
        when (classifyError(errorCode)) {
            ErrorCategory.BACKEND_FAILURE -> true
            ErrorCategory.TRANSIENT_AUDIO -> audioRetryExhausted
            ErrorCategory.NO_SPEECH,
            ErrorCategory.NO_MATCH,
            ErrorCategory.PERMISSION -> false
        }

    /** Whether to retry the *same* backend once after a brief mic-release pause. */
    fun shouldRetrySameBackend(errorCode: Int, alreadyRetriedAudio: Boolean): Boolean =
        !alreadyRetriedAudio && classifyError(errorCode) == ErrorCategory.TRANSIENT_AUDIO

    /**
     * Next distinct backend in [planned] after [tried], given [errorCode], or
     * null when the session should conclude and report to the user.
     */
    fun nextInPlan(
        planned: List<EmaRecognizerKind>,
        tried: Set<EmaRecognizerKind>,
        errorCode: Int,
        audioRetryExhausted: Boolean = true
    ): EmaRecognizerKind? {
        if (!shouldFallback(errorCode, audioRetryExhausted)) return null
        return planned.firstOrNull { it !in tried }
    }

    /** Plain, honest user-facing explanation of a final speech error. */
    fun userMessage(errorCode: Int, kind: EmaRecognizerKind, mode: EmaSpeechMode = EmaSpeechMode.EMA_OFFLINE_FIRST): String =
        when (classifyError(errorCode)) {
            ErrorCategory.NO_SPEECH -> "I didn't hear any speech. Tap Speak when you're ready."
            ErrorCategory.NO_MATCH -> "I couldn't catch those words. Please speak clearly and try again."
            ErrorCategory.PERMISSION -> "Microphone permission is required for speech recognition."
            ErrorCategory.TRANSIENT_AUDIO -> "The microphone is busy with another app. Try again in a moment."
            ErrorCategory.BACKEND_FAILURE -> when {
                errorCode == ERROR_LANGUAGE_NOT_SUPPORTED || errorCode == ERROR_LANGUAGE_UNAVAILABLE ->
                    if (kind == EmaRecognizerKind.EMA_OFFLINE && mode == EmaSpeechMode.EMA_OFFLINE_ONLY) {
                        "EMA's built-in offline speech engine currently supports English. Switch speech mode to allow system recognizers for other languages."
                    } else {
                        "The selected speech language is not available offline on this phone."
                    }
                errorCode == ERROR_NETWORK || errorCode == ERROR_NETWORK_TIMEOUT ->
                    "Network speech is unavailable. Switch to EMA offline speech for zero-internet recognition."
                errorCode == ERROR_RECOGNIZER_BUSY ->
                    "The speech recognizer is busy. Try again in a moment."
                kind == EmaRecognizerKind.EMA_OFFLINE ->
                    "EMA's offline speech engine could not start right now."
                else ->
                    "Speech recognition couldn't finish. Please try again."
            }
        }

    /**
     * Legacy pure-platform fallback rule preserved for existing tests and
     * single-hop platform callers.
     */
    fun next(current: EmaRecognizerKind, alreadyRetried: Boolean): EmaRecognizerKind? {
        if (alreadyRetried) return null
        return when (current) {
            EmaRecognizerKind.ON_DEVICE -> EmaRecognizerKind.EXTERNAL
            EmaRecognizerKind.EXTERNAL, EmaRecognizerKind.EMA_OFFLINE -> null
        }
    }
}
