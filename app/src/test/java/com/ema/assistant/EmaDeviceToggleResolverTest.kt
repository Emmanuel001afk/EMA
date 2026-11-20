package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaDeviceToggleResolverTest {
    private fun node(i: Int = 0, name: String = "Wi-Fi", checked: Boolean = false) = EmaNodeSnapshot(i,
        text = name, visible = true, enabled = true, clickableSelf = true, checkable = true, checked = checked)
    private fun resolve(vararg nodes: EmaNodeSnapshot) = EmaDeviceToggleResolver.resolve(EmaScreenSnapshot("com.android.settings", nodes.toList()), listOf("wifi", "wi-fi"))
    @Test fun exactIdentityAndStateAreResolvedTogether() {
        assertEquals(false, resolve(node())?.enabled)
        assertEquals(true, resolve(node(name = "Use Wi-Fi", checked = true))?.enabled)
        assertNull(resolve(node(name = "Wi-Fi calling")))
        assertNull(resolve(node(name = "Wi-Fi scanning")))
        assertNull(resolve(node(name = "Internet")))
    }
    @Test fun ambiguousOrUnknownOrHiddenControlsNeverAuthorizeATap() {
        assertNull(resolve(node(), node(1, checked = true)))
        assertNull(resolve(node().copy(checkable = false)))
        assertNull(resolve(node().copy(enabled = false)))
        assertNull(resolve(node().copy(visible = false)))
        assertNull(resolve(node().copy(clickableSelf = false)))
        assertEquals(false, resolve(node().copy(checkable = false, stateDescription = "Off"))?.enabled)
        assertNull(resolve(node().copy(checkable = false, stateDescription = "Not enabled by administrator")))
    }
}
