package com.sentry.nlu

import com.sentry.core.Command
import com.sentry.core.MediaAction
import com.sentry.core.VolumeChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fast path decides what happens for most of what anyone says to Sentry, and it
 * does it without a model that might notice a mistake. So both halves are tested:
 * that the phrasings people use are matched, and — at least as important — that
 * ordinary sentences are *not*.
 */
class FastMatcherTest {

    // ------------------------------------------------------------------ alarms

    @Test
    fun `sets an alarm from an explicit time`() {
        assertEquals(Command.SetAlarm(7, 0), FastMatcher.match("set an alarm for 7 am"))
        assertEquals(Command.SetAlarm(19, 30), FastMatcher.match("set an alarm for 7:30 pm"))
        assertEquals(Command.SetAlarm(6, 15), FastMatcher.match("alarm at 6:15 am"))
    }

    @Test
    fun `wake me up is an alarm`() {
        assertEquals(Command.SetAlarm(6, 0), FastMatcher.match("wake me up at 6 am"))
    }

    @Test
    fun `an alarm phrased as a duration becomes a timer`() {
        // "set an alarm in 10 minutes" is a timer in everything but name, and setting
        // an alarm for 00:10 would be wrong in a way the user notices at 10 past midnight.
        assertEquals(Command.SetTimer(600), FastMatcher.match("set an alarm in 10 minutes"))
    }

    // ------------------------------------------------------------------ timers

    @Test
    fun `sets a timer`() {
        assertEquals(Command.SetTimer(300), FastMatcher.match("set a timer for 5 minutes"))
        assertEquals(Command.SetTimer(300), FastMatcher.match("timer for five minutes"))
        assertEquals(Command.SetTimer(90), FastMatcher.match("set a timer for 90 seconds"))
        assertEquals(Command.SetTimer(3600), FastMatcher.match("timer for 1 hour"))
    }

    @Test
    fun `sums a compound duration`() {
        assertEquals(Command.SetTimer(5400), FastMatcher.match("set a timer for 1 hour 30 minutes"))
    }

    @Test
    fun `handles a trailing timer phrasing`() {
        assertEquals(Command.SetTimer(600), FastMatcher.match("10 minute timer"))
    }

    // ------------------------------------------------------------------- calls

    @Test
    fun `calls a contact by name`() {
        assertEquals(Command.Call("mum"), FastMatcher.match("call mum"))
        assertEquals(Command.Call("john smith"), FastMatcher.match("ring john smith"))
    }

    @Test
    fun `calls a number`() {
        assertEquals(
            Command.CallNumber("9876543210"),
            FastMatcher.match("call 9876543210"),
        )
    }

    @Test
    fun `answering and hanging up`() {
        assertEquals(Command.AnswerCall, FastMatcher.match("answer the call"))
        assertEquals(Command.AnswerCall, FastMatcher.match("pick up"))
        assertEquals(Command.HangUp, FastMatcher.match("hang up"))
        assertEquals(Command.HangUp, FastMatcher.match("decline"))
    }

    @Test
    fun `call me back is not a command`() {
        // A false positive here dials a stranger, so this one matters.
        assertNull(FastMatcher.match("call me back later"))
    }

    // ------------------------------------------------------------------- torch

    @Test
    fun `torch on and off`() {
        assertEquals(Command.Torch(true), FastMatcher.match("turn on the flashlight"))
        assertEquals(Command.Torch(true), FastMatcher.match("torch"))
        assertEquals(Command.Torch(false), FastMatcher.match("turn off the flashlight"))
        assertEquals(Command.Torch(false), FastMatcher.match("flashlight off"))
    }

    // ------------------------------------------------------------------- media

    @Test
    fun `media transport controls`() {
        assertEquals(Command.MediaControl(MediaAction.NEXT), FastMatcher.match("next song"))
        assertEquals(Command.MediaControl(MediaAction.PAUSE), FastMatcher.match("pause"))
        assertEquals(
            Command.MediaControl(MediaAction.PREVIOUS),
            FastMatcher.match("previous track"),
        )
    }

