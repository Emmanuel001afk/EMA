package com.ema.assistant.aeo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmaAeoCapabilityManifestTest {
    private val now = 1_800_000_000L

    @Test fun publishesTheExistingEmaCapabilityCatalogue() {
        val manifest = EmaAeoCapabilityManifest.create("1.0.0", now)
        assertTrue(EmaAeoCapabilityManifest.validate(manifest, now))
        assertTrue(manifest.getJSONArray("capabilities").length() > 0)
        assertTrue(manifest.getJSONObject("platform").getString("id") == "com.ema.assistant")
    }

    @Test fun rejectsExpiredManifest() {
        val manifest = EmaAeoCapabilityManifest.create("1.0.0", now)
        assertFalse(EmaAeoCapabilityManifest.validate(manifest, now + 301))
    }

    @Test fun rejectsAlteredCapabilityMetadata() {
        val manifest = EmaAeoCapabilityManifest.create("1.0.0", now)
        manifest.getJSONArray("capabilities")
            .getJSONObject(0)
            .put("title", "Forged capability")
        assertFalse(EmaAeoCapabilityManifest.validate(manifest, now))
    }
}
