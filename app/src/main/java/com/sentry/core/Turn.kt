package com.sentry.core

/** One line in the transcript. */
data class Turn(
    val id: Long,
    val party: Party,
    val text: String,
    val chip: Chip? = null,
    val isError: Boolean = false,
    /** True while the model is still streaming into this turn. */
    val streaming: Boolean = false,
)

/** Who said it. Named [Party] rather than "Speaker" so it does not collide with
 *  [com.sentry.voice.Speaker], which is the thing that does the talking. */
enum class Party { USER, SENTRY }
