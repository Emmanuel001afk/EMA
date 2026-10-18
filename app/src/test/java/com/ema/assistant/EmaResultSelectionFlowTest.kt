package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaResultSelectionFlowTest {

    /** A fake phone: screens change in response to taps, back and scroll. */
    private class FakeDevice(var screen: EmaScreenSnapshot) : EmaResultSelectionFlow.Port {
        var foreground = screen.packageName
        var media: EmaResultSelectionFlow.Media? = null
        var mediaObservable = true
        val clicked = mutableListOf<String>()
        var backs = 0
        var scrolls = 0
        var onClick: (EmaResultItem) -> Unit = {}
        var onBack: () -> Unit = {}
        var pages: List<EmaScreenSnapshot> = emptyList()
        var clickWorks = true
        override var rerunSearch: ((done: (Boolean) -> Unit) -> Unit)? = null

        override fun foregroundPackage() = foreground
        override fun results(packageName: String, query: String?) = EmaResultExtractor.extract(screen, query)
        override fun click(packageName: String, item: EmaResultItem, query: String?): Boolean {
            if (!clickWorks) return false
            clicked += item.title
            onClick(item)
            return true
        }
        override fun scrollForward(packageName: String): Boolean {
            if (scrolls >= pages.size) return false
            screen = pages[scrolls++]
            return true
        }
        override fun back(): Boolean { backs++; onBack(); return true }
        override fun media(packageName: String) = if (mediaObservable) media ?: EmaResultSelectionFlow.Media("", false) else null
        override fun schedule(delayMs: Long, block: () -> Unit) = block()
    }

    private fun run(device: FakeDevice, selection: EmaSelection, play: Boolean, chosen: List<String> = emptyList(), query: String? = "football"): EmaResultSelectionFlow.Result {
        var result: EmaResultSelectionFlow.Result? = null
        EmaResultSelectionFlow(device, device.screen.packageName, "YouTube", selection, play, chosen, query).run { result = it }
        return result!!
    }

    @Test fun playFirstResultIsVerifiedByMediaSessionTitle() {
        val d = FakeDevice(EmaResultScreens.footballWithDurations())
        d.onClick = { item -> d.screen = EmaResultScreens.playerPage(); d.media = EmaResultSelectionFlow.Media(item.title, true) }
        val r = run(d, EmaSelection.Ordinal(1), play = true)
        assertEquals(EmaOutcomeStatus.SUCCESS, r.outcome.status)
        assertEquals(listOf("Football highlights: derby day"), d.clicked) // the ad was skipped
        assertTrue(r.outcome.message.contains("first result"))
        assertEquals("Football highlights: derby day", r.chosenTitle)
    }

    @Test fun tappingIsNotSuccessWhenPlaybackCannotBeObserved() {
        val d = FakeDevice(EmaResultScreens.footballWithDurations())
        d.mediaObservable = false
        d.onClick = { d.screen = EmaResultScreens.playerPage() }
        val r = run(d, EmaSelection.Ordinal(1), play = true)
        assertEquals(EmaOutcomeStatus.UNVERIFIED, r.outcome.status)
        assertTrue(r.outcome.message.contains("notification access"))
    }

    @Test fun somethingElsePlayingIsReportedNotClaimed() {
        val d = FakeDevice(EmaResultScreens.footballWithDurations())
        d.onClick = { d.screen = EmaResultScreens.playerPage(); d.media = EmaResultSelectionFlow.Media("Best boots of the year", true) }
        val r = run(d, EmaSelection.Ordinal(1), play = true)
        assertEquals(EmaOutcomeStatus.UNVERIFIED, r.outcome.status)
        assertTrue(r.outcome.message.contains("ad"))
    }

    @Test fun aTapThatChangesNothingIsAFailure() {
        val d = FakeDevice(EmaResultScreens.portalInvoices())
        val r = run(d, EmaSelection.Ordinal(2), play = false, query = "invoices")
        assertEquals(EmaOutcomeStatus.FAILED, r.outcome.status)
        assertEquals(listOf("Invoice INV-1002 Abuja office"), d.clicked)
    }

    @Test fun openingARowInAPrivateAppIsVerifiedByScreenChange() {
        val d = FakeDevice(EmaResultScreens.portalInvoices())
        d.onClick = { d.screen = EmaResultScreens.playerPage().copy(packageName = "com.acme.portal") }
        val r = run(d, EmaSelection.Ordinal(2), play = false, query = "invoices")
        assertEquals(EmaOutcomeStatus.SUCCESS, r.outcome.status)
        assertTrue(r.outcome.message.contains("second result"))
    }

    @Test fun longestWithoutVisibleLengthsRefusesAndTapsNothing() {
        val d = FakeDevice(EmaResultScreens.footballWithoutDurations())
        val r = run(d, EmaSelection.Longest, play = true)
        assertEquals(EmaOutcomeStatus.FAILED, r.outcome.status)
        assertTrue(d.clicked.isEmpty())
        assertTrue(r.outcome.message.contains("won't guess"))
    }

    @Test fun thirdResultScrollsToFindIt() {
        val d = FakeDevice(EmaResultScreens.footballWithDurations())
        d.pages = listOf(EmaResultScreens.footballPage2())
        d.onClick = { item -> d.screen = EmaResultScreens.playerPage(); d.media = EmaResultSelectionFlow.Media(item.title, true) }
        val r = run(d, EmaSelection.Ordinal(3), play = true)
        assertEquals(EmaOutcomeStatus.SUCCESS, r.outcome.status)
        assertEquals(1, d.scrolls)
        // Non-ad results in order: derby day, every goal, training drills.
        assertEquals(listOf("Training drills for beginners"), d.clicked)
    }

    @Test fun positionBeyondTheListFailsAfterBoundedScrolling() {
        val d = FakeDevice(EmaResultScreens.footballWithDurations())
        val r = run(d, EmaSelection.Ordinal(9), play = true)
        assertEquals(EmaOutcomeStatus.FAILED, r.outcome.status)
        assertTrue(d.clicked.isEmpty())
    }

    @Test fun followUpGoesBackFromThePlayerToTheListFirst() {
        val d = FakeDevice(EmaResultScreens.playerPage())
        d.onBack = { d.screen = EmaResultScreens.footballPage2() }
        d.onClick = { item -> d.screen = EmaResultScreens.playerPage(); d.media = EmaResultSelectionFlow.Media(item.title, true) }
        val r = run(d, EmaSelection.Longest, play = true, chosen = listOf("Every goal of the season"))
        assertEquals(1, d.backs)
        assertEquals(EmaOutcomeStatus.SUCCESS, r.outcome.status)
        assertEquals(listOf("Every goal of the season"), d.clicked)
    }

    @Test fun anotherOneSkipsThePreviouslyOpenedItem() {
        val d = FakeDevice(EmaResultScreens.playerPage())
        d.onBack = { d.screen = EmaResultScreens.footballPage2() }
        d.onClick = { d.screen = EmaResultScreens.playerPage() }
        val r = run(d, EmaSelection.Another, play = false, chosen = listOf("Every goal of the season"))
        assertEquals(EmaOutcomeStatus.SUCCESS, r.outcome.status)
        assertEquals(listOf("Training drills for beginners"), d.clicked)
    }

    @Test fun backIsBoundedWhenTheListNeverReturns() {
        val d = FakeDevice(EmaResultScreens.playerPage())
        val r = run(d, EmaSelection.Another, play = false, chosen = listOf("Every goal of the season"))
        assertEquals(EmaResultSelectionFlow.MAX_BACKS, d.backs)
        assertEquals(EmaOutcomeStatus.FAILED, r.outcome.status)
        assertTrue(d.clicked.isEmpty())
    }

    @Test fun wrongForegroundAppReSearchesWhenPossible() {
        val d = FakeDevice(EmaResultScreens.footballWithDurations())
        d.foreground = "com.other"
        var reran = false
        d.rerunSearch = { done -> reran = true; d.foreground = EmaResultScreens.YT; done(true) }
        d.onClick = { item -> d.screen = EmaResultScreens.playerPage(); d.media = EmaResultSelectionFlow.Media(item.title, true) }
        val r = run(d, EmaSelection.Ordinal(1), play = true)
        assertTrue(reran)
        assertEquals(EmaOutcomeStatus.SUCCESS, r.outcome.status)
    }

    @Test fun wrongForegroundWithoutQueryFailsHonestly() {
        val d = FakeDevice(EmaResultScreens.footballWithDurations())
        d.foreground = "com.other"
        val r = run(d, EmaSelection.Ordinal(1), play = true, query = null)
        assertEquals(EmaOutcomeStatus.FAILED, r.outcome.status)
        assertTrue(d.clicked.isEmpty())
    }

    @Test fun noListOnScreenFailsWithoutTapping() {
        val d = FakeDevice(EmaResultScreens.playerPage())
        val r = run(d, EmaSelection.Ordinal(1), play = true)
        assertEquals(EmaOutcomeStatus.FAILED, r.outcome.status)
        assertTrue(d.clicked.isEmpty())
    }

    @Test fun failedClickIsReported() {
        val d = FakeDevice(EmaResultScreens.footballWithDurations())
        d.clickWorks = false
        val r = run(d, EmaSelection.Ordinal(1), play = true)
        assertEquals(EmaOutcomeStatus.FAILED, r.outcome.status)
        assertNull(r.chosenTitle)
    }
}
