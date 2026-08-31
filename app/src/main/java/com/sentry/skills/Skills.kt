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
import com.sentry.core.Channel
import com.sentry.core.Chip
import com.sentry.core.ChipIcon
import com.sentry.core.Command
import com.sentry.core.MediaAction
import com.sentry.core.Panel
import com.sentry.core.Provider
import com.sentry.data.Fact
import com.sentry.data.Memory
import com.sentry.core.LevelTarget
import com.sentry.data.NameBook
import com.sentry.data.VerbBook
import com.sentry.core.Reply
import com.sentry.service.ScreenshotService
import com.sentry.core.LevelChange
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
class Skills(
    private val context: Context,
    private val memory: Memory,
    /**
     * Shared with the rest of the app on purpose: [Watchers] invalidates this when
     * something is installed, and a private copy here would keep serving the stale
     * list it captured at startup.
     */
    private val apps: Apps,
    /**
     * Written to whenever the user resolves an ambiguous name, which is the only
     * moment Sentry is ever told, for free, what a mangled word actually meant.
     */
    private val names: NameBook,
    /** Directions the user has taught for words the recogniser mangles. */
    private val verbs: VerbBook,
) {

    private companion object {
        const val TAG = "Sentry/Skills"

        /**
         * Assumed when a stored number has no country code.
         *
         * WhatsApp addresses people internationally and will not find a bare local
         * number. India, because that is where this phone and its address book are;
         * a number already carrying a "+" is left exactly as it is.
         */
        const val DEFAULT_COUNTRY_CODE = "91"

        /** A spoken list longer than this stops being a question and becomes a recitation. */
        const val MAX_CHOICES = 5

        /** The scale Android stores screen brightness on. */
        const val BRIGHTNESS_MAX = 255
        const val BRIGHTNESS_STEP = 40

        /** People Sentry may have been told about, in the order it should guess them. */
        val FAMILY = listOf(Fact.MOTHER, Fact.FATHER, Fact.SPOUSE, Fact.SIBLING)
    }

    private val contacts = Contacts(context)

    /**
     * A stop on anything with a blast radius: calls, messages, alarms, timers, music.
     *
     * Off in normal use — nothing in the release build ever sets it — so an assistant
     * that cannot ring anything is a state only a test can put it in.
     */
    val disturbances = Disturbances()

    /** What was last offered as a numbered list, so "the second one" can resolve. */
    private var pendingChoices: List<ContactMatch> = emptyList()

    /** What to do with a choice once it is made. */
    private var pendingAction: ((ContactMatch) -> Reply)? = null

    /**
     * The words that produced the list, kept so the answer can be learned from.
     *
     * Without this the pick is thrown away and Sentry asks the same question
     * tomorrow, which is the behaviour that makes an assistant feel stupid.
     */
    private var pendingQuery: String = ""

    fun run(command: Command): Reply {
        // Any command that is not a selection invalidates a stale list; otherwise a
        // "second one" said minutes later would call whoever happened to be cached.
        if (command !is Command.Choose) {
            pendingChoices = emptyList()
            pendingAction = null
            pendingQuery = ""
        }
        // A second "which way?" replaces the first rather than stacking.
        if (command !is Command.WhichWay) pendingWay = null

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
        Command.CancelTimer ->
            dismissClock(AlarmClock.ACTION_DISMISS_TIMER, AlarmClock.ACTION_SHOW_TIMERS, "timer")
        Command.CancelAlarm ->
            dismissClock(AlarmClock.ACTION_DISMISS_ALARM, AlarmClock.ACTION_SHOW_ALARMS, "alarm")

        is Command.Call -> call(command.query)
        is Command.CallNumber -> placeCall(command.number, command.number)
        Command.AnswerCall -> answerCall()
        Command.HangUp -> hangUp()
        is Command.SendMessage -> sendMessage(command)

        is Command.PlayMusic -> playMusic(command.query, command.provider)
        is Command.MediaControl -> mediaControl(command.action)

        is Command.Torch -> torch(command.on)
        is Command.Volume -> volume(command.change)
        Command.VolumeQuery -> volumeQuery()
        is Command.Brightness -> brightness(command.change)
        Command.BrightnessQuery -> brightnessQuery()
        is Command.Silent -> silent(command.on)
        is Command.WhichWay -> whichWay(command)
        is Command.Dnd -> dnd(command.on)
        Command.OpenCamera -> openCamera()
        Command.BatteryStatus -> battery()
        is Command.Calculate -> Reply(command.spoken, Chip(ChipIcon.CLOCK, command.expression))
        Command.Screenshot -> screenshot()
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
        quietly("set an alarm")?.let { return it }
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
        quietly("start a timer")?.let { return it }
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

    /**
     * Silence a timer or alarm that is going off, and show the rest.
     *
     * ACTION_DISMISS_TIMER only affects a timer that has already expired. Measured,
     * not assumed: sent against a ninety-second timer with sixty seconds left it did
     * nothing at all and the timer rang on schedule; sent while that same timer was
     * ringing it stopped instantly. Android exposes no way to delete a timer that is
     * still counting down — only the clock app itself can do that.
     *
     * So the dismiss handles the urgent case, the one people shout at a phone, and
     * the timer list follows for the case it cannot touch: a countdown the user
     * wanted rid of is then one tap away instead of zero, which is the best this
     * platform allows and better than claiming to have cancelled something that is
     * still running.
     */
    private fun dismissClock(action: String, listAction: String, what: String): Reply {
        val dismiss = Intent(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (!canResolve(dismiss)) return Reply.error("There's no clock app on this phone.")
        context.startActivity(preferSystemHandler(dismiss))

        val list = Intent(listAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (canResolve(list)) context.startActivity(preferSystemHandler(list))

        return Reply(
            "Stopped anything ringing. Here are your ${what}s if one is still going.",
            Chip(ChipIcon.ALARM, what.replaceFirstChar { it.uppercase() }),
        )
    }

    // ---------------------------------------------------------------- comms

    /**
     * Turn a relationship into a name, if Sentry has been told one.
     *
     * "Call mom" is not a name at all, and no amount of fuzzy matching against the
     * address book will make it one. But if the user has said "my mother is Maa" at
     * some point, that is exactly the missing link — so kinship words resolve through
     * memory before the contact search sees them.
     */
    private fun relationFact(query: String): Fact? = when (query.trim().lowercase()) {
        "mom", "mum", "mother", "amma", "mummy", "ma" -> Fact.MOTHER
        "dad", "father", "daddy", "papa", "nanna", "appa" -> Fact.FATHER
        "wife", "husband", "partner" -> Fact.SPOUSE
        "brother", "sister", "sibling" -> Fact.SIBLING
        else -> null
    }

    /**
     * Contacts matching a spoken name, resolving relationships through memory.
     *
     * "Call mom" is not a name and no fuzzy matching will make it one — but if Sentry
     * has been told who mom is, that is the missing link. The remembered name is tried
     * first and the spoken word second, because the two can disagree: someone whose
     * mother is Rani may still have her saved in the phone as "Maa", and refusing to
     * fall back would make remembering a fact *worse* than not knowing it.
     */
    internal fun findPerson(query: String): Lookup {
        val candidates = buildList {
            // What a previous pick taught, first: it is the most specific thing
            // Sentry knows about this exact mis-hearing, and it was learned from
            // this user rather than guessed.
            names.resolve(query)?.let { add(it) }
            relationFact(query)?.let { fact -> memory[fact]?.let { add(it) } }
            add(query)
        }
        for (candidate in candidates) {
            val found = contacts.find(candidate)
            if (found.isNotEmpty()) {
                if (candidate != query) Log.d(TAG, "\"$query\" -> \"$candidate\" (remembered)")
                return found
            }
        }
        return Lookup(emptyList(), certain = false)
    }

    private fun call(query: String): Reply {
        if (!has(Manifest.permission.CALL_PHONE) || !contacts.hasPermission()) {
            return needsPermission("I need permission to see your contacts and place calls.")
        }

        val found = findPerson(query)
        if (found.size == 1 && found.certain) return placeCall(found[0].number, found[0].name)

        val offer = offerFor(query, found)
        if (offer.isEmpty()) return Reply("I couldn't find anyone called $query.")

        return offerChoice(
            query = query,
            matches = offer,
            question = questionFor(query, found, offer),
            action = { placeCall(it.number, it.name) },
        )
    }

    /**
     * The list to put in front of the user when the name did not resolve outright.
     *
     * A confident-but-multiple result is a real choice between real candidates and is
     * left alone. Anything else — nothing found, or one unconvincing match — gets the
     * starred contacts folded in, because the person the user meant is very likely
     * among them and otherwise has no way of reaching the list at all.
     */
    private fun offerFor(query: String, found: Lookup): List<ContactMatch> {
        if (found.certain && found.isNotEmpty()) return found
        return (found + rescue(query))
            .distinctBy { it.number.filter(Char::isDigit) }
            .take(MAX_CHOICES)
    }

    private fun questionFor(query: String, found: Lookup, offer: List<ContactMatch>): String = when {
        found.certain && found.isNotEmpty() -> question(offer)
        found.isEmpty() -> "I don't have anyone called $query. Did you mean one of these?"
        else -> "I'm not sure you meant ${found[0].name}. Which one?"
    }

    /**
     * What to offer when a spoken name matched nobody.
     *
     * The alternative is "I couldn't find anyone called karma", which is both true
     * and useless: the user knows who they meant, and Sentry has thrown away the one
     * chance it had to find out. Offering the starred contacts turns a dead end into
     * the only supervised example this system will ever get for free.
     *
     * Only for short queries. "Call the plumber I saw on Tuesday" matching nothing is
     * not a mis-hearing to be rescued, and reciting five names at it would be noise.
     */
    private fun rescue(query: String): List<ContactMatch> {
        if (query.trim().split(' ').size > 2) return emptyList()

        // Family first. Starred contacts are alphabetical and there are a dozen of
        // them, so on this phone "Maa" falls outside the top five and the one person
        // the user was most likely asking for never reaches the list. Anyone Sentry
        // has been told about by name is a better guess than anyone it has not.
        val family = FAMILY.mapNotNull { memory[it] }
            .flatMap { name -> contacts.find(name).take(1) }

        return (family + contacts.starred())
            .distinctBy { it.number.filter(Char::isDigit) }
            .take(MAX_CHOICES)
    }

    /**
     * Offer a list and remember enough to learn from the answer.
     *
     * Every path that asks "which one?" goes through here, so there is no way to add
     * a new one that forgets to record what was asked.
     */
    private fun offerChoice(
        query: String,
        matches: List<ContactMatch>,
        question: String,
        action: (ContactMatch) -> Reply,
    ): Reply {
        pendingChoices = matches
        pendingAction = action
        pendingQuery = query
        return Reply.ask(question, matches.map { it.spoken })
    }

    /**
     * The right question depends on what is ambiguous.
     *
     * Several people called Kumar is a different question from one person with a
     * mobile and a landline, and asking "which one?" for both leaves the user
     * guessing what is being asked.
     */
    private fun question(matches: List<ContactMatch>): String {
        val oneName = matches.map { it.name.lowercase() }.distinct().size == 1
        return if (oneName) {
            "${matches[0].name} has ${matches.size} numbers. Which one?"
        } else {
            "I found ${matches.size} matches. Which one?"
        }
    }

    private fun placeCall(number: String, who: String): Reply {
        if (!has(Manifest.permission.CALL_PHONE)) {
            return needsPermission("I need permission to place calls.")
        }
        if (disturbances.blocked) {
            Log.i(TAG, "would call $who ($number)")
            return Reply("I would call $who, but disturbances are switched off.")
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
        val found = findPerson(command.query)
        if (found.size == 1 && found.certain) {
            return compose(found[0], command.body, command.channel)
        }

        val offer = offerFor(command.query, found)
        if (offer.isEmpty()) return Reply("I couldn't find anyone called ${command.query}.")

        return offerChoice(
            query = command.query,
            matches = offer,
            question = questionFor(command.query, found, offer),
            action = { compose(it, command.body, command.channel) },
        )
    }

    /**
     * Opens the SMS app with the message ready to send rather than sending it.
     *
     * Sending silently would need SEND_SMS and would mean a misheard word goes out
     * to a real person with no chance to catch it. One tap is the right price.
     */
    private fun compose(match: ContactMatch, body: String?, channel: Channel): Reply {
        val intent = when (channel) {
            Channel.WHATSAPP -> whatsAppIntent(match, body)
            Channel.SMS -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${match.number}")).apply {
                body?.let { putExtra("sms_body", it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } ?: return Reply("WhatsApp isn't installed.")

        if (!canResolve(intent)) {
            return Reply.error("There's no ${channel.label} app that can do that.")
        }
        if (disturbances.blocked) {
            // Nothing is sent by opening a composer, but opening a chat still marks
            // it read, and a read receipt at three in the morning is a message of
            // its own.
            Log.i(TAG, "would open ${channel.label} for ${match.name}: $intent")
            return Reply("I would open ${channel.label} for ${match.name}, " +
                "but disturbances are switched off.")
        }
        context.startActivity(intent)

        // Still opens the composer rather than sending, on every channel. A misheard
        // word reaching a real person is not something one tap is too high a price to
        // prevent — and it matters more on WhatsApp, where there is no undo at all.
        return if (body != null) {
            Reply(
                "Ready to send to ${match.name} on ${channel.label}. Tap send.",
                Chip(ChipIcon.MESSAGE, match.name),
            )
        } else {
            Reply(
                "Opening ${channel.label} for ${match.name}.",
                Chip(ChipIcon.MESSAGE, match.name),
            )
        }
    }

    /**
     * WhatsApp wants an international number with no punctuation, and silently shows
     * "not on WhatsApp" for anything else — so the number is normalised here rather
     * than handed over as the address book stores it.
     */
    private fun whatsAppIntent(match: ContactMatch, body: String?): Intent? {
        if (!isInstalled(Channel.WHATSAPP.packageName!!)) return null

        val digits = match.number.filter { it.isDigit() }
        val international = when {
            match.number.trim().startsWith("+") -> digits
            digits.length == 10 -> DEFAULT_COUNTRY_CODE + digits
            else -> digits
        }

        val url = buildString {
            append("https://wa.me/").append(international)
            body?.let { append("?text=").append(Uri.encode(it)) }
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(Channel.WHATSAPP.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ---------------------------------------------------------------- media

    /**
     * Take a screenshot.
     *
     * Through an accessibility service, because that is the only route an app has:
     * MediaProjection would throw up a consent dialog every single time, which is
     * unusable for a voice command. The service is off until the user turns it on,
     * and this says so rather than failing silently.
     */
    private fun screenshot(): Reply {
        if (!ScreenshotService.isEnabled(context)) {
            return needsSetting(
                "To take screenshots I need Sentry's accessibility service turned on.",
                Settings.ACTION_ACCESSIBILITY_SETTINGS,
            )
        }
        return if (ScreenshotService.take()) {
            Reply("Screenshot taken.", Chip(ChipIcon.CAMERA, "Screenshot"))
        } else {
            Reply.error("I couldn't take the screenshot.")
        }
    }

    private fun playMusic(query: String?, provider: Provider?): Reply {
        quietly("play that")?.let { return it }
        if (provider != null) return playOn(provider, query)

        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query ?: "")
            if (query == null) {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canResolve(intent)) {
            return Reply.error("I couldn't find a music app that can search.")
        }
        context.startActivity(preferSystemHandler(intent))
        return if (query == null) {
            Reply("Playing music.", Chip(ChipIcon.MUSIC, "Music"))
        } else {
            Reply("Playing $query.", Chip(ChipIcon.MUSIC, query))
        }
    }

    /**
     * Play something in a named app.
     *
     * Each provider gets the media-search intent aimed at its package first, because
     * that is the one that actually starts playback rather than just opening a search
     * box. Where the app does not handle it, a deep link is the fallback, and simply
     * opening the app is the last resort — better than telling the user no.
     */
    private fun playOn(provider: Provider, query: String?): Reply {
        val target = resolveProvider(provider)
            ?: return Reply("${provider.label} isn't installed.")

        val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(target)
            putExtra(SearchManager.QUERY, query.orEmpty())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (query != null && canResolve(search)) {
            context.startActivity(search)
            return Reply("Playing $query on ${provider.label}.", Chip(ChipIcon.MUSIC, provider.label))
        }

        val deepLink = query?.let { deepLinkFor(provider, target, it) }
        if (deepLink != null && canResolve(deepLink)) {
            context.startActivity(deepLink)
            return Reply("Searching ${provider.label} for $query.", Chip(ChipIcon.MUSIC, provider.label))
        }

        val launch = apps.launchIntent(target)
            ?: return Reply.error("I couldn't open ${provider.label}.")
        context.startActivity(launch)
        return Reply("Opening ${provider.label}.", Chip(ChipIcon.MUSIC, provider.label))
    }

    private fun deepLinkFor(provider: Provider, target: String, query: String): Intent? {
        val encoded = Uri.encode(query)
        val uri = when (provider) {
            Provider.SPOTIFY -> "spotify:search:$encoded"
            Provider.YOUTUBE -> "https://www.youtube.com/results?search_query=$encoded"
            Provider.YOUTUBE_MUSIC -> "https://music.youtube.com/search?q=$encoded"
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .setPackage(target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The package that will actually play this, or null if nothing will.
     *
     * Prefers the official app, then falls back to whatever the launcher shows under
     * that name — which is how a replaced or modded client gets found. The user asked
     * for "YouTube Music"; which build of it they run is not their problem.
     */
    private fun resolveProvider(provider: Provider): String? {
        if (isInstalled(provider.canonical)) return provider.canonical

        for (name in listOf(provider.label) + provider.aliases) {
            val found = apps.find(name) ?: continue
            // "Music" is a loose alias and could match a media player that is not this
            // provider at all, so anything matched loosely still has to look related.
            Log.i(TAG, "${provider.label}: using ${found.packageName} as \"${found.label}\"")
            return found.packageName
        }
        return null
    }

    private fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0).enabled
    }.getOrDefault(false)

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

    /**
     * Screen brightness.
     *
     * Needs WRITE_SETTINGS, which is not a runtime permission but a page in Settings
     * the user has to visit — so the failure is handled the same way DND is: say what
     * is needed and put them on the page, rather than reporting that it did not work.
     *
     * Writes to the system setting rather than the window attribute. A window
     * brightness override only dims Sentry's own screen, which is a convincing
     * imitation of working right up until the user leaves the app.
     */
    private fun brightnessQuery(): Reply {
        if (!Settings.System.canWrite(context)) {
            // Reading needs no permission, so answer even when setting is not allowed.
            Log.d(TAG, "reading brightness without write access")
        }
        val current = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull() ?: return Reply.error("I can't read the screen brightness.")
        val percent = percentOf(current, BRIGHTNESS_MAX)
        return Reply("Brightness is $percent percent.", Chip(ChipIcon.SETTINGS, "$percent%"))
    }

    private fun brightness(change: LevelChange): Reply {
        if (!Settings.System.canWrite(context)) {
            return needsSetting(
                "I need permission to change the brightness.",
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
            )
        }

        val resolver = context.contentResolver
        val current = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(BRIGHTNESS_MAX / 2)

        val target = when (change) {
            LevelChange.Up -> current + BRIGHTNESS_STEP
            LevelChange.Down -> current - BRIGHTNESS_STEP
            is LevelChange.By -> current + BRIGHTNESS_MAX * change.delta / 100
            LevelChange.Max -> BRIGHTNESS_MAX
            // Never all the way off: a black screen looks exactly like a broken phone,
            // and the way back is a setting the user now cannot see to change.
            LevelChange.Min -> BRIGHTNESS_STEP
            is LevelChange.Percent -> BRIGHTNESS_MAX * change.value / 100
        }.coerceIn(1, BRIGHTNESS_MAX)

        // Automatic brightness would undo this within seconds, which reads as the
        // command having been ignored.
        runCatching {
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
        }
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, target)

        val percent = target * 100 / BRIGHTNESS_MAX
        return Reply("Brightness $percent percent.", Chip(ChipIcon.SETTINGS, "$percent%"))
    }

    /**
     * Read the media volume back without changing it.
     *
     * "The device brightness" and "what's the volume" used to have nowhere to go, so
     * they were answered by acting — the classifier picked the nearest label that
     * involved a percentage and Sentry raised an already-maxed stream.
     */
    private fun volumeQuery(): Reply {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return Reply.error("I can't reach the audio service.")
        val percent = percentOf(
            audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )
        return Reply("Media volume is $percent percent.", Chip(ChipIcon.VOLUME, "$percent%"))
    }

    private fun volume(change: VolumeChange): Reply {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return Reply.error("I can't reach the audio service.")

        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        if (max <= 0) return Reply.error("This phone reports no media volume range.")
        val before = audio.getStreamVolume(stream)

        // Everything is computed as a target index rather than nudged, so that "down"
        // and "down by ten percent" travel through the same arithmetic and cannot
        // disagree about what a step is.
        val target = when (change) {
            VolumeChange.Up -> before + stepFor(max)
            VolumeChange.Down -> before - stepFor(max)
            VolumeChange.Mute -> 0
            VolumeChange.Max -> max
            is VolumeChange.Percent -> Math.round(max * change.value / 100f)
            is VolumeChange.By -> before + Math.round(max * change.delta / 100f)
        }.coerceIn(0, max)

        audio.setStreamVolume(stream, target, 0)

        val now = audio.getStreamVolume(stream)
        val percent = percentOf(now, max)
        val speech = when {
            change == VolumeChange.Mute -> "Muted."
            // Saying a number that did not move reads as the command being ignored.
            now == before && now >= max -> "Volume is already all the way up."
            now == before && now <= 0 -> "Volume is already off."
            else -> "Volume $percent percent."
        }
        return Reply(speech, Chip(ChipIcon.VOLUME, "$percent%"))
    }

    /**
     * The ringer, which is neither media volume nor Do Not Disturb.
     *
     * Silencing needs the same policy access as Do Not Disturb on modern Android, so
     * a missing grant is handled the same way rather than failing silently — which is
     * what "Ok, I'll keep the device in silent" was.
     */
    private fun silent(on: Boolean): Reply {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return Reply.error("I can't reach the audio service.")
        val notifications = context.getSystemService(NotificationManager::class.java)

        if (on && notifications?.isNotificationPolicyAccessGranted == false) {
            return needsSetting(
                "I need Do Not Disturb access to silence the ringer.",
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            )
        }

        return runCatching {
            audio.ringerMode =
                if (on) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
            Reply(
                if (on) "The phone is on silent." else "The ringer is back on.",
                Chip(ChipIcon.VOLUME, if (on) "Silent" else "Ringer"),
            )
        }.getOrElse {
            Log.w(TAG, "could not set ringer mode", it)
            Reply.error("Android wouldn't let me change the ringer.")
        }
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
            return Reply.ask("That wasn't on the list. Which one?", choices.map { it.spoken })
        }
        val chosen = choices[position]
        learnFrom(pendingQuery, chosen)

        pendingChoices = emptyList()
        pendingAction = null
        pendingQuery = ""
        return action(chosen)
    }

    /**
     * Take the lesson out of a pick.
     *
     * Two different things can be learned here and they are not interchangeable.
     * "Call mom" resolving to a contact is a *fact about the user's family*, and it
     * belongs in memory where they can read it and correct it. "Call karma"
     * resolving to Maa is a fact about the *recogniser* — nothing about the user is
     * revealed by it — and it belongs in the name book.
     *
     * Both are best-effort. A pick that teaches nothing is still a pick that worked,
     * so nothing here is allowed to turn a successful call into an error.
     */
    private fun learnFrom(query: String, chosen: ContactMatch) {
        if (query.isBlank()) return
        runCatching {
            val relation = relationFact(query)
            if (relation != null) {
                // Only when nothing is on file. Overwriting a stated fact with an
                // inference from one tap would be Sentry deciding it knows the
                // user's family better than they do.
                if (memory[relation] == null) {
                    memory.remember(relation, chosen.name, source = "you picked $query")
                    Log.i(TAG, "learned ${relation.name} = ${chosen.name} from a choice")
                }
                return@runCatching
            }
            if (names.bind(query, chosen.name)) {
                Log.i(TAG, "\"$query\" now means ${chosen.name}")
            }
        }.onFailure { Log.w(TAG, "could not learn from choice", it) }
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

    /**
     * Refuse an action that would make a noise, when noise is switched off.
     *
     * Returns null in normal use, so every caller is one line and the guard cannot be
     * left off a new skill by forgetting to wrap it in an if.
     */
    // ------------------------------------------------------- which way?

    /** A direction Sentry asked for and is waiting on. */
    private var pendingWay: Command.WhichWay? = null

    /**
     * The words that answer "up or down?", and the verb each one stands for.
     *
     * Deliberately tiny. This runs on every utterance while a question is open, so
     * anything it accepts is a word that cannot be said in passing without moving a
     * level — and anything it does not accept simply cancels the question and is
     * handled normally.
     */
    private val ANSWERS = mapOf(
        "up" to "increase", "increase" to "increase", "higher" to "increase",
        "raise" to "increase", "louder" to "increase", "brighter" to "increase",
        "more" to "increase", "brighten" to "increase",
        "down" to "decrease", "decrease" to "decrease", "lower" to "decrease",
        "quieter" to "decrease", "softer" to "decrease", "dimmer" to "decrease",
        "dim" to "decrease", "less" to "decrease", "darker" to "decrease",
    )

    private val DECLINED = setOf(
        "neither", "nothing", "never mind", "nevermind", "cancel", "forget it",
        "no", "none", "stop",
    )

    /**
     * Sentry heard a level command with no direction in it.
     *
     * Reads the current level back as part of the question, because "up or down?" on
     * its own is a worse question than "volume is at ninety-two percent — up or
     * down?", and because it confirms Sentry understood which thing was meant.
     */
    private fun whichWay(command: Command.WhichWay): Reply {
        pendingWay = command
        val now = when (command.target) {
            LevelTarget.SOUND -> currentVolumePercent()
            LevelTarget.SCREEN -> currentBrightnessPercent()
        }
        val thing = if (command.target == LevelTarget.SOUND) "Volume" else "Brightness"
        val preamble = if (now == null) "" else "$thing is at $now percent. "
        return Reply.ask("${preamble}Up or down?", listOf("Up", "Down"))
    }

    /**
     * Take the answer to a question Sentry asked, if this utterance is one.
     *
     * @return null when nothing was pending, or when the words are not an answer — in
     *   which case the utterance is handled normally and the question is dropped,
     *   because a user who says something else has moved on.
     */
    fun answer(text: String): Reply? {
        val pending = pendingWay ?: return null
        val words = text.lowercase().trim().trim('.', '!', '?')

        if (words in DECLINED) {
            pendingWay = null
            return Reply("Alright, leaving it.")
        }

        // Only a bare direction counts. "Turn the volume up" is a whole command and
        // should go through the matcher like any other, not be treated as an answer.
        val verb = ANSWERS[words] ?: ANSWERS[words.removePrefix("the ")] ?: run {
            pendingWay = null
            return null
        }
        pendingWay = null

        // The answer is the authority. Nothing here is a guess, which is the entire
        // reason for asking rather than scoring.
        verbs.bind(pending.target, pending.heard, verb)

        val up = verb == "increase"
        return when (pending.target) {
            LevelTarget.SOUND ->
                volume(if (up) VolumeChange.Up else VolumeChange.Down)
            LevelTarget.SCREEN ->
                brightness(if (up) LevelChange.Up else LevelChange.Down)
        }
    }

    private fun currentVolumePercent(): Int? {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return percentOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC), max)
    }

    private fun currentBrightnessPercent(): Int? = runCatching {
        percentOf(
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS),
            BRIGHTNESS_MAX,
        )
    }.getOrNull()

    private fun percentOf(value: Int, max: Int): Int =
        if (max <= 0) 0 else Math.round(value * 100f / max).coerceIn(0, 100)

    /**
     * How far one spoken "turn it down" should move a stream.
     *
     * A single hardware step is whatever the manufacturer chose: on this phone media
     * volume has 25 of them, so "lower the volume" moved 100% to 96% and sounded
     * broken. A tenth of the range is what people seem to mean, and never less than
     * one step, so a coarse stream still moves.
     */
    private fun stepFor(max: Int): Int = maxOf(1, Math.round(max / 10f))

    private fun quietly(what: String): Reply? =
        if (disturbances.blocked) {
            Log.i(TAG, "would $what")
            Reply("I would $what, but disturbances are switched off.")
        } else {
            null
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
