package com.ema.assistant

import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Explicit current-weather lookup using Open-Meteo; all reporting is local. */
object EmaWeather {
    sealed class Request {
        data class Current(val city: String, val qualifier: String?, val countryQualifier: String? = null) : Request()
        data class Daily(val location: Current, val dayOffset: Int) : Request()
        object MissingLocation : Request()
        object UnsupportedPeriod : Request()
        object Invalid : Request()
    }
    data class Place(val name: String, val region: String, val country: String, val countryCode: String, val latitude: Double, val longitude: Double) {
        fun label(): String = listOf(name, region, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
    }
    sealed class Places {
        data class One(val place: Place) : Places()
        data class Ambiguous(val options: List<Place>) : Places()
        object None : Places()
        object Failed : Places()
    }
    data class Reading(val temperature: Double, val apparent: Double?, val humidity: Double?, val wind: Double?, val code: Int?, val localTime: String, val timezone: String)
    data class DailyForecast(val date: LocalDate, val timezone: String, val low: Double, val high: Double, val rainProbability: Double?, val code: Int?)

    fun parse(raw: String): Request? {
        val text = raw.trim().trimEnd('?', '!', '.').trim()
        if (Regex("(?i)\\b(?:and then|then|and)\\s+(?:open|send|message|call|play|tap|set|turn)\\b").containsMatchIn(text)) return null
        if (Regex("(?i)^(?:(?:what(?:'s| is) (?:the )?|current )?weather)$").matches(text)) return Request.MissingLocation
        val before = Regex("(?i)^(?:what(?:'s| is) (?:the )?)?weather\\s+(today|tomorrow)\\s+(?:in|at|for)\\s+(.+)$").matchEntire(text)
        val after = Regex("(?i)^(?:(?:what(?:'s| is) (?:the )?|current )?weather|(?:what(?:'s| is) the )?forecast)\\s+(?:in|at|for)\\s+(.+)$").matchEntire(text)
        if (before == null && after == null) return null
        var place = if (before != null) before.groupValues[2].trim() else after!!.groupValues[1].trim()
        var period: String? = before?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        val suffix = Regex("(?i)\\s+(today|tomorrow)$").find(place)
        if (suffix != null) {
            if (period != null) return Request.UnsupportedPeriod
            period = suffix.groupValues[1].lowercase(Locale.ROOT)
            place = place.substring(0, suffix.range.first).trim()
        }
        if (Regex("(?i)\\b(?:tomorrow|today|tonight|yesterday|weekend|next week|forecast|hourly|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").containsMatchIn(place)) return Request.UnsupportedPeriod
        if (period == null && Regex("(?i)\\bforecast\\b").containsMatchIn(text)) return Request.UnsupportedPeriod
        if (period != null && (text.startsWith("current ", true) || Regex("(?i)\\s+(?:right now|currently|now)$").containsMatchIn(place))) return Request.UnsupportedPeriod
        place = place.replace(Regex("(?i)\\s+(?:right now|currently|now)$"), "").trim()
        if (place.length !in 2..100 || !place.matches(Regex("^[\\p{L}\\p{M} .,'’\\-]+$"))) return Request.Invalid
        val parts = place.split(',').map { it.trim() }
        if (parts.size > 3 || parts.any { it.isBlank() }) return Request.Invalid
        val location = Request.Current(parts[0], parts.getOrNull(1), parts.getOrNull(2))
        return if (period == null) location else Request.Daily(location, if (period == "today") 0 else 1)
    }

    fun geocodingUrl(city: String): String = "https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&format=json&name=" + URLEncoder.encode(city, "UTF-8")

    fun parsePlaces(body: String, request: Request.Current): Places {
        val root = EmaJson.readObject(body) ?: return Places.Failed
        val raw = root["results"] as? List<*> ?: return if (root.containsKey("error")) Places.Failed else Places.None
        val options = raw.mapNotNull { value ->
            val item = value as? Map<*, *> ?: return@mapNotNull null
            val name = (item["name"] as? String)?.trim().orEmpty()
            val region = (item["admin1"] as? String)?.trim().orEmpty()
            val country = (item["country"] as? String)?.trim().orEmpty()
            val countryCode = (item["country_code"] as? String)?.trim().orEmpty()
            val lat = (item["latitude"] as? Number)?.toDouble() ?: return@mapNotNull null
            val lon = (item["longitude"] as? Number)?.toDouble() ?: return@mapNotNull null
            if (!name.equals(request.city, ignoreCase = true) || name.length > 100 || region.length > 100 || country.length > 100 ||
                countryCode.length > 4 || listOf(name, region, country, countryCode).any { v -> v.any { it.isISOControl() } } ||
                !lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) return@mapNotNull null
            val qualifier = request.qualifier
            if (request.countryQualifier != null) {
                if (qualifier == null || !qualifier.equals(region, true) ||
                    (!request.countryQualifier.equals(country, true) && !request.countryQualifier.equals(countryCode, true))) return@mapNotNull null
            } else if (qualifier != null && !qualifier.equals(region, true) && !qualifier.equals(country, true) && !qualifier.equals(countryCode, true)) return@mapNotNull null
            Place(name, region, country, countryCode, lat, lon)
        }.distinctBy { Triple(it.name.lowercase(Locale.ROOT), it.latitude, it.longitude) }
        return when (options.size) { 0 -> Places.None; 1 -> Places.One(options[0]); else -> Places.Ambiguous(options.take(5)) }
    }

