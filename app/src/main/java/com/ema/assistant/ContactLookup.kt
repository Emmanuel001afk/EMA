package com.ema.assistant

import android.content.Context
import android.provider.ContactsContract

/**
 * Shared, deterministic contact resolution.
 *
 * Calling and messaging both used to carry their own copy of this lookup with
 * slightly different behaviour, so the same spoken name could resolve to
 * different people depending on the verb. There is now one implementation.
 *
 * Ranking is explicit and total, which makes ambiguity detectable: EMA can tell
 * the difference between one clear match and several equally good ones, and asks
 * instead of guessing.
 */
object ContactLookup {

    data class Contact(
        val displayName: String,
        val phone: String?,
        val email: String?,
        val rank: Int
    )

    private const val NO_MATCH = Int.MAX_VALUE

    /**
     * Score a contact name against the spoken reference. Lower is better.
     *
     * 0 exact whole-name match; 1 every spoken word appears as a whole word in
     * the name (in any order, so "lovelace ada" finds "Ada Lovelace"); 2 the
     * reference is a prefix of the name or of one of its words; 3 a suffix;
     * 4 a loose substring anywhere. Anything else does not match.
     *
     * Whole-word matching outranks substring matching on purpose: saying "Ada"
     * must prefer the contact actually named Ada over one named Adaline.
     */
    fun rank(displayName: String, requested: String): Int {
        // Spoken input routinely carries repeated or trailing whitespace, so both
        // sides are collapsed before comparison.
        val candidate = collapse(displayName)
        val wanted = collapse(requested)
        if (candidate.isBlank() || wanted.isBlank()) return NO_MATCH

        val candidateWords = candidate.split(Regex("\\s+")).filter { it.isNotBlank() }
        val requestedWords = wanted.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (requestedWords.isEmpty()) return NO_MATCH

        return when {
            candidate == wanted -> 0
            requestedWords.all { word -> candidateWords.any { it == word } } -> 1
            candidate.startsWith(wanted) || candidateWords.any { it.startsWith(wanted) } -> 2
            candidate.endsWith(wanted) || candidateWords.any { it.endsWith(wanted) } -> 3
            candidate.contains(wanted) -> 4
            else -> NO_MATCH
        }
    }

    private fun collapse(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Contacts with any data row (phone or email), used for messaging. */
    fun find(context: Context, query: String): List<Contact> {
        val requested = query.trim().lowercase()
        if (requested.isBlank()) return emptyList()

        val grouped = linkedMapOf<String, Triple<String, String?, String?>>()
        runCatching {
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
                if (nameIndex < 0) return@use

                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIndex).orEmpty().trim()
                    if (displayName.isBlank()) continue
                    val key = displayName.lowercase()
                    val current = grouped[key]
                    val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty() else ""
                    val value = if (dataIndex >= 0) cursor.getString(dataIndex).orEmpty() else ""
                    var phone = current?.second
                    var email = current?.third
                    if (mime == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE && phone.isNullOrBlank()) phone = value
                    if (mime == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE && email.isNullOrBlank()) email = value
                    grouped[key] = Triple(displayName, phone, email)
                }
            }
        }.onFailure {
            android.util.Log.e("EMA", "Contact lookup failed for '$query'", it)
        }

        return grouped.values.mapNotNull { (displayName, phone, email) ->
            val score = rank(displayName, requested)
            if (score == NO_MATCH) null else Contact(displayName, phone, email, score)
        }
    }

    /**
     * Contacts that have a callable number.
     *
     * Provider-specific SQL ordering is avoided: some Android/OEM Contacts
     * providers reject COLLATE expressions, so the query is issued unordered and
     * ranking happens here instead.
     */
    fun findPhones(context: Context, query: String): List<Contact> {
        val requested = query.trim().lowercase()
        if (requested.isBlank()) return emptyList()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val out = mutableListOf<Contact>()

        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIndex < 0 || numberIndex < 0) return@use

                while (cursor.moveToNext()) {
                    val contactName = cursor.getString(nameIndex).orEmpty().trim()
                    val number = cursor.getString(numberIndex).orEmpty().trim()
                    if (contactName.isBlank() || number.isBlank()) continue
                    val score = rank(contactName, requested)
                    if (score != NO_MATCH) out += Contact(contactName, number, null, score)
                }
            }
        }.onFailure {
            android.util.Log.e("EMA", "Phone contact lookup failed for '$query'", it)
        }
        return out
    }

    /**
     * The strongest candidates, deduplicated. More than one result means the
     * reference was genuinely ambiguous and EMA must ask rather than choose.
     */
    fun best(candidates: List<Contact>): List<Contact> {
        if (candidates.isEmpty()) return emptyList()
        val bestRank = candidates.minOf { it.rank }
        return candidates.filter { it.rank == bestRank }
            .distinctBy { it.displayName.lowercase() to (it.phone ?: it.email) }
    }

    /**
     * Resolve a spoken number: digits are used as-is, a name goes through the
     * contact database. Returns null when nothing matched.
     */
    fun resolveCallableNumber(context: Context, reference: String): Contact? {
        val trimmed = reference.trim()
        if (trimmed.matches(Regex("[+0-9()\\-\\s]{7,}"))) {
            return Contact(trimmed, trimmed.replace(Regex("[^+0-9]"), ""), null, 0)
        }
        return best(findPhones(context, trimmed)).firstOrNull()
    }
}
