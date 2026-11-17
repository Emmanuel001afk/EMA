package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for EMA's offline Inverse Text Normalizer (ITN), covering
 * spoken clock times, phone/digit runs, compound ordinals, negative math,
 * spoken emails/domains, disfluency & "scratch that" editing, regional app &
 * contact & screen-control grounding, protected message bodies, dictation
 * formatting and privacy boundaries.
 */
class EmaInverseTextNormalizerTest {

    private val context = EmaSpeechContext(
        shortcuts = listOf("for me", "my bank"),
        installedApps = listOf("WhatsApp", "YouTube", "Spotify", "OPay", "PalmPay", "Moniepoint", "Paystack", "TikTok", "ChatGPT", "Settings"),
        contactNames = listOf("Daniel", "Tunde Okafor", "Emeka", "Mummy", "Ada Lovelace", "Grace"),
        screenLabels = listOf("Paystack Checkout", "Sign in with Google", "Export PDF")
    )

    private fun norm(raw: String, ctx: EmaSpeechContext = context): String =
        EmaInverseTextNormalizer.normalize(raw, EmaRecognitionProfile.COMMAND, ctx)

    @Test
    fun pluralSplitSpotifyIsRecoveredWithoutInventingMissingActions() {
        assertEquals("open Spotify", norm("open spots if i"))
        val unclear = norm("open spots if i agree at it")
        assertTrue(unclear, unclear.contains("agree at it"))
        assertFalse(unclear.contains("play", ignoreCase = true))
        val body = norm("message Ada saying open spots if i agree at it")
        assertTrue(body, body.contains("spots if I agree at it"))
    }

    @Test
    fun normalizesSpokenClockTimesAndDualAlarms() {
        assertEquals("set an alarm for 6:30 am", norm("set an alarm for six thirty a m"))
        assertEquals("set an alarm for 7:05 pm", norm("set an alarm for seven oh five p m"))
        assertEquals("set an alarm for 6 and 6:30 am", norm("set an alarm for six and six thirty a m"))
    }

    @Test
    fun normalizesSpokenPhoneNumbersDoubleTripleAndTrailingDigits() {
        assertEquals(
            "call +2348034456789",
            norm("call plus two three four eight zero three double four five six seven eight nine")
        )
        assertEquals(
            "dial 07015550912 with SIM 2",
            norm("dial zero seven zero one triple five oh nine one two with seem two")
        )
        assertEquals(
            "call Daniel ending 4210",
            norm("call daniel ending four two one zero")
        )
    }

    @Test
    fun preservesRelativeClockIdiomsAndVerbAm() {
        assertEquals("set an alarm for half past six", norm("set an alarm for half past six"))
        assertEquals("set an alarm for quarter to seven", norm("set an alarm for quarter to seven"))
        assertEquals("set an alarm for 7 I am going out", norm("set an alarm for seven i am going out"))
    }

    @Test
    fun normalizesDurationsPercentagesNegativeMathOrdinalsAndCompounds() {
        assertEquals("set a timer for 25 minutes", norm("set a timer for twenty five minutes"))
        assertEquals("set volume to 75%", norm("set volume to seventy five percent"))
        assertEquals("what is 15% of 2400", norm("what is fifteen percent of two thousand four hundred"))
        assertEquals("what is -15 plus 40", norm("what is negative fifteen plus forty"))
        assertEquals("convert -40 degrees celsius to fahrenheit", norm("convert negative forty degrees celsius two fahrenheit"))
        assertEquals("convert 2.5 kilometers to miles", norm("convert two point five kilometers to miles"))
        assertEquals("remind me on march 21st", norm("remind me on march twenty first"))
        assertEquals("turn on Wi-Fi", norm("turn on wi fi"))
        assertEquals("call Daniel with SIM 2", norm("call daniel with seem two"))
    }

    @Test
    fun normalizesSpokenEmailsAndDomainsWithoutTouchingOrdinaryPrepositions() {
        assertEquals(
            "alex.okafor@gmail.com",
            EmaInverseTextNormalizer.normalizeEmailsAndDomains("alex dot okafor at gmail dot com")
        )
        assertEquals(
            "visit github.com and ema.com.ng",
            EmaInverseTextNormalizer.normalizeEmailsAndDomains("visit github dot com and ema dot com dot ng")
        )
        assertEquals(
            "meet me at the park",
            EmaInverseTextNormalizer.normalizeEmailsAndDomains("meet me at the park")
        )
    }

    @Test
    fun cleansStuttersInlineHesitationsAndCommandSelfRepairs() {
        assertEquals("open YouTube and play jazz", norm("um open open uh you tube and play jazz"))
        assertEquals("set an alarm for 6:30 am", norm("set an alarm for um six thirty a m"))
        assertEquals("open Instagram", norm("open facebook scratch that open insta gram"))
    }

