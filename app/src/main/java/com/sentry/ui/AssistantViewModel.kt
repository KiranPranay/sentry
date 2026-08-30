package com.sentry.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sentry.Container
import com.sentry.core.Agent
import com.sentry.sentry
import com.sentry.voice.HotwordService
import com.sentry.voice.VoiceEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives one assistant session: microphone in, answer out, and back to listening.
 *
 * The loop it implements is the part that has to feel right. Hear the user, stop the
 * moment they stop, answer — and then reopen the microphone automatically, so a
 * conversation is a conversation rather than a sequence of separate commands each
 * needing its own wake word or mic tap. It ends on silence, on "stop", or on
 * dismissal, which are the three ways a person actually finishes talking.
 */
class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "Sentry/UI"

        /** Let the speaker drain before the microphone reopens. */
        const val TAIL_OF_SPEECH_MS = 350L

        /** How long after TTS reports "done" the speaker is still making noise. */
        const val SPEECH_HANGOVER_MS = 700L

        /**
         * How long the session survives with nothing said.
         *
         * Generous on purpose. The microphone being open is visible — the orb is lit
         * and the status line says so — and pausing to think mid-conversation is
         * normal. Closing the screen while someone is deciding what to say is the
         * failure this replaces.
         */
        const val SESSION_IDLE_MS = 45_000L

        /** Breath between a failed capture and reopening, so the two do not thrash. */
        const val RETRY_DELAY_MS = 250L


    }

    private val container: Container = application.sentry
    private val voice: VoiceEngine = container.voice
    val agent: Agent = container.agent

    val transcript = agent.transcript
    val status = agent.status
    val partial = voice.partial
    val amplitude = voice.amplitude
    val speechReady = voice.ready
    val expectsAnswer = agent.expectsAnswer

    /**
     * What the screen should say Sentry is doing.
     *
     * Combines two things the old code confused for one. [Agent.Status] is what
     * Sentry is *doing*; [VoiceEngine.capturing] is whether the microphone is
     * *open*. Speaking wins over listening only because the mic being open during an
     * answer is for barge-in, and calling that "Listening" would invite the user to
     * talk over every reply.
     */
    val uiState: StateFlow<UiState> = combine(
        agent.status,
        voice.capturing,
    ) { status, micOpen ->
        when {
            status == Agent.Status.SPEAKING -> UiState.SPEAKING
            status == Agent.Status.THINKING -> UiState.THINKING
            micOpen -> UiState.LISTENING
            else -> UiState.IDLE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.IDLE)

    enum class UiState { IDLE, LISTENING, THINKING, SPEAKING }

    private val _finished = MutableStateFlow(false)

    /** Set when the session is over and the activity should close. */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private var handling: Job? = null

    /**
     * The pending "reopen the microphone" for a capture that heard nothing.
     *
     * Held so it can be cancelled. It waits a moment before reopening, and a session
     * ending inside that window used to let it fire anyway: it re-took the microphone
     * for dictation *after* the wake word had been handed back, and the wake word
     * never came up again.
     */
    private var retry: Job? = null

    /** Set once the session is over, so an in-flight reply does not reopen the mic. */
    @Volatile
    private var ended = false

    /** When the user last did something. The idle budget counts from here. */
    @Volatile
    private var lastInteractionAt = 0L


    init {
        viewModelScope.launch {
            voice.events.collect { event ->
                when (event) {
                    is VoiceEngine.Event.Transcript -> {
                        // Speaker check, and only where it can be done honestly: a
                        // dictated command is long enough to carry a usable
                        // voiceprint, where the wake word is not. Fails open — see
                        // VoiceProfile.accepts.
                        if (container.voiceProfile.accepts(event.voiceprint)) {
                            onHeard(event.text)
                        } else {
                            Log.i(TAG, "ignoring \"${event.text}\": not the enrolled voice")
                            // Somebody spoke, just not the enrolled user. Keep the
                            // session up rather than dropping it on the floor.
                            onNothingHeard(heardSomething = true)
                        }
                    }

                    is VoiceEngine.Event.NoSpeech -> onNothingHeard(event.heardSomething)
                    // Not handled here on purpose. The wake word is the hotword
                    // service's to act on, and it opens this screen, which starts
                    // listening in onCreate/onNewIntent. Doing it here as well meant
                    // two starts for one wake word.
                    // The user talked over the answer. Stop talking at once — the
                    // engine is already transcribing what they are saying.
                    is VoiceEngine.Event.BargeIn -> container.speaker.stop()

                    is VoiceEngine.Event.WakeWord -> Unit
                    is VoiceEngine.Event.Failed -> {
                        Log.w(TAG, "voice: ${event.reason}")
                        agent.setListening(false)
                    }

                    is VoiceEngine.Event.ModelReady -> Unit
                }
            }
        }

        viewModelScope.launch {
            // collectLatest, with a hangover: a streamed answer is spoken sentence
            // by sentence, so "speaking" flickers false in the gaps between them
            // while the speaker is still physically playing the last one. Clearing
            // the flag instantly turned each of those gaps into a window where
            // Sentry transcribed its own tail as the user's next command.
            container.speaker.speaking.collectLatest { speaking ->
                if (speaking) {
                    voice.speaking = true
                } else {
                    delay(SPEECH_HANGOVER_MS)
                    voice.speaking = false
                }
            }
        }

        // Keep the engine informed that an answer is in flight, so it holds the
        // silence timeout instead of ending the conversation mid-thought.
        viewModelScope.launch {
            agent.status.collect { status ->
                voice.busy = status == Agent.Status.THINKING || status == Agent.Status.SPEAKING
            }
        }

        // Let the engine know what we are saying, so it never mistakes our own use
        // of the word "Sentry" for the user interrupting us.
        viewModelScope.launch {
            container.speaker.speaking.collect {
                voice.spokenText = container.speaker.currentText
            }
        }
    }

    fun startSession(listenImmediately: Boolean) {
        ended = false
        lastInteractionAt = System.currentTimeMillis()
        // Carry the conversation over when the user comes straight back: "who wrote
        // Dune" followed a moment later by "when did he die" should still work. After
        // a real gap it starts clean, because stale context is worse than none.
        agent.resume()
        if (listenImmediately) startListening()
    }

    fun startListening() {
        agent.setListening(true)
        voice.startCommand()
    }

    fun stopListening() {
        voice.stop()
        agent.setListening(false)
    }

    fun toggleMic() {
        if (voice.mode.value == VoiceEngine.Mode.COMMAND) stopListening() else startListening()
    }

    /** Text typed instead of spoken. */
    fun submit(text: String) {
        // No voice.stop() here. onHeard reopens the microphone immediately, and
        // startCommand's serialised restart already cancels whatever was running —
        // the explicit stop only raced it, sometimes landing *after* the reopen and
        // killing the capture 160ms into its life.
        onHeard(text)
    }

    /**
     * A capture that produced nothing to act on.
     *
     * The old rule was "any silence ends the conversation", which closed the screen
     * out from under the user for a pause of six seconds — or for one sentence said
     * a little too quietly, since a rejected utterance arrived here too. It also had
     * the opposite failure: a session where nothing had *ever* been said stayed open
     * forever with the microphone live.
     *
     * The session now runs on an idle budget instead. Every real exchange resets it;
     * silence only ends things once the whole budget has gone, which is what "idle
     * for too long" should have meant all along.
     */
    private fun onNothingHeard(heardSomething: Boolean) {
        val idleFor = System.currentTimeMillis() - lastInteractionAt
        Log.d(TAG, "nothing heard (spoke=$heardSomething, ended=$ended, idle=${idleFor}ms)")
        if (ended) return
        agent.setListening(false)
        if (idleFor >= SESSION_IDLE_MS) {
            Log.i(TAG, "closing after ${idleFor / 1000}s idle")
            finish()
            return
        }

        // Someone talking and not being understood should never shorten the session,
        // so their attempt counts as activity.
        if (heardSomething) lastInteractionAt = System.currentTimeMillis()

        retry?.cancel()
        retry = viewModelScope.launch {
            delay(RETRY_DELAY_MS)
            // No "is it already listening?" guard here, deliberately. The capture
            // that just failed is still unwinding — releasing the recorder and
            // closing three recognisers — so it still looks live for a moment, and
            // checking made the retry skip itself and the session hang with a dead
            // microphone. startCommand() serialises on the engine's own lock and
            // joins the outgoing job, so calling it unconditionally is the safe form.
            if (!ended) startListening()
        }
    }

    private fun onHeard(text: String) {
        lastInteractionAt = System.currentTimeMillis()
        retry?.cancel()
        handling?.cancel()
        handling = viewModelScope.launch {
            // Reopen the microphone *before* answering, not after. This is what lets
            // the user interrupt a long answer instead of waiting it out, and it is
            // the difference between a conversation and a pair of monologues.
            if (!ended) startListening()

            agent.handle(text)

            // Keep the conversation open. Sentry has just finished speaking, so the
            // microphone reopens by itself and the user can simply carry on talking —
            // no wake word, no tapping the mic between every sentence. The session
            // ends when they say nothing (VoiceEngine's silence timeout), say "stop",
            // or dismiss the screen.
            if (agent.ended.value) {
                finish()
                return@launch
            }
            // Normally the microphone is still open from before the answer. Only
            // restart it if something closed it — a barge-in that consumed the
            // capture, or an error.
            if (!ended && !voice.listening) {
                delay(TAIL_OF_SPEECH_MS)
                if (!ended && !voice.listening) startListening()
            }
        }
    }

    /** Interrupt whatever is happening — the user tapped away or said "stop". */
    fun cancel() {
        ended = true
        retry?.cancel()
        handling?.cancel()
        agent.forgetPending()
        container.speaker.stop()
        voice.stop()
        agent.setListening(false)
    }

    /**
     * Hand the microphone back to the wake-word service.
     *
     * Called when the session ends. Without it the hotword stops working after the
     * first use, because the UI walked off still holding the mic.
     */
    fun releaseToHotword() {
        ended = true
        retry?.cancel()
        handling?.cancel()
        agent.forgetPending()
        container.speaker.stop()
        agent.setListening(false)

        // resume(), not start(). This runs from onStop, by which point the app is
        // in the background — and a microphone-type foreground service cannot be
        // started from there on Android 14+. Doing so threw SecurityException and
        // killed the process every time a session ended. The service never stopped;
        // it only needs its engine pointed back at the wake word.
        if (container.prefs.hotwordEnabled) {
            Log.d(TAG, "handing the microphone back to the wake word")
            HotwordService.resume(getApplication())
        } else {
            voice.stop()
        }
    }

    fun finish() {
        ended = true
        _finished.value = true
    }

    override fun onCleared() {
        // releaseToHotword, not cancel. onCleared runs *after* onStop has already
        // handed the microphone back, and cancel() stops the engine outright — so it
        // killed the wake-word capture a moment after starting it and left the phone
        // deaf until the app was restarted. Handing back is idempotent; stopping is
        // not something we want to do twice.
        releaseToHotword()
        super.onCleared()
    }
}
