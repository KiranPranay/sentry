package com.sentry.core

/**
 * Something Sentry can actually do.
 *
 * Commands are produced two ways: by [com.sentry.nlu.FastMatcher] in under a
 * millisecond for the phrasings people actually use, and by the language model for
 * everything else. Both paths land here, so a skill never knows or cares which one
 * understood the user.
 */
sealed interface Command {

    // ----------------------------------------------------------------- time

    data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null) : Command
    data class SetTimer(val seconds: Int, val label: String? = null) : Command
    data object ShowAlarms : Command
    data object ShowTimers : Command

    /**
     * Stop what is counting down.
     *
     * Separate from [ShowTimers] because "cancel the timer" is the one thing people
     * say to a timer more often than they say anything else, and sending them to a
     * list of timers to tap one themselves is not an answer.
     */
    data object CancelTimer : Command
    data object CancelAlarm : Command

    // ---------------------------------------------------------------- comms

    /** Call someone by name; the contact still has to be resolved and may be ambiguous. */
    data class Call(val query: String) : Command
    data class CallNumber(val number: String) : Command
    data object AnswerCall : Command
    data object HangUp : Command
    data class SendMessage(
        val query: String,
        val body: String?,
        val channel: Channel = Channel.SMS,
    ) : Command

    // ---------------------------------------------------------------- media

    /** @param provider where to play it, when the user named somewhere. */
    data class PlayMusic(val query: String?, val provider: Provider? = null) : Command
    data class MediaControl(val action: MediaAction) : Command

    // --------------------------------------------------------------- device

    data class Torch(val on: Boolean) : Command
    data class Volume(val change: VolumeChange) : Command
    data class Brightness(val change: LevelChange) : Command

    /**
     * Read a level back instead of setting it.
     *
     * "The device brightness" is a question, and the only reason it was ever answered
     * by turning the volume up is that there was nothing here for it to become. The
     * classifier had to choose between fifteen labels, none of which meant "tell me
     * the brightness", so it picked one that at least involved a percentage.
     */
    data object VolumeQuery : Command
    data object BrightnessQuery : Command

    /**
     * The ringer, which is neither the media volume nor Do Not Disturb.
     *
     * "Keep the device in silent" reached the conversation tier, which said "Ok, I'll
     * keep the device in silent" and did nothing at all — the worst failure in the
     * system, because it is indistinguishable from success until the phone rings.
     */
    data class Silent(val on: Boolean) : Command
    data class Dnd(val on: Boolean) : Command
    data object OpenCamera : Command
    data object BatteryStatus : Command
    data object Screenshot : Command
    data class OpenPanel(val panel: Panel) : Command

    // ----------------------------------------------------------------- apps

    data class OpenApp(val name: String) : Command
    data class Search(val query: String) : Command
    data class Navigate(val destination: String) : Command

    // -------------------------------------------------------------- answers

    /** Something Sentry can answer from the device itself, with no model involved. */
    data object TimeQuery : Command
    data object DateQuery : Command

    /** A sum already worked out; [spoken] is the answer, not the question. */
    data class Calculate(val expression: String, val spoken: String) : Command

    /** Free-form conversation. The only command that necessarily costs a generation. */
    data class Chat(val text: String) : Command

    /** "the second one" — resolves against whatever list was last offered. */
    data class Choose(val index: Int) : Command

    data object Stop : Command
}

enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }

/**
 * Where to play something.
 *
 * [canonical] is the official package, but it is only a preference. People replace
 * these apps: on the phone this was built for, Google's YouTube Music is disabled and
 * a third-party client stands in for it. Refusing with "YouTube Music isn't
 * installed" while the user is looking at their YouTube Music icon is the kind of
 * wrongness that makes an assistant feel stupid, so [label] is used to find whatever
 * client is actually there.
 */
enum class Provider(
    val label: String,
    val canonical: String,
    /**
     * Names the launcher might show instead.
     *
     * Not hypothetical: the replacement client on this phone is labelled "YT Music",
     * so searching for "YouTube Music" found nothing and Sentry insisted the app was
     * not installed while its icon sat on the home screen.
     */
    val aliases: List<String> = emptyList(),
) {
    SPOTIFY("Spotify", "com.spotify.music"),
    YOUTUBE("YouTube", "com.google.android.youtube"),
    YOUTUBE_MUSIC(
        "YouTube Music",
        "com.google.android.apps.youtube.music",
        aliases = listOf("YT Music", "Youtube Music", "Music"),
    ),
}

/** How to send a message. */
enum class Channel(val label: String, val packageName: String?) {
    SMS("Messages", null),
    WHATSAPP("WhatsApp", "com.whatsapp"),
}

/**
 * How far to move something that has a level.
 *
 * Shared by brightness and anything else that turns out to work the same way. Volume
 * keeps its own type because it has a state the others do not — muted is not the same
 * as zero, and a screen cannot be muted.
 */
