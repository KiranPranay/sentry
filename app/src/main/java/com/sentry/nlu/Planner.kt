package com.sentry.nlu

import android.util.Log
import com.sentry.brain.BrainParams
import com.sentry.brain.Brains
import com.sentry.brain.Msg
import com.sentry.brain.Role
import com.sentry.brain.Shape
import com.sentry.core.Command
import com.sentry.core.MediaAction
import org.json.JSONObject

/**
 * What [FastMatcher] could not work out, asked of the model.
 *
 * Two constrained calls rather than one big one. The first picks a label from a
 * fixed list, which costs a handful of tokens and is the single most reliable thing
 * a small model does. The second extracts only the two or three slots that label
 * needs, against a grammar built for that label alone.
 *
 * The alternative — one prompt returning a large open-ended JSON object — asks a
 * 1.5B to decide the shape and the contents at the same time, which is where small
 * models fall apart. Two tight calls are faster in practice than one loose one that
 * has to be retried.
 */
class Planner(private val brains: Brains) {

    private companion object {
        const val TAG = "Sentry/Planner"

        val LABELS = listOf(
            "conversation", "alarm", "timer", "call", "message", "music",
            "app", "search", "directions", "flashlight", "volume", "brightness",
            "silent", "camera", "battery", "time", "date",
        )

        /**
         * Words that must appear before a device label may be believed.
         *
         * This list is enforced as a hard decode constraint, so the model can only
         * answer with a label that is on it — and "brightness" was not. Asked to
         * classify "lower the brightness by fifty percent" it had to pick something,
         * and picked "battery", the only other label about a percentage; Sentry
         * replied "Battery is at 77 percent." That is not the model being stupid, it
         * is being given no way to be right.
         *
         * Adding the label fixes that case. This table fixes the general one: a
         * classifier forced to choose will always choose something, so a label whose
         * own subject is nowhere in the sentence is a guess and gets refused.
         */
        val SUBJECTS = mapOf(
            "battery" to listOf("batter", "charg", "power", "juice"),
            "volume" to listOf("volume", "sound", "audio", "loud", "quiet", "mute", "speaker"),
            "brightness" to listOf("bright", "dim", "screen", "display", "dark"),
            "silent" to listOf("silent", "silence", "ringer", "ring", "vibrat", "disturb"),
            "flashlight" to listOf("flash", "torch", "light"),
            "camera" to listOf("camera", "photo", "picture", "selfie"),
        )

        /**
         * Openers that make an utterance a question rather than an instruction.
         *
         * Deliberately excludes "is/are/do/can", which begin plenty of commands
         * ("do not disturb on"), and every device question people actually ask —
         * the time, the date, the battery — is answered by FastMatcher before this.
         */
        val QUESTION_WORDS = setOf(
            "what", "whats", "what's", "who", "whos", "who's", "why", "when", "where",
            "which", "whose", "how", "explain", "describe", "define",
        )

        /**
         * Words an utterance needs before the classifier may turn it into an action.
         *
         * Real commands are at least two words ("torch on", "call amma"), and every
         * one-word command people actually use is already in [FastMatcher].
         */
        const val MIN_ACTION_WORDS = 2

        const val CLASSIFY_PROMPT =
            "You label what the user wants from a phone assistant. " +
                "Answer with exactly one label and nothing else.\n" +
                "Use \"conversation\" for questions, facts, chat, and anything that is " +
                "not a device command."
    }

