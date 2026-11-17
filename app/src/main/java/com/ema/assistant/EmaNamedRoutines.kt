package com.ema.assistant

import java.util.Locale

/**
 * Multi-step named routines and smart personal daily
 * briefings — EMA's deterministic on-device answer to Gemini Routines, Siri
 * Shortcuts, Bixby Modes & Routines and Morning Briefings.
 *
 * Pure Kotlin, zero Android framework dependencies, exhaustively unit tested.
 */
object EmaNamedRoutines {

    const val MAX_CUSTOM_ROUTINES = 30
    const val MAX_STEPS_PER_ROUTINE = 8
    private const val SCHEMA = "ema_named_routines=1"

    data class CustomRoutine(
        val name: String,
        val steps: List<String>
    )

    sealed class Action {
        /** Deliver a personalized smart daily briefing (morning/afternoon/evening). */
        object Briefing : Action()
        /** Save a custom multi-step routine. */
        data class SaveCustom(val name: String, val steps: List<String>) : Action()
        /** Run an owner-defined custom routine. */
        data class RunCustom(val routine: CustomRoutine) : Action()
        /** Delete an owner-defined custom routine. */
        data class DeleteCustom(val name: String) : Action()
        /** List the briefing and owner-defined routines. */
        object ListRoutines : Action()
        /** Refusal when a custom routine contains a secret or recursive trigger. */
        data class Refused(val reason: String) : Action()
    }

    data class BriefingInput(
        val hourOfDay: Int,
        val formattedTime: String,
        val formattedDate: String,
        val ownerName: String? = null,
        val batteryPercent: Int? = null,
        val charging: Boolean = false,
        val dndActive: Boolean = false,
        val upcomingReminders: List<String> = emptyList(),
        val openTodoCount: Int = 0,
        val topTodoItems: List<String> = emptyList(),
        val notificationSummary: String? = null,
        val scheduledRoutineCount: Int = 0,
        val parkingSpot: String? = null
    )

    /** Compose a warm, crisp, factual briefing from observed on-device state. */
    fun composeBriefing(input: BriefingInput): String {
        val greeting = when (input.hourOfDay) {
            in 4..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Hello"
        } + (input.ownerName?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "") + "."

        val sections = mutableListOf<String>()
        sections += "$greeting It's ${input.formattedTime} on ${input.formattedDate}."

        // Battery & phone state.
        val deviceBits = mutableListOf<String>()
        input.batteryPercent?.let { pct ->
            val chargeNote = when {
                input.charging -> " and charging"
                pct <= 20 -> " — low, consider plugging in soon"
                else -> ""
            }
            deviceBits += "Battery is at $pct%$chargeNote"
        }
        if (input.dndActive) deviceBits += "Do Not Disturb is on"
        if (deviceBits.isNotEmpty()) sections += deviceBits.joinToString("; ") + "."

        // Reminders.
        if (input.upcomingReminders.isEmpty()) {
            sections += "You have no upcoming reminders."
        } else {
            val count = input.upcomingReminders.size
            val preview = input.upcomingReminders.take(3).joinToString("; ")
            val extra = if (count > 3) " (plus ${count - 3} more)" else ""
            sections += "Upcoming reminder${if (count == 1) "" else "s"} ($count): $preview$extra."
        }

        // Open to-do items.
        if (input.openTodoCount > 0) {
            val preview = input.topTodoItems.take(3).joinToString(", ")
            val more = if (input.openTodoCount > 3) " and ${input.openTodoCount - 3} more" else ""
            sections += "To-do list (${input.openTodoCount} open): $preview$more."
        }

        // Priority notifications.
        input.notificationSummary?.takeIf { it.isNotBlank() }?.let {
            sections += it.trimEnd('.') + "."
        }

        // Active recurring routines & saved parking if relevant.
        if (input.scheduledRoutineCount > 0) {
            sections += "${input.scheduledRoutineCount} scheduled routine${if (input.scheduledRoutineCount == 1) "" else "s"} active."
        }
        input.parkingSpot?.takeIf { it.isNotBlank() }?.let {
            sections += "Saved parking spot: $it."
        }

        return sections.joinToString(" ")
    }

    /** Parse a routine/focus/briefing voice or text command. */
    fun parse(raw: String, customRoutines: Map<String, CustomRoutine> = emptyMap()): Action? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val clean = text.trimEnd('.', '!', '?').trim()
        val lower = clean.lowercase(Locale.ROOT)

