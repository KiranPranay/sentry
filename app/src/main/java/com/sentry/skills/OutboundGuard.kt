package com.sentry.skills

/**
 * Whether Sentry may do things other people can notice.
 *
 * Placing a call is the only skill whose blast radius extends past this phone: every
 * other action is undoable by the person holding it, and a call is not. During
 * development that asymmetry is easy to forget, right up until a test utterance dials
 * somebody's mother at two in the morning.
 *
 * Deliberately not a preference. It is not a feature the user chooses, it is a clamp
 * a test harness closes on itself, and it resets to open on every process start so no
 * one can be left with an assistant that has quietly stopped being able to call.
 */
class OutboundGuard {

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
