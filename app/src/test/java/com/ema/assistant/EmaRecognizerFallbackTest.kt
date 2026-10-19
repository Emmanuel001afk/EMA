package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The on-device recogniser fails in ways the phone never explains: the
 * language is not available, the model is not supported, the service returns
 * a server or network error. EMA retries the same listening session once with
 * another installed recogniser.
 */
class EmaRecognizerFallbackTest {

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
