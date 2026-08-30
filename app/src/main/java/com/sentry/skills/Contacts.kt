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

        return ContactRanker.rank(byName, query)
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

}
