package com.sentry.core

import android.util.Log
import com.sentry.brain.BrainParams
import com.sentry.brain.Brains
import com.sentry.brain.Msg
import com.sentry.brain.NoBrainException
import com.sentry.brain.Role
import com.sentry.data.Memory
import com.sentry.data.PhraseBook
import com.sentry.nlu.ChatPrompt
import com.sentry.nlu.FactMatcher
import com.sentry.nlu.FastMatcher
import com.sentry.nlu.Planner
import com.sentry.nlu.ReplyCleaner
import com.sentry.skills.Skills
import com.sentry.voice.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * The thing that decides what happens when the user says something.
 *
 * The order matters more than anything else in this app:
 *
 *  1. [FastMatcher] — a regular expression. Alarms, timers, calls, the torch. No
 *     model, no network, no wait. This is the path most requests take.
 *  2. [Planner] — two small constrained model calls, for phrasings the patterns did
 *     not cover.
 *  3. Conversation — a streamed answer, spoken sentence by sentence as it arrives.
 *
 * The previous version sent every utterance, "turn on the flashlight" included,
 * through a language model and waited for a JSON object to come back. That is why it
 * felt slow, and no amount of a faster model would have fixed it.
 */
class Agent(
    private val skills: Skills,
    private val planner: Planner,
    private val brains: Brains,
    private val speaker: Speaker,
    private val phrases: PhraseBook,
    private val memory: Memory,
) {

    private companion object {
        const val TAG = "Sentry/Agent"

        /** Turns of context kept for the model. Short on purpose: prompt eval costs time. */
        const val HISTORY_TURNS = 6

        /**
         * How long a command that cannot be taken back waits to see if the sentence
         * was actually finished.
         *
         * The recogniser ends a turn on a pause, so "call amma" and "call amma when
         * you get a chance" look identical for a moment. Waiting before dialling is
         * the only ordering that can be correct — undoing afterwards cannot work,
         * because the phone is already ringing by the time the rest arrives.
         *
         * Short enough to be invisible against a call that takes seconds to connect,
         * and paid only by commands that reach somebody. The torch still fires in
         * about ten milliseconds.
         */
        const val CONTINUATION_MS = 400L

        /**
         * How long a conversation stays resumable after the last thing said.
         *
         * Coming straight back and saying "when did he die" should still know who
         * "he" is. Coming back an hour later should not — stale context makes an
         * assistant answer a question nobody asked.
         */
        const val CONTEXT_TTL_MS = 3 * 60 * 1000L
    }

    enum class Status { IDLE, LISTENING, THINKING, SPEAKING }

    private val ids = AtomicLong(0)

    private val _transcript = MutableStateFlow<List<Turn>>(emptyList())
    val transcript: StateFlow<List<Turn>> = _transcript.asStateFlow()

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _expectsAnswer = MutableStateFlow(false)

    /** True when Sentry asked a question and the mic should reopen without a wake word. */
    val expectsAnswer: StateFlow<Boolean> = _expectsAnswer.asStateFlow()

    private val history = mutableListOf<Msg>()

    /** Fragments joined so far while waiting to see whether a sentence was finished. */
    private var pending: String = ""

    /** Bumped per deferred utterance, so an older wait knows it has been superseded. */
    private var pendingId: Long = 0

    private var lastSpokeAt = 0L

    private val _ended = MutableStateFlow(false)

    /** Set when the user said "stop". The session should close rather than listen on. */
    val ended: StateFlow<Boolean> = _ended.asStateFlow()

    fun setListening(listening: Boolean) {
        if (listening) _status.value = Status.LISTENING
        else if (_status.value == Status.LISTENING) _status.value = Status.IDLE
    }

    /**
     * Abandon a half-stitched sentence.
     *
     * Called when a session ends mid-wait, so the fragment cannot resurface glued to
     * the front of whatever is said next time.
     */
    fun forgetPending() {
        pending = ""
        pendingId++
    }

    /** Start a fresh conversation, discarding everything said before. */
    fun reset() {
        pending = ""
        pendingId++
        history.clear()
        _transcript.value = emptyList()
        _status.value = Status.IDLE
        _expectsAnswer.value = false
        _ended.value = false
    }

    /**
     * Begin a session, keeping the previous conversation if it was recent enough to
     * still be the same conversation. See [CONTEXT_TTL_MS].
     */
    fun resume() {
        if (System.currentTimeMillis() - lastSpokeAt > CONTEXT_TTL_MS) {
            reset()
        } else {
            _status.value = Status.IDLE
            _expectsAnswer.value = false
            _ended.value = false
        }
    }

    /**
     * Handle one utterance, start to finish: understand it, do it, say the answer.
     */
    suspend fun handle(utterance: String) {
        // Anything the user has taught Sentry is translated first, so a phrase the
        // recogniser reliably mangles arrives here as what they meant. Everything
        // downstream then treats it exactly like a phrase that was heard correctly.
        val text = phrases.translate(utterance.trim()).trim()
        if (text.isEmpty()) return

        // "Sentry" on its own is someone getting our attention, not a request. Say
        // nothing and let the caller keep the microphone open.
        if (FastMatcher.isWakeWordOnly(text)) {
            Log.d(TAG, "wake word only; still listening")
            return
        }

        _expectsAnswer.value = false

        // Stitching, done before anything commits rather than by undoing afterwards.
        // A fragment that would merely answer a question or flip a switch runs now;
        // one that would dial a number or set an alarm waits to see whether the
        // speaker had finished.
        val stitched = awaitCompleteUtterance(text)
        if (stitched == null) {
            Log.d(TAG, "\"$text\" superseded by a continuation")
            return
        }

        Log.i(TAG, "heard: \"$stitched\"")
        addTurn(Party.USER, stitched)

        // Notice anything durable the user just said about themselves. Deterministic
        // and free, so it runs on every turn without the model being involved — and
        // it happens before the reply, so an answer can already use what was learned.
        rememberFacts(stitched)

        try {
            val fast = FastMatcher.match(stitched)
            if (fast != null) {
                Log.d(TAG, "fast path: $fast")
                if (fast is Command.Stop) {
                    speaker.stop()
                    _status.value = Status.IDLE
                    _ended.value = true
                    return
                }
                execute(fast, stitched)
                return
            }

            _status.value = Status.THINKING
            val planned = planner.plan(stitched, history.toList())
            if (planned is Command.Chat) converse(stitched) else execute(planned, stitched)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "failed to handle \"$stitched\"", e)
            respond(Reply.error(explain(e)))
        } finally {
            lastSpokeAt = System.currentTimeMillis()
            if (_status.value != Status.LISTENING) _status.value = Status.IDLE
        }
    }

    /**
     * Record any facts the utterance stated about the user.
     *
     * Silent by design. Announcing "I'll remember that your mother is Rani" after an
     * aside would be insufferable; the memory screen is where this is visible, and
     * the transcript keeps the sentence it came from.
     */
    private fun rememberFacts(text: String) {
        for ((fact, value) in FactMatcher.find(text)) {
            if (memory.remember(fact, value, source = text)) {
                Log.i(TAG, "remembered ${fact.name} = \"$value\"")
            }
        }
    }

    /**
     * Wait, if necessary, for the rest of a sentence the recogniser cut short.
     *
     * Returns the utterance to act on — possibly several fragments joined — or null
     * when this call has been superseded because a continuation arrived and a later
     * invocation now owns the joined text.
     *
     * Only commands that cannot be undone pay the wait. Deciding that requires
     * classifying the fragment first, which is a regex and free.
     */
    private suspend fun awaitCompleteUtterance(text: String): String? {
        val risk = FastMatcher.match(text)?.commit ?: Commit.PURE

        // Once a sentence is being stitched, every later fragment joins it whatever
        // it looks like on its own. A continuation such as "tomorrow morning" parses
        // as harmless conversation in isolation, and running it alone would answer
        // something nobody asked while quietly dropping the "call amma" it belonged to.
        val continuing = pending.isNotBlank()
        if (!continuing && risk != Commit.IRREVERSIBLE && risk != Commit.RELATIVE) {
            return text
        }

        val id = ++pendingId
        pending = if (pending.isBlank()) text else "${pending.trimEnd()} $text"

        delay(CONTINUATION_MS)

        // Somebody else arrived while we waited; the joined text is theirs to run.
        if (id != pendingId) return null

        val whole = pending
        pending = ""
        return whole
    }

    /** Run a command and speak whatever it reports. */
    private suspend fun execute(command: Command, utterance: String) {
        _status.value = Status.THINKING
        val reply = skills.run(command)
        remember(utterance, reply.speech)
        respond(reply)
    }

    /**
     * Stream a conversational answer.
     *
     * Sentences are spoken as they complete rather than after the whole generation,
     * which is what makes a slow model feel responsive: the first words are out loud
     * while the rest is still being produced.
     */
    private suspend fun converse(text: String) {
        val messages = buildList {
            // The whole memory, not a relevant subset. It is a dozen short values by
            // construction, which is cheaper than any scheme for deciding relevance
            // and removes the need for a retrieval step entirely.
            val known = memory.asPromptContext()
            add(Msg(Role.SYSTEM, if (known.isBlank()) ChatPrompt.SYSTEM else "${ChatPrompt.SYSTEM}\n\n$known"))
            addAll(history)
            add(Msg(Role.USER, text))
        }

        val turnId = addTurn(Party.SENTRY, "", streaming = true)
        val whole = StringBuilder()
        val unspoken = StringBuilder()
        var spokenAnything = false

        try {
            brains.stream(messages, BrainParams(maxTokens = 220, temperature = 0.7f))
                .collect { piece ->
                    whole.append(piece)
                    unspoken.append(piece)
                    updateTurn(turnId, ReplyCleaner.clean(whole.toString()), streaming = true)

                    val sentence = takeSentence(unspoken)
                    if (sentence != null) {
                        _status.value = Status.SPEAKING
                        speaker.say(ReplyCleaner.clean(sentence), flush = !spokenAnything)
                        spokenAnything = true
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "conversation failed", e)
            if (whole.isBlank()) {
                removeTurn(turnId)
                respond(Reply.error(explain(e)))
                return
            }
        }

        val answer = ReplyCleaner.clean(whole.toString())
        Log.i(TAG, "reply: \"$answer\"")
        updateTurn(turnId, answer, streaming = false)

        val tail = ReplyCleaner.clean(unspoken.toString())
        if (tail.isNotBlank()) {
            _status.value = Status.SPEAKING
            speaker.say(tail, flush = !spokenAnything)
        }
        remember(text, answer)
    }

    /**
     * Pull one complete sentence off the front of [buffer], or null if there is not
     * one yet. Short fragments are held back — speaking "Yes." on its own and then
     * pausing sounds worse than waiting one more token.
     */
    private fun takeSentence(buffer: StringBuilder): String? {
        val text = buffer.toString()
        var index = -1
        for (i in text.indices) {
            if (text[i] in ".!?\n") {
                // Not a sentence end if it is a decimal point or an abbreviation.
                val next = text.getOrNull(i + 1)
                if (text[i] == '.' && next != null && next.isDigit()) continue
                index = i
            }
        }
        if (index < 0) return null

        val sentence = text.substring(0, index + 1)
        if (sentence.trim().length < 12 && text.length < 60) return null

        buffer.delete(0, index + 1)
        return sentence
    }

    /** Say a finished reply and record it. */
    private suspend fun respond(reply: Reply) {
        if (reply.speech.isBlank()) return
        Log.i(TAG, "reply: \"${reply.speech}\"" +
            if (reply.choices.isEmpty()) "" else " ${reply.choices}")
        addTurn(Party.SENTRY, reply.speech, chip = reply.chip, isError = reply.isError)

        _status.value = Status.SPEAKING
        val spoken = if (reply.choices.isEmpty()) {
            reply.speech
        } else {
            // Read the options out, numbered, so "the second one" means something to
            // someone who is not looking at the screen.
            reply.speech + " " + reply.choices.mapIndexed { index, name ->
                "${index + 1}. $name"
            }.joinToString(". ")
        }
        speaker.say(spoken)
        _expectsAnswer.value = reply.expectsAnswer
    }

    private fun explain(e: Exception): String = when (e) {
        is NoBrainException ->
            "I can't reach Tara Core. Open it once to finish setting it up."

        else -> "Something went wrong. Try again."
    }

    private fun remember(user: String, assistant: String) {
        if (assistant.isBlank()) return
        history.add(Msg(Role.USER, user))
        history.add(Msg(Role.ASSISTANT, assistant))
        while (history.size > HISTORY_TURNS * 2) history.removeAt(0)
    }

    // ------------------------------------------------------------ transcript

    private fun addTurn(
        party: Party,
        text: String,
        chip: Chip? = null,
        isError: Boolean = false,
        streaming: Boolean = false,
    ): Long {
        val id = ids.incrementAndGet()
        _transcript.update {
            it + Turn(
                id = id,
                party = party,
                text = text,
                chip = chip,
                isError = isError,
                streaming = streaming,
            )
        }
        return id
    }

    private fun updateTurn(id: Long, text: String, streaming: Boolean) {
        _transcript.update { turns ->
            turns.map { if (it.id == id) it.copy(text = text, streaming = streaming) else it }
        }
    }

    private fun removeTurn(id: Long) {
        _transcript.update { turns -> turns.filterNot { it.id == id } }
    }
}
