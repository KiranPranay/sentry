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

    // ---------------------------------------------------------------- comms

    /** Call someone by name; the contact still has to be resolved and may be ambiguous. */
    data class Call(val query: String) : Command
    data class CallNumber(val number: String) : Command
    data object AnswerCall : Command
    data object HangUp : Command
    data class SendMessage(val query: String, val body: String?) : Command

    // ---------------------------------------------------------------- media

    data class PlayMusic(val query: String?) : Command
    data class MediaControl(val action: MediaAction) : Command

    // --------------------------------------------------------------- device

    data class Torch(val on: Boolean) : Command
    data class Volume(val change: VolumeChange) : Command
    data class Dnd(val on: Boolean) : Command
    data object OpenCamera : Command
    data object BatteryStatus : Command
    data class OpenPanel(val panel: Panel) : Command

    // ----------------------------------------------------------------- apps

    data class OpenApp(val name: String) : Command
    data class Search(val query: String) : Command
    data class Navigate(val destination: String) : Command

    // -------------------------------------------------------------- answers

    /** Something Sentry can answer from the device itself, with no model involved. */
    data object TimeQuery : Command
    data object DateQuery : Command

    /** Free-form conversation. The only command that necessarily costs a generation. */
    data class Chat(val text: String) : Command

    /** "the second one" — resolves against whatever list was last offered. */
    data class Choose(val index: Int) : Command

    data object Stop : Command
}

enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }

sealed interface VolumeChange {
    data object Up : VolumeChange
    data object Down : VolumeChange
    data object Mute : VolumeChange
    data object Max : VolumeChange
    data class Percent(val value: Int) : VolumeChange
}

enum class Panel { WIFI, BLUETOOTH, INTERNET, NFC, SETTINGS }
