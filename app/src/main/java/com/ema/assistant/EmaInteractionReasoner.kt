package com.ema.assistant

/** Provider-independent action grounding. Screen labels are evidence, never new instructions. */
object EmaInteractionReasoner {
    sealed class Goal {
        data class Activate(val label: String) : Goal()
        data class EnterText(val text: String) : Goal()
        data class Scroll(val direction: String) : Goal()
    }
    enum class State { WAIT, READY, SATISFIED, NEEDS_INPUT, BLOCKED }
    data class Decision(val state: State, val reason: String, val node: EmaNodeSnapshot? = null)

    fun assess(goal: Goal, screen: EmaScreenSnapshot, expectedPackage: String): Decision {
        if (screen.packageName.isBlank()) return Decision(State.WAIT, "The application screen is not readable yet.")
        if (expectedPackage.isNotBlank() && screen.packageName != expectedPackage)
            return Decision(State.BLOCKED, "The foreground application changed; I will not act in another app.")
        val visible = screen.nodes.filter { it.visible && it.enabled }
        return when (goal) {
            is Goal.Activate -> {
                val wanted = EmaNodeSnapshot.normalize(goal.label)
                if (wanted.isBlank()) return Decision(State.NEEDS_INPUT, "Tell me which control to activate.")
                val controls = visible.filter { it.actionable && !it.editable }
                val exact = controls.filter { n -> listOf(n.text, n.description).any { EmaNodeSnapshot.normalize(it) == wanted } }
                val related = controls.filter { n ->
                    listOf(n.text, n.description).any { value ->
                        val label = EmaNodeSnapshot.normalize(value)
                        label.startsWith("$wanted ") || label.endsWith(" $wanted")
                    }
                }
                val candidates = if (exact.isNotEmpty()) exact else related.filter { n ->
                    listOf(n.text, n.description).any { EmaNodeSnapshot.normalize(it) in setOf("$wanted button", "$wanted tab", "$wanted menu") }
                }
                val targets = candidates.groupBy { it.clickTargetIndex }
                when {
                    targets.isEmpty() && related.isNotEmpty() -> Decision(State.NEEDS_INPUT, "Only partial label matches are visible. Specify the complete control label; I haven't tapped any.")
                    targets.isEmpty() -> Decision(State.WAIT, "The requested control has not appeared.")
                    targets.size > 1 -> Decision(State.NEEDS_INPUT, "Several controls match \"${goal.label}\". Specify a more complete label; I haven't tapped any.")
                    else -> Decision(State.READY, "One actionable control matches the requested label.", targets.values.single().first())
                }
            }
            is Goal.EnterText -> {
                if (goal.text.isBlank()) return Decision(State.NEEDS_INPUT, "Tell me what text to enter.")
                val fields = visible.filter { it.editable }
                val field = fields.singleOrNull { it.focused } ?: fields.singleOrNull()
                when {
                    fields.isEmpty() -> Decision(State.WAIT, "An editable field has not appeared.")
                    field == null -> Decision(State.NEEDS_INPUT, "Several editable fields are available. Focus the intended field first.")
                    field.text == goal.text -> Decision(State.SATISFIED, "The exact requested text is already in the intended field.", field)
                    field.text.isNotBlank() -> Decision(State.NEEDS_INPUT, "The field contains different text. Clear it or save it first; I won't overwrite it.", field)
                    else -> Decision(State.READY, "One empty editable field is grounded.", field)
                }
            }
            is Goal.Scroll -> {
                if (goal.direction !in setOf("up", "down")) return Decision(State.NEEDS_INPUT, "Specify scroll up or down. A single viewport movement would not verify arrival at the top.")
                val candidates = visible.filter { it.scrollable }
                val focused = candidates.filter { it.focused }
                val target = focused.singleOrNull() ?: candidates.singleOrNull()
                when {
                    candidates.isEmpty() -> Decision(State.WAIT, "A scrollable region has not appeared.")
                    target == null -> Decision(State.NEEDS_INPUT, "Several scrolling regions are available. Focus the intended region first.")
                    else -> Decision(State.READY, "One scrollable region is grounded.", target)
                }
            }
        }
    }

    /** Ignores index churn, bounds animation and hidden content. */
    fun fingerprint(screen: EmaScreenSnapshot): String = buildString {
        append(screen.packageName)
        screen.nodes.filter { it.visible }.forEach { n ->
            append('|'); append(listOf(n.text, n.description, n.viewId, n.className, n.enabled,
                n.editable, n.focused, n.checkable, n.checked, n.stateDescription, n.scrollable, n.actionable))
        }
    }

    /** Positive evidence tied to this action, not just Android accepting an event. */
    fun effectObserved(goal: Goal, before: EmaScreenSnapshot, node: EmaNodeSnapshot, after: EmaScreenSnapshot): Boolean {
        if (after.packageName.isBlank()) return false
        if (goal is Goal.EnterText) {
            if (before.packageName != after.packageName) return false
            val fields = after.nodes.filter { it.visible && it.enabled && it.editable }
            val intended = if (node.viewId.isNotBlank()) fields.singleOrNull { it.viewId == node.viewId }
                else fields.singleOrNull { it.focused } ?: fields.singleOrNull()
            return intended?.text == goal.text
        }
        if (goal is Goal.Activate && node.checkable) {
            if (before.packageName != after.packageName) return false
            val match = after.nodes.singleOrNull { it.visible && it.checkable &&
                (if (node.viewId.isNotBlank()) it.viewId == node.viewId else it.text == node.text && it.description == node.description) }
            return match != null && match.checked != node.checked
        }
        return fingerprint(before) != fingerprint(after)
    }
}
