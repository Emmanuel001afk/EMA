package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class EmaWikipediaLookupTest {
    @Test fun explicitLookupOnly() {
        assertEquals(EmaWikipediaLookup.Request.Lookup("Lagos"), EmaWikipediaLookup.parse("Wikipedia lookup Lagos"))
        assertEquals(EmaWikipediaLookup.Request.Lookup("Ada Lovelace"), EmaWikipediaLookup.parse("look up Ada Lovelace on Wikipedia"))
        assertEquals(EmaWikipediaLookup.Request.Lookup("Lagos"), EmaWikipediaLookup.parse("look up on Wikipedia: Lagos"))
        assertTrue(EmaWikipediaLookup.parse("wikipedia lookup") is EmaWikipediaLookup.Request.Invalid)
        assertTrue(EmaWikipediaLookup.parse("wikipedia lookup " + "x".repeat(161)) is EmaWikipediaLookup.Request.Invalid)
        assertNull(EmaWikipediaLookup.parse("what is Lagos?"))
        assertNull(EmaWikipediaLookup.parse("message Ada saying look up Lagos on Wikipedia"))
        assertNull(EmaWikipediaLookup.parse("don't look up Lagos on Wikipedia"))
    }

    @Test fun extractsArticleAndConstructsOwnSafeSourceUrl() {
        val body = """{"query":{"pages":[{"pageid":123,"title":"Lagos","extract":"Lagos is a city. A second sentence.","fullurl":"https://evil.example/"}]}}"""
        val article = EmaWikipediaLookup.parseResponse(body) as EmaWikipediaLookup.Result.Article
        assertEquals("Lagos", article.title)
        assertEquals("Lagos is a city. A second sentence.", article.excerpt)
        assertEquals("https://en.wikipedia.org/?curid=123", article.url)
        val formatted = EmaWikipediaLookup.format(article, Instant.parse("2026-11-23T12:00:00Z"))
        assertTrue(formatted.contains("Source: https://en.wikipedia.org/?curid=123"))
        assertTrue(formatted.contains("2026-11-23T12:00:00Z"))
    }

    @Test fun missingAmbiguousAndBadResponsesDoNotBecomeAnswers() {
        assertEquals(EmaWikipediaLookup.Result.Missing, EmaWikipediaLookup.parseResponse("""{"query":{"pages":[{"title":"X","missing":true}]}}"""))
        assertEquals(EmaWikipediaLookup.Result.Ambiguous, EmaWikipediaLookup.parseResponse("""{"query":{"pages":[{"pageid":7,"title":"X","pageprops":{"disambiguation":""},"extract":"Many meanings"}]}}"""))
        for (body in listOf("", "{}", "{\"error\":{}}", """{"query":{"pages":[]}}""", """{"query":{"pages":[{"pageid":-1,"title":"X","extract":"Fact"}]}}""")) {
            assertEquals(body, EmaWikipediaLookup.Result.Unavailable, EmaWikipediaLookup.parseResponse(body))
        }
    }

    @Test fun longExcerptsAreBoundedAndStayExtractive() {
        val text = "A long sentence about a subject. ".repeat(80)
        val body = """{"query":{"pages":[{"pageid":123,"title":"Test","extract":"$text"}]}}"""
        val article = EmaWikipediaLookup.parseResponse(body) as EmaWikipediaLookup.Result.Article
        assertTrue(article.excerpt.length <= 701)
        assertTrue(text.startsWith(article.excerpt))
    }
}
