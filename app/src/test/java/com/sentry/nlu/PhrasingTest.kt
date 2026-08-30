package com.sentry.nlu

import com.sentry.core.Command
import com.sentry.core.LevelChange
import com.sentry.core.VolumeChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The same request, said the many ways people say it.
 *
 * These all used to be matched against fixed lists of exact phrases, so "show my
 * alarms" worked and "show me my alarms" did not — one word longer, and it fell
 * through to the language model to be answered as conversation. A phrasebook is not
 * an understanding, and the failures were invisible: the assistant replied to every
 * one of them, just not with the thing that was asked for.
 *
 * The refusals at the bottom matter as much. Widening a pattern until it matches
 * everything is not an improvement, and "up" is a word people say.
 */
class PhrasingTest {

    private fun match(text: String) = FastMatcher.match(text)

    @Test
    fun `asking about the battery`() {
        val ways = listOf(
            "battery", "battery level", "battery percentage", "battery status",
            "what is the battery", "what's my battery", "hows my battery",
            "how much battery", "how much charge is left", "check the battery",
            "what is my battery level", "the battery",
        )
        ways.forEach { assertEquals(it, Command.BatteryStatus, match(it)) }
    }

    @Test
    fun `asking to see alarms and timers`() {
        listOf(
            "show me my alarms", "show my alarms", "list my alarms", "my alarms",
            "check my alarms", "what alarms do i have", "alarms",
        ).forEach { assertEquals(it, Command.ShowAlarms, match(it)) }

        listOf(
            "show me my timers", "list timers", "my timers", "check the timers",
            "what timers are running", "timers",
        ).forEach { assertEquals(it, Command.ShowTimers, match(it)) }
    }

    @Test
    fun `setting the volume to a number`() {
        listOf("set the volume to 5", "set volume to 5", "volume 5", "volume to 5")
            .forEach { assertEquals(it, Command.Volume(VolumeChange.Percent(5)), match(it)) }

        // Said out loud, a number is a word.
        assertEquals(Command.Volume(VolumeChange.Percent(50)), match("set the volume to fifty"))
    }

    @Test
    fun `turning the sound up and down`() {
        listOf("volume up", "louder", "make it louder", "turn it up", "turn up the volume",
            "turn the volume up", "raise the volume", "a bit louder")
            .forEach { assertEquals(it, Command.Volume(VolumeChange.Up), match(it)) }

        listOf("volume down", "quieter", "make it quieter", "turn it down",
            "turn down the sound", "turn the volume down", "lower the volume")
            .forEach { assertEquals(it, Command.Volume(VolumeChange.Down), match(it)) }
    }

    @Test
    fun `muting`() {
        listOf("mute", "mute it", "mute the phone", "silence the phone", "volume off",
            "turn off the sound", "no sound")
            .forEach { assertEquals(it, Command.Volume(VolumeChange.Mute), match(it)) }
    }

    @Test
    fun `be quiet means stop talking, not mute the phone`() {
        // Said to an assistant that is mid-sentence, "be quiet" is about the
        // assistant. Muting the ringer instead would be a strange thing to do.
        assertEquals(Command.Stop, match("be quiet"))
        assertEquals(Command.Stop, match("shut up"))
    }

    @Test
    fun `ordinary words are not commands`() {
        // Each of these is a word or phrase someone says in conversation. Matching
        // any of them would mean the assistant acts while being talked to.
        listOf(
            "up", "down", "higher", "lower", "off", "full",
            "how much battery does a tesla have", "the alarm went off",
            "set an alarm for seven", "set a timer for five minutes",
            "turn it", "turn off the lights", "how much is it",
        ).forEach { text ->
            val matched = match(text)
            val volumeOrList = matched is Command.Volume ||
                matched == Command.ShowAlarms || matched == Command.ShowTimers ||
                matched == Command.BatteryStatus
            if (volumeOrList) throw AssertionError("\"$text\" should not be $matched")
        }
    }

    @Test
    fun `a bare number is still not a sum`() {
        assertNull(match("seven"))
        assertNull(match("42"))
    }

    @Test
    fun `cancelling a timer or an alarm`() {
        listOf("cancel the timer", "stop the timer", "cancel timer", "delete the timer",
            "turn off the timer", "dismiss the timer", "stop my timer")
            .forEach { assertEquals(it, Command.CancelTimer, match(it)) }

        listOf("cancel the alarm", "stop the alarm", "turn off the alarm",
            "dismiss the alarm", "delete my alarm")
            .forEach { assertEquals(it, Command.CancelAlarm, match(it)) }
    }

    @Test
    fun `cancelling is not the same as setting or listing`() {
        assertEquals(Command.ShowTimers, match("show my timers"))
        assertEquals(Command.ShowAlarms, match("show my alarms"))
        // "Stop" on its own still ends the conversation rather than a countdown.
        assertEquals(Command.Stop, match("stop"))
    }

    @Test
    fun `changing the screen brightness`() {
        listOf("increase the brightness", "brightness up", "brighter", "make it brighter",
            "turn up the brightness", "brighten the screen", "raise the brightness")
            .forEach { assertEquals(it, Command.Brightness(LevelChange.Up), match(it)) }

        listOf("decrease the brightness", "brightness down", "dimmer", "dim the screen",
            "lower the brightness", "turn down the brightness")
            .forEach { assertEquals(it, Command.Brightness(LevelChange.Down), match(it)) }

        assertEquals(Command.Brightness(LevelChange.Max), match("max brightness"))
        assertEquals(Command.Brightness(LevelChange.Min), match("minimum brightness"))
        assertEquals(
            Command.Brightness(LevelChange.Percent(30)),
            match("set the brightness to 30"),
        )
        assertEquals(
            Command.Brightness(LevelChange.Percent(50)),
            match("set brightness to fifty"),
        )
    }

    @Test
    fun `brightness and volume do not shadow each other`() {
        // Both end in "up" and "down" and both accept a bare number, so the words
        // that distinguish them have to actually be required.
        assertEquals(Command.Volume(VolumeChange.Up), match("turn it up"))
        assertEquals(Command.Brightness(LevelChange.Up), match("turn the screen up"))
        assertEquals(Command.Volume(VolumeChange.Percent(30)), match("set the volume to 30"))
        assertEquals(Command.Brightness(LevelChange.Percent(30)), match("set the brightness to 30"))
    }
}