    fun weatherUrl(place: Place): String = "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}" +
        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&timezone=auto"

    fun parseReading(body: String): Reading? {
        val root = EmaJson.readObject(body) ?: return null
        if (root.containsKey("error")) return null
        val current = root["current"] as? Map<*, *> ?: return null
        val units = root["current_units"] as? Map<*, *> ?: return null
        if (units["temperature_2m"] != "°C" || units["apparent_temperature"] != "°C" ||
            units["relative_humidity_2m"] != "%" || units["wind_speed_10m"] != "km/h") return null
        fun number(key: String): Double? = (current[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }
        val temp = number("temperature_2m")?.takeIf { it in -100.0..70.0 } ?: return null
        val apparent = number("apparent_temperature")?.takeIf { it in -120.0..100.0 }
        val humidity = number("relative_humidity_2m")?.takeIf { it in 0.0..100.0 }
        val wind = number("wind_speed_10m")?.takeIf { it in 0.0..500.0 }
        val code = (current["weather_code"] as? Long)?.takeIf { it in 0..99 }?.toInt()
        val time = (current["time"] as? String).orEmpty()
        val zone = (root["timezone"] as? String).orEmpty()
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$").matches(time) || zone.length > 80 ||
            !zone.matches(Regex("^[A-Za-z0-9_+./-]+$"))) return null
        return Reading(temp, apparent, humidity, wind, code, time, zone)
    }

    fun dailyUrl(place: Place): String = "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=2"

    /** Select by the location's local date, never by array position or the phone's time zone. */
    fun parseDaily(body: String, dayOffset: Int, now: Instant = Instant.now()): DailyForecast? {
        if (dayOffset !in 0..1) return null
        val root = EmaJson.readObject(body) ?: return null
        if (root.containsKey("error")) return null
        val zone = (root["timezone"] as? String).orEmpty()
        if (zone.length > 80 || !zone.matches(Regex("^[A-Za-z0-9_+./-]+$"))) return null
        val expected = runCatching { now.atZone(ZoneId.of(zone)).toLocalDate().plusDays(dayOffset.toLong()) }.getOrNull() ?: return null
        val units = root["daily_units"] as? Map<*, *> ?: return null
        if (units["temperature_2m_max"] != "°C" || units["temperature_2m_min"] != "°C" ||
            units["precipitation_probability_max"] != "%") return null
        val daily = root["daily"] as? Map<*, *> ?: return null
        val days = daily["time"] as? List<*> ?: return null
        val indexes = days.indices.filter { days[it] == expected.toString() }
        if (indexes.size != 1) return null
        val index = indexes.single()
        fun value(key: String): Double? = ((daily[key] as? List<*>)?.getOrNull(index) as? Number)?.toDouble()?.takeIf { it.isFinite() }
        val low = value("temperature_2m_min")?.takeIf { it in -100.0..70.0 } ?: return null
        val high = value("temperature_2m_max")?.takeIf { it in -100.0..70.0 && it >= low } ?: return null
        val rain = value("precipitation_probability_max")?.takeIf { it in 0.0..100.0 }
        val code = ((daily["weather_code"] as? List<*>)?.getOrNull(index) as? Long)?.takeIf { it in 0..99 }?.toInt()
        return DailyForecast(expected, zone, low, high, rain, code)
    }

    fun formatDaily(place: Place, forecast: DailyForecast, retrieved: Instant = Instant.now()): String {
        fun n(value: Double) = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
        return buildString {
            append("Open-Meteo daily forecast for ").append(place.label()).append(" on ")
                .append(forecast.date).append(" (").append(forecast.timezone).append("): ")
            describeCode(forecast.code)?.let { append("most severe predicted condition: ").append(it).append(", ") }
            append("high ").append(n(forecast.high)).append("°C, low ").append(n(forecast.low)).append("°C")
            forecast.rainProbability?.let { append(", highest hourly precipitation probability ").append(n(it)).append('%') }
            append(". Retrieved: ").append(retrieved).append(". Forecasts can change. Weather data by Open-Meteo.com: https://open-meteo.com/")
        }
    }

    fun describeCode(code: Int?): String? = when (code) {
        0 -> "clear sky"; 1 -> "mainly clear"; 2 -> "partly cloudy"; 3 -> "overcast"
        45, 48 -> "fog"; 51, 53, 55 -> "drizzle"; 56, 57 -> "freezing drizzle"
        61, 63, 65 -> "rain"; 66, 67 -> "freezing rain"; 71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"; 85, 86 -> "snow showers"; 95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"; else -> null
    }

    fun format(place: Place, reading: Reading, retrieved: Instant = Instant.now()): String {
        fun n(value: Double) = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
        return buildString {
            append("Open-Meteo current weather for ").append(place.label()).append(": ")
            describeCode(reading.code)?.let { append(it).append(", ") }
            append(n(reading.temperature)).append("°C")
            reading.apparent?.let { append(", feels like ").append(n(it)).append("°C") }
            reading.humidity?.let { append(", humidity ").append(n(it)).append('%') }
            reading.wind?.let { append(", wind ").append(n(it)).append(" km/h") }
            append(". Data time: ").append(reading.localTime).append(" (").append(reading.timezone).append(").")
            append(" Retrieved: ").append(retrieved).append(". This is model data and may be delayed. Weather data by Open-Meteo.com: https://open-meteo.com/")
        }
    }
}
