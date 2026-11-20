package com.ema.assistant

/** Resolves identity and state together so read and tap cannot select different controls. */
object EmaDeviceToggleResolver {
    data class Decision(val node: EmaNodeSnapshot, val enabled: Boolean)
    private fun normalize(s: String) = EmaNodeSnapshot.normalize(s).replace("wi-fi", "wifi").replace("wi fi", "wifi")
    fun resolve(screen: EmaScreenSnapshot, labels: Collection<String>): Decision? {
        val wanted = labels.map(::normalize).filter { it.isNotBlank() }.toSet()
        val matches = screen.nodes.mapNotNull { node ->
            if (!node.visible || !node.enabled || !node.actionable) return@mapNotNull null
            val names = listOf(node.text, node.description).map(::normalize).filter { it.isNotBlank() }
            // Match a standalone feature name, or the standard "Use <feature>" switch.
            // Never interpret Wi-Fi calling, Bluetooth scanning, etc. as the radio.
            val identity = names.any { name -> wanted.any { label ->
                name == label || name == "use $label" || name.substringBefore(',').trim() == label
            } }
            if (!identity) return@mapNotNull null
            val state = if (node.checkable) node.checked else when (normalize(node.stateDescription)) {
                "on", "enabled", "connected", "on, connected", "on, not connected" -> true
                "off", "disabled" -> false
                else -> return@mapNotNull null
            }
            Decision(node, state)
        }
        // Labels and their clickable ancestor may both expose the same switch.
        val groups = matches.groupBy { it.node.clickTargetIndex }
        if (groups.size != 1) return null
        val group = groups.values.single()
        if (group.map { it.enabled }.distinct().size != 1) return null
        return group.first()
    }
}
