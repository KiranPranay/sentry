package com.sentry.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a single disambiguation pick is allowed to teach.
 *
 * These bindings are claimed without confirmation, from one tap, and then silently
 * steer every later contact lookup — so the refusals matter more than the
 * acceptances, and they are what most of this file tests.
 */
class NameRulesTest {

    @Test
    fun `the mishearing this feature exists for`() {
        // "maa" is not in the recogniser's lexicon; "karma" is the nearest thing
        // that is, and it comes back every single time.
        assertTrue(NameRules.bindable("karma", "Maa"))
        assertTrue(NameRules.bindable("come up", "Maa"))
        assertTrue(NameRules.bindable("my wife", "My life"))
    }

    @Test
    fun `a name the ranker already finds is not worth binding`() {
        // Exact, and the same once affectionate letter-stretching is squashed.
        assertFalse(NameRules.bindable("maa", "Maa"))
        assertFalse(NameRules.bindable("maa", "Maaaaaaa"))
        assertFalse(NameRules.bindable("Rani", "rani"))
        // A prefix of any word in the name: "sid" already reaches "Siddharth Rao".
        assertFalse(NameRules.bindable("sid", "Siddharth Rao"))
        assertFalse(NameRules.bindable("rao", "Siddharth Rao"))
    }

    @Test
    fun `ordinary words are never claimed as names`() {
        assertFalse(NameRules.bindable("the", "Maa"))
        assertFalse(NameRules.bindable("her", "Maa"))
        assertFalse(NameRules.bindable("that one", "Maa"))
        assertFalse(NameRules.bindable("call back", "Maa"))
    }

    @Test
    fun `a real name next to an ordinary word is still a name`() {
        // Only refused when *every* word is ordinary, otherwise "call ravi back"
        // could never teach anything.
        assertTrue(NameRules.bindable("the karma", "Maa"))
    }

    @Test
    fun `too short to be safe`() {
        assertFalse(NameRules.bindable("mm", "Maa"))
        assertFalse(NameRules.bindable("", "Maa"))
        assertFalse(NameRules.bindable("karma", ""))
    }

    @Test
    fun `punctuation and case do not create separate rules`() {
        assertEquals("karma", NameRules.normalise("  Karma!  "))
        assertEquals("my life", NameRules.normalise("My-Life"))
    }
}
