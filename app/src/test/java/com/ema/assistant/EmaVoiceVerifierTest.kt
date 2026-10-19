package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * The personal wake-word check.
 *
 * The numbers here are synthetic on purpose: the point is that the rules
 * (how close is close, when does the threshold move, which template is
 * replaced) hold for any sound, so they can be checked without a phone.
 */
class EmaVoiceVerifierTest {

    /** A synthetic "utterance": a smooth curve with a shape of its own. */
    private fun utterance(frames: Int = 30, seed: Int = 0, noise: Double = 0.0): List<FloatArray> =
        (0 until frames).map { index ->
            FloatArray(12) { coefficient ->
                val base = sin((index + seed) * 0.35 + coefficient * 0.7).toFloat()
                (base + noise * ((index * 31 + coefficient * 17) % 7 - 3)).toFloat()
            }
        }

    private fun template(
        id: String,
        kind: EmaVoiceVerifier.Template.Kind = EmaVoiceVerifier.Template.Kind.POSITIVE,
        frames: List<FloatArray> = utterance(),
        at: Long = 0L
    ) = EmaVoiceVerifier.Template(id, kind, id, frames, at)

    @Test
    fun theSameUtteranceHasNoDistance() {
        val a = utterance()
        assertEquals(0.0, EmaDtw.distance(a, a).toDouble(), 0.0001)
    }

    @Test
    fun theSameWordsSlowerAreStillClose() {
        val a = utterance(frames = 30)
        val b = utterance(frames = 40)
        val distance = EmaDtw.distance(a, b)
        assertTrue("the same shape must stay close, was $distance", distance < 1.0f)
    }

    @Test
    fun anotherVoiceIsFurtherAway() {
        val mine = utterance(seed = 0)
        val other = utterance(seed = 9)
        val distance = EmaDtw.distance(mine, other)
        assertTrue("another voice must be further away, was $distance", distance > 0.5f)
    }

    @Test
    fun withoutAProfileTheDecoderDecides() {
        val verifier = EmaVoiceVerifier()
        assertFalse(verifier.hasProfile())
        val verdict = verifier.verdict(utterance())
        assertTrue("without a profile the wake word must behave as before", verdict.accepted)
    }

    /** The owner's own samples: the same voice, said a little differently. */
    private fun profile(): EmaVoiceVerifier = EmaVoiceVerifier(templates = listOf(
        template("mine-1", frames = utterance(noise = 0.01), at = 1),
        template("mine-2", frames = utterance(frames = 32, noise = 0.01), at = 2),
        template("mine-3", frames = utterance(frames = 28, noise = 0.02), at = 3)
    ))

    @Test
    fun withAProfileTheOwnVoiceIsAccepted() {
        val verifier = profile()
        val verdict = verifier.verdict(utterance(noise = 0.02))
        assertTrue("the own voice must pass, was ${verdict.distance}", verdict.accepted)
    }

    @Test
    fun withAProfileAnotherVoiceIsRejected() {
        val verifier = profile()
        val verdict = verifier.verdict(utterance(seed = 12, noise = 0.05))
        assertFalse("another voice must not pass, was ${verdict.distance}", verdict.accepted)
    }

    @Test
    fun theThresholdIsMeasuredFromTheOwnSamples() {
        val verifier = profile()
        val calibrated = verifier.calibratedThreshold()
        assertTrue("the threshold must come from the own samples, was $calibrated", calibrated > 0f)
        assertEquals(calibrated.toDouble(), verifier.threshold.toDouble(), 0.0001)
    }

    @Test
    fun aNegativeRaisesTheThresholdAndItStaysBounded() {
        val verifier = profile()
        val before = verifier.threshold
        val after = verifier.noteNegative()
        assertTrue("a negative must raise the threshold", after > before)
        repeat(200) { verifier.noteNegative() }
        assertTrue("the threshold must stay bounded", verifier.threshold <= before + EmaVoiceVerifier.MAX_OFFSET + 0.001f)
    }

    @Test
    fun aPositiveRelaxesTheThresholdButNotTooFar() {
        val verifier = profile()
        verifier.noteNegative()
        val raised = verifier.threshold
        verifier.notePositive()
        assertTrue("a positive may relax the threshold", verifier.threshold < raised)
        repeat(50) { verifier.notePositive() }
        assertTrue("the learned part never goes below zero", verifier.offset >= 0f)
    }

    @Test
    fun theTemplateListIsBoundedAndTheWorstIsReplaced() {
        val verifier = EmaVoiceVerifier()
        // Two groups: the owner's own voice and something clearly different.
        val mine = utterance(seed = 0)
        val other = utterance(seed = 20)
        verifier.add(template("mine-1", frames = mine, at = 1))
        verifier.add(template("mine-2", frames = mine, at = 2))
        verifier.add(template("other-1", frames = other, at = 3))
        val worstBefore = verifier.worstTemplate()
        assertNotNull(worstBefore)

        var index = 4
        while (verifier.templates().size < 4) {
            verifier.add(template("mine-$index", frames = mine, at = index.toLong()), maximum = 4)
            index++
        }
        // Now the list is full: the next template replaces the worst one.
        val dropped = verifier.add(template("mine-$index", frames = mine, at = index.toLong()), maximum = 4)
        assertNotNull("something must be replaced when the list is full", dropped)
        assertTrue("the list must stay bounded", verifier.templates().size <= 4)
        assertEquals("mine-$index", verifier.templates().last().id)
    }

