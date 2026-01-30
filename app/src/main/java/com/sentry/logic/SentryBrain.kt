package com.sentry.logic

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SentryBrain {
    private const val TAG = "SentryBrain"
    // Updated to match the specific LiteRT community file (1B Model)
    private const val MODEL_NAME = "Gemma3-1B-IT_multi-prefill-seq_q8_ekv1280.task"
    
    private var llmInference: LlmInference? = null
    
    // Ultra-Simple Prompt for Nano Model (270M)
    // Avoid few-shot examples as they cause regurgitation in this specific quantized model.
    // Ultra-Simple Prompt for Nano Model (270M)
    // Focused PURELY on Chat for now (User request: "1st be a good Ai chatbot")
    // We removed commands to prevent "Setting alarm" hallucinations.
    private const val SYSTEM_PROMPT = """You are Sentry, a helpful assistant.
    Answer the user's question concisely and accurately in plain text.
    Do not output JSON.
    """

    // Flag to bypass native crash until we find a compatible model version
    private const val USE_MOCK_BRAIN = false

    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        if (USE_MOCK_BRAIN) {
            Log.i(TAG, "Brain initialized in MOCK MODE (Safety Bypass).")
            return@withContext
        }
        if (llmInference != null) return@withContext

        Log.i(TAG, "Initializing Gemma Brain...")
        
        // Fix for SIGABRT in XNNPack (Error: Cannot reserve space in a cache that isn't building)
        // We must purge the cache to force a fresh build.
        try {
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".xnnpack_cache")) {
                    Log.w(TAG, "Purging XNNPack cache: ${file.name}")
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
        }

        try {
            // 1. Try file from internal storage (robust)
            val modelFile = File(context.filesDir, MODEL_NAME)
            if (!modelFile.exists()) {
                Log.d(TAG, "Model not in filesDir, copying from assets...")
                context.assets.open(MODEL_NAME).use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Model copied to: ${modelFile.absolutePath}")
            }

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            Log.i(TAG, "Gemma initialized successfully! Brain is Online.")
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Brain Init Failed.", e)
            Log.e(TAG, "Ensure '$MODEL_NAME' is in app/src/main/assets/")
        }
    }

    // Session Memory
    private val history = java.util.Collections.synchronizedList(mutableListOf<String>())

    fun clearSession() {
        history.clear()
        Log.i(TAG, "Session Memory Cleared.")
    }

    suspend fun processUserRequest(userText: String): SentryIntent = withContext(Dispatchers.IO) {
        if (USE_MOCK_BRAIN || llmInference == null) {
             return@withContext IntentParser.parse("""{"action": "UNKNOWN", "reason": "Mock Mode"}""")
        }

        Log.d(TAG, "Processing Request: $userText")
        
        // Build Prompt with History
        // IMPORTANT: Use specific tokens to distinguish roles clearly for the model
        val historyContext = synchronized(history) {
            if (history.isEmpty()) "" else "\n" + history.joinToString("\n")
        }
        
        // Construct prompt with clear turn indicators
        // Format: System Instructions -> History -> Current Turn
        val prompt = "$SYSTEM_PROMPT\n$historyContext\nUser: $userText\nModel:"
        
        try {
            val fullResponse = llmInference?.generateResponse(prompt) ?: ""
            Log.d(TAG, "Full LLM Output: $fullResponse")
            
            // Robust Cleaner
            var cleanResponse = fullResponse.trim()
            
            // 1. Unescape literal newlines (Common issue with some tokenizers)
            cleanResponse = cleanResponse.replace("\\n", "\n")
            
            // 2. Strip the prompt echo if the model repeats it
            if (cleanResponse.startsWith("User:")) {
                 val modelIndex = cleanResponse.indexOf("Model:")
                 if (modelIndex != -1) {
                     cleanResponse = cleanResponse.substring(modelIndex + 6).trim()
                 }
            }
            
            // 3. Remove prefixes like "Sentry:" or "Model:"
            if (cleanResponse.startsWith("Sentry:", ignoreCase = true)) {
                cleanResponse = cleanResponse.substring(7).trim()
            }
            if (cleanResponse.startsWith("Model:", ignoreCase = true)) {
                cleanResponse = cleanResponse.substring(6).trim()
            }
            
            // 4. Remove any subsequent "User:" turns
            if (cleanResponse.contains("User:")) {
                cleanResponse = cleanResponse.substringBefore("User:").trim()
            }
            
            Log.d(TAG, "Truncated Output: $cleanResponse")
            
            // Update History (Limit to last 10 turns to avoid context overflow)
            synchronized(history) {
                history.add("User: $userText")
                history.add("Model: $cleanResponse")
                if (history.size > 20) {
                    history.removeAt(0)
                    history.removeAt(0)
                }
            }
            
            return@withContext IntentParser.parse(cleanResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Inference Failed", e)
            return@withContext ErrorIntent("Inference Failed: ${e.message}")
        }
    }
}
