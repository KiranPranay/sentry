package com.sentry.skills

/**
 * Whether Sentry may do things that other people notice.
 *
 * Most skills are undoable by whoever is holding the phone. A handful are not: a call
 * rings somebody else's phone, opening a chat marks it read, and an alarm or a timer
 * fills the room with noise on a delay, long after the command that caused it has
 * been forgotten. During development that distinction is easy to lose track of.
 *
 * It was lost twice in one night. Four calls went out to somebody's mother, two of
 * them after midnight, because "call maa" is the obvious thing to say when testing
 * whether "call maa" works. Then a test of sentence-stitching — two fragments, "set a
 * timer for one minute" and "and thirty seconds" — did exactly what it was supposed
 * to and set a real ninety-second timer, which rang.
 *
 * The first mistake produced a guard that covered calls. The second happened anyway,
 * because the guard covered the actions that had already gone wrong rather than the
 * ones that could. It covers everything with a blast radius now: reaching a person,
 * and making a noise.
 *
 * Deliberately not a preference, and deliberately not persisted. It is not a feature
 * the user chooses, it is a clamp a test harness closes on itself, and it opens again
 * on every process start so nobody is left holding an assistant that has quietly
 * stopped being able to set an alarm.
 */
class Disturbances {

    @Volatile
    var blocked: Boolean = false
        private set

    fun block() {
        blocked = true
    }

    fun allow() {
        blocked = false
    }
}
