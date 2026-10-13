package com.ema.assistant

/**
 * A small, tolerant JSON reader for EMA's AI response path.
 *
 * Why not `org.json`? It belongs to the Android platform, so any code that uses
 * it is untestable on a plain JVM (the platform jar ships throwing stubs) and
 * needs an extra dependency to unit test. Parsing an assistant's reply is pure
 * string work, so it is done here instead — which makes the whole AI response
 * path coverable by ordinary unit tests.
 *
 * Tolerance matters more than strictness: the input is model output, which
 * arrives wrapped in prose, in code fences, and occasionally with trailing
 * commas. The parser accepts those and returns null rather than throwing when
 * the text is not JSON at all, so callers always have a clean fallback.
 *
 * Not a general-purpose JSON library: no key ordering guarantees, no big-number
 * precision, no duplicate-key detection (last value wins).
 */
object EmaJson {

    /** Read the first JSON object in [text]. Returns null when there isn't one. */
    fun readObject(text: String): Map<String, Any?>? {
        val parser = Parser(text)
        parser.skipTo('{')
        val map = parser.parseValue() as? Map<*, *> ?: return null
        val result = LinkedHashMap<String, Any?>(map.size)
        map.forEach { (key, value) -> result[key.toString()] = value }
        return result
    }

    /** A string field, or "" when absent, null, or not a string. */
    fun string(obj: Map<String, Any?>, key: String): String =
        (obj[key] as? String).orEmpty()

    /** A field holding an array of strings. Non-string entries are skipped. */
    fun stringList(obj: Map<String, Any?>, key: String): List<String> {
        val raw = obj[key] as? List<*> ?: return emptyList()
        return raw.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotBlank) }
    }

    private class Parser(private val input: String) {
        private var index = 0

        /** Advance to the next occurrence of [char], or to the end of input. */
        fun skipTo(char: Char) {
            while (index < input.length && input[index] != char) index++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (index >= input.length) return null
            return when (val char = input[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> consumeLiteral("true", true)
                'f' -> consumeLiteral("false", false)
                'n' -> consumeLiteral("null", null)
                else -> if (char == '-' || char.isDigit()) parseNumber() else null
            }
        }

        private fun parseObject(): Map<String, Any?>? {
            expect('{') ?: return null
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (index < input.length) {
                skipWhitespace()
                // A key that is not a quoted string means this is not really a
                // JSON object. Salvage what was already read, but report failure
                // when nothing was, so callers fall back to the raw text.
                val key = if (peek() == '"') parseString() else return result.ifEmpty { null }
                skipWhitespace()
                // Tolerate a missing colon rather than discarding the object.
                if (peek() == ':') index++
                val value = parseValue()
                result[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        // Trailing comma before the closing brace is accepted.
                        skipWhitespace()
                        if (peek() == '}') {
                            index++
                            return result
                        }
                    }
                    '}' -> {
                        index++
                        return result
                    }
                    else -> return result
                }
            }
            return result
        }

        private fun parseArray(): List<Any?>? {
            expect('[') ?: return null
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (index < input.length) {
                result += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                        if (peek() == ']') {
                            index++
                            return result
                        }
                    }
                    ']' -> {
                        index++
                        return result
                    }
                    else -> return result
                }
            }
            return result
        }

        private fun parseString(): String {
            expect('"') ?: return ""
            val builder = StringBuilder()
            while (index < input.length) {
                when (val char = input[index]) {
                    '"' -> {
                        index++
                        return builder.toString()
                    }
                    '\\' -> {
                        index++
                        if (index >= input.length) return builder.toString()
                        when (val escaped = input[index]) {
                            'n' -> builder.append('\n')
                            't' -> builder.append('\t')
                            'r' -> builder.append('\r')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            '/' -> builder.append('/')
                            '\\' -> builder.append('\\')
                            '"' -> builder.append('"')
                            'u' -> {
                                val hex = input.substring(index + 1, minOf(index + 5, input.length))
                                val code = hex.toIntOrNull(16)
                                if (code != null && hex.length == 4) {
                                    builder.append(code.toChar())
                                    index += 4
                                } else {
                                    builder.append(escaped)
                                }
                            }
                            else -> builder.append(escaped)
                        }
                        index++
                    }
                    else -> {
                        builder.append(char)
                        index++
                    }
                }
            }
            // Unterminated string: return what was read rather than losing it.
            return builder.toString()
        }

        private fun parseNumber(): Any? {
            val start = index
            if (peek() == '-') index++
            while (index < input.length && (input[index].isDigit() || input[index] in ".eE+-")) index++
            val raw = input.substring(start, index)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull()
        }

        private fun consumeLiteral(literal: String, value: Any?): Any? {
            if (input.startsWith(literal, index)) {
                index += literal.length
                return value
            }
            return null
        }

        private fun peek(): Char? = if (index < input.length) input[index] else null

        private fun expect(char: Char): Unit? {
            skipWhitespace()
            if (index < input.length && input[index] == char) {
                index++
                return Unit
            }
            return null
        }

        private fun skipWhitespace() {
            while (index < input.length && input[index].isWhitespace()) index++
        }
    }
}
