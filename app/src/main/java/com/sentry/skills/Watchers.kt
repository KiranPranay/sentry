package com.sentry.skills

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log

/**
 * Keeps the contact and app indexes current.
 *
 * Without this both go stale in ways the user experiences as the assistant being
 * wrong rather than out of date: a contact saved five minutes ago cannot be called,
 * and an app installed this morning cannot be opened until the process restarts.
 *
 * Both signals are cheap and rare — people do not add contacts or install apps every
 * few seconds — so the work is debounced and done off the main thread, and the
 * expensive part (rebuilding the recogniser's bias grammar) happens once per burst
 * rather than once per changed row.
 */
class Watchers(
    private val context: Context,
    private val apps: Apps,
    private val onChanged: () -> Unit,
) {

    private companion object {
        const val TAG = "Sentry/Watchers"

        /**
         * Editing one contact fires several change notifications — name, number,
         * photo. Rebuilding an 80-entry grammar for each would be wasteful, and the
         * user cannot tell the difference between reacting in 50 ms and in 2 s.
         */
        const val DEBOUNCE_MS = 2_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val rebuild = Runnable {
        Log.i(TAG, "address book or app list changed; reindexing")
        apps.invalidate()
        onChanged()
    }

    private val contactsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            schedule()
        }
    }

    private val packagesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(rebuild)
        handler.postDelayed(rebuild, DEBOUNCE_MS)
    }

    fun start() {
        runCatching {
            context.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                contactsObserver,
            )
        }.onFailure { Log.w(TAG, "could not watch contacts", it) }

        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            context.registerReceiver(packagesReceiver, filter)
        }.onFailure { Log.w(TAG, "could not watch packages", it) }

        Log.i(TAG, "watching contacts and installed apps")
    }

    fun stop() {
        runCatching { context.contentResolver.unregisterContentObserver(contactsObserver) }
        runCatching { context.unregisterReceiver(packagesReceiver) }
        handler.removeCallbacks(rebuild)
    }
}
