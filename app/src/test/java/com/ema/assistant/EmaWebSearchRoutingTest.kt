package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaWebSearchRoutingTest {
    private val apps = listOf(EmaAppResolver.App("YouTube", "com.google.android.youtube"), EmaAppResolver.App("Spotify", "com.spotify.music"))
    @Test fun naturalSearchDefaultsToEmaAndExplicitChoicesOverride() {
        assertEquals(EmaWebSearchRouting.Provider.EMA, EmaWebSearchRouting.Provider.fromKey(null))
        assertEquals(EmaWebSearchRouting.Request("weather in Lagos"), EmaWebSearchRouting.parse("search on weather in Lagos", apps))
        assertEquals(EmaWebSearchRouting.Request("weather in Lagos"), EmaWebSearchRouting.parse("search for weather in Lagos", apps))
        assertEquals(EmaWebSearchRouting.Request("weather in Lagos", EmaWebSearchRouting.Provider.GOOGLE), EmaWebSearchRouting.parse("search google for weather in Lagos", apps))
        assertEquals(EmaWebSearchRouting.Request("weather in Lagos", EmaWebSearchRouting.Provider.GOOGLE), EmaWebSearchRouting.parse("search for weather in Lagos on google", apps))
        assertEquals(EmaWebSearchRouting.Request("weather in Lagos", EmaWebSearchRouting.Provider.BOTH), EmaWebSearchRouting.parse("search both for weather in Lagos", apps))
        assertEquals(EmaWebSearchRouting.Request("weather in Lagos", EmaWebSearchRouting.Provider.EMA, true), EmaWebSearchRouting.parse("search searxng for weather in Lagos", apps))
    }
    @Test fun appActionsAndMessageBodiesAreNotConvertedToWebSearch() {
        assertNull(EmaWebSearchRouting.parse("search YouTube for music", apps))
        assertNull(EmaWebSearchRouting.parse("search for music on Spotify", apps))
        assertNull(EmaWebSearchRouting.parse("message Ada saying search for weather", apps))
        assertNull(EmaWebSearchRouting.parse("search for music and then call Ada", apps))
    }
}
