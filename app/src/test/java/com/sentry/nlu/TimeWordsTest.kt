package com.sentry.nlu

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * Clock parsing, which is the part of the fast path most able to be confidently wrong.
 * An alarm set twelve hours out is worse than one that failed to be set at all.
 */
class TimeWordsTest {

    private fun at(hour: Int, minute: Int = 0): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }

    @Test
    fun `explicit meridiem is obeyed`() {
        assertEquals(TimeWords.Clock(7, 0), TimeWords.clock("7 am", at(21)))
        assertEquals(TimeWords.Clock(19, 30), TimeWords.clock("7:30 pm", at(9)))
        assertEquals(TimeWords.Clock(0, 15), TimeWords.clock("12:15 am", at(9)))
        assertEquals(TimeWords.Clock(12, 15), TimeWords.clock("12:15 pm", at(9)))
    }

    @Test
    fun `spoken parts of the day count as a meridiem`() {
        // The bug this test exists for: at 9pm, "seven thirty in the morning" was
        // being read as 19:30 — the one time it definitely did not mean.
        assertEquals(TimeWords.Clock(7, 30), TimeWords.clock("seven thirty in the morning", at(21)))
        assertEquals(TimeWords.Clock(7, 0), TimeWords.clock("7 in the morning", at(21)))
        assertEquals(TimeWords.Clock(19, 0), TimeWords.clock("7 in the evening", at(9)))
        assertEquals(TimeWords.Clock(20, 30), TimeWords.clock("8:30 tonight", at(9)))
    }

    @Test
    fun `without a meridiem it picks the next occurrence`() {
        // 7:30 is still ahead this morning.
        assertEquals(TimeWords.Clock(7, 30), TimeWords.clock("7:30", at(6)))
        // 7:30am has gone, 7:30pm has not.
        assertEquals(TimeWords.Clock(19, 30), TimeWords.clock("7:30", at(9)))
        // Both have gone, so it means tomorrow morning — not twelve hours ago.
        assertEquals(TimeWords.Clock(7, 30), TimeWords.clock("7:30", at(21)))
    }

    @Test
    fun `24 hour times are taken literally`() {
        assertEquals(TimeWords.Clock(18, 45), TimeWords.clock("18:45", at(9)))
    }

    @Test
    fun `past and to`() {
        assertEquals(TimeWords.Clock(7, 15), TimeWords.clock("quarter past seven am", at(6)))
        assertEquals(TimeWords.Clock(7, 30), TimeWords.clock("half past seven am", at(6)))
        assertEquals(TimeWords.Clock(7, 50), TimeWords.clock("ten to eight am", at(6)))
    }

    @Test
    fun `durations`() {
        assertEquals(300, TimeWords.duration("5 minutes"))
        assertEquals(300, TimeWords.duration("five minutes"))
        assertEquals(90, TimeWords.duration("90 seconds"))
        assertEquals(5400, TimeWords.duration("1 hour 30 minutes"))
        assertEquals(1800, TimeWords.duration("half an hour"))
        assertEquals(null, TimeWords.duration("the moon"))
    }

    @Test
    fun `spoken numbers`() {
        assertEquals(25, TimeWords.number("twenty five"))
        assertEquals(25, TimeWords.number("twenty-five"))
        assertEquals(7, TimeWords.number("7"))
        assertEquals(null, TimeWords.number("banana"))
    }
}
