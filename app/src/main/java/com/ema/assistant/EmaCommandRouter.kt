package com.ema.assistant

import android.Manifest
import android.app.Activity
import android.media.AudioManager
import android.telecom.TelecomManager
import android.provider.Settings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.content.ContentProviderOperation
import android.speech.tts.TextToSpeech
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First-stage command router.
 *
 * Deterministic phone/app commands are handled locally. The AI tunnel is only
 * used when EMA needs natural-language reasoning or information.
 */
class EmaCommandRouter(
    private val context: Context,
    private val ttsProvider: () -> TextToSpeech?
) {
    private val appEngine = EmaAppActionEngine(context)
    private val executionController = EmaExecutionController()
    private var lastAppName: String? = null

    private data class PendingTask(val type: String, val target: String, val app: String? = null, val body: String? = null, val sim: Int? = null, val candidates: List<String> = emptyList())
    // Follow-up state is scoped to this router/session so separate Activity and
    // VoiceInteractionSession instances cannot consume one another's pending task.
    private var pendingTask: PendingTask? = null

    private var executionStage = "routing"
    private val commandHandler = Handler(Looper.getMainLooper())
    private val commandTimeoutMs = 60_000L

    /** Cancel delayed multi-step work when the Android voice session disappears. */
    fun shutdown() {
        commandHandler.removeCallbacksAndMessages(null)
        executionController.shutdown()
    }

    fun execute(raw: String, done: (String) -> Unit) {
        // A command has one terminal result. This prevents a late callback,
        // timeout, or duplicated provider callback from producing conflicting
        // answers for the same user command.
        val completed = AtomicBoolean(false)
        val timeout = Runnable {
            if (!completed.compareAndSet(false, true)) return@Runnable
            executionController.shutdown()
            android.util.Log.e("EMA", "Command timed out after ${commandTimeoutMs}ms at stage=$executionStage: ${raw.trim()}")
            runCatching { done("I couldn't complete that command in time. The operation was stopped safely; I did not retry it automatically.") }
                .onFailure { android.util.Log.e("EMA", "Assistant timeout result delivery failed", it) }
        }
        commandHandler.postDelayed(timeout, commandTimeoutMs)

        val safeDone: (String) -> Unit = { result ->
            if (!completed.compareAndSet(false, true)) {
                android.util.Log.w("EMA", "Ignoring duplicate command completion: " + raw.trim())
            } else {
                commandHandler.removeCallbacks(timeout)
                Handler(Looper.getMainLooper()).post {
                    runCatching { done(result) }
                        .onFailure { android.util.Log.e("EMA", "Assistant result delivery failed", it) }
                }
            }
        }

        try {
            executeInternal(raw, safeDone)
        } catch (t: Throwable) {
            android.util.Log.e("EMA", "Command execution crashed at stage=$executionStage: " + raw.trim(), t)
            safeDone("I couldn't complete that command because the operation failed during $executionStage. I did not retry it automatically.")
        }
    }

    private fun executeInternal(raw: String, done: (String) -> Unit) {
        val command = raw.trim()
        if (command.isBlank()) {
            val message = "I didn't receive a command."
            finishAssistantResponse(message, done)
            return
        }
        if (isCloseAssistantCommand(command.lowercase(Locale.getDefault()))) {
            val message = "Closing EMA."
            speak(message)
            EmaVoiceInteractionSession.closeActive()
            done(message)
            return
        }
        // Pre-routing state helpers must never abort normal command routing.
        // Conversation history, stale pending tasks, or scheduler state are
        // auxiliary; a failure there must not make every command fail.
        runCatching {
            if (handlePendingFollowUp(command, done)) return
        }.onFailure {
            android.util.Log.e("EMA", "Pending follow-up handling failed; continuing", it)
        }

        pendingTask = null

        runCatching {
            if (handleScheduledAction(command, done)) return
        }.onFailure {
            android.util.Log.e("EMA", "Scheduled-action precheck failed; continuing", it)
        }

        runCatching {
            EmaConversationStore.add(context, "user", command)
        }.onFailure {
            android.util.Log.e("EMA", "Conversation history write failed; continuing", it)
        }

        runCatching {
            if (handleRecurringRoutine(command, done)) return
        }.onFailure {
            android.util.Log.e("EMA", "Recurring-routine precheck failed; continuing", it)
        }

        val lower = command.lowercase(Locale.getDefault())

        // Planning is a distinct stage. Runtime native failures must not be
        // mislabeled as an "understanding" failure.
        executionStage = "planning"

        // Deterministic planning happens before individual handlers.
        // It expands natural compound requests into the same native actions
        // used for one-step commands. No AI call is involved here.
        val planned = EmaNativeCommandPlanner.plan(command)
        executionStage = "executing"
        if (planned.size > 1) {
            executeSequence(planned, 0, done)
            return
        }
        val routedCommand = planned.firstOrNull().orEmpty()
        if (routedCommand.isNotBlank() && routedCommand != command) {
            executeInternal(routedCommand, done)
            return
        }

        when {
            lower == "scroll down" || lower == "scroll up" || lower == "scroll to top" -> {
                val direction = if (lower.contains("up") || lower.contains("top")) "up" else "down"
                EmaGenericUiController.scroll(direction) { _, message ->
                    speak(message); done(message)
                }
            }

            lower == "tap the first result" || lower == "select the first result" || lower == "open the first result" -> {
                val ok = EmaAccessibilityService.tapFirstResult()
                val message = if (ok) "Selected the first visible result." else "I could not identify a selectable result on this screen."
                speak(message); done(message)
            }

            lower == "read screen with camera" || lower == "read everything on screen" ||
                lower == "what does the screen say" || lower == "what is written on my screen" -> {
                statusWithOcr(done)
            }

            lower.startsWith("remind me ") || lower.startsWith("set a reminder ") -> {
                handleReminder(command, done)
            }

            lower.startsWith("create a calendar task ") || lower.startsWith("calendar task ") ||
                lower.startsWith("set a calendar task ") -> {
                handleCalendarTask(command, done)
            }

            lower.startsWith("create a calendar event ") || lower.startsWith("add calendar event ") ||
                lower.startsWith("calendar event ") || lower.startsWith("create an event ") -> {
                handleCalendarEvent(command, done)
            }

            lower == "read notifications" || lower == "show notifications" || lower == "what are my notifications" -> {
                val message = EmaNotificationListenerService.describeActiveNotifications()
                speak(message); done(message)
            }

            lower.startsWith("dismiss notification") || lower.startsWith("clear notification") -> {
                val target = command.substringAfter(' ').replaceFirst(Regex("(?i)^(notification|notifications)\\s*"), "").trim()
                val ok = EmaNotificationListenerService.dismissMatching(target)
                val message = if (ok) "I dismissed the matching notification." else "I could not find a matching active notification."
                speak(message); done(message)
            }

            lower == "open notifications" || lower == "show notification shade" -> {
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Showing notifications.", done)
            }

            lower == "analyze this image" || lower == "analyze the screen visually" || lower == "what am i looking at visually" -> {
                EmaAccessibilityService.describeScreenImage { labels ->
                    val ocr = EmaAccessibilityService.currentScreenText(8000)
                    val prompt = "Describe what is visible on the current Android screen. Image labels: " + labels + "\\nVisible text: " + ocr
                    CentralHubClient(context).ask(prompt) { answer ->
                        val result = answer.ifBlank { if (labels.isBlank()) "I could not identify visual content on this screen." else "I can identify: " + labels }
                        deliverAiAnswer(result, done)
                    }
                }
            }

            lower == "what time is it" || lower == "tell me the time" || lower == "current time" -> {
                val time = java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date())
                val message = "It is " + time + "."
                speak(message); done(message)
            }

            lower.startsWith("set an alarm for ") || lower.startsWith("set alarm for ") ||
                Regex("""(?i)^(?:set\s+(?:an\s+)?alarm\s+(?:in|inside|within)\s+clock\s+for\s+).+$""").matches(command) ||
                Regex("""(?i)^(?:open|enter|go\s+(?:to|into))\s+clock\s+and\s+set\s+(?:an\s+)?alarm\s+for\s+).+$""").matches(command) -> {
                val value = when {
                    lower.contains("alarm for ") -> command.substringAfter("alarm for ").trim()
                    lower.contains("alarm in clock for ") -> command.substringAfter("alarm in clock for ").trim()
                    lower.contains("alarm inside clock for ") -> command.substringAfter("alarm inside clock for ").trim()
                    lower.contains("alarm within clock for ") -> command.substringAfter("alarm within clock for ").trim()
                    else -> command.substringAfter(Regex("(?i)alarm\\s+for\\s+").find(command)?.value.orEmpty()).trim()
                }
                openAlarm(value, done)
            }

            lower == "increase brightness" || lower == "brightness up" -> adjustBrightness(0.1f, done)
            lower == "decrease brightness" || lower == "brightness down" -> adjustBrightness(-0.1f, done)
            lower.startsWith("set brightness to ") -> setBrightnessPercent(command.substringAfter("set brightness to ").trim(), done)
            lower == "turn on dnd" || lower == "turn dnd on" || lower == "enable dnd" || lower == "do not disturb on" -> setDnd(true, done)
            lower == "turn off dnd" || lower == "turn dnd off" || lower == "disable dnd" || lower == "do not disturb off" -> setDnd(false, done)
            lower == "open quick settings" || lower == "open control center" || lower == "open notification center" -> {
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Opening quick settings.", done)
            }
            lower == "power menu" || lower == "show power menu" -> {
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, "Showing the power menu.", done)
            }
            lower == "restart phone" || lower == "reboot phone" -> {
                val message = "Android does not expose a normal-app reboot command. I can open the power menu so you can restart the phone."
                speak(message)
                EmaAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
                done(message)
            }
            lower == "switch off phone" || lower == "turn off phone" || lower == "power off phone" -> {
                val message = "Android does not expose direct phone shutdown to a normal app. I can open the power menu for you."
                speak(message)
                EmaAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
                done(message)
            }

            lower.startsWith("navigate to ") || lower.startsWith("directions to ") -> {
                val destination = command.substringAfter(' ').substringAfter(' ').trim()
                openMaps(destination, done)
            }

            // Structured navigation must be resolved before the generic "go to"
            // fallback. This preserves the user's semantic roles:
            // destination/section -> app/settings context -> visible target.
            lower.startsWith("go to ") && Regex("(?i)^go\\s+to\\s+.+\\s+(?:on|in|using)\\s+.+$").matches(command) -> {
                val match = Regex("(?i)^go\\s+to\\s+(.+?)\\s+(?:on|in|using)\\s+(.+)$").find(command)
                if (match == null) {
                    val result = "I could not understand the destination and app."
                    speak(result); done(result)
                } else {
                    val target = match.groupValues[1].trim()
                    val app = match.groupValues[2].trim()
                    navigateInsideApp(app, target, done)
                }
            }

            lower.startsWith("go to ") && (
                lower.endsWith(" in settings") ||
                lower.endsWith(" settings") ||
                lower == "go to settings"
            ) -> {
                val phrase = command.substringAfter("go to ").trim()
                val target = phrase
                    .replace(Regex("(?i)\\s+in\\s+settings\\s*$"), "")
                    .replace(Regex("(?i)\\s+settings\\s*$"), "")
                    .trim()
                if (target.equals("settings", ignoreCase = true) || target.isBlank()) {
                    openSettings(Settings.ACTION_SETTINGS, "Settings", done)
                } else {
                    navigateInsideSettings(target, done)
                }
            }

            lower.startsWith("go to ") -> {
                val targetPhrase = command.substringAfter("go to ").trim()
                if (!handleAppSectionNavigation(targetPhrase, done)) {
                    openTarget(targetPhrase, done)
                }
            }

            isSaveContactCommand(lower) -> {
                saveContact(command, done)
            }

            isMessageCommand(lower) -> {
                val parsed = parseMessageCommand(command)
                if (parsed == null) {
                    val result = "Tell me who to message, which app to use, and what to say."
                    speak(result); done(result)
                } else {
                    val (recipient, app, body) = parsed
                    sendAppMessage(recipient, app, body, done)
                }
            }

            lower.startsWith("text ") || lower.startsWith("send a text to ") || lower.startsWith("send a message to ") -> {
                openMessageComposer(command, done)
            }

            lower == "back" || lower == "go back" ->
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK, "Going back.", done)

            lower == "home" || lower == "go home" ->
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME, "Going home.", done)

            lower == "volume up" || lower == "increase volume" -> adjustVolume(AudioManager.ADJUST_RAISE, "Volume increased.", done)
            lower == "volume down" || lower == "decrease volume" -> adjustVolume(AudioManager.ADJUST_LOWER, "Volume decreased.", done)
            lower == "mute" || lower == "mute volume" -> adjustVolume(AudioManager.ADJUST_MUTE, "Volume muted.", done)
            lower == "unmute" || lower == "unmute volume" -> adjustVolume(AudioManager.ADJUST_UNMUTE, "Volume unmuted.", done)

            lower.startsWith("set a timer for ") || lower.startsWith("timer for ") -> {
                val value = if (lower.startsWith("set a timer for ")) command.substringAfter("set a timer for ").trim()
                else command.substringAfter("timer for ").trim()
                openTimer(value, done)
            }

            lower == "open wifi settings" || lower == "open wi-fi settings" -> openSettings(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings", done)
            lower == "open bluetooth settings" -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings", done)
            lower == "open display settings" -> openSettings(Settings.ACTION_DISPLAY_SETTINGS, "display settings", done)
            lower == "open sound settings" -> openSettings(Settings.ACTION_SOUND_SETTINGS, "sound settings", done)
            lower == "open notification settings" -> openAppNotificationSettings(done)
            lower == "open accessibility settings" -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, "Accessibility settings", done)
            lower == "open assistant settings" -> openSettings(Settings.ACTION_VOICE_INPUT_SETTINGS, "assistant settings", done)

            lower == "recent apps" || lower == "show recent apps" ->
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS, "Showing recent apps.", done)

            lower == "lock phone" || lower == "lock the phone" -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locking the phone.", done)
                } else {
                    val result = "Phone locking is not supported on this Android version."
                    speak(result); done(result)
                }
            }

            lower == "play" || lower == "pause" || lower == "play or pause" -> {
                val ok = EmaAccessibilityService.toggleMediaPlayback()
                val message = if (ok) "Toggling media playback." else "I could not control media playback."
                speak(message); done(message)
            }

            lower == "read screen" || lower == "read this screen" || lower == "what is on my screen" -> {
                val screen = EmaAccessibilityService.currentScreenText()
                val message = if (screen.isBlank()) "EMA cannot read the current screen yet. Enable EMA's Accessibility access first." else screen
                speak(message.take(3000))
                done(message)
            }

            lower == "inspect screen" || lower == "inspect this screen" ||
                lower == "understand this screen" || lower == "analyze this screen" -> {
                val ui = EmaAccessibilityService.inspectCurrentUi()
                val message = if (ui.isBlank()) {
                    "EMA cannot inspect the current application's accessible UI. The app may not expose accessible controls, or Accessibility access may not be active."
                } else {
                    ui
                }
                speak(if (ui.isBlank()) message else "I inspected the accessible controls on this screen.")
                done(message)
            }

            lower.startsWith("tap ") || lower.startsWith("press ") -> {
                val target = command.substringAfter(' ').trim()
                EmaGenericUiController.tap(target) { _, message ->
                    speak(message); done(message)
                }
            }

            lower.startsWith("type ") -> {
                val value = command.substringAfter("type ").trim()
                EmaGenericUiController.type(value) { _, message ->
                    speak(message); done(message)
                }
            }

            lower.startsWith("call ") || lower.startsWith("dial ") || lower.startsWith("phone ") ||
                lower.startsWith("ring ") || lower.startsWith("call up ") -> {
                val name = when {
                    lower.startsWith("call ") -> command.substringAfter("call ").trim()
                    lower.startsWith("dial ") -> command.substringAfter("dial ").trim()
                    lower.startsWith("phone ") -> command.substringAfter("phone ").trim()
                    lower.startsWith("ring ") -> command.substringAfter("ring ").trim()
                    else -> command.substringAfter("call up ").trim()
                }
                callContact(name, done)
            }

            isAppSearchCommand(lower) -> {
                val parsed = parseAppSearch(command)
                if (parsed == null) {
                    speak("I could not understand which app you want to search.")
                    done("Try: go into YouTube and search for a topic.")
                } else {
                    val (app, query) = parsed
                    lastAppName = app
                    speak("Opening " + app + " and searching for " + query + ".")
                    if (lower.contains("first result") || lower.contains("first one")) {
                        appEngine.openAndSearchThenTapFirst(app, query) { _, result ->
                            speak(result); done(result)
                        }
                    } else {
                        appEngine.openAndSearch(app, query) { _, result ->
                            speak(result); done(result)
                        }
                    }
                }
            }

            isCombinedAppMessageCommand(lower) -> {
                val parsed = parseCombinedAppMessageCommand(command)
                if (parsed == null) {
                    val result = "Tell me who to message, which app to use, and what to say."
                    speak(result); done(result)
                } else {
                    val (app, recipient, body) = parsed
                    sendAppMessage(recipient, app, body, done)
                }
            }

            isMediaPlayCommand(lower) -> {
                val parsed = parseMediaPlay(command)
                if (parsed == null) {
                    speak("Tell me what to play and which app to use.")
                    done("Try: play a song on Spotify.")
                } else {
                    val (query, app) = parsed
                    appEngine.playMediaInApp(app, query) { ok, result ->
                        speak(result)
                        done(result)
                    }
                }
            }

            lower.startsWith("open ") -> openApp(command.substringAfter("open ").trim(), done)

            lower.startsWith("go to ") && lower.contains(" in settings") -> {
                val target = command.substringAfter("go to ").replace(Regex("(?i)\\s+in\\s+settings\\s*$"), "").trim()
                openSettingsAndFind(target, done)
            }

            lower.startsWith("go to ") && lower.contains(" settings") -> {
                val target = command.substringAfter("go to ").replace(Regex("(?i)\\s+settings\\s*$"), "").trim()
                openSettingsAndFind(target, done)
            }

            lower.startsWith("search with google ") || lower.startsWith("search google for ") || lower.startsWith("google search ") -> {
                val query = when {
                    lower.startsWith("search with google ") -> command.substringAfter("search with google ").trim()
                    lower.startsWith("search google for ") -> command.substringAfter("search google for ").trim()
                    else -> command.substringAfter("google search ").trim()
                }
                googleSearch(query, done)
            }

            lower.startsWith("schedule my day") || lower.startsWith("set my schedule for today") ||
                lower.startsWith("set today's schedule") -> {
                handleDaySchedule(command, done)
            }

            lower == "what can you do" || lower == "what can you do?" ||
                lower == "what can you do for me" || lower == "what can you do for me?" -> {
                val message = "I can set alarms and reminders, schedule your day, open and control apps, search and play media, send messages, make calls, navigate Settings, read or inspect the screen, control volume and brightness, and perform other Android actions. The native assistant layer runs first; AI is only used when a command needs additional reasoning."
                speak(message); done(message)
            }

            lower.startsWith("search ") || lower.startsWith("find ") -> {
                val query = command.substringAfter(' ').trim()
                val app = lastAppName
                if (!app.isNullOrBlank()) {
                    appEngine.openAndSearch(app, query) { _, result -> speak(result); done(result) }
                } else webSearch(query, done)
            }

            lower.startsWith("go to ") || lower.startsWith("navigate to ") ->
                openTarget(command.substringAfter(' ').trim().removePrefix("to ").trim(), done)

            else -> {
                // Native EMA routing has already had first refusal. Only now do
                // we use the second layer for language reasoning/information.
                executionStage = "planning"
                val prompt = runCatching {
                    EmaAiCommandInterpreter.buildPrompt(context, command)
                }.getOrElse {
                    val message = "I could not prepare an answer for that. Please try saying it another way."
                    deliverAiAnswer(message, done)
                    return@executeInternal
                }

                CentralHubClient(context).ask(prompt) { rawAnswer ->
                    try {
                        executionStage = "planning"
                        when (val parsed = EmaAiCommandInterpreter.parse(rawAnswer)) {
                            is EmaAiCommandInterpreter.Result.Actions -> {
                                if (parsed.actions.isEmpty()) {
                                    deliverAiAnswer(
                                        parsed.answer.ifBlank { "I could not determine what you wanted EMA to do." },
                                        done
                                    )
                                } else {
                                    // AI is the second-layer interpreter, never the authorization gate.
                                    // Only an explicitly action-oriented user command may cross into execution.
                                    if (!hasExplicitDeviceActionIntent(command)) {
                                        deliverAiAnswer(parsed.answer.ifBlank { "I could not determine a safe action for that request." }, done)
                                    } else {
                                        executionStage = "executing"
                                        executeAiActions(parsed.actions, 0, parsed.answer, done)
                                    }
                                }
                            }
                            is EmaAiCommandInterpreter.Result.Answer -> {
                                deliverAiAnswer(parsed.text, done)
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e("EMA", "AI response handling failed", t)
                        deliverAiAnswer(
                            rawAnswer.ifBlank { "I could not answer that right now. Please try again." },
                            done
                        )
                    }
                }
            }
        }
    }

    private fun executeSequence(
        steps: List<String>,
        index: Int,
        done: (String) -> Unit
    ) {
        if (index >= steps.size) {
            executionController.shutdown()
            done("Done.")
            return
        }

        executionController.runStep(
            action = { finish -> execute(steps[index], finish) },
            onTimeout = { "I couldn't complete step " + (index + 1) + " of " + steps.size + " before the execution timeout." }
        ) { result ->
            if (isActionFailure(result)) {
                executionController.shutdown()
                done(result)
            } else if (index == steps.lastIndex) {
                executionController.shutdown()
                done(result)
            } else {
                executeSequence(steps, index + 1, done)
            }
        }
    }

    private fun handleRecurringRoutine(command: String, done: (String) -> Unit): Boolean {
        val match = Regex("(?i)^every\\s+(day|weekday|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+at\\s+(.+?)\\s*:\\s*(.+)$").find(command)
            ?: Regex("(?i)^every\\s+(day|weekday|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+at\\s+(.+?)\\s+to\\s+(.+)$").find(command)
            ?: return false
        val recurrence = match.groupValues[1].lowercase()
        val time = match.groupValues[2].trim()
        val action = match.groupValues[3].trim()
        val parsed = Regex("(?i)^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$").find(time) ?: return false
        var hour = parsed.groupValues[1].toIntOrNull() ?: return false
        val minute = parsed.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: 0
        val meridiem = parsed.groupValues[3].lowercase()
        if (meridiem == "am" && hour == 12) hour = 0
        if (meridiem == "pm" && hour != 12) hour += 12
        if (hour !in 0..23 || minute !in 0..59) return false

        val ok = if (recurrence == "day" || recurrence == "weekday") {
            if (recurrence == "weekday") {
                val now = java.util.Calendar.getInstance()
                var found = false
                for (offset in 0..6) {
                    val c = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, offset) }
                    if (c.get(java.util.Calendar.DAY_OF_WEEK) in 2..6) { found = true; break }
                }
                EmaRoutineScheduler.scheduleDaily(context, action, hour, minute, weekdaysOnly = true) && found
            } else {
                EmaRoutineScheduler.scheduleDaily(context, action, hour, minute)
            }
        } else {
            val day = mapOf(
                "sunday" to java.util.Calendar.SUNDAY, "monday" to java.util.Calendar.MONDAY,
                "tuesday" to java.util.Calendar.TUESDAY, "wednesday" to java.util.Calendar.WEDNESDAY,
                "thursday" to java.util.Calendar.THURSDAY, "friday" to java.util.Calendar.FRIDAY,
                "saturday" to java.util.Calendar.SATURDAY
            )[recurrence] ?: return false
            EmaRoutineScheduler.scheduleWeekly(context, action, day, hour, minute)
        }
        val result = if (ok) "Recurring routine scheduled: $recurrence at $time." else "Android could not schedule that recurring routine."
        speak(result); done(result)
        return true
    }

    private fun handleScheduledAction(command: String, done: (String) -> Unit): Boolean {
        // Support the natural time-first form: “by 4 PM call Mom, by 7 PM set an alarm”.
        // Every item is stored as the original native command, so execution later
        // re-enters the same understand -> execute -> observe path.
        val time = "[0-9]{1,2}(?::[0-9]{2})?\\s*(?:am|pm)?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
        val pattern = Regex("(?i)(?:^|[,;]|\\bthen\\b)\\s*(?:(?:by|at)\\s+)?($time)\\s*(?:[,;:]|-)\\s*(.*?)(?=(?:[,;]|\\s+\\bthen\\b)?\\s*(?:(?:by|at)\\s+)?(?:$time)\\s*(?:[,;:]|-)|$)")
        val matches = pattern.findAll(command).toList()
        if (matches.isEmpty()) return false

        var count = 0
        val failed = mutableListOf<String>()
        for (match in matches) {
            val action = match.groupValues[2].trim().trim(',', ';', '-', '–', '—')
            if (action.isBlank()) continue
            val trigger = EmaScheduler.parseTimeToFutureMillis(match.groupValues[1].trim())
            if (trigger != null && EmaScheduler.schedule(context, action, trigger)) count++
            else failed += action + " at " + match.groupValues[1].trim()
        }

        if (count == 0 && failed.isEmpty()) return false
        val message = buildString {
            if (count > 0) append("Scheduled ").append(count).append(" action").append(if (count == 1) "" else "s").append(".")
            if (failed.isNotEmpty()) { if (isNotEmpty()) append("\n"); append("Could not schedule: ").append(failed.joinToString("; ")) }
        }
        speak(message); done(message); return true
    }

    private fun handleAppSectionNavigation(targetPhrase: String, done: (String) -> Unit): Boolean {
        val words = targetPhrase.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 2) return false

        // Resolve the longest installed app name at the beginning. Everything
        // after that is the destination inside the app, not another app.
        for (count in words.size - 1 downTo 1) {
            val appName = words.take(count).joinToString(" ")
            val target = appEngine.findApp(appName) ?: continue
            val section = words.drop(count).joinToString(" ").trim()
            if (section.isBlank()) return false
            navigateInsideApp(target.label, section, done)
            return true
        }
        return false
    }

    /**
     * Generic navigation: open the requested app, inspect the resulting UI,
     * locate the requested destination, and retry by scrolling when the
     * destination is not initially visible. No app-specific button names are
     * assumed here.
     */
    private fun navigateInsideApp(appName: String, destination: String, done: (String) -> Unit) {
        val target = appEngine.findApp(appName)
        if (target == null) {
            val result = "I could not find " + appName + " on this phone."
            speak(result); done(result); return
        }

        appEngine.openApp(target.label)
        lastAppName = target.label
        navigateVisibleDestination(destination, target.label, 0, done)
    }

    /**
     * Settings uses the same generic screen-navigation engine as third-party
     * apps. "Go to Settings" stops at Settings; a destination after Settings
     * continues through the visible Settings hierarchy.
     */
    private fun navigateInsideSettings(destination: String, done: (String) -> Unit) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            navigateVisibleDestination(destination, "Settings", 0, done)
        }.onFailure {
            val result = "I could not open Settings."
            speak(result); done(result)
        }
    }

    private fun navigateVisibleDestination(
        destination: String,
        contextLabel: String,
        attempt: Int,
        done: (String) -> Unit
    ) {
        if (destination.isBlank()) {
            val result = "I opened " + contextLabel + "."
            speak(result); done(result); return
        }

        val delay = if (attempt == 0) 700L else 350L
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // First try the generic controller so accessibility and its visual
            // fallback are both available.
            EmaGenericUiController.tap(destination) { ok, _ ->
                if (ok) {
                    val result = "Opened " + destination + " in " + contextLabel + "."
                    speak(result); done(result)
                    return@tap
                }

                if (attempt >= 5) {
                    val result = "I opened " + contextLabel + ", but could not locate " + destination + " in the current navigation path."
                    speak(result); done(result)
                    return@tap
                }

                // Re-inspect before changing the screen. If the destination is
                // present in accessible text, give it another exact/visual
                // opportunity; otherwise scroll the currently scrollable view.
                val screen = EmaAccessibilityService.currentScreenText(12000)
                val destinationWords = destination.lowercase(Locale.getDefault())
                    .split(Regex("\\s+"))
                    .filter { it.length > 1 }

                val textMatch = destinationWords.isNotEmpty() &&
                    destinationWords.any { word -> screen.lowercase(Locale.getDefault()).contains(word) }

                if (textMatch) {
                    EmaAccessibilityService.tapTextExact(destination)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        navigateVisibleDestination(destination, contextLabel, attempt + 1, done)
                    }, 250L)
                } else {
                    EmaGenericUiController.scroll("down") { _, _ ->
                        navigateVisibleDestination(destination, contextLabel, attempt + 1, done)
                    }
                }
            }
        }, delay)
    }

    private fun isSaveContactCommand(lower: String): Boolean =
        lower.startsWith("save ") && (
            lower.contains(" as ") ||
            lower.contains("'s number ") ||
            lower.contains(" number ")
        )

    private fun saveContact(command: String, done: (String) -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(Manifest.permission.WRITE_CONTACTS)
            val result = "EMA needs Contacts write access to save a contact. Grant it, then repeat the command."
            speak(result); done(result); return
        }

        val patterns = listOf(
            Regex("(?i)^save\\s+(.+?)\\s+as\\s+(.+)$"),
            Regex("(?i)^save\\s+(.+?)['’]s\\s+number\\s+(.+)$"),
            Regex("(?i)^save\\s+(.+?)\\s+number\\s+(.+)$")
        )

        var number: String? = null
        var name: String? = null
        for (pattern in patterns) {
            val match = pattern.find(command) ?: continue
            val first = match.groupValues[1].trim()
            val second = match.groupValues[2].trim()
            val firstLooksLikeNumber = first.matches(Regex("[+0-9()\\-\\s]{7,}"))
            if (firstLooksLikeNumber) {
                number = first
                name = second
            } else if (second.matches(Regex("[+0-9()\\-\\s]{7,}"))) {
                name = first
                number = second
            }
            if (number != null && name != null) break
        }

        // "save this number as NAME" can use a copied phone number as the
        // current number, without pretending the assistant can infer hidden data.
        if (number == null && command.matches(Regex("(?i)^save\\s+this\\s+number\\s+as\\s+.+$"))) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            if (clip.matches(Regex("[+0-9()\\-\\s]{7,}"))) {
                number = clip
                name = Regex("(?i)\\s+as\\s+(.+)$").find(command)?.groupValues?.getOrNull(1)?.trim()
            }
        }

        if (number.isNullOrBlank() || name.isNullOrBlank()) {
            val result = "Tell me the number and the name, for example: save 08012345678 as Daniel."
            speak(result); done(result); return
        }

        val cleanNumber = number!!.trim()
        val cleanName = name!!.trim()

        runCatching {
            val operations = arrayListOf<ContentProviderOperation>()
            operations += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()

            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, cleanName)
                .build()

            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, cleanNumber)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()

            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
        }.onSuccess {
            val result = "Saved $cleanName with number $cleanNumber."
            speak(result); done(result)
        }.onFailure {
            val result = "I could not save $cleanName to Contacts."
            speak(result); done(result)
        }
    }

    private fun statusWithOcr(done: (String) -> Unit) {
        val accessible = EmaAccessibilityService.currentScreenText()
        EmaAccessibilityService.readScreenWithOcr { ocr ->
            val combined = listOf(accessible, ocr).filter { it.isNotBlank() }.distinct().joinToString("\n")
            val result = combined.ifBlank { "I could not read text from the current screen." }.take(6000)
            speak(result); done(result)
        }
    }

    private fun adjustVolume(direction: Int, message: String, done: (String) -> Unit) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            val result = "I could not access the phone volume controls."
            speak(result); done(result); return
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        speak(message); done(message)
    }

    private fun openTimer(value: String, done: (String) -> Unit) {
        val match = Regex("(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)", RegexOption.IGNORE_CASE).find(value)
        if (match == null) {
            val result = "Tell me the timer duration, for example: set a timer for 10 minutes."
            speak(result); done(result); return
        }
        val amount = match.groupValues[1].toInt()
        val unit = match.groupValues[2].lowercase(Locale.getDefault())
        val seconds = when {
            unit.startsWith("hour") || unit.startsWith("hr") -> amount * 3600
            unit.startsWith("minute") || unit.startsWith("min") -> amount * 60
            else -> amount
        }
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "EMA timer")
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onSuccess {
            val result = "Opening a timer for $value."
            speak(result); done(result)
        }.onFailure {
            val result = "I could not open the phone timer."
            speak(result); done(result)
        }
    }

    private fun openAppNotificationSettings(done: (String) -> Unit) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onSuccess { val result = "Opening notification settings."; speak(result); done(result) }
            .onFailure { val result = "I could not open notification settings."; speak(result); done(result) }
    }

    private fun openSettings(action: String, label: String, done: (String) -> Unit) {
        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { val result = "Opening $label."; speak(result); done(result) }
            .onFailure { val result = "I could not open $label on this phone."; speak(result); done(result) }
    }

    private fun openAlarm(value: String, done: (String) -> Unit) {
        val time = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(value)
        if (time == null) {
            val message = "I could not understand the alarm time."
            speak(message); done(message); return
        }

        var hour = time.groupValues[1].toInt()
        val minute = time.groupValues[2].ifBlank { "0" }.toInt()
        when (time.groupValues[3].lowercase()) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) {
            val message = "That is not a valid alarm time."
            speak(message); done(message); return
        }

        // This is a system alarm request, not a UI-navigation task. Do not
        // guess or click a Clock button after opening it.
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "EMA alarm")
            putExtra(android.provider.AlarmClock.EXTRA_VIBRATE, true)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            val resolver = context.packageManager.resolveActivity(intent, 0)
                ?: throw IllegalStateException("No alarm application can handle ACTION_SET_ALARM")
            context.startActivity(intent)
            val message = "Alarm request sent to " + resolver.activityInfo.applicationInfo.loadLabel(context.packageManager) +
                " for " + value + "."
            speak(message); done(message)
        }.onFailure {
            val message = "I could not send the alarm request to the phone's alarm system."
            speak(message); done(message)
        }
    }

    private fun openMaps(destination: String, done: (String) -> Unit) {
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(destination))
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val message = "Opening navigation to " + destination + "."
            speak(message); done(message)
        }.onFailure {
            val fallback = Uri.parse("geo:0,0?q=" + Uri.encode(destination))
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            val message = "Opening maps for " + destination + "."
            speak(message); done(message)
        }
    }
    private fun openMessageComposer(command: String, done: (String) -> Unit) {
        val lower = command.lowercase(Locale.getDefault())
        val marker = when {
            lower.startsWith("send a message to ") -> "send a message to "
            lower.startsWith("send a text to ") -> "send a text to "
            else -> "text "
        }
        val rest = command.substring(marker.length).trim()
        val parts = rest.split(Regex("\\s+(?:saying|that says|message)\\s+"), limit = 2)
        val recipient = parts.firstOrNull().orEmpty()
        val body = parts.getOrNull(1).orEmpty()
        val uri = Uri.parse("smsto:" + Uri.encode(recipient))
        val intent = Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (body.isNotBlank()) intent.putExtra("sms_body", body)
        runCatching { context.startActivity(intent) }.onSuccess {
            val message = "Opening messages for " + recipient + "."
            speak(message); done(message)
        }.onFailure {
            val message = "I could not open the messaging app."
            speak(message); done(message)
        }
    }

    private data class MessageCommand(
        val recipient: String,
        val app: String,
        val body: String
    )

    private fun isMessageCommand(lower: String): Boolean {
        return lower.startsWith("message ") ||
            lower.startsWith("send a message ") ||
            (lower.startsWith("send ") && (
                lower.contains(" on ") || lower.contains(" in ")
            ) && (
                lower.contains(" saying ") || lower.contains(" that ") || lower.contains(" to ")
            )) ||
            (lower.startsWith("tell ") && (
                lower.contains(" on ") || lower.contains(" in ")
            ))
    }

    private fun parseMessageCommand(command: String): MessageCommand? {
        val patterns = listOf(
            Regex("(?i)^message\\s+(.+?)\\s+(?:on|in|using)\\s+(.+?)\\s+(?:saying|that|to say)\\s+(.+)$"),
            Regex("(?i)^send\\s+(?:a\\s+)?message\\s+(.+?)\\s+(?:on|in|using)\\s+(.+?)\\s+(?:saying|that|to say)\\s+(.+)$"),
            Regex("(?i)^send\\s+(.+?)\\s+(?:a\\s+)?message\\s+(?:on|in|using)\\s+(.+?)\\s+(?:saying|that|to say)\\s+(.+)$"),
            Regex("(?i)^tell\\s+(.+?)\\s+(?:on|in|using)\\s+(.+?)\\s+(?:that|to say)\\s+(.+)$"),
            Regex("(?i)^message\\s+(.+?)\\s+(?:on|in|using)\\s+(.+?)\\s*:\\s*(.+)$"),
            Regex("(?i)^send\\s+(?:a\\s+)?message\\s+to\\s+(.+?)\\s+(?:on|in|using)\\s+(.+?)\\s+(?:saying|that)\\s+(.+)$")
        )
        for (pattern in patterns) {
            val match = pattern.find(command) ?: continue
            val recipient = match.groupValues[1].trim()
            val app = match.groupValues[2].trim()
            val body = match.groupValues[3].trim()
            if (recipient.isNotBlank() && app.isNotBlank() && body.isNotBlank()) {
                return MessageCommand(recipient, app, body)
            }
        }
        return null
    }

    private fun sendAppMessage(
        recipient: String,
        app: String,
        body: String,
        done: (String) -> Unit
    ) {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(Manifest.permission.READ_CONTACTS)
            val result = "EMA needs Contacts access to find $recipient. Grant it, then repeat the message."
            speak(result); done(result); return
        }

        val requested = recipient.trim().lowercase()
        data class MessageCandidate(
            val displayName: String,
            val phone: String?,
            val email: String?,
            val rank: Int
        )
        val candidates = mutableListOf<MessageCandidate>()

        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.DISPLAY_NAME,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1
            ),
            null, null, null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
            val dataIndex = cursor.getColumnIndex(ContactsContract.Data.DATA1)

            val grouped = linkedMapOf<String, Triple<String, String?, String?>>()
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIndex).orEmpty().trim()
                if (displayName.isBlank()) continue
                val key = displayName.lowercase()
                val current = grouped[key]
                val mime = cursor.getString(mimeIndex).orEmpty()
                val value = cursor.getString(dataIndex).orEmpty()
                var phone = current?.second
                var email = current?.third
                if (mime == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE && phone.isNullOrBlank()) phone = value
                if (mime == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE && email.isNullOrBlank()) email = value
                grouped[key] = Triple(displayName, phone, email)
            }

            val requestedWords = requested.split(Regex("\\s+")).filter { it.isNotBlank() }
            for ((_, value) in grouped) {
                val displayName = value.first
                val candidate = displayName.lowercase()
                val words = candidate.split(Regex("\\s+")).filter { it.isNotBlank() }
                val rank = when {
                    candidate == requested -> 0
                    words.any { it == requested } -> 1
                    requestedWords.size > 1 && requestedWords.all { word -> words.any { it == word } } -> 2
                    candidate.startsWith("$requested ") || candidate.endsWith(" $requested") -> 3
                    candidate.contains(" $requested ") -> 3
                    candidate.startsWith(requested) || candidate.endsWith(requested) -> 4
                    candidate.contains(requested) -> 5
                    else -> Int.MAX_VALUE
                }
                if (rank != Int.MAX_VALUE) {
                    candidates.add(MessageCandidate(displayName, value.second, value.third, rank))
                }
            }
        }

        if (candidates.isEmpty()) {
            val result = "I couldn't find $recipient in your contacts."
            speak(result); done(result); return
        }

        val bestRank = candidates.minOf { it.rank }
        val best = candidates.filter { it.rank == bestRank }
            .distinctBy { it.displayName.lowercase() }

        if (best.size > 1) {
            val names = best.take(3).joinToString(", ") { it.displayName }
            pendingTask = PendingTask("message", recipient, app, body, candidates = best.map { it.displayName }.distinct())
            val result = "I found more than one possible contact for $recipient: $names. Tell me which one."
            speak(result); done(result); return
        }

        pendingTask = null
        val selected = best.first()
        appEngine.sendMessage(app, selected.displayName, selected.phone, selected.email, body) { _, result ->
            speak(result); done(result)
        }
    }

    private fun isAppSearchCommand(lower: String): Boolean {
        // App-directed search is a phone action, not a web-search fallback.
        if (isCallIntent(lower)) return false
        if (!lower.contains("search") && !lower.contains("find")) return false

        // Both natural word orders are supported:
        // "YouTube search for cats" and "search for cats on YouTube".
        // "open YouTube and search for cats" is also supported.
        val appFirst = Regex(
            "(?i)^(?:open\\s+|go\\s+(?:into|to)\\s+)?[^ ](?:.*?)(?:\\s+search(?:\\s+for)?\\s+|\\s+find\\s+).+"
        ).matches(lower)

        val appLast = Regex(
            "(?i)^(?:search(?:\\s+for)?|find)\\s+.+?\\s+(?:on|in|using)\\s+.+$"
        ).matches(lower)

        val namedApp = Regex(
            "(?i)^(?:search|find)\\s+[^ ]+\\s+(?:for|about)\\s+.+$"
        ).matches(lower)

        return appFirst || appLast || namedApp
    }

    private fun parseAppSearch(command: String): Pair<String, String>? {
        val lower = command.lowercase(Locale.getDefault())

        // App-first: "YouTube search for cats", "open YouTube and search cats".
        val appFirst = Regex(
            "(?i)^(.*?)\\s+(?:and\\s+)?search(?:\\s+for)?\\s+(.+)$"
        ).find(command)

        if (appFirst != null) {
            val app = appFirst.groupValues[1]
                .replaceFirst(Regex("(?i)^go\\s+(?:into|to)\\s+"), "")
                .replaceFirst(Regex("(?i)^open\\s+"), "")
                .trim()
            val query = appFirst.groupValues[2].trim()
            if (app.isNotBlank() && query.isNotBlank()) return app to query
        }

        // Search-first: "search Lonely on Spotify", "find a video in YouTube".
        val appLast = Regex(
            "(?i)^(?:search(?:\\s+for)?|find)\\s+(.+?)\\s+(?:on|in|using)\\s+(.+)$"
        ).find(command)

        if (appLast != null) {
            val query = appLast.groupValues[1].trim()
            val app = appLast.groupValues[2].trim()
            if (app.isNotBlank() && query.isNotBlank()) return app to query
        }

        val namedApp = Regex(
            "(?i)^(?:search|find)\\s+(.+?)\\s+(?:for|about)\\s+(.+)$"
        ).find(command)
        if (namedApp != null) {
            val app = namedApp.groupValues[1].trim()
            val query = namedApp.groupValues[2].trim()
            if (app.isNotBlank() && query.isNotBlank()) return app to query
        }

        return null
    }

    private fun isCombinedAppMessageCommand(lower: String): Boolean {
        if (!lower.startsWith("open ")) return false
        return lower.contains(" message ") &&
            (lower.contains(" that ") || lower.contains(" saying ")) &&
            (lower.contains(" and send") || lower.contains(" send"))
    }

    private fun parseCombinedAppMessageCommand(command: String): Triple<String, String, String>? {
        val patterns = listOf(
            Regex("(?i)^open\\s+(.+?)\\s*(?:,|and)?\\s+message\\s+(.+?)\\s+(?:that|saying)\\s+(.+?)(?:\\s+and\\s+send)?$"),
            Regex("(?i)^open\\s+(.+?)\\s+and\\s+message\\s+(.+?)\\s+(?:that|saying)\\s+(.+?)(?:\\s+and\\s+send)?$")
        )
        for (pattern in patterns) {
            val match = pattern.find(command) ?: continue
            val app = match.groupValues[1].trim()
            val recipient = match.groupValues[2].trim()
            val body = match.groupValues[3].trim()
            if (app.isNotBlank() && recipient.isNotBlank() && body.isNotBlank()) {
                return Triple(app, recipient, body)
            }
        }
        return null
    }

    private fun isCallIntent(lower: String): Boolean =
        lower.startsWith("call ") || lower.startsWith("dial ") ||
            lower.startsWith("phone ") || lower.startsWith("ring ") ||
            lower.startsWith("call up ")

    private fun isMediaPlayCommand(lower: String): Boolean =
        lower.startsWith("play ") ||
            lower.startsWith("listen to ") ||
            Regex("(?i)^open\\s+.+?\\s+and\\s+play\\s+.+$").matches(lower) ||
            Regex("(?i)^(?:in|inside|within)\\s+.+?,?\\s+play\\s+.+$").matches(lower)

    private fun parseMediaPlay(command: String): Pair<String, String>? {
        val lower = command.lowercase(Locale.getDefault())

        // Support natural assistant phrasing such as "open Spotify and play Arcade"
        // without requiring the AI layer to translate it first.
        val appFirst = Regex("(?i)^open\\s+(.+?)\\s+and\\s+play\\s+(.+)$").find(command)
        if (appFirst != null) {
            val app = appFirst.groupValues[1].trim()
            val query = appFirst.groupValues[2].trim()
            if (app.isNotBlank() && query.isNotBlank()) return query to app
        }

        val inApp = Regex("(?i)^(?:in|inside|within)\\s+(.+?),?\\s+play\\s+(.+)$").find(command)
        if (inApp != null) {
            val app = inApp.groupValues[1].trim()
            val query = inApp.groupValues[2].trim()
            if (app.isNotBlank() && query.isNotBlank()) return query to app
        }

        val markers = listOf(" on ", " in ", " using ", " with ", " from ")
        val marker = markers.firstOrNull { lower.lastIndexOf(it) > 5 }
        if (marker != null) {
            val index = lower.lastIndexOf(marker)
            val prefix = command.substring(0, index).trim()
            val app = command.substring(index + marker.length).trim()
            val query = prefix
                .replaceFirst(Regex("(?i)^play\\s+"), "")
                .replaceFirst(Regex("(?i)^listen to\\s+"), "")
                .trim()
            if (query.isNotBlank() && app.isNotBlank()) return query to app
        }

        // Speech can omit the connector word: "play X Spotify".
        // Resolve the longest installed app label at the end of the command.
        val installedLabels = runCatching {
            context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).mapNotNull { it.activityInfo?.applicationInfo?.loadLabel(context.packageManager)?.toString() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale.getDefault()) }
                .sortedByDescending { it.length }
        }.getOrDefault(emptyList())

        val spoken = command
            .replaceFirst(Regex("(?i)^play\\s+"), "")
            .replaceFirst(Regex("(?i)^listen to\\s+"), "")
            .trim()

        installedLabels.firstOrNull { label ->
            spoken.equals(label, ignoreCase = true) ||
                spoken.endsWith(" " + label, ignoreCase = true) ||
                Regex("(?i)\\b" + Regex.escape(label) + "\\b").containsMatchIn(spoken)
        }?.let { app ->
            val query = spoken
                .replace(Regex("(?i)\\b" + Regex.escape(app) + "\\b"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .removePrefix("and ")
                .removeSuffix(" and")
                .trim()
            if (query.isNotBlank()) return query to app
        }

        return null
    }

    private fun handlePendingFollowUp(command: String, done: (String) -> Unit): Boolean {
        val task = pendingTask ?: return false
        val lower = command.lowercase(Locale.getDefault()).trim()
        val exactOriginal = lower.contains("without") &&
            (lower.contains("prefix") || lower.contains("suffix") || lower.contains("anything before") || lower.contains("anything after"))
        val ordinal = when {
            Regex("(?i)\\b(first|1st|one)\\b").containsMatchIn(command) -> 0
            Regex("(?i)\\b(second|2nd|two)\\b").containsMatchIn(command) -> 1
            Regex("(?i)\\b(third|3rd|three)\\b").containsMatchIn(command) -> 2
            else -> -1
        }
        val sim = Regex("(?i)\\b(?:with\\s+)?(?:sim|slot)\\s*([12])\\b").find(command)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val selectedName = when {
            exactOriginal -> task.target
            ordinal >= 0 -> task.candidates.getOrNull(ordinal)
            else -> command.replace(Regex("(?i)^(the\\s+)?(one|person)\\s+"), "").trim()
                .takeIf { it.isNotBlank() && !it.equals("the other", true) }
        } ?: return false
        pendingTask = null
        if (task.type == "call") {
            callContact(if (sim != null) "$selectedName with SIM $sim" else selectedName, done)
            return true
        }
        if (task.type == "message" && task.app != null && !task.body.isNullOrBlank()) {
            sendAppMessage(selectedName, task.app, task.body, done)
            return true
        }
        return false
    }

    private fun callContact(rawName: String, done: (String) -> Unit) {
        val sim = Regex("(?i)\\b(?:with\\s+)?(?:sim|slot)\\s*([12])\\s*$").find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val name = rawName.replace(Regex("(?i)\\s+(?:with\\s+)?(?:sim|slot)\\s*[12]\\s*$"), "").trim()
        if (name.isBlank()) {
            speak("Tell me who to call."); done("Tell me who to call."); return
        }

        if (name.matches(Regex("[+0-9()\\-\\s]{7,}"))) {
            placeCall(name.replace(Regex("[^+0-9]"), ""), name, done, sim)
            return
        }

        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(Manifest.permission.READ_CONTACTS)
            val result = "EMA needs Contacts access to find $name. Grant it, then say call $name again."
            speak(result); done(result); return
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        data class ContactCandidate(
            val displayName: String,
            val number: String,
            val rank: Int
        )

        val candidates = mutableListOf<ContactCandidate>()

        val requested = name.trim().lowercase()
        val requestedWords = requested.split(Regex("\\s+")).filter { it.isNotBlank() }

        fun readPhoneContacts(sortOrder: String? = null): Boolean {
            return runCatching {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (nameIndex < 0 || numberIndex < 0) return@use

                    while (cursor.moveToNext()) {
                        val contactName = cursor.getString(nameIndex).orEmpty().trim()
                        val number = cursor.getString(numberIndex).orEmpty().trim()
                        if (contactName.isBlank() || number.isBlank()) continue

                        val candidate = contactName.lowercase()
                        val candidateWords = candidate.split(Regex("\\s+")).filter { it.isNotBlank() }

                        val rank = when {
                            candidate == requested -> 0
                            candidateWords.any { it == requested } -> 1
                            requestedWords.size > 1 && requestedWords.all { word -> candidateWords.any { it == word } } -> 2
                            candidate.startsWith("$requested ") || candidate.endsWith(" $requested") -> 3
                            candidate.contains(" $requested ") -> 3
                            candidate.startsWith(requested) || candidate.endsWith(requested) -> 4
                            candidate.contains(requested) -> 5
                            else -> Int.MAX_VALUE
                        }

                        if (rank != Int.MAX_VALUE) {
                            candidates.add(ContactCandidate(contactName, number, rank))
                        }
                    }
                }
                true
            }.getOrElse {
                android.util.Log.e("EMA", "Native contact lookup failed for '$name'", it)
                false
            }
        }

        // Do not depend on provider-specific SQL sorting. Some Android/OEM
        // Contacts providers reject COLLATE expressions. If the ordered query
        // fails, retry with the provider's default ordering.
        val queried = readPhoneContacts(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")
        if (!queried) {
            readPhoneContacts(null)
        }

        if (candidates.isEmpty()) {
            val result = "I couldn't find $name in your contacts."
            speak(result); done(result); return
        }

        val bestRank = candidates.minOf { it.rank }
        val best = candidates.filter { it.rank == bestRank }
            .distinctBy { it.displayName.lowercase() to it.number }

        if (best.size > 1) {
            val names = best.take(3).joinToString(", ") { it.displayName }
            pendingTask = PendingTask("call", name, sim = sim, candidates = best.map { it.displayName }.distinct())
            val result = "I found more than one possible contact for $name: $names. Tell me which one."
            speak(result); done(result); return
        }

        pendingTask = null
        val selected = best.first()
        placeCall(selected.number, selected.displayName, done, sim)
    }

    private fun placeCall(number: String, displayName: String, done: (String) -> Unit, sim: Int? = null) {
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(Manifest.permission.CALL_PHONE)
            val result = "EMA needs phone-call permission before it can call $displayName. Grant it, then say call $displayName again."
            speak(result); done(result); return
        }

        runCatching {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (sim != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                val accounts = telecom?.callCapablePhoneAccounts.orEmpty()
                val handle = accounts.getOrNull(sim - 1)
                if (handle != null) intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                else throw IllegalArgumentException("Requested SIM is not available")
            }
            context.startActivity(intent)
        }.onSuccess {
            val result = if (sim == null) "Calling $displayName." else "Calling $displayName on SIM $sim."
            speak(result); done(result)
        }.onFailure {
            val result = "Android did not allow EMA to place the call."
            speak(result); done(result)
        }
    }

    private fun requestPermission(permission: String) {
        val activity = context as? Activity
        if (activity != null) {
            activity.requestPermissions(arrayOf(permission), 42)
            return
        }
        // VoiceInteractionService/Session is not an Activity, so the old
        // requestPermissions call silently did nothing when EMA was invoked
        // as the system assistant. Bring the permission-capable Activity forward.
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra("ema_request_permission", permission)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }.onFailure {
            android.util.Log.e("EMA", "Could not open permission Activity for $permission", it)
        }
    }

    private fun globalAction(action: Int, message: String, done: (String) -> Unit) {
        val ok = EmaAccessibilityService.performGlobalAction(action)
        val result = if (ok) message else "EMA needs Accessibility access to perform that action."
        speak(result); done(result)
    }

    private fun openApp(appName: String, done: (String) -> Unit) {
        val target = appEngine.openApp(appName)
        if (target == null) {
            val message = "I couldn't find or open " + appName + " on this phone."
            speak(message); done(message); return
        }
        lastAppName = target.label
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val ui = EmaAccessibilityService.inspectCurrentUi()
            val finalMessage = if (ui.isNotBlank()) "Opened " + target.label + " and inspected the accessible UI." else "Opened " + target.label + "."
            speak(finalMessage); done(finalMessage)
        }, 600L)
    }
    private fun openSettingsAndFind(target: String, done: (String) -> Unit) {
        if (target.isBlank()) { openSettings(Settings.ACTION_SETTINGS, "Settings", done); return }
        runCatching {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                EmaGenericUiController.tap(target) { ok, detail ->
                    val message = if (ok) "Opened " + target + " in Settings. " + detail else "I opened Settings, but I could not locate " + target + "."
                    speak(message); done(message)
                }
            }, 900L)
        }.onFailure {
            val message = "I could not open Settings."
            speak(message); done(message)
        }
    }

    private fun googleSearch(query: String, done: (String) -> Unit) {
        if (query.isBlank()) {
            val result = "Tell me what to search for."
            speak(result); done(result); return
        }
        val target = "https://www.google.com/search?q=" + Uri.encode(query)
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onSuccess {
            val result = "Searching Google for $query."
            speak(result); done(result)
        }.onFailure {
            val result = "I could not open Google search."
            speak(result); done(result)
        }
    }

    private fun webSearch(query: String, done: (String) -> Unit) {
        if (query.isBlank()) return
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(intent) }.onSuccess {
            val message = "Searching for $query."
            speak(message); done(message)
        }.onFailure {
            openTarget("https://www.google.com/search?q=" + Uri.encode(query), done)
        }
    }

    private fun openTarget(target: String, done: (String) -> Unit) {
        val uri = if (target.contains("://")) Uri.parse(target) else Uri.parse("https://$target")
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val message = "Opening $target."
            speak(message); done(message)
        }.onFailure {
            val message = "I couldn't open $target."
            speak(message); done(message)
        }
    }

    /**
     * AI-generated actions are executable only when the user's original utterance
     * clearly asks EMA to perform a device/app operation. This prevents a normal
     * question or explanation request from being converted into phone control.
     */
    private fun hasExplicitDeviceActionIntent(command: String): Boolean {
        val normalized = command.trim().lowercase(Locale.getDefault())
            .replace(Regex("^(please|can you|could you|would you|i want you to|i need you to)\\s+"), "")
            .trim()

        if (normalized.isBlank()) return false

        val actionPatterns = listOf(
            Regex("""^(open|launch|start|close|exit|go to|navigate to|enter)\\b"""),
            Regex("""^(tap|click|press|select|choose|type|write|enter)\\b"""),
            Regex("""^(search|find|look for|google)\\b"""),
            Regex("""^(call|phone|dial|send|message|text)\\b"""),
            Regex("""^(play|pause|stop|skip|resume)\\b"""),
            Regex("""^(set|create|add|schedule|remind|remember)\\b"""),
            Regex("""^(turn|enable|disable|increase|decrease|raise|lower|change|set)\\b"""),
            Regex("""^(scroll|swipe|back|home|read screen|inspect screen|show notifications)\\b"""),
            Regex("""^(take|capture|screenshot|record)\\b""")
        )
        if (actionPatterns.any { it.containsMatchIn(normalized) }) return true

        // Natural requests that explicitly delegate an operation to EMA.
        return Regex("""^(help me|i need you to|i want you to)\\s+(open|launch|search|find|call|message|send|play|set|create|schedule|navigate|go to|tap|type|scroll|turn|enable|disable)\\b""")
            .containsMatchIn(command.trim().lowercase(Locale.getDefault()))
    }

    private fun executeAiActions(actions: List<String>, index: Int, finalAnswer: String, done: (String) -> Unit) {
        if (index >= actions.size) {
            val answer = finalAnswer.ifBlank { "Done." }
            executionController.shutdown()
            finishAssistantResponse(answer, done)
            return
        }

        executionController.runStep(
            action = { finish -> execute(actions[index], finish) },
            onTimeout = { "I couldn't complete step " + (index + 1) + " of " + actions.size + " before the execution timeout." }
        ) { result ->
            if (isActionFailure(result)) {
                executionController.shutdown()
                finishAssistantResponse(result, done)
            } else if (index == actions.lastIndex) {
                val answer = result.ifBlank { finalAnswer.ifBlank { "Done." } }
                executionController.shutdown()
                finishAssistantResponse(answer, done)
            } else {
                executeAiActions(actions, index + 1, finalAnswer, done)
            }
        }
    }

    private fun finishAssistantResponse(raw: String, done: (String) -> Unit) {
        val answer = raw.trim().ifBlank { "I could not answer that right now. Please try again." }
        Handler(Looper.getMainLooper()).post {
            runCatching { EmaConversationStore.add(context, "assistant", answer) }
                .onFailure { android.util.Log.e("EMA", "Could not store assistant response", it) }
            runCatching { speak(answer) }
                .onFailure { android.util.Log.e("EMA", "Could not speak assistant response", it) }
            runCatching { done(answer) }
                .onFailure { android.util.Log.e("EMA", "Could not deliver assistant response", it) }
        }
    }

    private fun deliverAiAnswer(raw: String, done: (String) -> Unit) {
        finishAssistantResponse(raw, done)
    }

    private fun isActionFailure(result: String): Boolean {
        val text = result.trim().lowercase(Locale.getDefault())
        if (text.isBlank()) return true
        return text.startsWith("i couldn't") ||
            text.startsWith("i could not") ||
            text.startsWith("i cannot") ||
            text.contains("could not verify") ||
            text.contains("couldn't verify") ||
            text.contains("was not verified") ||
            text.contains("but could not") ||
            text.contains("but couldn't") ||
            text.contains("failed")
    }

    private fun handleDaySchedule(command: String, done: (String) -> Unit) {
        val body = command.substringAfter(":", missingDelimiterValue = "").trim()
        if (body.isBlank()) {
            val message = "Give me today's schedule as times and tasks, for example: schedule my day: 8 AM class; 1 PM meeting; 6 PM gym."
            speak(message); done(message); return
        }

        val entries = body.split(Regex("\\s*;\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        var scheduled = 0
        val failed = mutableListOf<String>()
        for (entry in entries) {
            val match = Regex("(?i)^(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)\\s*(?:-|–|—|:|to)?\\s*(.+)$").find(entry)
            if (match == null) {
                failed.add(entry)
                continue
            }
            val trigger = EmaScheduler.parseTimeToFutureMillis(match.groupValues[1].trim())
            val task = match.groupValues[2].trim()
            if (trigger == null || !EmaReminderScheduler.schedule(context, task, trigger)) failed.add(entry)
            else scheduled++
        }

        val message = if (scheduled > 0 && failed.isEmpty()) {
            "I scheduled " + scheduled + " item" + if (scheduled == 1) "" else "s" + " for today. EMA will remind you at each time."
        } else if (scheduled > 0) {
            "I scheduled " + scheduled + " item" + if (scheduled == 1) "" else "s" + " for today, but could not schedule: " + failed.joinToString(", ") + "."
        } else {
            "I could not schedule those items. Check EMA's notification and alarm permissions and try again."
        }
        speak(message); done(message)
    }

    private fun handleReminder(command: String, done: (String) -> Unit) {
        val match = Regex("(?i)^(?:remind me|set a reminder)(?: to| that)? (.+?) (?:at|by) (.+)$").find(command)
        if (match == null) {
            val result = "Tell me what to remind you about and when."
            speak(result); done(result); return
        }
        val text = match.groupValues[1].trim()
        val trigger = EmaReminderScheduler.parseTime(match.groupValues[2].trim())
        if (trigger == null) {
            val result = "I understood the reminder, but I could not understand the time."
            speak(result); done(result); return
        }
        val ok = EmaReminderScheduler.schedule(context, text, trigger)
        val formatted = java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(trigger))
        val result = if (ok) "Reminder set for $formatted: $text." else "Android could not schedule that reminder."
        speak(result); done(result)
    }

    private fun handleCalendarTask(command: String, done: (String) -> Unit) {
        val taskText = command
            .replaceFirst(Regex("(?i)^create a calendar task\\s*"), "")
            .replaceFirst(Regex("(?i)^calendar task\\s*"), "")
            .replaceFirst(Regex("(?i)^set a calendar task\\s*"), "")
            .trim()
        if (taskText.isBlank()) {
            val result = "Tell me what the calendar task should be."
            speak(result); done(result); return
        }
        val calendar = appEngine.findApp("Calendar")
        if (calendar == null) {
            val result = "I could not find a Calendar app on this phone."
            speak(result); done(result); return
        }
        appEngine.openApp(calendar.label)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            EmaGenericUiController.tap("Task") { found, _ ->
                if (!found) {
                    EmaGenericUiController.tap("Tasks") { foundTasks, _ ->
                        if (!foundTasks) {
                            val result = "I opened Calendar, but Android did not expose its Task control."
                            speak(result); done(result)
                        } else {
                            createTaskThroughCurrentUi(taskText, done)
                        }
                    }
                } else {
                    createTaskThroughCurrentUi(taskText, done)
                }
            }
        }, 900L)
    }

    private fun createTaskThroughCurrentUi(text: String, done: (String) -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val opened = EmaAccessibilityService.activate("Add task") || EmaAccessibilityService.activate("Create task") || EmaAccessibilityService.activate("New task")
            if (!opened) {
                EmaGenericUiController.type(text) { typed, _ ->
                    val result = if (typed) "I entered the calendar task." else "I could not access the calendar task editor."
                    speak(result); done(result)
                }
                return@postDelayed
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                EmaGenericUiController.type(text) { typed, _ ->
                    val saved = EmaAccessibilityService.activate("Save")
                    val result = if (typed && saved) "Calendar task created." else if (typed) "I entered the calendar task, but could not verify Save." else "I could not enter the calendar task."
                    speak(result); done(result)
                }
            }, 500L)
        }, 500L)
    }

    private fun handleCalendarEvent(command: String, done: (String) -> Unit) {
        val title = command
            .replaceFirst(Regex("(?i)^create (?:a )?calendar event\\s*"), "")
            .replaceFirst(Regex("(?i)^add calendar event\\s*"), "")
            .replaceFirst(Regex("(?i)^calendar event\\s*"), "")
            .replaceFirst(Regex("(?i)^create an event\\s*"), "")
            .trim()
        val timeMatch = Regex("(?i)^(.+?)\\s+(?:at|on)\\s+(.+)$").find(title)
        val eventTitle = timeMatch?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { title }
        val timeText = timeMatch?.groupValues?.getOrNull(2)?.trim().orEmpty()
        val trigger = if (timeText.isBlank()) null else EmaScheduler.parseTimeToFutureMillis(timeText)
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = Uri.parse("content://com.android.calendar/events")
            putExtra("title", eventTitle)
            if (trigger != null) {
                putExtra("beginTime", trigger)
                putExtra("endTime", trigger + 60 * 60 * 1000L)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onSuccess {
            val result = if (trigger != null) "I opened Calendar with $eventTitle scheduled for " +
                java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(trigger)) + "."
            else "I opened Calendar ready to create $eventTitle."
            speak(result); done(result)
        }.onFailure {
            val result = "I could not open the calendar event editor."
            speak(result); done(result)
        }
    }

    private fun isCloseAssistantCommand(lower: String): Boolean =
        lower == "close" || lower == "close ema" || lower == "dismiss ema" ||
            lower == "close assistant" || lower == "dismiss assistant" ||
            lower == "i'm done" || lower == "im done" || lower == "i am done"

    private fun adjustBrightness(deltaFraction: Float, done: (String) -> Unit) {
        if (!Settings.System.canWrite(context)) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            val message = "EMA needs permission to change system brightness. I opened the setting for that permission."
            speak(message); done(message); return
        }
        val current = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        val next = (current + (255f * deltaFraction)).toInt().coerceIn(1, 255)
        runCatching { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next) }
            .onSuccess { val percent = (next * 100 / 255f).toInt(); val message = "Brightness set to about $percent percent."; speak(message); done(message) }
            .onFailure { val message = "I could not change the system brightness."; speak(message); done(message) }
    }

    private fun setBrightnessPercent(value: String, done: (String) -> Unit) {
        val percent = Regex("""(?i)(\d{1,3})(?:\s*%)?""").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (percent == null || percent !in 1..100) { val message = "Tell me a brightness percentage from 1 to 100."; speak(message); done(message); return }
        if (!Settings.System.canWrite(context)) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            val message = "EMA needs permission to change system brightness. I opened the setting for that permission."
            speak(message); done(message); return
        }
        val value255 = (percent * 255 / 100f).toInt().coerceIn(1, 255)
        runCatching { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value255) }
            .onSuccess { val message = "Brightness set to $percent percent."; speak(message); done(message) }
            .onFailure { val message = "I could not change the system brightness."; speak(message); done(message) }
    }

    private fun setDnd(enabled: Boolean, done: (String) -> Unit) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        if (manager == null) { val message = "I could not access Do Not Disturb controls."; speak(message); done(message); return }
        if (!manager.isNotificationPolicyAccessGranted) {
            runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            val message = "EMA needs Do Not Disturb access. I opened the setting so you can grant it."
            speak(message); done(message); return
        }
        runCatching { manager.setInterruptionFilter(if (enabled) android.app.NotificationManager.INTERRUPTION_FILTER_NONE else android.app.NotificationManager.INTERRUPTION_FILTER_ALL) }
            .onSuccess { val message = if (enabled) "Do Not Disturb is on." else "Do Not Disturb is off."; speak(message); done(message) }
            .onFailure { val message = "Android did not allow EMA to change Do Not Disturb right now."; speak(message); done(message) }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        // When a live VoiceInteractionSession owns the response, it performs
        // the single final TTS operation and updates the assistant UI state.
        // Router-level speech remains enabled for scheduled/background commands
        // that have no visible voice session, avoiding duplicate speech.
        if (EmaVoiceInteractionSession.isActiveVisible()) return
        // Speech is a response side-effect, never a prerequisite for command
        // completion. TTS initialization/providers can fail independently of
        // the native action that was just executed.
        runCatching {
            val engine = ttsProvider()
            if (engine == null) {
                android.util.Log.w("EMA", "TTS is not initialized; delivering text response without speech")
                return@runCatching
            }
            val status = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ema")
            if (status == TextToSpeech.ERROR) {
                android.util.Log.w("EMA", "TTS rejected utterance: " + text)
            }
        }.onFailure {
            android.util.Log.w("EMA", "TTS failed; command result remains valid", it)
        }
    }
}
