package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for EMA's contextual & MBR/ROVER N-best speech rescorer,
 * verified owner mishearing corrections, screen-label scoring and profile-aware
 * confirmation/search ranking.
 */
class EmaSpeechRescorerTest {

    private val context = EmaSpeechContext(
        shortcuts = listOf("morning briefing", "pay rent"),
        ownerAliases = listOf("portal"),
        learnedPhrases = listOf("check office mail"),
        installedApps = listOf("WhatsApp", "Spotify", "OPay", "YouTube", "Phormi"),
        contactNames = listOf("Daniel", "Tunde Okafor", "Ada Lovelace", "Mummy"),
        learnedReplacements = mapOf("open four me" to "open Phormi"),
        screenLabels = listOf("Confirm Transfer", "Download Receipt")
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
        assertTrue(outcome.isHighConfidence)
        assertTrue(outcome.marginOverRunnerUp > 0.05f)
        assertTrue(outcome.rawHypotheses.contains("set an arm for six dirty a m"))
    }

    @Test
    fun appliesVerifiedOwnerLearnedReplacementAndNeverAppliesItInDictation() {
        val cmdOut = EmaSpeechRescorer.rescoreStrings(
            candidates = listOf("open four me", "hop in four me"),
            confidenceScores = floatArrayOf(0.76f, 0.61f),
            context = context,
            profile = EmaRecognitionProfile.COMMAND
        )
        assertEquals("open Phormi", cmdOut.primary)
        assertTrue("Original acoustic transcript retained for trace", cmdOut.rawHypotheses.contains("open four me"))

        val dictOut = EmaSpeechRescorer.rescoreStrings(
            candidates = listOf("open four me"),
            confidenceScores = floatArrayOf(0.80f),
            context = context,
            profile = EmaRecognitionProfile.DICTATION
        )
        assertEquals("Open four me", dictOut.primary)
    }

    @Test
    fun promotesInstalledAppContactAndScreenLabelSlotsAccurately() {
        val appOut = EmaSpeechRescorer.rescoreStrings(
            candidates = listOf("open obey", "open oh pay", "hop in obey"),
            confidenceScores = floatArrayOf(0.79f, 0.75f, 0.60f),
            context = context
        )
        assertEquals("open OPay", appOut.primary)

        val contactOut = EmaSpeechRescorer.rescoreStrings(
            candidates = listOf("call tuesday", "call tunde"),
            confidenceScores = floatArrayOf(0.81f, 0.76f),
            context = context
        )
        assertEquals("call Tunde", contactOut.primary)

        val screenOut = EmaSpeechRescorer.rescoreStrings(
            candidates = listOf("tap conform transit", "tap confirm transfer"),
            confidenceScores = floatArrayOf(0.79f, 0.73f),
            context = context
        )
        assertEquals("tap Confirm Transfer", screenOut.primary)
    }

    @Test
    fun confirmationProfileRescuesYesNoCancelAndSimReplies() {
        val yesOut = EmaSpeechRescorer.rescoreStrings(
            candidates = listOf("go head", "go ahead"),
            confidenceScores = floatArrayOf(0.78f, 0.72f),
            context = context,
            profile = EmaRecognitionProfile.CONFIRMATION
        )
        assertEquals("go ahead", yesOut.primary)

        val cancelOut = EmaSpeechRescorer.rescoreStrings(
            candidates = listOf("council", "cancel"),
            confidenceScores = floatArrayOf(0.79f, 0.71f),
            context = context,
            profile = EmaRecognitionProfile.CONFIRMATION
        )
        assertEquals("cancel", cancelOut.primary)
    }

    @Test
    fun promotesOwnerShortcutLearnedPhrasesDeviceCommandsAndCalculations() {
        assertEquals(
            "morning briefing",
            EmaSpeechRescorer.rescoreStrings(
                listOf("mourning breathing", "morning briefing"),
                floatArrayOf(0.82f, 0.74f),
                context
            ).primary
        )
        assertEquals(
            "check office mail",
            EmaSpeechRescorer.rescoreStrings(
                listOf("chuck office male", "check office mail"),
                floatArrayOf(0.80f, 0.73f),
                context
            ).primary
        )
        assertEquals(
            "turn on flashlight",
            EmaSpeechRescorer.rescoreStrings(
                listOf("torn on flash lid", "turn on flashlight"),
                floatArrayOf(0.78f, 0.74f),
                context
            ).primary
        )
        assertEquals(
            "what is 15% of 200",
            EmaSpeechRescorer.rescoreStrings(
                listOf("what his fifteen percent off two hundred", "what is fifteen percent of two hundred"),
                floatArrayOf(0.78f, 0.74f),
                context
            ).primary
        )
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
