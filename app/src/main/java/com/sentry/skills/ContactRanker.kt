package com.sentry.skills

/**
 * Deciding which contact a spoken name meant.
 *
 * Pure string work, deliberately separate from [Contacts] so it can be tested against
 * real address books rather than reasoned about. On a phone with two thousand
 * contacts this is what stands between "call maa" and a read-out list of everyone
 * whose name happens to contain those letters.
 */
/**
 * Who was found, and whether it is safe to act on without asking.
 *
 * Behaves as the list of matches everywhere a list was used before, and carries the
 * one extra bit the caller genuinely needs: a single match is not the same thing as
 * a sure match. "Call karma" finds exactly one contact — "Viswa Karma Industries" —
 * and dialling it is worse than asking, because the user meant their mother.
 */
class Lookup(
    private val matches: List<ContactMatch>,
    val certain: Boolean,
) : List<ContactMatch> by matches

object ContactRanker {

    /** How far ahead the top match must be to be taken without asking. */
    private const val CLEAR_WINNER = 1.4f

    private val NOTHING = Lookup(emptyList(), certain = false)

    /** Ranked candidates, best first. */
    fun rank(candidates: List<ContactMatch>, query: String): Lookup {
        val needle = normalise(query)
        if (needle.isEmpty()) return NOTHING

        val ranked = candidates
            .map { it to score(normalise(it.name), needle) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }

        if (ranked.isEmpty()) return NOTHING

        // A candidate far ahead of the rest is an answer, not a question. Asking
        // every time a short name matches several people is how an assistant becomes
        // slower than the dialler it replaced.
        val best = ranked[0]
        val runnerUp = ranked.getOrNull(1)
        if (runnerUp == null || best.second >= runnerUp.second * CLEAR_WINNER) {
            return Lookup(listOf(best.first), sure(normalise(best.first.name), needle))
        }

        return Lookup(ranked.take(5).map { it.first }, certain = false)
    }

    /**
     * Whether the best match is the one the user meant, or merely the closest thing
     * in the address book to a word the recogniser invented.
     *
     * Being alone in the results is no evidence at all — every wrong answer above is
     * also alone. What counts is whether the spoken words actually account for the
     * contact's name: said in full, said allowing for drawn-out spelling, said as the
     * start of it, or covering at least half of it. A word buried in the middle of a
     * longer name is a guess, and guesses get asked about rather than dialled.
     */
    private fun sure(name: String, needle: String): Boolean {
        if (name == needle) return true
        if (collapse(name) == collapse(needle)) return true
        if (name.startsWith(needle)) return true
        return covers(name, needle)
    }

    /** Whether the query explains at least half the words in the contact's name. */
    private fun covers(name: String, needle: String): Boolean {
        val nameWords = name.split(' ').filter { it.isNotBlank() }
        if (nameWords.isEmpty()) return false
        val needleWords = needle.split(' ').filter { it.isNotBlank() }
        val matched = nameWords.count { word ->
            needleWords.any { it == word || word.startsWith(it) || collapse(word) == collapse(it) }
        }
        return matched * 2 >= nameWords.size
    }

    private fun normalise(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() || it == ' ' }
            .replace(Regex(" +"), " ")
            .trim()

    /**
     * Squash runs of the same letter: "maaaaaaa" and "maa" both become "ma".
     *
     * People name contacts with drawn-out affection — "Maaaaaaa", "Ammmma",
     * "Dadddyyy" — and then say the short form out loud. Without this, "call maa"
     * scores "Maaaaaaa" barely above "Maanasa" and Sentry stops to ask which, every
     * single time. With it, the two collapse to the same string and the intended
     * contact wins outright.
     */
    private fun collapse(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            if (out.isEmpty() || out.last() != c) out.append(c)
        }
        return out.toString()
    }

    /**
     * How well a contact name answers the query. Higher is better, zero excludes.
     *
     * Prefix matches beat contained matches, and a match on the first name beats one
     * buried in a surname, because that is the order people actually mean.
     */
    private fun score(name: String, needle: String): Int {
        if (name == needle) return 1000
        // The same name once the affectionate letter-stretching is removed.
        if (collapse(name) == collapse(needle)) return 900
        if (name.startsWith(needle)) return 500 - name.length

        val nameWords = name.split(' ')
        val needleWords = needle.split(' ')

        var total = 0
        for (word in needleWords) {
            when {
                nameWords.any { it == word } -> total += 200
                nameWords.any { it.startsWith(word) } -> total += 120
                // A near-miss on spelling, which is the common recogniser failure.
                nameWords.any { editDistance(it, word) <= 1 && word.length >= 4 } -> total += 90
                name.contains(word) -> total += 40
                else -> total -= 30
            }
        }
        // Earlier words in a name matter more: "John" in "John Smith" beats "John"
        // in "Smith John".
        if (nameWords.firstOrNull()?.startsWith(needleWords.first()) == true) total += 60

        return total.coerceAtLeast(0)
    }

    /** Levenshtein, capped in practice by the short strings it runs on. */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
