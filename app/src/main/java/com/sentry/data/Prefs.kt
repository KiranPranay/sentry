package com.sentry.data

import android.content.Context
import androidx.core.content.edit
import com.sentry.brain.Brains
import com.sentry.voice.SpeechPack

/**
 * The handful of settings Sentry has.
 *
 * Plain SharedPreferences rather than DataStore: every one of these is read
 * synchronously during service startup, and a suspending read there would mean the
 * hotword service starts before it knows whether the user wanted it on.
 */
class Prefs(context: Context) {

    private companion object {
        const val FILE = "sentry"
        const val KEY_HOTWORD = "hotword_enabled"
        const val KEY_BACKEND = "backend"
        const val KEY_LOCKSCREEN = "lockscreen_enabled"
        const val KEY_SPEECH_PACK = "speech_pack"
    }

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether "Sentry" wakes the assistant. Off until the user turns it on. */
    var hotwordEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOTWORD, false)
        set(value) = prefs.edit { putBoolean(KEY_HOTWORD, value) }

    /** Whether the assistant may open over the lock screen. */
    var lockscreenEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCKSCREEN, true)
        set(value) = prefs.edit { putBoolean(KEY_LOCKSCREEN, value) }

    /** Which acoustic model to recognise with. Accent, not size, is the point. */
    var speechPack: SpeechPack
        get() = SpeechPack.from(prefs.getString(KEY_SPEECH_PACK, null))
        set(value) = prefs.edit { putString(KEY_SPEECH_PACK, value.name) }

    var backend: String
        get() = prefs.getString(KEY_BACKEND, Brains.Preference.AUTO.name)
            ?: Brains.Preference.AUTO.name
        set(value) = prefs.edit { putString(KEY_BACKEND, value) }

    fun backendPreference(): Brains.Preference =
        runCatching { Brains.Preference.valueOf(backend) }
            .getOrDefault(Brains.Preference.AUTO)
}
