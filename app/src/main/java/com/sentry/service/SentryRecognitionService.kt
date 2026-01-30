package com.sentry.service

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import com.sentry.logic.SentryBrain
import com.sentry.logic.SkillsRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.IOException

class SentryRecognitionService : RecognitionService() {

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    
    // Keep track of the current client callback
    private var currentCallback: Callback? = null

    override fun onCreate() {
        super.onCreate()
        loadModel()
    }

    private fun loadModel() {
        Log.d("SentryRecognition", "Starting Vosk Unpack (Manual)...")
        scope.launch(Dispatchers.IO) {
            try {
                val assetPath = "model-en-us"
                val targetDir = java.io.File(getExternalFilesDir(null), "model-en-us")
                
                // Simple sync: If target doesn't exist or is empty, copy.
                // For dev speed, we assume cleanliness, or overwrite.
                if (!targetDir.exists()) {
                     Log.d("SentryRecognition", "Unpacking model to: ${targetDir.absolutePath}")
                     copyAssets(assetPath, targetDir)
                } else {
                     Log.d("SentryRecognition", "Model already exists at: ${targetDir.absolutePath}")
                }
                
                // Create Model on Main Thread (or safe thread)
                withContext(Dispatchers.Main) {
                    try {
                        model = Model(targetDir.absolutePath)
                        Log.d("SentryRecognition", "Model Loaded Successfully!")
                        // Notify listener if waiting? 
                        // If onStartListening was called early, we might need to kick it.
                    } catch (e: Exception) {
                        Log.e("SentryRecognition", "Failed to init Model", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("SentryRecognition", "Manual Unpack Failed", e)
            }
        }
    }

    private fun copyAssets(path: String, outPath: java.io.File) {
        val assets = this.assets
        val list = assets.list(path) ?: return
        
        if (list.isEmpty()) {
            // It's a file
            // Make sure parent dir exists
            outPath.parentFile?.mkdirs()
            try {
                assets.open(path).use { input ->
                    java.io.FileOutputStream(outPath).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: IOException) {
                // If it's a directory that list() returned empty for? Rare in assets.
            }
        } else {
            // It's a directory
            outPath.mkdirs()
            for (file in list) {
                copyAssets("$path/$file", java.io.File(outPath, file))
            }
        }
    }

    private fun listAssets(assets: android.content.res.AssetManager, path: String) {
        try {
            val list = assets.list(path)
            if (list != null && list.isNotEmpty()) {
                for (file in list) {
                    val newPath = if (path.isEmpty()) file else "$path/$file"
                    Log.d("SentryAssets", "Found: $newPath")
                    listAssets(assets, newPath) // Recurse
                }
            }
        } catch (e: IOException) {}
    }

    override fun onStartListening(intent: Intent?, listener: Callback?) {
        Log.d("Sentry", "onStartListening Called")
        currentCallback = listener
        
        if (model == null) {
            Log.w("Sentry", "Model not ready")
            currentCallback?.error(SpeechRecognizer.ERROR_SERVER)
            return
        }

        // STOP existing service if running, to prevent stuck states (Error 10/8)
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            Log.e("Sentry", "Error stopping previous service", e)
        }
        speechService = null

        try {
            val recognizer = Recognizer(model, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(VoskBridge())
            
            Log.d("Sentry", "SpeechService started")
            currentCallback?.readyForSpeech(Bundle.EMPTY)
        } catch (e: Exception) {
            Log.e("Sentry", "Failed to init SpeechService", e)
            currentCallback?.error(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    override fun onStopListening(listener: Callback?) {
        Log.d("Sentry", "onStopListening")
        try {
            speechService?.stop()
        } catch (e: Exception) {
            Log.e("Sentry", "Error stopping service", e)
        }
    }

    override fun onCancel(listener: Callback?) {
        Log.d("Sentry", "onCancel")
        try {
            speechService?.cancel()
        } catch (e: Exception) {
             Log.e("Sentry", "Error cancelling service", e)
        }
        currentCallback = null // Detach
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.shutdown()
        speechService = null
    }

    // Bridge Vosk events to Android RecognitionCallback
    inner class VoskBridge : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            // Optional: Send partials if you want UI updates
             val bundle = Bundle().apply {
                 putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(parseText(hypothesis)))
             }
             currentCallback?.partialResults(bundle)
        }

        override fun onResult(hypothesis: String?) {
            val text = parseText(hypothesis)
            Log.d("Sentry", "Vosk Result: $text")
            
            if (text.isNotBlank()) {
                // 1. Notify Verification/Session that speech ended
                val bundle = Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                }
                currentCallback?.results(bundle)
                
                // 2. Process logic REMOVED: Moved to SentrySession to handle Permissions/UI Context
                // scope.launch { ... }
            } else {
                // Empty result usually means silence or noise
                // We don't error, just finish? Or wait? 
                // Vosk sends onResult often. If it's final result time, we should probably close.
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            val text = parseText(hypothesis)
            Log.d("Sentry", "Vosk Final Result: $text")
            if (text.isNotBlank()) {
                 val bundle = Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                }
                currentCallback?.results(bundle)
                
                // Logic moved to Session
            }
            // Ensure we stop recording to reset state
            speechService?.stop()
            currentCallback = null
        }

        override fun onError(exception: Exception?) {
            Log.e("Sentry", "Vosk Internal Error", exception)
            currentCallback?.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        }

        override fun onTimeout() {
            currentCallback?.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        }

        private fun parseText(json: String?): String {
            if (json == null) return ""
            return try {
                // {"text" : "hello world"}
                val start = json.indexOf(": \"") + 3
                val end = json.lastIndexOf("\"")
                if (start in 3 until end) json.substring(start, end) else ""
            } catch (e: Exception) { "" }
        }
    }
}
