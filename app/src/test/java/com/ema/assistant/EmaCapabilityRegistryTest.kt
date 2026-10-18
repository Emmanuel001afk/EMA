package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test
import com.ema.assistant.EmaCapabilityRegistry.Prerequisite

class EmaCapabilityRegistryTest {
    @Test fun messagingNeedsContactsAndScreenControl() {
        val cap = EmaCapabilityRegistry.forCommand(EmaCommand.SendMessage("Daniel", "WhatsApp", "hi"))!!
        assertTrue(cap.consequential)
        assertEquals(setOf(Prerequisite.ACCESSIBILITY), EmaCapabilityRegistry.missing(cap, setOf(Prerequisite.CONTACTS)))
        val msg = EmaCapabilityRegistry.explainMissing(cap, setOf(Prerequisite.ACCESSIBILITY))
        assertTrue(msg.contains("screen control"))
        assertTrue(msg.contains("I haven't done anything yet"))
    }

    @Test fun smsDoesNotNeedScreenControl() {
        val cap = EmaCapabilityRegistry.forCommand(EmaCommand.SendMessage("Mum", null, "hi"), smsApp = true)!!
        assertTrue(EmaCapabilityRegistry.missing(cap, setOf(Prerequisite.CONTACTS)).isEmpty())
    }

    @Test fun describeReflectsWhatIsGranted() {
        val none = EmaCapabilityRegistry.describe(emptySet(), aiAvailable = false)
        assertTrue(none.contains("calculations"))
        assertTrue(none.contains("With "))
        assertTrue(none.contains("works offline"))
        val all = EmaCapabilityRegistry.describe(Prerequisite.values().toSet(), aiAvailable = true)
        assertFalse(all.contains("With "))
        assertTrue(all.contains("optional AI"))
    }

    @Test fun everyCapabilityStatesHowItIsVerified() {
        for (c in EmaCapabilityRegistry.ALL) {
            assertTrue(c.id, c.verification.isNotBlank())
            assertTrue(c.id, c.mechanisms.isNotEmpty())
        }
    }
}
