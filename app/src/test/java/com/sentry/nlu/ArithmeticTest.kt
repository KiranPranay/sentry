package com.sentry.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sums, and the far more important business of knowing when something is not one.
 *
 * A wrong number delivered confidently is the worst possible answer here, because
 * nothing about it looks wrong — which is exactly why this does not go near a model.
 */
class ArithmeticTest {

    private fun answer(text: String) = Arithmetic.evaluate(text)?.spoken

    @Test
    fun `the four operations`() {
        assertEquals("27", answer("what is 15 plus 12"))
        assertEquals("3", answer("what is 15 minus 12"))
        assertEquals("60", answer("what is 5 times 12"))
        assertEquals("25", answer("what is 100 divided by 4"))
    }

    @Test
    fun `spoken numbers`() {
        assertEquals("60", answer("what is five times twelve"))
        assertEquals("150", answer("what is fifty plus one hundred"))
    }

    @Test
    fun `multiplication binds tighter than addition`() {
        assertEquals("70", answer("2 plus 4 times 17"))
    }

    @Test
    fun `percentages`() {
        assertEquals("300", answer("what is 15 percent of 2000"))
        assertEquals("50", answer("what is 20% of 250"))
    }

    @Test
    fun `Indian scales`() {
        assertEquals("250000", answer("what is 2 lakh plus 50 thousand"))
        assertEquals("20000000", answer("what is 2 crore"))
    }

    @Test
    fun `a bare number is not a sum`() {
        // Answering "seven" with "7" is not useful, and would swallow real speech.
        assertNull(answer("seven"))
        assertNull(answer("what is 42"))
    }

    @Test
    fun `sentences that merely contain numbers are refused`() {
        assertNull(answer("set an alarm for 7"))
        assertNull(answer("call 9876543210"))
        assertNull(answer("what is my blood group"))
        assertNull(answer("play 3 doors down"))
        assertNull(answer("what is the meaning of life"))
    }

    @Test
    fun `division by zero is not an answer`() {
        assertNull(answer("what is 5 divided by 0"))
    }

    @Test
    fun `whole numbers do not read as decimals`() {
        assertEquals("60", answer("5 times 12"))
        assertEquals("2.5", answer("5 divided by 2"))
    }
}
