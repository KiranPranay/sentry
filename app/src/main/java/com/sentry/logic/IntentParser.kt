package com.sentry.logic

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

// Supported Intent Types
enum class SentryAction {
    SET_ALARM, MAKE_CALL, PLAY_MUSIC, CHAT, UNKNOWN, ERROR
}

// Base Intent
sealed class SentryIntent(val action: SentryAction)

// Specific Intents
data class AlarmIntent(val hour: Int, val minute: Int) : SentryIntent(SentryAction.SET_ALARM)
data class CallIntent(val contactName: String?, val selectionIndex: Int? = null) : SentryIntent(SentryAction.MAKE_CALL)
data class MusicIntent(val query: String) : SentryIntent(SentryAction.PLAY_MUSIC)
data class ChatIntent(val text: String) : SentryIntent(SentryAction.CHAT)
data class UnknownIntent(val reason: String) : SentryIntent(SentryAction.UNKNOWN)
data class ErrorIntent(val message: String) : SentryIntent(SentryAction.ERROR)

// Raw JSON Container
data class RawIntent(
    val action: String?,
    val hour: Int?,
    val minute: Int?,
    val contactName: String?,
    val selectionIndex: Int?,
    val musicQuery: String?,
    val text: String?, // For Chat
    val reason: String?
)

object IntentParser {
    private val gson = Gson()

    fun parse(llmOutput: String): SentryIntent {
        try {
            // Robust JSON extraction: Find first { and last }
            val firstBrace = llmOutput.indexOf('{')
            val lastBrace = llmOutput.lastIndexOf('}')
            
            // Log for debugging
            android.util.Log.d("IntentParser", "Raw Output: $llmOutput")

            val jsonString = if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
                llmOutput.substring(firstBrace, lastBrace + 1)
            } else {
                // If no JSON found, treat the whole response as a conversational reply (CHAT intent)
                // Filter out empty responses
                if (llmOutput.isBlank()) return ErrorIntent("Empty Response")
                return ChatIntent(llmOutput.trim())
            }
            
            val raw = gson.fromJson(jsonString, RawIntent::class.java) ?: return ErrorIntent("Empty JSON")

            return when (raw.action) {
                "SET_ALARM" -> {
                    if (raw.hour != null && raw.minute != null) {
                        AlarmIntent(raw.hour, raw.minute)
                    } else {
                        ErrorIntent("Missing time for alarm")
                    }
                }
                "MAKE_CALL" -> {
                    if (raw.selectionIndex != null) {
                         CallIntent(null, raw.selectionIndex)
                    } else if (!raw.contactName.isNullOrBlank()) {
                        CallIntent(raw.contactName, null)
                    } else {
                        ErrorIntent("Missing contact name or selection index")
                    }
                }
                "PLAY_MUSIC" -> {
                    if (!raw.musicQuery.isNullOrBlank()) {
                        MusicIntent(raw.musicQuery)
                    } else {
                        ErrorIntent("Missing music query")
                    }
                }
                "CHAT" -> {
                    if (!raw.text.isNullOrBlank()) {
                        ChatIntent(raw.text)
                    } else {
                        ErrorIntent("Empty chat response")
                    }
                }
                else -> UnknownIntent(raw.reason ?: "Unknown action")
            }
        } catch (e: JsonSyntaxException) {
            return ErrorIntent("Malformed JSON: ${e.message} | Raw: $llmOutput")
        } catch (e: Exception) {
            return ErrorIntent("Parsing Error: ${e.message}")
        }
    }
}
