package com.sentry.service

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import com.sentry.sentry
import com.sentry.voice.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Offline speech recognition, exposed to the rest of the system.
 *
 * A `VoiceInteractionService` is rejected at parse time unless it names a
 * recognition service, so this has to exist. Rather than make it a stub, it hands
 * out the same offline Vosk pipeline Sentry uses itself — which means anything on
 * the device that asks Sentry to transcribe gets recognition that works with no
 * network at all.
 */
class SentryRecognitionService : RecognitionService() {

    private companion object {
        const val TAG = "Sentry/Recognition"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val callback = listener ?: return
        val voice = sentry.voice

        job?.cancel()
        job = scope.launch {
            if (!voice.prepare()) {
                report(callback) { it.error(SpeechRecognizer.ERROR_SERVER) }
                return@launch
            }
            if (!voice.hasMicPermission()) {
                report(callback) { it.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
                return@launch
            }

            report(callback) { it.readyForSpeech(Bundle.EMPTY) }
            voice.startCommand()

            voice.events.collect { event ->
                when (event) {
                    is VoiceEngine.Event.Transcript -> {
                        report(callback) {
                            it.endOfSpeech()
                            it.results(bundleOf(event.text))
                        }
                        return@collect
                    }

                    is VoiceEngine.Event.NoSpeech ->
                        report(callback) { it.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }

                    is VoiceEngine.Event.Failed -> {
                        Log.w(TAG, event.reason)
                        report(callback) { it.error(SpeechRecognizer.ERROR_AUDIO) }
                    }

                    else -> Unit
                }
            }
        }

        // Partial results are a separate stream, so they get their own collector.
        scope.launch {
            voice.partial.collect { text ->
                if (text.isNotBlank()) report(callback) { it.partialResults(bundleOf(text)) }
            }
        }
    }

    override fun onStopListening(listener: Callback?) {
        sentry.voice.stop()
    }

    override fun onCancel(listener: Callback?) {
        job?.cancel()
        job = null
        sentry.voice.stop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun bundleOf(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    /** A dead client throws from every callback; that is not our failure to report. */
    private inline fun report(callback: Callback, block: (Callback) -> Unit) {
        runCatching { block(callback) }
            .onFailure { Log.d(TAG, "recognition client went away", it) }
    }
}
