package com.sentry.nlu

import com.sentry.core.Channel
import com.sentry.core.Command
import com.sentry.core.MediaAction
import com.sentry.core.Panel
import com.sentry.core.Provider
import com.sentry.core.LevelChange
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
            ?: Levels.match(s)
            ?: matchDnd(s)
            ?: matchCall(s)
            ?: matchMessage(s)
            ?: matchNavigate(s)
            ?: matchCamera(s)
            ?: matchScreenshot(s)
            ?: matchArithmetic(raw)
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

    /**
     * Asking to see a list, in any of the ways people ask.
     *
     * These were sets of exact phrases, which meant "show me my alarms" — one word
     * longer than "show my alarms" — fell through to the language model and came back
     * as conversation. A fixed phrase list is a phrasebook, not an understanding.
     */
    private val CANCEL_TIMER = Regex(
        """^(?:cancel|stop|delete|dismiss|turn off|kill|end)\s+(?:the |my |that )?timers?$""" +
            """|^timer off$"""
    )
    private val CANCEL_ALARM = Regex(
        """^(?:cancel|stop|delete|dismiss|turn off|kill|snooze off)\s+(?:the |my |that )?alarms?$""" +
            """|^alarm off$"""
    )

    private val SHOW_TIMERS = Regex(
        """^(?:show|list|see|check|open|what|what are)?\s*(?:me )?(?:my |the )?timers?""" +
            """(?: do i have| are (?:there|running|set)| i have)?$"""
    )
    private val SHOW_ALARMS = Regex(
        """^(?:show|list|see|check|open|what|what are)?\s*(?:me )?(?:my |the )?alarms?""" +
            """(?: do i have| are (?:there|set)| i have)?$"""
    )

    private fun matchTimer(s: String): Command? {
        if (CANCEL_TIMER.matches(s)) return Command.CancelTimer
        if (SHOW_TIMERS.matches(s)) return Command.ShowTimers
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
        if (CANCEL_ALARM.matches(s)) return Command.CancelAlarm
        if (SHOW_ALARMS.matches(s)) return Command.ShowAlarms
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

    // ----------------------------------------------------------- screenshot

    private val SCREENSHOT = setOf(
        "take a screenshot", "screenshot", "take screenshot", "capture the screen",
        "take a screen shot", "screen shot", "grab the screen",
    )

    private fun matchScreenshot(s: String): Command? =
        if (s in SCREENSHOT) Command.Screenshot else null

    // ---------------------------------------------------------------- music

    /**
     * Apps people name when they say where to play something.
     *
     * Matched on the words actually spoken rather than the package label, so
     * "youtube music" has to be tried before "youtube" or it never wins.
     */
    private val PROVIDERS: List<Pair<Regex, Provider>> = listOf(
        Regex("""\b(?:on |in |using )?youtube music\b""") to Provider.YOUTUBE_MUSIC,
        Regex("""\b(?:on |in |using )?yt music\b""") to Provider.YOUTUBE_MUSIC,
        Regex("""\b(?:on |in |using )?spotify\b""") to Provider.SPOTIFY,
        Regex("""\b(?:on |in |using )?youtube\b""") to Provider.YOUTUBE,
    )

    private val PLAY = Regex("""^play\s+(.+)$""")
    private val GENERIC_MUSIC = setOf(
        "some music", "music", "a song", "some songs", "songs", "something",
        "some tunes", "anything",
    )

    private fun matchMusic(s: String): Command? {
        val what = PLAY.find(s)?.groupValues?.get(1) ?: return null

        // "play something on Spotify" names both the thing and the place; take the
        // place out so it does not end up in the search query.
        var query = what
        var provider: Provider? = null
        for ((pattern, candidate) in PROVIDERS) {
            if (pattern.containsMatchIn(query)) {
                provider = candidate
                query = pattern.replace(query, " ")
                break
            }
        }
        query = query.replace(Regex("""\s+"""), " ").trim().removeSuffix(" on").trim()

        if (query.isBlank() || query in GENERIC_MUSIC) return Command.PlayMusic(null, provider)
        return Command.PlayMusic(query, provider)
    }

    // --------------------------------------------------------------- volume

    private val SET_VOLUME = Regex(
        """^(?:set |change |put )?(?:the )?volume(?: level)?(?: to| at)?\s+(.+?)%?$"""
    )

    /** Anything a person might call the sound coming out of the phone. */
    private const val SOUND = """(?:it|the (?:volume|sound|audio|phone|music))"""

    // Every alternative names the thing being changed. An earlier draft made both the
    // verb and the object optional, which meant a bare "up" — a perfectly ordinary
    // word to say mid-sentence — turned the volume up.
    private val VOLUME_UP = Regex(
        """^volume (?:up|higher)$""" +
            """|^(?:make )?(?:it )?louder$|^a (?:bit|little) louder$""" +
            """|^(?:turn|bump|crank) up $SOUND$|^(?:turn|bump|crank) $SOUND up$""" +
            """|^(?:increase|raise)(?: the)? volume$"""
    )
    private val VOLUME_DOWN = Regex(
        """^volume (?:down|lower)$""" +
            """|^(?:make )?(?:it )?(?:quieter|softer)$|^a (?:bit|little) (?:quieter|softer)$""" +
            """|^(?:turn|bring) down $SOUND$|^(?:turn|bring) $SOUND down$""" +
            """|^(?:decrease|lower|reduce)(?: the)? volume$"""
    )
    private val MUTE = Regex(
        """^mute(?: $SOUND)?$|^silence $SOUND$|^volume off$""" +
            """|^(?:turn|switch) off (?:$SOUND|the sound|the audio)$""" +
            """|^no sound$"""
    )
    private val MAX_VOLUME = Regex(
        """^(?:max|maximum|full|highest|loudest) volume$|^volume (?:max|maximum)$|^loudest$"""
    )

    private val SET_BRIGHTNESS = Regex(
        """^(?:set |change |put )?(?:the )?(?:screen )?brightness(?: level)?(?: to| at)?\s+(.+?)%?$"""
    )
    private const val SCREEN = """(?:it|the (?:brightness|screen|display))"""

    private val BRIGHTER = Regex(
        """^brightness up$|^(?:make )?(?:it )?brighter$|^a (?:bit|little) brighter$""" +
            """|^(?:turn|bump) up $SCREEN$|^(?:turn|bump) $SCREEN up$""" +
            """|^(?:increase|raise)(?: the)? (?:brightness|screen brightness)$""" +
            """|^brighten(?: $SCREEN)?$"""
    )
    private val DIMMER = Regex(
        """^brightness down$|^(?:make )?(?:it )?dimmer$|^a (?:bit|little) dimmer$""" +
            """|^(?:turn|bring) down $SCREEN$|^(?:turn|bring) $SCREEN down$""" +
            """|^(?:decrease|lower|reduce)(?: the)? (?:brightness|screen brightness)$""" +
            """|^dim(?: $SCREEN)?$"""
    )
    private val MAX_BRIGHTNESS = Regex(
        """^(?:max|maximum|full|highest|brightest)(?: brightness)?$|^brightness (?:max|maximum)$"""
    )
    private val MIN_BRIGHTNESS = Regex(
        """^(?:min|minimum|lowest|dimmest)(?: brightness)?$|^brightness (?:min|minimum)$"""
    )

    private fun matchBrightness(s: String): Command? {
        if (!s.contains("bright") && !s.contains("dim") && !s.contains("screen") &&
            !s.contains("display")
        ) {
            return null
        }
        SET_BRIGHTNESS.find(s)?.let { m ->
            val pct = Arithmetic.number(m.groupValues[1])?.toInt()
            if (pct != null && pct in 0..100) return Command.Brightness(LevelChange.Percent(pct))
        }
        return when {
            MAX_BRIGHTNESS.matches(s) -> Command.Brightness(LevelChange.Max)
            MIN_BRIGHTNESS.matches(s) -> Command.Brightness(LevelChange.Min)
            BRIGHTER.matches(s) -> Command.Brightness(LevelChange.Up)
            DIMMER.matches(s) -> Command.Brightness(LevelChange.Down)
            else -> null
        }
    }

    private fun matchVolume(s: String): Command? {
        SET_VOLUME.find(s)?.let { m ->
            // Spoken as often as typed: "set the volume to fifty" is the same request
            // as "volume 50", and only one of them used to work.
            val pct = Arithmetic.number(m.groupValues[1])?.toInt()
            if (pct != null && pct in 0..100) return Command.Volume(VolumeChange.Percent(pct))
        }
        return when {
            MUTE.matches(s) -> Command.Volume(VolumeChange.Mute)
            MAX_VOLUME.matches(s) -> Command.Volume(VolumeChange.Max)
            VOLUME_UP.matches(s) -> Command.Volume(VolumeChange.Up)
            VOLUME_DOWN.matches(s) -> Command.Volume(VolumeChange.Down)
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

    private val WHATSAPP = Regex("""\b(?:on |in |via |using )?whats ?app\b""")

    private val MESSAGE = Regex(
        """^(?:text|message|sms|whatsapp)\s+(.+?)(?:\s+(?:saying|that says|and say|with)\s+(.+))?$"""
    )
    private val SEND_MESSAGE = Regex(
        """^send (?:a )?(?:text|message|sms|whatsapp)(?: message)? to\s+(.+?)(?:\s+(?:saying|that says|and say|with)\s+(.+))?$"""
    )

    private fun matchMessage(s: String): Command? {
        val m = SEND_MESSAGE.find(s) ?: MESSAGE.find(s) ?: return null
        var who = m.groupValues[1].trim()
        val body = m.groupValues.getOrNull(2)?.trim()?.ifBlank { null }

        // The channel can be named at either end — "whatsapp mum" or "text mum on
        // whatsapp" — and either way it is not part of the person's name.
        val channel = if (WHATSAPP.containsMatchIn(s)) Channel.WHATSAPP else Channel.SMS
        who = WHATSAPP.replace(who, " ").replace(Regex("""\s+"""), " ").trim()

        if (who.isBlank() || who.length > 40) return null
        return Command.SendMessage(who, body, channel)
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

    // ----------------------------------------------------------- arithmetic

    /**
     * Takes the raw utterance rather than the normalised one, because the normaliser
     * strips the punctuation and casing that [Arithmetic] uses to recognise a sum.
     */
    private fun matchArithmetic(raw: String): Command? =
        Arithmetic.evaluate(raw)?.let { Command.Calculate(it.expression, it.spoken) }

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
    private val BATTERY_Q = Regex(
        """^(?:what(?:'s| is)|hows|how(?:'s| is)|check|tell me)?\s*""" +
            """(?:my |the )?battery(?: level| percentage| status| charge| percent)?""" +
            """(?: (?:at|left|remaining|doing))?$|""" +
            """^how much (?:battery|charge)""" +
            """(?: (?:is (?:left|there|remaining)|do i have|have i got))?$|""" +
            """^(?:am i|is it) charging$"""
    )

    private fun matchQuery(s: String): Command? = when {
        s in TIME_Q -> Command.TimeQuery
        s in DATE_Q -> Command.DateQuery
        BATTERY_Q.matches(s) -> Command.BatteryStatus
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
