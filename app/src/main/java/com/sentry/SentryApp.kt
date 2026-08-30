package com.sentry

import android.app.Application
import android.content.Context
import com.sentry.brain.Brains
import com.sentry.core.Agent
import com.sentry.data.Prefs
import com.sentry.nlu.FastMatcher
import com.sentry.nlu.Planner
import com.sentry.skills.Skills
import com.sentry.voice.Speaker
import com.sentry.voice.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SentryApp : Application() {

    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        container.start()
    }
}

/**
 * The app's long-lived objects, in one place.
 *
 * All of these are genuinely process-wide: the acoustic model is 68 MB and must not
 * be loaded twice, the Tara Core binding should be one connection, and the hotword
 * service and the assistant UI have to share the same microphone and the same
 * transcript. A dependency-injection framework would buy nothing here.
 */
class Container(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs = Prefs(appContext)

    val voice = VoiceEngine(appContext).apply {
        // Let the recogniser prefer a hypothesis that is an actual command. This is
        // the only place the audio layer and the language layer meet, and it is a
        // one-way predicate rather than a dependency.
        preferHypothesis = { FastMatcher.match(it) != null }
    }
    val speaker = Speaker(appContext)

    val brains = Brains(appContext, prefs.backendPreference())

    private val skills = Skills(appContext)
    private val planner = Planner(brains)

    val agent = Agent(skills, planner, brains, speaker)

    /**
     * Warm everything that would otherwise be paid for on the first request: the
     * acoustic model, the inference backend, and the text-to-speech engine.
     *
     * This is the difference between "Sentry" being answered instantly and being
     * answered after a two second stall on the very first use of the day.
     */
    fun start() {
        scope.launch { voice.prepare() }
        scope.launch { brains.warmUp() }
    }

    fun shutdown() {
        voice.close()
        speaker.close()
        brains.close()
    }
}

/** Reaches the container from anywhere with a Context. */
val Context.sentry: Container
    get() = (applicationContext as SentryApp).container
