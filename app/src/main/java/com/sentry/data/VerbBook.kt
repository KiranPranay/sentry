package com.sentry.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.sentry.core.LevelTarget
import org.json.JSONObject

/**
 * Which way a mis-heard word turned out to mean.
 *
 * The recogniser reliably destroys the verb of a level command while leaving the noun
 * intact: "increase the volume" arrives as "jellyfish the volume", "the jewish the
 * volume percent", "increased devised the brightness". The noun is enough to know a
 * level command was meant, and not enough to know which way.
 *
 * Guessing is not available. Every classical phonetic code — Soundex, Metaphone,
 * NYSIIS — rates "jellyfish" *closer to decrease than to increase*, and Soundex puts
 * "jewish" one step from "max", so a matcher confident enough to recover these would
 * cheerfully set the volume to maximum on a mis-hearing. The audio that would settle
 * it was thrown away by the decoder before any of this code runs.
 *
 * What is available is the user. Sentry asks once — "up or down?" — and writes the
 * answer down against the word it actually heard. Their answer is the authority, not
 * a distance metric, and it is right by construction. This is the same bargain as
 * [NameBook], and for the same reason.
 */
class VerbBook(context: Context) {

    private companion object {
        const val TAG = "Sentry/VerbBook"
        const val FILE = "sentry_verbs"
        const val KEY = "directions"

        /**
         * Words too ordinary to bind, even with a target noun beside them.
         *
         * A binding is claimed from one answer and then steers silently, so a word
         * the user says all day is not safe to claim. The noun requirement does most
         * of the work; this catches the rest.
         */
        val TOO_COMMON = setOf(
            "the", "a", "an", "and", "but", "for", "with", "this", "that", "there",
            "here", "you", "your", "my", "me", "it", "its", "is", "was", "are",
            "can", "will", "just", "now", "then", "some", "any", "all", "not",
            "please", "okay", "ok", "yes", "no", "hey", "hi", "so", "well",
        )

        const val MIN_LENGTH = 3
    }

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** "<target>:<spoken>" -> the canonical verb it stands for. */
    @Volatile
    private var learned: Map<String, String> = load()

    private fun load(): Map<String, String> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val json = JSONObject(raw)
        buildMap { for (key in json.keys()) put(key, json.getString(key)) }
    }.onFailure { Log.w(TAG, "could not read learned directions", it) }.getOrDefault(emptyMap())

    private fun save(map: Map<String, String>) {
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        prefs.edit { putString(KEY, json.toString()) }
        learned = map
    }

    private fun key(target: LevelTarget, spoken: String) =
        "${target.name}:${normalise(spoken)}"

    /** Everything learned, as spoken word to verb, for display. */
    fun all(): Map<String, String> = learned.mapKeys { (k, _) -> k.substringAfter(':') }

    /**
     * The verb this word has been taught to mean, or null.
     *
     * Bound per target, because the same mis-hearing can sit in front of either noun
     * and the two are answered separately.
     */
    fun resolve(target: LevelTarget, spoken: String): String? =
        learned[key(target, spoken)]?.also {
            Log.i(TAG, "\"$spoken\" -> \"$it\" (learned, ${target.name})")
        }

    fun bind(target: LevelTarget, spoken: String, verb: String): Boolean {
        val word = normalise(spoken)
        if (word.length < MIN_LENGTH || word in TOO_COMMON) {
            Log.i(TAG, "refusing to bind \"$word\"")
            return false
        }
        save(learned + (key(target, spoken) to verb))
        Log.i(TAG, "learned \"$word\" means \"$verb\" for ${target.name}")
        return true
    }

    fun forget(spoken: String) {
        val word = normalise(spoken)
        save(learned.filterKeys { it.substringAfter(':') != word })
    }

    private fun normalise(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]"), "").trim()
}
