package com.ema.assistant

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class EmaOfflineModelRecoveryTest {
    @Test fun healthyLoadDoesNotReinstall() {
        var repairs = 0
        assertEquals("ready", EmaOfflineModelRecovery.load({ "ready" }, { repairs++ }))
        assertEquals(0, repairs)
    }

    @Test fun unreadableInstalledModelIsRepairedFromLocalAssetsOnce() {
        var loads = 0
        var repairs = 0
        val result = EmaOfflineModelRecovery.load({
            loads++
            if (loads == 1) throw IOException("unreadable model")
            "ready"
        }, { repairs++ })
        assertEquals("ready", result)
        assertEquals(2, loads)
        assertEquals(1, repairs)
    }

    @Test fun persistentFailureDoesNotLoopAndRetainsFirstCause() {
        var loads = 0
        var repairs = 0
        try {
            EmaOfflineModelRecovery.load({ loads++; throw IOException("failure $loads") }, { repairs++ })
            fail("Expected failure")
        } catch (error: IOException) {
            assertEquals(2, loads)
            assertEquals(1, repairs)
            assertEquals("failure 1", error.suppressed.single().message)
        }
    }

    @Test fun memoryAndPermissionFailuresAreNotTreatedAsCorruptModels() {
        var repairs = 0
        try {
            EmaOfflineModelRecovery.load({ throw OutOfMemoryError("memory") }, { repairs++ })
            fail("Expected memory failure")
        } catch (_: OutOfMemoryError) { }
        try {
            EmaOfflineModelRecovery.load({ throw SecurityException("permission") }, { repairs++ })
            fail("Expected permission failure")
        } catch (_: SecurityException) { }
        assertEquals(0, repairs)
    }

    @Test fun failedRepairDoesNotAttemptAnotherLoad() {
        var loads = 0
        try {
            EmaOfflineModelRecovery.load({ loads++; throw IOException("model") }, { throw IOException("storage full") })
            fail("Expected storage failure")
        } catch (error: IOException) {
            assertEquals(1, loads)
            assertEquals("storage full", error.message)
        }
    }

    @Test fun defaultModeCannotFallBackToCloudSpeech() {
        val mode = EmaSpeechMode.fromKey(null)
        assertEquals(EmaSpeechMode.EMA_OFFLINE_ONLY, mode)
        assertEquals(mode.key, EmaSpeechMode.DEFAULT_KEY)
        assertFalse(mode.allowsSystemRecognizers)
        assertEquals(listOf(EmaRecognizerKind.EMA_OFFLINE), EmaRecognizerFallback.plan(mode, EmaRecognizerKind.values().toSet()))
        assertTrue(EmaRecognizerFallback.plan(mode, setOf(EmaRecognizerKind.EXTERNAL)).isEmpty())
        // An owner's explicitly saved choice must not be silently rewritten.
        assertEquals(EmaSpeechMode.SYSTEM_FIRST, EmaSpeechMode.fromKey("system_first"))
        assertEquals(EmaSpeechMode.EMA_OFFLINE_FIRST, EmaSpeechMode.fromKey("ema_offline_first"))
    }
}
