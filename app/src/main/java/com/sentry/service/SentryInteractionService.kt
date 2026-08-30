package com.sentry.service

import android.service.voice.VoiceInteractionService
import android.util.Log
import com.sentry.sentry
import com.sentry.voice.HotwordService

/**
 * Registers Sentry as an assistant Android knows about.
 *
 * Being the default assistant is what earns the assist gesture and the power-button
 * hold. It is not how the wake word works — that is [HotwordService], because the
 * system's own [android.service.voice.AlwaysOnHotwordDetector] is in practice
 * reserved for the manufacturer's enrolled keyphrase and will not give a third-party
 * app a custom one.
 */
class SentryInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i("Sentry/VIS", "voice interaction service ready")

        // Being made the default assistant is the moment the user has clearly opted
        // in, so this is where the wake word comes back after a reboot or an update.
        if (sentry.prefs.hotwordEnabled) {
            HotwordService.start(this)
        }
    }
}
