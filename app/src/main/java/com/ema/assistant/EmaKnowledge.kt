package com.ema.assistant

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Offline knowledge that needs no network and no model: arithmetic,
 * percentages, unit conversion, calendar arithmetic and facts about EMA
 * itself.
 *
 * Deliberately separate from device control. Every answer here is computed,
 * not looked up, so it is correct by construction; anything that would need
 * current external information (news, weather, live prices, general trivia) is
 * reported honestly as needing the internet instead of being invented.
 */
object EmaKnowledge {

    data class Answer(val text: String, val kind: String)

    fun answer(raw: String, today: LocalDate = LocalDate.now()): Answer? {
        val q = raw.trim().trimEnd('?', '.', '!').trim()
        if (q.isBlank()) return null
        val lower = q.lowercase(Locale.ROOT)
        selfAnswer(lower)?.let { return Answer(it, "self") }
        convert(lower)?.let { return Answer(it, "conversion") }
        dateAnswer(lower, today)?.let { return Answer(it, "date") }
        calculate(lower)?.let { return Answer(it, "calculation") }
        return null
    }

    /** True for questions that need live or external information EMA does not have offline. */
    fun needsExternalInformation(raw: String): Boolean {
        val l = raw.lowercase(Locale.ROOT)
        return Regex("\\b(weather|forecast|news|headlines|score|stock|price of|exchange rate|who won|latest|currently|right now in|traffic|population of|capital of|who is|who was|what is the meaning|define|definition of|translate)\\b").containsMatchIn(l)
    }

    // ------------------------------------------------------------ self

    private fun selfAnswer(l: String): String? = when {
        Regex("^(?:who|what) are you$|^what(?:'s| is) your name$|^introduce yourself$").containsMatchIn(l) ->
            "I'm EMA, an assistant that runs on this phone. I understand requests with on-device logic, control apps through Android, and check the result before I report it."
        Regex("^(?:do you|does ema) (?:need|use|require) (?:the )?internet|^are you (?:online|offline)|^do you work offline").containsMatchIn(l) ->
            "Phone control, messaging, media, alarms, reminders, calculations and conversions work without the internet. Only questions that need outside information, such as news or weather, need a connection, and an optional AI service if you turn it on."
        Regex("^are you (?:an? )?(?:ai|robot|chatgpt|human|person|llm)").containsMatchIn(l) ->
            "I'm EMA. My understanding and phone actions come from deterministic logic built into this app, not from a chat model. An optional online AI can be switched on in Settings for open-ended questions."
        else -> null
    }

    // ------------------------------------------------------ arithmetic

