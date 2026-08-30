package com.sentry.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ranking against a real address book.
 *
 * These names are taken verbatim from the phone this was built for, because that is
 * where the interesting cases live: two thousand contacts, affectionate spellings,
 * emoji, and several people whose names share a prefix with the one meant.
 */
class ContactRankerTest {

    private fun contacts(vararg names: String) =
        names.mapIndexed { index, name -> ContactMatch(name, "+9100000000$index") }

    @Test
    fun `a stretched name is found by its short form`() {
        // The case this exists for. "call maa" has to reach "Maaaaaaa" and not stop
        // to ask about Maanasa, Maaya and Pedhammmmaaaaaaaaa.
        val all = contacts(
            "Maanasa A It B.Clg",
            "Narendhra Maaya",
            "Pedhammmmaaaaaaaaa",
            "Maaaaaaa 💜💞🤍",
        )
        val result = ContactRanker.rank(all, "maa")

        assertEquals("should not have asked which one", 1, result.size)
        assertEquals("Maaaaaaa 💜💞🤍", result[0].name)
    }

    @Test
    fun `the short form the recogniser actually produces still works`() {
        // Contact names are not in the acoustic model's vocabulary, so "maa" comes
        // back as whatever English word fits — usually "ma". Both have to land on
        // the same person.
        val all = contacts(
            "Maanasa A It B.Clg",
            "Narendhra Maaya",
            "Pedhammmmaaaaaaaaa",
            "Maaaaaaa 💜💞🤍",
        )
        for (heard in listOf("ma", "maa", "maaa")) {
            val result = ContactRanker.rank(all, heard)
            assertEquals("\"$heard\" should resolve outright", 1, result.size)
            assertEquals("Maaaaaaa 💜💞🤍", result[0].name)
        }
    }

    @Test
    fun `emoji and punctuation do not affect matching`() {
        val result = ContactRanker.rank(contacts("Maaaaaaa 💜💞🤍"), "maa")
        assertEquals(1, result.size)
    }

    @Test
    fun `an exact name wins outright`() {
        val result = ContactRanker.rank(contacts("Ravi", "Ravi Kumar", "Ravindra"), "ravi")
        assertEquals(1, result.size)
        assertEquals("Ravi", result[0].name)
    }

    @Test
    fun `genuinely ambiguous names are still offered as a list`() {
        // Two people with the same first name is a real question, and guessing would
        // be worse than asking.
        val result = ContactRanker.rank(contacts("Kumar Reddy", "Kumar Swamy"), "kumar")
        assertTrue("expected a choice, got ${result.map { it.name }}", result.size > 1)
    }

    @Test
    fun `a misheard spelling still finds the contact`() {
        val result = ContactRanker.rank(contacts("Siddharth", "Priya"), "sidharth")
        assertEquals("Siddharth", result[0].name)
    }

    @Test
    fun `a number label is spoken only when it disambiguates`() {
        // "Maa" alone reads better than "Maa, Mobile" when there is only one number.
        assertEquals("Maa", ContactMatch("Maa", "+919030004575").spoken)
        assertEquals("Maa, Mobile", ContactMatch("Maa", "+919030004575", "Mobile").spoken)
    }

    @Test
    fun `nothing relevant returns nothing`() {
        assertTrue(ContactRanker.rank(contacts("Ravi", "Priya"), "zebediah").isEmpty())
    }

    @Test
    fun `a blank query matches nobody`() {
        assertTrue(ContactRanker.rank(contacts("Ravi"), "").isEmpty())
        assertTrue(ContactRanker.rank(contacts("Ravi"), "   ").isEmpty())
    }
}
