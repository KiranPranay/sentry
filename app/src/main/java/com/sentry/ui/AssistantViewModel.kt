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
import kotlinx.coroutines.flow.collectLatest
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

    }

    private val container: Container = application.sentry
    private val voice: VoiceEngine = container.voice
    val agent: Agent = container.agent

    val transcript = agent.transcript
    val status = agent.status
    val partial = voice.partial
    val amplitude = voice.amplitude
    val speechReady = voice.ready

    private val _finished = MutableStateFlow(false)

    /** Set when the session is over and the activity should close. */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private var handling: Job? = null

    /** Set once the session is over, so an in-flight reply does not reopen the mic. */
    @Volatile
    private var ended = false

    init {
        viewModelScope.launch {
            voice.events.collect { event ->
                when (event) {
                    is VoiceEngine.Event.Transcript -> onHeard(event.text)

                    // Silence is how a conversation ends. Anything else would leave
                    // the microphone open indefinitely after the user walked away.
                    is VoiceEngine.Event.NoSpeech -> {
                        agent.setListening(false)
                        if (agent.transcript.value.isNotEmpty()) finish()
                    }
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
        voice.stop()
        onHeard(text)
    }

    private fun onHeard(text: String) {
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
        handling?.cancel()
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
        handling?.cancel()
        container.speaker.stop()
        agent.setListening(false)

        val app = getApplication<Application>()
        if (container.prefs.hotwordEnabled) {
            // Deliberately no voice.stop() first. Starting the hotword capture goes
            // through the engine's own serialised restart, which cancels the command
            // capture and brings up the wake-word one as a single operation. Stopping
            // separately here raced the service's start and left the engine off.
            HotwordService.start(app)
        } else {
            voice.stop()
        }
    }

    fun finish() {
        ended = true
        _finished.value = true
    }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }
}
