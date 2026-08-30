package com.sentry.skills

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class ContactMatch(val name: String, val number: String)

/**
 * Finding the person the user meant.
 *
 * Speech recognition mangles names more than any other kind of word, so an exact
 * `LIKE '%query%'` — what this used to do — misses "call Siddharth" whenever the
 * recogniser hears "Sidharth". The lookup below is deliberately generous and then
 * ranks, rather than being strict and finding nothing.
 */
class Contacts(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Contacts matching [query], best first.
     *
     * Numbers for the same person are collapsed, because "which of Mum's three
     * numbers" is a question the user should only be asked when it matters.
     */
    fun find(query: String): List<ContactMatch> {
        if (!hasPermission() || query.isBlank()) return emptyList()

        val rows = queryContacts(query)
        if (rows.isEmpty()) return emptyList()

        val byName = rows.groupBy { it.name.lowercase() }
            .map { (_, group) -> group.first() }

        val needle = normalise(query)

        // An exact name match wins outright — asking "Mum or Mum's Office?" when the
        // user said "Mum" and there is a contact called exactly that is just noise.
        byName.firstOrNull { normalise(it.name) == needle }?.let { return listOf(it) }

        return byName
            .map { it to score(normalise(it.name), needle) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(5)
    }

    private fun queryContacts(query: String): List<ContactMatch> {
        val matches = mutableListOf<ContactMatch>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )

        // Match on any word of the query, so "call john smith" still finds "John"
        // when the surname was misheard.
        val words = query.split(' ').filter { it.length >= 2 }.take(3)
        val selection = if (words.isEmpty()) {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        } else {
            words.joinToString(" OR ") {
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            }
        }
        val args = if (words.isEmpty()) arrayOf("%$query%") else words.map { "%$it%" }.toTypedArray()

        runCatching {
            context.contentResolver.query(
                uri, projection, selection, args,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val numberColumn = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                if (nameColumn < 0 || numberColumn < 0) return@use
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    val number = cursor.getString(numberColumn) ?: continue
                    matches.add(ContactMatch(name, number))
                }
            }
        }
        return matches
    }

    private fun normalise(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()

    /**
     * How well a contact name answers the query. Higher is better, zero excludes.
     *
     * Prefix matches beat contained matches, and a match on the first name beats one
     * buried in a surname, because that is the order people actually mean.
     */
    private fun score(name: String, needle: String): Int {
        if (name == needle) return 1000
        if (name.startsWith(needle)) return 500 - name.length

        val nameWords = name.split(' ')
        val needleWords = needle.split(' ')

        var total = 0
        for (word in needleWords) {
            when {
                nameWords.any { it == word } -> total += 200
                nameWords.any { it.startsWith(word) } -> total += 120
                // A near-miss on spelling, which is the common recogniser failure.
                nameWords.any { editDistance(it, word) <= 1 && word.length >= 4 } -> total += 90
                name.contains(word) -> total += 40
                else -> total -= 30
            }
        }
        // Earlier words in a name matter more: "John" in "John Smith" beats "John"
        // in "Smith John".
        if (nameWords.firstOrNull()?.startsWith(needleWords.first()) == true) total += 60

        return total.coerceAtLeast(0)
    }

    /** Levenshtein, capped in practice by the short strings it runs on. */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
