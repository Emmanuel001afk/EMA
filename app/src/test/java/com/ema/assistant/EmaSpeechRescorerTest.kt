package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for EMA's contextual domain-aware N-best speech rescorer.
 */
class EmaSpeechRescorerTest {

    private val context = EmaSpeechContext(
        shortcuts = listOf("morning briefing", "pay rent"),
        ownerAliases = listOf("portal"),
        learnedPhrases = listOf("check office mail"),
        installedApps = listOf("WhatsApp", "Spotify", "OPay", "YouTube", "Phormi"),
        contactNames = listOf("Daniel", "Tunde", "Ada Lovelace", "Mummy")
    )

    @Test
    fun promotesValidAlarmCommandOverAcousticallySimilarNonsense() {
        val outcome = EmaSpeechRescorer.rescoreStrings(
            candidates = listOf(
                "set an arm for six dirty a m",
                "set an alarm for six thirty a m",
                "said an alarm four six thirty"
            ),
            confidenceScores = floatArrayOf(0.80f, 0.77f, 0.65f),
            context = context
        )
        assertEquals("set an alarm for 6:30 am", outcome.primary)
        // Raw runner-ups preserved for mishearing learning.
        assertTrue(outcome.rawHypotheses.contains("set an arm for six dirty a m"))
    }

    @Test
    fun promotesInstalledAppMatchOverHomophoneVerbMishearing() {
        val outcome = EmaSpeechRescorer.rescoreStrings(
            candidates = listOf("open obey", "open oh pay", "hop in obey"),
            confidenceScores = floatArrayOf(0.79f, 0.75f, 0.60f),
            context = context
        )
        assertEquals("open OPay", outcome.primary)
    }

    @Test
    fun promotesRealContactInCallSlotOverWeekdayMishearing() {
        val outcome = EmaSpeechRescorer.rescoreStrings(
            candidates = listOf("call tuesday", "call tunde"),
            confidenceScores = floatArrayOf(0.81f, 0.76f),
            context = context
        )
        assertEquals("call Tunde", outcome.primary)
    }

    @Test
    fun promotesOwnerShortcutAndConfirmedLearnedPhrases() {
        val shortcutOut = EmaSpeechRescorer.rescoreStrings(
            candidates = listOf("mourning breathing", "morning briefing"),
            confidenceScores = floatArrayOf(0.82f, 0.74f),
            context = context
        )
        assertEquals("morning briefing", shortcutOut.primary)

        val learnedOut = EmaSpeechRescorer.rescoreStrings(
            candidates = listOf("chuck office male", "check office mail"),
            confidenceScores = floatArrayOf(0.80f, 0.73f),
            context = context
        )
        assertEquals("check office mail", learnedOut.primary)
    }

    @Test
    fun promotesSystemDeviceCommandsAndOfflineCalculations() {
        val sysOut = EmaSpeechRescorer.rescoreStrings(
            candidates = listOf("torn on flash lid", "turn on flashlight"),
            confidenceScores = floatArrayOf(0.78f, 0.74f),
            context = context
        )
        assertEquals("turn on flashlight", sysOut.primary)

        val mathOut = EmaSpeechRescorer.rescoreStrings(
            candidates = listOf("what his fifteen percent off two hundred", "what is fifteen percent of two hundred"),
            confidenceScores = floatArrayOf(0.78f, 0.74f),
            context = context
        )
        assertEquals("what is 15% of 200", mathOut.primary)
    }

    @Test
    fun handlesEmptyOrBlankInputSafely() {
        val empty = EmaSpeechRescorer.rescoreStrings(emptyList(), null, context)
        assertNull(empty.primary)
        assertTrue(empty.hypotheses.isEmpty())
        assertEquals(0, empty.confidenceScores.size)

        val blanks = EmaSpeechRescorer.rescoreStrings(listOf("  ", ""), null, context)
        assertNull(blanks.primary)
        assertNull(EmaSpeechRescorer.chooseBest(emptyList(), null, context))
    }
}
