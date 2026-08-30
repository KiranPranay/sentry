package com.sentry.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sentry.sentry

/**
 * Tries to restore the wake word after a reboot or an app update.
 *
 * "Tries", because Android 14+ will not let a microphone-type foreground service
 * start from the background, and a boot broadcast is the background. [HotwordService.start]
 * swallows the refusal rather than crashing, and the setup screen — which is in the
 * foreground by definition — is where it reliably comes back. Attempting it costs
 * nothing and works on the older releases where it is still permitted.
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
