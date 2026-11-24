package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class EmaWeatherTest {
    private val places = """{"results":[{"name":"Lagos","admin1":"Lagos","country":"Nigeria","country_code":"NG","latitude":6.45,"longitude":3.39},{"name":"Lagos","admin1":"Faro","country":"Portugal","country_code":"PT","latitude":37.1,"longitude":-8.67}]}"""
    private val current = """{"timezone":"Africa/Lagos","current_units":{"temperature_2m":"°C","apparent_temperature":"°C","relative_humidity_2m":"%","wind_speed_10m":"km/h"},"current":{"time":"2026-11-23T12:00","temperature_2m":29.5,"apparent_temperature":32.2,"relative_humidity_2m":80,"weather_code":3,"wind_speed_10m":12.4}}"""

    @Test fun weatherIntentIsBoundedAndDoesNotConfuseForecastWithNow() {
        assertEquals(EmaWeather.Request.Current("Lagos", null), EmaWeather.parse("what's the weather in Lagos?"))
        assertEquals(EmaWeather.Request.Current("Lagos", "Nigeria"), EmaWeather.parse("weather in Lagos, Nigeria"))
        assertEquals(EmaWeather.Request.Current("Lagos", "Lagos", "Nigeria"), EmaWeather.parse("weather in Lagos, Lagos, Nigeria"))
        assertEquals(EmaWeather.Request.MissingLocation, EmaWeather.parse("what is the weather?"))
        assertEquals(EmaWeather.Request.Daily(EmaWeather.Request.Current("Lagos", null), 1), EmaWeather.parse("weather in Lagos tomorrow"))
        assertEquals(EmaWeather.Request.Daily(EmaWeather.Request.Current("Lagos", "Nigeria"), 0), EmaWeather.parse("weather today in Lagos, Nigeria"))
        assertEquals(EmaWeather.Request.Daily(EmaWeather.Request.Current("Lagos", null), 1), EmaWeather.parse("forecast for Lagos tomorrow"))
        assertEquals(EmaWeather.Request.UnsupportedPeriod, EmaWeather.parse("weather in Lagos next week"))
        assertEquals(EmaWeather.Request.UnsupportedPeriod, EmaWeather.parse("current weather in Lagos tomorrow"))
        assertNull(EmaWeather.parse("message Ada saying weather in Lagos"))
        assertNull(EmaWeather.parse("weather in Lagos and then call Ada"))
    }

    @Test fun ambiguousCitiesAreNotGuessed() {
        assertTrue(EmaWeather.parsePlaces(places, EmaWeather.Request.Current("Lagos", null)) is EmaWeather.Places.Ambiguous)
        val result = EmaWeather.parsePlaces(places, EmaWeather.Request.Current("Lagos", "Nigeria")) as EmaWeather.Places.One
        assertEquals("Nigeria", result.place.country)
        assertEquals(EmaWeather.Places.None, EmaWeather.parsePlaces(places, EmaWeather.Request.Current("Lagos", "Ghana")))
        assertEquals(EmaWeather.Places.Failed, EmaWeather.parsePlaces("bad json", EmaWeather.Request.Current("Lagos", null)))
    }

    @Test fun dailyForecastUsesLocationDateAndLabelsProbability() {
        val place = (EmaWeather.parsePlaces(places, EmaWeather.Request.Current("Lagos", "Nigeria")) as EmaWeather.Places.One).place
        // Lagos is already on 24 November even though UTC is still 23 November.
        val now = Instant.parse("2026-11-23T23:30:00Z")
        val daily = """{"timezone":"Africa/Lagos","daily_units":{"temperature_2m_max":"°C","temperature_2m_min":"°C","precipitation_probability_max":"%"},"daily":{"time":["2026-11-25","2026-11-24"],"temperature_2m_max":[31.0,30.0],"temperature_2m_min":[24.0,23.0],"precipitation_probability_max":[70,40],"weather_code":[61,3]}}"""
        val today = EmaWeather.parseDaily(daily, 0, now)!!
        val tomorrow = EmaWeather.parseDaily(daily, 1, now)!!
        assertEquals("2026-11-24", today.date.toString())
        assertEquals(30.0, today.high, 0.0)
        assertEquals("2026-11-25", tomorrow.date.toString())
        assertEquals(70.0, tomorrow.rainProbability!!, 0.0)
        val text = EmaWeather.formatDaily(place, tomorrow, now)
        assertTrue(text.contains("highest hourly precipitation probability 70%"))
        assertTrue(text.contains("Forecasts can change"))
        assertTrue(text.contains("Weather data by Open-Meteo.com"))
        assertNull(EmaWeather.parseDaily(daily.replace("°C", "°F"), 1, now))
        assertNull(EmaWeather.parseDaily(daily.replace("2026-11-25", "2026-11-24"), 1, now))
        assertNull(EmaWeather.parseDaily(daily, 2, now))
        assertNull(EmaWeather.parseDaily(daily.replace("Africa/Lagos", "invalid/place"), 1, now))
    }

    @Test fun validDataIsAttributedAndIncludesTimeAndUnits() {
        val place = (EmaWeather.parsePlaces(places, EmaWeather.Request.Current("Lagos", "Nigeria")) as EmaWeather.Places.One).place
        val reading = EmaWeather.parseReading(current)!!
        val result = EmaWeather.format(place, reading, Instant.parse("2026-11-23T12:10:00Z"))
        assertTrue(result.contains("29.5°C"))
        assertTrue(result.contains("overcast"))
        assertTrue(result.contains("Africa/Lagos"))
        assertTrue(result.contains("2026-11-23T12:10:00Z"))
        assertTrue(result.contains("model data"))
        assertTrue(result.contains("https://open-meteo.com/"))
        assertNull(EmaWeather.parseReading(current.replace("°C", "°F")))
        assertNull(EmaWeather.parseReading("{}"))
        assertNull(EmaWeather.describeCode(999))
    }
}
