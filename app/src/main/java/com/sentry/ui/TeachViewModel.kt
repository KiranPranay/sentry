package com.sentry.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sentry.Container
import com.sentry.sentry
import com.sentry.voice.HotwordService
import com.sentry.voice.VoiceEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Teaching Sentry a phrase it keeps mishearing.
 *
 * The user says the phrase a few times; Sentry writes down what it actually heard and
 * remembers that those sounds mean this phrase. It is not training the recogniser —
 * that is not possible on a phone, and is not what "Ok Google" enrollment does either
 * — it is learning a consistent mistake so it stops mattering.
 *
 * Repetitions matter because the mistake is only *mostly* consistent: "call maa" came
 * back as "karma" twice and "come up" once. Capturing several attempts catches the
 * variants, and a variant heard more than once is the one worth trusting.
 */
class TeachViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "Sentry/Teach"
        const val ATTEMPTS = 3
    }

    private val container: Container = application.sentry
    private val voice: VoiceEngine = container.voice

    data class Attempt(val heard: String, val usable: Boolean)

    data class State(
        val phrase: String = "",
        val listening: Boolean = false,
        val attempts: List<Attempt> = emptyList(),
        val partial: String = "",
        val saved: Boolean = false,
        val error: String? = null,
    ) {
        val remaining: Int get() = (ATTEMPTS - attempts.size).coerceAtLeast(0)
        val complete: Boolean get() = attempts.size >= ATTEMPTS
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val learned = MutableStateFlow(container.phrases.all())

    private var listenJob: Job? = null

    init {
        viewModelScope.launch {
            voice.events.collect { event ->
                when (event) {
                    is VoiceEngine.Event.Transcript -> onHeard(event.text)

                    is VoiceEngine.Event.NoSpeech -> _state.value = _state.value.copy(
                        listening = false,
                        error = "I didn't hear anything. Try again, a little louder.",
                    )

                    is VoiceEngine.Event.Failed -> _state.value = _state.value.copy(
                        listening = false,
                        error = event.reason,
                    )

                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            voice.partial.collect { _state.value = _state.value.copy(partial = it) }
        }
    }

    fun setPhrase(phrase: String) {
        _state.value = State(phrase = phrase)
    }

    fun record() {
        val current = _state.value
        if (current.phrase.isBlank() || current.complete) return
        _state.value = current.copy(listening = true, error = null)
        voice.startCommand()
    }

    private fun onHeard(heard: String) {
        val current = _state.value
        if (!current.listening) return

        // A phrase that already matches what was meant needs no rule, and saving one
        // would quietly shadow correct recognition later.
        val usable = normalise(heard) != normalise(current.phrase) && heard.isNotBlank()

        _state.value = current.copy(
            listening = false,
            partial = "",
            attempts = current.attempts + Attempt(heard, usable),
        )
        Log.i(TAG, "attempt ${current.attempts.size + 1}: \"$heard\"")
    }

    fun stopListening() {
        voice.stop()
        _state.value = _state.value.copy(listening = false)
    }

    /**
     * Save what was learned — every distinct variant, not just the repeated ones.
     *
     * The first version kept only mis-hearings heard more than once, on the theory
     * that one sample is weak evidence. In practice the mistake is stable in *kind*
     * but not in detail: the same phrase came back as "voters", "voters" and "don't
     * compete", and on the next run as "eat". Keeping only the repeat throws away
     * real coverage.
     *
     * Binding all of them is safe because these strings are rare by construction —
     * they are what the recogniser produces for a phrase it cannot spell — and
     * [PhraseBook] refuses anything common enough to matter.
     */
    fun save() {
        val current = _state.value
        val phrase = current.phrase.trim()
        if (phrase.isBlank()) return

        val usable = current.attempts.filter { it.usable }.map { it.heard }
        if (usable.isEmpty()) {
            _state.value = current.copy(
                error = "Sentry heard that correctly every time — nothing to teach.",
            )
            return
        }

        val toLearn = usable.map { normalise(it) }.distinct()

        // Re-teaching replaces the old rules rather than adding to them, so a phrase
        // taught twice does not accumulate stale mishearings forever.
        container.phrases.forgetTarget(phrase)
        var saved = 0
        toLearn.forEach { if (container.phrases.learn(it, phrase)) saved++ }
        Log.i(TAG, "learned $saved of ${toLearn.size} variants for \"$phrase\"")

        learned.value = container.phrases.all()
        // The recogniser should now be biased towards the phrase we just learned.
        container.refreshBias()
        _state.value = current.copy(
            saved = saved > 0,
            error = if (saved > 0) null else "Couldn't save that.",
        )
    }

    fun forget(heard: String) {
        container.phrases.forget(heard)
        learned.value = container.phrases.all()
        container.refreshBias()
    }

    fun reset() {
        _state.value = State()
    }

    /** Give the microphone back to the wake word when this screen closes. */
    fun release() {
        listenJob?.cancel()
        // Deliberately no voice.stop() before handing back. Starting the hotword
        // service goes through the engine's own serialised restart, which cancels
        // this screen's capture and brings up the wake-word one as a single
        // operation. Stopping separately raced that restart and won, leaving the
        // wake word dead after every visit to this screen.
        if (container.prefs.hotwordEnabled) {
            HotwordService.start(getApplication())
        } else {
            voice.stop()
        }
    }

    private fun normalise(text: String) =
        text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()

    override fun onCleared() {
        release()
        super.onCleared()
    }
}
