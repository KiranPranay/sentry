package com.sentry.nlu

import com.sentry.core.Command
import com.sentry.core.LevelChange
import com.sentry.core.LevelTarget
import com.sentry.core.VolumeChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Volume and brightness, said the way they were actually said.
 *
 * Every utterance in [`from the session that prompted this`] is taken verbatim from a
 * logged conversation on the user's phone. Each one had a working skill behind it and
 * reached the wrong one, or none, because the patterns were a list of whole sentences
 * rather than a grammar: they accepted a verb or an amount, never both, and one
 * unexpected filler word between the verb and the noun was enough to lose the command.
 *
 * The refusals at the bottom are the load-bearing half. The safety property here is
 * structural — nothing matches without a domain noun — so these assert the structure
 * rather than a threshold.
 */
class LevelsTest {

    private fun match(text: String) = FastMatcher.match(text)

    @Test
    fun `from the session that prompted this`() {
        assertEquals(
            Command.Volume(VolumeChange.Percent(50)),
            match("lower the volume to fifty percent"),
        )
        assertEquals(
            Command.Volume(VolumeChange.By(-50)),
            match("lower the volume by fifty percent"),
        )
        assertEquals(Command.Brightness(LevelChange.Up), match("increase the device brightness"))
        assertEquals(Command.BrightnessQuery, match("the device brightness"))
        assertEquals(
            Command.Brightness(LevelChange.By(-50)),
            match("lower the brightness by fifty percent"),
        )
        assertEquals(Command.Silent(on = true), match("keep the device in silent"))
    }

    @Test
    fun `a spoken percentage has the word percent in it`() {
        // Arithmetic.number refuses any word it does not know, so it returned null for
        // the whole of "fifty percent" — and every spoken percentage in the system
        // failed. The tests appeared to cover this because they said "fifty", and
        // nobody says "fifty".
        assertEquals(Command.Volume(VolumeChange.Percent(50)), match("set the volume to fifty percent"))
        assertEquals(Command.Volume(VolumeChange.Percent(50)), match("set the volume to 50 percent"))
        assertEquals(Command.Brightness(LevelChange.Percent(30)), match("brightness 30 percent"))
    }

    @Test
    fun `to is a destination and by is a distance`() {
        assertEquals(Command.Volume(VolumeChange.Percent(20)), match("set the volume to 20 percent"))
        assertEquals(Command.Volume(VolumeChange.By(-20)), match("lower the volume by 20 percent"))
        assertEquals(Command.Volume(VolumeChange.By(20)), match("raise the volume by 20 percent"))
        assertEquals(Command.Brightness(LevelChange.By(10)), match("increase the brightness by 10 percent"))
    }

    @Test
    fun `filler between the verb and the noun does not lose the command`() {
        listOf(
            "increase the device brightness", "increase the phone brightness",
            "please increase the brightness", "turn up the device volume",
            "just lower the volume please", "set the device volume to 40 percent",
        ).forEach { assertEquals(it, true, match(it) != null) }
    }

    @Test
    fun `reading a level back is not setting it`() {
        assertEquals(Command.VolumeQuery, match("the volume"))
        assertEquals(Command.VolumeQuery, match("what is the volume"))
        assertEquals(Command.VolumeQuery, match("what's the volume"))
        assertEquals(Command.BrightnessQuery, match("the brightness"))
        assertEquals(Command.BrightnessQuery, match("what is the screen brightness"))
        assertEquals(Command.BrightnessQuery, match("the device brightness"))
    }

    @Test
    fun `the pronoun never outranks a verb that names its own domain`() {
        // "Make it brighter" put the pronoun first, and taking it at face value made
        // a brightness request into a volume one.
        assertEquals(Command.Brightness(LevelChange.Up), match("make it brighter"))
        assertEquals(Command.Brightness(LevelChange.Down), match("make it dimmer"))
        assertEquals(Command.Volume(VolumeChange.Up), match("make it louder"))
        assertEquals(Command.Volume(VolumeChange.Up), match("turn it up"))
    }

    @Test
    fun `nothing matches without naming what to change`() {
        // The whole safety argument. However many verbs and amounts the tables hold,
        // a sentence that never says which thing cannot become a command.
        listOf(
            "up", "down", "off", "on", "full", "higher", "lower", "maximum",
            "fifty percent", "to fifty percent", "by half", "it", "turn it",
            "increase", "reduce", "set it to fifty", "silent",
        ).forEach { assertNull(it, match(it)) }
    }

    @Test
    fun `a noun in ordinary speech is not a command`() {
        listOf(
            "high volume", "low volume", "trading volume", "blood volume",
            "the volume of the box", "a large volume of water",
            "the movie was silent", "the screen is cracked", "my screen is broken",
            "the display is fine", "check the volume of the trade",
            "turn the page", "the sound of music",
        ).forEach { assertNull(it, match(it)) }
    }

    @Test
    fun `a destroyed verb becomes a question, not a guess`() {
        // The recogniser keeps the noun and loses the verb. Every phonetic code rates
        // "jellyfish" closer to "decrease" than to "increase", and one step from
        // "max", so the word is carried into the question rather than scored.
        assertEquals(
            Command.WhichWay(LevelTarget.SOUND, "jellyfish"),
            match("jellyfish the volume"),
        )
        assertEquals(
            Command.WhichWay(LevelTarget.SOUND, "jewish"),
            match("the jewish the volume percent"),
        )
        assertEquals(
            Command.WhichWay(LevelTarget.SCREEN, "jellyfish"),
            match("jellyfish the brightness"),
        )
    }

    @Test
    fun `a contact name is not a mangled verb`() {
        // "Prakash the volume" parses exactly like "jellyfish the volume" — one word
        // the grammar cannot place, in front of a level noun. Nothing about the
        // string distinguishes them, so the address book gets a veto.
        try {
            Levels.knownName = { it == "prakash" }
            assertNull(FastMatcher.match("prakash the volume"))
            // Still asks about a word that is nobody's name.
            assertEquals(
                Command.WhichWay(LevelTarget.SOUND, "jellyfish"),
                FastMatcher.match("jellyfish the volume"),
            )
        } finally {
            Levels.knownName = null
        }
    }

    @Test
    fun `a word the user has already answered for is not asked about twice`() {
        try {
            Levels.learnedVerb = { _, word -> if (word == "jellyfish") "increase" else null }
            assertEquals(Command.Volume(VolumeChange.Up), FastMatcher.match("jellyfish the volume"))
            assertEquals(
                Command.Brightness(LevelChange.Up),
                FastMatcher.match("jellyfish the brightness"),
            )
        } finally {
            Levels.learnedVerb = null
        }
    }

    @Test
    fun `an inflected verb is still that verb`() {
        // "Increase the device brightness" arrived as this, and both words were
        // unplaceable: one carried an ending, the other was decoder noise.
        assertEquals(
            Command.Brightness(LevelChange.Up),
            match("increased devised the brightness"),
        )
        assertEquals(Command.Volume(VolumeChange.Down), match("lowered the volume"))
    }

    @Test
    fun `a noun phrase is never mistaken for a mangled command`() {
        // These name a level and one other word, which is the same shape as a
        // destroyed verb. Sentry must not ask "up or down?" at someone discussing the
        // stock market.
        listOf(
            "high volume", "low volume", "trading volume", "blood volume",
            "the trading volume", "the total volume", "a large volume",
            "the average volume",
        ).forEach {
            val m = match(it)
            if (m != null) throw AssertionError("\"$it\" should not match, was $m")
        }
    }
}
