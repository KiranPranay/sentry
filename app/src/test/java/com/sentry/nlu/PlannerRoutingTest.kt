package com.sentry.nlu

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The boundary between "do something" and "answer something".
 *
 * These go through [FastMatcher] rather than [Planner] because the planner's own
 * routing needs a live model; what is checked here is the property that matters —
 * that a question is never mistaken for a device command by the cheap path, and so
 * reaches the conversational one.
 */
class PlannerRoutingTest {

    @Test
    fun `questions are not device commands`() {
        // The regression this exists for: "what is the population there" was answered
        // with the battery level.
        val questions = listOf(
            "what is the population there",
            "what is the capital of france",
            "who wrote the book dune",
            "why is the sky blue",
            "how does a jet engine work",
            "where is the nearest star",
            "when did the war end",
            "explain photosynthesis",
        )
        for (question in questions) {
            assertNull(
                "\"$question\" must not fast-match a command",
                FastMatcher.match(question),
            )
        }
    }

    @Test
    fun `one word mishearings are not commands`() {
        // "call maa" came back as "karma", and the classifier labelled it
        // "flashlight". Nothing here may fast-match, and the planner refuses to act
        // on anything this short that did not.
        for (noise in listOf("karma", "come", "troop", "lucky", "dynasty", "alberta")) {
            assertNull("\"$noise\" must not be a command", FastMatcher.match(noise))
        }
    }

    @Test
    fun `device questions are still answered without a model`() {
        // These look like questions but have a real answer on the device, so they
        // must be caught before anything conversational happens.
        assertNull(FastMatcher.match("what is the population there"))
        assert(FastMatcher.match("what time is it") != null)
        assert(FastMatcher.match("what is the date") != null)
        assert(FastMatcher.match("how much battery do i have") != null)
    }
}
