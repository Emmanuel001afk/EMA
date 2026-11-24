package com.ema.assistant

import android.content.ClipboardManager
import android.content.Context
import java.util.Locale

/** Android adapter; reasoning engines remain pure Kotlin and never invoke AI. */
class EmaProductivityEngine(private val context: Context) {
    fun briefing(): String {
        val now = java.time.ZonedDateTime.now()
        val workspace = EmaPersonalWorkspaceStore.load(context)
        val todos = workspace.openTodoItems()
        val reminders = EmaReminderScheduler.pending(context).map { it.text }
        val notifications = if (EmaNotificationListenerService.isConnected())
            EmaNotificationDigest.summarize(EmaNotificationListenerService.digestItems(), brief = true)
        else "Notifications unavailable: EMA notification access is not connected"
        return EmaNamedRoutines.composeBriefing(EmaNamedRoutines.BriefingInput(
            hourOfDay = now.hour,
            formattedTime = now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
            formattedDate = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM")),
            upcomingReminders = reminders, openTodoCount = todos.first, topTodoItems = todos.second,
            notificationSummary = notifications, parkingSpot = workspace.parking?.location
        ))
    }

    /** OCR is read-only and is used only for explicit screen requests with no accessible text. */
    fun answerScreenRequest(command: String, done: (String) -> Unit): Boolean {
        val lower = command.trim().trimEnd('.', '?', '!').lowercase(Locale.ROOT)
        val writing = EmaWritingTools.parseCommand(command)
        val needsScreen = EmaGroundedQuestion.parse(command)?.source == EmaGroundedQuestion.Source.SCREEN ||
            lower in setOf("summarize this screen", "summarize screen", "summarize this page",
            "extract entities from this screen", "find details on this screen") ||
            Regex("(?i)^ask (?:about )?(?:this |the )?screen:\\s*(.+)$").matches(command.trim()) ||
            (writing is EmaWritingTools.Command.TransformTarget && writing.target == EmaWritingTools.Target.SCREEN) ||
            (writing is EmaWritingTools.Command.StatsTarget && writing.target == EmaWritingTools.Target.SCREEN)
        if (!needsScreen) return false
        val visible = EmaAccessibilityService.currentScreenText()
        if (visible.isNotBlank()) done(answer(command, visible) ?: "I couldn't interpret that screen request.")
        else EmaAccessibilityService.readScreenWithOcr { text ->
            done(answer(command, text) ?: "I couldn't read that screen. Protected screens cannot be captured.")
        }
        return true
    }

