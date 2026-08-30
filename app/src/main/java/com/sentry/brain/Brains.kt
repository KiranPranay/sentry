package com.sentry.brain

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Picks a backend and keeps a working one in hand.
 *
 * The policy is deliberately dull: prefer AICore when the device really has it,
 * otherwise Tara Core, and fall back to Tara Core the moment AICore misbehaves.
 * Selection happens once during warm-up, off the critical path, so no user request
 * ever waits on a capability probe.
 */
class Brains(context: Context, private val preference: Preference = Preference.AUTO) {

    enum class Preference { AUTO, TARA_CORE, AICORE }

    private companion object {
        const val TAG = "Sentry/Brains"
    }

    private val taraCore = TaraCoreBrain(context)

    private val aiCore: Brain? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { AiCoreBrain(context) }
                .onFailure { Log.i(TAG, "AICore classes unavailable", it) }
                .getOrNull()
        } else {
            null
        }

    @Volatile
    private var chosen: Brain? = null

    /** The backend currently in use. Null until warm-up has run. */
    val active: Brain? get() = chosen

    /**
     * Decide which backend to use and make it ready. Safe to call repeatedly; safe to
     * call from a service's onCreate, since it never throws.
     */
    suspend fun warmUp() {
        val pick = when (preference) {
            Preference.TARA_CORE -> taraCore.takeIf { it.isAvailable() }

            Preference.AICORE ->
                aiCore?.takeIf { it.isAvailable() } ?: taraCore.takeIf { it.isAvailable() }

            Preference.AUTO ->
                aiCore?.takeIf { it.isAvailable() } ?: taraCore.takeIf { it.isAvailable() }
        }
        chosen = pick
        if (pick == null) {
            Log.w(TAG, "no inference backend is available")
        } else {
            Log.i(TAG, "using ${pick.name}")
            pick.warmUp()
        }
    }

    /**
     * The backend to use for a request needing a guaranteed [Shape].
     *
     * Prefers one that actually enforces the shape: an unconstrained model that
     * disobeys the format is indistinguishable from one that was never constrained,
     * and the failure surfaces as a nonsense action rather than an error.
     */
    private suspend fun brainFor(params: BrainParams): Brain? {
        val current = chosen ?: run { warmUp(); chosen }
        if (params.shape != null && current?.constrains == false) {
            if (taraCore.constrains && taraCore.isAvailable()) return taraCore
        }
        return current
    }

    fun stream(messages: List<Msg>, params: BrainParams = BrainParams()): Flow<String> = flow {
        val brain = brainFor(params) ?: throw NoBrainException()
        // The `catch` operator rather than a try/catch around emitAll: catching an
        // exception that came from *downstream* and then emitting again violates
        // flow exception transparency, and Kotlin throws for it at runtime. `catch`
        // sees only upstream failures, which is exactly the set we want to retry.
        emitAll(
            brain.stream(messages, params).catch { failure ->
                // One retry on the other backend: an AICore that dies mid-stream
                // should cost the user a pause, not the answer.
                val fallback = fallbackFor(brain) ?: throw failure
                Log.w(TAG, "${brain.name} failed, retrying on ${fallback.name}", failure)
                chosen = fallback
                emitAll(fallback.stream(messages, params))
            }
        )
    }

    suspend fun complete(messages: List<Msg>, params: BrainParams = BrainParams()): String {
        val brain = brainFor(params) ?: throw NoBrainException()
        return try {
            brain.complete(messages, params)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val fallback = fallbackFor(brain) ?: throw e
            Log.w(TAG, "${brain.name} failed, retrying on ${fallback.name}", e)
            chosen = fallback
            fallback.complete(messages, params)
        }
    }

    private suspend fun fallbackFor(failed: Brain): Brain? =
        if (failed !== taraCore && taraCore.isAvailable()) taraCore else null

    fun close() {
        taraCore.close()
        aiCore?.close()
    }
}

/**
 * No backend could answer. Callers turn this into an explanation the user can act
 * on — usually "Tara Core isn't installed" — rather than a stack trace.
 */
class NoBrainException : Exception("No on-device inference backend is available")
