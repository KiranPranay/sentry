package com.sentry.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sentry.R
import com.sentry.sentry
import com.sentry.ui.AssistantActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Listens for "Sentry" whenever the phone is on.
 *
 * A foreground service is not a design choice — Android will not let an app hold the
 * microphone from the background for any length of time, and a service without a
 * visible notification is killed. So the notification is the price of a wake word
 * that works with the screen off, and the honest thing is to say so in it.
 *
 * The detector itself is deliberately cheap: [VoiceEngine] runs Vosk against a
 * two-word grammar, which is a small fraction of the work of full recognition.
 */
class HotwordService : Service() {

    companion object {
        private const val TAG = "Sentry/Hotword"
        private const val CHANNEL_ID = "sentry_hotword"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.sentry.hotword.START"
        const val ACTION_STOP = "com.sentry.hotword.STOP"

        fun start(context: Context) {
            val intent = Intent(context, HotwordService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, HotwordService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listenJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForegroundCompat()
        startListening()
        // Restart if the system kills us: a wake word that silently stops working
        // after a memory-pressure event is worse than one that was never enabled.
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Arm the detector. Called on every start command, not only the first.
     *
     * The re-arm matters as much as the arm: when the assistant screen closes it
     * hands the microphone back by starting this service again. Guarding the whole
     * method on "is the collector already running" meant that second call did
     * nothing, the engine stayed stopped, and the wake word silently worked exactly
     * once per boot. So the collector is created once and the *capture* is started
     * every time.
     */
    private fun startListening() {
        val voice = sentry.voice

        scope.launch {
            if (!voice.prepare()) {
                Log.e(TAG, "acoustic model unavailable; stopping")
                stopSelf()
                return@launch
            }
            voice.startHotword()
        }

        if (listenJob != null) return

        listenJob = scope.launch {
            voice.events.collect { event ->
                when (event) {
                    is VoiceEngine.Event.WakeWord -> onWakeWord()

                    is VoiceEngine.Event.Failed -> {
                        Log.w(TAG, "voice engine: ${event.reason}")
                        // Usually another app took the mic. Back off and try again
                        // rather than dying, since the mic normally comes back.
                        delay(3_000)
                        if (voice.mode.value == VoiceEngine.Mode.OFF) voice.startHotword()
                    }

                    else -> Unit
                }
            }
        }
    }

    /**
     * Hand off to the assistant UI.
     *
     * The service stops its own listening first: the activity takes the microphone
     * over for dictation, and two owners of one [VoiceEngine] would fight.
     */
    private fun onWakeWord() {
        Log.i(TAG, "wake word; opening the assistant")
        val intent = Intent(this, AssistantActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            putExtra(AssistantActivity.EXTRA_LISTEN_IMMEDIATELY, true)
            putExtra(AssistantActivity.EXTRA_SOURCE, "hotword")
        }
        startActivity(intent)
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        sentry.voice.stop()
    }

    override fun onDestroy() {
        stopListening()
        scope.cancel()
        super.onDestroy()
    }

    // --------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wake word",
            // Low, not default: this notification is a legal requirement, not news.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while Sentry is listening for its wake word."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AssistantActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, HotwordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sentry is listening")
            .setContentText("Say \"Sentry\" to wake it. Audio never leaves this phone.")
            .setSmallIcon(R.drawable.ic_sentry_notification)
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
