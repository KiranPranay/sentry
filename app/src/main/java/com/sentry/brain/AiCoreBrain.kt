package com.sentry.brain

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gemini Nano, through Google's on-device AICore service.
 *
 * Only some devices have it — it is a system component, not something an app can
 * install — and the SDK is experimental and access-gated, so this backend treats
 * *every* failure as "not available today" and lets [Brains] fall back to Tara Core.
 * Nothing here is allowed to break the assistant.
 *
 * Where it does work it is materially faster than a CPU-bound GGUF model, because it
 * reaches hardware Tara Core's `llama.cpp` backend cannot.
 */
@RequiresApi(Build.VERSION_CODES.S)
class AiCoreBrain(context: Context) : Brain {

    private companion object {
        const val TAG = "Sentry/AiCore"
        const val AICORE_PACKAGE = "com.google.android.aicore"
    }

    private val appContext = context.applicationContext
    private val lock = Mutex()

    @Volatile
    private var model: GenerativeModel? = null

    /** Null until we have actually tried; afterwards the cached verdict. */
    @Volatile
    private var available: Boolean? = null

    override val name = "Gemini Nano (AICore)"

    /**
     * AICore exposes no grammar or schema hook, so a requested [Shape] can only be
     * asked for in the prompt and validated afterwards.
     */
    override val constrains = false

    override suspend fun isAvailable(): Boolean {
        available?.let { return it }
        if (!isSystemComponentPresent()) {
            available = false
            return false
        }
        return runCatching { ensureModel() }.isSuccess.also { available = it }
    }

    override suspend fun warmUp() {
        runCatching { ensureModel() }
            .onFailure { Log.i(TAG, "AICore unavailable on this device: ${it.message}") }
    }

    private fun isSystemComponentPresent(): Boolean = try {
        appContext.packageManager.getPackageInfo(AICORE_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    @SuppressLint("MissingPermission")
    private suspend fun ensureModel(): GenerativeModel {
        model?.let { return it }
        return lock.withLock {
            model?.let { return it }
            val created = GenerativeModel(
                generationConfig = generationConfig {
                    context = appContext
                    temperature = 0.7f
                    topK = 16
                    maxOutputTokens = 256
                }
            )
            // The only honest availability probe: it throws when the feature is
            // absent, not allow-listed, or still downloading its weights.
            created.prepareInferenceEngine()
            model = created
            available = true
            Log.i(TAG, "AICore inference engine ready")
            created
        }
    }

    override fun stream(messages: List<Msg>, params: BrainParams): Flow<String> = flow {
        val engine = ensureModel()
        // AICore takes one flat prompt: it has no notion of roles or of a chat
        // template, so the conversation is rendered before it goes in.
        val prompt = render(messages, params)

        // The SDK does not document whether chunks are deltas or the whole answer so
        // far, and it has differed between preview builds. Detecting it costs one
        // string comparison per chunk and removes an entire class of duplicated-text
        // bug, so we do that rather than trust either behaviour.
        val seen = StringBuilder()
        engine.generateContentStream(prompt)
            .map { it.text.orEmpty() }
            .collect { chunk ->
                if (chunk.isEmpty()) return@collect
                val piece = if (chunk.startsWith(seen) && chunk.length > seen.length) {
                    chunk.substring(seen.length)
                } else {
                    chunk
                }
                seen.append(piece)
                emit(piece)
            }
    }

    override suspend fun complete(messages: List<Msg>, params: BrainParams): String {
        val engine = ensureModel()
        return engine.generateContent(render(messages, params)).text.orEmpty()
    }

    override fun close() {
        runCatching { model?.close() }
        model = null
    }

    /**
     * Flatten a role-tagged conversation into the single string AICore accepts.
     *
     * A requested [Shape] becomes an instruction rather than a constraint, which is
     * exactly as reliable as it sounds — callers that need the guarantee should
     * check [constrains] and pick another backend.
     */
    private fun render(messages: List<Msg>, params: BrainParams): String = buildString {
        messages.firstOrNull { it.role == Role.SYSTEM }?.let {
            append(it.content).append("\n\n")
        }
        params.shape?.let { append(instructionFor(it)).append("\n\n") }
        for (message in messages) {
            when (message.role) {
                Role.SYSTEM -> Unit
                Role.USER -> append("User: ").append(message.content).append('\n')
                Role.ASSISTANT -> append("Assistant: ").append(message.content).append('\n')
            }
        }
        append("Assistant:")
    }

    private fun instructionFor(shape: Shape): String = when (shape) {
        is Shape.OneOf ->
            "Reply with exactly one of these words and nothing else: " +
                shape.options.joinToString(", ")

        is Shape.Json ->
            "Reply with a single JSON object and nothing else, with these keys: " +
                shape.fields.joinToString(", ") { "${it.name} (${it.type.name.lowercase()})" }
    }
}