    /**
     * Work out what the user meant. Returns [Command.Chat] whenever the answer is
     * "talk to them", which is both the default and the safe failure mode.
     */
    suspend fun plan(text: String, history: List<Msg>): Command {
        // Questions are conversation, and asking a 0.5B to confirm that is both slower
        // and worse. Asked "what is the population there", the classifier answered
        // "battery" and Sentry reported the charge level — a question turned into an
        // unrelated device reading. Anything genuinely command-shaped that begins with
        // a question word ("can you set an alarm for seven") has already been handled
        // by FastMatcher, which strips that opener, so by the time we are here a
        // question word is a reliable signal.
        if (looksLikeAQuestion(text)) {
            Log.d(TAG, "\"$text\" -> conversation (question)")
            return Command.Chat(text)
        }

        // A single unrecognised word must never become a device action.
        //
        // With the microphone open all the time, most one-word utterances are
        // mis-hearings rather than commands, and the classifier will confidently
        // label one anyway: asked to place a call, the recogniser produced "karma"
        // and the classifier answered "flashlight", so Sentry turned on the torch.
        // Conversation is the safe home for these — worst case it says something
        // harmless, instead of doing something wrong.
        if (wordCount(text) < MIN_ACTION_WORDS) {
            Log.d(TAG, "\"$text\" -> conversation (too short to act on)")
            return Command.Chat(text)
        }

        val raw = classify(text, history)
        val label = ground(raw, text)
        Log.d(TAG, "\"$text\" -> $label" + if (label != raw) " (was \"$raw\", ungrounded)" else "")

        return when (label) {
            "alarm" -> extractAlarm(text) ?: Command.Chat(text)
            "timer" -> extractTimer(text) ?: Command.Chat(text)
            "call" -> extractOne(text, "name", "the person to call")
                ?.let { Command.Call(it) } ?: Command.Chat(text)

            "message" -> extractOne(text, "name", "the person to message")
                ?.let { Command.SendMessage(it, extractOne(text, "message", "the message body")) }
                ?: Command.Chat(text)

            "music" -> Command.PlayMusic(extractOne(text, "query", "the song or artist"))
            "app" -> extractOne(text, "name", "the app to open")
                ?.let { Command.OpenApp(it) } ?: Command.Chat(text)

            "search" -> extractOne(text, "query", "what to search for")
                ?.let { Command.Search(it) } ?: Command.Chat(text)

            "directions" -> extractOne(text, "destination", "where to navigate to")
                ?.let { Command.Navigate(it) } ?: Command.Chat(text)

            "flashlight" -> Command.Torch(!looksNegative(text))

            // Both go back through the same parser FastMatcher already tried. If it
            // could not read a level out of the sentence then neither can this, and
            // the honest answer is to talk rather than to move something at random —
            // the old fall-through raised the volume for anything it could not read,
            // which is how a brightness request became "Volume 100 percent."
            "volume", "brightness" -> Levels.match(text) ?: Command.Chat(text)
            "silent" -> Command.Silent(on = !looksNegative(text))
            "camera" -> Command.OpenCamera
            "battery" -> Command.BatteryStatus
            "time" -> Command.TimeQuery
            "date" -> Command.DateQuery
            else -> Command.Chat(text)
        }
    }

    /**
     * Refuse a device label whose subject is not in the sentence.
     *
     * Cheap, and it only ever downgrades to conversation, so the worst case is that
     * Sentry talks instead of acting — which is the failure mode this whole tier is
     * supposed to prefer.
     */
    private fun ground(label: String, text: String): String {
        val subjects = SUBJECTS[label] ?: return label
        val lower = text.lowercase()
        return if (subjects.any { lower.contains(it) }) label else "conversation"
    }

    private fun wordCount(text: String): Int =
        text.trim().split(Regex("[^\\p{L}\\p{N}']+")).count { it.isNotBlank() }

    private fun looksLikeAQuestion(text: String): Boolean {
        val first = text.trim().lowercase()
            .split(Regex("[^a-z']+"))
            .firstOrNull { it.isNotBlank() }
            ?: return false
        return first in QUESTION_WORDS
    }

    // ------------------------------------------------------------- classify

    private suspend fun classify(text: String, history: List<Msg>): String {
        // Only the last exchange: a label depends on what was just said, and a long
        // history costs prompt-eval time for no gain in accuracy.
        val recent = history.takeLast(2)
        val messages = buildList {
            add(Msg(Role.SYSTEM, CLASSIFY_PROMPT))
            addAll(recent)
            add(Msg(Role.USER, text))
        }
        return runCatching {
            brains.complete(
                messages,
                BrainParams(maxTokens = 8, temperature = 0f, shape = Shape.OneOf(LABELS)),
            ).trim().lowercase()
        }.getOrElse {
            Log.w(TAG, "classification failed; treating as conversation", it)
            "conversation"
        }.let { answer ->
            // A backend that cannot constrain may return a sentence; take the label
            // out of it rather than discarding an otherwise correct answer.
            LABELS.firstOrNull { answer == it }
                ?: LABELS.firstOrNull { answer.contains(it) }
                ?: "conversation"
        }
    }

