package com.ema.assistant

import org.junit.Assert.*
import org.junit.Test

class EmaDeviceKnowledgeTest {
    @Test fun everyFeatureCanBeFoundExplainedAndOpenedWithoutMutation() {
        for (f in EmaDeviceKnowledge.features) for (alias in f.aliases) {
            assertEquals(alias, f, EmaDeviceKnowledge.find(alias))
            assertEquals(alias, EmaDeviceKnowledge.Operation.OPEN, EmaDeviceKnowledge.parse("open $alias settings")?.operation)
            assertEquals(alias, EmaDeviceKnowledge.Operation.EXPLAIN, EmaDeviceKnowledge.parse("what is $alias?")?.operation)
            assertEquals(alias, f, EmaDeviceKnowledge.parse("search settings for $alias")?.feature)
        }
    }
    @Test fun ringerModesAndRotationKeepTheirOwnMeaning() {
        for (name in listOf("silent mode", "vibrate mode", "auto rotate")) {
            assertEquals(EmaDeviceKnowledge.Operation.ENABLE, EmaDeviceKnowledge.parse("turn $name on")?.operation)
            assertEquals(EmaDeviceKnowledge.Operation.DISABLE, EmaDeviceKnowledge.parse("switch $name off")?.operation)
        }
        assertNotEquals(EmaDeviceKnowledge.find("silent mode"), EmaDeviceKnowledge.find("dnd"))
        assertNotEquals(EmaDeviceKnowledge.find("auto rotate"), EmaDeviceKnowledge.find("display"))
    }
    @Test fun stateQuestionsAreNeverMutations() {
        for (name in listOf("wi-fi", "bluetooth", "dnd", "location", "battery saver")) {
            for (word in listOf("on", "off", "enabled", "disabled"))
                assertEquals(EmaDeviceKnowledge.Operation.STATUS, EmaDeviceKnowledge.parse("is $name $word?")?.operation)
            assertEquals(EmaDeviceKnowledge.Operation.ENABLE, EmaDeviceKnowledge.parse("activate $name")?.operation)
            assertEquals(EmaDeviceKnowledge.Operation.DISABLE, EmaDeviceKnowledge.parse("deactivate $name")?.operation)
        }
    }
    @Test fun mentionsInsidePayloadsCannotActivateSettings() {
        for (raw in listOf("message Ada saying turn on wifi", "search YouTube for bluetooth", "don't turn off wifi", "open wifi and erase files", "turn on wifi but don't connect"))
            assertNull(raw, EmaDeviceKnowledge.parse(raw))
    }
    @Test fun dndNameIsNotANegativeInstruction() {
        assertEquals("turn on dnd", EmaLanguageUnderstanding.assess("do not disturb on").canonical)
        assertEquals(EmaLanguageUnderstanding.Act.REQUEST, EmaLanguageUnderstanding.assess("do not disturb off").act)
        assertEquals(EmaLanguageUnderstanding.Act.NEGATED, EmaLanguageUnderstanding.assess("do not turn on dnd").act)
    }
    @Test fun pairingAndNetworkWorkflowsStaySeparateFromEnablingTheRadio() {
        for (raw in listOf("pair bluetooth headphones", "connect to wifi Home", "forget wifi Home", "scan for nearby devices"))
            assertEquals(raw, EmaDeviceKnowledge.Operation.WORKFLOW, EmaDeviceKnowledge.parse(raw)?.operation)
        assertNull(EmaDeviceKnowledge.parse("connect to my bank"))
    }
}
