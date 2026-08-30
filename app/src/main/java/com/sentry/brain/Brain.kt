package com.sentry.brain

import kotlinx.coroutines.flow.Flow

/** One chat turn handed to a backend. */
data class Msg(val role: Role, val content: String)

enum class Role { SYSTEM, USER, ASSISTANT }

/**
 * A shape the answer must take.
 *
 * Backends that can enforce this do; backends that cannot are asked politely in the
 * prompt and their output is parsed defensively. [Brain.constrains] says which you
 * are dealing with, so a caller that genuinely depends on the shape can pick a
 * backend that will honour it.
 */
sealed interface Shape {
    /** Exactly one of these strings, verbatim. */
    data class OneOf(val options: List<String>) : Shape

    /** A JSON object with these keys, in this order. */
    data class Json(val fields: List<Field>) : Shape

    data class Field(val name: String, val type: FieldType, val required: Boolean = true)

    enum class FieldType { STRING, INTEGER, NUMBER, BOOLEAN }
}

data class BrainParams(
    val maxTokens: Int = 256,
    val temperature: Float = 0.7f,
    val shape: Shape? = null,
    val stop: List<String> = emptyList(),
)

/**
 * A source of language-model answers.
 *
 * Sentry has two: Tara Core, which is always available because it is a service the
 * user installed on purpose, and AICore, which is faster and NPU-backed on the
 * handful of devices that ship it. Neither is on the critical path for alarms,
 * timers or calls — those never reach a brain at all — so a slow or absent backend
 * degrades conversation rather than breaking the assistant.
 */
interface Brain {

    /** Shown in settings. */
    val name: String

    /** Whether this backend can enforce [Shape] rather than merely being asked to. */
    val constrains: Boolean

    /**
     * Whether this backend can serve requests right now. Cheap enough to call on a
     * cold start; implementations cache the expensive part.
     */
    suspend fun isAvailable(): Boolean

    /** Warm the backend so the first real request is not the one that pays for it. */
    suspend fun warmUp()

    /** Stream an answer. Cancelling the collector must stop the generation. */
    fun stream(messages: List<Msg>, params: BrainParams = BrainParams()): Flow<String>

    /** The whole answer at once. Never call from the main thread. */
    suspend fun complete(messages: List<Msg>, params: BrainParams = BrainParams()): String

    fun close()
}
