package com.ema.assistant

import java.util.Locale

/**
 * Private on-device personal workspace — EMA's zero-network equivalent of
 * Google Keep / Tasks, Siri Lists & Notes and parking-spot memory.
 *
 * Provides three bounded, user-owned local collections:
 *  1. **Named checklists** (`shopping`, `to-do`, `groceries`, `packing`,
 *     `movies`, `books`, or any custom list name): multi-item add, check off,
 *     uncheck, remove, read, list all, clear completed, clear list, share text.
 *  2. **Quick notes / scratchpad**: create, append, pin/unpin, read latest,
 *     search by keyword, list all, delete last or matching, clear all.
 *  3. **Saved parking / spot memory**: `"remember I parked on Level 2 Zone B"`
 *     and `"where did I park?"` with exact elapsed duration.
 *
 * Safety & privacy:
 *  - Refuses passwords, PINs, OTPs, CVVs and card/bank details.
 *  - Bounded capacity (max [MAX_LISTS] lists × [MAX_ITEMS_PER_LIST] items,
 *    max [MAX_NOTES] notes).
 *  - Stored strictly in app-private storage with versioned atomic encoding.
 *  - Pure Kotlin: zero Android dependencies, exhaustively unit tested.
 */
data class EmaWorkspace(
    val lists: Map<String, List<ChecklistItem>> = emptyMap(),
    val notes: List<Note> = emptyList(),
    val parking: ParkingSpot? = null
) {
    data class ChecklistItem(
        val text: String,
        val done: Boolean = false,
        val addedAtMs: Long = 0L
    )

    data class Note(
        val text: String,
        val pinned: Boolean = false,
        val createdAtMs: Long = 0L
    )

    data class ParkingSpot(
        val location: String,
        val savedAtMs: Long
    )

    sealed class Op {
        data class AddItems(val listName: String, val items: List<String>) : Op()
        data class CheckItem(val listName: String?, val itemQuery: String, val done: Boolean) : Op()
        data class RemoveItem(val listName: String?, val itemQuery: String) : Op()
        data class ShowList(val listName: String) : Op()
        object ShowAllLists : Op()
        data class ClearCompleted(val listName: String) : Op()
        data class ClearList(val listName: String) : Op()
        data class AddNote(val text: String) : Op()
        data class AppendLastNote(val extra: String) : Op()
        data class PinLastNote(val pinned: Boolean) : Op()
        object ShowNotes : Op()
        object ShowLastNote : Op()
        data class FindNotes(val query: String) : Op()
        object DeleteLastNote : Op()
        data class DeleteNoteMatching(val query: String) : Op()
        object ClearNotes : Op()
        data class SaveParking(val location: String) : Op()
        object RecallParking : Op()
        object ClearParking : Op()
    }

    data class Outcome(
        val workspace: EmaWorkspace,
        val reply: String,
        val changed: Boolean,
        val activeListName: String? = null,
        val copyableText: String? = null,
        val undoSummary: String? = null
    )

    fun apply(op: Op, nowMs: Long = System.currentTimeMillis()): Outcome = when (op) {
        is Op.AddItems -> addItems(op.listName, op.items, nowMs)
        is Op.CheckItem -> setChecked(op.listName, op.itemQuery, op.done)
        is Op.RemoveItem -> removeItem(op.listName, op.itemQuery)
        is Op.ShowList -> showList(op.listName)
        Op.ShowAllLists -> showAllLists()
        is Op.ClearCompleted -> clearCompleted(op.listName)
        is Op.ClearList -> clearList(op.listName)
        is Op.AddNote -> addNote(op.text, nowMs)
        is Op.AppendLastNote -> appendNote(op.extra)
        is Op.PinLastNote -> pinLastNote(op.pinned)
        Op.ShowNotes -> showNotes()
        Op.ShowLastNote -> showLastNote()
        is Op.FindNotes -> findNotes(op.query)
        Op.DeleteLastNote -> deleteLastNote()
        is Op.DeleteNoteMatching -> deleteNoteMatching(op.query)
        Op.ClearNotes -> clearNotes()
        is Op.SaveParking -> saveParking(op.location, nowMs)
        Op.RecallParking -> recallParking(nowMs)
        Op.ClearParking -> clearParking()
    }

    /** Pending (unchecked) items on the owner's to-do / tasks lists, for daily briefings. */
    fun openTodoItems(maxItems: Int = 5): Pair<Int, List<String>> {
        val todoKeys = listOf("to-do", "todo", "tasks", "task")
        val all = todoKeys.flatMap { lists[it].orEmpty() }.filterNot { it.done }
        return all.size to all.take(maxItems).map { it.text }
    }

    /** Plain-text export of a named list for clipboard copy or Share sheet. */
    fun exportList(listName: String): String? {
        val key = canonicalListName(listName)
        val items = lists[key]?.takeIf { it.isNotEmpty() } ?: return null
        val header = "${displayListName(key)} (${items.count { !it.done }} open):"
        val body = items.joinToString("\n") { (if (it.done) "[x] " else "[ ] ") + it.text }
        return "$header\n$body"
    }

    // --------------------------------------------------- list mutations

    private fun addItems(rawList: String, rawItems: List<String>, nowMs: Long): Outcome {
        val key = canonicalListName(rawList)
        val clean = rawItems.map { it.trim().trim('.', ',', ';') }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) {
            return Outcome(this, "Tell me what to add to your ${displayListName(key)}.", false, key)
        }
        if (clean.any(::looksLikeSecret)) {
            return Outcome(this, SECRET_REFUSAL, false)
        }
        if (key !in lists && lists.size >= MAX_LISTS) {
            return Outcome(this, "You already have $MAX_LISTS lists. Clear one before creating a new list.", false)
        }
        val existing = lists[key].orEmpty()
        val existingLower = existing.filterNot { it.done }.mapTo(HashSet()) { it.text.lowercase(Locale.ROOT) }
        val updated = existing.toMutableList()
        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        for (item in clean) {
            val low = item.lowercase(Locale.ROOT)
            if (low in existingLower) {
                skipped += item
                continue
            }
            if (updated.size >= MAX_ITEMS_PER_LIST) break
            // If an identical checked item exists, reopen it instead of duplicating.
            val doneIdx = updated.indexOfFirst { it.done && it.text.equals(item, ignoreCase = true) }
            if (doneIdx >= 0) {
                updated[doneIdx] = updated[doneIdx].copy(done = false, addedAtMs = nowMs)
            } else {
                updated += ChecklistItem(item.take(MAX_ITEM_CHARS), done = false, addedAtMs = nowMs)
            }
            existingLower += low
            added += item
        }

        if (added.isEmpty() && skipped.isEmpty()) {
            return Outcome(this, "Your ${displayListName(key)} is full ($MAX_ITEMS_PER_LIST items). Remove an item first.", false, key)
        }
        if (added.isEmpty()) {
            val what = skipped.joinToString(", ")
            return Outcome(
                this,
                "${what.replaceFirstChar { it.titlecase(Locale.ROOT) }} ${if (skipped.size == 1) "is" else "are"} already on your ${displayListName(key)}.",
                changed = false,
                activeListName = key
            )
        }

        val next = copy(lists = lists + (key to updated))
        val openCount = updated.count { !it.done }
        val joined = humanJoin(added)
        return Outcome(
            workspace = next,
            reply = "Added $joined to your ${displayListName(key)} ($openCount item${if (openCount == 1) "" else "s"} open).",
            changed = true,
            activeListName = key,
            copyableText = next.exportList(key),
            undoSummary = "removed $joined from your ${displayListName(key)}"
        )
    }

    private fun setChecked(rawList: String?, query: String, done: Boolean): Outcome {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return Outcome(this, "Tell me which item to ${if (done) "check off" else "uncheck"}.", false)
        val searchKeys = if (rawList != null) listOf(canonicalListName(rawList)) else lists.keys.toList()
        val allMatches = searchKeys.flatMap { key ->
            lists[key].orEmpty().filter { it.text.lowercase(Locale.ROOT).contains(q) }
        }
        val exactMatches = allMatches.count { it.text.equals(q, ignoreCase = true) }
        if (exactMatches > 1 || (exactMatches == 0 && allMatches.size > 1)) {
            return Outcome(this, "More than one item matches. Name the exact item and list so I change the right one.", false)
        }
        val matchingKeys = if (exactMatches == 1) searchKeys.filter { key ->
            lists[key].orEmpty().any { it.text.equals(q, ignoreCase = true) }
        } else searchKeys
        for (key in matchingKeys) {
            val items = lists[key] ?: continue
            val idx = findItemIndex(items, q, preferDone = !done)
            if (idx >= 0) {
                val item = items[idx]
                val updated = items.toMutableList().also { it[idx] = item.copy(done = done) }
                val remaining = updated.count { !it.done }
                val next = copy(lists = lists + (key to updated))
                val verb = if (done) "Checked off" else "Unchecked"
                val tail = if (remaining == 0) "all done!" else "$remaining left"
                return Outcome(
                    workspace = next,
                    reply = "$verb \"${item.text}\" on your ${displayListName(key)} ($tail).",
                    changed = true,
                    activeListName = key,
                    undoSummary = "${if (done) "unchecked" else "checked"} \"${item.text}\" on your ${displayListName(key)}"
                )
            }
        }
        val where = if (rawList != null) "your ${displayListName(canonicalListName(rawList))}" else "your lists"
        return Outcome(this, "I couldn't find \"$query\" on $where.", false)
    }

    private fun removeItem(rawList: String?, query: String): Outcome {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return Outcome(this, "Tell me which item to remove.", false)
        val searchKeys = if (rawList != null) listOf(canonicalListName(rawList)) else lists.keys.toList()
        val allMatches = searchKeys.flatMap { key ->
            lists[key].orEmpty().filter { it.text.lowercase(Locale.ROOT).contains(q) }
        }
        val exactMatches = allMatches.count { it.text.equals(q, ignoreCase = true) }
        if (exactMatches > 1 || (exactMatches == 0 && allMatches.size > 1)) {
            return Outcome(this, "More than one item matches. Name the exact item and list so I change the right one.", false)
        }
        val matchingKeys = if (exactMatches == 1) searchKeys.filter { key ->
            lists[key].orEmpty().any { it.text.equals(q, ignoreCase = true) }
        } else searchKeys
        for (key in matchingKeys) {
            val items = lists[key] ?: continue
            val idx = findItemIndex(items, q, preferDone = null)
            if (idx >= 0) {
                val removed = items[idx]
                val updated = items.toMutableList().also { it.removeAt(idx) }
                val nextLists = if (updated.isEmpty()) lists - key else lists + (key to updated)
                val next = copy(lists = nextLists)
                return Outcome(
                    workspace = next,
                    reply = "Removed \"${removed.text}\" from your ${displayListName(key)} (${updated.count { !it.done }} remaining).",
                    changed = true,
                    activeListName = key,
                    undoSummary = "restored \"${removed.text}\" to your ${displayListName(key)}"
                )
            }
        }
        val where = if (rawList != null) "your ${displayListName(canonicalListName(rawList))}" else "your lists"
        return Outcome(this, "I couldn't find \"$query\" on $where.", false)
    }

    private fun showList(rawList: String): Outcome {
        val key = canonicalListName(rawList)
        val items = lists[key].orEmpty()
        if (items.isEmpty()) {
            return Outcome(
                this,
                "Your ${displayListName(key)} is empty. Say \"add <item> to my ${displayListName(key)}\" to start it.",
                changed = false,
                activeListName = key
            )
        }
        val open = items.filterNot { it.done }
        val done = items.filter { it.done }
        val sb = StringBuilder()
        if (open.isEmpty()) {
            sb.append("Everything on your ${displayListName(key)} is checked off (${done.size} completed).")
        } else {
            sb.append("Your ${displayListName(key)} (${open.size} item${if (open.size == 1) "" else "s"}): ")
            sb.append(open.take(15).joinToString(", ") { it.text }).append('.')
            if (open.size > 15) sb.append(" Plus ${open.size - 15} more.")
            if (done.isNotEmpty()) sb.append(" (${done.size} completed.)")
        }
        return Outcome(this, sb.toString(), false, activeListName = key, copyableText = exportList(key))
    }

    private fun showAllLists(): Outcome {
        val nonEmpty = lists.filterValues { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) {
            return Outcome(
                this,
                "You don't have any lists yet. Say \"add milk to my shopping list\" or \"add finish report to my to-do list\".",
                changed = false
            )
        }
        val summary = nonEmpty.entries.joinToString("; ") { (k, items) ->
            val open = items.count { !it.done }
            val done = items.size - open
            "${displayListName(k)} ($open open${if (done > 0) ", $done done" else ""})"
        }
        return Outcome(this, "Your lists: $summary.", false)
    }

    private fun clearCompleted(rawList: String): Outcome {
        val key = canonicalListName(rawList)
        val items = lists[key].orEmpty()
        val kept = items.filterNot { it.done }
        val removed = items.size - kept.size
        if (removed == 0) {
            return Outcome(this, "No completed items to clear on your ${displayListName(key)}.", false, key)
        }
        val nextLists = if (kept.isEmpty()) lists - key else lists + (key to kept)
        return Outcome(
            workspace = copy(lists = nextLists),
            reply = "Cleared $removed completed item${if (removed == 1) "" else "s"} from your ${displayListName(key)}.",
            changed = true,
            activeListName = key,
            undoSummary = "restored $removed completed item${if (removed == 1) "" else "s"} to your ${displayListName(key)}"
        )
    }

    private fun clearList(rawList: String): Outcome {
        val key = canonicalListName(rawList)
        val count = lists[key]?.size ?: 0
        if (count == 0) {
            return Outcome(this, "Your ${displayListName(key)} is already empty.", false, key)
        }
        return Outcome(
            workspace = copy(lists = lists - key),
            reply = "Cleared your ${displayListName(key)} ($count item${if (count == 1) "" else "s"} removed).",
            changed = true,
            activeListName = key,
            undoSummary = "restored your ${displayListName(key)} ($count items)"
        )
    }

    // --------------------------------------------------- note mutations

    private fun addNote(rawText: String, nowMs: Long): Outcome {
        val cleaned = rawText.trim()
        if (cleaned.isEmpty()) return Outcome(this, "Tell me what you'd like the note to say.", false)
        if (looksLikeSecret(cleaned)) return Outcome(this, SECRET_REFUSAL, false)
        val trimmed = cleaned.take(MAX_NOTE_CHARS)
        if (notes.size >= MAX_NOTES) return Outcome(this, "Your notes are full ($MAX_NOTES). Delete a note before adding another.", false)
        val updated = notes + Note(trimmed, pinned = false, createdAtMs = nowMs)
        return Outcome(
            workspace = copy(notes = updated),
            reply = "Saved note (${updated.size} total): \"$trimmed\"",
            changed = true,
            copyableText = trimmed,
            undoSummary = "deleted the last saved note"
        )
    }

    private fun appendNote(extra: String): Outcome {
        val cleaned = extra.trim()
        if (cleaned.isEmpty()) return Outcome(this, "Tell me what to add to your last note.", false)
        if (looksLikeSecret(cleaned)) return Outcome(this, SECRET_REFUSAL, false)
        if (notes.isEmpty()) return addNote(cleaned, System.currentTimeMillis())
        val last = notes.last()
        val sep = if (last.text.lastOrNull() in listOf('.', '!', '?', '\n')) " " else ". "
        val merged = (last.text + sep + cleaned).take(MAX_NOTE_CHARS)
        val updated = notes.dropLast(1) + last.copy(text = merged)
        return Outcome(
            workspace = copy(notes = updated),
            reply = "Updated your last note: \"$merged\"",
            changed = true,
            copyableText = merged,
            undoSummary = "reverted your last note edit"
        )
    }

    private fun pinLastNote(pinned: Boolean): Outcome {
        if (notes.isEmpty()) return Outcome(this, "You don't have any notes yet.", false)
        val last = notes.last()
        val updated = notes.dropLast(1) + last.copy(pinned = pinned)
        val verb = if (pinned) "Pinned" else "Unpinned"
        return Outcome(
            workspace = copy(notes = updated),
            reply = "$verb note: \"${last.text.take(80)}\"",
            changed = true,
            undoSummary = "${if (pinned) "unpinned" else "pinned"} your last note"
        )
    }

    private fun showNotes(): Outcome {
        if (notes.isEmpty()) {
            return Outcome(this, "You don't have any saved notes. Say \"take a note: ...\" to save one.", false)
        }
        val ordered = notes.filter { it.pinned } + notes.filterNot { it.pinned }.asReversed()
        val shown = ordered.take(6).mapIndexed { idx, n ->
            val pin = if (n.pinned) "[Pinned] " else ""
            "${idx + 1}. $pin${n.text.take(140)}"
        }
        val more = if (notes.size > 6) "\n(${notes.size - 6} older notes — say \"search notes for <word>\".)" else ""
        val copyAll = ordered.joinToString("\n\n") { (if (it.pinned) "[Pinned] " else "") + it.text }
        return Outcome(
            this,
            "You have ${notes.size} note${if (notes.size == 1) "" else "s"}:\n${shown.joinToString("\n")}$more",
            changed = false,
            copyableText = copyAll
        )
    }

    private fun showLastNote(): Outcome {
        val last = notes.lastOrNull()
            ?: return Outcome(this, "You don't have any saved notes yet.", false)
        return Outcome(this, "Last note: ${last.text}", false, copyableText = last.text)
    }

    private fun findNotes(query: String): Outcome {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return showNotes()
        val terms = q.split(Regex("\\s+")).filter { it.length >= 2 }
        val matches = notes.asReversed().filter { n ->
            val low = n.text.lowercase(Locale.ROOT)
            low.contains(q) || (terms.isNotEmpty() && terms.all { low.contains(it) })
        }
        if (matches.isEmpty()) {
            return Outcome(this, "No saved notes matched \"$query\".", false)
        }
        val formatted = matches.take(5).mapIndexed { i, n -> "${i + 1}. ${n.text.take(160)}" }.joinToString("\n")
        return Outcome(
            this,
            "Found ${matches.size} note${if (matches.size == 1) "" else "s"} matching \"$query\":\n$formatted",
            changed = false,
            copyableText = matches.first().text
        )
    }

    private fun deleteLastNote(): Outcome {
        val last = notes.lastOrNull() ?: return Outcome(this, "You have no notes to delete.", false)
        return Outcome(
            workspace = copy(notes = notes.dropLast(1)),
            reply = "Deleted your last note (\"${last.text.take(60)}\").",
            changed = true,
            undoSummary = "restored your deleted note"
        )
    }

    private fun deleteNoteMatching(query: String): Outcome {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return Outcome(this, "Tell me which note to delete.", false)
        if (notes.count { it.text.lowercase(Locale.ROOT).contains(q) } > 1) {
            return Outcome(this, "More than one note matches. Please use a more specific phrase.", false)
        }
        val idx = notes.indexOfLast { it.text.lowercase(Locale.ROOT).contains(q) }
        if (idx < 0) return Outcome(this, "I don't have a note matching \"$query\".", false)
        val removed = notes[idx]
        val updated = notes.toMutableList().also { it.removeAt(idx) }
        return Outcome(
            workspace = copy(notes = updated),
            reply = "Deleted note: \"${removed.text.take(70)}\".",
            changed = true,
            undoSummary = "restored deleted note"
        )
    }

    private fun clearNotes(): Outcome {
        if (notes.isEmpty()) return Outcome(this, "You have no saved notes to clear.", false)
        val n = notes.size
        return Outcome(
            workspace = copy(notes = emptyList()),
            reply = "Cleared all $n note${if (n == 1) "" else "s"}.",
            changed = true,
            undoSummary = "restored $n note${if (n == 1) "" else "s"}"
        )
    }

    // --------------------------------------------------- parking spot

    private fun saveParking(location: String, nowMs: Long): Outcome {
        val clean = location.trim().trimEnd('.')
        if (clean.isEmpty()) return Outcome(this, "Tell me where you parked, for example \"remember I parked on Level 2 near Gate B\".", false)
        if (looksLikeSecret(clean)) return Outcome(this, SECRET_REFUSAL, false)
        return Outcome(
            workspace = copy(parking = ParkingSpot(clean.take(180), nowMs)),
            reply = "Saved your parking spot: $clean.",
            changed = true,
            copyableText = clean,
            undoSummary = "cleared your saved parking spot"
        )
    }

    private fun recallParking(nowMs: Long): Outcome {
        val spot = parking ?: return Outcome(
            this,
            "You haven't saved a parking spot. Say \"remember I parked at <spot>\" when you park.",
            changed = false
        )
        val ageMin = ((nowMs - spot.savedAtMs).coerceAtLeast(0L)) / 60_000L
        val whenText = when {
            ageMin < 1 -> "just now"
            ageMin < 60 -> "$ageMin minute${if (ageMin == 1L) "" else "s"} ago"
            ageMin < 24 * 60 -> {
                val h = ageMin / 60; val m = ageMin % 60
                if (m == 0L) "${h}h ago" else "${h}h ${m}m ago"
            }
            else -> "${ageMin / (24 * 60)} day${if (ageMin / (24 * 60) == 1L) "" else "s"} ago"
        }
        return Outcome(this, "You parked at ${spot.location} (saved $whenText).", false, copyableText = spot.location)
    }

    private fun clearParking(): Outcome {
        if (parking == null) return Outcome(this, "No parking spot is currently saved.", false)
        return Outcome(
            workspace = copy(parking = null),
            reply = "Cleared your saved parking spot.",
            changed = true,
            undoSummary = "restored your parking spot"
        )
    }

    companion object {
        const val MAX_LISTS = 25
        const val MAX_ITEMS_PER_LIST = 80
        const val MAX_ITEM_CHARS = 140
        const val MAX_NOTES = 100
        const val MAX_NOTE_CHARS = 2_000
        private const val SCHEMA = "ema_workspace=1"

        private val SECRET_REGEX = Regex(
            "(?i)\\b(password|passcode|passphrase|otp|verification code|one[- ]time code|pin\\b|cvv|cvc|secret key|api key|private key|" +
                "seed phrase|recovery phrase|social security|bvn\\b|nin\\b|card number|account number|routing number)\\b|" +
                "\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b"
        )
        private const val SECRET_REFUSAL =
            "I don't save passwords, PINs, card numbers or security keys in notes or lists. Please keep those in a password manager."

        private val COMPOUND_ITEMS = setOf(
            "peanut butter and jelly", "salt and pepper", "bread and butter",
            "mac and cheese", "macaroni and cheese", "fish and chips",
            "rice and beans", "oil and vinegar", "chips and salsa", "milk and cereal"
        )

        fun looksLikeSecret(text: String): Boolean = SECRET_REGEX.containsMatchIn(text) ||
            EmaScreenIntelligence.extractEntities(text).any { it.kind == EmaScreenIntelligence.EntityKind.OTP_CODE }

        fun canonicalListName(raw: String): String {
            val clean = raw.lowercase(Locale.ROOT)
                .replace(Regex("^(?:my|our|the|a|an)\\s+"), "")
                .replace(Regex("\\s+lists?$"), "")
                .replace(Regex("[^a-z0-9\\- ]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            return when (clean) {
                "", "items", "list" -> "to-do"
                "todo", "to do", "to-do", "task", "tasks" -> "to-do"
                "grocery", "groceries", "supermarket", "market" -> "groceries"
                "shop", "shopping", "buy", "buying" -> "shopping"
                "pack", "packing", "travel", "trip" -> "packing"
                else -> clean.take(32)
            }
        }

        fun displayListName(canonical: String): String =
            if (canonical.endsWith("list")) canonical else "$canonical list"

        /** Split spoken multi-item additions ("milk, bread, and eggs") cleanly. */
        fun splitItems(raw: String): List<String> {
            val trimmed = raw.trim().trimEnd('.', '!')
            if (trimmed.isEmpty()) return emptyList()
            val low = trimmed.lowercase(Locale.ROOT)
            if (low in COMPOUND_ITEMS) return listOf(trimmed)
            if (trimmed.contains(',')) {
                return trimmed.split(',')
                    .map { it.trim().replace(Regex("(?i)^and\\s+"), "").trim() }
                    .filter { it.isNotEmpty() }
            }
            val andParts = trimmed.split(Regex("(?i)\\s+and\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            // Split on single "and" when each side is a short item (<= 3 words).
            return if (andParts.size in 2..6 && andParts.all { it.split(Regex("\\s+")).size <= 3 }) {
                andParts
            } else {
                listOf(trimmed)
            }
        }

        /**
         * Parse a natural-language list, note or parking command, or null when
         * the request belongs to another layer.
         */
        fun parse(raw: String, activeListName: String? = null): Op? {
            val s = raw.trim()
            if (s.isBlank()) return null
            val clean = s.trimEnd('.', '!', '?').trim()
            val lower = clean.lowercase(Locale.ROOT)

            // 1. Parking memory.
            Regex("(?i)^(?:remember (?:that )?(?:i |we )?parked|save (?:my )?parking(?: spot| location)?|i parked)(?:\\s+(?:my car\\s+)?(?:at|on|in|near|by|opposite|beside)|\\s*:)\\s*(.+)$")
                .find(clean)?.let { return Op.SaveParking(it.groupValues[1].trim()) }
            if (Regex("^(?:where did i park(?: my car)?|where is my car(?: parked)?|what(?:'s| is) my parking spot|find my parked car)$").matches(lower)) {
                return Op.RecallParking
            }
            if (Regex("^(?:clear|forget|delete|remove) (?:my )?parking(?: spot| location)?$").matches(lower)) {
                return Op.ClearParking
            }

            // 2. Notes.
            Regex("(?i)^(?:take a note|make a note|save a note|add a note|write a note|new note|note to self|jot down)(?:\\s*(?::|that|saying))\\s*(.+)$")
                .find(s)?.let { return Op.AddNote(it.groupValues[1].trim()) }
            Regex("(?i)^(?:take a note|note to self|jot down)\\s+(.+)$")
                .find(s)?.let { return Op.AddNote(it.groupValues[1].trim()) }
            Regex("(?i)^(?:append|add)\\s+(.+?)\\s+to (?:my )?(?:last|latest) note$").find(clean)?.let {
                return Op.AppendLastNote(it.groupValues[1].trim())
            }
            if (Regex("^(?:pin (?:my )?(?:last|latest) note)$").matches(lower)) return Op.PinLastNote(true)
            if (Regex("^(?:unpin (?:my )?(?:last|latest) note)$").matches(lower)) return Op.PinLastNote(false)
            if (Regex("^(?:read|show|list|what are) (?:all )?(?:my )?notes$").matches(lower)) return Op.ShowNotes
            if (Regex("^(?:read|show|what(?:'s| is)) (?:my )?(?:last|latest|most recent) note$").matches(lower)) return Op.ShowLastNote
            Regex("(?i)^(?:find|search|look up|show) (?:my )?notes? (?:about|for|matching|on|with)\\s+(.+)$").find(clean)?.let {
                return Op.FindNotes(it.groupValues[1].trim())
            }
            if (Regex("^(?:delete|remove) (?:my )?(?:last|latest) note$").matches(lower)) return Op.DeleteLastNote
            Regex("(?i)^(?:delete|remove) (?:my |the )?note (?:about|for|matching|on)\\s+(.+)$").find(clean)?.let {
                return Op.DeleteNoteMatching(it.groupValues[1].trim())
            }
            if (Regex("^(?:clear|delete|erase) all (?:of )?(?:my )?notes$").matches(lower)) return Op.ClearNotes

            // 3. Checklists.
            if (Regex("^(?:show|list|read|what are) (?:all )?(?:of )?my lists$").matches(lower)) {
                return Op.ShowAllLists
            }
            Regex("(?i)^(?:add|put)\\s+(.+?)\\s+(?:to|on|onto|into)\\s+(?:my|our|the)\\s+([a-z0-9\\- ]{2,28}?)\\s+list$").find(clean)?.let { m ->
                return Op.AddItems(m.groupValues[2], splitItems(m.groupValues[1]))
            }
            Regex("(?i)^(?:add|put)\\s+(.+?)\\s+(?:to|on)\\s+(?:my\\s+)?(groceries|to-do|todo|tasks)$").find(clean)?.let { m ->
                return Op.AddItems(m.groupValues[2], splitItems(m.groupValues[1]))
            }
            // Ellipsis: "add bread and eggs too" / "also add butter" right after touching a list.
            if (activeListName != null) {
                Regex("(?i)^(?:also\\s+)?add\\s+(.+?)\\s+(?:too|as well|to it|to that list)$").find(clean)?.let { m ->
                    return Op.AddItems(activeListName, splitItems(m.groupValues[1]))
                }
            }
            Regex("(?i)^(?:check off|tick off|cross off|mark)\\s+(.+?)(?:\\s+as (?:done|complete|completed))?(?:\\s+(?:from|on|in)\\s+(?:my|our|the)\\s+([a-z0-9\\- ]{2,28}?)\\s+list)?$").find(clean)?.let { m ->
                val list = m.groupValues[2].takeIf { it.isNotBlank() } ?: activeListName
                return Op.CheckItem(list, m.groupValues[1].trim(), done = true)
            }
            Regex("(?i)^uncheck\\s+(.+?)(?:\\s+(?:from|on|in)\\s+(?:my|our|the)\\s+([a-z0-9\\- ]{2,28}?)\\s+list)?$").find(clean)?.let { m ->
                val list = m.groupValues[2].takeIf { it.isNotBlank() } ?: activeListName
                return Op.CheckItem(list, m.groupValues[1].trim(), done = false)
            }
            Regex("(?i)^(?:remove|delete|take off)\\s+(.+?)\\s+(?:from|off)\\s+(?:my|our|the)\\s+([a-z0-9\\- ]{2,28}?)\\s+list$").find(clean)?.let { m ->
                return Op.RemoveItem(m.groupValues[2], m.groupValues[1].trim())
            }
            Regex("(?i)^(?:clear|remove|delete) (?:completed|checked|done) items? (?:from|on|in) (?:my|our|the) ([a-z0-9\\- ]{2,28}?) list$").find(clean)?.let { m ->
                return Op.ClearCompleted(m.groupValues[1])
            }
            Regex("(?i)^(?:clear|empty|delete) (?:my|our|the) ([a-z0-9\\- ]{2,28}?) list$").find(clean)?.let { m ->
                val name = m.groupValues[1].trim()
                if (name.lowercase(Locale.ROOT) != "reminder" && name.lowercase(Locale.ROOT) != "reminders") {
                    return Op.ClearList(name)
                }
            }
            Regex("(?i)^(?:what(?:'s| is) (?:on|in)|read|show|check|open) (?:my|our|the) ([a-z0-9\\- ]{2,28}?) list$").find(clean)?.let { m ->
                val name = m.groupValues[1].trim()
                if (name.lowercase(Locale.ROOT) !in setOf("reminder", "reminders", "contact", "contacts", "notification", "notifications")) {
                    return Op.ShowList(name)
                }
            }
            if (Regex("^(?:show|read|what are) my (?:groceries|to-dos|todos|tasks)$").matches(lower)) {
                val target = if (lower.contains("grocer")) "groceries" else "to-do"
                return Op.ShowList(target)
            }
            return null
        }

        // --------------------------------------------------- persistence

        private fun esc(s: String): String =
            s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")

        private fun unesc(s: String): String = buildString(s.length) {
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> append('\t')
                        'n' -> append('\n')
                        '\\' -> append('\\')
                        else -> append(s[i + 1])
                    }
                    i += 2
                } else {
                    append(s[i++])
                }
            }
        }

        fun encode(w: EmaWorkspace): String = buildString {
            append(SCHEMA).append('\n')
            for ((listName, items) in w.lists) {
                for (item in items) {
                    append("item\t").append(esc(listName)).append('\t')
                        .append(if (item.done) '1' else '0').append('\t')
                        .append(item.addedAtMs).append('\t')
                        .append(esc(item.text)).append('\n')
                }
            }
            for (note in w.notes) {
                append("note\t").append(if (note.pinned) '1' else '0').append('\t')
                    .append(note.createdAtMs).append('\t')
                    .append(esc(note.text)).append('\n')
            }
            w.parking?.let { p ->
                append("park\t").append(p.savedAtMs).append('\t').append(esc(p.location)).append('\n')
            }
        }

        fun decode(raw: String?): EmaWorkspace {
            if (raw.isNullOrBlank()) return EmaWorkspace()
            val lines = raw.lineSequence().toList()
            if (lines.firstOrNull()?.trim() != SCHEMA) return EmaWorkspace()
            val lists = linkedMapOf<String, MutableList<ChecklistItem>>()
            val notes = mutableListOf<Note>()
            var parking: ParkingSpot? = null

            for (line in lines.drop(1)) {
                if (line.isBlank()) continue
                val parts = line.split('\t')
                when (parts.firstOrNull()) {
                    "item" -> if (parts.size >= 5) {
                        val key = canonicalListName(unesc(parts[1]))
                        if (key !in lists && lists.size >= MAX_LISTS) continue
                        val itemText = unesc(parts[4]).take(MAX_ITEM_CHARS)
                        if (itemText.isBlank() || looksLikeSecret(itemText)) continue
                        val bucket = lists.getOrPut(key) { mutableListOf() }
                        if (bucket.size < MAX_ITEMS_PER_LIST) {
                            bucket += ChecklistItem(
                                text = itemText,
                                done = parts[2] == "1",
                                addedAtMs = parts[3].toLongOrNull() ?: 0L
                            )
                        }
                    }
                    "note" -> if (parts.size >= 4 && notes.size < MAX_NOTES) {
                        val noteText = unesc(parts[3]).take(MAX_NOTE_CHARS)
                        if (noteText.isBlank() || looksLikeSecret(noteText)) continue
                        notes += Note(
                            text = noteText,
                            pinned = parts[1] == "1",
                            createdAtMs = parts[2].toLongOrNull() ?: 0L
                        )
                    }
                    "park" -> if (parts.size >= 3) {
                        val spotText = unesc(parts[2]).take(180)
                        if (spotText.isBlank() || looksLikeSecret(spotText)) continue
                        parking = ParkingSpot(
                            location = spotText,
                            savedAtMs = parts[1].toLongOrNull() ?: 0L
                        )
                    }
                }
            }
            return EmaWorkspace(lists.mapValues { it.value.toList() }, notes, parking)
        }

        private fun findItemIndex(items: List<ChecklistItem>, queryLow: String, preferDone: Boolean?): Int {
            val exact = items.indices.firstOrNull { idx ->
                items[idx].text.lowercase(Locale.ROOT) == queryLow &&
                    (preferDone == null || items[idx].done == preferDone)
            } ?: items.indices.firstOrNull { items[it].text.lowercase(Locale.ROOT) == queryLow }
            if (exact != null) return exact
            return items.indices.firstOrNull { idx ->
                items[idx].text.lowercase(Locale.ROOT).contains(queryLow) &&
                    (preferDone == null || items[idx].done == preferDone)
            } ?: items.indices.firstOrNull { items[it].text.lowercase(Locale.ROOT).contains(queryLow) } ?: -1
        }

        private fun humanJoin(items: List<String>): String = when (items.size) {
            0 -> ""
            1 -> items[0]
            2 -> "${items[0]} and ${items[1]}"
            else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
        }
    }
}
