package com.sentry.nlu

import com.sentry.core.Command
import com.sentry.core.MediaAction
import com.sentry.core.Panel
import com.sentry.core.VolumeChange

/**
 * Understands the commands people actually say, without a language model.
 *
 * This exists because the honest answer to "set a timer for five minutes" is a
 * regular expression, not a 1.5B parameter network. Routing it through a model costs
 * hundreds of milliseconds and adds a chance of being wrong about something that
 * cannot be wrong. Everything here resolves in well under a millisecond.
 *
 * The rule is **precision over recall**: a miss costs one model round trip, which is
 * merely slow, while a false positive sets the wrong alarm or calls the wrong person.
 * So every pattern here is anchored and specific, and anything doubtful returns null
 * and lets [Planner] deal with it.
 */
object FastMatcher {

    /** Filler that carries no meaning and only confuses the patterns below. */
    private val FILLER = Regex(
        """^(?:hey |ok |okay )?sentry[,\s]+|^(?:please|could you|can you|would you|i want you to|i need you to)\s+"""
    )

    private val PUNCTUATION = Regex("""[!?,.;]+$""")

    /**
     * True when the utterance is nothing but the wake word.
     *
     * People say "Sentry" and then pause to think, and in an open conversation the
     * wake word gets picked up as a command in its own right. Neither should cost a
     * model round trip or an answer — the right response to "Sentry" is to keep
     * listening, which is what the caller does with this.
     */
    fun isWakeWordOnly(raw: String): Boolean {
        val s = raw.trim().lowercase()
            .replace(PUNCTUATION, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return s in setOf("sentry", "hey sentry", "ok sentry", "okay sentry", "century")
    }

    fun match(raw: String): Command? {
        var s = raw.trim().lowercase()
        // Strip leading filler repeatedly: "hey sentry, please open camera".
        while (true) {
            val stripped = s.replaceFirst(FILLER, "")
            if (stripped == s) break
            s = stripped.trim()
        }
        s = s.replace(PUNCTUATION, "").trim()
        s = s.replace(Regex("""\s+"""), " ")
        if (s.isEmpty()) return null

        return matchStop(s)
            ?: matchChoice(s)
            ?: matchCallControl(s)
            ?: matchTimer(s)
            ?: matchAlarm(s)
            ?: matchTorch(s)
            ?: matchMediaControl(s)
            ?: matchMusic(s)
            ?: matchVolume(s)
            ?: matchDnd(s)
            ?: matchCall(s)
            ?: matchMessage(s)
            ?: matchNavigate(s)
            ?: matchCamera(s)
            ?: matchQuery(s)
            ?: matchPanel(s)
            ?: matchOpenApp(s)
            ?: matchSearch(s)
    }

    // ----------------------------------------------------------------- stop

    private val STOP = setOf(
        "stop", "cancel", "never mind", "nevermind", "shut up", "be quiet", "quiet",
        "forget it", "abort", "silence", "stop it", "that's all", "thats all",
        "nothing", "exit", "close",
    )

    private fun matchStop(s: String): Command? = if (s in STOP) Command.Stop else null

    // --------------------------------------------------------------- choice

    private val CHOICE = Regex(
        """^(?:the )?(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|last)(?: one)?$"""
    )
    private val CHOICE_NUMBER = Regex("""^(?:number |option )?(\d{1,2})$""")

    private fun matchChoice(s: String): Command? {
        CHOICE.find(s)?.let { m ->
            TimeWords.ordinal(m.groupValues[1])?.let { return Command.Choose(it) }
        }
        CHOICE_NUMBER.find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n in 1..10) return Command.Choose(n)
        }
        return null
    }

    // --------------------------------------------------------- call control

    private val ANSWER = setOf(
        "answer", "answer it", "answer the call", "pick up", "pick it up",
        "accept the call", "accept call", "take the call",
    )
    private val HANGUP = setOf(
        "hang up", "hangup", "end the call", "end call", "decline", "reject",
        "decline the call", "reject the call", "hang up the call", "dismiss the call",
    )

    private fun matchCallControl(s: String): Command? = when (s) {
        in ANSWER -> Command.AnswerCall
        in HANGUP -> Command.HangUp
        else -> null
    }

    // ---------------------------------------------------------------- timer

    private val TIMER = Regex(
        """^(?:set |start |create |put )?(?:a |an )?timer(?: for| of)?\s+(.+)$|^(?:time me for|count down|countdown)\s+(.+)$"""
    )
    private val REMIND_IN = Regex("""^remind me (?:in|after)\s+(.+?)(?:\s+to\s+(.+))?$""")

    private fun matchTimer(s: String): Command? {
        if (s == "show timers" || s == "show my timers" || s == "my timers") {
            return Command.ShowTimers
        }
        TIMER.find(s)?.let { m ->
            val rest = m.groupValues[1].ifBlank { m.groupValues[2] }
            TimeWords.duration(rest)?.let { return Command.SetTimer(it) }
        }
        REMIND_IN.find(s)?.let { m ->
            TimeWords.duration(m.groupValues[1])?.let {
                return Command.SetTimer(it, m.groupValues[2].ifBlank { null })
            }
        }
        // "5 minute timer", "10 min timer"
        if (s.endsWith(" timer")) {
            TimeWords.duration(s.removeSuffix(" timer"))?.let { return Command.SetTimer(it) }
        }
        return null
    }

    // ---------------------------------------------------------------- alarm

    private val ALARM = Regex(
        """^(?:set|create|make|put|add)?\s*(?:an? )?alarm(?: for| at| to)?\s+(.+)$"""
    )
    private val WAKE_ME = Regex("""^wake me(?: up)?(?: at| for| by)?\s+(.+)$""")

    private fun matchAlarm(s: String): Command? {
        if (s in setOf(
                "show alarms", "show my alarms", "my alarms", "list alarms",
                "what alarms do i have", "open alarms",
            )
        ) {
            return Command.ShowAlarms
        }
        // A duration phrase means a timer even when the speaker said "alarm".
        val body = ALARM.find(s)?.groupValues?.get(1) ?: WAKE_ME.find(s)?.groupValues?.get(1)
        if (body != null) {
            if (body.startsWith("in ")) {
                TimeWords.duration(body)?.let { return Command.SetTimer(it) }
            }
            TimeWords.clock(body)?.let { return Command.SetAlarm(it.hour, it.minute) }
        }
        return null
    }

    // ---------------------------------------------------------------- torch

    private val TORCH_WORDS = listOf("flashlight", "flash light", "torch")

    private fun matchTorch(s: String): Command? {
        if (TORCH_WORDS.none { s.contains(it) }) return null

        // A question about the torch is a question, not an instruction.
        val first = s.split(' ').firstOrNull().orEmpty()
        if (first in setOf("what", "where", "why", "when", "how", "is", "was", "did")) {
            return null
        }

        val off = Regex("""\b(off|disable|kill|switch off|stop)\b""").containsMatchIn(s)
        // Anything else mentioning the torch means turn it on. Recognition drops
        // small words constantly — "turn on the flashlight" came back as "the
        // flashlight" — and requiring the word "on" throws the command away over a
        // syllable the user did say.
        return Command.Torch(!off)
    }

    // -------------------------------------------------------- media control

    private fun matchMediaControl(s: String): Command? = when (s) {
        "pause", "pause music", "pause the music", "pause it", "stop the music",
        "stop music" -> Command.MediaControl(MediaAction.PAUSE)

        "resume", "resume music", "unpause", "continue", "continue music",
        "keep playing", "play it" -> Command.MediaControl(MediaAction.PLAY)

        "next", "next song", "next track", "skip", "skip song", "skip this",
        "skip the song", "play the next song" -> Command.MediaControl(MediaAction.NEXT)

        "previous", "previous song", "previous track", "go back", "back",
        "last song", "play the previous song" -> Command.MediaControl(MediaAction.PREVIOUS)

        else -> null
    }

    // ---------------------------------------------------------------- music

    private val PLAY = Regex("""^play\s+(.+)$""")
    private val GENERIC_MUSIC = setOf(
        "some music", "music", "a song", "some songs", "songs", "something",
        "some tunes", "anything",
    )

    private fun matchMusic(s: String): Command? {
        val what = PLAY.find(s)?.groupValues?.get(1) ?: return null
        if (what in GENERIC_MUSIC) return Command.PlayMusic(null)
        // "play <x> on youtube" — strip the destination, the media intent picks it.
        val cleaned = what.replace(Regex("""\s+on\s+(spotify|youtube|youtube music|music)$"""), "")
        return Command.PlayMusic(cleaned.trim().ifBlank { null })
    }

    // --------------------------------------------------------------- volume

    private val SET_VOLUME = Regex("""^(?:set |change )?volume(?: level)?(?: to)?\s+(\d{1,3})%?$""")

    private fun matchVolume(s: String): Command? {
        SET_VOLUME.find(s)?.let { m ->
            val pct = m.groupValues[1].toIntOrNull()
            if (pct != null && pct in 0..100) return Command.Volume(VolumeChange.Percent(pct))
        }
        return when (s) {
            "volume up", "turn it up", "turn up the volume", "louder", "increase volume",
            "turn the volume up", "volume higher" -> Command.Volume(VolumeChange.Up)

            "volume down", "turn it down", "turn down the volume", "quieter", "softer",
            "decrease volume", "turn the volume down", "lower the volume" ->
                Command.Volume(VolumeChange.Down)

            "mute", "mute it", "silence it", "volume off", "turn off the volume" ->
                Command.Volume(VolumeChange.Mute)

            "max volume", "full volume", "volume max", "loudest", "maximum volume" ->
                Command.Volume(VolumeChange.Max)

            else -> null
        }
    }

    // ------------------------------------------------------------------ dnd

    private fun matchDnd(s: String): Command? {
        if (!s.contains("do not disturb") && !s.contains("dnd") &&
            !s.contains("don't disturb") && !s.contains("silent mode")
        ) {
            return null
        }
        val off = Regex("""\b(off|disable|end|stop|exit)\b""").containsMatchIn(s)
        return Command.Dnd(!off)
    }

    // ----------------------------------------------------------------- call

    private val CALL = Regex("""^(?:call|dial|phone|ring)\s+(.+)$""")
    private val DIGITS = Regex("""^[\d\s+()-]{5,}$""")

    private fun matchCall(s: String): Command? {
        val target = CALL.find(s)?.groupValues?.get(1)?.trim() ?: return null
        if (target.isBlank()) return null
        // "call me back later" and friends are conversation, not a command.
        if (target.startsWith("me ") || target == "me") return null
        if (DIGITS.matches(target)) {
            return Command.CallNumber(target.filter { it.isDigit() || it == '+' })
        }
        // Strip a trailing "on speaker" / "on whatsapp" so it is not part of the name.
        val name = target.replace(Regex("""\s+on\s+\w+$"""), "").trim()
        if (name.length > 40) return null
        return Command.Call(name)
    }

    // -------------------------------------------------------------- message

    private val MESSAGE = Regex(
        """^(?:text|message|sms|whatsapp)\s+(.+?)(?:\s+(?:saying|that says|and say|with)\s+(.+))?$"""
    )
    private val SEND_MESSAGE = Regex(
        """^send (?:a )?(?:text|message|sms|whatsapp)(?: message)? to\s+(.+?)(?:\s+(?:saying|that says|and say|with)\s+(.+))?$"""
    )

    private fun matchMessage(s: String): Command? {
        val m = SEND_MESSAGE.find(s) ?: MESSAGE.find(s) ?: return null
        val who = m.groupValues[1].trim()
        if (who.isBlank() || who.length > 40) return null
        val body = m.groupValues.getOrNull(2)?.trim()?.ifBlank { null }
        return Command.SendMessage(who, body)
    }

    // ------------------------------------------------------------- navigate

    private val NAVIGATE = Regex(
        """^(?:navigate to|directions to|take me to|drive to|route to|how do i get to|get directions to)\s+(.+)$"""
    )

    private fun matchNavigate(s: String): Command? =
        NAVIGATE.find(s)?.groupValues?.get(1)?.trim()?.ifBlank { null }
            ?.let { Command.Navigate(it) }

    // --------------------------------------------------------------- camera

    private val CAMERA = setOf(
        "open camera", "open the camera", "camera", "take a photo", "take a picture",
        "take a selfie", "selfie", "launch camera", "start camera",
    )

    private fun matchCamera(s: String): Command? =
        if (s in CAMERA) Command.OpenCamera else null

    // ---------------------------------------------------------------- query

    private val TIME_Q = setOf(
        "what time is it", "what's the time", "whats the time", "the time",
        "time", "tell me the time", "what is the time", "current time",
    )
    private val DATE_Q = setOf(
        "what's the date", "whats the date", "what is the date", "what day is it",
        "what's today", "whats today", "today's date", "todays date", "the date",
        "what day is today",
    )
    private val BATTERY_Q = setOf(
        "battery", "battery level", "how much battery", "how much battery do i have",
        "what's my battery", "whats my battery", "battery percentage",
        "how much charge", "battery status",
    )

    private fun matchQuery(s: String): Command? = when (s) {
        in TIME_Q -> Command.TimeQuery
        in DATE_Q -> Command.DateQuery
        in BATTERY_Q -> Command.BatteryStatus
        else -> null
    }

    // ---------------------------------------------------------------- panel

    /**
     * Radios cannot be toggled by an app since Android 10, so the honest thing is to
     * put the user one tap away rather than to pretend and silently fail.
     */
    private fun matchPanel(s: String): Command? {
        val isToggle = Regex("""\b(turn|switch|enable|disable|connect|open)\b""").containsMatchIn(s)
        if (!isToggle) return null
        return when {
            s.contains("wifi") || s.contains("wi-fi") || s.contains("wi fi") ->
                Command.OpenPanel(Panel.WIFI)

            s.contains("bluetooth") -> Command.OpenPanel(Panel.BLUETOOTH)

            s.contains("mobile data") || s.contains("cellular") || s.contains("internet") ->
                Command.OpenPanel(Panel.INTERNET)

            s.contains("nfc") -> Command.OpenPanel(Panel.NFC)
            else -> null
        }
    }

    // ------------------------------------------------------------- open app

    private val OPEN_APP = Regex("""^(?:open|launch|start|run|go to)\s+(?:the )?(.+?)(?: app)?$""")

    private fun matchOpenApp(s: String): Command? {
        if (s == "open settings" || s == "settings" || s == "open the settings") {
            return Command.OpenPanel(Panel.SETTINGS)
        }
        val name = OPEN_APP.find(s)?.groupValues?.get(1)?.trim() ?: return null
        // Two words is about the longest real app name spoken aloud; beyond that it
        // is far more likely to be a sentence that happens to start with "start".
        if (name.isBlank() || name.split(' ').size > 3) return null
        return Command.OpenApp(name)
    }

    // --------------------------------------------------------------- search

    private val SEARCH = Regex(
        """^(?:search(?: for)?|google|look up|find|search the web for)\s+(.+)$"""
    )

    private fun matchSearch(s: String): Command? =
        SEARCH.find(s)?.groupValues?.get(1)?.trim()?.ifBlank { null }
            ?.let { Command.Search(it) }
}
