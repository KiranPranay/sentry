package com.sentry.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SentrySession(context: Context) : VoiceInteractionSession(context) {

    private var recognizer: SpeechRecognizer? = null
    // New UI Components
    private var chatContainer: LinearLayout? = null
    private var scrollView: android.widget.ScrollView? = null
    private var micButton: com.google.android.material.floatingactionbutton.FloatingActionButton? = null
    private var inputText: com.google.android.material.textfield.TextInputEditText? = null
    private var loadingIndicator: com.google.android.material.progressindicator.LinearProgressIndicator? = null
    
    private var isListening = false

    override fun onCreate() {
        super.onCreate()
        // Ensure Theme is applied using a Wrapper
        val themeContext = android.view.ContextThemeWrapper(context, com.sentry.R.style.Theme_Sentry)
        val inflater = android.view.LayoutInflater.from(themeContext)
        val view = inflater.inflate(com.sentry.R.layout.layout_voice_plate, null)
        setContentView(view)
        
        chatContainer = view.findViewById(com.sentry.R.id.chat_container)
        scrollView = view.findViewById(com.sentry.R.id.scroll_view)
        micButton = view.findViewById(com.sentry.R.id.btn_mic)
        inputText = view.findViewById(com.sentry.R.id.input_text)
        loadingIndicator = view.findViewById(com.sentry.R.id.loading_indicator)

        // Handle "Enter" on keyboard (Send)
        inputText?.setOnEditorActionListener { v, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                // Send Logic
                val text = inputText?.text?.toString() ?: ""
                if (text.isNotBlank()) {
                    inputText?.setText("")
                    addBubble(text, isUser = true)
                    processRequest(text)
                }
                true
            } else {
                false
            }
        }
        
        micButton?.setOnClickListener {
            if (com.sentry.logic.FeedbackManager.isSpeaking) {
                addBubble("Wait for Sentry to finish speaking.", isUser = false)
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
                 if (speaking) {
                     micButton?.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                 } else {
                     micButton?.setImageResource(android.R.drawable.ic_btn_speak_now)
                 }
                 
                 if (speaking && isListening) {
                     stopListening() // Kill mic if TTS steals focus
                 }
             }
        }
        
        initRecognizer()
    }
    
    private fun processRequest(text: String) {
        showLoading(true)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val intent = com.sentry.logic.SentryBrain.processUserRequest(text)
            showLoading(false)
            com.sentry.logic.SkillsRouter.route(this@SentrySession, intent)
        }
    }
    
    private fun showLoading(isLoading: Boolean) {
        loadingIndicator?.post {
            loadingIndicator?.visibility = if (isLoading) android.view.View.VISIBLE else android.view.View.INVISIBLE
        }
    }
    
    // Bubble Injection Logic
    fun addMessage(text: String) {
        // Compatibility function for Router
        addBubble(text, isUser = false)
    }

    private fun addBubble(text: String, isUser: Boolean) {
        chatContainer?.post {
            val layoutId = if (isUser) com.sentry.R.layout.item_chat_user else com.sentry.R.layout.item_chat_bot
            val bubbleView = LayoutInflater.from(context).inflate(layoutId, chatContainer, false)
            val textView = bubbleView.findViewById<TextView>(com.sentry.R.id.text_message)
            textView.text = text
            chatContainer?.addView(bubbleView)
            
            // Auto-scroll
            scrollView?.post {
                scrollView?.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
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
        // Start fresh session logic
        com.sentry.logic.SentryBrain.clearSession()
        
        // Clear Chat UI
        chatContainer?.removeAllViews()
        addBubble("Ready.", isUser = false)
    }

    override fun onHide() {
        super.onHide()
        com.sentry.logic.SentryBrain.clearSession()
        stopListening()
    }
    
    private fun startListening() {
        if (isListening) return 
        try {
            recognizer?.cancel()
            val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            recognizer?.startListening(intent)
            isListening = true
            
            // UI Update: Pulse Mic
            micButton?.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFB00020.toInt()) // Red
            showLoading(true) // Show indicator to mean "Listening/Processing" state
        } catch (e: Exception) {
            addBubble("Error starting: ${e.message}", isUser = false)
            isListening = false
        }
    }
    
    fun stopListening() {
        try {
            recognizer?.stopListening()
            isListening = false
             // Reset UI
            micButton?.backgroundTintList = null // Default
            showLoading(false)
        } catch (e: Exception) {
            // Ignored
        }
    }

    inner class SentryRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { 
            isListening = false
            micButton?.backgroundTintList = null
            showLoading(true) // Keep loading while we process results
        }
        override fun onError(error: Int) {
            isListening = false
            micButton?.backgroundTintList = null
            showLoading(false)
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                 addBubble("Speech Error ($error)", isUser = false)
            }
        }
        override fun onResults(results: Bundle?) {
            isListening = false
            micButton?.backgroundTintList = null
            
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""

            if (text.isNotBlank()) {
                addBubble(text, isUser = true)
                processRequest(text)
            } else {
                showLoading(false)
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
