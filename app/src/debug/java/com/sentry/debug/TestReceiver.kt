package com.sentry.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sentry.sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives Sentry from adb, for testing.
 *
 * Speaking to the phone is the real interface, but it is a terrible test harness: a
 * synthetic voice played across a room is inconsistent run to run, so a failure never
 * distinguishes "the pipeline is wrong" from "the room was noisy". This injects an
 * utterance at exactly the point a transcript would arrive, which makes everything
 * downstream — matching, stitching, skills, memory — deterministic and scriptable.
 *
 *   adb shell am broadcast -a com.sentry.debug.SAY --es text "what time is it"
 *
 * Debug builds only; see src/debug/AndroidManifest.xml.
 */
class TestReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "Sentry/Test"
        const val ACTION_SAY = "com.sentry.debug.SAY"
        const val ACTION_DUMP = "com.sentry.debug.DUMP"
        const val ACTION_LOOKUP = "com.sentry.debug.LOOKUP"
        const val ACTION_SPLIT = "com.sentry.debug.SPLIT"
        const val ACTION_APPS = "com.sentry.debug.APPS"

        /** Long enough for a conversational answer from a small model. */
        const val TURN_TIMEOUT_MS = 90_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context, intent: Intent?) {
        val container = context.sentry

        when (intent?.action) {
            ACTION_SAY -> {
                val text = intent.getStringExtra("text").orEmpty()
                if (text.isBlank()) {
                    Log.w(TAG, "SAY with no text")
                    return
                }
                Log.i(TAG, "--> \"$text\"")
                scope.launch {
                    val took = withTimeoutOrNull(TURN_TIMEOUT_MS) {
                        val started = System.currentTimeMillis()
                        container.agent.handle(text)
                        System.currentTimeMillis() - started
                    }
                    val reply = container.agent.transcript.value.lastOrNull()
                    Log.i(TAG, "<-- \"${reply?.text.orEmpty()}\" (${took ?: -1}ms)")
                }
            }

            ACTION_SPLIT -> {
                // Two fragments with a controlled gap, which is what the recogniser
                // produces when it endpoints mid-sentence. Driving this from adb is
                // not reliable — two round trips take longer than the continuation
                // window — so the gap is applied in-process.
                val first = intent.getStringExtra("first").orEmpty()
                val second = intent.getStringExtra("second").orEmpty()
                val gap = intent.getIntExtra("gap", 150).toLong()
                Log.i(TAG, "--> \"$first\" +${gap}ms \"$second\"")
                scope.launch {
                    launch { container.agent.handle(first) }
                    delay(gap)
                    container.agent.handle(second)
                    Log.i(TAG, "<-- \"${container.agent.transcript.value.lastOrNull()?.text.orEmpty()}\"")
                }
            }

            ACTION_LOOKUP -> {
                val name = intent.getStringExtra("name").orEmpty()
                val match = container.appsIndex.find(name)
                Log.i(TAG, "lookup \"$name\" -> ${match?.label} / ${match?.packageName}")
            }

            ACTION_APPS -> {
                val filter = intent.getStringExtra("filter").orEmpty().lowercase()
                container.appsIndex.all()
                    .filter { filter.isBlank() || it.label.lowercase().contains(filter) ||
                        it.packageName.lowercase().contains(filter) }
                    .forEach { Log.i(TAG, "  app: ${it.label}  [${it.packageName}]") }
            }

            ACTION_DUMP -> {
                Log.i(TAG, "=== transcript ===")
                container.agent.transcript.value.forEach {
                    Log.i(TAG, "  ${it.party}: ${it.text}")
                }
                Log.i(TAG, "=== learned phrases ===")
                container.phrases.all().forEach { (heard, meant) ->
                    Log.i(TAG, "  \"$heard\" -> \"$meant\"")
                }
                Log.i(TAG, "=== voice profile ===")
                Log.i(TAG, "  samples=${container.voiceProfile.sampleCount} " +
                    "enforce=${container.voiceProfile.enforce}")
            }
        }
    }
}
