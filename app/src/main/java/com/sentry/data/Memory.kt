package com.sentry.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONObject

/**
 * The things Sentry knows about the person using it.
 *
 * A closed set of keys rather than free-form key/value, and that is the whole design
 * decision. An open store sounds more capable and is worse in every way that matters
 * here: the same fact arrives as "mother", "mothers name", "mom" and "Mother", none
 * of which can be deduplicated or updated; the list grows without bound, so it can no
 * longer be handed to the model wholesale and needs a retrieval step; and picking a
 * label from a fixed list is the one thing a small model does reliably, which is the
 * same reasoning [com.sentry.nlu.Planner] already uses for intent labels.
 *
 * Bounded at a dozen keys, the entire memory fits in a prompt, so there is no
 * retrieval problem to solve at all.
 */
enum class Fact(val label: String, val question: String) {
    NAME("Name", "what should I call you"),
    NICKNAME("Nickname", "your nickname"),
    MOTHER("Mother", "your mother's name"),
    FATHER("Father", "your father's name"),
    SPOUSE("Partner", "your partner's name"),
    SIBLING("Sibling", "your brother or sister"),
    BLOOD_GROUP("Blood group", "your blood group"),
    BIRTHDAY("Birthday", "your birthday"),
    PHONE("My number", "your own phone number"),
    EMAIL("Email", "your email address"),
    HOME("Home", "where you live"),
    WORK("Work", "where you work"),
    ;

    companion object {
        fun from(name: String?): Fact? = entries.firstOrNull { it.name == name }
    }
}

/** One remembered fact and where it came from. */
data class Remembered(val fact: Fact, val value: String, val source: String)

/**
 * Durable facts, kept on the phone.
 *
 * Deliberately small and legible: the user can see every row, and every row records
 * the sentence it came from, because a fact whose provenance is invisible is one the
 * user cannot judge or correct.
 */
class Memory(context: Context) {

    private companion object {
        const val TAG = "Sentry/Memory"
        const val FILE = "sentry_memory"
        const val KEY = "facts"

        /** Nothing longer than this is a name, a blood group or a birthday. */
        const val MAX_VALUE_LENGTH = 60
    }

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Volatile
    private var facts: Map<Fact, Remembered> = load()

    private fun load(): Map<Fact, Remembered> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val json = JSONObject(raw)
        buildMap {
            for (key in json.keys()) {
                val fact = Fact.from(key) ?: continue
                val row = json.getJSONObject(key)
                put(fact, Remembered(fact, row.getString("value"), row.optString("source")))
            }
        }
    }.onFailure { Log.w(TAG, "could not read memory", it) }.getOrDefault(emptyMap())

    private fun persist(map: Map<Fact, Remembered>) {
        val json = JSONObject()
        map.forEach { (fact, row) ->
            json.put(fact.name, JSONObject().put("value", row.value).put("source", row.source))
        }
        prefs.edit { putString(KEY, json.toString()) }
        facts = map
    }

    fun all(): List<Remembered> = Fact.entries.mapNotNull { facts[it] }

    operator fun get(fact: Fact): String? = facts[fact]?.value

    val isEmpty: Boolean get() = facts.isEmpty()

    /**
     * Record a fact.
     *
     * Later statements win. Someone correcting Sentry — "no, my mother's name is
     * Rani" — is the most important case to get right, and the simplest rule that
     * gets it right is that the most recent thing said is the truth.
     */
    fun remember(fact: Fact, value: String, source: String = ""): Boolean {
        val cleaned = value.trim().trim('.', ',', '!', '?').trim()
        if (cleaned.isBlank() || cleaned.length > MAX_VALUE_LENGTH) return false

        val existing = facts[fact]?.value
        if (existing.equals(cleaned, ignoreCase = true)) return false

        persist(facts + (fact to Remembered(fact, cleaned, source)))
        Log.i(TAG, "remembered ${fact.name} = \"$cleaned\"")
        return true
    }

    fun forget(fact: Fact) {
        if (facts.containsKey(fact)) {
            persist(facts - fact)
            Log.i(TAG, "forgot ${fact.name}")
        }
    }

    fun clear() = persist(emptyMap())

    /**
     * Everything known, as a line for the model's system prompt.
     *
     * The whole store, because it is bounded — a dozen short values is a few dozen
     * tokens, cheaper than any scheme for deciding which ones are relevant, and it
     * never needs an embedding index.
     */
    fun asPromptContext(): String {
        if (facts.isEmpty()) return ""
        val lines = all().joinToString("\n") { "- ${it.fact.label}: ${it.value}" }
        return "What you know about the person you are talking to:\n$lines"
    }
}
