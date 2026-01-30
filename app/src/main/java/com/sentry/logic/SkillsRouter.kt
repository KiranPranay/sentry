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
            is AlarmIntent -> { 
                lastCandidates = emptyList() 
                setAlarm(session, intent.hour, intent.minute) 
            }
            // Updated to handle both name and index
            is CallIntent -> {
                if (intent.selectionIndex != null) {
                    handleSelectionCall(session, intent.selectionIndex)
                } else if (intent.contactName != null) {
                    // Stale cache should be cleared if we are starting a NEW name search
                    lastCandidates = emptyList()
                    makeCall(session, intent.contactName)
                } else {
                    prepareForSpeech(session, "I don't know who to call.")
                }
            }
            is MusicIntent -> {
                lastCandidates = emptyList()
                playMusic(session, intent.query)
            }
            is ChatIntent -> {
                lastCandidates = emptyList()
                prepareForSpeech(session, intent.text)
            }
            is UnknownIntent -> prepareForSpeech(session, intent.reason)
            is ErrorIntent -> prepareForSpeech(session, "Error: ${intent.message}")
        }
    }

    // Cache to store the last list of ambiguous contacts found
    private var lastCandidates: List<ContactMatch> = emptyList()

    private fun handleSelectionCall(session: android.service.voice.VoiceInteractionSession, index: Int) {
        if (lastCandidates.isEmpty()) {
            prepareForSpeech(session, "I don't remember the contact list. Who do you want to call?")
            return
        }
        // User says "1st one" (index 1) -> List index 0
        val listIndex = index - 1
        if (listIndex in lastCandidates.indices) {
            val match = lastCandidates[listIndex]
            val i = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${match.number}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            safeStartActivity(session, i, "Calling ${match.name}")
        } else {
            prepareForSpeech(session, "That number isn't on the list.")
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

    private data class ContactMatch(val name: String, val number: String)

    private fun makeCall(session: android.service.voice.VoiceInteractionSession, contactName: String) {
        val context = session.context
        // Check permissions
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            prepareForSpeech(session, "I need permission to access contacts. Tap to grant.")
            val permIntent = Intent(context, com.sentry.ui.PermissionsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permIntent)
            return
        }

        val candidates = resolveContacts(context, contactName)
        
        if (candidates.isEmpty()) {
            prepareForSpeech(session, "I couldn't find anyone named $contactName.")
        } else if (candidates.size == 1) {
            val match = candidates[0]
            val i = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${match.number}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            safeStartActivity(session, i, "Calling ${match.name}")
        } else {
            // Ambiguous: Cache and Speak
            lastCandidates = candidates.take(5) // Store up to 5
            
            val options = lastCandidates.mapIndexed { index, contactMatch ->
                "${index + 1}. ${contactMatch.name}"
            }.joinToString(", ")
            prepareForSpeech(session, "I found multiple contacts: $options. Who do you want to call?")
        }
    }

    private fun resolveContacts(context: Context, nameQuery: String): List<ContactMatch> {
        val matches = mutableListOf<ContactMatch>()
        val cursor = context.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$nameQuery%"),
            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )
        
        cursor?.use {
            val nameIdx = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
            
            while (it.moveToNext()) {
                if (nameIdx != -1 && numIdx != -1) {
                    val accName = it.getString(nameIdx)
                    val accNum = it.getString(numIdx)
                    if (accName != null && accNum != null) {
                       matches.add(ContactMatch(accName, accNum))
                    }
                }
            }
        }
        
        // Smart Filter Logic
        // 1. Exact Match Priority (Case Insensitive)
        val exactMatch = matches.find { it.name.equals(nameQuery, ignoreCase = true) }
        if (exactMatch != null) {
            return listOf(exactMatch)
        }
        
        // 2. Return unique matches
        return matches.distinctBy { it.name }
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
             
             // FEEDBACK LOOP: Tell the Brain what we just said, so it has context for the NEXT turn.
             // e.g. If we said "Who do you want to call?", the Brain needs to know that.
             SentryBrain.addToHistory("Model: $text")
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
