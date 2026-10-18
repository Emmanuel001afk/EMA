package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaResultExtractorTest {

    private fun titles(items: List<EmaResultItem>) = items.map { it.title }

    @Test fun extractsRowsInReadingOrderAndIgnoresChrome() {
        val items = EmaResultExtractor.extract(EmaResultScreens.footballWithDurations(), "football")
        assertEquals(listOf("Best boots of the year", "Football highlights: derby day", "Every goal of the season"), titles(items))
        assertTrue(items[0].sponsored)
        assertFalse(items[1].sponsored)
        assertEquals(12 * 60 + 34, items[1].durationSeconds)
        assertEquals(3600 + 2 * 60 + 10, items[2].durationSeconds)
        assertEquals(1_200_000L, items[1].views)
    }

    @Test fun genericListRowsOfAPrivateAppAreExtracted() {
        val items = EmaResultExtractor.extract(EmaResultScreens.portalInvoices(), "invoices")
        assertEquals(3, items.size)
        assertEquals("Invoice INV-1001 Lagos office", items[0].title)
        assertNull(items[0].durationSeconds)
    }

    @Test fun firstResultSkipsAdsAndSaysSo() {
        val items = EmaResultExtractor.extract(EmaResultScreens.footballWithDurations(), "football")
        val c = EmaResultExtractor.choose(items, EmaSelection.Ordinal(1)) as EmaResultExtractor.Choice.Chosen
        assertEquals("Football highlights: derby day", c.item.title)
        assertTrue(c.note!!.contains("ad"))
    }

    @Test fun longestUsesObservedDurations() {
        val items = EmaResultExtractor.extract(EmaResultScreens.footballPage2(), "football")
        val c = EmaResultExtractor.choose(items, EmaSelection.Longest) as EmaResultExtractor.Choice.Chosen
        assertEquals("Every goal of the season", c.item.title)
        val s = EmaResultExtractor.choose(items, EmaSelection.Shortest) as EmaResultExtractor.Choice.Chosen
        assertEquals("Training drills for beginners", s.item.title)
    }

    @Test fun longestRefusesWithoutDurationsInsteadOfGuessing() {
        val items = EmaResultExtractor.extract(EmaResultScreens.footballWithoutDurations(), "football")
        val c = EmaResultExtractor.choose(items, EmaSelection.Longest)
        assertTrue(c is EmaResultExtractor.Choice.Refused)
        assertTrue((c as EmaResultExtractor.Choice.Refused).reason.contains("won't guess"))
    }

    @Test fun ordinalBeyondVisibleAsksForMore() {
        val items = EmaResultExtractor.extract(EmaResultScreens.footballWithDurations(), "football")
        assertTrue(EmaResultExtractor.choose(items, EmaSelection.Ordinal(3)) is EmaResultExtractor.Choice.NeedMore)
        val last = EmaResultExtractor.choose(items, EmaSelection.Last) as EmaResultExtractor.Choice.Chosen
        assertEquals("Every goal of the season", last.item.title)
    }

    @Test fun anotherSkipsWhatWasAlreadyChosen() {
        val items = EmaResultExtractor.extract(EmaResultScreens.footballPage2(), "football")
        val c = EmaResultExtractor.choose(items, EmaSelection.Another, listOf("Every goal of the season")) as EmaResultExtractor.Choice.Chosen
        assertEquals("Training drills for beginners", c.item.title)
    }

    @Test fun titledSelectionMatchesWords() {
        val items = EmaResultExtractor.extract(EmaResultScreens.portalInvoices(), "invoices")
        val c = EmaResultExtractor.choose(items, EmaSelection.Titled("the Abuja office invoice")) as EmaResultExtractor.Choice.Chosen
        assertEquals("Invoice INV-1002 Abuja office", c.item.title)
    }

    @Test fun onlyAdsAreRefused() {
        val ad = EmaResultItem(0, "Buy now", "Sponsored", 0, 0, 10, 10, sponsored = true)
        assertTrue(EmaResultExtractor.choose(listOf(ad), EmaSelection.Ordinal(1)) is EmaResultExtractor.Choice.Refused)
    }

    @Test fun aTitleStartingWithAVerbIsContent() {
        val screen = EmaResultScreens.Builder(EmaResultScreens.YT).apply {
            chrome("kids songs")
            row("Play with me: colours song", "Kids TV · 9K views", "3:10", 270)
            row("Open the box song", "Kids TV · 4K views", "2:05", 840)
        }.build()
        assertEquals(listOf("Play with me: colours song", "Open the box song"), EmaResultExtractor.extract(screen, "kids songs").map { it.title })
    }

    @Test fun playerPageHasNoResultRows() {
        assertTrue(EmaResultExtractor.extract(EmaResultScreens.playerPage(), "football").size < 2)
    }

    @Test fun titlesMatchToleratesDecorationButNotOtherVideos() {
        assertTrue(EmaResultExtractor.titlesMatch("Football highlights: derby day", "Football Highlights - Derby Day (Official)"))
        assertFalse(EmaResultExtractor.titlesMatch("Football highlights: derby day", "Best boots of the year"))
        assertFalse(EmaResultExtractor.titlesMatch("Football highlights", ""))
    }

    @Test fun durationParsing() {
        assertEquals(754, EmaResultExtractor.parseDurationSeconds("12:34"))
        assertEquals(3730, EmaResultExtractor.parseDurationSeconds("1:02:10"))
        assertEquals(12 * 60 + 3, EmaResultExtractor.parseDurationSeconds("12 minutes, 3 seconds"))
    }
}
