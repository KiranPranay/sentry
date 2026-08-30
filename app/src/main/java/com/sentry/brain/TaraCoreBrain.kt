package com.sentry.brain

import android.content.Context
import android.util.Log
import dev.taracore.api.ChatMessageParcel
import dev.taracore.client.ChatParams
import dev.taracore.client.Constraint
import dev.taracore.client.TaraCore
import dev.taracore.client.TaraCoreClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The default backend: the Tara Core inference service.
 *
 * Sentry ships no weights and no engine of its own. Tara Core holds one copy of the
 * model for the whole device, which is the entire reason this app no longer carries
 * a 500 MB `.task` file in its assets.
 */
class TaraCoreBrain(context: Context) : Brain {

    private companion object {
        const val TAG = "Sentry/TaraCore"
    }

    private val appContext = context.applicationContext
    private val client = TaraCoreClient(appContext)
    private val connectLock = Mutex()

    @Volatile
    private var connected = false

    @Volatile
    private var apiVersion = 0

    override val name = "Tara Core"

    override val constrains: Boolean get() = apiVersion >= 2

    override suspend fun isAvailable(): Boolean =
        TaraCore.isInstalled(appContext) && runCatching { ensureConnected() }.isSuccess

    override suspend fun warmUp() {
        runCatching {
            ensureConnected()
            // Ask the service to make the user's chosen model resident. It takes no
            // model id — the engine is shared, and picking one on every other app's
            // behalf is not ours to do — and it may decline, which is fine: the next
            // request still works, it just pays the load. Without this the first
            // question of any hour waits on a gigabyte coming off storage, because
            // the idle unloader has been.
            if (apiVersion >= 3) {
                val warmed = client.warmUpQuietly()
                Log.i(TAG, if (warmed) "model warmed" else "service declined to warm")
            }
        }.onFailure { Log.w(TAG, "warm-up failed", it) }
    }

    private suspend fun ensureConnected() {
        if (connected && client.isConnected) return
        connectLock.withLock {
            if (connected && client.isConnected) return
            client.connect()
            connected = true
            apiVersion = runCatching { client.apiVersion() }.getOrDefault(1)
            Log.i(TAG, "connected, api version $apiVersion")
        }
    }

    override fun stream(messages: List<Msg>, params: BrainParams): Flow<String> {
        // connect() suspends, so the flow has to be built lazily rather than here.
        return flow {
            ensureConnected()
            emitAll(client.chatStream(messages.toParcels(), params.toChatParams()))
        }
    }

    override suspend fun complete(messages: List<Msg>, params: BrainParams): String {
        ensureConnected()
        return client.chat(messages.toParcels(), params.toChatParams())
    }

    override fun close() {
        connected = false
        runCatching { client.close() }
    }

    // -------------------------------------------------------------- mapping

    private fun List<Msg>.toParcels(): List<ChatMessageParcel> = map {
        ChatMessageParcel(
            role = when (it.role) {
                Role.SYSTEM -> ChatMessageParcel.ROLE_SYSTEM
                Role.USER -> ChatMessageParcel.ROLE_USER
                Role.ASSISTANT -> ChatMessageParcel.ROLE_ASSISTANT
            },
            content = it.content,
        )
    }

    private fun BrainParams.toChatParams() = ChatParams(
        // Null means "whatever is resident", which never costs a model swap. Naming
        // one would let the service decide a multi-second swap is acceptable, and for
        // a voice assistant it is not. With auto-load left on, a cold service still
        // loads the user's chosen active model rather than failing the first request
        // of the day — Tara Core resolves null to loaded, then active, then whatever
        // is downloaded. Which model that is stays the user's choice, made in Tara
        // Core, because the engine is shared with every other app on the device.
        modelId = null,
        maxTokens = maxTokens,
        temperature = temperature,
        stop = stop,
        allowAutoLoad = true,
        grammar = shape?.let { toGrammar(it) },
    )

    private fun toGrammar(shape: Shape): String? = when (shape) {
        is Shape.OneOf -> Constraint.oneOf(shape.options)

        // Constraint.obj rather than a hand-built schema tree: this is the shape the
        // whole planner depends on, and expressing it in the SDK's own vocabulary
        // means the guarantee is the SDK's to keep.
        is Shape.Json -> Constraint.obj {
            for (field in shape.fields) {
                when (field.type) {
                    Shape.FieldType.STRING -> string(field.name, field.required)
                    Shape.FieldType.INTEGER -> integer(field.name, field.required)
                    Shape.FieldType.NUMBER -> number(field.name, field.required)
                    Shape.FieldType.BOOLEAN -> boolean(field.name, field.required)
                }
            }
        }
    }
}
