package com.ema.assistant

import android.content.Context

/**
 * Prompt construction and response parsing for EMA's optional AI layer.
 *
 * Two rules shape this file:
 *
 * 1. **The AI never performs anything.** It may only propose canonical command
 *    strings, which are then re-interpreted by EMA's own deterministic grammar
 *    and executed by EMA's own engine. Every phrase listed in the prompt is a
 *    phrasing [EmaCommandGrammar] actually understands, so the AI cannot propose
 *    an action EMA is unable to carry out.
 * 2. **Context is proportional to the request.** Screen dumps are large and the
 *    Central Hub tunnel is a shared, metered resource. Most requests reaching the
 *    AI are knowledge questions, where the visible UI is irrelevant, so screen
 *    context is attached only when the wording actually refers to the screen.
 */
object EmaAiCommandInterpreter {

    /**
     * One canonical command EMA advertises to the AI.
     *
     * @param template the shape shown in the prompt
     * @param example a concrete instance, used by tests to prove EMA can really
     *   execute what it offers
     * @param grammar true when [EmaCommandGrammar] parses it; false when the
     *   router handles it as a literal device command
     */
    data class Canonical(val template: String, val example: String, val grammar: Boolean)

    /**
     * The complete action vocabulary. The prompt is generated from this list, so
     * what EMA offers the AI and what EMA can execute cannot drift apart.
     */
    val CANONICAL_COMMANDS: List<Canonical> = listOf(
        Canonical("open <app>", "open Spotify", true),
        Canonical("go to <target> in settings", "go to battery in settings", true),
        Canonical("search <query>", "search weather in London", true),
        Canonical("search with google <query>", "search with google best pizza near me", true),
        Canonical("tap <visible label>", "tap Settings", true),
        Canonical("type <text>", "type hello there", false),
        Canonical("scroll up", "scroll up", false),
        Canonical("scroll down", "scroll down", false),
        Canonical("back", "back", false),
        Canonical("home", "home", false),
        Canonical("call <name>", "call Ada Lovelace", true),
        Canonical(
            "send a message to <name> on <app> saying <body>",
            "send a message to Ada on WhatsApp saying I am on my way", true
        ),
        Canonical("send an email to <address> saying <body>", "send an email to ada@example.com saying hello", true),
        Canonical("navigate to <place>", "navigate to Central Park", true),
        Canonical("set an alarm for <time>", "set an alarm for 7 am", true),
        Canonical("set a timer for <duration>", "set a timer for 10 minutes", true),
        Canonical("remind me <text> at <time>", "remind me to take my medicine at 9 pm", true),
        Canonical("list my reminders", "list my reminders", false),
        Canonical("cancel my reminder about <text>", "cancel my reminder about medicine", false),
        Canonical("create a calendar event <title> at <time>", "create a calendar event Meeting at 5 pm", false),
        Canonical("create a calendar task <text>", "create a calendar task Buy milk", false),
        Canonical("play <query> on <app>", "play Arcade on Spotify", true),
        Canonical("open <app> and play <query>", "open YouTube and play lofi beats", true),
        Canonical("pause", "pause", true),
        Canonical("resume", "resume", true),
        Canonical("next track", "next track", true),
        Canonical("previous track", "previous track", true),
        Canonical("stop playback", "stop playback", true),
        Canonical("increase brightness", "increase brightness", false),
        Canonical("decrease brightness", "decrease brightness", false),
        Canonical("set brightness to <percent>", "set brightness to 40 percent", false),
        Canonical("set volume to <percent>", "set volume to 50 percent", false),
        Canonical("turn on DND", "turn on DND", false),
        Canonical("turn off DND", "turn off DND", false),
        Canonical("turn on flashlight", "turn on flashlight", false),
        Canonical("turn off flashlight", "turn off flashlight", false),
        Canonical("save <number> as <name>", "save 5551234 as Ada", true),
        Canonical("what time is it", "what time is it", false),
        Canonical("what is today's date", "what is today's date", false),
        Canonical("what is my battery level", "what is my battery level", false),
        Canonical("read notifications", "read notifications", false),
        Canonical("read screen", "read screen", false),
        Canonical("copy screen text", "copy screen text", false),
        Canonical("take a screenshot", "take a screenshot", false),
        Canonical("close EMA", "close EMA", false),
        Canonical("what can you do", "what can you do", false)
    )

