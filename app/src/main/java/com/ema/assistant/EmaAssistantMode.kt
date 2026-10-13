package com.ema.assistant

/**
 * Which layer is allowed to answer.
 *
 * The user's requirement is explicit: the digital assistant comes first and the
 * AI is a second, optional layer. This setting makes that guarantee a real,
 * user-visible control rather than an implicit code path — including a mode in
 * which EMA never contacts an AI service at all, so the assistant keeps working
 * on a phone with no reachable or no freely available AI backend.
 *
 * Pure enum, safe to reference from unit tests.
 */
enum class EmaAssistantMode(val key: String, val label: String, val explanation: String) {

    /**
     * Default. Every deterministic native path is attempted first. The AI is
     * consulted only for requests the native layer genuinely cannot resolve,
     * and an AI failure never blocks a device action.
     */
    NATIVE_FIRST(
        "native_first",
        "Native first (AI as fallback)",
        "EMA performs phone and app actions itself. AI is used only when a request needs outside knowledge."
    ),

    /**
     * Fully offline assistant. No AI request is ever made. Requests needing
     * outside knowledge are answered with an honest explanation instead of a
     * network call, which is what makes EMA usable with no AI backend at all.
     */
    NATIVE_ONLY(
        "native_only",
        "Native only (no AI calls)",
        "EMA never contacts an AI service. Everything is performed on the device."
    ),

    /**
     * AI-assisted language understanding, still executed by the native layer.
     * The AI may only propose canonical commands; it never performs them and
     * never authorises them.
     */
    AI_ASSIST(
        "ai_assist",
        "AI-assisted wording (native execution)",
        "AI helps EMA understand unusual wording. EMA still performs every action itself and verifies it."
    );

    val allowsAi: Boolean get() = this != NATIVE_ONLY

    companion object {
        const val DEFAULT_KEY = "native_first"

        fun fromKey(key: String?): EmaAssistantMode =
            values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: NATIVE_FIRST
    }
}
