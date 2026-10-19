package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A device setting may only be applied when the WHOLE utterance is a setting
 * command. Otherwise "send John a message saying increase the brightness"
 * changes the brightness instead of sending the message.
 */
class EmaDeviceSettingScopeTest {

    private fun setting(command: String) = EmaSystemCommandVocabulary.parseDeviceSetting(command)

    @Test
    fun aMessageThatMentionsASettingIsAMessage() {
        assertNull(setting("send John a message saying increase the brightness"))
        assertNull(setting("send John a message saying turn on airplane mode"))
        assertNull(setting("message John saying turn on Do Not Disturb"))
        assertNull(setting("text John saying increase the brightness"))
    }

    @Test
    fun aSearchOrReminderThatMentionsASettingIsNotASetting() {
        assertNull(setting("search for increase the brightness"))
        assertNull(setting("remind me to turn on airplane mode at 7 pm"))
        assertNull(setting("call John and increase the brightness"))
    }

    @Test
    fun aRealSettingCommandIsStillASetting() {
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Kind.DND,
            setting("turn on Do Not Disturb")?.kind
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Kind.DND,
            setting("turn on dnd")?.kind
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Kind.BRIGHTNESS,
            setting("increase brightness to maximum")?.kind
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Kind.AIRPLANE,
            setting("turn on airplane mode")?.kind
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Kind.AIRPLANE,
            setting("switch flight mode off")?.kind
        )
    }

    @Test
    fun theWakeWordAndPolitenessDoNotHideASetting() {
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Kind.BRIGHTNESS,
            setting("hey ema please increase brightness to maximum")?.kind
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Kind.DND,
            setting("EMA turn on do not disturb")?.kind
        )
    }

    @Test
    fun theActionAndTheValueAreStillUnderstood() {
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Action.MAX,
            setting("increase brightness to maximum")?.action
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Action.SET,
            setting("set brightness to 40 percent")?.action
        )
        assertEquals(40, setting("set brightness to 40 percent")?.value)
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Action.OFF,
            setting("turn off airplane mode")?.action
        )
        assertEquals(
            EmaSystemCommandVocabulary.DeviceSetting.Action.INCREASE,
            setting("raise brightness")?.action
        )
    }
}
