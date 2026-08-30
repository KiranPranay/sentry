package com.sentry.nlu

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Spoken sums, worked out without a model.
 *
 * "What is fifteen percent of two thousand" is arithmetic, and arithmetic is the last
 * thing that should be handed to a language model: a 0.5B will answer confidently and
 * be wrong, and the user has no way to tell. Two hundred lines of parsing is a
 * better trade than a plausible wrong number.
 *
 * Deliberately narrow. It handles the four operations, percentages and a few spoken
 * forms; anything it is not sure of returns null and falls through to conversation,
 * which is the honest outcome for "what is the meaning of life times two".
 */
object Arithmetic {

    /** A sum and its answer, formatted for speech. */
    data class Result(val expression: String, val value: Double) {

        /**
         * Trailing ".0" on a whole number reads as a machine talking. Beyond a couple
         * of decimals nobody is listening either, so the answer is rounded to
         * something a person would actually say.
         */
        val spoken: String
            get() {
                if (!value.isFinite()) return "undefined"
                val rounded = (value * 100).roundToLong() / 100.0
                return if (abs(rounded - rounded.roundToLong()) < 1e-9) {
                    rounded.roundToLong().toString()
                } else {
                    rounded.toString()
                }
            }
    }

    private val WORDS = mapOf(
        "zero" to 0.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0,
        "five" to 5.0, "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0,
        "ten" to 10.0, "eleven" to 11.0, "twelve" to 12.0, "thirteen" to 13.0,
        "fourteen" to 14.0, "fifteen" to 15.0, "sixteen" to 16.0, "seventeen" to 17.0,
        "eighteen" to 18.0, "nineteen" to 19.0, "twenty" to 20.0, "thirty" to 30.0,
        "forty" to 40.0, "fifty" to 50.0, "sixty" to 60.0, "seventy" to 70.0,
        "eighty" to 80.0, "ninety" to 90.0,
    )

    private val SCALES = mapOf(
        "hundred" to 100.0, "thousand" to 1_000.0, "lakh" to 100_000.0,
        "million" to 1_000_000.0, "crore" to 10_000_000.0, "billion" to 1_000_000_000.0,
    )

    /** Openers that mean "do this sum", stripped before parsing. */
    private val LEAD = Regex(
        """^(?:hey |ok )?(?:sentry[,\s]+)?(?:what(?:'s| is)|whats|how much is|calculate|compute|work out|tell me)\s+"""
    )

    private val OPERATORS = mapOf(
        "plus" to "+", "add" to "+", "and" to "+",
        "minus" to "-", "subtract" to "-", "less" to "-",
        "times" to "*", "multiplied" to "*", "multiply" to "*", "into" to "*",
        "divided" to "/", "divide" to "/", "over" to "/",
    )

    /**
     * Work out a spoken sum, or return null if this was not one.
     *
     * Null is the common case and the important one: most sentences containing a
     * number are not arithmetic, and answering them with a number would be worse than
     * not answering at all.
     */
    fun evaluate(raw: String): Result? {
        var text = raw.trim().lowercase().removeSuffix("?").trim()
        text = text.replace(LEAD, "").trim()
        if (text.isEmpty()) return null

        // "20% of 400" and "20 percent of 400" are the same sum.
        val percentOf = Regex("""^(.+?)\s*(?:%|percent|per cent)\s+of\s+(.+)$""").find(text)
        if (percentOf != null) {
            val part = number(percentOf.groupValues[1]) ?: return null
            val whole = number(percentOf.groupValues[2]) ?: return null
            return Result("${percentOf.groupValues[1]}% of ${percentOf.groupValues[2]}",
                whole * part / 100.0)
        }

        val tokens = tokenise(text) ?: return null

        // A bare number is not a sum — answering "seven" with "7" is not useful, and
        // treating every number as a question would swallow "set an alarm for 7".
        // The exception is a scale word: "what is two crore" is genuinely asking to
        // have it written out, and nothing else phrases itself that way.
        if (tokens.none { it in setOf("+", "-", "*", "/") }) {
            val namesAScale = SCALES.keys.any { text.contains(it) }
            if (!namesAScale) return null
        }

        val value = evaluateTokens(tokens) ?: return null
        return Result(text, value)
    }

