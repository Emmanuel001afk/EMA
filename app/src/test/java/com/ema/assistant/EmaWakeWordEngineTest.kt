package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaWakeWordEngineTest {
    private val engine = EmaWakeWordEngine()

    @Test fun rejectsAudioOutsideSupportedSampleRateAndDuration() {
        assertNull(engine.enroll(ShortArray(8_000), 44_100))
        assertNull(engine.enroll(ShortArray(1_000)))
        assertNull(engine.enroll(ShortArray(80_000)))
    }

    @Test fun enrollmentProducesTemplateForValidAudio() {
        val pcm = syntheticVoiceLikeSignal(16_000)
        val template = engine.enroll(pcm)
        assertNotNull(template)
        assertTrue(template!!.frameCount >= 20)
    }

    @Test fun sameWindowScoresAsMatchAtItsMeasuredDistance() {
        val pcm = syntheticVoiceLikeSignal(16_000)
        val template = engine.enroll(pcm)!!
        val measured = engine.compare(pcm, template, threshold = 100f)
        assertTrue(measured.distance.isFinite())
        assertTrue(measured.matched)
    }

    @Test fun invalidThresholdDoesNotMatch() {
        val pcm = syntheticVoiceLikeSignal(16_000)
        val template = engine.enroll(pcm)!!
        assertFalse(engine.compare(pcm, template, threshold = 0f).matched)
    }

    private fun syntheticVoiceLikeSignal(sampleRate: Int): ShortArray =
        ShortArray(sampleRate) { i ->
            val t = i.toDouble() / sampleRate
            val envelope = (0.3 + 0.7 * kotlin.math.abs(kotlin.math.sin(2 * Math.PI * 3 * t)))
            val signal = envelope * (
                5_000 * kotlin.math.sin(2 * Math.PI * 180 * t) +
                2_000 * kotlin.math.sin(2 * Math.PI * 730 * t) +
                1_000 * kotlin.math.sin(2 * Math.PI * 1_650 * t)
            )
            signal.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
}
