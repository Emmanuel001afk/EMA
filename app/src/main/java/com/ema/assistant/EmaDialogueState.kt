package com.ema.assistant

import java.time.LocalDate
import kotlin.math.max

/**
 * Short-lived, in-memory conversational working state that powers multi-turn
 * anaphora ("double that", "split it 4 ways", "convert that to miles"),
 * ellipsis ("and in Tokyo?", "what about 25 km?", "add eggs too"), grounded
 * follow-ups on the last read/shared/transcribed document ("summarize it",
 * "extract action items", "what does it say about Friday?"), response replay
 * ("repeat that", "copy that", "save that as a note"), deterministic "undo
 * that" state reversal, and a built-in multi-turn stopwatch.
 *
 * Strict privacy & safety guarantees:
 *  - Lives solely in volatile process memory; NEVER written to disk or network.
 *  - Expires automatically after [TTL_MS] (10 minutes) of inactivity.
 *  - NEVER stores message recipients, message bodies, phone call targets,
 *    passwords, OTPs or secrets — consequential actions always require explicit
 *    targets in the current utterance.
 *
 * Pure Kotlin: zero Android dependencies, fully unit tested on a plain JVM.
 */
object EmaDialogueState {

    const val TTL_MS: Long = 10 * 60_000L
    const val UNDO_TTL_MS: Long = 15 * 60_000L
    private const val MAX_DOCUMENT_CHARS = 12_000
    private const val MAX_REPLY_CHARS = 4_000
    private const val MAX_UNDO_STACK = 6
    private const val MAX_LAPS = 20

    data class Quantity(
        val value: Double,
        val unit: String? = null,
        val label: String = "",
        val atMs: Long
    )

    data class Conversion(
        val value: Double,
        val fromUnit: String,
        val toUnit: String,
        val result: Double,
        val atMs: Long
    )

    data class DateRef(
        val date: LocalDate,
        val label: String,
        val atMs: Long
    )

    data class WorldClockRef(
        val place: String,
        val zoneId: String,
        val atMs: Long
    )

    data class ListRef(
        val name: String,
        val atMs: Long
    )

    data class DocumentRef(
        val source: String,
        val text: String,
        val atMs: Long
    )

    data class ReplyRef(
        val text: String,
        val kind: String,
        val copyValue: String? = null,
        val atMs: Long
    )

    /** A reversible action whose prior state EMA observed and can restore on "undo". */
    sealed class UndoEntry(open val summary: String, open val atMs: Long) {
        data class Flashlight(val restoreOn: Boolean, override val summary: String, override val atMs: Long) :
            UndoEntry(summary, atMs)
        data class Dnd(val restoreFilter: Int, override val summary: String, override val atMs: Long) :
            UndoEntry(summary, atMs)
        data class Brightness(
            val restoreValue: Int,
            val restoreMode: Int,
            override val summary: String,
            override val atMs: Long
        ) : UndoEntry(summary, atMs)
        data class Volume(val restoreIndex: Int, override val summary: String, override val atMs: Long) :
            UndoEntry(summary, atMs)
        data class WorkspaceSnapshot(
            val previous: EmaWorkspace,
            override val summary: String,
            override val atMs: Long
        ) : UndoEntry(summary, atMs)
        data class ReminderAdded(val label: String, override val summary: String, override val atMs: Long) :
            UndoEntry(summary, atMs)
        data class FocusSession(val restoreFilter: Int, override val summary: String, override val atMs: Long) :
            UndoEntry(summary, atMs)
    }

    /** Immutable snapshot passed into pure reasoning functions. */
    data class Snapshot(
        val quantity: Quantity? = null,
        val conversion: Conversion? = null,
        val dateRef: DateRef? = null,
        val worldClock: WorldClockRef? = null,
        val activeList: ListRef? = null,
        val document: DocumentRef? = null,
        val lastReply: ReplyRef? = null,
        val canUndo: Boolean = false
    ) {
        companion object {
            val EMPTY = Snapshot()
        }
    }

    private var quantity: Quantity? = null
    private var conversion: Conversion? = null
    private var dateRef: DateRef? = null
    private var worldClock: WorldClockRef? = null
    private var activeList: ListRef? = null
    private var document: DocumentRef? = null
    private var lastReply: ReplyRef? = null
    private val undoStack = ArrayDeque<UndoEntry>()