    /** The vocabulary line embedded in the prompt, generated from [CANONICAL_COMMANDS]. */
    fun canonicalVocabulary(): String = CANONICAL_COMMANDS.joinToString(", ") { it.template }

    /** Limits chosen so a screen-aware request still fits a modest tunnel budget. */
    private const val CONVERSATION_TURNS = 10
    private const val ASSIST_TEXT_LIMIT = 2_000
    private const val UI_LIMIT = 4_000
    private const val SCREEN_TEXT_LIMIT = 3_000

    /**
     * Whether the request depends on what is currently on screen.
     *
     * "Tap the blue button" needs the UI dump; "what is the capital of Ghana"
     * does not, and sending 10 KB of accessibility text for it wastes the
     * request and slows the answer.
     */
    fun needsScreenContext(command: String): Boolean {
        val lower = command.lowercase()
        // Word-bounded matching is required: "what is the capital of Ghana"
        // contains the letters "it" inside "capital", and a substring test would
        // attach a useless screen dump to a plain knowledge question.
        val deictic = listOf(
            "this", "that", "these", "those", "here", "there", "screen",
            "current", "visible", "showing", "displayed", "above", "below",
            "it", "them", "the page", "this app", "read out"
        )
        if (deictic.any { containsWord(lower, it) }) return true
        // Generic UI verbs only make sense against a concrete screen.
        val screenVerbs = listOf("tap", "click", "press", "type", "scroll", "select", "choose")
        return screenVerbs.any { containsWord(lower, it) }
    }

