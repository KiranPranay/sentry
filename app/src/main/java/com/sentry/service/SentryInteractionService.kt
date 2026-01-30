package com.sentry.service

import android.service.voice.VoiceInteractionService
import kotlinx.coroutines.launch

class SentryInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Initialize Gemma 3 Singleton (Coroutine scope needed)
        // For simplicity in this skeleton, we'll launch a thread or use a scope if available.
        // VoiceInteractionService doesn't provide a scope by default, using simple Thread for now or GlobalScope (prototype)
        // A better approach is to use a properly scoped coroutine, but for this task:
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            com.sentry.logic.SentryBrain.initialize(applicationContext)
        }
    }
}
