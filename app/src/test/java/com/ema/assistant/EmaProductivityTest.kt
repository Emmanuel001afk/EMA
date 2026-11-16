package com.ema.assistant

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Offline regression contracts: no framework, network, AI or permission mocks. */
class EmaProductivityTest {
    @Before fun reset() = EmaDialogueState.clear()

    @Test fun listLifecycleAndPersistence() {
        val add = EmaWorkspace.parse("add milk, bread, and eggs to my shopping list")!!
        val result = EmaWorkspace().apply(add, 100)
        assertTrue(result.changed)
        assertEquals(3, result.workspace.lists["shopping"]!!.size)
        assertEquals(result.workspace, EmaWorkspace.decode(EmaWorkspace.encode(result.workspace)))
        val checked = result.workspace.apply(EmaWorkspace.Op.CheckItem("shopping", "milk", true))
        assertTrue(checked.workspace.lists["shopping"]!!.first().done)
        val removed = checked.workspace.apply(EmaWorkspace.Op.RemoveItem("shopping", "bread"))
        assertEquals(2, removed.workspace.lists["shopping"]!!.size)
        assertTrue(removed.workspace.exportList("shopping")!!.contains("[x] milk"))
    }

    @Test fun duplicatesAndCompoundItems() {
        val w = EmaWorkspace().apply(EmaWorkspace.Op.AddItems("shopping", listOf("milk"))).workspace
        assertFalse(w.apply(EmaWorkspace.Op.AddItems("shopping", listOf("MILK"))).changed)
        assertEquals(listOf("fish and chips"), EmaWorkspace.splitItems("fish and chips"))
        assertEquals(listOf("bread", "eggs"), EmaWorkspace.splitItems("bread and eggs"))
        assertTrue(EmaWorkspace.parse("add eggs too", "shopping") is EmaWorkspace.Op.AddItems)
        assertNull(EmaWorkspace.parse("add eggs too"))
    }

    @Test fun doesNotStealMessagesOrReminders() {
        for (text in listOf("message Ada saying add milk to my shopping list", "remind me to buy milk", "search for take a note", "remember that my cat is blue")) {
            assertNull(text, EmaWorkspace.parse(text))
            assertNull(text, EmaWritingTools.parseCommand(text))
        }
    }

    @Test fun secretsAreRefusedAcrossWorkspace() {
        for (text in listOf("my password is abc", "PIN 1234", "OTP code 123456", "card number 4444 5555 6666 7777")) {
            assertFalse(text, EmaWorkspace().apply(EmaWorkspace.Op.AddNote(text)).changed)
            assertFalse(text, EmaWorkspace().apply(EmaWorkspace.Op.AddItems("shopping", listOf(text))).changed)
            assertFalse(text, EmaWorkspace().apply(EmaWorkspace.Op.SaveParking(text)).changed)
        }
    }

    @Test fun notesPinSearchAppendAndEscapeRoundTrip() {
        var w = EmaWorkspace().apply(EmaWorkspace.Op.AddNote("Project alpha\nBring a pen\tand \\paper"), 50).workspace
        w = w.apply(EmaWorkspace.Op.PinLastNote(true)).workspace
        w = w.apply(EmaWorkspace.Op.AppendLastNote("Review tomorrow")).workspace
        assertTrue(w.notes.single().pinned)
        assertTrue(w.apply(EmaWorkspace.Op.FindNotes("project")).reply.contains("Found 1"))
        assertEquals(w, EmaWorkspace.decode(EmaWorkspace.encode(w)))
        assertTrue(w.apply(EmaWorkspace.Op.DeleteLastNote).workspace.notes.isEmpty())
    }

    @Test fun parkingRecallIsObservedNotInvented() {
        val w = EmaWorkspace().apply(EmaWorkspace.Op.SaveParking("Level 2 Zone B"), 1_000).workspace
        assertTrue(w.apply(EmaWorkspace.Op.RecallParking, 121_000).reply.contains("2 minutes ago"))
        assertTrue(EmaWorkspace.parse("where did I park?") is EmaWorkspace.Op.RecallParking)
    }

    @Test fun capacityAndMalformedDataAreBounded() {
        val notes = List(EmaWorkspace.MAX_NOTES) { EmaWorkspace.Note("note $it") }
        assertFalse(EmaWorkspace(notes = notes).apply(EmaWorkspace.Op.AddNote("new")).changed)
        val rows = (0..100).joinToString("\n") { "item\tlist$it\t0\t1\tx" }
        assertEquals(EmaWorkspace.MAX_LISTS, EmaWorkspace.decode("ema_workspace=1\n$rows").lists.size)
        assertEquals(EmaWorkspace(), EmaWorkspace.decode("future_schema=2"))
        assertTrue(EmaWorkspace.decode("ema_workspace=1\nnote\t0\t1\tpassword is abc").notes.isEmpty())
    }

    @Test fun dialogueExpiresAndUndoIsBounded() {
        EmaDialogueState.recordActiveList("shopping", 100)
        EmaDialogueState.recordDocument("screen", "A normal document", 100)
        assertEquals("shopping", EmaDialogueState.snapshot(101).activeList!!.name)
        assertNull(EmaDialogueState.snapshot(EmaDialogueState.TTL_MS + 101).document)
        repeat(10) { EmaDialogueState.pushUndo(EmaDialogueState.UndoEntry.WorkspaceSnapshot(EmaWorkspace(), "restore", 100)) }
        var count = 0
        while (EmaDialogueState.popUndo(101) != null) count++
        assertEquals(6, count)
    }

