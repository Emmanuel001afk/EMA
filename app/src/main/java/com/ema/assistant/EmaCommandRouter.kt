package com.ema.assistant

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EMA's command router.
 *
 * LAYER ORDER IS THE PRODUCT. The digital assistant runs first and does the
 * work; the AI is a second, optional layer that is only reached when the
 * deterministic layers genuinely have nothing to offer:
 *
 *   1. EmaNativeCommandPlanner   normalise, split compound requests
 *   2. literal device commands   volume, brightness, navigation, screen, clock
 *   3. EmaCommandGrammar         structured intents with explicit roles
 *   4. EmaNativeFallbackInterpreter  free-form requests grounded in installed apps
 *   5. AI tunnel                 ONLY if steps 1-4 produce nothing, the user has
 *                                not disabled it, and a tunnel is configured
 *
 * A missing, unreachable, or unaffordable AI backend therefore cannot stop EMA
 * from controlling the phone. The AI can only ever *propose* canonical commands;
 * execution and verification always happen on the device.
 */
class EmaCommandRouter(
    private val context: Context,
    private val ttsProvider: () -> TextToSpeech?
) {
    private val appEngine = EmaAppActionEngine(context)
    private val executionController = EmaExecutionController()
    private var lastAppName: String? = null

    private data class PendingTask(
        val type: String,
        val target: String,
        val app: String? = null,
        val body: String? = null,
        val sim: Int? = null,
        val candidates: List<String> = emptyList()
    )

    // Follow-up state is scoped to this router/session so separate Activity and
    // VoiceInteractionSession instances cannot consume one another's pending task.
    private var pendingTask: PendingTask? = null

    private var executionStage = "routing"
    private val commandHandler = Handler(Looper.getMainLooper())
    private val commandTimeoutMs = 60_000L

    private val prefs by lazy { context.getSharedPreferences("ema", Context.MODE_PRIVATE) }

    /** Which layer is allowed to answer. Native first by default. */
    private fun assistantMode(): EmaAssistantMode =
        EmaAssistantMode.fromKey(prefs.getString("assistant_mode", EmaAssistantMode.DEFAULT_KEY))

    /** Cancel delayed multi-step work when the Android voice session disappears. */
    fun shutdown() {
        commandHandler.removeCallbacksAndMessages(null)
        executionController.shutdown()
    }

    /**
     * The live command's single terminal guard and its deadline.
     *
     * A router instance serves one command at a time (the voice session, the
     * activity and the receivers each own their instance), so holding these on
     * the instance is what lets a multi-step sequence extend one shared deadline
     * instead of nesting a fresh timeout per step.
     */
    private var activeCompletion = AtomicBoolean(false)
    private var activeTimeout: Runnable? = null

    /** Reschedule the shared deadline, keeping the total budget honest. */
    private fun extendDeadline(totalMs: Long) {
        val timeout = activeTimeout ?: return
        commandHandler.removeCallbacks(timeout)
        commandHandler.postDelayed(timeout, totalMs)
    }

    fun execute(raw: String, done: (String) -> Unit) {
        // A command has one terminal result. This prevents a late callback,
        // timeout, or duplicated provider callback from producing conflicting
        // answers for the same user command.
        executionController.reset()
        val completed = AtomicBoolean(false)
        activeCompletion = completed
        val timeout = Runnable {
            if (!completed.compareAndSet(false, true)) return@Runnable
            executionController.shutdown()
            android.util.Log.e("EMA", "Command timed out after ${commandTimeoutMs}ms at stage=$executionStage: ${raw.trim()}")
            runCatching { done("I couldn't complete that command in time. The operation was stopped safely; I did not retry it automatically.") }
                .onFailure { android.util.Log.e("EMA", "Assistant timeout result delivery failed", it) }
        }
        activeTimeout = timeout
        commandHandler.postDelayed(timeout, commandTimeoutMs)

        val safeDone: (String) -> Unit = { result ->
            if (!completed.compareAndSet(false, true)) {
                android.util.Log.w("EMA", "Ignoring duplicate command completion: " + raw.trim())
            } else {
                commandHandler.removeCallbacks(timeout)
                activeTimeout = null
                Handler(Looper.getMainLooper()).post {
                    runCatching { done(result) }
                        .onFailure { android.util.Log.e("EMA", "Assistant result delivery failed", it) }
                }
            }
        }

        try {
            executeInternal(raw, safeDone, 0)
        } catch (t: Throwable) {
            android.util.Log.e("EMA", "Command execution crashed at stage=$executionStage: " + raw.trim(), t)
            safeDone("I couldn't complete that command because the operation failed during $executionStage. I did not retry it automatically.")
        }
    }

    /**
     * @param depth guards against a planner rewrite looping back into itself.
     */
    private fun executeInternal(raw: String, done: (String) -> Unit, depth: Int) {
        val command = raw.trim()
        if (command.isBlank()) {
            finishAssistantResponse("I didn't receive a command.", done)
            return
        }
        if (depth == 0 && isCloseAssistantCommand(command.lowercase(Locale.getDefault()))) {
            val message = "Closing EMA."
            speak(message)
            EmaVoiceInteractionSession.closeActive()
            done(message)
            return
        }

        // Pre-routing state helpers must never abort normal command routing.
        // Conversation history, stale pending tasks, or scheduler state are
        // auxiliary; a failure there must not make every command fail.
        if (depth == 0) {
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
        }

        val lower = command.lowercase(Locale.getDefault())

        // Planning is a distinct stage. Runtime native failures must not be
        // mislabeled as an "understanding" failure.
        executionStage = "planning"

        // Deterministic planning happens before individual handlers. It expands
        // natural compound requests into the same native actions used for
        // one-step commands. No AI call is involved here.
        if (depth < 3) {
            val planned = EmaNativeCommandPlanner.plan(command)
            executionStage = "executing"
            if (planned.size > 1) {
                // One shared deadline for the whole request, scaled by the number
                // of steps so a three-part command is not starved by a budget
                // sized for one. Per-step work is still bounded by the execution
                // controller.
                extendDeadline(commandTimeoutMs * planned.size)
                executeSequence(planned, 0, done, depth + 1)
                return
            }
            val routedCommand = planned.firstOrNull().orEmpty()
            if (routedCommand.isNotBlank() && routedCommand != command) {
                executeInternal(routedCommand, done, depth + 1)
                return
            }
        }
        executionStage = "executing"

        if (handleSystemCommand(lower, command, done)) return

        // LAYER 3: structured intents, understood deterministically.
        val interpreted = runCatching { EmaCommandGrammar.interpret(command) }.getOrElse {
            android.util.Log.e("EMA", "Native grammar failed for: $command", it)
            null
        }
        if (interpreted != null) {
            android.util.Log.i("EMA", "native grammar resolved: ${interpreted.toCanonical()}")
            dispatch(interpreted, done)
            return
        }

        val mode = assistantMode()

        // LAYER 5 (only in AI-assisted wording mode): let the AI interpret
        // unusual phrasing before EMA falls back to app grounding.
        if (mode == EmaAssistantMode.AI_ASSIST && mode.allowsAi && CentralHubClient.isConfigured(context)) {
            askAi(command, done)
            return
        }

        // LAYER 4: the offline safety net, grounded in what is installed.
        val fallback = runCatching {
            EmaNativeFallbackInterpreter.interpret(command, installedApps())
        }.getOrElse {
            android.util.Log.e("EMA", "Native fallback failed for: $command", it)
            null
        }
        if (fallback != null) {
            android.util.Log.i("EMA", "native fallback resolved (${fallback.via}): ${fallback.command.toCanonical()}")
            dispatch(fallback.command, done)
            return
        }

        // LAYER 5: the AI is the last resort, and only when it is allowed.
        if (mode.allowsAi && CentralHubClient.isConfigured(context)) {
            askAi(command, done)
            return
        }

        finishAssistantResponse(nativeOnlyExplanation(command, mode), done)
    }

    private fun installedApps(): List<EmaAppResolver.App> = runCatching {
        appEngine.installedAppLabels().map { label -> EmaAppResolver.App(label, "") }
    }.getOrDefault(emptyList())

    /**
     * Honest answer when no deterministic layer matched and the AI is switched
     * off or not configured. EMA never pretends a device action failed when the
     * real situation is that it did not understand the wording.
     */
    private fun nativeOnlyExplanation(command: String, mode: EmaAssistantMode): String {
        val reason = if (mode == EmaAssistantMode.NATIVE_ONLY) {
            "AI assistance is switched off, so I answered using only the on-device assistant."
        } else {
            "No AI service is configured, so I answered using only the on-device assistant."
        }
        return "I could not turn \"$command\" into a phone action. $reason " +
            "I can open and control apps, play and search media, send messages, make calls, " +
            "set alarms, timers and reminders, navigate Settings, and read or control the screen. " +
            "Try naming the app, for example: open Spotify and play Arcade."
    }

    // ------------------------------------------------------- system commands

    /**
     * Literal device commands that need no slot extraction. Returns true when
     * the command was consumed here.
     */
    private fun handleSystemCommand(lower: String, command: String, done: (String) -> Unit): Boolean {
        when {
            lower == "scroll down" || lower == "scroll up" || lower == "scroll to top" -> {
                val direction = if (lower.contains("up") || lower.contains("top")) "up" else "down"
                EmaGenericUiController.scroll(direction) { _, message -> speak(message); done(message) }
            }

            lower == "tap the first result" || lower == "select the first result" ||
                lower == "open the first result" || lower == "play the first result" -> {
                val ok = EmaAccessibilityService.tapFirstResult()
                val message = if (ok) "Selected the first visible result."
                else "I could not identify a selectable result on this screen."
                speak(message); done(message)
            }

            lower == "read screen with camera" || lower == "read everything on screen" ||
                lower == "what does the screen say" || lower == "what is written on my screen" -> {
                statusWithOcr(done)
            }

            lower == "read notifications" || lower == "show notifications" ||
                lower == "what are my notifications" -> {
                val message = EmaNotificationListenerService.describeActiveNotifications()
                speak(message); done(message)
            }

            lower == "read the last notification" || lower == "what was the last notification" -> {
                val message = EmaNotificationListenerService.latestNotifications.firstOrNull()
                    ?: "There are no recent notifications."
                speak(message); done(message)
            }

            lower == "what are my reminders" || lower == "list my reminders" ||
                lower == "show my reminders" || lower == "list reminders" ||
                lower == "what reminders do i have" -> {
                listReminders(done)
            }

            lower == "cancel all reminders" || lower == "delete all my reminders" ||
                lower == "clear my reminders" -> {
                val cancelled = EmaReminderScheduler.cancelMatching(context, "")
                val message = if (cancelled == 0) "You have no reminders to cancel."
                else "Cancelled $cancelled reminder${if (cancelled == 1) "" else "s"}."
                speak(message); done(message)
            }

            lower.startsWith("cancel my reminder ") || lower.startsWith("cancel the reminder ") ||
                lower.startsWith("delete my reminder ") || lower.startsWith("remove my reminder ") -> {
                val about = command
                    .replaceFirst(Regex("(?i)^(?:cancel|delete|remove)\\s+(?:my|the)\\s+reminder\\s+"), "")
                    .replaceFirst(Regex("(?i)^(?:about|for|to|that says)\\s+"), "")
                    .trim()
                val cancelled = EmaReminderScheduler.cancelMatching(context, about)
                val message = when {
                    cancelled == 0 && about.isBlank() -> "Tell me which reminder to cancel."
                    cancelled == 0 -> "I have no reminder matching \"$about\"."
                    cancelled == 1 -> "Cancelled the reminder about $about."
                    else -> "Cancelled $cancelled reminders matching $about."
                }
                speak(message); done(message)
            }

            lower == "list files" || lower == "show files" || lower == "list my files" -> {
                val message = EmaFileAccessEngine.list()
                speak(message.take(1200)); done(message)
            }

            lower.startsWith("list files in ") -> {
                val message = EmaFileAccessEngine.list(command.substringAfter("list files in ").trim())
                speak(message.take(1200)); done(message)
            }

            lower.startsWith("find file ") || lower.startsWith("find the file ") ||
                lower.startsWith("search for file ") -> {
                val query = command
                    .replaceFirst(Regex("(?i)^find(?: the)? file\\s+"), "")
                    .replaceFirst(Regex("(?i)^search for file\\s+"), "")
                    .trim()
                val message = EmaFileAccessEngine.find(query)
                speak(message.take(1200)); done(message)
            }

            lower.startsWith("dismiss notification") || lower.startsWith("clear notification") -> {
                val target = command.substringAfter(' ')
                    .replaceFirst(Regex("(?i)^(notification|notifications)\\s*"), "").trim()
                val ok = EmaNotificationListenerService.dismissMatching(target)
                val message = if (ok) "I dismissed the matching notification."
                else "I could not find a matching active notification."
                speak(message); done(message)
            }

            lower == "open notifications" || lower == "show notification shade" -> {
                globalAction(AccessibilityGlobal.NOTIFICATIONS, "Showing notifications.", done)
            }

            lower.startsWith("create a calendar task ") || lower.startsWith("calendar task ") ||
                lower.startsWith("set a calendar task ") -> {
                handleCalendarTask(command, done)
            }

            lower.startsWith("create a calendar event ") || lower.startsWith("add calendar event ") ||
                lower.startsWith("calendar event ") || lower.startsWith("create an event ") ||
                lower.startsWith("add an event ") -> {
                handleCalendarEvent(command, done)
            }

            lower.startsWith("schedule my day") || lower.startsWith("set my schedule for today") ||
                lower.startsWith("set today's schedule") || lower.startsWith("set my schedule") -> {
                handleDaySchedule(command, done)
            }

            lower == "copy screen text" || lower == "copy this screen" -> {
                val text = EmaAccessibilityService.currentScreenText(20000)
                if (text.isBlank()) {
                    val message = EmaAccessibilityService.availabilityProblem()
                        ?: "I could not read text from this screen."
                    speak(message); done(message)
                } else {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("EMA screen text", text))
                    val message = if (clipboard != null) "Copied the screen text to the clipboard."
                    else "I could not access the clipboard."
                    speak(message); done(message)
                }
            }

            lower == "analyze this image" || lower == "analyze the screen visually" ||
                lower == "what am i looking at visually" -> {
                EmaAccessibilityService.describeScreenImage { labels ->
                    val ocr = EmaAccessibilityService.currentScreenText(8000)
                    val prompt = "Describe what is visible on the current Android screen. Image labels: " +
                        labels + "\nVisible text: " + ocr
                    CentralHubClient(context).ask(prompt) { answer ->
                        val result = answer.ifBlank {
                            if (labels.isBlank()) "I could not identify visual content on this screen."
                            else "I can identify: " + labels
                        }
                        deliverAiAnswer(result, done)
                    }
                }
            }

            lower == "what time is it" || lower == "tell me the time" || lower == "current time" -> {
                val time = java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date())
                val message = "It is $time."
                speak(message); done(message)
            }

            lower == "what is the date" || lower == "what day is it" || lower == "today's date" -> {
                val date = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(java.util.Date())
                val message = "Today is $date."
                speak(message); done(message)
            }

            lower == "battery level" || lower == "what is my battery" || lower == "battery status" ||
                lower == "how much battery is left" -> {
                val message = batteryStatus()
                speak(message); done(message)
            }

            lower == "storage status" || lower == "how much storage do i have" ||
                lower == "check my storage" -> {
                val message = storageStatus()
                speak(message); done(message)
            }

            lower == "take a screenshot" || lower == "screenshot" || lower == "capture the screen" -> {
                takeScreenshot(done)
            }

            lower == "turn on flashlight" || lower == "turn on the flashlight" ||
                lower == "flashlight on" || lower == "turn on torch" -> setFlashlight(true, done)

            lower == "turn off flashlight" || lower == "turn off the flashlight" ||
                lower == "flashlight off" || lower == "turn off torch" -> setFlashlight(false, done)

            lower == "increase brightness" || lower == "brightness up" -> adjustBrightness(0.1f, done)
            lower == "decrease brightness" || lower == "brightness down" -> adjustBrightness(-0.1f, done)
            lower.startsWith("set brightness to ") ->
                setBrightnessPercent(command.substringAfter("set brightness to ").trim(), done)

            lower == "turn on dnd" || lower == "turn dnd on" || lower == "enable dnd" ||
                lower == "do not disturb on" -> setDnd(true, done)
            lower == "turn off dnd" || lower == "turn dnd off" || lower == "disable dnd" ||
                lower == "do not disturb off" -> setDnd(false, done)

            lower == "open quick settings" || lower == "open control center" ||
                lower == "open notification center" ->
                globalAction(AccessibilityGlobal.QUICK_SETTINGS, "Opening quick settings.", done)

            lower == "power menu" || lower == "show power menu" ->
                globalAction(AccessibilityGlobal.POWER_DIALOG, "Showing the power menu.", done)

            lower == "restart phone" || lower == "reboot phone" -> {
                val message = "Android does not expose a normal-app reboot command. I opened the power menu so you can restart the phone."
                speak(message)
                EmaAccessibilityService.performGlobalAction(AccessibilityGlobal.POWER_DIALOG)
                done(message)
            }

            lower == "switch off phone" || lower == "turn off phone" || lower == "power off phone" -> {
                val message = "Android does not expose direct phone shutdown to a normal app. I opened the power menu for you."
                speak(message)
                EmaAccessibilityService.performGlobalAction(AccessibilityGlobal.POWER_DIALOG)
                done(message)
            }

            lower == "back" || lower == "go back" ->
                globalAction(AccessibilityGlobal.BACK, "Going back.", done)

            lower == "home" || lower == "go home" ->
                globalAction(AccessibilityGlobal.HOME, "Going home.", done)

            lower == "recent apps" || lower == "show recent apps" || lower == "open recent apps" ||
                lower == "switch apps" ->
                globalAction(AccessibilityGlobal.RECENTS, "Showing recent apps.", done)

            lower == "volume up" || lower == "increase volume" ->
                adjustVolume(AudioManager.ADJUST_RAISE, "Volume increased.", done)
            lower == "volume down" || lower == "decrease volume" ->
                adjustVolume(AudioManager.ADJUST_LOWER, "Volume decreased.", done)
            lower == "mute" || lower == "mute volume" ->
                adjustVolume(AudioManager.ADJUST_MUTE, "Volume muted.", done)
            lower == "unmute" || lower == "unmute volume" ->
                adjustVolume(AudioManager.ADJUST_UNMUTE, "Volume unmuted.", done)
            lower == "max volume" || lower == "full volume" || lower == "volume to maximum" ->
                setVolumePercent(100, done)
            lower.startsWith("set volume to ") ->
                setVolumePercent(command.substringAfter("set volume to ").trim(), done)

            lower == "lock phone" || lower == "lock the phone" || lower == "lock screen" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    globalAction(AccessibilityGlobal.LOCK_SCREEN, "Locking the phone.", done)
                } else {
                    val result = "Phone locking is not supported on this Android version."
                    speak(result); done(result)
                }
            }

            lower == "open wifi settings" || lower == "open wi-fi settings" ->
                openSettings(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings", done)
            lower == "open bluetooth settings" ->
                openSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings", done)
            lower == "open display settings" ->
                openSettings(Settings.ACTION_DISPLAY_SETTINGS, "display settings", done)
            lower == "open sound settings" ->
                openSettings(Settings.ACTION_SOUND_SETTINGS, "sound settings", done)
            lower == "open notification settings" -> openAppNotificationSettings(done)
            lower == "open accessibility settings" ->
                openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, "Accessibility settings", done)
            lower == "open assistant settings" ->
                openSettings(Settings.ACTION_VOICE_INPUT_SETTINGS, "assistant settings", done)

            lower == "read screen" || lower == "read this screen" || lower == "what is on my screen" -> {
                val screen = EmaAccessibilityService.currentScreenText()
                val message = if (screen.isBlank()) {
                    EmaAccessibilityService.availabilityProblem() ?: "This screen exposes no readable text."
                } else screen
                speak(message.take(3000))
                done(message)
            }

            lower == "inspect screen" || lower == "inspect this screen" ||
                lower == "understand this screen" || lower == "analyze this screen" -> {
                val ui = EmaAccessibilityService.inspectCurrentUi()
                val message = if (ui.isBlank()) {
                    EmaAccessibilityService.availabilityProblem()
                        ?: "This application does not expose accessible controls on the current screen."
                } else ui
                speak(if (ui.isBlank()) message else "I inspected the accessible controls on this screen.")
                done(message)
            }

            lower.startsWith("tap ") || lower.startsWith("press ") || lower.startsWith("click ") -> {
                val target = command.substringAfter(' ').trim()
                EmaGenericUiController.tap(target) { _, message -> speak(message); done(message) }
            }

            lower.startsWith("long press ") -> {
                val target = command.substringAfter("long press ").trim()
                val ok = EmaAccessibilityService.longPressText(target)
                val message = if (ok) "Long-pressed $target." else "I could not find $target to long-press."
                speak(message); done(message)
            }

            lower.startsWith("type ") -> {
                val value = command.substringAfter("type ").trim()
                EmaGenericUiController.type(value) { _, message -> speak(message); done(message) }
            }

            lower == "what can you do" || lower == "what can you do?" ||
                lower == "what can you do for me" || lower == "what can you do for me?" ||
                lower == "help" || lower == "what do you do" -> {
                val message = capabilitiesDescription()
                speak(message); done(message)
            }

            lower == "what mode are you in" || lower == "is ai enabled" || lower == "are you using ai" -> {
                val mode = assistantMode()
                val message = "Assistant mode: ${mode.label}. ${mode.explanation}"
                speak(message); done(message)
            }

            lower == "turn off ai" || lower == "disable ai" || lower == "no ai mode" -> {
                setAssistantMode(EmaAssistantMode.NATIVE_ONLY, done)
            }

            lower == "turn on ai" || lower == "enable ai" -> {
                setAssistantMode(EmaAssistantMode.NATIVE_FIRST, done)
            }

            else -> return false
        }
        return true
    }

    private object AccessibilityGlobal {
        val BACK = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
        val HOME = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
        val RECENTS = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
        val NOTIFICATIONS = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        val QUICK_SETTINGS = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        val POWER_DIALOG = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        val LOCK_SCREEN = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        val SCREENSHOT = android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
    }

    private fun capabilitiesDescription(): String {
        val mode = assistantMode()
        val ai = when (mode) {
            EmaAssistantMode.NATIVE_ONLY -> "AI is switched off; everything runs on the phone."
            EmaAssistantMode.NATIVE_FIRST -> "The on-device assistant runs first; AI is only a fallback for questions."
            EmaAssistantMode.AI_ASSIST -> "AI helps with unusual wording; the phone actions are still performed on-device."
        }
        return "I can open and control any app, search inside apps, play music and video, " +
            "send messages and make calls, set alarms, timers, reminders and daily routines, " +
            "navigate Settings, read or inspect the screen, tap and type for you, control volume, " +
            "brightness, flashlight, Do Not Disturb, take screenshots, and check battery and storage. $ai"
    }

    private fun setAssistantMode(mode: EmaAssistantMode, done: (String) -> Unit) {
        prefs.edit().putString("assistant_mode", mode.key).apply()
        val message = "Assistant mode set to ${mode.label}. ${mode.explanation}"
        speak(message); done(message)
    }

    // ------------------------------------------------------------- dispatch

    /** Execute a structured command produced by the deterministic layers. */
    private fun dispatch(command: EmaCommand, done: (String) -> Unit) {
        executionStage = "executing"
        when (command) {
            is EmaCommand.SendMessage -> sendMessageCommand(command, done)
            is EmaCommand.OpenSmsComposer -> openMessageComposer(command.recipient, command.body, done)
            is EmaCommand.CallContact -> callContact(
                if (command.sim == null) command.name else "${command.name} with SIM ${command.sim}",
                done
            )
            is EmaCommand.PlayMedia -> playMedia(command, done)
            is EmaCommand.MediaControl -> mediaControl(command.action, done)
            is EmaCommand.SearchInApp -> searchCommand(command, done)
            is EmaCommand.WebSearch -> googleSearch(command.query, done)
            is EmaCommand.OpenApp -> openAppOrSection(command.app, null, done)
            is EmaCommand.OpenAppSection -> openAppOrSection(command.app, command.section, done)
            is EmaCommand.OpenSettingsPage -> openSettingsPage(command.target, done)
            is EmaCommand.NavigateTo -> openMaps(command.destination, done)
            is EmaCommand.SetAlarm -> openAlarm(command.timeText, done)
            is EmaCommand.SetTimer -> openTimer(command.durationText, done)
            is EmaCommand.SetReminder -> createReminder(command.text, command.timeText, done)
            is EmaCommand.SaveContact -> saveContact(command.name, command.number, done)
        }
    }

    private fun sendMessageCommand(command: EmaCommand.SendMessage, done: (String) -> Unit) {
        if (command.body.isBlank()) {
            // No body: open the right place and ask what to say, rather than
            // sending an empty message or guessing content.
            if (command.app.isNullOrBlank()) {
                openMessageComposer(command.recipient, "", done)
                return
            }
            val target = appEngine.findApp(command.app)
            if (target == null) {
                val message = "I could not find ${command.app} on this phone."
                speak(message); done(message); return
            }
            pendingTask = PendingTask("message-body", command.recipient, target.label)
            appEngine.openApp(target.label)
            lastAppName = target.label
            val message = "I opened ${target.label}. What should I say to ${command.recipient}?"
            speak(message); done(message)
            return
        }
        if (command.app.isNullOrBlank()) {
            // No app named: prefer a chat app EMA has used recently, else SMS.
            val remembered = lastAppName
            if (!remembered.isNullOrBlank()) {
                sendAppMessage(command.recipient, remembered, command.body, done)
            } else {
                sendAppMessage(command.recipient, "Messages", command.body, done)
            }
            return
        }
        sendAppMessage(command.recipient, command.app, command.body, done)
    }

    private fun playMedia(command: EmaCommand.PlayMedia, done: (String) -> Unit) {
        val app = command.app ?: lastAppName
        if (app.isNullOrBlank()) {
            // No app named and no recent app: resume or start via the transport.
            val ok = EmaAccessibilityService.mediaTransport(EmaAccessibilityService.MediaTransportAction.PLAY)
            val message = if (ok) "I asked Android to start playback. Tell me the app if you want a specific song."
            else "Tell me which app to play ${command.query} in, for example: play ${command.query} on Spotify."
            speak(message); done(message)
            return
        }
        appEngine.playMediaInApp(app, command.query) { _, result ->
            if (result.isNotBlank()) lastAppName = app
            speak(result); done(result)
        }
    }

    private fun mediaControl(action: EmaCommand.MediaControl.Action, done: (String) -> Unit) {
        when (action) {
            EmaCommand.MediaControl.Action.TOGGLE -> {
                val ok = EmaAccessibilityService.toggleMediaPlayback()
                val message = if (ok) "Toggling media playback." else "I could not control media playback."
                speak(message); done(message)
            }
            EmaCommand.MediaControl.Action.PLAY -> transport(EmaAccessibilityService.MediaTransportAction.PLAY, "Starting playback.", done)
            EmaCommand.MediaControl.Action.PAUSE -> transport(EmaAccessibilityService.MediaTransportAction.PAUSE, "Pausing playback.", done)
            EmaCommand.MediaControl.Action.RESUME -> transport(EmaAccessibilityService.MediaTransportAction.PLAY, "Resuming playback.", done)
            EmaCommand.MediaControl.Action.STOP -> transport(EmaAccessibilityService.MediaTransportAction.STOP, "Stopping playback.", done)
            EmaCommand.MediaControl.Action.NEXT -> transport(EmaAccessibilityService.MediaTransportAction.NEXT, "Skipping to the next track.", done)
            EmaCommand.MediaControl.Action.PREVIOUS -> transport(EmaAccessibilityService.MediaTransportAction.PREVIOUS, "Going back to the previous track.", done)
            EmaCommand.MediaControl.Action.WHATS_PLAYING -> {
                val ui = EmaAccessibilityService.currentScreenText(4000)
                val playing = EmaAccessibilityService.isAudioPlaying()
                val message = when {
                    ui.isBlank() && !playing -> EmaAccessibilityService.availabilityProblem() ?: "I could not identify what is playing."
                    !playing -> "Nothing appears to be playing right now."
                    else -> "This is what is on screen while audio plays:\n" + ui.take(600)
                }
                speak(message.take(1200)); done(message)
            }
        }
    }

    private fun transport(
        action: EmaAccessibilityService.MediaTransportAction,
        successMessage: String,
        done: (String) -> Unit
    ) {
        val ok = EmaAccessibilityService.mediaTransport(action)
        val message = if (ok) successMessage else "I could not control media playback on this phone."
        speak(message); done(message)
    }

    private fun searchCommand(command: EmaCommand.SearchInApp, done: (String) -> Unit) {
        val app = command.app ?: lastAppName
        if (app.isNullOrBlank()) {
            webSearch(command.query, done)
            return
        }
        val target = appEngine.findApp(app)
        if (target == null) {
            // The named app is not installed: a web search still answers the request.
            webSearch(command.query, done)
            return
        }
        lastAppName = target.label
        speak("Searching ${target.label} for ${command.query}.")
        if (command.selectFirst) {
            appEngine.openAndSearchThenTapFirst(target.label, command.query) { _, result -> speak(result); done(result) }
        } else {
            appEngine.openAndSearch(target.label, command.query) { _, result -> speak(result); done(result) }
        }
    }

    /**
     * Open an app, optionally then activate a section inside it.
     *
     * The longest installed application name at the start of the phrase is the
     * app; everything after it is the destination, never a second app.
     */
    private fun openAppOrSection(appPhrase: String, section: String?, done: (String) -> Unit) {
        if (!section.isNullOrBlank()) {
            navigateInsideApp(appPhrase, section, done)
            return
        }
        val direct = appEngine.findApp(appPhrase)
        if (direct != null) {
            openConfirmedApp(direct.label, done)
            return
        }
        // "WhatsApp settings", "Spotify playlists": split at the longest
        // installed app prefix.
        val words = appPhrase.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (count in words.size - 1 downTo 1) {
            val candidateApp = words.take(count).joinToString(" ")
            val target = appEngine.findApp(candidateApp) ?: continue
            val remainder = words.drop(count).joinToString(" ").trim()
            if (remainder.isBlank()) {
                openConfirmedApp(target.label, done)
                return
            }
            navigateInsideApp(target.label, remainder, done)
            return
        }
        // Not an app at all: it may be a website or a document target.
        openTarget(appPhrase, done)
    }

    private fun openConfirmedApp(label: String, done: (String) -> Unit) {
        val target = appEngine.openApp(label)
        if (target == null) {
            val message = "I couldn't open $label on this phone."
            speak(message); done(message); return
        }
        lastAppName = target.label
        val message = "Opened ${target.label}."
        speak(message); done(message)
    }

    private fun openSettingsPage(target: String, done: (String) -> Unit) {
        if (target.isBlank()) {
            openSettings(Settings.ACTION_SETTINGS, "Settings", done)
            return
        }
        // Well-known pages have direct system intents, which is more reliable
        // than navigating the Settings hierarchy by sight.
        val directAction = when (EmaAppResolver.normalize(target)) {
            "wi fi", "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness", "screen" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume", "ringtone" -> Settings.ACTION_SOUND_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "airplane mode" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "date and time" -> Settings.ACTION_DATE_SETTINGS
            "language", "language and input" -> Settings.ACTION_LOCALE_SETTINGS
            "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
            "assistant", "voice" -> Settings.ACTION_VOICE_INPUT_SETTINGS
            "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            else -> null
        }
        if (directAction != null) {
            if (directAction == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                openAppNotificationSettings(done)
            } else {
                openSettings(directAction, "$target settings", done)
            }
            return
        }
        navigateInsideSettings(target, done)
    }

    // ---------------------------------------------------------- sequences

    private fun executeSequence(
        steps: List<String>,
        index: Int,
        done: (String) -> Unit,
        depth: Int
    ) {
        if (index >= steps.size) {
            executionController.shutdown()
            done("Done.")
            return
        }

        executionController.runStep(
            // executeInternal, not execute: the outer call already installed the
            // single deadline and terminal guard for this user request. Calling
            // execute() here used to nest another 60s timeout per step, and a
            // step that finished after the outer timeout could advance the
            // sequence the user had been told had failed.
            action = { finish -> executeInternal(steps[index], finish, depth) },
            onTimeout = { "I couldn't complete step " + (index + 1) + " of " + steps.size + " before the execution timeout." }
        ) { result ->
            if (isActionFailure(result)) {
                executionController.shutdown()
                done(result)
            } else if (index == steps.lastIndex) {
                executionController.shutdown()
                done(result)
            } else {
                executeSequence(steps, index + 1, done, depth)
            }
        }
    }

    // --------------------------------------------------------- scheduling

    private fun handleRecurringRoutine(command: String, done: (String) -> Unit): Boolean {
        val match = Regex("(?i)^every\\s+(day|weekday|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+at\\s+(.+?)\\s*:\\s*(.+)$").find(command)
            ?: Regex("(?i)^every\\s+(day|weekday|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+at\\s+(.+?)\\s+(?:to|and)\\s+(.+)$").find(command)
            ?: return false
        val recurrence = match.groupValues[1].lowercase()
        val time = match.groupValues[2].trim()
        val action = match.groupValues[3].trim()
        if (action.isBlank()) return false

        val moment = EmaTimeParser.parse(time) ?: return false
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = moment.millis }
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)

        val ok = if (recurrence == "day" || recurrence == "weekday") {
            EmaRoutineScheduler.scheduleDaily(context, action, hour, minute, weekdaysOnly = recurrence == "weekday")
        } else {
            val day = mapOf(
                "sunday" to java.util.Calendar.SUNDAY, "monday" to java.util.Calendar.MONDAY,
                "tuesday" to java.util.Calendar.TUESDAY, "wednesday" to java.util.Calendar.WEDNESDAY,
                "thursday" to java.util.Calendar.THURSDAY, "friday" to java.util.Calendar.FRIDAY,
                "saturday" to java.util.Calendar.SATURDAY
            )[recurrence] ?: return false
            EmaRoutineScheduler.scheduleWeekly(context, action, day, hour, minute)
        }
        val result = if (ok) "Recurring routine scheduled: every $recurrence at ${EmaTimeParser.describeShort(moment.millis)}."
        else "Android could not schedule that recurring routine."
        speak(result); done(result)
        return true
    }

    /**
     * One-shot timed actions.
     *
     * The pattern is deliberately narrow. A loose time matcher used to run over
     * every command, which meant a song title such as "one - dance" could be
     * read as "schedule 'dance' at 1 o'clock". A scheduling cue is now required.
     */
    private fun handleScheduledAction(command: String, done: (String) -> Unit): Boolean {
        val lower = command.lowercase(Locale.getDefault())
        val hasCue = Regex("(?i)\\b(?:by|at)\\s+[0-9one-ten-elf-wlvtw]+").containsMatchIn(lower) ||
            lower.contains(":") || lower.contains(";")
        if (!hasCue) return false

        val time = "[0-9]{1,2}(?::[0-9]{2})?\\s*(?:am|pm)|" +
            "(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\s*(?::[0-9]{2})?\\s*(?:am|pm)|" +
            "[0-9]{1,2}:[0-9]{2}"
        val pattern = Regex(
            "(?i)(?:^|[,;]|\\bthen\\b)\\s*(?:(?:by|at)\\s+)?($time)\\s*(?:[,;:])-?\\s*(.+?)(?=\\s*(?:[,;]|\\bthen\\b)\\s*(?:(?:by|at)\\s+)?(?:$time)\\s*[,;:]|$)"
        )
        val matches = pattern.findAll(command).toList()
        if (matches.isEmpty()) return false

        var count = 0
        val failed = mutableListOf<String>()
        for (match in matches) {
            val action = match.groupValues[2].trim().trim(',', ';', '-', '\u2013', '\u2014')
            if (action.isBlank()) continue
            val timeText = match.groupValues[1].trim()
            val trigger = EmaTimeParser.parseToFutureMillis(timeText)
            if (trigger != null && EmaScheduler.schedule(context, action, trigger)) count++
            else failed += "$action at $timeText"
        }

        if (count == 0 && failed.isEmpty()) return false
        val message = buildString {
            if (count > 0) append("Scheduled ").append(count).append(" action").append(if (count == 1) "" else "s").append(".")
            if (failed.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("Could not schedule: ").append(failed.joinToString("; "))
            }
        }
        speak(message); done(message); return true
    }

    // -------------------------------------------------------- navigation UI

    /**
     * Generic navigation: open the requested app, inspect the resulting UI,
     * locate the requested destination, and retry by scrolling when the
     * destination is not initially visible. No app-specific button names are
     * assumed here.
     */
    private fun navigateInsideApp(appName: String, destination: String, done: (String) -> Unit) {
        val target = appEngine.findApp(appName)
        if (target == null) {
            val result = "I could not find $appName on this phone."
            speak(result); done(result); return
        }

        val unavailable = EmaAccessibilityService.availabilityProblem()
        if (unavailable != null) {
            // Still open the app: that part needs no screen control.
            appEngine.openApp(target.label)
            lastAppName = target.label
            val result = "I opened ${target.label}, but $unavailable"
            speak(result); done(result); return
        }

        appEngine.openApp(target.label)
        lastAppName = target.label
        navigateVisibleDestination(destination, target.label, 0, done)
    }

    /**
     * Settings uses the same generic screen-navigation engine as third-party
     * apps.
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
            val result = "I opened $contextLabel."
            speak(result); done(result); return
        }

        val delay = if (attempt == 0) 900L else 400L
        commandHandler.postDelayed({
            EmaGenericUiController.tap(destination) { ok, detail ->
                if (ok) {
                    val result = "Opened $destination in $contextLabel."
                    speak(result); done(result)
                    return@tap
                }

                if (attempt >= 4) {
                    val result = "I opened $contextLabel, but could not locate $destination. $detail"
                    speak(result); done(result)
                    return@tap
                }

                // Re-inspect before changing the screen. If the destination is
                // present in accessible text, give it another exact opportunity;
                // otherwise scroll the currently scrollable view.
                val screen = EmaAccessibilityService.currentScreenText(12000)
                val destinationWords = destination.lowercase(Locale.getDefault())
                    .split(Regex("\\s+"))
                    .filter { it.length > 1 }

                val textMatch = destinationWords.isNotEmpty() &&
                    destinationWords.any { word -> screen.lowercase(Locale.getDefault()).contains(word) }

                if (textMatch) {
                    EmaAccessibilityService.tapTextExact(destination)
                    commandHandler.postDelayed({
                        navigateVisibleDestination(destination, contextLabel, attempt + 1, done)
                    }, 300L)
                } else {
                    EmaGenericUiController.scroll("down") { _, _ ->
                        navigateVisibleDestination(destination, contextLabel, attempt + 1, done)
                    }
                }
            }
        }, delay)
    }

    // ----------------------------------------------------------- contacts

    private fun saveContact(name: String, number: String, done: (String) -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(Manifest.permission.WRITE_CONTACTS)
            val result = "EMA needs Contacts write access to save a contact. Grant it, then repeat the command."
            speak(result); done(result); return
        }

        val cleanNumber = number.trim()
        val cleanName = name.trim()

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

        val candidates = ContactLookup.find(context, recipient)

        if (candidates.isEmpty()) {
            val result = "I couldn't find $recipient in your contacts."
            speak(result); done(result); return
        }

        val best = ContactLookup.best(candidates)

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

    private fun handlePendingFollowUp(command: String, done: (String) -> Unit): Boolean {
        val task = pendingTask ?: return false
        val lower = command.lowercase(Locale.getDefault()).trim()

        // "what time should I remind you?" follow-up: the utterance is the time.
        if (task.type == "reminder-time") {
            val moment = EmaTimeParser.parse(command.trim())
            if (moment == null) return false
            pendingTask = null
            val ok = EmaReminderScheduler.schedule(context, task.target, moment.millis)
            val result = if (ok) "Reminder set for ${EmaTimeParser.describe(moment.millis)}: ${task.target}."
            else "Android could not schedule that reminder."
            speak(result); done(result)
            return true
        }

        // "what should I say?" follow-up: the whole utterance is the body.
        if (task.type == "message-body" && task.app != null) {
            val body = command.trim()
                .replaceFirst(Regex("(?i)^(say|tell him|tell her|write|message)\\s+"), "")
                .trim()
            if (body.isBlank()) return false
            pendingTask = null
            sendAppMessage(task.target, task.app, body, done)
            return true
        }

        val exactOriginal = lower.contains("without") &&
            (lower.contains("prefix") || lower.contains("suffix") ||
                lower.contains("anything before") || lower.contains("anything after"))
        val ordinal = when {
            Regex("(?i)\\b(first|1st|one)\\b").containsMatchIn(command) -> 0
            Regex("(?i)\\b(second|2nd|two)\\b").containsMatchIn(command) -> 1
            Regex("(?i)\\b(third|3rd|three)\\b").containsMatchIn(command) -> 2
            else -> -1
        }
        val sim = Regex("(?i)\\b(?:with\\s+)?(?:sim|slot)\\s*([12])\\b").find(command)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
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
        val sim = Regex("(?i)\\b(?:with\\s+)?(?:sim|slot)\\s*([12])\\s*$").find(rawName)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val name = rawName.replace(Regex("(?i)\\s+(?:with\\s+)?(?:sim|slot)\\s*[12]\\s*$"), "").trim()
        if (name.isBlank()) {
            val message = "Tell me who to call."
            speak(message); done(message); return
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

        val candidates = ContactLookup.findPhones(context, name)
        if (candidates.isEmpty()) {
            val result = "I couldn't find $name in your contacts."
            speak(result); done(result); return
        }

        val best = ContactLookup.best(candidates)
        if (best.size > 1) {
            val names = best.take(3).joinToString(", ") { it.displayName }
            pendingTask = PendingTask("call", name, sim = sim, candidates = best.map { it.displayName }.distinct())
            val result = "I found more than one possible contact for $name: $names. Tell me which one."
            speak(result); done(result); return
        }

        pendingTask = null
        val selected = best.first()
        placeCall(selected.phone.orEmpty().ifBlank { name }, selected.displayName, done, sim)
    }

    private fun placeCall(number: String, displayName: String, done: (String) -> Unit, sim: Int? = null) {
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(Manifest.permission.CALL_PHONE)
            val result = "EMA needs phone-call permission before it can call $displayName. Grant it, then say call $displayName again."
            speak(result); done(result); return
        }

        runCatching {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (sim != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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

    // --------------------------------------------------------- system state

    private fun batteryStatus(): String {
        val manager = runCatching {
            context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        }.getOrNull()
        val level = manager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = runCatching { manager?.isCharging == true }.getOrDefault(false)
        return if (level < 0) "I could not read the battery level on this phone."
        else "Battery is at $level percent" + if (charging) " and charging." else "."
    }

    private fun storageStatus(): String = runCatching {
        val stats = StatFs(Environment.getDataDirectory().path)
        val freeGb = stats.availableBytes / 1_073_741_824.0
        val totalGb = stats.totalBytes / 1_073_741_824.0
        String.format(Locale.getDefault(), "Storage: %.1f GB free of %.1f GB.", freeGb, totalGb)
    }.getOrElse { "I could not read the storage information on this phone." }

    private fun takeScreenshot(done: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val message = "Taking a screenshot needs Android 9 or newer."
            speak(message); done(message); return
        }
        val ok = EmaAccessibilityService.performGlobalAction(AccessibilityGlobal.SCREENSHOT)
        val message = if (ok) "Screenshot taken."
        else EmaAccessibilityService.availabilityProblem() ?: "Android did not allow the screenshot."
        speak(message); done(message)
    }

    private fun setFlashlight(enabled: Boolean, done: (String) -> Unit) {
        val result = runCatching {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
                return@runCatching "This phone has no camera flash for me to control."
            }
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE)
                as? android.hardware.camera2.CameraManager
                ?: return@runCatching "This phone does not expose camera controls to EMA."
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                runCatching {
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }.getOrDefault(false)
            } ?: return@runCatching "This phone has no flash available to EMA."
            cameraManager.setTorchMode(cameraId, enabled)
            if (enabled) "Flashlight is on." else "Flashlight is off."
        }.getOrElse { error ->
            android.util.Log.e("EMA", "Flashlight control failed", error)
            // The two failure modes need different user guidance: another app
            // holding the camera is transient, a missing permission is not.
            when (error) {
                is SecurityException ->
                    "Android did not allow EMA to control the flashlight. Grant EMA camera permission in Settings and try again."
                is IllegalArgumentException ->
                    "The flashlight is busy or unavailable right now. Close any app using the camera and try again."
                else -> "I could not control the flashlight on this phone."
            }
        }
        speak(result); done(result)
    }

    // ------------------------------------------------------------- helpers

    private fun statusWithOcr(done: (String) -> Unit) {
        val accessible = EmaAccessibilityService.currentScreenText()
        EmaAccessibilityService.readScreenWithOcr { ocr ->
            val combined = listOf(accessible, ocr).filter { it.isNotBlank() }.distinct().joinToString("\n")
            val result = combined.ifBlank {
                EmaAccessibilityService.availabilityProblem() ?: "I could not read text from the current screen."
            }.take(6000)
            speak(result); done(result)
        }
    }

    private fun adjustVolume(direction: Int, message: String, done: (String) -> Unit) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            val result = "I could not access the phone volume controls."
            speak(result); done(result); return
        }
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI) }
        speak(message); done(message)
    }

    private fun setVolumePercent(value: String, done: (String) -> Unit) {
        val percent = Regex("""(?i)(\d{1,3})(?:\s*%)?""").find(value)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (percent == null) {
            val message = "Tell me a volume percentage from 0 to 100."
            speak(message); done(message); return
        }
        setVolumePercent(percent, done)
    }

    private fun setVolumePercent(percent: Int, done: (String) -> Unit) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            val message = "I could not access the phone volume controls."
            speak(message); done(message); return
        }
        val clamped = percent.coerceIn(0, 100)
        val result = runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (max * clamped / 100f).toInt().coerceIn(0, max)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            "Volume set to $clamped percent."
        }.getOrElse { "I could not change the volume." }
        speak(result); done(result)
    }

    private fun openTimer(value: String, done: (String) -> Unit) {
        val duration = EmaTimeParser.parseDuration(value)
        if (duration == null) {
            val result = "Tell me the timer duration, for example: set a timer for 10 minutes."
            speak(result); done(result); return
        }
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, duration.seconds)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "EMA timer")
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onSuccess {
            val result = "Timer set for $value."
            speak(result); done(result)
        }.onFailure {
            val result = "I could not start the phone timer. Android has no timer application available."
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
        val moment = EmaTimeParser.parse(value)
        if (moment == null) {
            val message = "I could not understand the alarm time."
            speak(message); done(message); return
        }
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = moment.millis }
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)

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
            "Alarm request sent to " + resolver.activityInfo.applicationInfo.loadLabel(context.packageManager) +
                " for " + EmaTimeParser.describe(moment.millis) + "."
        }.onSuccess { message -> speak(message); done(message) }
            .onFailure {
                val message = "I could not send the alarm request to the phone's alarm system."
                speak(message); done(message)
            }
    }

    private fun createReminder(text: String, timeText: String, done: (String) -> Unit) {
        if (text.isBlank()) {
            val message = "Tell me what to remind you about."
            speak(message); done(message); return
        }
        if (timeText.isBlank()) {
            pendingTask = PendingTask("reminder-time", text)
            val message = "What time should I remind you to $text?"
            speak(message); done(message); return
        }
        val moment = EmaTimeParser.parse(timeText)
        if (moment == null) {
            val message = "I understood the reminder, but I could not understand \"$timeText\" as a time."
            speak(message); done(message); return
        }
        val ok = EmaReminderScheduler.schedule(context, text, moment.millis)
        val result = if (ok) "Reminder set for ${EmaTimeParser.describe(moment.millis)}: $text."
        else "Android could not schedule that reminder."
        speak(result); done(result)
    }

    private fun openMaps(destination: String, done: (String) -> Unit) {
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(destination))
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opening navigation to $destination."
        }.onSuccess { message -> speak(message); done(message) }
            .onFailure {
                val fallback = Uri.parse("geo:0,0?q=" + Uri.encode(destination))
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                val message = "Opening maps for $destination."
                speak(message); done(message)
            }
    }

    private fun openMessageComposer(recipient: String, body: String, done: (String) -> Unit) {
        if (recipient.isBlank()) {
            val message = "Tell me who to text."
            speak(message); done(message); return
        }
        val resolved = runCatching { ContactLookup.best(ContactLookup.find(context, recipient)).firstOrNull() }.getOrNull()
        val address = resolved?.phone?.takeIf { it.isNotBlank() } ?: recipient
        val uri = Uri.parse("smsto:" + Uri.encode(address))
        val intent = Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (body.isNotBlank()) intent.putExtra("sms_body", body)
        runCatching { context.startActivity(intent) }.onSuccess {
            val message = if (body.isBlank()) "Opening messages for ${resolved?.displayName ?: recipient}. What should I say?"
            else "Opening messages for ${resolved?.displayName ?: recipient}."
            speak(message); done(message)
        }.onFailure {
            val message = "I could not open the messaging app on this phone."
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
        if (query.isBlank()) {
            val message = "Tell me what to search for."
            speak(message); done(message); return
        }
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

    private fun globalAction(action: Int, message: String, done: (String) -> Unit) {
        val ok = EmaAccessibilityService.performGlobalAction(action)
        val result = if (ok) message
        else EmaAccessibilityService.availabilityProblem() ?: "EMA needs Accessibility access to perform that action."
        speak(result); done(result)
    }

    /** Reads back the reminders that are still waiting, with human times. */
    private fun listReminders(done: (String) -> Unit) {
        val reminders = EmaReminderScheduler.pending(context)
        val message = when {
            reminders.isEmpty() -> "You have no reminders set."
            reminders.size == 1 ->
                "1 reminder: ${reminders[0].text} at ${EmaTimeParser.describe(reminders[0].atMillis)}."
            else -> {
                val head = reminders.take(6).joinToString("; ") {
                    "${it.text} at ${EmaTimeParser.describeShort(it.atMillis)}"
                }
                val extra = if (reminders.size > 6) ", plus ${reminders.size - 6} more" else ""
                "${reminders.size} reminders: $head$extra."
            }
        }
        speak(message); done(message)
    }

    // ---------------------------------------------------------- calendar

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
        commandHandler.postDelayed({
            EmaGenericUiController.tap("Task") { found, _ ->
                if (!found) {
                    EmaGenericUiController.tap("Tasks") { foundTasks, _ ->
                        if (!foundTasks) {
                            val result = "I opened Calendar, but its Task control was not reachable."
                            speak(result); done(result)
                        } else createTaskThroughCurrentUi(taskText, done)
                    }
                } else createTaskThroughCurrentUi(taskText, done)
            }
        }, 1100L)
    }

    private fun createTaskThroughCurrentUi(text: String, done: (String) -> Unit) {
        commandHandler.postDelayed({
            val opened = EmaAccessibilityService.activate("Add task") ||
                EmaAccessibilityService.activate("Create task") ||
                EmaAccessibilityService.activate("New task")
            if (!opened) {
                EmaGenericUiController.type(text) { typed, _ ->
                    val result = if (typed) "I entered the calendar task, but could not find its Save control."
                    else "I could not access the calendar task editor."
                    speak(result); done(result)
                }
                return@postDelayed
            }
            commandHandler.postDelayed({
                EmaGenericUiController.type(text) { typed, _ ->
                    val saved = EmaAccessibilityService.activate("Save")
                    val result = when {
                        typed && saved -> "Calendar task created."
                        typed -> "I entered the calendar task, but could not confirm Save."
                        else -> "I could not enter the calendar task."
                    }
                    speak(result); done(result)
                }
            }, 600L)
        }, 600L)
    }

    private fun handleCalendarEvent(command: String, done: (String) -> Unit) {
        val title = command
            .replaceFirst(Regex("(?i)^create (?:a )?calendar event\\s*"), "")
            .replaceFirst(Regex("(?i)^add (?:a )?calendar event\\s*"), "")
            .replaceFirst(Regex("(?i)^calendar event\\s*"), "")
            .replaceFirst(Regex("(?i)^create an event\\s*"), "")
            .replaceFirst(Regex("(?i)^add an event\\s*"), "")
            .trim()
        if (title.isBlank()) {
            val result = "Tell me the event title."
            speak(result); done(result); return
        }
        val timeMatch = Regex("(?i)^(.+?)\\s+(?:at|on)\\s+(.+)$").find(title)
        val eventTitle = timeMatch?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { title }
        val timeText = timeMatch?.groupValues?.getOrNull(2)?.trim().orEmpty()
        val trigger = if (timeText.isBlank()) null else EmaTimeParser.parseToFutureMillis(timeText)

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
            val result = if (trigger != null) "I opened Calendar with $eventTitle at ${EmaTimeParser.describe(trigger)}."
            else "I opened Calendar ready to create $eventTitle."
            speak(result); done(result)
        }.onFailure {
            val result = "I could not open the calendar event editor on this phone."
            speak(result); done(result)
        }
    }

    private fun handleDaySchedule(command: String, done: (String) -> Unit) {
        val body = command.substringAfter(":", missingDelimiterValue = "").trim()
        if (body.isBlank()) {
            val message = "Give me today's schedule as times and tasks, for example: schedule my day: 8 AM class; 1 PM meeting; 6 PM gym."
            speak(message); done(message); return
        }

        val entries = body.split(Regex("\\s*;\\s*")).map { it.trim() }.filter { it.isNotBlank() }
        var scheduled = 0
        val failed = mutableListOf<String>()
        for (entry in entries) {
            val match = Regex("(?i)^(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)\\s*(?:-|\\u2013|\\u2014|:|to)?\\s*(.+)$").find(entry)
            if (match == null) {
                failed += entry
                continue
            }
            val trigger = EmaTimeParser.parseToFutureMillis(match.groupValues[1].trim())
            val task = match.groupValues[2].trim()
            if (trigger == null || !EmaReminderScheduler.schedule(context, task, trigger)) failed += entry
            else scheduled++
        }

        val message = when {
            scheduled > 0 && failed.isEmpty() ->
                "I scheduled $scheduled item${if (scheduled == 1) "" else "s"} for today. EMA will remind you at each time."
            scheduled > 0 ->
                "I scheduled $scheduled item${if (scheduled == 1) "" else "s"} for today, but could not schedule: ${failed.joinToString(", ")}."
            else -> "I could not schedule those items. Check EMA's notification and alarm permissions and try again."
        }
        speak(message); done(message)
    }

    // ------------------------------------------------------------- AI layer

    /**
     * Second layer: language reasoning and information only.
     *
     * The AI can propose canonical commands, but it never executes them and
     * never authorises them. Only an explicitly action-oriented user command may
     * cross into execution, and execution still happens on the device.
     */
    private fun askAi(command: String, done: (String) -> Unit) {
        executionStage = "planning"
        val prompt = runCatching {
            EmaAiCommandInterpreter.buildPrompt(context, command)
        }.getOrElse {
            finishAssistantResponse("I could not prepare an answer for that. Please try saying it another way.", done)
            return
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
                        } else if (!hasExplicitDeviceActionIntent(command)) {
                            deliverAiAnswer(
                                parsed.answer.ifBlank { "I could not determine a safe action for that request." },
                                done
                            )
                        } else {
                            executionStage = "executing"
                            // One shared deadline for the whole plan, scaled by its
                            // length so a multi-step plan is not starved.
                            extendDeadline(commandTimeoutMs * parsed.actions.size)
                            executeAiActions(parsed.actions, 0, parsed.answer, done)
                        }
                    }
                    is EmaAiCommandInterpreter.Result.Answer -> deliverAiAnswer(parsed.text, done)
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

    /**
     * AI-generated actions are executable only when the user's original
     * utterance clearly asks EMA to perform a device/app operation. This
     * prevents a normal question from being converted into phone control.
     */
    private fun hasExplicitDeviceActionIntent(command: String): Boolean {
        val normalized = command.trim().lowercase(Locale.getDefault())
            .replace(Regex("^(please|can you|could you|would you|i want you to|i need you to)\\s+"), "")
            .trim()

        if (normalized.isBlank()) return false

        // The literal device commands are recognized from the same vocabulary the
        // AI prompt is generated from, so a canonical command EMA advertises can
        // never be rejected by this gate.
        if (EmaSystemCommandVocabulary.looksLikeDeviceAction(normalized)) return true

        val actionPatterns = listOf(
            Regex("""^(open|launch|start|close|exit|go to|navigate to|enter)\b"""),
            Regex("""^(tap|click|press|select|choose|type|write|enter)\b"""),
            Regex("""^(search|find|look for|google)\b"""),
            Regex("""^(call|phone|dial|send|message|text)\b"""),
            Regex("""^(play|pause|stop|skip|resume)\b"""),
            Regex("""^(set|create|add|schedule|remind|remember)\b"""),
            Regex("""^(turn|enable|disable|increase|decrease|raise|lower|change|set)\b"""),
            Regex("""^(scroll|swipe|back|home|read screen|inspect screen|show notifications)\b"""),
            Regex("""^(take|capture|screenshot|record)\b""")
        )
        if (actionPatterns.any { it.containsMatchIn(normalized) }) return true

        return Regex("""^(help me|i need you to|i want you to)\s+(open|launch|search|find|call|message|send|play|set|create|schedule|navigate|go to|tap|type|scroll|turn|enable|disable)\b""")
            .containsMatchIn(normalized)
    }

    private fun executeAiActions(actions: List<String>, index: Int, finalAnswer: String, done: (String) -> Unit) {
        if (index >= actions.size) {
            val answer = finalAnswer.ifBlank { "Done." }
            executionController.shutdown()
            finishAssistantResponse(answer, done)
            return
        }

        executionController.runStep(
            // executeInternal, not execute: the user's original request already
            // installed the single deadline and terminal guard. Re-entering
            // execute() here nested a further 60s timeout per proposed action and
            // let an abandoned step advance the plan after a failure was reported.
            action = { finish -> executeInternal(actions[index], finish, 1) },
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

    private fun isCloseAssistantCommand(lower: String): Boolean =
        lower == "close" || lower == "close ema" || lower == "dismiss ema" ||
            lower == "close assistant" || lower == "dismiss assistant" ||
            lower == "exit" || lower == "exit ema" || lower == "quit" ||
            lower == "i'm done" || lower == "im done" || lower == "i am done" ||
            lower == "that's all" || lower == "thats all"

    private fun adjustBrightness(deltaFraction: Float, done: (String) -> Unit) {
        if (!Settings.System.canWrite(context)) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            val message = "EMA needs permission to change system brightness. I opened the setting for that permission."
            speak(message); done(message); return
        }
        val current = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        val next = (current + (255f * deltaFraction)).toInt().coerceIn(1, 255)
        runCatching { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next) }
            .onSuccess {
                val percent = (next * 100 / 255f).toInt()
                val message = "Brightness set to about $percent percent."
                speak(message); done(message)
            }
            .onFailure { val message = "I could not change the system brightness."; speak(message); done(message) }
    }

    private fun setBrightnessPercent(value: String, done: (String) -> Unit) {
        val percent = Regex("""(?i)(\d{1,3})(?:\s*%)?""").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (percent == null || percent !in 1..100) {
            val message = "Tell me a brightness percentage from 1 to 100."
            speak(message); done(message); return
        }
        if (!Settings.System.canWrite(context)) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
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
        if (manager == null) {
            val message = "I could not access Do Not Disturb controls."
            speak(message); done(message); return
        }
        if (!manager.isNotificationPolicyAccessGranted) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val message = "EMA needs Do Not Disturb access. I opened the setting so you can grant it."
            speak(message); done(message); return
        }
        runCatching {
            manager.setInterruptionFilter(
                if (enabled) android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            )
        }
            .onSuccess {
                val message = if (enabled) "Do Not Disturb is on." else "Do Not Disturb is off."
                speak(message); done(message)
            }
            .onFailure {
                val message = "Android did not allow EMA to change Do Not Disturb right now."
                speak(message); done(message)
            }
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
                android.util.Log.w("EMA", "TTS rejected utterance: $text")
            }
        }.onFailure {
            android.util.Log.w("EMA", "TTS failed; command result remains valid", it)
        }
    }
}
