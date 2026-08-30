package com.sentry

import android.app.Application
import android.content.Context
import com.sentry.brain.Brains
import com.sentry.core.Agent
import com.sentry.data.PhraseBook
import com.sentry.data.Prefs
import com.sentry.nlu.FastMatcher
import com.sentry.nlu.Planner
import com.sentry.skills.Contacts
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
    val phrases = PhraseBook(appContext)

    val voice = VoiceEngine(appContext).apply {
        pack = prefs.speechPack
        // Let the recogniser prefer a hypothesis that is an actual command. This is
        // the only place the audio layer and the language layer meet, and it is a
        // one-way predicate rather than a dependency.
        preferHypothesis = { FastMatcher.match(it) != null }
    }
    val speaker = Speaker(appContext)

    val brains = Brains(appContext, prefs.backendPreference())

    private val contacts = Contacts(appContext)
    private val skills = Skills(appContext)
    private val planner = Planner(brains)

    val agent = Agent(skills, planner, brains, speaker, phrases)

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
        scope.launch { refreshBias() }
    }

    /**
     * Rebuild the recogniser's bias list from the user's contacts and anything they
     * have taught Sentry.
     *
     * Cheap and idempotent, so it also runs after teaching a phrase — a name the user
     * just had to spell out by voice is exactly the one worth biasing towards.
     */
    fun refreshBias() {
        // What the user taught comes first, and is the part that reliably works.
        //
        // Vosk drops any grammar word outside the model's lexicon — on this phone it
        // rejected 'maaaaaaa', 'ananya', 'akshitha', 'chintu' and 'meka', which is to
        // say most of the address book. The recogniser cannot emit those strings at
        // all, which is exactly why "call maa" comes back as "karma".
        //
        // A taught phrase has the opposite property by construction: it is what the
        // recogniser itself produced, so every word in it is in the lexicon and the
        // grammar keeps all of it. Teaching a phrase therefore does two things —
        // it translates the mistake, and it biases the decoder towards making that
        // same mistake consistently.
        val taught = phrases.all().let { it.keys + it.values }.distinct()

        val templates = listOf("call", "text")
        val fromContacts = contacts.biasNames().flatMap { name ->
            // Several spellings, because we cannot ask the model what it knows and
            // the rejected ones cost nothing but a log line.
            spellings(name).flatMap { spelling -> templates.map { "$it $spelling" } }
        }

        voice.biasPhrases = (taught + fromContacts).distinct()
    }

    /**
     * Plausible in-lexicon spellings of a contact's name.
     *
     * "Maaaaaaa" is not a word any model knows, but "ma" might be. Costs nothing to
     * try: Vosk silently drops the ones it does not have.
     */
    private fun spellings(name: String): List<String> {
        val collapsed = buildString {
            for (c in name) if (isEmpty() || last() != c) append(c)
        }
        return listOf(name, collapsed).distinct().filter { it.length >= 2 }
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
