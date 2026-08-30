package com.sentry.skills

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * @param label how to describe this number when several belong to one person —
 *   "Mobile", "Home". Blank when the person has only one.
 */
data class ContactMatch(val name: String, val number: String, val label: String = "") {
    /** What to read out when offering a choice. */
    val spoken: String get() = if (label.isBlank()) name else "$name, $label"
}

/** Contacts to bias recognition towards. Beyond this the grammar costs more than it gives. */
private const val MAX_BIAS_NAMES = 80

/** A spoken list longer than this stops being a question and becomes a recitation. */
private const val MAX_OFFERED = 5

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
    fun find(query: String): Lookup {
        if (!hasPermission() || query.isBlank()) return Lookup(emptyList(), certain = false)

        val rows = queryContacts(query)
        if (rows.isEmpty()) return Lookup(emptyList(), certain = false)

        // One row per person for ranking. Which of their numbers to use is a
        // separate question, answered below only if it turns out to matter.
        val byName = rows.groupBy { it.name.lowercase() }
            .map { (_, group) -> group.first() }

        val ranked = ContactRanker.rank(byName, query)

        // A single person with several numbers is still a choice the user has to
        // make. Collapsing to the first — which is what this did — silently dialled
        // whichever number the address book happened to return first.
        if (ranked.size == 1) {
            val numbers = rows
                .filter { it.name.equals(ranked[0].name, ignoreCase = true) }
                .distinctBy { it.number.filter(Char::isDigit) }
            // Still the same certainty: which of Maa's two numbers to ring is a
            // different question from whether Maa is who was meant.
            if (numbers.size > 1) return Lookup(numbers, ranked.certain)
        }
        return ranked
    }

    /**
     * Names worth biasing the recogniser towards.
     *
     * Starred contacts, because that is the only signal available without
     * `READ_CALL_LOG` — and the people someone calls by voice are overwhelmingly the
     * people they have starred. First names only: nobody says "call Ravi Kumar
     * Reddy" to a phone, and a long phrase in the grammar is dead weight.
     *
     * Capped, because this list is compiled into an FST every time the microphone
     * opens. A thousand contacts would cost more in build time than the accuracy is
     * worth.
     */
    fun biasNames(limit: Int = MAX_BIAS_NAMES): List<String> {
        if (!hasPermission()) return emptyList()

        val names = mutableListOf<String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                "${ContactsContract.Contacts.STARRED} = 1",
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val column = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                if (column < 0) return@use
                while (cursor.moveToNext() && names.size < limit) {
                    val display = cursor.getString(column) ?: continue
                    // Strip emoji and decoration, then take the first word.
                    val first = display.lowercase()
                        .filter { it.isLetter() || it == ' ' }
                        .trim()
                        .split(' ')
                        .firstOrNull { it.length >= 2 }
                        ?: continue
                    names.add(first)
                }
            }
        }
        return names.distinct()
    }

    /**
     * The people the user has starred, as callable matches.
     *
     * Used when a spoken name matches nobody at all. "Karma" cannot be fuzzy-matched
     * to "Maa" by any amount of string work — the recogniser replaced the word, it
     * did not misspell it — so the only honest move left is to ask. Starred contacts
     * are the right list to ask from: they are short, and the people someone calls by
     * voice are overwhelmingly the people they have starred.
     */
    fun starred(limit: Int = MAX_OFFERED): List<ContactMatch> {
        if (!hasPermission()) return emptyList()

        val matches = mutableListOf<ContactMatch>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1",
                null,
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
        return matches.distinctBy { it.name.lowercase() }.take(limit)
    }

    private fun queryContacts(query: String): List<ContactMatch> {
        val matches = mutableListOf<ContactMatch>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
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
                val typeColumn = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.TYPE
                )
                val labelColumn = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.LABEL
                )
                if (nameColumn < 0 || numberColumn < 0) return@use
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    val number = cursor.getString(numberColumn) ?: continue
                    val label = if (typeColumn >= 0) {
                        ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                            context.resources,
                            cursor.getInt(typeColumn),
                            if (labelColumn >= 0) cursor.getString(labelColumn) else null,
                        ).toString()
                    } else {
                        ""
                    }
                    matches.add(ContactMatch(name, number, label))
                }
            }
        }
        return matches
    }

}
