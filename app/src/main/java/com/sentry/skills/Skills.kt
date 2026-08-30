package com.sentry.skills

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.sentry.core.Chip
import com.sentry.core.ChipIcon
import com.sentry.core.Command
import com.sentry.core.MediaAction
import com.sentry.core.Panel
import com.sentry.core.Reply
import com.sentry.core.VolumeChange
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Everything Sentry can do that is not talking.
 *
 * Each branch returns a [Reply] rather than throwing, including the failures: a
 * missing permission is something to say out loud and offer to fix, not a stack
 * trace. Nothing here touches a language model.
 */
class Skills(private val context: Context) {

    private companion object {
        const val TAG = "Sentry/Skills"
    }

    private val contacts = Contacts(context)
    private val apps = Apps(context)

    /** What was last offered as a numbered list, so "the second one" can resolve. */
    private var pendingChoices: List<ContactMatch> = emptyList()

    /** What to do with a choice once it is made. */
    private var pendingAction: ((ContactMatch) -> Reply)? = null

    fun run(command: Command): Reply {
        // Any command that is not a selection invalidates a stale list; otherwise a
        // "second one" said minutes later would call whoever happened to be cached.
        if (command !is Command.Choose) {
            pendingChoices = emptyList()
            pendingAction = null
        }

        return runCatching { dispatch(command) }
            .getOrElse {
                Log.e(TAG, "skill failed for $command", it)
                Reply.error("That didn't work: ${it.message ?: "something went wrong"}")
            }
    }

    private fun dispatch(command: Command): Reply = when (command) {
        is Command.SetAlarm -> setAlarm(command)
        is Command.SetTimer -> setTimer(command)
        Command.ShowAlarms -> showClock(AlarmClock.ACTION_SHOW_ALARMS, "alarms")
        Command.ShowTimers -> showClock(AlarmClock.ACTION_SHOW_TIMERS, "timers")

        is Command.Call -> call(command.query)
        is Command.CallNumber -> placeCall(command.number, command.number)
        Command.AnswerCall -> answerCall()
        Command.HangUp -> hangUp()
        is Command.SendMessage -> sendMessage(command)

        is Command.PlayMusic -> playMusic(command.query)
        is Command.MediaControl -> mediaControl(command.action)

        is Command.Torch -> torch(command.on)
        is Command.Volume -> volume(command.change)
        is Command.Dnd -> dnd(command.on)
        Command.OpenCamera -> openCamera()
        Command.BatteryStatus -> battery()
        is Command.OpenPanel -> openPanel(command.panel)

        is Command.OpenApp -> openApp(command.name)
        is Command.Search -> search(command.query)
        is Command.Navigate -> navigate(command.destination)

        Command.TimeQuery -> Reply(
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Calendar.getInstance().time)
                .let { "It's $it." },
            Chip(ChipIcon.CLOCK, "Now"),
        )

        Command.DateQuery -> Reply(
            SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
                .format(Calendar.getInstance().time).let { "It's $it." },
            Chip(ChipIcon.CLOCK, "Today"),
        )

