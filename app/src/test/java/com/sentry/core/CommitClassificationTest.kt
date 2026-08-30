package com.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How costly each command is to get wrong.
 *
 * This classification decides which commands wait for the rest of a sentence before
 * they run. Erring on the cautious side merely adds latency; erring the other way
 * dials somebody on a half-heard fragment, so the dangerous set is pinned explicitly
 * rather than left to a reviewer's memory.
 */
class CommitClassificationTest {

    @Test
    fun `anything that reaches a person cannot be taken back`() {
        assertEquals(Commit.IRREVERSIBLE, Command.Call("amma").commit)
        assertEquals(Commit.IRREVERSIBLE, Command.CallNumber("9030004575").commit)
        assertEquals(Commit.IRREVERSIBLE, Command.SendMessage("ravi", "hi").commit)
        assertEquals(Commit.IRREVERSIBLE, Command.AnswerCall.commit)
        assertEquals(Commit.IRREVERSIBLE, Command.HangUp.commit)
    }

    @Test
    fun `alarms and timers cannot be taken back either`() {
        // Set with EXTRA_SKIP_UI, and no delete intent exists, so a corrected
        // instruction would leave the wrong one behind for good.
        assertEquals(Commit.IRREVERSIBLE, Command.SetAlarm(7, 30).commit)
        assertEquals(Commit.IRREVERSIBLE, Command.SetTimer(300).commit)
    }

    @Test
    fun `steps are not destinations`() {
        // Running these twice moves twice as far, so they must never be re-applied.
        assertEquals(Commit.RELATIVE, Command.Volume(VolumeChange.Up).commit)
        assertEquals(Commit.RELATIVE, Command.Volume(VolumeChange.Down).commit)
        assertEquals(Commit.RELATIVE, Command.MediaControl(MediaAction.NEXT).commit)
        assertEquals(Commit.RELATIVE, Command.MediaControl(MediaAction.PREVIOUS).commit)
    }

    @Test
    fun `absolute settings are safe to repeat`() {
        assertEquals(Commit.IDEMPOTENT, Command.Volume(VolumeChange.Mute).commit)
        assertEquals(Commit.IDEMPOTENT, Command.Volume(VolumeChange.Percent(50)).commit)
        assertEquals(Commit.IDEMPOTENT, Command.MediaControl(MediaAction.PAUSE).commit)
        assertEquals(Commit.IDEMPOTENT, Command.Torch(true).commit)
        assertEquals(Commit.IDEMPOTENT, Command.Dnd(true).commit)
    }

    @Test
    fun `questions touch nothing and never wait`() {
        assertEquals(Commit.PURE, Command.TimeQuery.commit)
        assertEquals(Commit.PURE, Command.DateQuery.commit)
        assertEquals(Commit.PURE, Command.BatteryStatus.commit)
        assertEquals(Commit.PURE, Command.Chat("why is the sky blue").commit)
    }

    @Test
    fun `one-way session state counts as irreversible`() {
        // Neither dials anyone, but Choose consumes the pending disambiguation list
        // and Stop ends the session; a fragment must not spend either.
        assertEquals(Commit.IRREVERSIBLE, Command.Choose(1).commit)
        assertEquals(Commit.IRREVERSIBLE, Command.Stop.commit)
    }
}
