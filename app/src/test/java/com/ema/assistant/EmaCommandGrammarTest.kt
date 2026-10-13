package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The digital assistant's understanding layer.
 *
 * Every case here is a sentence a user can actually say. These tests exist so
 * that the native layer can be trusted to run FIRST, without AI, and so that a
 * regression in role assignment (app / target / content) is caught before it
 * reaches a phone.
 */
class EmaCommandGrammarTest {

    // ------------------------------------------------------------ messaging

    @Test
    fun messageWithAppAndBodyKeepsRolesApart() {
        val command = EmaCommandGrammar.interpret("send a message to Daniel on WhatsApp saying I am coming")
        val message = command as? EmaCommand.SendMessage
        assertNotNull("expected a SendMessage command", message)
        assertEquals("Daniel", message!!.recipient)
        assertEquals("WhatsApp", message.app)
        assertEquals("I am coming", message.body)
    }

    @Test
    fun messageNounIsNeverSentAsTheBody() {
        val command = EmaCommandGrammar.interpret("send a message to Mum on Telegram saying goodnight") as? EmaCommand.SendMessage
        assertNotNull(command)
        assertEquals("goodnight", command!!.body)
        assertEquals("Mum", command.recipient)
        assertEquals("Telegram", command.app)
    }

    @Test
    fun messageWithoutTheWordMessage() {
        val command = EmaCommandGrammar.interpret("message John on WhatsApp saying hello there") as? EmaCommand.SendMessage
        assertNotNull(command)
        assertEquals("John", command!!.recipient)
        assertEquals("WhatsApp", command.app)
        assertEquals("hello there", command.body)
    }

    @Test
    fun messageWithThatMarker() {
        val command = EmaCommandGrammar.interpret("tell Daniel on WhatsApp that the meeting moved to 5 pm") as? EmaCommand.SendMessage
        assertNotNull(command)
        assertEquals("Daniel", command!!.recipient)
        assertEquals("WhatsApp", command.app)
        assertEquals("the meeting moved to 5 pm", command.body)
    }

    @Test
    fun bodyMayContainTheWordThen() {
        val command = EmaCommandGrammar.interpret("message Sam on WhatsApp saying I will call you then") as? EmaCommand.SendMessage
        assertNotNull(command)
        assertEquals("I will call you then", command!!.body)
    }

    @Test
    fun bodyMayContainTheWordOn() {
        val command = EmaCommandGrammar.interpret("send a message to Ada on WhatsApp saying put it on the table") as? EmaCommand.SendMessage
        assertNotNull(command)
        assertEquals("Ada", command!!.recipient)
        assertEquals("WhatsApp", command.app)
        assertEquals("put it on the table", command.body)
    }

    @Test
    fun bodyFirstFormIsSupported() {
        val command = EmaCommandGrammar.interpret("send hello to Daniel on WhatsApp") as? EmaCommand.SendMessage
        assertNotNull(command)
        assertEquals("Daniel", command!!.recipient)
        assertEquals("hello", command.body)
        assertEquals("WhatsApp", command.app)
    }

    @Test
    fun whatsappVerbImpliesTheApp() {
        val command = EmaCommandGrammar.interpret("whatsapp Daniel saying I am outside") as? EmaCommand.SendMessage
        assertNotNull(command)
        assertEquals("WhatsApp", command!!.app)
        assertEquals("Daniel", command.recipient)
        assertEquals("I am outside", command.body)
    }

    @Test
    fun recipientOnlyIsUnderstoodWithEmptyBody() {
        val command = EmaCommandGrammar.interpret("message Daniel on WhatsApp") as? EmaCommand.SendMessage
        assertNotNull(command)
        assertEquals("Daniel", command!!.recipient)
        assertEquals("WhatsApp", command.app)
        assertEquals("", command.body)
    }

    @Test
    fun leadingAppClauseIsReordered() {
        val command = EmaCommandGrammar.interpret("on WhatsApp, message Daniel saying hi") as? EmaCommand.SendMessage
        assertNotNull(command)
        assertEquals("WhatsApp", command!!.app)
        assertEquals("Daniel", command.recipient)
        assertEquals("hi", command.body)
    }

