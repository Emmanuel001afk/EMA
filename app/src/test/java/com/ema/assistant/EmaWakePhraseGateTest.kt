package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaWakePhraseGateTest {
    @Test fun inlineWakeReturnsOneWholeRequest() {
        val gate = EmaWakePhraseGate()
        assertEquals("open Spotify play Arcade", gate.accept("Hey EMA, open Spotify play Arcade", true, 100))
        assertNull(gate.accept("send another message", true, 200))
    }
    @Test fun standaloneWakeArmsOnlyOneRequest() {
        val gate = EmaWakePhraseGate()
        assertEquals("", gate.accept("hey ema", true, 100))
        assertEquals("open WhatsApp", gate.accept("open WhatsApp", true, 200))
        assertNull(gate.accept("open YouTube", true, 300))
    }
    @Test fun wakeExpiresAndDoesNotMatchInsideSpeech() {
        val gate = EmaWakePhraseGate()
        gate.accept("hey ema", true, 100)
        assertNull(gate.accept("send a message", true, 10_100))
        assertNull(gate.accept("someone said hey ema", true, 10_200))
        assertNull(gate.accept("hey emailed", true, 10_300))
    }
    @Test fun disabledGateAndResetDoNotLeaveAnArmedRequest() {
        val gate = EmaWakePhraseGate()
        gate.accept("hey ema", true, 100)
        gate.reset()
        assertNull(gate.accept("open Spotify", true, 200))
        assertEquals("open Spotify", gate.accept("open Spotify", false, 300))
    }
}
