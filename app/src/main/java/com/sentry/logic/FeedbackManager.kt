package com.sentry.logic

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

object FeedbackManager : TextToSpeech.OnInitListener {

    private var listener: ((Boolean) -> Unit)? = null
    var isSpeaking = false
        private set

    private var tts: TextToSpeech? = null
    private var isReady = false

    fun init(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context, this)
        }
    }

    fun setListener(l: (Boolean) -> Unit) {
        listener = l
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("FeedbackManager", "Language not supported")
            } else {
                isReady = true
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                        listener?.invoke(true)
                    }

                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        listener?.invoke(false)
                    }

                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        listener?.invoke(false)
                    }
                })
            }
        } else {
            Log.e("FeedbackManager", "Init initialization failed")
        }
    }

    fun speak(text: String) {
        if (isReady) {
            val params = android.os.Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, java.util.UUID.randomUUID().toString())
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "SentryTTS")
        } else {
            Log.w("FeedbackManager", "TTS not ready, cannot speak: $text")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
