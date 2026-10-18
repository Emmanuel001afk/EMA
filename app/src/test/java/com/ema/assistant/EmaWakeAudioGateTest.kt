package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaWakeAudioGateTest {
    private fun frame(amplitude: Int) = ByteArray(1600).also { out ->
        for (i in out.indices step 2) {
            out[i] = amplitude.toByte()
            out[i + 1] = (amplitude shr 8).toByte()
        }
    }

    @Test fun silenceDoesNotReachDecoder() {
        val gate = EmaWakeAudioGate()
        repeat(1000) { assertTrue(gate.accept(frame(0), 1600).audio.isEmpty()) }
    }

    @Test fun voiceIncludesBoundedPreRoll() {
        val gate = EmaWakeAudioGate()
        repeat(20) { gate.accept(frame(0), 1600) }
        val voiced = gate.accept(frame(1000), 1600)
        assertEquals(7, voiced.audio.size)
        assertFalse(voiced.resetDecoderAfter)
    }

    @Test fun trailingSilenceReachesDecoderThenStops() {
        val gate = EmaWakeAudioGate()
        gate.accept(frame(1000), 1600)
        repeat(23) { assertFalse(gate.accept(frame(0), 1600).resetDecoderAfter) }
        val end = gate.accept(frame(0), 1600)
        assertTrue(end.resetDecoderAfter)
        assertEquals(1, end.audio.size)
        assertTrue(gate.accept(frame(0), 1600).audio.isEmpty())
    }

    @Test fun negativePcmAndContinuousNoiseAreBounded() {
        val gate = EmaWakeAudioGate()
        assertFalse(gate.accept(frame(-2000), 1600).audio.isEmpty())
        var resets = 0
        var deliveredInLastFiveSeconds = 0
        repeat(400) { i ->
            val out = gate.accept(frame(2000), 1600)
            if (out.resetDecoderAfter) resets++
            if (i >= 300) deliveredInLastFiveSeconds += out.audio.size
        }
        // Steady noise must not keep the decoder running: the window is closed
        // at least once and, once the level is learned as background, no more
        // audio is decoded at all (this was the battery/[unk] root cause).
        assertTrue(resets >= 1)
        assertEquals(0, deliveredInLastFiveSeconds)
        assertTrue(gate.backgroundLevel > 2000 / 3.0)
    }

    @Test fun speechAboveLearnedNoiseStillOpensGate() {
        val gate = EmaWakeAudioGate()
        repeat(400) { gate.accept(frame(2000), 1600) }
        assertTrue(gate.accept(frame(2000), 1600).audio.isEmpty())
        assertFalse(gate.accept(frame(9000), 1600).audio.isEmpty())
    }

    @Test fun shortSpeechDoesNotRaiseBackground() {
        val gate = EmaWakeAudioGate()
        repeat(20) { gate.accept(frame(0), 1600) }
        val before = gate.backgroundLevel
        repeat(40) { gate.accept(frame(3000), 1600) } // two seconds of speech
        assertEquals(before, gate.backgroundLevel, 0.0001)
    }

    @Test fun invalidPcmIsRejected() {
        try {
            EmaWakeAudioGate().accept(ByteArray(3), 3)
            fail("Incomplete PCM16 sample accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
