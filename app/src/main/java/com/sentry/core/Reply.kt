package com.sentry.core

/**
 * The outcome of running one command.
 *
 * [speech] is what Sentry says out loud and shows in the transcript. Keep it short:
 * this is a voice assistant, and a spoken paragraph is a worse answer than a spoken
 * sentence even when it contains more.
 */
data class Reply(
    val speech: String,
    /** A short badge shown next to the reply, e.g. "Alarm · 7:00 AM". */
    val chip: Chip? = null,
    /** Sentry asked something and should reopen the mic without a wake word. */
    val expectsAnswer: Boolean = false,
    /** Choices offered to the user, so "the second one" can be resolved next turn. */
    val choices: List<String> = emptyList(),
    val isError: Boolean = false,
) {
    companion object {
        fun error(message: String) = Reply(message, isError = true)

        fun ask(question: String, choices: List<String> = emptyList()) =
            Reply(question, expectsAnswer = true, choices = choices)
    }
}

/** A small labelled badge rendered with the reply. */
data class Chip(val icon: ChipIcon, val label: String)

enum class ChipIcon {
    ALARM, TIMER, CALL, MESSAGE, MUSIC, TORCH, VOLUME, APP, SEARCH, NAVIGATION,
    CAMERA, BATTERY, SETTINGS, CLOCK, DND,
}