    @Test
    fun multiWordRecipientIsKeptTogether() {
        val command = EmaCommandGrammar.interpret("message Aunty Blessing on WhatsApp saying good morning") as? EmaCommand.SendMessage
        assertNotNull(command)
        assertEquals("Aunty Blessing", command!!.recipient)
        assertEquals("good morning", command.body)
    }

    @Test
    fun politenessPrefixIsStripped() {
        val command = EmaCommandGrammar.interpret("Hey EMA, please could you message Daniel on WhatsApp saying ok") as? EmaCommand.SendMessage
        assertNotNull(command)
        assertEquals("Daniel", command!!.recipient)
        assertEquals("ok", command.body)
    }

    // ----------------------------------------------------------------- media

    @Test
    fun playSongOnNamedApp() {
        val command = EmaCommandGrammar.interpret("play Arcade on Spotify") as? EmaCommand.PlayMedia
        assertNotNull(command)
        assertEquals("Arcade", command!!.query)
        assertEquals("Spotify", command.app)
    }

    @Test
    fun playKeepsAppWhenSongTitleContainsPreposition() {
        // The last app preposition wins, so "in my library" stays part of the title.
        val command = EmaCommandGrammar.interpret("play the album in my library on Spotify") as? EmaCommand.PlayMedia
        assertNotNull(command)
        assertEquals("Spotify", command!!.app)
    }

    @Test
    fun playOpenAppAndPlayForm() {
        val command = EmaCommandGrammar.interpret("open Spotify and play Lonely") as? EmaCommand.PlayMedia
        assertNotNull(command)
        assertEquals("Lonely", command!!.query)
        assertEquals("Spotify", command.app)
    }

    @Test
    fun playInAppForm() {
        val command = EmaCommandGrammar.interpret("in YouTube play Despacito") as? EmaCommand.PlayMedia
        assertNotNull(command)
        assertEquals("Despacito", command!!.query)
        assertEquals("YouTube", command.app)
    }

    @Test
    fun playStripsSongDecoration() {
        val command = EmaCommandGrammar.interpret("play the song Hello on Spotify") as? EmaCommand.PlayMedia
        assertNotNull(command)
        assertEquals("Hello", command!!.query)
    }

    @Test
    fun listenToIsAPlayCommand() {
        val command = EmaCommandGrammar.interpret("listen to Burna Boy on Spotify") as? EmaCommand.PlayMedia
        assertNotNull(command)
        assertEquals("Burna Boy", command!!.query)
        assertEquals("Spotify", command.app)
    }

    @Test
    fun barePauseIsMediaControlNotMediaQuery() {
        val command = EmaCommandGrammar.interpret("pause") as? EmaCommand.MediaControl
        assertNotNull(command)
        assertEquals(EmaCommand.MediaControl.Action.PAUSE, command!!.action)
    }

    @Test
    fun nextAndPreviousAreMediaControls() {
        assertEquals(
            EmaCommand.MediaControl.Action.NEXT,
            (EmaCommandGrammar.interpret("next song") as? EmaCommand.MediaControl)?.action
        )
        assertEquals(
            EmaCommand.MediaControl.Action.PREVIOUS,
            (EmaCommandGrammar.interpret("previous track") as? EmaCommand.MediaControl)?.action
        )
        assertEquals(
            EmaCommand.MediaControl.Action.STOP,
            (EmaCommandGrammar.interpret("stop the music") as? EmaCommand.MediaControl)?.action
        )
    }

    @Test
    fun startTimerIsNotAMediaRequest() {
        // Regression guard: "start" must never be treated as a play verb.
        val command = EmaCommandGrammar.interpret("start the timer for 10 minutes")
        assertTrue("expected a timer, got $command", command is EmaCommand.SetTimer)
    }

    // ------------------------------------------------------------------ call