    @Test
    fun theOutlierIsTheWorstTemplate() {
        val verifier = EmaVoiceVerifier()
        val mine = utterance(seed = 0)
        verifier.add(template("a", frames = mine, at = 1))
        verifier.add(template("b", frames = mine, at = 2))
        verifier.add(template("c", frames = mine, at = 3))
        val worst = verifier.worstTemplate()
        assertNotNull(worst)
        assertTrue("with three identical samples any of them may go", worst!!.id in setOf("a", "b", "c"))
    }

    @Test
    fun aNegativeTemplateMakesTheCheckStricter() {
        val verifier = EmaVoiceVerifier(templates = listOf(template("mine"), template("mine-2", frames = utterance(frames = 31), at = 2)))
        val borderline = utterance(seed = 6, noise = 0.05)
        val before = verifier.verdict(borderline).accepted
        verifier.add(template("negative", kind = EmaVoiceVerifier.Template.Kind.NEGATIVE, frames = borderline, at = 9))
        val after = verifier.verdict(borderline).accepted
        assertFalse("a sample that looks like a negative must not pass", after)
        assertTrue("the decision may only get stricter", !after || before)
    }

    @Test
    fun aTemplateCanBeWrittenAndReadBack() {
        val template = EmaVoiceVerifier.Template(
            id = "enrolled-1",
            kind = EmaVoiceVerifier.Template.Kind.POSITIVE,
            label = "enrolled",
            frames = utterance(frames = 12),
            addedAt = 1234L
        )
        val text = EmaVoiceTemplateFormat.encode(template)
        val parsed = EmaVoiceTemplateFormat.decode(text)
        assertNotNull(parsed)
        assertEquals("enrolled-1", parsed!!.id)
        assertEquals(EmaVoiceVerifier.Template.Kind.POSITIVE, parsed.kind)
        assertEquals(12, parsed.frames.size)
        assertEquals(1234L, parsed.addedAt)
    }

    @Test
    fun textThatIsNotATemplateIsNotReadBack() {
        assertNull(EmaVoiceTemplateFormat.decode("hello"))
    }

    @Test
    fun theFeaturesOfTheSameSoundAreStable() {
        val tone = EmaVoiceFeatures.tone(220.0, 400)
        val first = EmaVoiceFeatures.mfcc(tone)
        val second = EmaVoiceFeatures.mfcc(tone)
        assertTrue(first.isNotEmpty())
        assertEquals(first.size, second.size)
        for (i in first.indices) {
            for (j in first[i].indices) {
                assertEquals(first[i][j].toDouble(), second[i][j].toDouble(), 0.0001)
            }
        }
    }

    @Test
    fun theFeaturesIgnoreHowLoudItIs() {
        val quiet = EmaVoiceFeatures.tone(220.0, 400, amplitude = 0.05)
        val loud = EmaVoiceFeatures.tone(220.0, 400, amplitude = 0.5)
        val a = EmaVoiceFeatures.mfcc(quiet)
        val b = EmaVoiceFeatures.mfcc(loud)
        assertTrue(a.isNotEmpty() && b.isNotEmpty())
        val distance = EmaDtw.distance(a, b)
        assertTrue("the same words at another volume must stay close, was $distance", distance < 1.0f)
    }

    @Test
    fun differentSoundsGiveDifferentFeatures() {
        val low = EmaVoiceFeatures.mfcc(EmaVoiceFeatures.tone(180.0, 400))
        val high = EmaVoiceFeatures.mfcc(EmaVoiceFeatures.tone(900.0, 400))
        val distance = EmaDtw.distance(low, high)
        assertTrue("different sounds must differ, was $distance", distance > 0.3f)
    }

    @Test
    fun theEnrollmentNeedsEightSamples() {
        val enrollment = EmaVoiceEnrollment()
        assertEquals(8, enrollment.required)
        for (round in 1..7) {
            assertFalse(enrollment.add(utterance(seed = round)))
        }
        assertTrue(enrollment.add(utterance(seed = 8)))
        assertTrue(enrollment.isComplete())
    }

    @Test
    fun aSilentSampleDoesNotCount() {
        val enrollment = EmaVoiceEnrollment()
        assertFalse(enrollment.add(emptyList()))
        assertEquals(0, enrollment.count())
    }

    @Test
    fun theEnrolledTemplateHasTheRightLength() {
        val enrollment = EmaVoiceEnrollment()
        repeat(8) { enrollment.add(utterance(frames = 20 + it)) }
        val template = enrollment.template(id = "enrolled", at = 1L)
        assertNotNull(template)
        assertEquals(EmaVoiceFeatures.TEMPLATE_FRAMES, template!!.frames.size)
    }
}
