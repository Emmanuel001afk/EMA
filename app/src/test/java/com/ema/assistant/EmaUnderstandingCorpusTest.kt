package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Data-driven natural-language understanding test. Every line of
 * src/test/resources/ema_understanding_corpus.tsv is a request and the frame
 * it must produce. All mismatches are reported together.
 */
class EmaUnderstandingCorpusTest {

    private val apps = listOf(
        "WhatsApp", "WhatsApp Business", "YouTube", "YouTube Music", "Spotify", "Messages", "Chrome",
        "Gmail", "Maps", "Settings", "Telegram", "Camera", "Clock", "My Portal", "Acme Staff Chat"
    ).map { EmaAppResolver.App(it, "pkg." + it.lowercase().replace(" ", "")) }

    private fun env(context: String): EmaSemanticParser.Env {
        val base = EmaSemanticParser.Env(apps, null, defaultApps = mapOf("video" to "YouTube"))
        if (context == "-") return base
        val parts = context.split(':')
        require(parts[0] == "task") { "bad context $context" }
        return base.copy(activeTask = EmaActiveTask(parts[1], parts[2], lastWasPlay = parts.getOrNull(3) == "play", updatedAtMs = 0))
    }

    private fun corpus(): List<String> {
        val name = "ema_understanding_corpus.tsv"
        javaClass.classLoader?.getResourceAsStream(name)?.use { return it.bufferedReader().readLines() }
        val file = listOf("src/test/resources/$name", "app/src/test/resources/$name").map(::File).first { it.exists() }
        return file.readLines()
    }

    @Test fun everyCorpusLineProducesItsExpectedFrame() {
        val failures = mutableListOf<String>()
        var count = 0
        for (line in corpus()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val cols = line.split('\t')
            assertEquals("malformed corpus line: $line", 3, cols.size)
            count++
            val actual = render(EmaSemanticParser.understand(cols[1], env(cols[0])))
            if (actual != cols[2]) failures += "  \"${cols[1]}\" [${cols[0]}]\n      expected ${cols[2]}\n      actual   $actual"
        }
        assertTrue("corpus is unexpectedly small", count >= 40)
        assertTrue("${failures.size} of $count corpus lines failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    companion object {
        fun render(result: EmaSemanticParser.Result): String = when (result) {
            is EmaSemanticParser.Result.NotHandled -> "NOT_HANDLED"
            is EmaSemanticParser.Result.Clarify ->
                if (result.options.isEmpty()) "CLARIFY[${result.missing}]" else "CLARIFY[${result.missing}|${result.options.joinToString(",")}]"
            is EmaSemanticParser.Result.Plan -> result.steps.joinToString(" | ") { step(it) }
        }

        private fun step(s: EmaSemanticParser.Step): String {
            fun a(app: String?) = app ?: "-"
            return when (val c = s.command) {
                null -> "LEGACY[${s.legacyText}]"
                is EmaCommand.SendMessage -> "MSG[${a(c.app)}|${c.recipient}|${c.body}]"
                is EmaCommand.SearchInApp -> "SEARCH[${a(c.app)}|${c.query}]"
                is EmaCommand.SelectResult -> "SELECT[${a(c.app)}|${sel(c.selection)}|${if (c.play) "play" else "open"}${if (s.continuation) "|cont" else ""}]"
                is EmaCommand.OpenApp -> "OPEN[${c.app}]"
                is EmaCommand.OpenAppSection -> "SECTION[${c.app}|${c.section}]"
                is EmaCommand.PlayMedia -> "PLAY[${a(c.app)}|${c.query}]"
                is EmaCommand.MediaControl -> "MEDIA[${c.action}]"
                else -> c.toCanonical()
            }
        }

        private fun sel(s: EmaSelection) = when (s) {
            is EmaSelection.Ordinal -> "Ordinal(${s.position})"
            is EmaSelection.Titled -> "Titled(${s.text})"
            else -> s.javaClass.simpleName
        }
    }
}