    // Built-in multi-turn stopwatch (monotonic wall/test clock in ms).
    private var swRunning = false
    private var swStartedAtMs = 0L
    private var swAccumulatedMs = 0L
    private val swLapsMs = mutableListOf<Long>()

    private fun fresh(atMs: Long, nowMs: Long, ttl: Long = TTL_MS): Boolean =
        nowMs >= atMs - 60_000L && nowMs - atMs <= ttl

    @Synchronized
    fun snapshot(nowMs: Long = System.currentTimeMillis()): Snapshot {
        pruneExpired(nowMs)
        return Snapshot(
            quantity = quantity,
            conversion = conversion,
            dateRef = dateRef,
            worldClock = worldClock,
            activeList = activeList,
            document = document,
            lastReply = lastReply,
            canUndo = undoStack.isNotEmpty()
        )
    }

    @Synchronized
    fun recordQuantity(
        value: Double,
        unit: String? = null,
        label: String = "",
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (!value.isFinite()) return
        quantity = Quantity(value, unit?.trim()?.takeIf { it.isNotEmpty() }, label.take(80), nowMs)
    }

    @Synchronized
    fun recordConversion(
        value: Double,
        fromUnit: String,
        toUnit: String,
        result: Double,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (!value.isFinite() || !result.isFinite()) return
        conversion = Conversion(value, fromUnit.trim(), toUnit.trim(), result, nowMs)
        quantity = Quantity(result, toUnit.trim(), "$value $fromUnit in $toUnit", nowMs)
    }

    @Synchronized
    fun recordDate(date: LocalDate, label: String = "", nowMs: Long = System.currentTimeMillis()) {
        dateRef = DateRef(date, label.take(80), nowMs)
    }

    @Synchronized
    fun recordWorldClock(place: String, zoneId: String, nowMs: Long = System.currentTimeMillis()) {
        worldClock = WorldClockRef(place.trim().take(60), zoneId, nowMs)
    }

    @Synchronized
    fun recordActiveList(name: String, nowMs: Long = System.currentTimeMillis()) {
        val cleaned = name.trim().lowercase().take(40)
        if (cleaned.isNotEmpty()) activeList = ListRef(cleaned, nowMs)
    }

    @Synchronized
    fun recordDocument(source: String, text: String, nowMs: Long = System.currentTimeMillis()) {
        val cleaned = text.trim()
        if (cleaned.isEmpty() || EmaWorkspace.looksLikeSecret(cleaned)) {
            document = null
            return
        }
        document = DocumentRef(
            source = source.trim().ifEmpty { "text" }.take(60),
            text = cleaned.take(MAX_DOCUMENT_CHARS),
            atMs = nowMs
        )
    }

    @Synchronized
    fun recordReply(
        text: String,
        kind: String = "general",
        copyValue: String? = null,
        sensitive: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (sensitive || EmaWorkspace.looksLikeSecret(text)) {
            lastReply = null
            return
        }
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        lastReply = ReplyRef(
            text = cleaned.take(MAX_REPLY_CHARS),
            kind = kind,
            copyValue = copyValue?.trim()?.take(MAX_DOCUMENT_CHARS),
            atMs = nowMs
        )
    }

    @Synchronized
    fun pushUndo(entry: UndoEntry) {
        pruneExpired(entry.atMs)
        while (undoStack.size >= MAX_UNDO_STACK) undoStack.removeFirst()
        undoStack.addLast(entry)
    }

    @Synchronized
    fun popUndo(nowMs: Long = System.currentTimeMillis()): UndoEntry? {
        pruneExpired(nowMs)
        return if (undoStack.isEmpty()) null else undoStack.removeLast()
    }

    @Synchronized
    fun clear() {
        quantity = null
        conversion = null
        dateRef = null
        worldClock = null
        activeList = null
        document = null
        lastReply = null
        undoStack.clear()
        swRunning = false
        swStartedAtMs = 0L
        swAccumulatedMs = 0L
        swLapsMs.clear()
    }

