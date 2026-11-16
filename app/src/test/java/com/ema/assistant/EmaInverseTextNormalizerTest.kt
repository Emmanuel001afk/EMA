package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for EMA's offline Inverse Text Normalizer (ITN), covering
 * spoken clock times, numbers, percentages, decimals, protected message bodies,
 * owner shortcut preservation, phonetic slot grounding and dictation punctuation.
 */
class EmaInverseTextNormalizerTest {

    private val context = EmaSpeechContext(
        shortcuts = listOf("for me", "my bank"),
        installedApps = listOf("WhatsApp", "YouTube", "Spotify", "OPay", "TikTok", "ChatGPT", "Settings"),
        contactNames = listOf("Daniel", "Tunde", "Mummy", "Ada Lovelace")
    )

    private fun norm(raw: String, ctx: EmaSpeechContext = context): String =
        EmaInverseTextNormalizer.normalize(raw, EmaRecognitionProfile.COMMAND, ctx)

    @Test
    fun normalizesSpokenClockTimesAndDualAlarms() {
        assertEquals("set an alarm for 6:30 am", norm("set an alarm for six thirty a m"))
        assertEquals("set an alarm for 7:05 pm", norm("set an alarm for seven oh five p m"))
        assertEquals("set an alarm for 6 and 6:30 am", norm("set an alarm for six and six thirty a m"))
    }

    @Test
    fun preservesRelativeClockIdiomsAndVerbAm() {
        assertEquals("set an alarm for half past six", norm("set an alarm for half past six"))
        assertEquals("set an alarm for quarter to seven", norm("set an alarm for quarter to seven"))
        // "am" in "I am going out" must never attach to "7" as 7 AM.
        assertEquals("set an alarm for 7 I am going out", norm("set an alarm for seven i am going out"))
    }

    @Test
    fun normalizesDurationsPercentagesCalculationsAndCompounds() {
        assertEquals("set a timer for 25 minutes", norm("set a timer for twenty five minutes"))
        assertEquals("set volume to 75%", norm("set volume to seventy five percent"))
        assertEquals("what is 15% of 2400", norm("what is fifteen percent of two thousand four hundred"))
        assertEquals("convert 2.5 kilometers to miles", norm("convert two point five kilometers to miles"))
        assertEquals("turn on Wi-Fi", norm("turn on wi fi"))
        assertEquals("call Daniel with SIM 2", norm("call daniel with seem two"))
    }

    @Test
    fun groundsAppAndContactSlotsWhileKeepingMessageBodiesVerbatim() {
        // "what's up" in the app slot becomes WhatsApp, while body stays protected.
        assertEquals(
            "message Daniel on WhatsApp that I am outside",
            norm("message daniel on what's up that i am outside")
        )
        // "what's up" inside the message body MUST remain "what's up".
        assertEquals(
            "text Daniel saying what's up",
            norm("text daniel saying what's up")
        )
        // Split homophone app opening grounds cleanly to installed app label.
        assertEquals("open OPay", norm("open oh pay"))
        // Phonetic contact grounding in call slot.
        assertEquals("call Tunde", norm("call tune day"))
    }

    @Test
    fun preservesOwnerShortcutsPackageSpellingPronounOneAndOrdinals() {
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
    fun formatsDictationWithSpokenPunctuationAndCapitalization() {
        val dictated = EmaInverseTextNormalizer.normalize(
            raw = "hello daniel comma i will arrive at six thirty p m period are you home question mark",
            profile = EmaRecognitionProfile.DICTATION,
            context = context
        )
        assertEquals("Hello daniel, I will arrive at 6:30 pm. Are you home?", dictated)
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
