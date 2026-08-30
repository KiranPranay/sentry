package com.sentry.service

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.sentry.ui.AssistantActivity

/**
 * The assist gesture's entry point.
 *
 * Deliberately thin: it starts [AssistantActivity] and gets out of the way. A
 * session can host its own view hierarchy, but that view has no lifecycle owner, no
 * `ViewModelStore` and no saved-state registry, so Compose and every lifecycle-aware
 * API have to be hand-wired into it. Handing off to an activity means the assist
 * gesture, the wake word and the launcher icon all arrive at exactly the same screen
 * with none of that scaffolding — and there is only one UI to get right.
 */
class SentrySession(context: android.content.Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        val intent = Intent(context, AssistantActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            putExtra(AssistantActivity.EXTRA_LISTEN_IMMEDIATELY, true)
            putExtra(AssistantActivity.EXTRA_SOURCE, "assist")
        }
        context.startActivity(intent)

        // Close the session window immediately; the activity is the real UI and two
        // overlapping assistant surfaces would fight over the microphone.
        hide()
    }
}
