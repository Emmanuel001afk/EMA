package com.ema.assistant

/** Evidence-driven task stages, shared by native execution and deterministic tests. */
object EmaTaskReadiness {
    enum class Stage { WAIT, READY_TO_TYPE, READY_TO_SUBMIT, BLOCKED, TIMED_OUT }
    data class Assessment(val stage: Stage, val reason: String, val evidence: List<String>)
    const val COMPOSER_BUDGET_MS = 8_000L

    fun message(
        screen: EmaScreenSnapshot,
        expectedPackage: String,
        recipient: String,
        body: String,
        waitedMs: Long,
        locked: Boolean = false
    ): Assessment {
        fun waitFor(reason: String, evidence: List<String> = emptyList()) =
            if (waitedMs >= COMPOSER_BUDGET_MS) Assessment(Stage.TIMED_OUT, reason, evidence)
            else Assessment(Stage.WAIT, reason, evidence)
        if (locked) return Assessment(Stage.BLOCKED, "The phone is locked. Unlock it before sending.", listOf("keyguard"))
        if (expectedPackage.isBlank() || recipient.isBlank() || body.isBlank())
            return Assessment(Stage.BLOCKED, "The app, recipient and message must all be explicit.", listOf("incomplete-goal"))
        if (screen.packageName.isBlank()) return waitFor("The target application's controls are not readable yet.")
        if (screen.packageName != expectedPackage)
            return Assessment(Stage.BLOCKED, "The foreground application changed. I will not type or send in another app.", listOf("package-mismatch"))
        if (!EmaActionEvidence.recipientHeaderMatches(screen, expectedPackage, recipient))
            return waitFor("The intended recipient has not been verified in the chat header.", listOf("package-match"))
        val fields = screen.nodes.filter { it.visible && it.enabled && it.editable }
        val field = fields.singleOrNull { it.focused } ?: fields.singleOrNull()
            ?: return waitFor("An unambiguous message composer has not appeared.", listOf("package-match", "recipient-match"))
        val observed = field.text.trim()
        if (observed.isNotEmpty() && observed != body.trim())
            return Assessment(Stage.BLOCKED, "A different draft is already in the composer. Clear or save it yourself before retrying; I won't overwrite it.", listOf("recipient-match", "conflicting-draft"))
        if (observed.isBlank()) return Assessment(Stage.READY_TO_TYPE, "The verified chat has an empty composer.", listOf("package-match", "recipient-match", "empty-composer"))
        if (EmaSendControlResolver.resolve(screen, field) == null)
            return waitFor("The exact draft is present, but a safe Send control has not appeared.", listOf("recipient-match", "body-match"))
        return Assessment(Stage.READY_TO_SUBMIT, "Recipient, exact body and Send control are observed.", listOf("package-match", "recipient-match", "body-match", "send-control"))
    }
}
