package com.ema.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * First-stage command router.
 *
 * Deterministic phone/app commands are handled locally. The AI tunnel is only
 * used when EMA needs natural-language reasoning or information.
 */
class EmaCommandRouter(
    private val context: Context,
    private val tts: TextToSpeech
) {
    private val appEngine = EmaAppActionEngine(context)

    fun execute(raw: String, done: (String) -> Unit) {
        val command = raw.trim()
        if (command.isBlank()) return
        val lower = command.lowercase(Locale.getDefault())

        when {
            lower == "back" || lower == "go back" ->
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK, "Going back.", done)

            lower == "home" || lower == "go home" ->
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME, "Going home.", done)

            lower == "play" || lower == "pause" || lower == "play or pause" ->
                globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_MEDIA_PLAY_PAUSE, "Toggling media playback.", done)

            lower == "read screen" || lower == "read this screen" || lower == "what is on my screen" -> {
                val screen = EmaAccessibilityService.currentScreenText()
                val message = if (screen.isBlank()) "EMA cannot read the current screen yet. Enable EMA's Accessibility access first." else screen
                speak(message.take(3000))
                done(message)
            }

            lower.startsWith("tap ") || lower.startsWith("press ") -> {
                val target = command.substringAfter(' ').trim()
                val ok = EmaAccessibilityService.tapText(target)
                val message = if (ok) "Tapped $target." else "I could not find a tappable \"$target\" on the current screen."
                speak(message); done(message)
            }

            lower.startsWith("type ") -> {
                val value = command.substringAfter("type ").trim()
                val ok = EmaAccessibilityService.setFocusedText(value)
                val message = if (ok) "Typed the requested text." else "I could not find an editable field on the current screen."
                speak(message); done(message)
            }

            lower.startsWith("call ") -> callContact(command.substringAfter("call ").trim(), done)

            isAppSearchCommand(lower) -> {
                val parsed = parseAppSearch(command)
                if (parsed == null) {
                    speak("I could not understand which app you want to search.")
                    done("Try: go into YouTube and search for a topic.")
                } else {
                    val (app, query) = parsed
                    speak("Opening $app and searching for $query.")
                    appEngine.openAndSearch(app, query) { _, result ->
                        speak(result); done(result)
                    }
                }
            }

            isMediaPlayCommand(lower) -> {
                val parsed = parseMediaPlay(command)
                if (parsed == null) {
                    speak("Tell me what to play and which app to use.")
                    done("Try: play a song on Spotify.")
                } else {
                    val (query, app) = parsed
                    if (appEngine.playMediaInApp(app, query)) {
                        val result = "Playing $query in $app."
                        speak(result); done(result)
                    } else {
                        appEngine.openAndSearch(app, query) { _, result ->
                            val message = if (result.startsWith("I searched")) "$result I could not invoke playback automatically." else result
                            speak(message); done(message)
                        }
                    }
                }
            }

            lower.startsWith("open ") -> openApp(command.substringAfter("open ").trim(), done)

            lower.startsWith("search ") || lower.startsWith("find ") ->
                webSearch(command.substringAfter(' ').trim(), done)

            lower.startsWith("go to ") || lower.startsWith("navigate to ") ->
                openTarget(command.substringAfter(' ').trim().removePrefix("to ").trim(), done)

            else -> {
                val needsScreen = listOf("this screen", "my screen", "on screen", "this page", "current page", "what am i looking at", "read this")
                    .any(lower::contains)

                val prompt = if (needsScreen) {
                    val screen = EmaAccessibilityService.currentScreenText()
                    if (screen.isBlank()) command else "Current screen context:\n$screen\n\nUser request: $command"
                } else command

                CentralHubClient(context).ask(prompt) { answer ->
                    speak(answer); done(answer)
                }
            }
        }
    }

    private fun isAppSearchCommand(lower: String): Boolean =
        (lower.startsWith("open ") || lower.startsWith("go into ") || lower.startsWith("go to ")) &&
            (lower.contains(" search ") || lower.contains(" search for "))

    private fun parseAppSearch(command: String): Pair<String, String>? {
        val lower = command.lowercase(Locale.getDefault())
        val markers = listOf(" and search for ", " and search ", " search for ", " search ")
        val marker = markers.firstOrNull { lower.contains(it) } ?: return null
        val index = lower.indexOf(marker)
        val appPart = command.substring(0, index)
            .replaceFirst(Regex("(?i)^go into\\s+"), "")
            .replaceFirst(Regex("(?i)^go to\\s+"), "")
            .replaceFirst(Regex("(?i)^open\\s+"), "")
            .trim()
        val query = command.substring(index + marker.length).trim()
        if (appPart.isBlank() || query.isBlank()) return null
        return appPart to query
    }

    private fun isMediaPlayCommand(lower: String): Boolean =
        lower.startsWith("play ") || lower.startsWith("listen to ")

    private fun parseMediaPlay(command: String): Pair<String, String>? {
        val lower = command.lowercase(Locale.getDefault())
        val markers = listOf(" on ", " in ", " using ")
        val marker = markers.firstOrNull { lower.lastIndexOf(it) > 5 } ?: return null
        val index = lower.lastIndexOf(marker)
        val prefix = command.substring(0, index).trim()
        val app = command.substring(index + marker.length).trim()
        val query = prefix
            .replaceFirst(Regex("(?i)^play\\s+"), "")
            .replaceFirst(Regex("(?i)^listen to\\s+"), "")
            .trim()
        if (query.isBlank() || app.isBlank()) return null
        return query to app
    }

    private fun callContact(name: String, done: (String) -> Unit) {
        if (name.isBlank()) {
            speak("Tell me who to call."); done("Tell me who to call."); return
        }

        if (name.matches(Regex("[+0-9()\\-\\s]{7,}"))) {
            placeCall(name.replace(Regex("[^+0-9]"), ""), name, done)
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

        var foundName: String? = null
        var foundNumber: String? = null

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, null, null, null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val contactName = cursor.getString(nameIndex).orEmpty()
                if (contactName.equals(name, true) || contactName.contains(name, true)) {
                    foundName = contactName
                    foundNumber = cursor.getString(numberIndex)
                    break
                }
            }
        }

        if (foundNumber.isNullOrBlank()) {
            val result = "I couldn't find $name in your contacts."
            speak(result); done(result); return
        }

        placeCall(foundNumber!!, foundName ?: name, done)
    }

    private fun placeCall(number: String, displayName: String, done: (String) -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermission(Manifest.permission.CALL_PHONE)
            val result = "EMA needs phone-call permission before it can call $displayName. Grant it, then say call $displayName again."
            speak(result); done(result); return
        }

        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onSuccess {
            val result = "Calling $displayName."
            speak(result); done(result)
        }.onFailure {
            val result = "Android did not allow EMA to place the call."
            speak(result); done(result)
        }
    }

    private fun requestPermission(permission: String) {
        (context as? Activity)?.requestPermissions(arrayOf(permission), 42)
    }

    private fun globalAction(action: Int, message: String, done: (String) -> Unit) {
        val ok = EmaAccessibilityService.performGlobalAction(action)
        val result = if (ok) message else "EMA needs Accessibility access to perform that action."
        speak(result); done(result)
    }

    private fun openApp(appName: String, done: (String) -> Unit) {
        val target = appEngine.openApp(appName)
        val message = if (target == null) "I couldn't find or open $appName on this phone." else "${target.label}"
        val finalMessage = if (target == null) message else "Opening $message."
        speak(finalMessage); done(finalMessage)
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

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ema")
    }
}