    fun answer(command: String, observedScreenText: String? = null): String? {
        val lower = command.trim().trimEnd('.', '?', '!').lowercase(Locale.ROOT)
        val snapshot = EmaDialogueState.snapshot()
        EmaWorkspace.parse(command, snapshot.activeList?.name)?.let { op ->
            // Destructive bulk operations require a second, explicit utterance.
            if (op is EmaWorkspace.Op.ClearNotes || op is EmaWorkspace.Op.ClearList) {
                return "For safety, bulk deletion by voice is not enabled. Remove individual items or notes instead."
            }
            val before = EmaPersonalWorkspaceStore.load(context)
            val result = before.apply(op)
            if (result.changed) {
                if (!EmaPersonalWorkspaceStore.save(context, result.workspace)) {
                    return "I couldn't save that change. Your workspace has not been confirmed as updated."
                }
                EmaDialogueState.pushUndo(EmaDialogueState.UndoEntry.WorkspaceSnapshot(
                    before, result.undoSummary ?: "restored your workspace", System.currentTimeMillis()))
            }
            result.activeListName?.let { EmaDialogueState.recordActiveList(it) }
            return result.reply
        }
        when (lower) {
            "diagnose screen control", "check screen access" -> {
                if (!EmaAccessibilityService.isConnected()) return EmaAccessibilityService.availabilityProblem()
                val screen = EmaAccessibilityService.screenSnapshot(700)
                val visible = screen.nodes.count { it.visible }
                val readable = screen.nodes.count { it.visible && it.label.isNotBlank() }
                return "EMA Accessibility is connected. Foreground application: " +
                    EmaAccessibilityService.foregroundPackage().ifBlank { "not observable" } +
                    ". Visible controls: $visible; readable controls: $readable. Media-session verification: " +
                    (if (EmaNotificationListenerService.isConnected()) "connected." else "off — enable EMA Notification access in Android Settings.") +
                    (if (readable == 0) " This app exposes no readable controls right now. Explicit screen questions can try offline OCR; unlock the phone and dismiss sign-in or permission prompts yourself." else "")
            }
            "undo", "undo that" -> {
                val entry = EmaDialogueState.popUndo()
                    ?: return "There is no recent reversible workspace change to undo."
                if (entry !is EmaDialogueState.UndoEntry.WorkspaceSnapshot) {
                    return "That action cannot be undone here."
                }
                return if (EmaPersonalWorkspaceStore.save(context, entry.previous)) "Undone: ${entry.summary}."
                else {
                    EmaDialogueState.pushUndo(entry)
                    "I couldn't save the undo. Please try again."
                }
            }
            "start stopwatch", "start the stopwatch", "resume stopwatch" -> return EmaDialogueState.stopwatchStart()
            "stop stopwatch", "pause stopwatch" -> return EmaDialogueState.stopwatchPauseOrStop()
            "reset stopwatch" -> return EmaDialogueState.stopwatchReset()
            "lap", "stopwatch lap" -> return EmaDialogueState.stopwatchLap()
            "stopwatch", "stopwatch status" -> return EmaDialogueState.stopwatchStatus()
            "repeat that", "repeat your answer" -> return snapshot.lastReply?.text ?: "There is no recent reply to repeat."
            "summarize notifications", "notification digest", "priority notifications" -> {
                if (!EmaNotificationListenerService.isConnected()) return "Enable EMA notification access in Android Settings first."
                return EmaNotificationDigest.summarize(EmaNotificationListenerService.digestItems(), lower == "priority notifications")
            }
        }
        EmaWritingTools.parseCommand(command)?.let { writing ->
            fun target(target: EmaWritingTools.Target): String = when (target) {
                EmaWritingTools.Target.SCREEN -> observedScreenText ?: EmaAccessibilityService.currentScreenText()
                EmaWritingTools.Target.CLIPBOARD -> context.getSystemService(ClipboardManager::class.java)
                    ?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                EmaWritingTools.Target.LAST_DOCUMENT -> snapshot.document?.text.orEmpty()
            }
            var source = ""
            val answer = when (writing) {
                is EmaWritingTools.Command.TransformInline -> {
                    source = writing.text
                    EmaWritingTools.rewrite(source, writing.tone).let { it.transformed.ifBlank { it.summary } }
                }
                is EmaWritingTools.Command.TransformTarget -> {
                    source = target(writing.target)
                    EmaWritingTools.rewrite(source, writing.tone).let { it.transformed.ifBlank { it.summary } }
                }
                is EmaWritingTools.Command.StatsInline -> {
                    source = writing.text
                    EmaWritingTools.analyze(source).describe()
                }
                is EmaWritingTools.Command.StatsTarget -> {
                    source = target(writing.target)
                    EmaWritingTools.analyze(source).describe()
                }
                is EmaWritingTools.Command.Draft -> return EmaWritingTools.draft(writing.request)?.let {
                    "Draft only — not sent:\n${it.transformed}"
                }
            }
            if (source.isBlank()) return "There is no readable source text. Read a screen first or provide text after a colon."
            EmaDialogueState.recordDocument("writing source", source)
            return answer
        }
        EmaGroundedQuestion.parse(command)?.let { request ->
            val previous = request.source == EmaGroundedQuestion.Source.DOCUMENT
            val source = if (previous) snapshot.document?.text.orEmpty()
                else observedScreenText ?: EmaAccessibilityService.currentScreenText()
            if (source.isBlank()) return if (previous)
                "There is no recent readable document. Read or summarize a screen first."
            else "There is no readable text on this screen."
            EmaDialogueState.recordDocument(if (previous) "document" else "screen", source)
            return EmaScreenIntelligence.answerAboutText(
                request.question, source, if (previous) "the recent document" else "this screen", request.topicOnly
            ).text
        }
        val summary = lower in setOf("summarize this screen", "summarize screen", "summarize this page", "summarize it", "summarize that")
        val entities = lower in setOf("extract entities from this screen", "find details on this screen")
        val question = Regex("(?i)^ask (?:about )?(?:this |the )?screen:\\s*(.+)$").matchEntire(command.trim())
        val documentQuestion = Regex("(?i)^ask (?:about )?(?:the )?document:\\s*(.+)$").matchEntire(command.trim())
        if (summary || entities || question != null || documentQuestion != null) {
            val usePrevious = lower in setOf("summarize it", "summarize that") || documentQuestion != null
            val source = if (usePrevious) snapshot.document?.text.orEmpty() else observedScreenText ?: EmaAccessibilityService.currentScreenText()
            if (source.isBlank()) return "There is no readable source text. For screen questions, enable EMA Accessibility access first."
            EmaDialogueState.recordDocument(if (usePrevious) "document" else "screen", source)
            return when {
                summary -> EmaScreenIntelligence.summarize(source).ifBlank { "No substantive text was found." }
                entities -> EmaScreenIntelligence.formatEntitiesSummary(EmaScreenIntelligence.extractEntities(source))
                else -> EmaScreenIntelligence.answerAboutText((question ?: documentQuestion)!!.groupValues[1], source).text
            }
        }
        return null
    }
}
