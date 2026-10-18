package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test
import com.ema.assistant.EmaVoiceSelector.Style
import com.ema.assistant.EmaVoiceSelector.VoiceInfo

class EmaVoiceSelectorTest {
    private val nigerianFemale = VoiceInfo("en-ng-x-tfn-local", "en-NG", quality = 400)
    private val usMale = VoiceInfo("en-us-x-iom-local", "en-US", quality = 400)
    private val usFemale = VoiceInfo("en-us-x-sfg-local", "en-US", quality = 400)
    private val ukMaleNetwork = VoiceInfo("en-gb-x-rjs-network", "en-GB", quality = 500, requiresNetwork = true)
    private val notDownloaded = VoiceInfo("en-us-x-iol-local", "en-US", quality = 500, notInstalled = true)
    private val all = listOf(nigerianFemale, usMale, usFemale, ukMaleNetwork, notDownloaded)

    @Test fun warmStylePrefersAnInstalledDeeperVoiceOverTheRegionalDefault() {
        val c = EmaVoiceSelector.choose(all, null, Style.WARM, "en-NG", allowNetwork = false)
        assertEquals(usMale, c.voice)
    }

    @Test fun notInstalledAndOnlineVoicesAreExcludedUnlessAllowed() {
        val ranked = EmaVoiceSelector.ranked(all, Style.WARM, "en-NG", allowNetwork = false)
        assertFalse(ranked.contains(notDownloaded))
        assertFalse(ranked.contains(ukMaleNetwork))
        assertTrue(EmaVoiceSelector.ranked(all, Style.WARM, "en-NG", allowNetwork = true).contains(ukMaleNetwork))
        assertFalse(EmaVoiceSelector.ranked(all, Style.WARM, "en-NG", allowNetwork = true).contains(notDownloaded))
    }

    @Test fun savedVoiceIsHonouredWhenUsable() {
        assertEquals(nigerianFemale, EmaVoiceSelector.choose(all, nigerianFemale.name, Style.WARM, "en-NG", false).voice)
        // A saved voice that is no longer installed falls back, and says so.
        val c = EmaVoiceSelector.choose(all, notDownloaded.name, Style.WARM, "en-NG", false)
        assertEquals(usMale, c.voice)
        assertTrue(c.reason.contains("no longer available"))
    }

    @Test fun systemStyleLeavesTheEngineDefault() {
        assertNull(EmaVoiceSelector.choose(all, null, Style.SYSTEM, "en-NG", false).voice)
    }

    @Test fun clearStylePrefersABrighterVoice() {
        val c = EmaVoiceSelector.choose(listOf(usMale, usFemale), null, Style.CLEAR, "en-US", false)
        assertEquals(usFemale, c.voice)
    }

    @Test fun nothingUsableMeansDefaultNotAFalseClaim() {
        val c = EmaVoiceSelector.choose(listOf(notDownloaded), null, Style.WARM, "en-US", false)
        assertNull(c.voice)
        assertTrue(c.reason.contains("default"))
    }

    @Test fun descriptionsOnlyStateReportedFacts() {
        assertTrue(EmaVoiceSelector.describe(usMale).contains("works offline"))
        assertTrue(EmaVoiceSelector.describe(ukMaleNetwork).contains("needs internet"))
        assertEquals(true, EmaVoiceSelector.knownMale("en-us-x-iom-local"))
        assertEquals(false, EmaVoiceSelector.knownMale("en-ng-x-tfn-local"))
        assertNull(EmaVoiceSelector.knownMale("some-vendor-voice"))
    }

    @Test fun warmStyleIsCalmerThanDefault() {
        assertTrue(Style.WARM.pitch < 1f && Style.WARM.rate < 1f)
        assertEquals(Style.WARM, Style.fromKey(null))
        assertEquals(Style.CLEAR, Style.fromKey("clear"))
    }
}