    @Test
    fun `plays music`() {
        assertEquals(Command.PlayMusic(null), FastMatcher.match("play some music"))
        assertEquals(Command.PlayMusic("bohemian rhapsody"), FastMatcher.match("play bohemian rhapsody"))
        // The destination is not part of the search query.
        assertEquals(Command.PlayMusic("hello"), FastMatcher.match("play hello on spotify"))
    }

    // ------------------------------------------------------------------ volume

    @Test
    fun `volume commands`() {
        assertEquals(Command.Volume(VolumeChange.Up), FastMatcher.match("volume up"))
        assertEquals(Command.Volume(VolumeChange.Down), FastMatcher.match("turn it down"))
        assertEquals(Command.Volume(VolumeChange.Mute), FastMatcher.match("mute"))
        assertEquals(Command.Volume(VolumeChange.Max), FastMatcher.match("max volume"))
        assertEquals(
            Command.Volume(VolumeChange.Percent(50)),
            FastMatcher.match("set volume to 50"),
        )
    }

    // ------------------------------------------------------------------- misc

    @Test
    fun `opens apps`() {
        assertEquals(Command.OpenApp("whatsapp"), FastMatcher.match("open whatsapp"))
        assertEquals(Command.OpenApp("play store"), FastMatcher.match("launch play store"))
    }

    @Test
    fun `searches and navigates`() {
        assertEquals(Command.Search("tallest mountain"), FastMatcher.match("google tallest mountain"))
        assertEquals(Command.Navigate("the airport"), FastMatcher.match("navigate to the airport"))
    }

    @Test
    fun `device queries`() {
        assertEquals(Command.TimeQuery, FastMatcher.match("what time is it"))
        assertEquals(Command.DateQuery, FastMatcher.match("what day is it"))
        assertEquals(Command.BatteryStatus, FastMatcher.match("how much battery do i have"))
    }

    @Test
    fun `choices resolve to an index`() {
        assertEquals(Command.Choose(1), FastMatcher.match("the first one"))
        assertEquals(Command.Choose(2), FastMatcher.match("second"))
        assertEquals(Command.Choose(3), FastMatcher.match("number 3"))
    }

    @Test
    fun `strips the wake word and filler`() {
        assertEquals(Command.Torch(true), FastMatcher.match("hey sentry, turn on the torch"))
        assertEquals(Command.OpenCamera, FastMatcher.match("sentry please open camera"))
    }

    // --------------------------------------------------- things it must NOT match

    @Test
    fun `conversation falls through to the model`() {
        val notCommands = listOf(
            "who was the first person on the moon",
            "what do you think about that",
            "tell me a joke",
            "how are you",
            "why is the sky blue",
            "what's the capital of france",
            "i had a really long day today",
            "explain quantum computing",
        )
        for (utterance in notCommands) {
            assertNull("\"$utterance\" should not fast-match", FastMatcher.match(utterance))
        }
    }

    @Test
    fun `a long sentence starting with a command word is not an app launch`() {
        // "start" and "find" begin plenty of ordinary sentences.
        assertNull(FastMatcher.match("start thinking about what we discussed yesterday"))
    }

    @Test
    fun `stop words are recognised`() {
        assertEquals(Command.Stop, FastMatcher.match("stop"))
        assertEquals(Command.Stop, FastMatcher.match("never mind"))
    }

    @Test
    fun `blank input matches nothing`() {
        assertNull(FastMatcher.match(""))
        assertNull(FastMatcher.match("   "))
    }

    @Test
    fun `matching is fast enough to be free`() {
        // The entire premise of the fast path is that it costs nothing. If this ever
        // regresses into something expensive, the design argument goes with it.
        val utterances = listOf(
            "set an alarm for 7 am", "call mum", "turn on the flashlight",
            "who was the first person on the moon", "play some music",
        )
        // Warm up, so the measurement is not dominated by regex compilation.
        repeat(200) { utterances.forEach { FastMatcher.match(it) } }

        val started = System.nanoTime()
        repeat(1_000) { utterances.forEach { FastMatcher.match(it) } }
        val perMatchMicros = (System.nanoTime() - started) / 5_000.0 / 1_000.0

        assertTrue(
            "fast path took ${perMatchMicros}us per match, which is no longer fast",
            perMatchMicros < 500.0,
        )
    }
}
