package com.sentry.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Takes screenshots on command.
 *
 * An accessibility service, because it is the only route an app has to the system
 * screenshot. `MediaProjection` — the other option — puts a consent dialog in front of
 * the user every single time it starts, which is fine for a screen recorder and
 * useless for something meant to answer "take a screenshot" in one breath.
 *
 * That is a real cost: accessibility services can, in principle, read the screen. This
 * one is deliberately inert — it declares no event types, subscribes to no windows,
 * and its [onAccessibilityEvent] does nothing at all. The only capability it uses is
 * the global screenshot action.
 */
class ScreenshotService : AccessibilityService() {

    companion object {
        private const val TAG = "Sentry/Screenshot"

        @Volatile
        private var instance: ScreenshotService? = null

        /**
         * Whether the user has switched the service on.
         *
         * Reads the setting rather than trusting [instance], so the answer is right
         * even before Android has bound us.
         */
        fun isEnabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return enabled.contains(context.packageName + "/" + ScreenshotService::class.java.name)
        }

        /** @return false when the service is not connected or the platform refused. */
        fun take(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            val service = instance ?: return false
            return runCatching {
                service.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            }.onFailure { Log.w(TAG, "screenshot failed", it) }.getOrDefault(false)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "screenshot service connected")
    }

    /** Deliberately empty. This service watches nothing. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
