package com.sentry.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What may and may not be learned.
 *
 * A learned rule silently rewrites everything the user says, so the rules about what
 * is *refused* matter more than the ones about what is accepted. The acceptance logic
 * is mirrored here rather than called directly, because [PhraseBook] needs a Context.
 */
class PhraseBookRulesTest {

    private val common = setOf("what", "the", "is", "yes", "no", "ok", "hey", "who", "you")

    private fun learnable(heard: String, meant: String): Boolean {
        val key = heard.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex(" +"), " ").trim()
        val target = meant.trim()
        if (key.length < 3 || target.isBlank()) return false
        if (key == target.lowercase()) return false
        if (!key.contains(' ') && key in common) return false
        return true
    }

    @Test
    fun `a rare mishearing is learnable`() {
        // The case the whole feature exists for.
        assertTrue(learnable("karma", "call maa"))
        assertTrue(learnable("come up", "call maa"))
        assertTrue(learnable("cause harm", "call maa"))
    }

    @Test
    fun `a single very common word is refused`() {
        // Teaching "what is the date" really did produce "what" twice. Binding it
        // would turn every later sentence that came out as "what" into a date query.
        assertFalse(learnable("what", "what is the date"))
        assertFalse(learnable("the", "the flashlight"))
        assertFalse(learnable("yes", "call maa"))
    }

    @Test
    fun `a common word inside a longer phrase is fine`() {
        assertTrue(learnable("what does", "what is the date"))
        assertTrue(learnable("the flashlight", "turn on the flashlight"))
    }

    @Test
    fun `nothing is learned when it was already correct`() {
        assertFalse(learnable("call maa", "call maa"))
    }

    @Test
    fun `too short to be anything`() {
        assertFalse(learnable("a", "call maa"))
        assertFalse(learnable("", "call maa"))
    }
}
