package com.sentry.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sentry.nlu.FastMatcher
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
        const val ACTION_WHO = "com.sentry.debug.WHO"
        const val ACTION_REACH = "com.sentry.debug.REACH"
        const val ACTION_PLAN = "com.sentry.debug.PLAN"

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

            ACTION_PLAN -> {
                // What Sentry would do, without doing it. The only way to sweep the
                // whole action surface at three in the morning: "play something on
                // Spotify" is a fine thing to check and a terrible thing to run.
                val text = intent.getStringExtra("text").orEmpty()
                val translated = container.phrases.translate(text)
                val command = FastMatcher.match(translated)
                val note = if (translated != text) "  (heard as \"$translated\")" else ""
                Log.i(TAG, "plan \"$text\"$note -> ${command ?: "no fast match; would ask the model"}")
            }

            ACTION_REACH -> {
                // "--ez on false" before a test sweep, and nothing said to Sentry can
                // ring a phone that belongs to someone else.
                val on = intent.getBooleanExtra("on", true)
                if (on) container.skills.reachOthers.allow() else container.skills.reachOthers.block()
                Log.i(TAG, "reaching other people: ${if (on) "allowed" else "BLOCKED"}")
            }

            ACTION_WHO -> {
                // Resolution only — deliberately never dispatches, so a name can be
                // checked at three in the morning without a phone ringing anywhere.
                val name = intent.getStringExtra("name").orEmpty()
                val found = container.skills.findPerson(name)
                if (found.isEmpty()) {
                    Log.i(TAG, "who \"$name\" -> nobody")
                } else {
                    val verdict = if (found.certain) "certain" else "unsure"
                    found.forEach {
                        Log.i(TAG, "who \"$name\" [$verdict] -> ${it.name} (${it.number}) ${it.label}")
                    }
                }
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
                Log.i(TAG, "=== learned names ===")
                container.names.all().forEach { (spoken, contact) ->
                    Log.i(TAG, "  \"$spoken\" -> $contact")
                }
                Log.i(TAG, "=== memory ===")
                container.memory.all().forEach {
                    Log.i(TAG, "  ${it.fact.name} = ${it.value}  (${it.source})")
                }
                Log.i(TAG, "=== voice profile ===")
                Log.i(TAG, "  samples=${container.voiceProfile.sampleCount} " +
                    "enforce=${container.voiceProfile.enforce}")
            }
        }
    }
}