    private fun pruneExpired(nowMs: Long) {
        quantity = quantity?.takeIf { fresh(it.atMs, nowMs) }
        conversion = conversion?.takeIf { fresh(it.atMs, nowMs) }
        dateRef = dateRef?.takeIf { fresh(it.atMs, nowMs) }
        worldClock = worldClock?.takeIf { fresh(it.atMs, nowMs) }
        activeList = activeList?.takeIf { fresh(it.atMs, nowMs) }
        document = document?.takeIf { fresh(it.atMs, nowMs) }
        lastReply = lastReply?.takeIf { fresh(it.atMs, nowMs) }
        while (undoStack.isNotEmpty() && !fresh(undoStack.first().atMs, nowMs, UNDO_TTL_MS)) {
            undoStack.removeFirst()
        }
    }

    // --------------------------------------------------------- stopwatch

    @Synchronized
    fun stopwatchStart(nowMs: Long = System.currentTimeMillis()): String {
        if (swRunning) {
            return "The stopwatch is already running at ${formatElapsed(currentStopwatchMs(nowMs))}."
        }
        val resuming = swAccumulatedMs > 0L
        swRunning = true
        swStartedAtMs = nowMs
        return if (resuming) {
            "Stopwatch resumed from ${formatElapsed(swAccumulatedMs)}."
        } else {
            "Stopwatch started. Say \"lap\", \"stopwatch time\" or \"stop stopwatch\" anytime."
        }
    }

    @Synchronized
    fun stopwatchPauseOrStop(reset: Boolean = false, nowMs: Long = System.currentTimeMillis()): String {
        val elapsed = currentStopwatchMs(nowMs)
        if (!swRunning && elapsed == 0L) return "The stopwatch isn't running."
        if (swRunning) {
            swAccumulatedMs = elapsed
            swRunning = false
        }
        val formatted = formatElapsed(elapsed)
        val lapsNote = if (swLapsMs.isEmpty()) "" else " (${swLapsMs.size} lap${if (swLapsMs.size == 1) "" else "s"} recorded)"
        if (reset) {
            swAccumulatedMs = 0L
            swLapsMs.clear()
            return "Stopwatch reset (was at $formatted)."
        }
        return "Stopwatch stopped at $formatted$lapsNote."
    }

    @Synchronized
    fun stopwatchReset(): String {
        val hadTime = swRunning || swAccumulatedMs > 0L
        swRunning = false
        swStartedAtMs = 0L
        swAccumulatedMs = 0L
        swLapsMs.clear()
        return if (hadTime) "Stopwatch reset to 0 seconds." else "The stopwatch is already at 0."
    }

    @Synchronized
    fun stopwatchLap(nowMs: Long = System.currentTimeMillis()): String {
        if (!swRunning) return "The stopwatch isn't running. Say \"start stopwatch\" first."
        val total = currentStopwatchMs(nowMs)
        val prevTotal = swLapsMs.lastOrNull() ?: 0L
        val split = max(0L, total - prevTotal)
        if (swLapsMs.size >= MAX_LAPS) swLapsMs.removeAt(0)
        swLapsMs += total
        return "Lap ${swLapsMs.size}: ${formatElapsed(split)} (total ${formatElapsed(total)})."
    }

    @Synchronized
    fun stopwatchStatus(nowMs: Long = System.currentTimeMillis()): String {
        val total = currentStopwatchMs(nowMs)
        if (!swRunning && total == 0L) return "The stopwatch isn't running."
        val state = if (swRunning) "running" else "paused"
        val laps = if (swLapsMs.isEmpty()) "" else ", ${swLapsMs.size} lap${if (swLapsMs.size == 1) "" else "s"}"
        return "Stopwatch is $state at ${formatElapsed(total)}$laps."
    }

    private fun currentStopwatchMs(nowMs: Long): Long =
        if (swRunning) swAccumulatedMs + max(0L, nowMs - swStartedAtMs) else swAccumulatedMs

    fun formatElapsed(ms: Long): String {
        val clamped = max(0L, ms)
        val hours = clamped / 3_600_000L
        val minutes = (clamped % 3_600_000L) / 60_000L
        val seconds = (clamped % 60_000L) / 1_000L
        val tenths = (clamped % 1_000L) / 100L
        return when {
            hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0L -> "${minutes}m ${seconds}.${tenths}s"
            else -> "${seconds}.${tenths} seconds"
        }
    }
}
