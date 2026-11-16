package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for EMA's offline phonetic encoder and safety-gated entity
 * resolver (app names and personal contacts).
 */
class EmaPhoneticMatcherTest {

    @Test
    fun matchesSplitAndHomophoneAppNames() {
        val apps = listOf("WhatsApp", "YouTube", "TikTok", "ChatGPT", "OPay", "Moniepoint", "Phormi", "Spotify", "Settings")

        assertEquals("WhatsApp", EmaPhoneticMatcher.bestUnambiguousMatch("whats app", apps)?.canonical)
        assertEquals("YouTube", EmaPhoneticMatcher.bestUnambiguousMatch("you tube", apps)?.canonical)
        assertEquals("TikTok", EmaPhoneticMatcher.bestUnambiguousMatch("tick tock", apps)?.canonical)
        assertEquals("ChatGPT", EmaPhoneticMatcher.bestUnambiguousMatch("chat gee pee tee", apps)?.canonical)
        assertEquals("OPay", EmaPhoneticMatcher.bestUnambiguousMatch("oh pay", apps)?.canonical)
        assertEquals("Moniepoint", EmaPhoneticMatcher.bestUnambiguousMatch("money point", apps)?.canonical)
        assertEquals("Phormi", EmaPhoneticMatcher.bestUnambiguousMatch("for me", apps)?.canonical)
    }

    @Test
    fun matchesPhoneticContactSpellingsUnambiguously() {
        val contacts = listOf("Tunde", "Chidi", "Ada Lovelace", "Emmanuel", "Grace Hopper", "Daniel")

        assertEquals("Tunde", EmaPhoneticMatcher.bestUnambiguousMatch("tune day", contacts)?.canonical)
        assertEquals("Daniel", EmaPhoneticMatcher.bestUnambiguousMatch("daniel", contacts)?.canonical)
        assertEquals("Ada Lovelace", EmaPhoneticMatcher.bestUnambiguousMatch("ada lovelace", contacts)?.canonical)
    }

    @Test
    fun refusesAmbiguousNearTiesInsteadOfGuessingWrongPerson() {
        val contacts = listOf("Kristen", "Kristin")
        assertNull(
            "Near-identical phonetic ties must stay unresolved so EMA asks",
            EmaPhoneticMatcher.bestUnambiguousMatch("christen", contacts, minScore = 0.80, minMargin = 0.08)
        )
    }

    @Test
    fun neverRewritesProtectedWordsOrUnrelatedPhrases() {
        val apps = listOf("Homer", "Backer", "Timely", "Spotify")
        assertNull(EmaPhoneticMatcher.bestUnambiguousMatch("home", apps))
        assertNull(EmaPhoneticMatcher.bestUnambiguousMatch("back", apps))
        assertNull(EmaPhoneticMatcher.bestUnambiguousMatch("timer", apps))
        assertNull(EmaPhoneticMatcher.bestUnambiguousMatch("weather tomorrow", apps))
        // Exact app name still matches cleanly.
        assertEquals("Timer", EmaPhoneticMatcher.bestUnambiguousMatch("timer", listOf("Timer"))?.canonical)
        assertTrue(EmaPhoneticMatcher.similarity("Spotify", "spotify") == 1.0)
    }
}
