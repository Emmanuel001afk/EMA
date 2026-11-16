package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for EMA's speech mode selection, offline-first / strict-offline
 * / system-first recognizer plans, connectivity awareness and error recovery.
 */
class EmaRecognizerFallbackTest {

    private val allBackends = setOf(
        EmaRecognizerKind.EMA_OFFLINE,
        EmaRecognizerKind.ON_DEVICE,
        EmaRecognizerKind.EXTERNAL
    )

    @Test
    fun offlineFirstPrioritizesEmaEngineThenOnDeviceThenExternal() {
        val plan = EmaRecognizerFallback.plan(
            mode = EmaSpeechMode.EMA_OFFLINE_FIRST,
            available = allBackends,
            networkAvailable = true
        )
        assertEquals(
            listOf(EmaRecognizerKind.EMA_OFFLINE, EmaRecognizerKind.ON_DEVICE, EmaRecognizerKind.EXTERNAL),
            plan
        )
    }

    @Test
    fun worksStandaloneOnPhonesWithZeroExternalSpeechServices() {
        val plan = EmaRecognizerFallback.plan(
            mode = EmaSpeechMode.EMA_OFFLINE_FIRST,
            available = setOf(EmaRecognizerKind.EMA_OFFLINE),
            networkAvailable = false
        )
        assertEquals(listOf(EmaRecognizerKind.EMA_OFFLINE), plan)
    }

    @Test
    fun offlineOnlyModeNeverIncludesExternalOrPlatformServices() {
        val plan = EmaRecognizerFallback.plan(
            mode = EmaSpeechMode.EMA_OFFLINE_ONLY,
            available = allBackends,
            networkAvailable = true
        )
        assertEquals(listOf(EmaRecognizerKind.EMA_OFFLINE), plan)
        assertTrue(
            EmaRecognizerFallback.plan(
                mode = EmaSpeechMode.EMA_OFFLINE_ONLY,
                available = setOf(EmaRecognizerKind.ON_DEVICE, EmaRecognizerKind.EXTERNAL),
                networkAvailable = true
            ).isEmpty()
        )
    }

    @Test
    fun systemFirstPromotesEmaOfflineImmediatelyWhenNetworkIsDown() {
        val offline = EmaRecognizerFallback.plan(
            mode = EmaSpeechMode.SYSTEM_FIRST,
            available = allBackends,
            networkAvailable = false
        )
        assertEquals(
            listOf(EmaRecognizerKind.EMA_OFFLINE, EmaRecognizerKind.ON_DEVICE, EmaRecognizerKind.EXTERNAL),
            offline
        )

        val online = EmaRecognizerFallback.plan(
            mode = EmaSpeechMode.SYSTEM_FIRST,
            available = allBackends,
            networkAvailable = true
        )
        assertEquals(
            listOf(EmaRecognizerKind.ON_DEVICE, EmaRecognizerKind.EXTERNAL, EmaRecognizerKind.EMA_OFFLINE),
            online
        )
    }

    @Test
    fun classifiesRecognizerErrorsAndLanguageSupportAccurately() {
        assertTrue(EmaRecognizerFallback.shouldFallback(EmaRecognizerFallback.ERROR_NETWORK))
        assertTrue(EmaRecognizerFallback.shouldFallback(EmaRecognizerFallback.ERROR_SERVER))
        assertTrue(EmaRecognizerFallback.shouldFallback(EmaRecognizerFallback.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertFalse(EmaRecognizerFallback.shouldFallback(EmaRecognizerFallback.ERROR_NO_MATCH))
        assertFalse(EmaRecognizerFallback.shouldFallback(EmaRecognizerFallback.ERROR_SPEECH_TIMEOUT))
        assertFalse(EmaRecognizerFallback.shouldFallback(EmaRecognizerFallback.ERROR_INSUFFICIENT_PERMISSIONS))

        assertTrue(EmaRecognizerFallback.shouldRetrySameBackend(EmaRecognizerFallback.ERROR_AUDIO, alreadyRetriedAudio = false))
        assertFalse(EmaRecognizerFallback.shouldRetrySameBackend(EmaRecognizerFallback.ERROR_AUDIO, alreadyRetriedAudio = true))

        assertTrue(EmaSpeechMode.supportsOfflineLanguage(null, defaultLanguageTag = "en-US"))
        assertTrue(EmaSpeechMode.supportsOfflineLanguage("en-US"))
        assertTrue(EmaSpeechMode.supportsOfflineLanguage("en-NG"))
        assertTrue(EmaSpeechMode.supportsOfflineLanguage("en_GB"))
        assertFalse(EmaSpeechMode.supportsOfflineLanguage("fr-FR"))
        assertEquals(EmaRecognizerKind.EMA_OFFLINE, EmaRecognizerKind.fromStored("VOSK_OFFLINE"))
    }

    // --------------------------------------------------- legacy compatibility

    @Test
    fun theOnDeviceRecogniserFallsBackToAnExternalOne() {
        assertEquals(
            EmaRecognizerKind.EXTERNAL,
            EmaRecognizerFallback.next(EmaRecognizerKind.ON_DEVICE, alreadyRetried = false)
        )
    }

    @Test
    fun aSessionRetriesOnlyOnce() {
        assertNull(EmaRecognizerFallback.next(EmaRecognizerKind.ON_DEVICE, alreadyRetried = true))
        assertNull(EmaRecognizerFallback.next(EmaRecognizerKind.EXTERNAL, alreadyRetried = true))
    }

    @Test
    fun anExternalRecogniserThatFailedEndsTheSession() {
        assertNull(EmaRecognizerFallback.next(EmaRecognizerKind.EXTERNAL, alreadyRetried = false))
    }
}
