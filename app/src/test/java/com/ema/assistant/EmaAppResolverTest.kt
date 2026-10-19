package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Open any app" depends entirely on mapping spoken text to a real installed
 * application. These tests pin that behaviour down so it cannot regress into
 * guessing.
 */
class EmaAppResolverTest {

    private val installed = listOf(
        EmaAppResolver.App("WhatsApp", "com.whatsapp"),
        EmaAppResolver.App("WhatsApp Business", "com.whatsapp.w4b"),
        EmaAppResolver.App("Spotify", "com.spotify.music"),
        EmaAppResolver.App("YouTube", "com.google.android.youtube"),
        EmaAppResolver.App("YouTube Music", "com.google.android.apps.youtube.music"),
        EmaAppResolver.App("Instagram", "com.instagram.android"),
        EmaAppResolver.App("Messenger", "com.facebook.orca"),
        EmaAppResolver.App("Telegram", "org.telegram.messenger"),
        EmaAppResolver.App("Chrome", "com.android.chrome"),
        EmaAppResolver.App("Opera Browser", "com.opera.browser"),
        EmaAppResolver.App("Gmail", "com.google.android.gm"),
        EmaAppResolver.App("Messages", "com.google.android.apps.messaging"),
        EmaAppResolver.App("Phone", "com.google.android.dialer"),
        EmaAppResolver.App("Clock", "com.google.android.deskclock"),
        EmaAppResolver.App("Calendar", "com.google.android.calendar"),
        EmaAppResolver.App("Settings", "com.android.settings"),
        EmaAppResolver.App("Camera", "com.android.camera"),
        EmaAppResolver.App("Google Maps", "com.google.android.apps.maps"),
        EmaAppResolver.App("Files by Google", "com.google.android.apps.nbu.files"),
        EmaAppResolver.App("Netflix", "com.netflix.mediaclient"),
        EmaAppResolver.App("My Music Player", "com.ema.musicplayer")
    )

    private fun resolve(name: String) = EmaAppResolver.resolve(name, installed)

    @Test
    fun exactLabelWins() {
        assertEquals("com.whatsapp", resolve("WhatsApp")?.app?.packageName)
    }

    @Test
    fun caseAndPunctuationAreIgnored() {
        assertEquals("com.whatsapp", resolve("whatsapp")?.app?.packageName)
        assertEquals("com.google.android.youtube", resolve("you tube")?.app?.packageName)
    }

    @Test
    fun spokenAliasesResolve() {
        assertEquals("com.whatsapp", resolve("wa")?.app?.packageName)
        assertEquals("com.instagram.android", resolve("insta")?.app?.packageName)
        assertEquals("com.google.android.youtube", resolve("yt")?.app?.packageName)
        assertEquals("org.telegram.messenger", resolve("tg")?.app?.packageName)
    }

    @Test
    fun decoratedReferencesResolve() {
        assertEquals("com.spotify.music", resolve("the Spotify app")?.app?.packageName)
        assertEquals("com.whatsapp", resolve("my WhatsApp")?.app?.packageName)
    }

    @Test
    fun longerLabelBeatsShorterWhenAllWordsMatch() {
        // "youtube music" must not collapse to plain YouTube.
        assertEquals("com.google.android.apps.youtube.music", resolve("YouTube Music")?.app?.packageName)
    }

    @Test
    fun whatsappBusinessIsDistinguishedFromWhatsApp() {
        assertEquals("com.whatsapp.w4b", resolve("WhatsApp Business")?.app?.packageName)
        assertEquals("com.whatsapp", resolve("whatsapp messenger")?.app?.packageName)
    }

    @Test
    fun genericCategoriesMapToRealApps() {
        assertEquals("com.google.android.apps.messaging", resolve("messages")?.app?.packageName)
        assertEquals("com.google.android.apps.messaging", resolve("sms")?.app?.packageName)
        assertEquals("com.google.android.dialer", resolve("dialer")?.app?.packageName)
        assertEquals("com.google.android.deskclock", resolve("alarm")?.app?.packageName)
    }

    @Test
    fun browserCategoryPrefersAnInstalledBrowserLabelOverChrome() {
        assertEquals("com.opera.browser", resolve("browser")?.app?.packageName)
    }

    @Test
    fun browserDoesNotAssumeChromeWhenNoBrowserLabelMatches() {
        val nonBrowserApps = installed.filterNot { it.packageName == "com.opera.browser" }
        assertNull(EmaAppResolver.resolve("browser", nonBrowserApps))
    }

    @Test
    fun resolutionIsDeterministic() {
        val first = resolve("whatsapp")
        repeat(20) { assertEquals(first, resolve("whatsapp")) }
    }

    @Test
    fun unknownAppReturnsNullRatherThanAGuess() {
        assertNull(resolve("NotInstalledAnywhere"))
        assertNull(resolve(""))
        assertNull(resolve("   "))
    }

    @Test
    fun noiseOnlyReturnsNull() {
        assertNull(resolve("the app please"))
    }

    // ------------------------------------------------------------- span find

    @Test
    fun findsLongestInstalledAppNameInsideFreeText() {
        val span = EmaAppResolver.findSpan("please get spotify to play some music", installed)
        assertNotNull(span)
        assertEquals("com.spotify.music", span!!.app.packageName)
        assertEquals("spotify", span.matched)
    }

    @Test
    fun spanMatchIsWordBounded() {
        // "youtuber" must not match the YouTube app.
        assertNull(EmaAppResolver.findSpan("ask the youtuber about it", installed))
    }

    @Test
    fun spanRemovalLeavesTheActionText() {
        val span = EmaAppResolver.findSpan("play arcade on spotify now", installed)!!
        val remainder = EmaAppResolver.removeSpan("play arcade on spotify now", span)
        assertEquals("play arcade now", remainder)
    }

    @Test
    fun spanPrefersTheLongestMatch() {
        val span = EmaAppResolver.findSpan("open youtube music please", installed)
        assertNotNull(span)
        assertEquals("com.google.android.apps.youtube.music", span!!.app.packageName)
    }

    @Test
    fun spanFindingIsPurelyLexical() {
        // Documented behaviour, not a bug: findSpan only reports that an
        // installed app name occurs in the text. Deciding whether the sentence
        // is actually a request to act on that app is the caller's job, which is
        // why EmaNativeFallbackInterpreter also requires an action verb.
        assertNull("no installed app name occurs here", EmaAppResolver.findSpan("I am happy today", installed))
        assertNotNull(EmaAppResolver.findSpan("open Netflix", installed))
    }

    @Test
    fun normalizeStripsPunctuation() {
        assertEquals("whatsapp", EmaAppResolver.normalize("WhatsApp."))
        assertEquals("you tube", EmaAppResolver.normalize("You-Tube"))
    }

    @Test
    fun stripNoiseRemovesLeadingAndTrailingDecoration() {
        assertEquals("spotify", EmaAppResolver.stripNoise("the spotify app"))
        assertTrue(EmaAppResolver.stripNoise("open spotify").isNotBlank())
    }
}