    private val WORD_NUMBERS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
        "hundred" to 100, "thousand" to 1000, "million" to 1_000_000
    )

    /** Evaluate a spoken or typed arithmetic question. Returns null when it is not arithmetic. */
    fun calculate(lower: String): String? {
        var s = lower
            .replace(Regex("^(?:what(?:'s| is)|calculate|compute|work out|how much is|tell me|solve|evaluate)\\s+"), "")
            .replace(Regex("^(?:the\\s+)?(?:answer|result) (?:to|of)\\s+"), "")
            .replace(Regex("^the\\s+"), "")
            .replace(",", "")
            .trim()
        if (s.isBlank()) return null
        // "15% of 2400", "15 percent of 2400"
        Regex("^([\\d.]+)\\s*(?:%|percent|per cent)\\s+of\\s+([\\d.]+)$").find(s)?.let { m ->
            val p = m.groupValues[1].toDouble(); val v = m.groupValues[2].toDouble()
            return "${fmt(p)}% of ${fmt(v)} is ${fmt(p / 100 * v)}."
        }
        // "what percent is 30 of 120", "30 is what percent of 120"
        Regex("^(?:what percent(?:age)? is\\s+([\\d.]+)\\s+of\\s+([\\d.]+)|([\\d.]+) is what percent(?:age)? of ([\\d.]+))$").find(s)?.let { m ->
            val a = (m.groupValues[1].ifBlank { m.groupValues[3] }).toDouble()
            val b = (m.groupValues[2].ifBlank { m.groupValues[4] }).toDouble()
            if (b == 0.0) return "I can't compute a percentage of zero."
            return "${fmt(a)} is ${fmt(a / b * 100)}% of ${fmt(b)}."
        }
        // "increase 200 by 15%", "200 plus 15 percent", "decrease 80 by 10%"
        Regex("^(increase|decrease|reduce)\\s+([\\d.]+)\\s+by\\s+([\\d.]+)\\s*(?:%|percent)$").find(s)?.let { m ->
            val v = m.groupValues[2].toDouble(); val p = m.groupValues[3].toDouble()
            val r = if (m.groupValues[1] == "increase") v * (1 + p / 100) else v * (1 - p / 100)
            return "${fmt(v)} ${if (m.groupValues[1] == "increase") "increased" else "reduced"} by ${fmt(p)}% is ${fmt(r)}."
        }
        val expr = toExpression(s) ?: return null
        if (!expr.any { it.isDigit() } || !expr.any { it in "+-*/^√!" } && !expr.contains("sqrt")) return null
        val value = runCatching { Parser(expr).parse() }.getOrNull() ?: return null
        if (value.isNaN()) return "That calculation has no real answer."
        if (value.isInfinite()) return "That result is undefined."
        return "${pretty(s)} = ${fmt(value)}."
    }

    private fun toExpression(s: String): String? {
        var e = " $s "
        for ((word, n) in WORD_NUMBERS.entries.sortedByDescending { it.key.length }) {
            e = e.replace(Regex("(?<=\\s)$word(?=\\s)"), n.toString())
        }
        val pairs = listOf(
            "multiplied by" to "*", "times" to "*", "x" to "*", "into" to "*",
            "divided by" to "/", "over" to "/", "plus" to "+", "add" to "+", "and" to "+",
            "minus" to "-", "subtract" to "-", "less" to "-",
            "to the power of" to "^", "raised to" to "^", "power" to "^",
            "squared" to "^2", "cubed" to "^3", "square root of" to "√", "root of" to "√", "sqrt" to "√",
            "percent" to "%", "factorial" to "!", "modulo" to "%%", "mod" to "%%"
        )
        for ((w, op) in pairs) e = e.replace(Regex("(?<=[\\s\\d])${Regex.escape(w)}(?=[\\s\\d(])"), " $op ")
        e = e.replace("×", "*").replace("÷", "/").replace("−", "-")
        e = e.replace(Regex("\\s+"), "")
        // "15%" meaning 0.15 when not followed by "of"
        e = e.replace(Regex("(\\d+(?:\\.\\d+)?)%(?!%)"), "($1/100)")
        e = e.replace("%%", "%")
        return if (Regex("^[\\d.+\\-*/^()√!%]+$").matches(e)) e else null
    }

    private fun pretty(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double { val v = expr(); if (i != s.length) throw IllegalArgumentException(); return v }
        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            return v
        }
        fun term(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                val op = s[i++]; val r = power()
                v = when (op) { '*' -> v * r; '/' -> v / r; else -> v % r }
            }
            return v
        }
        fun power(): Double {
            val base = unary()
            if (i < s.length && s[i] == '^') { i++; return base.pow(power()) }
            return base
        }
        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            if (i < s.length && s[i] == '√') { i++; return sqrt(unary()) }
            return postfix()
        }
        fun postfix(): Double {
            var v = atom()
            while (i < s.length && s[i] == '!') {
                i++
                if (v < 0 || v > 170 || v != floor(v)) throw IllegalArgumentException()
                v = (1..v.toInt()).fold(1.0) { acc, n -> acc * n }
            }
            return v
        }
        fun atom(): Double {
            if (i < s.length && s[i] == '(') { i++; val v = expr(); if (i >= s.length || s[i] != ')') throw IllegalArgumentException(); i++; return v }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException()
            return s.substring(start, i).toDouble()
        }
    }

    fun fmt(v: Double): String {
        if (abs(v - Math.rint(v)) < 1e-9 && abs(v) < 1e15) return String.format(Locale.ROOT, "%,d", Math.rint(v).toLong())
        val s = String.format(Locale.ROOT, "%,.6f", v).trimEnd('0').trimEnd('.')
        return s
    }

    // ------------------------------------------------------ conversion

    private data class Unit(val dimension: String, val factor: Double)

    private val UNITS: Map<String, Unit> = buildMap {
        fun add(names: List<String>, dim: String, f: Double) = names.forEach { put(it, Unit(dim, f)) }
        add(listOf("mm", "millimeter", "millimeters", "millimetre", "millimetres"), "length", 0.001)
        add(listOf("cm", "centimeter", "centimeters", "centimetre", "centimetres"), "length", 0.01)
        add(listOf("m", "meter", "meters", "metre", "metres"), "length", 1.0)
        add(listOf("km", "kilometer", "kilometers", "kilometre", "kilometres", "kms"), "length", 1000.0)
        add(listOf("in", "inch", "inches"), "length", 0.0254)
        add(listOf("ft", "foot", "feet"), "length", 0.3048)
        add(listOf("yd", "yard", "yards"), "length", 0.9144)
        add(listOf("mi", "mile", "miles"), "length", 1609.344)
        add(listOf("mg", "milligram", "milligrams"), "mass", 0.001)
        add(listOf("g", "gram", "grams"), "mass", 1.0)
        add(listOf("kg", "kilo", "kilos", "kilogram", "kilograms"), "mass", 1000.0)
        add(listOf("lb", "lbs", "pound", "pounds"), "mass", 453.59237)
        add(listOf("oz", "ounce", "ounces"), "mass", 28.349523125)
        add(listOf("stone", "stones"), "mass", 6350.29318)
        add(listOf("ml", "milliliter", "milliliters", "millilitre", "millilitres"), "volume", 0.001)
        add(listOf("l", "liter", "liters", "litre", "litres"), "volume", 1.0)
        add(listOf("gallon", "gallons", "gal"), "volume", 3.785411784)
        add(listOf("cup", "cups"), "volume", 0.2365882365)
        add(listOf("second", "seconds", "sec", "secs"), "time", 1.0)
        add(listOf("minute", "minutes", "min", "mins"), "time", 60.0)
        add(listOf("hour", "hours", "hr", "hrs"), "time", 3600.0)
        add(listOf("day", "days"), "time", 86400.0)
        add(listOf("week", "weeks"), "time", 604800.0)
        add(listOf("kb", "kilobyte", "kilobytes"), "data", 1024.0)
        add(listOf("mb", "megabyte", "megabytes"), "data", 1024.0 * 1024)
        add(listOf("gb", "gigabyte", "gigabytes"), "data", 1024.0 * 1024 * 1024)
        add(listOf("tb", "terabyte", "terabytes"), "data", 1024.0 * 1024 * 1024 * 1024)
        add(listOf("kmh", "kph", "km/h"), "speed", 1000.0 / 3600)
        add(listOf("mph"), "speed", 1609.344 / 3600)
    }

    private val TEMPS = mapOf(
        "c" to "C", "celsius" to "C", "centigrade" to "C", "degrees celsius" to "C",
        "f" to "F", "fahrenheit" to "F", "degrees fahrenheit" to "F",
        "k" to "K", "kelvin" to "K"
    )

    fun convert(lower: String): String? {
        val s = lower.replace(",", "").replace("degrees ", "").replace("degree ", "").replace("°", " ")
        val m = Regex("(?:^|\\b)(?:convert\\s+|what(?:'s| is)\\s+|how much is\\s+)?(-?[\\d.]+)\\s*([a-z/]+(?:\\s[a-z]+)?)\\s+(?:to|in|into|as)\\s+([a-z/]+)$").find(s)
            ?: Regex("^how many\\s+([a-z/]+)\\s+(?:are\\s+)?(?:in|is|are)\\s+(-?[\\d.]+)\\s*([a-z/]+)$").find(s)?.let { h ->
                return convertValues(h.groupValues[2].toDoubleOrNull() ?: return null, h.groupValues[3], h.groupValues[1])
            }
            ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        return convertValues(value, m.groupValues[2].trim(), m.groupValues[3].trim())
    }

    private fun convertValues(value: Double, fromRaw: String, toRaw: String): String? {
        val from = fromRaw.trim(); val to = toRaw.trim()
        val tf = TEMPS[from]; val tt = TEMPS[to]
        if (tf != null && tt != null) {
            val c = when (tf) { "C" -> value; "F" -> (value - 32) * 5 / 9; else -> value - 273.15 }
            val r = when (tt) { "C" -> c; "F" -> c * 9 / 5 + 32; else -> c + 273.15 }
            return "${fmt(value)}°$tf is ${fmt(round2(r))}°$tt."
        }
        val uf = UNITS[from] ?: return null
        val ut = UNITS[to] ?: return null
        if (uf.dimension != ut.dimension) return "I can't convert $from to $to: one is ${uf.dimension} and the other is ${ut.dimension}."
        val r = value * uf.factor / ut.factor
        return "${fmt(value)} $from is ${fmt(round4(r))} $to."
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0
    private fun round4(v: Double) = if (abs(v) >= 1) Math.round(v * 100) / 100.0 else Math.round(v * 10000) / 10000.0

    // ------------------------------------------------------------ dates

    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    private fun parseDate(text: String, today: LocalDate): LocalDate? {
        val t = text.trim().removePrefix("the ").trim()
        when (t) {
            "today" -> return today
            "tomorrow" -> return today.plusDays(1)
            "yesterday" -> return today.minusDays(1)
            "christmas", "christmas day" -> return nextOccurrence(MonthDay.of(12, 25), today)
            "new year", "new year's day", "new years day", "new year's" -> return nextOccurrence(MonthDay.of(1, 1), today)
            "valentine's day", "valentines day" -> return nextOccurrence(MonthDay.of(2, 14), today)
            "halloween" -> return nextOccurrence(MonthDay.of(10, 31), today)
        }
        Regex("^(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?([a-z]+)(?:\\s+(\\d{4}))?$").find(t)?.let { m ->
            val month = MONTHS.indexOfFirst { it.startsWith(m.groupValues[2].take(3)) } + 1
            if (month == 0) return null
            val day = m.groupValues[1].toInt()
            return runCatching {
                if (m.groupValues[3].isNotBlank()) LocalDate.of(m.groupValues[3].toInt(), month, day)
                else nextOccurrence(MonthDay.of(month, day), today)
            }.getOrNull()
        }
        Regex("^([a-z]+)\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?$").find(t)?.let { m ->
            val month = MONTHS.indexOfFirst { it.startsWith(m.groupValues[1].take(3)) } + 1
            if (month == 0) return null
            val day = m.groupValues[2].toInt()
            return runCatching {
                if (m.groupValues[3].isNotBlank()) LocalDate.of(m.groupValues[3].toInt(), month, day)
                else nextOccurrence(MonthDay.of(month, day), today)
            }.getOrNull()
        }
        return null
    }

    private fun nextOccurrence(md: MonthDay, today: LocalDate): LocalDate {
        val thisYear = md.atYear(today.year)
        return if (thisYear.isBefore(today)) md.atYear(today.year + 1) else thisYear
    }

    private fun describe(d: LocalDate): String =
        "${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}, ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${d.year}"

    fun dateAnswer(lower: String, today: LocalDate): String? {
        Regex("^how many (days|weeks) (?:are there )?(?:until|till|to|before) (.+)$").find(lower)?.let { m ->
            val target = parseDate(m.groupValues[2], today) ?: return null
            val days = ChronoUnit.DAYS.between(today, target)
            if (days == 0L) return "That's today."
            return if (m.groupValues[1] == "weeks") "${fmt(days / 7.0)} weeks until ${describe(target)}."
            else "$days day${if (days == 1L) "" else "s"} until ${describe(target)}."
        }
        Regex("^(?:what(?:'s| is| will be)? (?:the date|the day|day)|what date is it) in (\\d+|[a-z]+) (days?|weeks?|months?|years?)(?: from (?:now|today))?$").find(lower)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: WORD_NUMBERS[m.groupValues[1]]?.toLong() ?: return null
            val d = when {
                m.groupValues[2].startsWith("day") -> today.plusDays(n)
                m.groupValues[2].startsWith("week") -> today.plusWeeks(n)
                m.groupValues[2].startsWith("month") -> today.plusMonths(n)
                else -> today.plusYears(n)
            }
            return "It will be ${describe(d)}."
        }
        Regex("^what day (?:of the week )?(?:is|was|will be|will) (.+?)(?: be| fall on| fall)?$").find(lower)?.let { m ->
            val phrase = m.groupValues[1].removePrefix("it ").trim()
            if (phrase == "it" || phrase == "today") return "Today is ${describe(today)}."
            val d = parseDate(phrase, today) ?: return null
            return "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${d.year} is a ${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}."
        }
        Regex("^(?:what(?:'s| is) )?(?:the )?date (?:of )?(next|this) (monday|tuesday|wednesday|thursday|friday|saturday|sunday)$").find(lower)?.let { m ->
            val dow = DayOfWeek.valueOf(m.groupValues[2].uppercase())
            var d = today.plusDays(1)
            while (d.dayOfWeek != dow) d = d.plusDays(1)
            // "this Friday" = the coming Friday. "next Friday" = the Friday of
            // next week: if the coming one is still in the current Mon-Sun week,
            // skip a week.
            if (m.groupValues[1] == "next" && d.with(DayOfWeek.MONDAY) == today.with(DayOfWeek.MONDAY)) d = d.plusWeeks(1)
            return "${m.groupValues[1].replaceFirstChar { it.uppercase() }} ${m.groupValues[2]} is ${describe(d)}."
        }
        if (Regex("^is (\\d{4}) a leap year$").matches(lower)) {
            val y = Regex("\\d{4}").find(lower)!!.value.toInt()
            return if (java.time.Year.isLeap(y.toLong())) "Yes, $y is a leap year." else "No, $y is not a leap year."
        }
        return null
    }
}
