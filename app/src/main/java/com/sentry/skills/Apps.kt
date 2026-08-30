package com.sentry.skills

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

data class AppMatch(val label: String, val packageName: String)

/**
 * Resolving "open whatsapp" to something launchable.
 *
 * The launcher list is read once and cached: it costs tens of milliseconds on a
 * phone with a hundred apps, and doing it inside a voice command would be the
 * slowest thing in an otherwise instant path.
 */
class Apps(private val context: Context) {

    @Volatile
    private var cache: List<AppMatch>? = null

    /** Rebuild on next use — call when packages change. */
    fun invalidate() {
        cache = null
    }

    private fun launchable(): List<AppMatch> {
        cache?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageManager = context.packageManager
        val resolved: List<ResolveInfo> = runCatching {
            packageManager.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())

        val apps = resolved.mapNotNull { info ->
            val label = runCatching { info.loadLabel(packageManager).toString() }.getOrNull()
            val packageName = info.activityInfo?.packageName
            if (label.isNullOrBlank() || packageName.isNullOrBlank()) null
            else AppMatch(label, packageName)
        }.distinctBy { it.packageName }

        cache = apps
        return apps
    }

    /** Everything the launcher shows, for diagnostics. */
    fun all(): List<AppMatch> = launchable()

    /** Best match for a spoken app name, or null when nothing is close enough. */
    fun find(query: String): AppMatch? {
        val needle = query.lowercase().trim().removeSuffix(" app").trim()
        if (needle.isEmpty()) return null

        val apps = launchable()

        apps.firstOrNull { it.label.lowercase() == needle }?.let { return it }
        apps.firstOrNull { it.label.lowercase().startsWith(needle) }?.let { return it }

        // "play store" should reach "Google Play Store", so match on word starts
        // rather than requiring the whole label to begin with what was said.
        apps.firstOrNull { app ->
            app.label.lowercase().split(' ', '-').any { it.startsWith(needle) }
        }?.let { return it }

        apps.firstOrNull { it.label.lowercase().contains(needle) }?.let { return it }

        // Finally, a package name match, which catches "open com.foo" and the times
        // the label bears no relation to what the user calls the app.
        //
        // Both sides are stripped of punctuation, not just the query. This phone runs
        // a YouTube Music fork labelled "YT Music" — no spelling of "youtube music"
        // matches that label — and its package, "anddea.youtube.music", failed too
        // because the dots were only removed from one side of the comparison.
        val squashed = needle.filter(Char::isLetterOrDigit)
        if (squashed.isEmpty()) return null
        return apps.firstOrNull { app ->
            app.packageName.lowercase().filter(Char::isLetterOrDigit).contains(squashed)
        }
    }

    fun launchIntent(packageName: String): Intent? =
        runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
