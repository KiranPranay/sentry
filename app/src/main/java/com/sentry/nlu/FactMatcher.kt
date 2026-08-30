package com.sentry.nlu

import com.sentry.data.Fact

/**
 * Noticing durable facts in what someone says, without a language model.
 *
 * A sibling of [FastMatcher], and for the same reason: "my mother's name is Rani" is
 * a template, and templates are a job for a regular expression. Asking a 0.5B to
 * extract it would cost a round trip, and — because the backend keeps a single
 * prompt-prefix cache — would also make the *next* conversational turn re-prefill
 * from scratch. The deterministic path costs neither.
 *
 * Precision matters more than coverage here, because a wrong fact is worse than a
 * missing one: it is invisible until it is repeated back, and it silently poisons
 * every later answer. So this only fires on unambiguous statements and refuses
 * everything else.
 */
object FactMatcher {

    /**
     * A statement, or a question about a statement?
     *
     * "What is my blood group?" contains every word "my blood group is B positive"
     * does. Storing a fact from a question is the single most likely way to end up
     * with nonsense in memory, so questions are refused outright before any pattern
     * is tried.
     */
    private val QUESTION_OPENERS = setOf(
        "what", "whats", "what's", "who", "whos", "who's", "when", "where", "why",
        "which", "how", "do", "does", "did", "is", "are", "was", "were", "can",
        "could", "will", "would", "tell", "remind", "remember",
    )

    /**
     * Each fact, and the ways people actually introduce it.
     *
     * The value is whatever follows; [clean] trims the politeness off the end.
     */
    /**
     * "my <thing> is <value>", in the shapes people really say it.
     *
     * Built rather than written out because the possessive is the trap. Speech
     * recognisers do not emit apostrophes, so "my mother's name is Rani" arrives as
     * "my mothers name is Rani" — and an alternation that treats a bare "s" as the
     * copula happily matched "mother" + "s" and captured "name is Rani" as the
     * mother's name. Requiring an explicit "is" removes the ambiguity entirely.
     */
    private fun possessive(vararg words: String): Regex {
        val alternatives = words.joinToString("|")
        return Regex("""\bmy (?:$alternatives)(?:'s|s)?(?:\s+name)?(?:'s)?\s+(?:is|was|=)\s+(.+)""")
    }

    private val PATTERNS: List<Pair<Fact, Regex>> = listOf(
        Fact.NAME to possessive("name"),
        Fact.NAME to Regex("""\bi(?:'m| am)\s+called\s+(.+)"""),
        Fact.NAME to Regex("""\byou can call me\s+(.+)"""),

        Fact.NICKNAME to possessive("nick ?name"),
        Fact.NICKNAME to Regex("""\b(?:they|everyone|people|friends) calls? me\s+(.+)"""),
        Fact.NICKNAME to Regex("""\balso called(?: as)?\s+(.+)"""),

        Fact.MOTHER to possessive("mother", "mom", "mum", "amma", "mummy", "maa"),
        Fact.FATHER to possessive("father", "dad", "daddy", "papa", "nanna", "appa"),
        Fact.SPOUSE to possessive("wife", "husband", "partner"),
        Fact.SIBLING to possessive("brother", "sister", "sibling"),

        Fact.BLOOD_GROUP to possessive("blood ?group", "blood ?type"),
        Fact.BIRTHDAY to possessive("birthday", "date of birth", "dob"),
        Fact.BIRTHDAY to Regex("""\bi was born on\s+(.+)"""),

        Fact.PHONE to possessive("number", "phone number", "mobile(?: number)?"),
        Fact.EMAIL to possessive("e-?mail(?: address)?"),

        Fact.HOME to Regex("""\bi live (?:in|at)\s+(.+)"""),
        Fact.HOME to possessive("address", "home"),
        Fact.WORK to Regex("""\bi work (?:at|for|in)\s+(.+)"""),
        Fact.WORK to possessive("job", "company", "workplace"),
    )

    /**
     * Where one statement ends and the next begins.
     *
     * People introduce themselves in a single breath — "my name is Pranay, my mother
     * is Rani" — so a captured value has to stop at the boundary rather than swallow
     * the rest of the sentence.
     */
    private val CLAUSE_BREAK = Regex("""\s*[,;]\s*|\s+and\s+|\s+but\s+|\s+my\s+""")

    /** Trailing politeness and filler that is not part of the value. */
    private val TAIL = Regex(
        """\s+(?:please|thanks|thank you|ok|okay|by the way|actually|right|yeah|yes)\b.*$"""
    )

    /**
     * Facts stated in one utterance. Usually zero; occasionally more than one, since
     * people introduce themselves in a single breath.
     */
    fun find(raw: String): List<Pair<Fact, String>> {
        val text = raw.trim().lowercase()
            .replace(Regex("""[!?]+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (text.isEmpty()) return emptyList()

        if (isQuestion(text)) return emptyList()

        val found = LinkedHashMap<Fact, String>()
        for ((fact, pattern) in PATTERNS) {
            if (found.containsKey(fact)) continue
            val captured = pattern.find(text)?.groupValues?.getOrNull(1) ?: continue
            val value = clean(captured, raw) ?: continue
            found[fact] = value
        }
        return found.toList()
    }

    private fun isQuestion(text: String): Boolean {
        if (text.contains('?')) return true
        val first = text.split(' ').firstOrNull().orEmpty()
        return first in QUESTION_OPENERS
    }

    /**
     * Tidy a captured value, and take the casing from the original.
     *
     * Names are the main thing stored here and lower-casing them would show "rani"
     * back to someone who said "Rani", which reads as the assistant not really having
     * listened.
     */
    private fun clean(captured: String, original: String): String? {
        var value = captured.replace(TAIL, "").trim().trim('.', ',', ';').trim()

        // A value ends where the next clause begins. Without this, "my name is
        // Pranay, my mother is Rani" records the name as everything after "is" —
        // which is both wrong and unfalsifiable, since it never looks empty.
        value = value.split(CLAUSE_BREAK).first().trim().trim(',', ';').trim()

        if (value.isBlank() || value.length > 60) return null
        // Two or three words is a name; a sentence is not.
        if (value.split(' ').size > 5) return null

        // Recover the original capitalisation for the same span.
        val index = original.lowercase().indexOf(value)
        return if (index >= 0) original.substring(index, index + value.length).trim() else value
    }
}
