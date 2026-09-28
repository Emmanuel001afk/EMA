package com.ema.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import java.util.Locale

class EmaCommandRouter(
    private val context: Context,
    private val tts: TextToSpeech
) {
    fun execute(raw: String, done: (String) -> Unit) {
        val command = raw.trim()
        if (command.isBlank()) return
        val lower = command.lowercase(Locale.getDefault())

        when {
            lower.startsWith("open ") -> {
                val request = command.substringAfter("open ").trim()
                val markers = listOf(" and search for ", " and search ", " search for ", " search ")
                val marker = markers.firstOrNull { lower.contains(it) }
                if (marker != null) {
                    val index = lower.indexOf(marker)
                    openApp(
                        request.substring(0, index).trim(),
                        request.substring(index + marker.length).trim(),
                        done
                    )
                } else {
                    openApp(request, null, done)
                }
            }
            lower.startsWith("search ") -> webSearch(command.substringAfter("search ").trim(), done)
            lower.startsWith("go to ") -> openTarget(command.substringAfter("go to ").trim(), done)
            else -> CentralHubClient(context).ask(command) { answer ->
                speak(answer)
                done(answer)
            }
        }
    }

    private fun openApp(appName: String, query: String?, done: (String) -> Unit) {
        val pm = context.packageManager
        val candidates = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val match = candidates.firstOrNull {
            it.loadLabel(pm).toString().equals(appName, true)
        } ?: candidates.firstOrNull {
            it.loadLabel(pm).toString().contains(appName, true)
        }

        if (match == null) {
            val message = "I couldn't find $appName on this phone."
            speak(message); done(message); return
        }

        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
        if (launch == null) {
            val message = "I found $appName, but Android did not provide a launch action."
            speak(message); done(message); return
        }

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)

        if (!query.isNullOrBlank()) {
            EmaAccessibilityService.requestTextSearch(query)
        }

        val label = match.loadLabel(pm).toString()
        val message = if (query.isNullOrBlank()) "Opening $label."
        else "Opening $label and searching for $query."
        speak(message)
        done(message)
    }

    private fun webSearch(query: String, done: (String) -> Unit) {
        if (query.isBlank()) return
        val intent = Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra("query", query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            openTarget("https://www.google.com/search?q=" + Uri.encode(query), done)
            return
        }
        val message = "Searching for $query."
        speak(message); done(message)
    }

    private fun openTarget(target: String, done: (String) -> Unit) {
        val uri = if (target.contains("://")) Uri.parse(target) else Uri.parse("https://$target")
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val message = "Opening $target."
        speak(message); done(message)
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ema")
    }
}