    @Test fun sensitiveRepliesDoNotLeaveOldContext() {
        EmaDialogueState.recordReply("hello", nowMs = 100)
        EmaDialogueState.recordReply("secret", sensitive = true, nowMs = 101)
        assertNull(EmaDialogueState.snapshot(102).lastReply)
        EmaDialogueState.recordDocument("screen", "OTP 123456", 100)
        assertNull(EmaDialogueState.snapshot(101).document)
    }

    @Test fun stopwatchSupportsPauseAndResume() {
        EmaDialogueState.stopwatchStart(1_000)
        assertTrue(EmaDialogueState.stopwatchStatus(3_500).contains("2"))
        EmaDialogueState.stopwatchPauseOrStop(nowMs = 3_500)
        val paused = EmaDialogueState.stopwatchStatus(8_000)
        assertEquals(paused, EmaDialogueState.stopwatchStatus(12_000))
        EmaDialogueState.stopwatchStart(12_000)
        assertNotEquals(paused, EmaDialogueState.stopwatchStatus(15_000))
    }

    @Test fun writingIsExplicitAndNeverClaimsSendingOrCopying() {
        val proofread = EmaWritingTools.rewrite("teh meeting is tommorow", EmaWritingTools.Tone.PROOFREAD)
        assertFalse(proofread.transformed.contains("teh"))
        assertTrue(proofread.transformed.contains("tomorrow"))
        assertTrue(EmaWritingTools.parseCommand("proofread: teh cat") is EmaWritingTools.Command.TransformInline)
        assertEquals(3, EmaWritingTools.analyze("one two three").words)
        assertEquals(0, EmaWritingTools.analyze("").words)
        val draft = EmaWritingTools.draft("draft a running late text")!!
        assertFalse(draft.summary.contains("copied"))
        assertTrue(draft.summary.contains("not sent"))
    }

    @Test fun screenAnswersStayGrounded() {
        val source = "The workshop starts Friday at 10:30. Bring your notebook. Contact ada@example.com for details."
        assertTrue(EmaScreenIntelligence.extractEntities(source).any { it.kind == EmaScreenIntelligence.EntityKind.EMAIL && it.value == "ada@example.com" })
        assertTrue(EmaScreenIntelligence.summarize(source).contains("workshop"))
        assertFalse(EmaScreenIntelligence.answerAboutText("What is the weather in Tokyo?", "The cat is asleep.").matched)
        assertFalse(EmaScreenIntelligence.answerAboutText("When?", "").matched)
        assertEquals("", EmaScreenIntelligence.summarize(""))
    }

    private fun notification(key: String, title: String, text: String = "Hello", app: String = "Chat") =
        EmaNotificationDigest.Item(key, "com.chat", app, title, text, canReply = true)

    @Test fun notificationTriageAndAmbiguity() {
        val ada = notification("1", "Ada")
        val ben = notification("2", "Ben")
        assertEquals(EmaNotificationDigest.Priority.MESSAGE, EmaNotificationDigest.classify(ada))
        assertTrue(EmaNotificationDigest.resolveReplyTarget(listOf(ada, ben), "Chat") is EmaNotificationDigest.ReplyMatch.Ambiguous)
        assertTrue(EmaNotificationDigest.resolveReplyTarget(listOf(ada, ben), "Ada") is EmaNotificationDigest.ReplyMatch.Single)
        assertTrue(EmaNotificationDigest.resolveReplyTarget(emptyList(), null) is EmaNotificationDigest.ReplyMatch.None)
        assertTrue(EmaNotificationDigest.filterByQuery(listOf(ada, ben), "Ada").contains("1 notification"))
    }

    @Test fun notificationCodesAreRedactedInBothDirectionsAndTitles() {
        for (text in listOf("OTP code: 123456", "123456 is your verification code")) {
            assertFalse(EmaNotificationDigest.redactSensitiveCodes(text).contains("123456"))
            val n = notification("1", text, text)
            assertFalse(EmaNotificationDigest.summarize(listOf(n)).contains("123456"))
            assertFalse(EmaNotificationDigest.summarize(listOf(n), brief = true).contains("123456"))
        }
    }

    @Test fun routinesAreBoundedAndRoundTrip() {
        val action = EmaNamedRoutines.parse("create a routine called study: turn on DND; open Calculator") as EmaNamedRoutines.Action.SaveCustom
        assertEquals(2, action.steps.size)
        val routines = mapOf(action.name to EmaNamedRoutines.CustomRoutine(action.name, action.steps))
        assertEquals(routines, EmaNamedRoutines.decode(EmaNamedRoutines.encode(routines)))
        assertTrue(EmaNamedRoutines.parse("run study routine", routines) is EmaNamedRoutines.Action.RunCustom)
        assertTrue(EmaNamedRoutines.parse("create a routine called loop: run loop") is EmaNamedRoutines.Action.Refused)
        assertTrue(EmaNamedRoutines.parse("create a routine called private: type password abc") is EmaNamedRoutines.Action.Refused)
        assertNull(EmaNamedRoutines.parse("message Ada saying good morning"))
    }

    @Test fun briefingOnlyUsesSuppliedFacts() {
        val text = EmaNamedRoutines.composeBriefing(EmaNamedRoutines.BriefingInput(8, "08:00", "Monday", openTodoCount = 1, topTodoItems = listOf("Review report")))
        assertTrue(text.contains("Good morning"))
        assertTrue(text.contains("Review report"))
        assertFalse(text.contains("Battery"))
        assertFalse(text.contains("weather", ignoreCase = true))
    }
}
