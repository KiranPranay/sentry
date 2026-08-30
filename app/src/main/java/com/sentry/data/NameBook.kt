package com.sentry.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Which contact a mangled name turned out to mean.
 *
 * [PhraseBook] rewrites whole utterances, which is the right shape for a phrase the
 * user deliberately taught but the wrong one for a name: teaching "call karma" does
 * nothing for "message karma", and nobody is going to sit through the Teach screen
 * once per verb. Names need to be learned once and then work everywhere they appear.
 *
 * The other difference is where the label comes from. A phrase has to be taught on
 * purpose; a name teaches itself. When Sentry asks "which one?" and the user picks,
 * that pick is a free supervised example — this is what the recogniser produced, and
 * this is who was meant — and it costs the user nothing to give. Recording it is the
 * difference between an assistant that mishears "maa" as "karma" forever and one that
 * gets it wrong exactly once.
 */
class NameBook(context: Context) {

    private companion object {
        const val TAG = "Sentry/NameBook"
        const val FILE = "sentry_names"
        const val KEY = "bindings"
    }

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** spoken (normalised) -> the contact name it meant. */
    @Volatile
    private var bindings: Map<String, String> = load()

    private fun load(): Map<String, String> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val json = JSONObject(raw)
        buildMap { for (key in json.keys()) put(key, json.getString(key)) }
    }.onFailure { Log.w(TAG, "could not read learned names", it) }.getOrDefault(emptyMap())

    private fun save(map: Map<String, String>) {
        val json = JSONObject()
        map.forEach { (spoken, contact) -> json.put(spoken, contact) }
        prefs.edit { putString(KEY, json.toString()) }
        bindings = map
    }

    /** Everything learned, for display. */
    fun all(): Map<String, String> = bindings

    /** The contact this spoken name has been bound to, or null. */
    fun resolve(spoken: String): String? =
        bindings[NameRules.normalise(spoken)]?.also {
            Log.i(TAG, "\"$spoken\" -> \"$it\" (learned)")
        }

    /**
     * Record that [spoken] meant [contactName].
     *
     * @return false when the pair was rejected, in which case nothing was stored.
     */
    fun bind(spoken: String, contactName: String): Boolean {
        if (!NameRules.bindable(spoken, contactName)) return false
        save(bindings + (NameRules.normalise(spoken) to contactName.trim()))
        Log.i(TAG, "learned \"${NameRules.normalise(spoken)}\" -> \"${contactName.trim()}\"")
        return true
    }

    fun forget(spoken: String) {
        save(bindings - NameRules.normalise(spoken))
    }

    fun clear() = save(emptyMap())
}

/**
 * What may be bound to a contact.
 *
 * Pure, and deliberately outside [NameBook], so the tests exercise the real rules
 * rather than a copy of them that can drift.
 */
object NameRules {

    /** Below this a binding would fire on half the noise in a room. */
    const val MIN_LENGTH = 3

    /**
     * Words that are never a person's name.
     *
     * A binding is claimed from a single pick, with no confirmation, so the cost of
     * claiming a word that turns up in ordinary speech is high and the benefit is
     * nil — "call the office" should not teach that "the" means anyone.
     */
    private val NOT_A_NAME = setOf(
        "a", "an", "and", "any", "one", "the", "them", "this", "that", "there",
        "here", "him", "her", "his", "she", "he", "they", "it", "me", "my", "you",
        "your", "someone", "somebody", "person", "people", "number", "contact",
        "phone", "call", "again", "back", "now", "please", "next", "last", "first",
        "second", "third", "other", "another",
    )

    fun normalise(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex(" +"), " ")
            .trim()

    /**
     * Squash repeated letters, so "maaaaaaa" and "maa" are the same word.
     *
     * Mirrors what the ranker does, for the same reason: people write contact names
     * with drawn-out affection and then say the short form out loud.
     */
    private fun collapse(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) if (out.isEmpty() || out.last() != c) out.append(c)
        return out.toString()
    }

    fun bindable(spoken: String, contactName: String): Boolean {
        val key = normalise(spoken)
        val target = normalise(contactName)
        if (key.length < MIN_LENGTH || target.isBlank()) return false

        // The recogniser already got it right, or close enough that the ranker will
        // find it unaided. Storing these fills the list with rules that do nothing.
        if (key == target) return false
        if (collapse(key) == collapse(target)) return false
        if (target.split(' ').any { it.startsWith(key) }) return false

        // A word from ordinary speech is not safe to claim as a name.
        if (key.split(' ').all { it in NOT_A_NAME }) return false

        return true
    }
}
