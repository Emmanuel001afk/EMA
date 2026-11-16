package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for EMA's offline phonetic encoder and ambiguity-safe entity
 * resolver across global/regional apps and personal contacts.
 */
class EmaPhoneticMatcherTest {

    @Test
    fun matchesSplitHomophoneAndRegionalAppNames() {
        val apps = listOf(
            "WhatsApp", "YouTube", "TikTok", "ChatGPT", "OPay", "PalmPay",
            "Moniepoint", "Kuda", "Audiomack", "Boomplay", "Phormi", "Spotify", "Settings"
        )

        assertEquals("WhatsApp", EmaPhoneticMatcher.bestUnambiguousMatch("whats app", apps)?.canonical)
        assertEquals("YouTube", EmaPhoneticMatcher.bestUnambiguousMatch("you tube", apps)?.canonical)
        assertEquals("TikTok", EmaPhoneticMatcher.bestUnambiguousMatch("tick tock", apps)?.canonical)
        assertEquals("ChatGPT", EmaPhoneticMatcher.bestUnambiguousMatch("chat gee pee tee", apps)?.canonical)
        assertEquals("OPay", EmaPhoneticMatcher.bestUnambiguousMatch("oh pay", apps)?.canonical)
        assertEquals("PalmPay", EmaPhoneticMatcher.bestUnambiguousMatch("palm pay", apps)?.canonical)
        assertEquals("Moniepoint", EmaPhoneticMatcher.bestUnambiguousMatch("money point", apps)?.canonical)
        assertEquals("Kuda", EmaPhoneticMatcher.bestUnambiguousMatch("could a", apps)?.canonical)
        assertEquals("Audiomack", EmaPhoneticMatcher.bestUnambiguousMatch("audio mac", apps)?.canonical)
        assertEquals("Boomplay", EmaPhoneticMatcher.bestUnambiguousMatch("boom play", apps)?.canonical)
        assertEquals("Phormi", EmaPhoneticMatcher.bestUnambiguousMatch("for me", apps)?.canonical)
    }

    @Test
    fun matchesContactsAndPreservesSharedGivenNameAmbiguity() {
        val contacts = listOf("Tunde Okafor", "Tunde Balogun", "Emeka Nwosu", "Ada Lovelace", "Gbenga", "Daniel")

        // Both Tunde Okafor and Tunde Balogun exist -> resolves to "Tunde" (never silently picking one person!).
        assertEquals("Tunde", EmaPhoneticMatcher.bestContactSlotMatch("tune day", contacts)?.canonical)
        // Unique given name stored inside full contact -> resolves cleanly to "Emeka".
        assertEquals("Emeka", EmaPhoneticMatcher.bestContactSlotMatch("eh mecca", contacts)?.canonical)
        // Exact single & full names match cleanly.
        assertEquals("Daniel", EmaPhoneticMatcher.bestContactSlotMatch("daniel", contacts)?.canonical)
        assertEquals("Ada Lovelace", EmaPhoneticMatcher.bestContactSlotMatch("ada lovelace", contacts)?.canonical)
        assertTrue("Labial-velar gb onset encoded cleanly", EmaPhoneticMatcher.phoneticKey("Gbenga").startsWith("P"))
    }

    @Test
    fun refusesAmbiguousNearTiesInsteadOfGuessingWrongPerson() {
        val contacts = listOf("Kristen", "Kristin")
        assertNull(
            "Near-identical phonetic ties must stay unresolved so EMA asks",
            EmaPhoneticMatcher.bestUnambiguousMatch("christen", contacts, minScore = 0.80, minMargin = 0.08)
        )
        assertNull(
            EmaPhoneticMatcher.bestContactSlotMatch("christen", contacts)
        )
    }

    @Test
    fun neverRewritesProtectedWordsOrUnrelatedPhrases() {
        val apps = listOf("Homer", "Backer", "Timely", "Spotify")
        assertNull(EmaPhoneticMatcher.bestUnambiguousMatch("home", apps))
        assertNull(EmaPhoneticMatcher.bestUnambiguousMatch("back", apps))
        assertNull(EmaPhoneticMatcher.bestUnambiguousMatch("timer", apps))
        assertNull(EmaPhoneticMatcher.bestUnambiguousMatch("weather tomorrow", apps))
        assertEquals("Timer", EmaPhoneticMatcher.bestUnambiguousMatch("timer", listOf("Timer"))?.canonical)
        assertTrue(EmaPhoneticMatcher.similarity("Spotify", "spotify") == 1.0)
    }
}