    @Test
    fun callContact() {
        val command = EmaCommandGrammar.interpret("call Daniel") as? EmaCommand.CallContact
        assertNotNull(command)
        assertEquals("Daniel", command!!.name)
        assertNull(command.sim)
    }

    @Test
    fun callWithSimSelection() {
        val command = EmaCommandGrammar.interpret("call Daniel with SIM 2") as? EmaCommand.CallContact
        assertNotNull(command)
        assertEquals("Daniel", command!!.name)
        assertEquals(2, command.sim)
    }

    @Test
    fun dialIsACall() {
        val command = EmaCommandGrammar.interpret("dial 08012345678") as? EmaCommand.CallContact
        assertNotNull(command)
        assertEquals("08012345678", command!!.name)
    }

    // ------------------------------------------------------------------ time

    @Test
    fun alarmWithExplicitTime() {
        val command = EmaCommandGrammar.interpret("set an alarm for 6:30 am") as? EmaCommand.SetAlarm
        assertNotNull(command)
        assertEquals("6:30 am", command!!.timeText)
    }

    @Test
    fun alarmInsideClockAppIsStillAnAlarm() {
        val command = EmaCommandGrammar.interpret("set an alarm in clock for 7 am") as? EmaCommand.SetAlarm
        assertNotNull(command)
        assertTrue(command!!.timeText.contains("7"))
    }

    @Test
    fun alarmWithoutATimeIsNotAnAlarmCommand() {
        assertNull(EmaCommandGrammar.interpret("open the alarm clock app") as? EmaCommand.SetAlarm)
    }

    @Test
    fun timerWithDuration() {
        val command = EmaCommandGrammar.interpret("set a timer for 10 minutes") as? EmaCommand.SetTimer
        assertNotNull(command)
        assertEquals("10 minutes", command!!.durationText)
    }

    @Test
    fun reminderWithTextAndTime() {
        val command = EmaCommandGrammar.interpret("remind me to call Mum at 5 pm") as? EmaCommand.SetReminder
        assertNotNull(command)
        assertEquals("call Mum", command!!.text)
        assertEquals("5 pm", command.timeText)
    }

    @Test
    fun reminderWithRelativeTime() {
        val command = EmaCommandGrammar.interpret("remind me to take my medicine in 30 minutes") as? EmaCommand.SetReminder
        assertNotNull(command)
        assertEquals("take my medicine", command!!.text)
        assertEquals("30 minutes", command.timeText)
    }

    @Test
    fun reminderTimeFirstForm() {
        val command = EmaCommandGrammar.interpret("remind me at 4 pm to call Daniel") as? EmaCommand.SetReminder
        assertNotNull(command)
        assertEquals("call Daniel", command!!.text)
    }

    @Test
    fun wakeMeIsAnAlarm() {
        val command = EmaCommandGrammar.interpret("wake me at 5 am") as? EmaCommand.SetAlarm
        assertNotNull(command)
        assertEquals("5 am", command!!.timeText)
    }

    // ---------------------------------------------------------- apps / places

    @Test
    fun openApp() {
        val command = EmaCommandGrammar.interpret("open WhatsApp") as? EmaCommand.OpenApp
        assertNotNull(command)
        assertEquals("WhatsApp", command!!.app)
    }

    @Test
    fun launchIsOpen() {
        val command = EmaCommandGrammar.interpret("launch Spotify") as? EmaCommand.OpenApp
        assertNotNull(command)
        assertEquals("Spotify", command!!.app)
    }

    @Test
    fun openAppStripsDecoration() {
        val command = EmaCommandGrammar.interpret("open the YouTube app") as? EmaCommand.OpenApp
        assertNotNull(command)
        assertEquals("YouTube", command!!.app)
    }

    @Test
    fun sectionInsideAppIsResolvedAsAppSection() {
        val command = EmaCommandGrammar.interpret("go to playlists in Spotify") as? EmaCommand.OpenAppSection
        assertNotNull(command)
        assertEquals("Spotify", command!!.app)
        assertEquals("playlists", command.section)
    }

