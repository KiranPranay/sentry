package com.sentry.service

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class SentrySessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = SentrySession(this)
}
