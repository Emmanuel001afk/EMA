package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contact resolution ranking.
 *
 * Ranking is what lets EMA tell one clear match from several equally good ones,
 * so a wrong person is never silently called or messaged.
 */
class ContactLookupTest {

    private fun rank(name: String, wanted: String) = ContactLookup.rank(name, wanted)

    @Test
    fun exactWholeNameIsTheBestMatch() {
        assertEquals(0, rank("Ada Lovelace", "ada lovelace"))
        assertEquals(0, rank("Ada Lovelace", "ADA   LOVELACE"))
        assertEquals(0, rank("  Ada Lovelace  ", "ada lovelace"))
    }

    @Test
    fun repeatedWhitespaceOnEitherSideStillMatchesExactly() {
        // Voice transcripts commonly carry doubled spaces.
        assertEquals(0, rank("Ada   Lovelace", "ada lovelace"))
        assertEquals(0, rank("Ada Lovelace ", " ada  lovelace "))
    }

    @Test
    fun singleNameMatchesAWordOfTheContact() {
        assertEquals(1, rank("Ada Lovelace", "ada"))
        assertEquals(1, rank("Ada Lovelace", "lovelace"))
    }

    @Test
    fun multiWordQueryMatchesInAnyOrder() {
        assertEquals(1, rank("Ada Lovelace", "lovelace ada"))
        assertEquals(1, rank("Grace Brewster Hopper", "grace hopper"))
    }

    @Test
    fun aCompleteNameInsideALongerContactStillRanksAsWholeWords() {
        assertEquals(1, rank("Dr. Ada Lovelace", "ada lovelace"))
        assertEquals(1, rank("Lady Ada", "ada"))
    }

    @Test
    fun wholeWordBeatsSubstringOfAnotherContact() {
        // "Ada" must not silently resolve to "Adaline" when Ada exists.
        assertTrue(rank("Ada Lovelace", "ada") < rank("Adaline Lovelace", "ada"))
        assertEquals(1, rank("Ada Lovelace", "ada"))
        assertEquals(2, rank("Adaline Lovelace", "ada"))
    }

    @Test
    fun prefixAndSuffixMatchesAreWeakerThanWholeWords() {
        assertEquals(2, rank("Adaline Lovelace", "adal"))
        assertEquals(3, rank("Adaline Lovelace", "line"))
    }

    @Test
    fun looseSubstringIsTheWeakestMatch() {
        assertEquals(4, rank("Bradley Cooper", "rad"))
    }

    @Test
    fun unrelatedNamesNeverMatch() {
        assertEquals(Int.MAX_VALUE, rank("Grace Hopper", "ada"))
        assertEquals(Int.MAX_VALUE, rank("Alan Turing", "ada lovelace"))
    }

    @Test
    fun blankInputNeverMatches() {
        assertEquals(Int.MAX_VALUE, rank("", "ada"))
        assertEquals(Int.MAX_VALUE, rank("Ada Lovelace", ""))
        assertEquals(Int.MAX_VALUE, rank("   ", "   "))
    }

    // --------------------------------------------------------------------- best

    private fun contact(name: String, phone: String? = null, email: String? = null, r: Int) =
        ContactLookup.Contact(name, phone, email, r)

    @Test
    fun bestKeepsOnlyTheStrongestRank() {
        val candidates = listOf(
            contact("Ada Lovelace", phone = "111", r = 0),
            contact("Adaline Lovelace", phone = "222", r = 2)
        )
        val best = ContactLookup.best(candidates)
        assertEquals(1, best.size)
        assertEquals("Ada Lovelace", best[0].displayName)
    }

    @Test
    fun bestSurfacesTiesSoEmaCanAskInsteadOfGuessing() {
        val candidates = listOf(
            contact("Ada Lovelace", phone = "111", r = 0),
            contact("Ada Lovelace", phone = "222", r = 0)
        )
        assertEquals(2, ContactLookup.best(candidates).size)
    }

    @Test
    fun bestDeduplicatesIdenticalEntries() {
        val candidates = listOf(
            contact("Ada Lovelace", phone = "111", r = 0),
            contact("Ada Lovelace", phone = "111", r = 0),
            contact("ada lovelace", phone = "111", r = 0)
        )
        assertEquals(1, ContactLookup.best(candidates).size)
    }

    @Test
    fun bestOfEmptyIsEmpty() {
        assertTrue(ContactLookup.best(emptyList()).isEmpty())
    }

    @Test
    fun distinctWorkAndPersonalNumbersAreBothTies() {
        val candidates = listOf(
            contact("Ada Lovelace", email = "ada@work.example", r = 1),
            contact("Ada Lovelace", email = "ada@home.example", r = 1)
        )
        assertEquals(2, ContactLookup.best(candidates).size)
    }
}
