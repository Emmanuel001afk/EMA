package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline safety net. These are requests EmaCommandGrammar does not cover,
 * and the point of the layer is that EMA still acts on the device instead of
 * dead-ending on an unreachable AI service.
 */
class EmaNativeFallbackInterpreterTest {

    private val installed = listOf(
        EmaAppResolver.App("WhatsApp", "com.whatsapp"),
        EmaAppResolver.App("Spotify", "com.spotify.music"),
        EmaAppResolver.App("YouTube", "com.google.android.youtube"),
        EmaAppResolver.App("Instagram", "com.instagram.android"),
        EmaAppResolver.App("Chrome", "com.android.chrome"),
        EmaAppResolver.App("Settings", "com.android.settings"),
        EmaAppResolver.App("Netflix", "com.netflix.mediaclient")
    )

    private fun run(text: String) = EmaNativeFallbackInterpreter.interpret(text, installed)

    @Test
    fun delegatedPlayIsUnderstood() {
        val result = run("get Spotify to play some music")
        assertNotNull(result)
        val play = result!!.command as? EmaCommand.PlayMedia
        assertNotNull("expected PlayMedia, got ${result.command}", play)
        assertEquals("com.spotify.music", EmaAppResolver.resolve(play!!.app!!, installed)?.app?.packageName)
    }

    @Test
    fun delegatedSearchIsUnderstood() {
        val result = run("use YouTube to find cat videos")
        assertNotNull(result)
        val search = result!!.command as? EmaCommand.SearchInApp
        assertNotNull("expected SearchInApp, got ${result.command}", search)
        assertTrue(search!!.query.isNotBlank())
        assertEquals("YouTube", search.app)
    }

    @Test
    fun bareAppMentionOpensTheApp() {
        val result = run("Netflix")
        assertNotNull(result)
        assertEquals(EmaCommand.OpenApp("Netflix"), result!!.command)
    }

    @Test
    fun appMentionWithDecorationStillOpens() {
        val result = run("instagram please")
        assertNotNull(result)
        assertTrue(result!!.command is EmaCommand.OpenApp)
    }

    @Test
    fun makeAppDoSomethingIsUnderstood() {
        val result = run("make Chrome open the news site")
        assertNotNull(result)
        assertTrue("expected an app command, got ${result!!.command}", result.command is EmaCommand.OpenAppSection || result.command is EmaCommand.OpenApp)
    }

    @Test
    fun knowledgeQuestionsAreNeverTurnedIntoDeviceActions() {
        assertNull(run("what is the capital of Ghana"))
        assertNull(run("explain how photosynthesis works"))
        assertNull(run("who invented the telephone"))
        assertNull(run("how do I cook jollof rice"))
        assertNull(run("tell me about the Eiffel Tower"))
    }

    @Test
    fun knowledgeQuestionThatMentionsAnAppDoesNotLaunchIt() {
        // "spotify" appears, but the user is asking a question about it.
        assertNull(run("what is spotify premium"))
    }

    @Test
    fun playWithoutAnAppStillProducesAPlayCommand() {
        val result = run("play something relaxing")
        assertNotNull(result)
        val play = result!!.command as? EmaCommand.PlayMedia
        assertNotNull(play)
        assertNull(play!!.app)
    }

    @Test
    fun searchWithoutAnAppStillProducesASearchCommand() {
        val result = run("look for nearby pharmacies")
        assertNotNull(result)
        assertTrue(result!!.command is EmaCommand.SearchInApp)
    }

    @Test
    fun uninstalledAppIsNotInvented() {
        // Nothing is installed under this name, and there is no device verb,
        // so the layer must decline rather than fabricate an app.
        assertNull(run("FantasyAppThatDoesNotExist please do the thing"))
    }

    @Test
    fun emptyInputIsRejected() {
        assertNull(run(""))
        assertNull(run("   "))
        assertNull(EmaNativeFallbackInterpreter.interpret("open WhatsApp", emptyList()))
    }

    @Test
    fun delegatedMessageOpensTheRightAppWhenNoRecipientIsGiven() {
        val result = run("get WhatsApp to send something")
        assertNotNull(result)
        val command = result!!.command
        assertTrue(
            "expected a message or an app open, got $command",
            command is EmaCommand.SendMessage || command is EmaCommand.OpenApp
        )
    }

    @Test
    fun resultExplainsHowItWasResolved() {
        val result = run("Netflix")
        assertNotNull(result)
        assertTrue(result!!.via.isNotBlank())
    }
}
