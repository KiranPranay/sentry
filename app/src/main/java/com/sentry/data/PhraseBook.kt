package com.sentry.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONObject

/**
 * What Sentry has learned about how *this* person says things.
 *
 * The problem it solves is narrow and stubborn. Asked to "call maa", the recogniser
 * returns "karma" — every time, reliably, because "maa" is not in its lexicon and
 * "karma" is the nearest thing that is. No amount of fuzzy matching on the contact
 * name helps, because by then the word "call" has been swallowed too and there is
 * nothing left to match.
 *
 * Retraining the acoustic model on the phone is not an option, and the "Ok Google"
 * enrollment people picture does not do that either — those phrases build a *speaker*
 * profile for the wake word, not a transcription model. What is available, and what
 * this is, is the other half of the trick: if the mistake is consistent, learn the
 * mistake. The user says the phrase they want a few times, Sentry writes down what it
 * actually heard, and from then on it translates.
 *
 * Stored as a rewrite from heard text to intended text rather than to a command, so
 * a learned phrase goes through exactly the same pipeline as a correctly heard one
 * and cannot drift out of step with it.
 */
class PhraseBook(context: Context) {

    private companion object {
        const val TAG = "Sentry/PhraseBook"
        const val FILE = "sentry_phrases"
        const val KEY = "rewrites"

        /**
         * A heard phrase must be at least this long to be learned.
         *
         * Binding a single short syllable would fire on half the noise in a room.
         */
        const val MIN_HEARD_LENGTH = 3

        /**
         * Words too common to hand over to a learned rule.
         *
         * Teaching "what is the date" produced "what" twice, and binding *that* would
         * mean every later sentence beginning and ending with "what" silently became
         * a date query. A rare mis-hearing like "karma" is safe to claim; a function
         * word the user says a hundred times a day is not, so a single one of these
         * on its own is refused. In a longer phrase they are fine — "what does" is
         * distinctive in a way "what" is not.
         */
        val TOO_COMMON = setOf(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "do",
            "for", "from", "go", "he", "her", "hey", "him", "his", "how", "i", "if",
            "in", "is", "it", "its", "me", "my", "no", "not", "now", "of", "off",
            "ok", "okay", "on", "one", "or", "our", "out", "please", "she", "so",
            "that", "the", "them", "then", "there", "they", "this", "to", "up", "us",
            "was", "we", "well", "were", "what", "when", "where", "which", "who",
            "why", "will", "with", "yes", "you", "your",
        )
    }

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** heard (normalised) -> what the user actually meant. */
    @Volatile
    private var rewrites: Map<String, String> = load()

    private fun load(): Map<String, String> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val json = JSONObject(raw)
        buildMap {
            for (key in json.keys()) put(key, json.getString(key))
        }
    }.onFailure { Log.w(TAG, "could not read learned phrases", it) }.getOrDefault(emptyMap())

    private fun save(map: Map<String, String>) {
        val json = JSONObject()
        map.forEach { (heard, meant) -> json.put(heard, meant) }
        prefs.edit { putString(KEY, json.toString()) }
        rewrites = map
    }

    /** Everything learned, for display, newest last. */
    fun all(): Map<String, String> = rewrites

    /**
     * Translate an utterance, or return it unchanged.
     *
     * Matches the whole utterance only. A substring rule would let "karma" rewrite
     * the middle of an unrelated sentence, which is a much worse failure than not
     * having learned the phrase at all.
     */
    fun translate(heard: String): String {
        val key = normalise(heard)
        val meant = rewrites[key] ?: return heard
        Log.i(TAG, "\"$heard\" -> \"$meant\" (learned)")
        return meant
    }

    /**
     * Teach one mapping.
     *
     * @return false when the pair was rejected — too short, or identical to what was
     *   meant, in which case there is nothing to learn.
     */
    fun learn(heard: String, meant: String): Boolean {
        val key = normalise(heard)
        val target = meant.trim()
        if (key.length < MIN_HEARD_LENGTH || target.isBlank()) return false
        // Nothing to fix: the recogniser already got it right.
        if (key == normalise(target)) return false
        // One very common word on its own is not a mis-hearing worth claiming.
        if (!key.contains(' ') && key in TOO_COMMON) {
            Log.i(TAG, "refusing to learn \"$key\": too common a word to bind")
            return false
        }

        save(rewrites + (key to target))
        Log.i(TAG, "learned \"$key\" -> \"$target\"")
        return true
    }

    fun forget(heard: String) {
        save(rewrites - normalise(heard))
    }

    /** Remove every rule that maps to [meant]. Used when re-teaching a phrase. */
    fun forgetTarget(meant: String) {
        val target = normalise(meant)
        save(rewrites.filterValues { normalise(it) != target })
    }

    private fun normalise(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex(" +"), " ")
            .trim()
}
