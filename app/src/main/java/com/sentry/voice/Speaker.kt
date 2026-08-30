package com.sentry.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sentry's voice.
 *
 * Two things this does that the naive version does not: it lets a caller *await* the
 * end of an utterance, so the microphone reopens the instant Sentry stops talking
 * rather than after a guessed delay; and it takes transient audio focus, so music
 * ducks instead of playing over the answer.
 */
class Speaker(context: Context) {

    private companion object {
        const val TAG = "Sentry/Speaker"

        /** No utterance should outlive this; a stuck engine must not wedge the mic. */
        const val MAX_UTTERANCE_MS = 30_000L
    }

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    @Volatile
    private var ready = false

    /**
     * The text currently being spoken, or null.
     *
     * The microphone stays open while Sentry talks so the user can interrupt, which
     * means the recogniser also hears Sentry. Knowing exactly what we are saying lets
     * us throw those words away without needing echo cancellation to be perfect.
     */
    @Volatile
    var currentText: String? = null
        private set

    private var focusRequest: AudioFocusRequest? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val tts = TextToSpeech(appContext) { status ->
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "text to speech failed to initialise")
            return@TextToSpeech
        }
        ready = true
    }.apply {
        setAudioAttributes(attributes)
        language = Locale.getDefault().takeIf { isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
            ?: Locale.US
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
            }

            override fun onDone(utteranceId: String?) = finish(utteranceId)

            @Deprecated("Superseded by onError(String, int)")
            override fun onError(utteranceId: String?) = finish(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)

            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
    }

    private fun finish(utteranceId: String?) {
        utteranceId?.let { pending.remove(it)?.complete(Unit) }
        if (pending.isEmpty()) {
            _speaking.value = false
            currentText = null
            abandonFocus()
        }
    }

    /**
     * Say something, and suspend until it has been said.
     *
     * Returns immediately for blank text so callers do not have to special-case the
     * replies that are deliberately silent.
     *
     * Pass [flush] false to queue after whatever is already speaking, which is how a
     * streamed answer is spoken sentence by sentence without cutting itself off.
     */
    suspend fun say(text: String, flush: Boolean = true) {
        if (text.isBlank()) return
        if (!ready) {
            Log.w(TAG, "not ready; dropping: $text")
            return
        }

        requestFocus()

        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        currentText = text
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = tts.speak(text, queueMode, params, id)
        if (result != TextToSpeech.SUCCESS) {
            pending.remove(id)
            abandonFocus()
            return
        }

        // A timeout rather than an unbounded wait: some engines never report done,
        // and a voice assistant that stops listening forever is worse than one that
        // cuts a sentence short.
        withTimeoutOrNull(MAX_UTTERANCE_MS) { done.await() }
        pending.remove(id)
    }

    fun stop() {
        runCatching { tts.stop() }
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
        _speaking.value = false
        currentText = null
        abandonFocus()
    }

    private fun requestFocus() {
        val manager = audio ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    private fun abandonFocus() {
        val manager = audio ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
    }

    fun close() {
        stop()
        runCatching { tts.shutdown() }
    }
}