    @Test
    fun settingsRoot() {
        val command = EmaCommandGrammar.interpret("go to settings") as? EmaCommand.OpenSettingsPage
        assertNotNull(command)
        assertEquals("", command!!.target)
    }

    @Test
    fun settingsPage() {
        val command = EmaCommandGrammar.interpret("go to battery in settings") as? EmaCommand.OpenSettingsPage
        assertNotNull(command)
        assertEquals("battery", command!!.target)
    }

    @Test
    fun navigateToPlace() {
        val command = EmaCommandGrammar.interpret("navigate to Lekki Phase 1") as? EmaCommand.NavigateTo
        assertNotNull(command)
        assertEquals("Lekki Phase 1", command!!.destination)
    }

    // ---------------------------------------------------------------- search

    @Test
    fun searchInsideNamedApp() {
        val command = EmaCommandGrammar.interpret("search for cats on YouTube") as? EmaCommand.SearchInApp
        assertNotNull(command)
        assertEquals("cats", command!!.query)
        assertEquals("YouTube", command.app)
    }

    @Test
    fun appFirstSearchForm() {
        val command = EmaCommandGrammar.interpret("YouTube search for cats") as? EmaCommand.SearchInApp
        assertNotNull(command)
        assertEquals("cats", command!!.query)
        assertEquals("YouTube", command.app)
    }

    @Test
    fun openAndSearchForm() {
        val command = EmaCommandGrammar.interpret("open YouTube and search for cats") as? EmaCommand.SearchInApp
        assertNotNull(command)
        assertEquals("cats", command!!.query)
        assertEquals("YouTube", command.app)
    }

    @Test
    fun searchWithFirstResultSelection() {
        val command = EmaCommandGrammar.interpret("search for cats on YouTube and open the first result") as? EmaCommand.SearchInApp
        assertNotNull(command)
        assertTrue(command!!.selectFirst)
    }

    @Test
    fun googleSearchIsAWebSearch() {
        val command = EmaCommandGrammar.interpret("search with google best restaurants near me") as? EmaCommand.WebSearch
        assertNotNull(command)
        assertEquals("best restaurants near me", command!!.query)
    }

    // -------------------------------------------------------------- contacts

    @Test
    fun saveContactNumberFirst() {
        val command = EmaCommandGrammar.interpret("save 08012345678 as Daniel") as? EmaCommand.SaveContact
        assertNotNull(command)
        assertEquals("Daniel", command!!.name)
        assertEquals("08012345678", command.number)
    }

    @Test
    fun saveContactNameFirst() {
        val command = EmaCommandGrammar.interpret("save Daniel's number 08012345678") as? EmaCommand.SaveContact
        assertNotNull(command)
        assertEquals("Daniel", command!!.name)
    }

    // ----------------------------------------------------------- canonical

    @Test
    fun canonicalFormRoundTripsThroughTheGrammar() {
        val original = EmaCommandGrammar.interpret("message Daniel on WhatsApp saying hello")
        assertNotNull(original)
        val canonical = original!!.toCanonical()
        val again = EmaCommandGrammar.interpret(canonical) as? EmaCommand.SendMessage
        assertNotNull("canonical form '$canonical' must be re-interpretable", again)
        assertEquals("Daniel", again!!.recipient)
        assertEquals("hello", again.body)
    }

    @Test
    fun canonicalPlayFormRoundTrips() {
        val canonical = EmaCommandGrammar.interpret("play Arcade on Spotify")!!.toCanonical()
        val again = EmaCommandGrammar.interpret(canonical) as? EmaCommand.PlayMedia
        assertNotNull(again)
        assertEquals("Arcade", again!!.query)
        assertEquals("Spotify", again.app)
    }

    @Test
    fun unknownRequestReturnsNullSoItCanEscalate() {
        assertNull(EmaCommandGrammar.interpret("what is the capital of Ghana"))
        assertNull(EmaCommandGrammar.interpret("explain how photosynthesis works"))
    }

    @Test
    fun blankInputIsRejected() {
        assertNull(EmaCommandGrammar.interpret(""))
        assertNull(EmaCommandGrammar.interpret("   "))
    }
}