        is Command.Choose -> choose(command.index)
        Command.Stop -> Reply("")
        // Conversation never reaches here; the agent streams it from the model.
        is Command.Chat -> Reply("")
    }

    // ----------------------------------------------------------------- time

    private fun setAlarm(command: Command.SetAlarm): Reply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            if (alarms?.canScheduleExactAlarms() == false) {
                return needsSetting(
                    "I need permission to set exact alarms.",
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                )
            }
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, command.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
            command.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canResolve(intent)) return Reply.error("There's no clock app on this phone.")

        context.startActivity(preferSystemHandler(intent))
        val spoken = formatTime(command.hour, command.minute)
        return Reply("Alarm set for $spoken.", Chip(ChipIcon.ALARM, spoken))
    }

    private fun setTimer(command: Command.SetTimer): Reply {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, command.seconds)
            command.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canResolve(intent)) return Reply.error("There's no timer app on this phone.")

        context.startActivity(preferSystemHandler(intent))
        val spoken = formatDuration(command.seconds)
        return Reply("Timer set for $spoken.", Chip(ChipIcon.TIMER, spoken))
    }

    private fun showClock(action: String, what: String): Reply {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!canResolve(intent)) return Reply.error("There's no clock app on this phone.")
        context.startActivity(preferSystemHandler(intent))
        return Reply("Here are your $what.", Chip(ChipIcon.ALARM, what.replaceFirstChar { it.uppercase() }))
    }

    // ---------------------------------------------------------------- comms

    private fun call(query: String): Reply {
        if (!has(Manifest.permission.CALL_PHONE) || !contacts.hasPermission()) {
            return needsPermission("I need permission to see your contacts and place calls.")
        }

        val matches = contacts.find(query)
        return when {
            matches.isEmpty() -> Reply("I couldn't find anyone called $query.")
            matches.size == 1 -> placeCall(matches[0].number, matches[0].name)
            else -> {
                pendingChoices = matches
                pendingAction = { placeCall(it.number, it.name) }
                Reply.ask(
                    "I found ${matches.size} matches. Which one?",
                    matches.map { it.name },
                )
            }
        }
    }

    private fun placeCall(number: String, who: String): Reply {
        if (!has(Manifest.permission.CALL_PHONE)) {
            return needsPermission("I need permission to place calls.")
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return Reply("Calling $who.", Chip(ChipIcon.CALL, who))
    }

    private fun answerCall(): Reply {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Reply.error("This version of Android won't let me answer calls.")
        }
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return needsPermission("I need permission to answer calls.")
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: return Reply.error("I can't reach the phone service.")
        telecom.acceptRingingCall()
        return Reply("Answering.", Chip(ChipIcon.CALL, "Answered"))
    }

    private fun hangUp(): Reply {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return Reply.error("This version of Android won't let me end calls.")
        }
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return needsPermission("I need permission to end calls.")
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: return Reply.error("I can't reach the phone service.")
        val ended = telecom.endCall()
        return if (ended) Reply("Hung up.", Chip(ChipIcon.CALL, "Ended"))
        else Reply("There's no call to end.")
    }

    private fun sendMessage(command: Command.SendMessage): Reply {
        if (!contacts.hasPermission()) {
            return needsPermission("I need permission to see your contacts.")
        }
        val matches = contacts.find(command.query)
        return when {
            matches.isEmpty() -> Reply("I couldn't find anyone called ${command.query}.")
            matches.size == 1 -> compose(matches[0], command.body)
            else -> {
                pendingChoices = matches
                pendingAction = { compose(it, command.body) }
                Reply.ask(
                    "I found ${matches.size} matches. Which one?",
                    matches.map { it.name },
                )
            }
        }
    }

    /**
     * Opens the SMS app with the message ready to send rather than sending it.
     *
     * Sending silently would need SEND_SMS and would mean a misheard word goes out
     * to a real person with no chance to catch it. One tap is the right price.
     */
    private fun compose(match: ContactMatch, body: String?): Reply {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${match.number}")).apply {
            body?.let { putExtra("sms_body", it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canResolve(intent)) return Reply.error("There's no messaging app on this phone.")
        context.startActivity(intent)
        return if (body != null) {
            Reply("Ready to send to ${match.name}. Tap send.", Chip(ChipIcon.MESSAGE, match.name))
        } else {
            Reply("Opening a message to ${match.name}.", Chip(ChipIcon.MESSAGE, match.name))
        }
    }

    // ---------------------------------------------------------------- media

    private fun playMusic(query: String?): Reply {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query ?: "")
            if (query == null) {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canResolve(intent)) {
            // Nothing handles the media search intent; fall back to whatever music
            // app exists rather than telling the user "no".
            return Reply.error("I couldn't find a music app that can search.")
        }
        context.startActivity(intent)
        return if (query == null) {
            Reply("Playing music.", Chip(ChipIcon.MUSIC, "Music"))
        } else {
            Reply("Playing $query.", Chip(ChipIcon.MUSIC, query))
        }
    }

    private fun mediaControl(action: MediaAction): Reply {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return Reply.error("I can't reach the audio service.")

        val keyCode = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        // Both halves of the key press, or the app on the other end waits forever.
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))

        val said = when (action) {
            MediaAction.PLAY -> "Playing."
            MediaAction.PAUSE -> "Paused."
            MediaAction.NEXT -> "Next."
            MediaAction.PREVIOUS -> "Going back."
        }
        return Reply(said, Chip(ChipIcon.MUSIC, said.trimEnd('.')))
    }

    // --------------------------------------------------------------- device

    private fun torch(on: Boolean): Reply {
        val cameras = context.getSystemService(CameraManager::class.java)
            ?: return Reply.error("I can't reach the camera service.")

        val id = cameras.cameraIdList.firstOrNull { cameraId ->
            cameras.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return Reply.error("This phone has no flash.")

        cameras.setTorchMode(id, on)
        return Reply(
            if (on) "Torch on." else "Torch off.",
            Chip(ChipIcon.TORCH, if (on) "On" else "Off"),
        )
    }

    private fun volume(change: VolumeChange): Reply {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return Reply.error("I can't reach the audio service.")

        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)

        when (change) {
            VolumeChange.Up ->
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)

            VolumeChange.Down ->
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)

            VolumeChange.Mute -> audio.setStreamVolume(stream, 0, 0)
            VolumeChange.Max -> audio.setStreamVolume(stream, max, 0)
            is VolumeChange.Percent ->
                audio.setStreamVolume(stream, (max * change.value / 100f).toInt(), 0)
        }

        val now = audio.getStreamVolume(stream)
        val percent = if (max == 0) 0 else (now * 100f / max).toInt()
        return Reply(
            when (change) {
                VolumeChange.Mute -> "Muted."
                else -> "Volume $percent percent."
            },
            Chip(ChipIcon.VOLUME, "$percent%"),
        )
    }

    private fun dnd(on: Boolean): Reply {
        val notifications = context.getSystemService(NotificationManager::class.java)
            ?: return Reply.error("I can't reach the notification service.")

        if (!notifications.isNotificationPolicyAccessGranted) {
            return needsSetting(
                "I need Do Not Disturb access first.",
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            )
        }
        notifications.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return Reply(
            if (on) "Do Not Disturb is on." else "Do Not Disturb is off.",
            Chip(ChipIcon.DND, if (on) "On" else "Off"),
        )
    }

    private fun openCamera(): Reply {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!canResolve(intent)) return Reply.error("There's no camera app on this phone.")
        context.startActivity(intent)
        return Reply("Camera.", Chip(ChipIcon.CAMERA, "Camera"))
    }

    private fun battery(): Reply {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Reply.error("I couldn't read the battery.")

        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return Reply.error("I couldn't read the battery.")

        val percent = (level * 100f / scale).toInt()
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val said = if (plugged) "Battery is at $percent percent and charging."
        else "Battery is at $percent percent."
        return Reply(said, Chip(ChipIcon.BATTERY, "$percent%"))
    }

    /**
     * Radios have not been app-toggleable since Android 10, so this opens the panel
     * instead of failing quietly — one tap away and honest about it.
     */
    private fun openPanel(panel: Panel): Reply {
        val action = when (panel) {
            Panel.WIFI ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI
                else Settings.ACTION_WIFI_SETTINGS

            Panel.INTERNET ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                else Settings.ACTION_WIRELESS_SETTINGS

            Panel.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            Panel.NFC -> Settings.ACTION_NFC_SETTINGS
            Panel.SETTINGS -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!canResolve(intent)) return Reply.error("I couldn't open that setting.")
        context.startActivity(intent)

        val name = panel.name.lowercase().replaceFirstChar { it.uppercase() }
        return Reply("Here's $name.", Chip(ChipIcon.SETTINGS, name))
    }

    // ----------------------------------------------------------------- apps

    private fun openApp(name: String): Reply {
        val match = apps.find(name) ?: return Reply("I couldn't find an app called $name.")
        val intent = apps.launchIntent(match.packageName)
            ?: return Reply.error("${match.label} can't be opened.")
        context.startActivity(intent)
        return Reply("Opening ${match.label}.", Chip(ChipIcon.APP, match.label))
    }

    private fun search(query: String): Reply {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallback = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val chosen = if (canResolve(intent)) intent else fallback
        if (!canResolve(chosen)) return Reply.error("There's no browser on this phone.")
        context.startActivity(chosen)
        return Reply("Searching for $query.", Chip(ChipIcon.SEARCH, query))
    }

    private fun navigate(destination: String): Reply {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=${Uri.encode(destination)}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val fallback = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("geo:0,0?q=${Uri.encode(destination)}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val chosen = if (canResolve(intent)) intent else fallback
        if (!canResolve(chosen)) return Reply.error("There's no maps app on this phone.")
        context.startActivity(chosen)
        return Reply("Directions to $destination.", Chip(ChipIcon.NAVIGATION, destination))
    }

    // --------------------------------------------------------------- choice

    private fun choose(index: Int): Reply {
        val choices = pendingChoices
        val action = pendingAction
        if (choices.isEmpty() || action == null) {
            return Reply("There's nothing to choose from right now.")
        }
        val position = if (index == -1) choices.lastIndex else index - 1
        if (position !in choices.indices) {
            return Reply.ask(
                "That wasn't on the list. Which one?",
                choices.map { it.name },
            )
        }
        pendingChoices = emptyList()
        pendingAction = null
        return action(choices[position])
    }

    // -------------------------------------------------------------- helpers

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun canResolve(intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null

    /**
     * Aim an intent at one handler when several match and one is the system's own.
     *
     * Otherwise "set a timer for five minutes" ends in a *Complete action using…*
     * dialog, which is precisely the interruption the fast path exists to avoid. A
     * system clock app is the right default; where the choice is genuinely between
     * two third-party apps we leave the chooser alone, because then it is a real
     * question and guessing would be worse.
     */
    private fun preferSystemHandler(intent: Intent): Intent {
        val handlers = runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())
        if (handlers.size < 2) return intent

        val system = handlers.filter { resolved ->
            val flags = resolved.activityInfo?.applicationInfo?.flags ?: 0
            flags and (ApplicationInfo.FLAG_SYSTEM or
                ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }
        val only = system.singleOrNull() ?: return intent

        return Intent(intent).setComponent(
            ComponentName(only.activityInfo.packageName, only.activityInfo.name)
        )
    }

    private fun needsPermission(message: String) = Reply(
        "$message Open Sentry to grant it.",
        Chip(ChipIcon.SETTINGS, "Permission"),
        isError = true,
    )

    private fun needsSetting(message: String, action: String): Reply {
        runCatching {
            val intent = Intent(action)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (canResolve(intent)) {
                context.startActivity(intent)
            } else {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return Reply(message, Chip(ChipIcon.SETTINGS, "Permission"), isError = true)
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(calendar.time)
    }

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remaining = seconds % 60
        return buildList {
            if (hours > 0) add("$hours hour${plural(hours)}")
            if (minutes > 0) add("$minutes minute${plural(minutes)}")
            if (remaining > 0 && hours == 0) add("$remaining second${plural(remaining)}")
        }.joinToString(" ").ifBlank { "$seconds seconds" }
    }

    private fun plural(count: Int) = if (count == 1) "" else "s"
}
