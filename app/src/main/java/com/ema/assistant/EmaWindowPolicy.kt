package com.ema.assistant

/** Screen ownership is resolved before choosing controls. Never act through a system modal. */
object EmaWindowPolicy {
    enum class Kind { APPLICATION, SYSTEM, IGNORE }
    data class Window(val index: Int, val packageName: String, val layer: Int, val kind: Kind, val active: Boolean)

    fun choose(windows: List<Window>, assistantPackage: String, targetPackage: String? = null): Int? {
        val system = windows.filter { it.kind == Kind.SYSTEM && it.active && it.packageName != assistantPackage }
            .maxByOrNull { it.layer }
        val app = windows.filter { it.kind == Kind.APPLICATION }.maxByOrNull { it.layer }
        val top = if (system != null && (app == null || system.layer > app.layer)) system else app
        if (top == null || (targetPackage != null && (top.kind != Kind.APPLICATION || top.packageName != targetPackage))) return null
        return top.index
    }
}