        // 1. Custom routine creation:
        // "create a routine called workout: turn on DND, set volume to 80 percent, open Spotify"
        Regex(
            "(?i)^(?:create|make|save|add|set up)\\s+(?:a\\s+)?routine\\s+(?:called|named)\\s+" +
                "([a-z0-9' ]{2,32}?)\\s*(?::|that\\s+does|to)\\s*(.+)$"
        ).find(clean)?.let { m ->
            val name = normalizeName(m.groupValues[1])
            val body = m.groupValues[2].trim()
            if (name.isBlank()) return Action.Refused("Tell me a name for the routine.")
            if (EmaWorkspace.looksLikeSecret(name) || EmaWorkspace.looksLikeSecret(body)) {
                return Action.Refused("I don't save passwords, PINs or sensitive codes inside routines.")
            }
            val steps = splitRoutineSteps(body)
            if (steps.isEmpty()) return Action.Refused("Tell me at least one step for the \"$name\" routine.")
            if (steps.any { normalizeName(it) == name || normalizeName(it) == "run $name" }) {
                return Action.Refused("A routine cannot invoke itself.")
            }
            if (steps.size > MAX_STEPS_PER_ROUTINE) return Action.Refused("A routine can have at most $MAX_STEPS_PER_ROUTINE steps.")
            return Action.SaveCustom(name, steps)
        }

        // 2. Delete / list routines.
        Regex("(?i)^(?:delete|remove|forget)\\s+(?:my\\s+|the\\s+)?routine\\s+(?:called\\s+|named\\s+)?(.+)$")
            .find(clean)?.let { return Action.DeleteCustom(normalizeName(it.groupValues[1])) }
        if (Regex("^(?:list|show|what are) (?:all )?(?:of )?(?:my )?routines$").matches(lower)) {
            return Action.ListRoutines
        }

        // 3. Explicit / direct custom routine execution (owner custom routine overrides built-ins of same name).
        Regex("(?i)^(?:run|start|execute)\\s+(?:my\\s+|the\\s+)?(.+?)\\s+routine$").find(clean)?.let { m ->
            val key = normalizeName(m.groupValues[1])
            customRoutines[key]?.let { return Action.RunCustom(it) }
        }
        Regex("(?i)^(?:run|start)\\s+routine\\s+(.+)$").find(clean)?.let { m ->
            val key = normalizeName(m.groupValues[1])
            customRoutines[key]?.let { return Action.RunCustom(it) }
        }
        customRoutines[normalizeName(lower)]?.let { return Action.RunCustom(it) }

        // 4. Smart Daily Briefing.
        if (lower in BRIEFING_PHRASES) return Action.Briefing

        return null
    }

    fun normalizeName(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .replace(Regex("^(?:my|the|a)\\s+"), "")
            .replace(Regex("\\s+routine$"), "")
            .replace(Regex("[^a-z0-9' ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(32)

    private fun splitRoutineSteps(body: String): List<String> =
        // Only explicit semicolons separate stored steps. Commas and "then"
        // commonly occur inside messages, search queries and dictated notes.
        body.split(';')
            .map { it.trim().replace(Regex("(?i)^and\\s+"), "").trim().trimEnd('.') }
            .filter { it.isNotBlank() }

    private val BRIEFING_PHRASES = setOf(
        "good morning", "good morning ema", "morning briefing", "my morning briefing",
        "daily briefing", "my daily briefing", "my briefing", "brief me", "give me my briefing",
        "give me a briefing", "catch me up", "what's my day look like", "what does my day look like",
        "start my day", "my day", "evening briefing", "afternoon briefing", "status briefing"
    )

    // --------------------------------------------------- persistence

    fun encode(routines: Map<String, CustomRoutine>): String = buildString {
        append(SCHEMA).append('\n')
        for ((_, r) in routines) {
            append(r.name.replace('\t', ' ').replace('\n', ' '))
            for (step in r.steps) {
                append('\t').append(step.replace('\t', ' ').replace('\n', ' '))
            }
            append('\n')
        }
    }

    fun decode(raw: String?): Map<String, CustomRoutine> {
        if (raw.isNullOrBlank()) return emptyMap()
        val lines = raw.lineSequence().toList()
        if (lines.firstOrNull()?.trim() != SCHEMA) return emptyMap()
        val out = linkedMapOf<String, CustomRoutine>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 2) continue
            val name = normalizeName(parts[0])
            val steps = parts.drop(1).map { it.trim() }.filter { it.isNotBlank() }
            if (name.isNotEmpty() && steps.isNotEmpty() && !EmaWorkspace.looksLikeSecret(line) && out.size < MAX_CUSTOM_ROUTINES) {
                out[name] = CustomRoutine(name, steps.take(MAX_STEPS_PER_ROUTINE))
            }
        }
        return out
    }
}
