package com.ema.assistant

/**
 * Which engine turns the owner's speech into text.
 *
 * The microphone itself is never the online part: reading it is plain
 * Android `AudioRecord` and needs no network. What can need the network is the
 * *recogniser* - the service that maps audio samples to words. On most phones
 * that service is Google's, so this setting decides whether EMA may ask it at
 * all.
 *
 * Pure Kotlin so the routing rules are unit tested without a phone.
 */
enum class EmaSpeechEngine {

    /** Android's configured recogniser only (Google on most phones). May use the network. */
    SYSTEM,

    /** Android first; EMA's bundled English model only when the system fails. */
    SYSTEM_WITH_LOCAL_FALLBACK,

    /** EMA's bundled English model only. No system recogniser, no network. */
    LOCAL_ONLY;

    /** True when Android's recogniser may be asked to listen. */
    val usesSystemRecognizer: Boolean get() = this != LOCAL_ONLY

    /** True when EMA's own bundled model may be loaded. */
    val usesLocalModel: Boolean get() = this != SYSTEM

    /** True when nothing but the phone's own model may read the microphone. */
    val isLocalOnly: Boolean get() = this == LOCAL_ONLY

    /**
     * Backends to try, in order, when a listening session starts.
     *
     * [preferred] is the kind that worked last time; it only moves to the
     * front when this mode allows that backend at all.
     */
    fun startOrder(preferred: EmaRecognizerKind?): List<EmaRecognizerKind> {
        val allowed = when (this) {
            SYSTEM -> listOf(EmaRecognizerKind.ON_DEVICE, EmaRecognizerKind.EXTERNAL)
            SYSTEM_WITH_LOCAL_FALLBACK -> listOf(
                EmaRecognizerKind.ON_DEVICE, EmaRecognizerKind.EXTERNAL, EmaRecognizerKind.VOSK_OFFLINE
            )
            LOCAL_ONLY -> listOf(EmaRecognizerKind.VOSK_OFFLINE)
        }
        if (preferred == null || preferred !in allowed) return allowed
        return listOf(preferred) + allowed.filter { it != preferred }
    }

    /**
     * The next backend after [current] failed inside the same session, or null
     * when the session must end.
     *
     * A local-only engine never retries onto a system recogniser: that would
     * silently send the microphone to Google, which is exactly what the owner
     * switched off.
     */
    fun nextAfterFailure(current: EmaRecognizerKind): EmaRecognizerKind? {
        val order = startOrder(null)
        val index = order.indexOf(current)
        if (index < 0) return null
        return order.getOrNull(index + 1)
    }

    companion object {
        /**
         * Read the stored value, and translate the older yes/no "local
         * fallback" preference so an existing install keeps its behaviour.
         */
        fun parse(stored: String?, legacyLocalFallback: Boolean): EmaSpeechEngine {
            val byName = stored?.let { name -> entries.firstOrNull { it.name == name } }
            if (byName != null) return byName
            return if (legacyLocalFallback) SYSTEM_WITH_LOCAL_FALLBACK else SYSTEM
        }
    }
}