sealed interface LevelChange {
    data object Up : LevelChange
    data object Down : LevelChange
    data object Max : LevelChange
    data object Min : LevelChange
    data class Percent(val value: Int) : LevelChange

    /**
     * Move [delta] percentage points from wherever it is now, signed.
     *
     * "Lower the volume by fifty percent" is not "lower the volume", and without this
     * the two were the same command: a single hardware step, which on a 25-step
     * stream is four percent. Asked for half, the phone gave a twenty-fifth.
     */
    data class By(val delta: Int) : LevelChange
}

sealed interface VolumeChange {
    data object Up : VolumeChange
    data object Down : VolumeChange
    data object Mute : VolumeChange
    data object Max : VolumeChange
    data class Percent(val value: Int) : VolumeChange

    /** Signed move from the current level, in percentage points. See [LevelChange.By]. */
    data class By(val delta: Int) : VolumeChange
}

enum class Panel { WIFI, BLUETOOTH, INTERNET, NFC, SETTINGS }

/**
 * How costly it is to have run a command you did not mean.
 *
 * This exists because the recogniser splits sentences, and the obvious repair — act
 * on the fragment, then cancel and redo when the rest arrives — cannot work. There
 * is no suspension point between [Agent.handle] and the [android.content.Intent]
 * that dials a number, so a Kotlin cancellation arriving from the next fragment is
 * always too late: the phone is already ringing. Alarms are worse still, being set
 * with EXTRA_SKIP_UI and having no delete intent at all, so a merged correction
 * leaves two alarms and no way to remove the wrong one.
 *
 * So the order is inverted: commands that cannot be taken back wait a moment to see
 * whether the sentence was finished, and everything else runs immediately as before.
 */
enum class Commit {
    /** Answers a question and touches nothing. Free to run whenever. */
    PURE,

    /** Sets an absolute state. Running it twice lands in the same place. */
    IDEMPOTENT,

    /** A step, not a destination. Running it twice moves twice as far. */
    RELATIVE,

    /** Reaches a person, the network, or persistent state with no undo. */
    IRREVERSIBLE,
}

/**
 * Deliberately exhaustive with no `else`: adding a [Command] without deciding how
 * costly it is to get wrong should fail to compile, not default to "safe".
 */
val Command.commit: Commit
    get() = when (this) {
        is Command.TimeQuery, is Command.DateQuery, is Command.BatteryStatus,
        is Command.Calculate, is Command.Chat,
        // Reading a level back changes nothing, which is exactly why the fast path
        // is allowed to accept a shape it would otherwise have to refuse.
        is Command.VolumeQuery, is Command.BrightnessQuery -> Commit.PURE

        // Writes a file and flashes the screen, but replaces nothing and harms
        // nothing if it happens twice.
        is Command.Screenshot -> Commit.IDEMPOTENT

        is Command.Torch, is Command.Dnd, is Command.OpenCamera, is Command.OpenApp,
        is Command.OpenPanel, is Command.ShowAlarms, is Command.ShowTimers ->
            Commit.IDEMPOTENT

        is Command.Brightness -> when (change) {
            is LevelChange.Up, is LevelChange.Down, is LevelChange.By -> Commit.RELATIVE
            is LevelChange.Max, is LevelChange.Min, is LevelChange.Percent ->
                Commit.IDEMPOTENT
        }

        // Silencing the ringer is a state, not a nudge, and saying it twice leaves
        // the phone exactly as silent as saying it once.
        is Command.Silent -> Commit.IDEMPOTENT

        is Command.Volume -> when (change) {
            is VolumeChange.Up, is VolumeChange.Down, is VolumeChange.By -> Commit.RELATIVE
            is VolumeChange.Mute, is VolumeChange.Max, is VolumeChange.Percent ->
                Commit.IDEMPOTENT
        }

        is Command.MediaControl -> when (action) {
            MediaAction.NEXT, MediaAction.PREVIOUS -> Commit.RELATIVE
            MediaAction.PLAY, MediaAction.PAUSE -> Commit.IDEMPOTENT
        }

        // Reaches somebody, or persists with no way back.
        is Command.Call, is Command.CallNumber, is Command.AnswerCall,
        is Command.HangUp, is Command.SendMessage, is Command.SetAlarm,
        is Command.SetTimer, is Command.Search, is Command.Navigate,
        is Command.PlayMusic -> Commit.IRREVERSIBLE

        // Repeating them is harmless, but a countdown that has been thrown away
        // cannot be got back, so they wait out the stitching window like the rest.
        is Command.CancelTimer, is Command.CancelAlarm -> Commit.IRREVERSIBLE

        // Not destructive in themselves, but both consume one-way state: Choose eats
        // the pending disambiguation list, and Stop ends the session for good.
        is Command.Choose, is Command.Stop -> Commit.IRREVERSIBLE
    }