    @Test
    fun groundsAppsContactsAndScreenControlsWhileKeepingMessageBodiesVerbatim() {
        assertEquals(
            "message Daniel on WhatsApp that I am outside",
            norm("message daniel on what's up that i am outside")
        )
        assertEquals(
            "text Daniel saying what's up",
            norm("text daniel saying what's up")
        )
        assertEquals("open OPay", norm("open oh pay"))
        assertEquals("open PalmPay", norm("open palm pay"))
        assertEquals("open Moniepoint", norm("open money point"))
        assertEquals("call Tunde", norm("call tune day"))
        assertEquals("call Emeka", norm("call eh mecca"))
        assertEquals("tap Paystack Checkout", norm("tap pay stack check out"))
    }

    @Test
    fun preservesOwnerShortcutsPackageSpellingPronounOneAndStandaloneOrdinals() {
        val withPhormi = context.copy(installedApps = context.installedApps + "Phormi")
        assertEquals("for me", norm("for me", withPhormi))
        assertEquals("open for me", norm("open for me", withPhormi))
        assertEquals(
            "remember for me means phormi package spelling P H O R M I",
            norm("remember for me means phormi package spelling p h o r m i", withPhormi)
        )
        assertEquals("choose the longest one", norm("choose the longest one"))
        assertEquals("play the third result", norm("play the third result"))
    }

    @Test
    fun formatsRichDictationWithScratchThatProperNounsEmailsCurrencyAndParagraphs() {
        val dictated = EmaInverseTextNormalizer.normalize(
            raw = "hello daniel comma i will arrive on monday at six thirty p m period are you home question mark",
            profile = EmaRecognitionProfile.DICTATION,
            context = context
        )
        assertEquals("Hello Daniel, I will arrive on Monday at 6:30 pm. Are you home?", dictated)

        // Voice-editing "scratch that" deletes only the last clause, preserving earlier sentences.
        val edited = EmaInverseTextNormalizer.normalize(
            raw = "the total is twenty five dollars and fifty cents full stop send it tomorrow scratch that email alex dot okafor at gmail dot com exclamation mark",
            profile = EmaRecognitionProfile.DICTATION,
            context = context
        )
        assertEquals("The total is $25.50. Email alex.okafor@gmail.com!", edited)

        // Noun "grace" and "trial period" are never corrupted in dictation.
        val safeNouns = EmaInverseTextNormalizer.normalize(
            raw = "we offer a short grace period comma thank you full stop",
            profile = EmaRecognitionProfile.DICTATION,
            context = context
        )
        assertEquals("We offer a short grace period, thank you.", safeNouns)

        // Multi-paragraph file transcripts preserve paragraph breaks.
        val multiPara = EmaInverseTextNormalizer.normalize(
            raw = "first agenda item on monday.\n\nsecond item in lagos.",
            profile = EmaRecognitionProfile.DICTATION,
            context = context
        )
        assertEquals("First agenda item on Monday.\n\nSecond item in Lagos.", multiPara)

        // Optional caller flags: disable formatting & offensive-word masking.
        val unformatted = EmaInverseTextNormalizer.normalize(
            raw = "hello comma world [unk]",
            profile = EmaRecognitionProfile.DICTATION,
            enableFormatting = false
        )
        assertEquals("hello comma world", unformatted)

        val masked = EmaInverseTextNormalizer.normalize(
            raw = "what the shit happened",
            profile = EmaRecognitionProfile.DICTATION,
            maskOffensiveWords = true
        )
        assertEquals("What the s*** happened", masked)
    }

    @Test
    fun supportsConfirmationAndWebSearchProfilesAndProtectsPrivateHints() {
        assertEquals(
            "yes",
            EmaInverseTextNormalizer.normalize("uh yep", EmaRecognitionProfile.CONFIRMATION, context)
        )
        assertEquals(
            "SIM 2",
            EmaInverseTextNormalizer.normalize("seem two", EmaRecognitionProfile.CONFIRMATION, context)
        )
        assertEquals(
            "GitHub offline speech Android",
            EmaInverseTextNormalizer.normalize("github offline speech android", EmaRecognitionProfile.WEB_SEARCH, context)
        )

        val privacyCtx = context.copy(learnedReplacements = mapOf("open for me" to "open Phormi"))
        val hints = privacyCtx.publicBiasingHints()
        assertFalse("Contacts must never leak into public biasing hints", hints.contains("Tunde Okafor"))
        assertFalse("Screen labels must never leak into public biasing hints", hints.contains("Paystack Checkout"))
        assertFalse("Learned replacements must never leak into public biasing hints", hints.contains("open Phormi"))
    }

    @Test
    fun normalizedCommandsParseDirectlyInEmaGrammarAndKnowledge() {
        val alarm = norm("set an alarm for six forty five a m")
        val cmd = EmaCommandGrammar.interpret(alarm)
        assertTrue("Expected SetAlarm, got $cmd for '$alarm'", cmd is EmaCommand.SetAlarm)

        val calc = norm("what is twenty percent of five hundred")
        val answer = EmaKnowledge.answer(calc)
        assertTrue("Expected offline calculation answer for '$calc'", answer?.text?.contains("100") == true)
    }
}