    /** True when [word] occurs in [text] as a whole word or phrase. */
    private fun containsWord(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val index = text.indexOf(word, from)
            if (index < 0) return false
            val before = if (index == 0) ' ' else text[index - 1]
            val afterIndex = index + word.length
            val after = if (afterIndex >= text.length) ' ' else text[afterIndex]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = index + 1
        }
    }

    fun buildPrompt(context: Context, userCommand: String): String {
        val conversation = EmaConversationStore.contextText(context, CONVERSATION_TURNS)
        val screenAware = needsScreenContext(userCommand)
        val assistContext = if (screenAware) EmaVoiceInteractionSession.currentAssistText().take(ASSIST_TEXT_LIMIT) else ""
        val ui = if (screenAware) EmaAccessibilityService.inspectCurrentUi(UI_LIMIT) else ""
        val screen = if (screenAware) EmaAccessibilityService.currentScreenText(SCREEN_TEXT_LIMIT) else ""

        val contextBlock = if (screenAware) {
            """

Android assistant context (AssistStructure):
$assistContext

Accessible UI currently exposed by Android:
$ui

Current screen text:
$screen"""
        } else {
            """

The user's request does not refer to the visible screen, so no screen dump is attached. Do not ask for one; answer from the request and the conversation."""
        }

        return """
You are EMA's natural-language reasoning and information layer. You are part of EMA, the Android digital assistant; your response is EMA's response to the user.
EMA performs phone and app actions itself, deterministically, before you are consulted. You are reached only when EMA's on-device understanding could not resolve the request, or when the request needs outside knowledge.
You may receive a brand-new request, a follow-up to the previous turn, a correction, or a completely unrelated new request. Do not force a new utterance to be a continuation when it clearly starts a new task.

IMPORTANT RESPONSIBILITY BOUNDARY:
- EMA itself is the Android assistant. The AI layer helps EMA understand language, answer questions, and plan genuinely requested device/app actions.
- Do NOT treat every user message as an action request.
- If the user is asking for knowledge, explanation, advice, conversation, clarification, or a general answer, return mode "answer". Do not create phone actions merely because an answer mentions an app, website, person, setting, or other actionable object.
- Only return mode "actions" when the user clearly intends EMA to perform an action on the device or inside an app. Requests such as "how do I open...", "what does ... mean?", "tell me about...", "can you explain...", or "which app can..." are information requests unless the user explicitly asks EMA to perform the action.
- If the user gives a clear imperative device/app request, choose mode "actions" and produce the complete executable plan. Do not downgrade a clear action request to an informational answer merely because the wording is conversational.
- If a request is genuinely ambiguous about the target or action, ask for clarification rather than inventing details.
- Never invent that an action happened. EMA's local execution layer is the authority for actual device changes and verifies execution.

For PHONE ACTIONS, return JSON only:
{"mode":"actions","actions":["canonical command 1","canonical command 2"],"answer":"short confirmation"}
Use only canonical commands that EMA can execute: ${canonicalVocabulary()}. For recurring actions use: every day at <time>: <canonical command>, or every <weekday> at <time>: <canonical command>.
Never propose a canonical command that is not in that list. If the user's goal needs something EMA cannot do, return mode "answer" with a "limitation" instead of inventing a command.
For arbitrary third-party apps, use generic open/search/tap/type/scroll actions rather than app-specific SDKs.
Preserve the user's roles exactly: APP is where the action happens; TARGET is the person, section, result, song, video, or other object; CONTENT is what is typed/sent/searched. Never swap these roles.
"Go to <section> in/on <app>" means open the app, then activate the requested visible section; it does not mean search the web or look for an app named "<app> <section>".
"Go to <app> <section>" may mean open the installed app and activate that section. Use the longest matching installed app name visible in the provided UI/app context.
"Send/message <person> on <app> that/saying <content>" means select that person in that app, enter the exact content, then send it. Do not use SMS/Messages unless the user asked for that app.
"Play <item> on/in <app>" means search for the item in that app, select the requested matching result when needed, then activate Play. Do not use a global media toggle as a substitute for selecting the requested item.
For multi-step requests, include every required step in order. Do not stop after merely opening an app. Treat natural connectors such as "and", "then", "after that", "and then", and comma-separated imperative clauses as possible action boundaries, while keeping content such as message text intact. "Open X and play Y" is one multi-step device request, not an informational question.
For a request that needs information rather than a phone action, return:
{"mode":"answer","answer":"..."}
This answer is delivered to the user as EMA. Do not describe yourself as a separate AI service or tell the user to talk to "the AI layer."
Keep answers short enough to be spoken aloud: two or three sentences unless the user asked for detail or a list.
For a request that cannot be executed because Android does not expose the required control, return:
{"mode":"answer","answer":"...","limitation":"..."}
Never invent that an action succeeded. EMA will verify actions locally.

Recent conversation:
$conversation
$contextBlock

User request:
$userCommand
""".trimIndent()
    }

    /**
     * Turn a model reply into either an action plan or a spoken answer.
     *
     * Any reply that is not well-formed JSON is delivered verbatim as an answer.
     * That is deliberate: losing the assistant's words because of a stray brace
     * is worse than speaking them.
     */
    fun parse(raw: String): Result {
        val text = raw.trim()
        // Accept structured JSON whether the model returns plain JSON, a Markdown
        // code fence, or JSON embedded in prose.
        val normalized = text
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val json = EmaJson.readObject(normalized) ?: return Result.Answer(text)

        val limitation = EmaJson.string(json, "limitation")
        return when (EmaJson.string(json, "mode").lowercase()) {
            "actions" -> Result.Actions(
                EmaJson.stringList(json, "actions"),
                EmaJson.string(json, "answer"),
                limitation
            )
            else -> Result.Answer(
                EmaJson.string(json, "answer").ifBlank { text },
                limitation
            )
        }
    }

    sealed class Result {
        data class Actions(
            val actions: List<String>,
            val answer: String,
            val limitation: String = ""
        ) : Result()

        data class Answer(val text: String, val limitation: String = "") : Result()
    }
}
