package com.ema.assistant

import java.math.BigInteger
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

/** Computed, bounded offline knowledge. Whole utterances only; never executes a device action. */
object EmaReasoningKnowledge {
    data class Result(val text: String, val value: Double? = null)
    private const val N = "(-?\\d+(?:\\.\\d+)?)"
    private fun f(n: Double): String = java.math.BigDecimal.valueOf(n).setScale(8, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    private fun result(label: String, n: Double): Result = if (!n.isFinite()) Result("That result is undefined.") else Result("$label = ${f(n)}.", n)
    private fun match(pattern: String, q: String) = Regex(pattern, RegexOption.IGNORE_CASE).matchEntire(q)

    val cities: Map<String, String> = mapOf(
        "lagos" to "Africa/Lagos", "abuja" to "Africa/Lagos", "accra" to "Africa/Accra",
        "nairobi" to "Africa/Nairobi", "cairo" to "Africa/Cairo", "johannesburg" to "Africa/Johannesburg",
        "london" to "Europe/London", "paris" to "Europe/Paris", "berlin" to "Europe/Berlin",
        "rome" to "Europe/Rome", "madrid" to "Europe/Madrid", "moscow" to "Europe/Moscow",
        "new york" to "America/New_York", "los angeles" to "America/Los_Angeles", "chicago" to "America/Chicago",
        "toronto" to "America/Toronto", "vancouver" to "America/Vancouver", "sao paulo" to "America/Sao_Paulo",
        "dubai" to "Asia/Dubai", "riyadh" to "Asia/Riyadh", "mumbai" to "Asia/Kolkata", "delhi" to "Asia/Kolkata",
        "singapore" to "Asia/Singapore", "tokyo" to "Asia/Tokyo", "seoul" to "Asia/Seoul",
        "beijing" to "Asia/Shanghai", "hong kong" to "Asia/Hong_Kong", "sydney" to "Australia/Sydney",
        "melbourne" to "Australia/Melbourne", "auckland" to "Pacific/Auckland", "honolulu" to "Pacific/Honolulu"
    )

    fun answer(raw: String, today: LocalDate = LocalDate.now(), clock: Clock = Clock.systemUTC(), previous: Double? = null): Result? {
        if (raw.length > 4096) return null
        val q = raw.trim().trimEnd('?', '!', '.').lowercase(Locale.ROOT)
        when (q) {
            "double that", "double it" -> return previous?.let { result("2 × ${f(it)}", it * 2) } ?: Result("There is no recent numeric result to double.")
            "half that", "halve it", "halve that" -> return previous?.let { result("${f(it)} / 2", it / 2) } ?: Result("There is no recent numeric result to halve.")
        }
        match("(?:split (?:that|it) (?:into |among |between )?|divide (?:that|it) by )(\\d+)(?: ways| people)?", q)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: return Result("That divisor is too large.")
            return previous?.let { value -> if (n == 0) Result("Cannot divide by zero.") else result("${f(value)} / $n", value / n) }
                ?: Result("There is no recent numeric result to split.")
        }
        match("(?:what(?:'s| is) the )?time in ([a-z ]+)", q)?.let {
            val city = it.groupValues[1]
            val zone = cities[city] ?: return Result("I don't have an offline timezone mapping for $city. Specify a supported city.")
            val now = ZonedDateTime.now(clock.withZone(ZoneId.of(zone)))
            return Result("In ${city.replaceFirstChar { c -> c.titlecase(Locale.ROOT) }}, it is ${now.format(DateTimeFormatter.ofPattern("HH:mm, EEEE d MMMM uuuu", Locale.ENGLISH))} (${now.offset}).")
        }
        match("(?:what is |calculate )?(sin|cos|tan|ln|log10|log2|sqrt|abs)\\(?\\s*$N\\s*\\)?(?: (degrees|radians))?", q)?.let {
            val op = it.groupValues[1]; val x = it.groupValues[2].toDouble(); val unit = it.groupValues[3]
            val angle = if (unit == "radians") x else Math.toRadians(x)
            val value = when (op) {
                "sin" -> sin(angle); "cos" -> cos(angle)
                "tan" -> if (abs(cos(angle)) < 1e-12) Double.NaN else tan(angle)
                "ln" -> ln(x); "log10" -> log10(x); "log2" -> log2(x); "sqrt" -> sqrt(x); else -> abs(x)
            }
            return result("$op(${f(x)}${if (op in setOf("sin", "cos", "tan")) if (unit == "radians") " radians" else " degrees" else ""})", value)
        }
        match("(?:is )?(\\d+) (?:a )?prime(?: number)?", q)?.let {
            val n = it.groupValues[1].toLongOrNull() ?: return Result("Use an integer no larger than 1,000,000,000,000.")
            if (n > 1_000_000_000_000L) return Result("Use an integer no larger than 1,000,000,000,000.")
            var d = 3L
            var prime = n >= 2 && (n == 2L || n % 2 != 0L)
            while (prime && d <= n / d) { if (n % d == 0L) prime = false; d += 2 }
            return Result("$n is ${if (prime) "prime" else "not prime"}.")
        }
        match("(gcd|lcm) (?:of )?(\\d+) (?:and |,\\s*)(\\d+)", q)?.let {
            val a = it.groupValues[2].toBigInteger(); val b = it.groupValues[3].toBigInteger()
            if (a.bitLength() > 256 || b.bitLength() > 256) return Result("Use integers of at most 256 bits.")
            val gcd = a.gcd(b)
            val value = if (it.groupValues[1] == "gcd") gcd else if (gcd == BigInteger.ZERO) BigInteger.ZERO else (a / gcd) * b
            return Result("${it.groupValues[1].uppercase(Locale.ROOT)}($a, $b) = $value.")
        }
        match("(mean|average|median|sum|range|population standard deviation|sample standard deviation) of ([-0-9., ]+)", q)?.let {
            val tokens = it.groupValues[2].split(Regex("[, ]+")).filter { t -> t.isNotBlank() }
            if (tokens.size !in 1..1000) return Result("Provide between 1 and 1,000 numbers, separated by commas or spaces.")
            val values = tokens.map { t -> t.toDoubleOrNull() ?: return Result("I couldn't parse that list of numbers.") }
            if (values.any { v -> !v.isFinite() }) return Result("Use finite numbers.")
            val mean = values.average(); val sorted = values.sorted(); val size = values.size
            val value = when (it.groupValues[1]) {
                "mean", "average" -> mean
                "median" -> if (size % 2 == 1) sorted[size / 2] else (sorted[size / 2 - 1] + sorted[size / 2]) / 2
                "sum" -> values.sum(); "range" -> sorted.last() - sorted.first()
                "sample standard deviation" -> if (size < 2) Double.NaN else sqrt(values.sumOf { v -> (v - mean).pow(2) } / (size - 1))
                else -> sqrt(values.sumOf { v -> (v - mean).pow(2) } / size)
            }
            return result(it.groupValues[1], value)
        }
        match("split $N (?:among|between) (\\d+) (?:people|ways)(?: with $N(?:%| percent) tip)?", q)?.let {
            val total = it.groupValues[1].toDouble(); val count = it.groupValues[2].toIntOrNull() ?: 0
            val tip = it.groupValues[3].toDoubleOrNull() ?: 0.0
            if (count <= 0 || total < 0 || tip < 0) return Result("Use a non-negative bill and tip, and at least one person.")
            return result("Per person including ${f(tip)}% tip", total * (1 + tip / 100) / count)
        }
        match("(?:calculate )?bmi (?:for )?$N kg (?:and |at )?$N (cm|m)", q)?.let {
            val kg = it.groupValues[1].toDouble(); var height = it.groupValues[2].toDouble()
            if (it.groupValues[3] == "cm") height /= 100
            if (kg <= 0 || height <= 0) return Result("Weight and height must be positive.")
            val bmi = kg / (height * height)
            return Result("BMI = ${f(bmi)}. This is a screening calculation, not a diagnosis.", bmi)
        }
        match("(?:how old (?:am i|is someone)|age) (?:if (?:i was|they were) )?born (?:on )?(\\d{4}-\\d{2}-\\d{2})", q)?.let {
            val date = runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() ?: return Result("That birth date is not valid.")
            if (date > today) return Result("That birth date is in the future.")
            val age = Period.between(date, today)
            return Result("${age.years} years, ${age.months} months and ${age.days} days old.")
        }
        match("solve linear $N x \\+ $N = $N", q)?.let {
            val a = it.groupValues[1].toDouble(); val b = it.groupValues[2].toDouble(); val c = it.groupValues[3].toDouble()
            return if (a == 0.0) Result(if (b == c) "Infinitely many solutions." else "No solution.") else result("x", (c - b) / a)
        }
        match("solve quadratic $N x\\^2 \\+ $N x \\+ $N = 0", q)?.let {
            val a = it.groupValues[1].toDouble(); val b = it.groupValues[2].toDouble(); val c = it.groupValues[3].toDouble()
            if (a == 0.0) return if (b == 0.0) Result(if (c == 0.0) "Infinitely many solutions." else "No solution.") else result("x", -c / b)
            val disc = b * b - 4 * a * c
            if (!disc.isFinite()) return Result("Coefficients are too large for a reliable result.")
            if (disc < 0) return Result("No real roots (negative discriminant).")
            // Stable formula avoids cancellation for widely differing roots.
            val t = -0.5 * (b + if (b >= 0) sqrt(disc) else -sqrt(disc))
            val x1 = if (t == 0.0) 0.0 else t / a
            val x2 = if (t == 0.0) 0.0 else c / t
            return if (!x1.isFinite() || !x2.isFinite()) Result("Coefficients are too large for a reliable result.") else Result("x = ${f(min(x1, x2))} or ${f(max(x1, x2))}.")
        }
        return null
    }
}
