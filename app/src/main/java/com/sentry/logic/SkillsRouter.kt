package com.sentry.logic

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.widget.Toast
import android.util.Log

object SkillsRouter {

    fun route(session: android.service.voice.VoiceInteractionSession, intent: SentryIntent) {
        val context = session.context
        when (intent) {
            is AlarmIntent -> setAlarm(session, intent.hour, intent.minute)
            is CallIntent -> makeCall(session, intent.contactName)
            is MusicIntent -> playMusic(session, intent.query)
            is ChatIntent -> prepareForSpeech(session, intent.text)
            is UnknownIntent -> prepareForSpeech(session, intent.reason)
            is ErrorIntent -> prepareForSpeech(session, "Error: ${intent.message}")
        }
    }

    private fun setAlarm(session: android.service.voice.VoiceInteractionSession, hour: Int, minute: Int) {
        // Handle Android 12+ Exact Alarm Permission
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val alarmManager = session.context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                 prepareForSpeech(session, "I need permission to set exact alarms. Tap to grant.")
                 val permIntent = Intent(session.context, com.sentry.ui.PermissionsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                 }
                 session.context.startActivity(permIntent)
                 return
            }
        }

        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        if (i.resolveActivity(session.context.packageManager) != null) {
            safeStartActivity(session, i, "Setting alarm for $hour:$minute")
        } else {
            prepareForSpeech(session, "No Alarm app found on this device.")
        }
    }

    private fun makeCall(session: android.service.voice.VoiceInteractionSession, contactName: String) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(session.context, android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val i = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:1234567890")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            safeStartActivity(session, i, "Calling $contactName")
        } else {
            prepareForSpeech(session, "I need permission to make calls. Tap to grant.")
            // Launch our Permission Request UI
            val permIntent = Intent(session.context, com.sentry.ui.PermissionsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            session.context.startActivity(permIntent)
        }
    }

    private fun playMusic(session: android.service.voice.VoiceInteractionSession, query: String) {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        safeStartActivity(session, i, "Playing $query")
    }

    private fun speak(context: Context, text: String) {
        FeedbackManager.speak(text)
    }
    
    // Helper to control Session specific logic (UI logging + Mic control)
    private fun prepareForSpeech(session: android.service.voice.VoiceInteractionSession, text: String) {
        if (session is com.sentry.service.SentrySession) {
             session.stopListening() // CRITICAL: Stop mic BEFORE TTS starts to avoid echo
             session.addMessage("Sentry: $text")
        }
        speak(session.context, text)
    }

    private fun safeStartActivity(session: android.service.voice.VoiceInteractionSession, intent: Intent, feedback: String) {
        try {
            prepareForSpeech(session, feedback)
            session.context.startActivity(intent)
        } catch (e: Throwable) {
            val error = "Crash: ${e.message}"
            Log.e("SkillsRouter", "Action failed", e)
            if (session is com.sentry.service.SentrySession) {
                 session.addMessage(error)
                 // Also log cause if useful
                 e.cause?.let { session.addMessage("Cause: ${it.message}") }
            }
            speak(session.context, "I encountered a critical error.")
        }
    }
}
