package com.ema.assistant

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Explicit, read-only, exact-title Wikipedia lookup. Never executes page content. */
object EmaWikipediaLookup {
    const val MAX_TITLE = 160
    private const val MAX_BYTES = 128 * 1024
    private const val ENDPOINT = "https://en.wikipedia.org/w/api.php"

    sealed class Request {
        data class Lookup(val title: String) : Request()
        data class Invalid(val message: String) : Request()
    }
    sealed class Result {
        data class Article(val title: String, val excerpt: String, val url: String) : Result()
        object Missing : Result()
        object Ambiguous : Result()
        object Unavailable : Result()
    }

    /** Requires the user to name Wikipedia and request a lookup, not merely ask a question. */
    fun parse(raw: String): Request? {
        val text = raw.trim()
        val prefix = Regex("(?i)^(?:wikipedia lookup|look up on wikipedia)(?:\\s*:\\s*|\\s+|$)(.*)$")
            .matchEntire(text)
        val suffix = Regex("(?i)^look up\\s+(.+?)\\s+on wikipedia[.!?]*$").matchEntire(text)
        val match = prefix ?: suffix ?: return null
        val title = match.groupValues[1].trim().trimEnd('?', '.', '!').trim()
        return when {
            title.isBlank() -> Request.Invalid("Tell me the exact Wikipedia article title to look up.")
            title.length > MAX_TITLE -> Request.Invalid("That Wikipedia title is too long. Use at most $MAX_TITLE characters.")
            title.any { it.isISOControl() } -> Request.Invalid("That Wikipedia title contains unsupported control characters.")
            else -> Request.Lookup(title)
        }
    }

    /** Parse MediaWiki formatversion=2. Refuse missing and disambiguation pages. */
    fun parseResponse(body: String): Result {
        if (body.length > MAX_BYTES) return Result.Unavailable
        val root = EmaJson.readObject(body) ?: return Result.Unavailable
        if (root.containsKey("error")) return Result.Unavailable
        val query = root["query"] as? Map<*, *> ?: return Result.Unavailable
        val pages = query["pages"] as? List<*> ?: return Result.Unavailable
        if (pages.size != 1) return Result.Unavailable
        val page = pages.single() as? Map<*, *> ?: return Result.Unavailable
        if (page.containsKey("missing") || page.containsKey("invalid")) return Result.Missing
        val props = page["pageprops"] as? Map<*, *>
        if (props?.containsKey("disambiguation") == true) return Result.Ambiguous
        val id = page["pageid"] as? Long ?: return Result.Unavailable
        if (id <= 0) return Result.Unavailable
        val title = (page["title"] as? String)?.trim().orEmpty()
        val rawExtract = (page["extract"] as? String)?.trim().orEmpty()
        if (title.isBlank() || title.length > 300 || rawExtract.isBlank()) return Result.Unavailable
        val extract = rawExtract.replace(Regex("[\\r\\n\\t ]+"), " ").trim()
        val excerpt = if (extract.length <= 700) extract else {
            val cut = extract.take(700)
            val end = cut.indexOfLast { it == '.' || it == '!' || it == '?' }
            if (end >= 100) cut.substring(0, end + 1) else cut.trimEnd() + "…"
        }
        return Result.Article(title, excerpt, "https://en.wikipedia.org/?curid=$id")
    }

    /** Blocking network operation. Caller must run it off the main thread. */
    fun lookup(title: String): Result {
        if (title.isBlank() || title.length > MAX_TITLE || title.any { it.isISOControl() }) return Result.Unavailable
        val encoded = URLEncoder.encode(title, "UTF-8")
        val endpoint = "$ENDPOINT?action=query&format=json&formatversion=2&redirects=1" +
            "&prop=extracts%7Cpageprops&exintro=1&explaintext=1&exsentences=3&titles=$encoded"
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 7_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "EMA-Android/0.9 (https://github.com/Emmanuel001afk/EMA)")
            if (connection.responseCode != 200) return Result.Unavailable
            val bytes = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_BYTES) return Result.Unavailable
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            parseResponse(String(bytes, StandardCharsets.UTF_8))
        } catch (_: Exception) {
            Result.Unavailable
        } finally {
            connection?.disconnect()
        }
    }

    fun format(result: Result, fetchedAt: Instant = Instant.now()): String = when (result) {
        is Result.Article -> "Wikipedia article: ${result.title}. Excerpt: ${result.excerpt}\n" +
            "Source: ${result.url}\nRetrieved: $fetchedAt. Wikipedia can be incomplete or out of date."
        Result.Missing -> "I couldn't find an exact Wikipedia article title. Try a more specific title or explicitly search the web."
        Result.Ambiguous -> "That Wikipedia title is a disambiguation page. Please specify which subject you mean."
        Result.Unavailable -> "I couldn't retrieve a usable Wikipedia article. Check your connection or try again later."
    }
}
