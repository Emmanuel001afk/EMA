package com.ema.assistant

/**
 * A provider-neutral description of one on-screen control.
 *
 * The accessibility layer converts real AccessibilityNodeInfo objects into
 * these snapshots so that every decision about *which* control to press is
 * plain, deterministic, offline logic that can be unit tested on a JVM.
 *
 * No Android types appear here on purpose.
 */
data class EmaNodeSnapshot(
    /** Index of this node in the snapshot list supplied to a resolver. */
    val index: Int,
    val text: String = "",
    val description: String = "",
    val viewId: String = "",
    val className: String = "",
    val visible: Boolean = false,
    val enabled: Boolean = false,
    /** True when this node itself accepts ACTION_CLICK. */
    val clickableSelf: Boolean = false,
    /**
     * Index of the nearest clickable ancestor, or -1. Many apps put the label
     * on a child TextView and the click handler on the parent row, so a label
     * match is only actionable through this fallback.
     */
    val clickableAncestorIndex: Int = -1,
    val editable: Boolean = false,
    val focused: Boolean = false,
    val scrollable: Boolean = false,
    /** Quick Settings tiles and Settings switches expose their state here. */
    val checkable: Boolean = false,
    val checked: Boolean = false,
    /** "On" / "Off" / "On, connected": read when a control is not checkable. */
    val stateDescription: String = "",
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    /** True when something in this node's own subtree can be clicked. */
    val actionable: Boolean get() = clickableSelf || clickableAncestorIndex >= 0

    /** Where a click should actually be dispatched. */
    val clickTargetIndex: Int get() = if (clickableSelf) index else clickableAncestorIndex

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    /** Everything a human would read as this control's identity. */
    val label: String
        get() = buildString {
            if (text.isNotBlank()) append(text)
            if (description.isNotBlank()) { if (isNotEmpty()) append(' '); append(description) }
        }.trim()

    /** label + view id, normalised, used for substring matching. */
    val searchable: String
        get() = normalize("$text $description $viewId")

    /** The view id with its package prefix removed: "com.x:id/send" -> "send". */
    val viewIdLeaf: String
        get() = viewId.substringAfterLast('/').substringAfterLast(':').trim()

    val isEmpty: Boolean get() = label.isBlank() && viewId.isBlank()

    companion object {
        fun normalize(value: String): String = value
            .lowercase()
            .replace('\u00a0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

/** Flat, ordered description of one screen, produced by the accessibility layer. */
data class EmaScreenSnapshot(
    val packageName: String,
    val nodes: List<EmaNodeSnapshot>
) {
    /** The control that currently holds input focus, if any. */
    val focusedNode: EmaNodeSnapshot? get() = nodes.firstOrNull { it.focused && it.editable }

    /** First editable control on screen (chat composer, search box, form field). */
    val firstEditable: EmaNodeSnapshot? get() = nodes.firstOrNull { it.editable }

    fun nodeAt(index: Int): EmaNodeSnapshot? = nodes.getOrNull(index)

    /** All readable text on the screen, in traversal order. */
    fun textDump(maxChars: Int = 16000): String = buildString {
        for (node in nodes) {
            if (node.label.isBlank()) continue
            append(node.label.take(300)).append('\n')
            if (length >= maxChars) break
        }
    }.trim().take(maxChars)
}