    /**
     * Split into numbers and operators, or null if anything unrecognised appears.
     *
     * Strict on purpose: an unknown word means this was a sentence that happened to
     * contain numbers, not a sum.
     */
    private fun tokenise(text: String): List<String>? {
        val cleaned = text.replace(Regex("""[,]"""), "")
            .replace("x", " * ")
            .replace(Regex("""([+\-*/×÷])"""), " $1 ")
            .replace("×", "*")
            .replace("÷", "/")
        val words = cleaned.split(Regex("""\s+""")).filter { it.isNotBlank() }

        val tokens = mutableListOf<String>()
        val pending = mutableListOf<String>()

        fun flush(): Boolean {
            if (pending.isEmpty()) return true
            val value = number(pending.joinToString(" ")) ?: return false
            tokens.add(value.toString())
            pending.clear()
            return true
        }

        for (word in words) {
            when {
                word == "by" || word == "of" -> Unit  // "divided by", "multiplied by"
                word in setOf("+", "-", "*", "/") -> {
                    if (!flush()) return null
                    tokens.add(word)
                }
                OPERATORS.containsKey(word) -> {
                    // "and" is only an operator between numbers; "one hundred and two"
                    // is a single number, so it joins the pending run instead.
                    if (word == "and" && pending.isNotEmpty()) {
                        pending.add(word)
                    } else {
                        if (!flush()) return null
                        tokens.add(OPERATORS.getValue(word))
                    }
                }
                else -> pending.add(word)
            }
        }
        if (!flush()) return null
        return tokens.takeIf { it.isNotEmpty() }
    }

    /** A number written in digits or words, including Indian scales. */
    fun number(text: String): Double? {
        val cleaned = text.trim().replace(",", "").replace("-", " ")
        if (cleaned.isEmpty()) return null
        cleaned.toDoubleOrNull()?.let { return it }

        var total = 0.0
        var current = 0.0
        var matched = false

        for (word in cleaned.split(Regex("""\s+"""))) {
            if (word.isBlank() || word == "and") continue
            val digits = word.toDoubleOrNull()
            when {
                digits != null -> {
                    current += digits; matched = true
                }
                WORDS.containsKey(word) -> {
                    current += WORDS.getValue(word); matched = true
                }
                SCALES.containsKey(word) -> {
                    val scale = SCALES.getValue(word)
                    // "two thousand" scales what came before; "thousand" alone is 1000.
                    if (current == 0.0) current = 1.0
                    if (scale >= 1000) {
                        total += current * scale
                        current = 0.0
                    } else {
                        current *= scale
                    }
                    matched = true
                }
                else -> return null
            }
        }
        return if (matched) total + current else null
    }

    /** Left to right, with multiplication and division binding tighter. */
    private fun evaluateTokens(tokens: List<String>): Double? {
        val values = mutableListOf<Double>()
        val ops = mutableListOf<String>()

        fun apply(): Boolean {
            val op = ops.removeLastOrNull() ?: return false
            val right = values.removeLastOrNull() ?: return false
            val left = values.removeLastOrNull() ?: return false
            val result = when (op) {
                "+" -> left + right
                "-" -> left - right
                "*" -> left * right
                "/" -> if (right == 0.0) return false else left / right
                else -> return false
            }
            values.add(result)
            return true
        }

        for (token in tokens) {
            when (token) {
                "+", "-", "*", "/" -> {
                    while (ops.isNotEmpty() && precedence(ops.last()) >= precedence(token)) {
                        if (!apply()) return null
                    }
                    ops.add(token)
                }
                else -> values.add(token.toDoubleOrNull() ?: return null)
            }
        }
        while (ops.isNotEmpty()) if (!apply()) return null
        return values.singleOrNull()
    }

    private fun precedence(op: String) = if (op == "*" || op == "/") 2 else 1
}
