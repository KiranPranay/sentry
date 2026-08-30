package com.sentry.nlu

import com.sentry.data.Fact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Noticing facts, and — more importantly — refusing to.
 *
 * A wrongly remembered fact is invisible until Sentry repeats it back, and poisons
 * every answer in between, so the refusals below matter more than the matches.
 */
class FactMatcherTest {

    private fun one(text: String) = FactMatcher.find(text).firstOrNull()

    @Test
    fun `learns the things people actually say about themselves`() {
        assertEquals(Fact.NAME to "Pranay Kiran", one("My name is Pranay Kiran"))
        assertEquals(Fact.NICKNAME to "Bannu", one("everyone call me Bannu"))
        assertEquals(Fact.MOTHER to "Rani", one("my mother's name is Rani"))
        assertEquals(Fact.MOTHER to "Rani", one("my mom is Rani"))
        assertEquals(Fact.FATHER to "Chinni", one("My father's name is Chinni"))
        assertEquals(Fact.BLOOD_GROUP to "B positive", one("my blood group is B positive"))
        assertEquals(Fact.PHONE to "9676504552", one("my number is 9676504552"))
    }

    @Test
    fun `the possessive without an apostrophe still works`() {
        // Speech recognisers do not emit apostrophes, so this is the form that
        // actually arrives — and it used to capture "name is Rani" as the value.
        assertEquals(Fact.MOTHER to "Rani", one("my mothers name is Rani"))
        assertEquals(Fact.FATHER to "Chinni", one("my fathers name is Chinni"))
        assertEquals(Fact.MOTHER to "Rani", one("my mother name is Rani"))
        assertEquals(Fact.NAME to "Pranay", one("my name is Pranay"))
    }

    @Test
    fun `Indian kinship words are understood`() {
        assertEquals(Fact.MOTHER to "Rani", one("my amma is Rani"))
        assertEquals(Fact.FATHER to "Chinni", one("my nanna is Chinni"))
    }

    @Test
    fun `a question is never a fact`() {
        // The most dangerous case: it contains every word the statement does.
        for (question in listOf(
            "what is my blood group",
            "what's my name",
            "do you know my mother's name",
            "tell me my father's name",
            "who is my mother",
            "remind me my number is what",
        )) {
            assertTrue("\"$question\" must not store a fact", FactMatcher.find(question).isEmpty())
        }
    }

    @Test
    fun `ordinary conversation stores nothing`() {
        for (chatter in listOf(
            "set an alarm for seven thirty",
            "call maa",
            "why is the sky blue",
            "turn on the flashlight",
            "the weather is nice today",
            "my head is hurting",
        )) {
            assertTrue("\"$chatter\" must not store a fact", FactMatcher.find(chatter).isEmpty())
        }
    }

    @Test
    fun `capitalisation survives`() {
        // Showing "rani" back to someone who said "Rani" reads as not having listened.
        assertEquals(Fact.MOTHER to "Rani", one("my mother is Rani"))
    }

    @Test
    fun `a second clause is not part of the value`() {
        assertEquals(Fact.NAME to "Pranay", one("my name is Pranay and I live in Hyderabad"))
    }

    @Test
    fun `a whole sentence is not a name`() {
        assertTrue(FactMatcher.find("my name is the one you already know very well").isEmpty())
    }

    @Test
    fun `several facts in one breath`() {
        val found = FactMatcher.find("my name is Pranay, my mother is Rani").toMap()
        assertEquals("Pranay", found[Fact.NAME])
        assertEquals("Rani", found[Fact.MOTHER])
    }

    @Test
    fun `being asked to remember is not being asked a question`() {
        // "remember" has to count as a question opener — "do you remember", "remember
        // when" — which meant the plainest way of stating a fact was the one shape
        // that was always discarded.
        assertEquals(
            listOf(Fact.SIBLING to "Divya"),
            FactMatcher.find("remember that my sister is Divya"),
        )
        assertEquals(
            listOf(Fact.SIBLING to "Divya"),
            FactMatcher.find("remember my sister is Divya"),
        )
        assertEquals(
            listOf(Fact.BLOOD_GROUP to "O negative"),
            FactMatcher.find("please note that my blood group is O negative"),
        )
    }

    @Test
    fun `a real question is still refused`() {
        assertTrue(FactMatcher.find("do you remember my name").isEmpty())
        assertTrue(FactMatcher.find("what is my sister's name").isEmpty())
        assertTrue(FactMatcher.find("remember when we went to Goa").isEmpty())
    }
}
