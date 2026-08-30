package com.sentry.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Resolving what the user calls an app to what is actually installed.
 *
 * The label and the package are both unreliable on their own. People rename apps,
 * install forks, and say the name of the thing rather than the name on the icon —
 * this phone's YouTube Music is a fork whose label is "YT Music" and whose package
 * is "anddea.youtube.music", and "open youtube music" matched neither.
 *
 * [Apps] needs a PackageManager, so the matching rules are exercised through the same
 * ordered strategies it applies, against a fixed list.
 */
class AppsMatchTest {

    private val installed = listOf(
        AppMatch("YT Music", "anddea.youtube.music"),
        AppMatch("Spotify", "com.spotify.music"),
        AppMatch("YouTube", "com.google.android.youtube"),
        AppMatch("Google Play Store", "com.android.vending"),
        AppMatch("WhatsApp", "com.whatsapp"),
        AppMatch("Messages", "com.google.android.apps.messaging"),
    )

    /** The strategies from [Apps.find], in order. */
    private fun find(query: String): AppMatch? {
        val needle = query.lowercase().trim().removeSuffix(" app").trim()
        if (needle.isEmpty()) return null

        installed.firstOrNull { it.label.lowercase() == needle }?.let { return it }
        installed.firstOrNull { it.label.lowercase().startsWith(needle) }?.let { return it }
        installed.firstOrNull { app ->
            app.label.lowercase().split(' ', '-').any { it.startsWith(needle) }
        }?.let { return it }
        installed.firstOrNull { it.label.lowercase().contains(needle) }?.let { return it }

        val squashed = needle.filter(Char::isLetterOrDigit)
        if (squashed.isEmpty()) return null
        return installed.firstOrNull { app ->
            app.packageName.lowercase().filter(Char::isLetterOrDigit).contains(squashed)
        }
    }

    @Test
    fun `a fork found by its package rather than its label`() {
        // The case this test exists for: nothing about "YT Music" resembles what was
        // said, and the dots in the package used to defeat the last-resort match.
        assertEquals("anddea.youtube.music", find("youtube music")?.packageName)
    }

    @Test
    fun `the obvious matches still win`() {
        assertEquals("com.spotify.music", find("spotify")?.packageName)
        assertEquals("com.google.android.youtube", find("youtube")?.packageName)
        assertEquals("com.whatsapp", find("whatsapp")?.packageName)
        // A word start, not just a prefix of the whole label.
        assertEquals("com.android.vending", find("play store")?.packageName)
    }

    @Test
    fun `an app that is not installed is not invented`() {
        assertNull(find("netflix"))
        assertNull(find(""))
        assertNull(find("   "))
    }
}