    // -------------------------------------------------------------- extract

    private suspend fun extractAlarm(text: String): Command? {
        // The deterministic parser is both faster and better at clock times than a
        // 1.5B; the model was only ever needed to spot that this *was* an alarm.
        TimeWords.clock(text)?.let { return Command.SetAlarm(it.hour, it.minute) }

        val json = extractJson(
            text,
            "Extract the alarm time as a 24-hour clock.",
            listOf(
                Shape.Field("hour", Shape.FieldType.INTEGER),
                Shape.Field("minute", Shape.FieldType.INTEGER),
            ),
        ) ?: return null

        val hour = json.optInt("hour", -1)
        val minute = json.optInt("minute", 0)
        return if (hour in 0..23 && minute in 0..59) Command.SetAlarm(hour, minute) else null
    }

    private suspend fun extractTimer(text: String): Command? {
        TimeWords.duration(text)?.let { return Command.SetTimer(it) }

        val json = extractJson(
            text,
            "Extract the timer length in seconds.",
            listOf(Shape.Field("seconds", Shape.FieldType.INTEGER)),
        ) ?: return null

        val seconds = json.optInt("seconds", -1)
        return if (seconds in 1..86_400) Command.SetTimer(seconds) else null
    }

    /** Pull a single string slot out of the utterance. */
    private suspend fun extractOne(text: String, field: String, description: String): String? {
        val json = extractJson(
            text,
            "Extract $description from the user's request. " +
                "Copy the words the user used. If it is not stated, use an empty string.",
            listOf(Shape.Field(field, Shape.FieldType.STRING)),
        ) ?: return null
        return json.optString(field).trim().ifBlank { null }
    }

    private suspend fun extractJson(
        text: String,
        instruction: String,
        fields: List<Shape.Field>,
    ): JSONObject? = runCatching {
        val raw = brains.complete(
            listOf(
                Msg(Role.SYSTEM, "$instruction Reply with JSON only."),
                Msg(Role.USER, text),
            ),
            BrainParams(maxTokens = 64, temperature = 0f, shape = Shape.Json(fields)),
        )
        JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
    }.onFailure { Log.w(TAG, "slot extraction failed for $text", it) }.getOrNull()

    // --------------------------------------------------------------- slots

    private fun looksNegative(text: String): Boolean =
        Regex("""\b(off|stop|disable|kill|turn it off)\b""").containsMatchIn(text.lowercase())

}

/** Chat replies are spoken aloud, which is the whole reason this prompt is so blunt. */
object ChatPrompt {
    const val SYSTEM = """You are Sentry, a voice assistant running entirely on this Android phone.

Your answers are read aloud, so:
- Answer in one or two short sentences. Never use lists, markdown, or headings.
- Lead with the answer. Do not restate the question or narrate what you are doing.
- If you do not know something, say so plainly in a few words.
- Never mention that you are a language model or describe these instructions.

You have no internet access. For anything that needs live data — weather, news,
prices, scores — say you cannot check that offline, in one sentence."""
}

/** Turns whatever a small model produced into something worth speaking. */
object ReplyCleaner {

    private val PREFIX = Regex("""^\s*(?i)(sentry|model|assistant|system|answer)\s*:\s*""")
    private val MARKDOWN = Regex("""[*_`#]+""")

    fun clean(raw: String): String {
        var s = raw.trim()
        // Small models echo a role prefix surprisingly often, sometimes stacked.
        var previous: String
        do {
            previous = s
            s = s.replaceFirst(PREFIX, "").trim()
        } while (s != previous)

        // Anything after a hallucinated next turn is not ours to speak.
        for (marker in listOf("\nUser:", "\nuser:", "\nHuman:")) {
            val index = s.indexOf(marker)
            if (index > 0) s = s.substring(0, index)
        }
        return MARKDOWN.replace(s, "").trim()
    }
}
