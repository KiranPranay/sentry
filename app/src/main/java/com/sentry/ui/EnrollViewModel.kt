package com.sentry.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sentry.Container
import com.sentry.data.VoiceProfile
import com.sentry.sentry
import com.sentry.voice.HotwordService
import com.sentry.voice.VoiceEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Teaching Sentry your voice.
 *
 * This is the flow people mean when they mention "Ok Google" enrollment, and unlike
 * the phrase teaching next door it really is the same mechanism: each recording is
 * turned into an x-vector — a 128-number description of the voice rather than the
 * words — and the set becomes a profile to compare future speech against.
 *
 * The prompts are long and phonetically varied on purpose. The speaker model wants
 * seconds of speech, not a wake word: it is a telephone-band model that emits nothing
 * at all below about half a second and is only really steady past two. Five short
 * repetitions of "Sentry" would produce five unreliable vectors and a profile that
 * matches everybody.
 */
class EnrollViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "Sentry/Enroll"
    }

    /**
     * Varied sentences rather than repetitions.
     *
     * Enrolling on one phrase and verifying on another is measurably worse than
     * matching content, so the safest thing is to enroll across the *range* of things
     * someone actually says to an assistant. These are also all long enough to clear
     * the model's minimum comfortably.
     */
    val prompts = listOf(
        "Sentry, what is the weather going to be like today",
        "Set an alarm for seven thirty tomorrow morning",
        "Call my mother and tell her I am running a little late",
        "Play some music and turn the volume up a bit",
        "Remind me to buy milk and bread on the way home",
    )

    private val container: Container = application.sentry
    private val voice: VoiceEngine = container.voice
    private val profile: VoiceProfile = container.voiceProfile

    data class Sample(val frames: Int, val heard: String)

    data class State(
        val index: Int = 0,
        val listening: Boolean = false,
        val samples: List<Sample> = emptyList(),
        val partial: String = "",
        val saved: Boolean = false,
        val spread: Pair<Float, Float>? = null,
        val enforce: Boolean = false,
        val enrolledCount: Int = 0,
        val error: String? = null,
    ) {
        val complete: Boolean get() = samples.size >= VoiceProfile.ENROLL_TARGET
        val remaining: Int get() = (VoiceProfile.ENROLL_TARGET - samples.size).coerceAtLeast(0)
    }

    private val _state = MutableStateFlow(
        State(
            // Reflect the profile that is already on disk. Without this, reopening
            // the screen after enrolling showed the "read this out" flow again, as
            // though nothing had been learned — `saved` meant "saved during this
            // visit" where the screen needed "enrolled at all".
            saved = profile.isEnrolled,
            enforce = profile.enforce,
            enrolledCount = profile.sampleCount,
            spread = profile.selfConsistency(),
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** Vectors collected this session, not yet committed. */
    private val collected = mutableListOf<FloatArray>()

    init {
        viewModelScope.launch {
            voice.events.collect { event ->
                when (event) {
                    is VoiceEngine.Event.Transcript -> onRecorded(event)

                    is VoiceEngine.Event.NoSpeech -> _state.value = _state.value.copy(
                        listening = false,
                        error = "I didn't hear that. Try again, and say the whole sentence.",
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

    fun record() {
        val current = _state.value
        if (current.listening || current.complete) return
        _state.value = current.copy(listening = true, error = null)
        voice.startCommand()
    }

    fun stopListening() {
        voice.stop()
        _state.value = _state.value.copy(listening = false)
    }

    private fun onRecorded(event: VoiceEngine.Event.Transcript) {
        val current = _state.value
        if (!current.listening) return

        val print = event.voiceprint
        if (print == null) {
            _state.value = current.copy(
                listening = false,
                partial = "",
                error = "That was too short to get a voiceprint from. " +
                    "Say the whole sentence at a normal pace.",
            )
            return
        }
        if (print.frames < VoiceEngine.MIN_VOICEPRINT_FRAMES) {
            _state.value = current.copy(
                listening = false,
                partial = "",
                error = "Only ${print.frames / 100f}s of speech — a bit more, please.",
            )
            return
        }

        collected.add(print.vector)
        Log.i(TAG, "sample ${collected.size}: ${print.frames} frames, dim ${print.vector.size}")

        _state.value = current.copy(
            listening = false,
            partial = "",
            error = null,
            index = (current.index + 1).coerceAtMost(prompts.lastIndex),
            samples = current.samples + Sample(print.frames, event.text),
        )

        if (_state.value.complete) save()
    }

    private fun save() {
        profile.enroll(collected.toList())
        _state.value = _state.value.copy(
            saved = true,
            spread = profile.selfConsistency(),
            enrolledCount = profile.sampleCount,
        )
        // A voice profile says nothing about which words are in the lexicon, but the
        // recogniser bias list is cheap to rebuild and this is a natural moment.
        container.refreshBias()
    }

    fun setEnforce(value: Boolean) {
        profile.enforce = value
        _state.value = _state.value.copy(enforce = value)
    }

    fun clear() {
        profile.clear()
        collected.clear()
        _state.value = State(enforce = false, enrolledCount = 0)
    }

    fun restart() {
        collected.clear()
        _state.value = _state.value.copy(
            index = 0,
            samples = emptyList(),
            saved = false,
            error = null,
        )
    }

    fun release() {
        // Deliberately no voice.stop() before handing back. Starting the hotword
        // service goes through the engine's own serialised restart, which cancels
        // this screen's capture and brings up the wake-word one as a single
        // operation. Stopping separately raced that restart and won, leaving the
        // wake word dead after every visit to this screen.
        // resume(), not start(). This runs from onStop, by which point the app is
        // in the background — and a microphone-type foreground service cannot be
        // started from there on Android 14+. Doing so threw SecurityException and
        // killed the process every time a session ended. The service never stopped;
        // it only needs its engine pointed back at the wake word.
        if (container.prefs.hotwordEnabled) {
            HotwordService.resume(getApplication())
        } else {
            voice.stop()
        }
    }

    override fun onCleared() {
        release()
        super.onCleared()
    }
}
