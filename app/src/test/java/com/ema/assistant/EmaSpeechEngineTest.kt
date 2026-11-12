package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The microphone is not the online part; the recogniser is. These rules decide
 * whether an Android (usually Google) recogniser is allowed to see the audio at
 * all, because "offline" has to mean offline, not "offline unless the local
 * model is busy".
 */
class EmaSpeechEngineTest {

    @Test
    fun localOnlyStartsOnTheBundledModel() {
        assertEquals(
            listOf(EmaRecognizerKind.VOSK_OFFLINE),
            EmaSpeechEngine.LOCAL_ONLY.startOrder(preferred = null)
        )
    }

    @Test
    fun localOnlyNeverReachesASystemRecognizer() {
        // A remembered EXTERNAL/ON_DEVICE preference must not move the start order.
        assertEquals(
            listOf(EmaRecognizerKind.VOSK_OFFLINE),
            EmaSpeechEngine.LOCAL_ONLY.startOrder(preferred = EmaRecognizerKind.EXTERNAL)
        )
        assertNull(EmaSpeechEngine.LOCAL_ONLY.nextAfterFailure(EmaRecognizerKind.VOSK_OFFLINE))
    }

    @Test
    fun localOnlyHasNoSystemFallbackWhenTheModelFails() {
        // End the session honestly; never quietly substitute Google for the
        // engine the owner selected.
        for (kind in EmaRecognizerKind.entries) {
            val next = EmaSpeechEngine.LOCAL_ONLY.nextAfterFailure(kind)
            assertTrue(
                "local-only must not retry onto $next",
                next == null || next == EmaRecognizerKind.VOSK_OFFLINE
            )
        }
    }

    @Test
    fun systemModeNeverLoadsTheLocalModel() {
        assertFalse(EmaSpeechEngine.SYSTEM.usesLocalModel)
        assertTrue(EmaSpeechEngine.SYSTEM.usesSystemRecognizer)
        assertFalse(EmaSpeechEngine.SYSTEM.startOrder(null).contains(EmaRecognizerKind.VOSK_OFFLINE))
        assertNull(EmaSpeechEngine.SYSTEM.nextAfterFailure(EmaRecognizerKind.EXTERNAL))
    }

    @Test
    fun fallbackModeKeepsTheLocalModelLast() {
        assertEquals(
            listOf(
                EmaRecognizerKind.ON_DEVICE,
                EmaRecognizerKind.EXTERNAL,
                EmaRecognizerKind.VOSK_OFFLINE
            ),
            EmaSpeechEngine.SYSTEM_WITH_LOCAL_FALLBACK.startOrder(null)
        )
        assertEquals(
            EmaRecognizerKind.VOSK_OFFLINE,
            EmaSpeechEngine.SYSTEM_WITH_LOCAL_FALLBACK.nextAfterFailure(EmaRecognizerKind.EXTERNAL)
        )
    }

    @Test
    fun theKindThatWorkedStillGoesFirst() {
        assertEquals(
            listOf(EmaRecognizerKind.EXTERNAL, EmaRecognizerKind.ON_DEVICE, EmaRecognizerKind.VOSK_OFFLINE),
            EmaSpeechEngine.SYSTEM_WITH_LOCAL_FALLBACK.startOrder(EmaRecognizerKind.EXTERNAL)
        )
        assertEquals(
            listOf(EmaRecognizerKind.VOSK_OFFLINE, EmaRecognizerKind.ON_DEVICE, EmaRecognizerKind.EXTERNAL),
            EmaSpeechEngine.SYSTEM_WITH_LOCAL_FALLBACK.startOrder(EmaRecognizerKind.VOSK_OFFLINE)
        )
    }

    @Test
    fun anOlderInstallKeepsItsOldBehaviour() {
        // The previous release had a single "local fallback" switch. Turning it
        // on must not silently become local-only.
        assertEquals(EmaSpeechEngine.SYSTEM, EmaSpeechEngine.parse(null, legacyLocalFallback = false))
        assertEquals(
            EmaSpeechEngine.SYSTEM_WITH_LOCAL_FALLBACK,
            EmaSpeechEngine.parse(null, legacyLocalFallback = true)
        )
    }

    @Test
    fun theStoredChoiceWinsOverTheLegacySwitch() {
        assertEquals(
            EmaSpeechEngine.LOCAL_ONLY,
            EmaSpeechEngine.parse(EmaSpeechEngine.LOCAL_ONLY.name, legacyLocalFallback = false)
        )
        assertEquals(
            EmaSpeechEngine.SYSTEM,
            EmaSpeechEngine.parse("SYSTEM", legacyLocalFallback = true)
        )
        // Garbage in preferences must not break listening.
        assertEquals(
            EmaSpeechEngine.SYSTEM_WITH_LOCAL_FALLBACK,
            EmaSpeechEngine.parse("not-a-mode", legacyLocalFallback = true)
        )
    }
}
