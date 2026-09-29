package com.ema.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object EmaAiCommandInterpreter {
    fun buildPrompt(context: Context, userCommand: String): String {
        val conversation = EmaConversationStore.contextText(context, 10)
        val ui = EmaAccessibilityService.inspectCurrentUi(12000)
        val screen = EmaAccessibilityService.currentScreenText(12000)
        return """
You are EMA's natural-language reasoning layer. Understand the user's intent like a mature Android digital assistant.
You may receive a brand-new request, a follow-up to the previous turn, a correction, or a completely unrelated new request. Do not force a new utterance to be a continuation when it clearly starts a new task.

For PHONE ACTIONS, return JSON only:
{"mode":"actions","actions":["canonical command 1","canonical command 2"],"answer":"short confirmation"}
Use only canonical commands that EMA can execute: open <app>, go to <target> in settings, search <query>, search with google <query>, tap <visible label>, type <text>, scroll up, scroll down, back, home, call <name>, send a message to <name> on <app> saying <body>, navigate to <place>, set an alarm for <time>, set a timer for <duration>, remind me <text> at <time>, create a calendar event <title> at <time>, create a calendar task <text>, play <query> on <app>, open <app> and play <query>, increase brightness, decrease brightness, set brightness to <percent>, turn on DND, turn off DND, close EMA, read screen, inspect screen. For recurring actions use: every day at <time>: <canonical command>, or every <weekday> at <time>: <canonical command>.
For arbitrary third-party apps, use generic open/search/tap/type/scroll actions rather than app-specific SDKs.
For multi-step requests, include every required step in order. Do not stop after merely opening an app.
For a request that needs information rather than a phone action, return:
{"mode":"answer","answer":"..."}
For a request that cannot be executed because Android does not expose the required control, return:
{"mode":"answer","answer":"...","limitation":"..."}
Never invent that an action succeeded. EMA will verify actions locally.

Recent conversation:
$conversation

Accessible UI currently exposed by Android:
$ui

Current screen text:
$screen

User request:
$userCommand
""".trimIndent()
    }

    fun parse(raw: String): Result {
        val text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return Result.Answer(text)
        val json = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
            ?: return Result.Answer(text)
        return when (json.optString("mode").lowercase()) {
            "actions" -> {
                val array = json.optJSONArray("actions") ?: JSONArray()
                val actions = buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optString(i).trim()
                        if (item.isNotBlank()) add(item)
                    }
                }
                Result.Actions(actions, json.optString("answer"))
            }
            else -> Result.Answer(json.optString("answer").ifBlank { text })
        }
    }

    sealed class Result {
        data class Actions(val actions: List<String>, val answer: String): Result()
        data class Answer(val text: String): Result()
    }
}
