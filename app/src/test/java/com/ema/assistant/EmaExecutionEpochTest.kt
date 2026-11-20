package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaExecutionEpochTest {
    @Test fun resetCannotReviveCallbacksFromAnAbandonedCommand() {
        val epoch = EmaExecutionEpoch()
        val first = epoch.begin()
        assertTrue(epoch.isCurrent(first))
        epoch.invalidate()
        assertFalse(epoch.isCurrent(first))
        val second = epoch.begin()
        assertTrue(epoch.isCurrent(second))
        assertFalse(epoch.isCurrent(first))
        assertNotEquals(first, second)
    }
    @Test fun replacingAnActiveCommandAlsoInvalidatesItsToken() {
        val epoch = EmaExecutionEpoch()
        val old = epoch.begin()
        val replacement = epoch.begin()
        assertFalse(epoch.isCurrent(old)); assertTrue(epoch.isCurrent(replacement))
        epoch.invalidate(); epoch.invalidate()
        assertFalse(epoch.isActive()); assertFalse(epoch.isCurrent(replacement))
    }
}
