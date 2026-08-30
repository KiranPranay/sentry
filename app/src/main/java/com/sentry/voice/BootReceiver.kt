package com.sentry.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sentry.sentry

/**
 * Restores the wake word after a reboot or an app update.
 *
 * Without this, "Sentry" silently stops working every time the phone restarts, and
 * the user has no way to know why.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (context.sentry.prefs.hotwordEnabled) HotwordService.start(context)
            }
        }
    }
}
