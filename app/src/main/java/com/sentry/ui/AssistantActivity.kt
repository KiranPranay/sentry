package com.sentry.ui

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.sentry.sentry
import com.sentry.ui.theme.SentryTheme
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The assistant, drawn over whatever the user was doing.
 *
 * Deliberately an activity rather than a `VoiceInteractionSession` view. The session
 * gives you a bare `View` with no lifecycle owner, no saved-state registry and no
 * `ViewModelStore`, so Compose has to be hand-wired into it and every lifecycle-aware
 * API becomes a special case. An activity gets all of that for free, works
 * identically whether it was opened by the wake word, the assist gesture or the
 * launcher, and — the part that matters here — can be shown over the lock screen.
 */
class AssistantActivity : ComponentActivity() {

    companion object {
        const val EXTRA_LISTEN_IMMEDIATELY = "listen_immediately"
        const val EXTRA_SOURCE = "source"
    }

    private val viewModel: AssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showOverLockScreen()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val listenImmediately = intent.getBooleanExtra(EXTRA_LISTEN_IMMEDIATELY, false)
        viewModel.startSession(listenImmediately)

        lifecycleScope.launch {
            viewModel.finished.filter { it }.collect { finish() }
        }

        setContent {
            SentryTheme {
                AssistantScreen(
                    viewModel = viewModel,
                    onDismiss = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Re-launched by the wake word while already open: start a new session rather
        // than leaving the previous conversation on screen.
        viewModel.startSession(intent.getBooleanExtra(EXTRA_LISTEN_IMMEDIATELY, false))
    }

    /**
     * Make the assistant usable without unlocking.
     *
     * This is most of the answer to "the lock screen only does answer and decline" —
     * setting a timer, turning on the torch or answering a call are all things that
     * should not require the phone in your hand and your face in front of it.
     * Anything that actually exposes private data still asks for a dismiss.
     */
    private fun showOverLockScreen() {
        if (!sentry.prefs.lockscreenEnabled) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    /**
     * Ask the user to unlock, for the few things that should not happen on a locked
     * phone — reading messages back, opening an app with personal content.
     *
     * Deliberately *not* called on open: dismissing the keyguard the moment the
     * assistant appears would undo [showOverLockScreen] entirely, and setting a timer
     * or answering a call has no business demanding a fingerprint.
     */
    fun requireUnlock(onUnlocked: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            onUnlocked()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = onUnlocked()
            },
        )
    }

    override fun onStop() {
        super.onStop()
        // Whether we were dismissed or navigated away from, the microphone goes back
        // to the wake-word service; otherwise "Sentry" stops working after one use.
        viewModel.releaseToHotword()
    }
}
