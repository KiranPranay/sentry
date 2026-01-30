package com.sentry.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SentrySession(context: Context) : VoiceInteractionSession(context) {

    private var recognizer: SpeechRecognizer? = null
    private var statusView: TextView? = null
    private var scrollView: android.widget.ScrollView? = null
    private var micButton: android.widget.ImageButton? = null
    private var inputText: android.widget.EditText? = null
    private var sendButton: android.widget.ImageButton? = null
    private var isListening = false

    override fun onCreate() {
        super.onCreate()
        val view = layoutInflater.inflate(com.sentry.R.layout.layout_voice_plate, null)
        setContentView(view)
        statusView = view.findViewById(com.sentry.R.id.sentry_text)
        scrollView = view.findViewById(com.sentry.R.id.scroll_view)
        micButton = view.findViewById(com.sentry.R.id.btn_mic)
        inputText = view.findViewById(com.sentry.R.id.input_text)
        sendButton = view.findViewById(com.sentry.R.id.btn_send)

        sendButton?.setOnClickListener {
            val text = inputText?.text?.toString() ?: ""
            if (text.isNotBlank()) {
                inputText?.setText("")
                addMessage("User (Text): $text")
                // Use IO scope or Main scope properly. 
                // Using Main scope like onResults does:
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val intent = com.sentry.logic.SentryBrain.processUserRequest(text)
                    com.sentry.logic.SkillsRouter.route(this@SentrySession, intent)
                }
            }
        }
        
        // Handle "Enter" on keyboard
        inputText?.setOnEditorActionListener { v, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendButton?.performClick()
                true
            } else {
                false
            }
        }
        
        micButton?.setOnClickListener {
            if (com.sentry.logic.FeedbackManager.isSpeaking) {
                addMessage("Wait for Sentry to finish speaking.")
                return@setOnClickListener
            }
            if (isListening) {
                stopListening()
            } else {
                startListening()
            }
        }

        com.sentry.logic.FeedbackManager.init(context)
        com.sentry.logic.FeedbackManager.setListener { speaking ->
             micButton?.post {
                 micButton?.isEnabled = !speaking
                 val color = if (speaking) android.graphics.Color.GRAY else (if (isListening) android.graphics.Color.RED else 0xFF00E5FF.toInt())
                 micButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                 if (speaking && isListening) {
                     stopListening() // Kill mic if TTS steals focus
                 }
             }
        }
        
        initRecognizer()
    }
    
    private fun initRecognizer() {
        if (recognizer != null) {
            recognizer?.destroy()
            recognizer = null
        }
        val component = ComponentName(context, SentryRecognitionService::class.java)
        recognizer = SpeechRecognizer.createSpeechRecognizer(context, component)
        recognizer?.setRecognitionListener(SentryRecognitionListener())
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d("SentrySession", "Session Shown.")
        // Start fresh session by clearing logic memory AND UI text
        com.sentry.logic.SentryBrain.clearSession()
        statusView?.text = "" 
        addMessage("--- Ready ---")
    }

    override fun onHide() {
        super.onHide()
        // Clear session on exit
        com.sentry.logic.SentryBrain.clearSession()
        stopListening()
    }
    
    private fun startListening() {
        if (isListening) return // Already running
        
        try {
            // Reset to ensure clean state
            recognizer?.cancel()
            val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            recognizer?.startListening(intent)
            isListening = true
            micButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
            addMessage("Listening...")
        } catch (e: Exception) {
            addMessage("Error starting: ${e.message}")
            isListening = false
        }
    }
    
    fun stopListening() {
        try {
            recognizer?.stopListening()
            isListening = false
            micButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
        } catch (e: Exception) {
            // Ignored
        }
    }

    private fun updateStatus(text: String) {
        // Alias for addMessage used by legacy calls
        addMessage(text)
    }

    fun addMessage(text: String) {
        statusView?.post {
            statusView?.append("\n$text")
            // Auto-scroll to bottom
            scrollView?.post {
                scrollView?.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    inner class SentryRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { Log.d("SentrySession", "onReadyForSpeech") }
        override fun onBeginningOfSpeech() { 
            Log.d("SentrySession", "onBeginningOfSpeech") 
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { 
            Log.d("SentrySession", "onEndOfSpeech") 
            isListening = false
            micButton?.post {
                 micButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
            }
        }
        override fun onError(error: Int) {
            Log.e("SentrySession", "Speech Error: $error")
            addMessage("Speech Error ($error)")
            isListening = false
            micButton?.post {
                 micButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
            }
            
            // Re-init on critical errors, but avoid loops
            if (!isListening && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER)) {
                 // Only restart if we didn't intentionally stop?
                 // Actually, Error 5 often happens if we call start while it's busy.
                 // A restart is aggressive but might be needed.
                 // statusView?.post { initRecognizer() } 
                 // Let's NOT auto-restart on error for now to prevent loops vs crashes.
                 // User can tap Mic to restart.
                 addMessage("Tap Mic to retry.")
            }
        }
        override fun onResults(results: Bundle?) {
            Log.d("SentrySession", "onResults received")
            isListening = false
            micButton?.post {
                 micButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
            }
            
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""

            if (text.isNotBlank()) {
                addMessage("User: $text")
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val intent = com.sentry.logic.SentryBrain.processUserRequest(text)
                    com.sentry.logic.SkillsRouter.route(this@SentrySession, intent)
                }
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
