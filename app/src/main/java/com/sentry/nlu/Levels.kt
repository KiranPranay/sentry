package com.sentry.nlu

import com.sentry.core.Command
import com.sentry.core.LevelChange
import com.sentry.core.LevelTarget
import com.sentry.core.VolumeChange

/**
 * Volume and brightness, parsed rather than enumerated.
 *
 * These used to be two lists of whole sentences written as anchored regexes — one per
 * phrasing, per domain. That shape accepts a verb or an amount but never both, so
 * "lower the volume" worked and "lower the volume to fifty percent" did not; and one
 * unexpected filler word broke it, so "increase the brightness" worked and "increase
 * the *device* brightness" did not. Every miss fell through to a 1.5B classifier that
 * had no brightness label available to it, and answered a brightness request by
 * turning the volume up, or by reading out the battery.
 *
 * So this reads the sentence as three slots instead: which thing (volume or screen),
 * what to do to it, and by how much. Any order, any filler, either half optional.
 *
 * The safety property is structural, not a threshold. **Nothing here matches without
 * an explicit domain word**, which is what makes it safe to accept loose phrasings:
 * "up", "down", "off", "full" and "fifty percent" cannot become commands on their
 * own, however many verbs and amounts the tables contain. Unexplained words are
 * refused outright rather than ignored, because "trading volume" and "high volume"
 * are not requests to change anything.
 */
internal object Levels {

    /**
     * What the user has taught, injected rather than imported.
     *
     * This object is pure and has no Context, the same way [FastMatcher] is, so the
     * store is handed in by the container as a one-way lookup — the same shape as
     * VoiceEngine.preferHypothesis. Null in tests, which keeps them honest about what
     * the grammar alone can do.
     */
    @Volatile
    var learnedVerb: ((LevelTarget, String) -> String?)? = null

    /**
     * Whether a word is somebody's name or an app on this phone.
     *
     * A destroyed verb and a contact name occupy the same slot — "prakash the volume"
     * parses exactly like "jellyfish the volume" — and only the address book can tell
     * them apart. Injected for the same reason as [learnedVerb]: this object stays
     * pure, and the tests see what the grammar alone can do.
     */
    @Volatile
    var knownName: ((String) -> Boolean)? = null

    private enum class Domain { SOUND, SCREEN, RINGER }

    private enum class Act { UP, DOWN, MAX, MIN, MUTE }

    /**
     * Nouns that name what is being changed, and are the only way in.
     *
     * "It" is here because "turn it up" is what people say, but it is deliberately
     * weak: see [strong]. Media words like "music" are absent — "the music" is a
     * request to play something, not a question about a level.
     */
    private val DOMAINS = mapOf(
        "volume" to Domain.SOUND,
        "sound" to Domain.SOUND,
        "audio" to Domain.SOUND,
        "speaker" to Domain.SOUND,
        "it" to Domain.SOUND,
        "brightness" to Domain.SCREEN,
        "screen" to Domain.SCREEN,
        "display" to Domain.SCREEN,
        "ringer" to Domain.RINGER,
        "ringtone" to Domain.RINGER,
        "ring" to Domain.RINGER,
    )

    /** A domain word specific enough to answer a question about, not just act on. */
    private fun strong(word: String) = word != "it"

    private val ACTS = mapOf(
        "increase" to Act.UP, "raise" to Act.UP, "up" to Act.UP, "boost" to Act.UP,
        "bump" to Act.UP, "higher" to Act.UP, "louder" to Act.UP, "brighter" to Act.UP,
        "brighten" to Act.UP, "crank" to Act.UP,

        "decrease" to Act.DOWN, "lower" to Act.DOWN, "down" to Act.DOWN,
        "reduce" to Act.DOWN, "quieter" to Act.DOWN, "softer" to Act.DOWN,
        "dim" to Act.DOWN, "dimmer" to Act.DOWN, "darker" to Act.DOWN,

        "max" to Act.MAX, "maximum" to Act.MAX, "full" to Act.MAX,
        "highest" to Act.MAX, "loudest" to Act.MAX, "brightest" to Act.MAX,

        "min" to Act.MIN, "minimum" to Act.MIN, "lowest" to Act.MIN,
        "dimmest" to Act.MIN, "darkest" to Act.MIN,

        "mute" to Act.MUTE, "silence" to Act.MUTE, "silent" to Act.MUTE,
    )

    /**
     * Verbs that name their own domain, so they need no noun.
     *
     * "Louder" and "dimmer" are unambiguous in a way "up" and "full" are not, which
     * is the whole distinction: these can stand alone, and the entries in [ACTS] that
     * are not here cannot.
     */
    private val SELF_DOMAINING = mapOf(
        "louder" to Domain.SOUND, "quieter" to Domain.SOUND, "softer" to Domain.SOUND,
        "loudest" to Domain.SOUND, "mute" to Domain.SOUND,
        "brighter" to Domain.SCREEN, "brighten" to Domain.SCREEN,
        "dimmer" to Domain.SCREEN, "dim" to Domain.SCREEN, "darker" to Domain.SCREEN,
        "brightest" to Domain.SCREEN, "dimmest" to Domain.SCREEN,
        "darkest" to Domain.SCREEN,
        // "Silence the phone" is about the ringer; "mute" is about what is playing.
        // People use them for different things and the phone treats them as different
        // systems, so collapsing the two is what made "keep the device in silent"
        // unanswerable.
        "silent" to Domain.RINGER, "silence" to Domain.RINGER,
    )

    /** Words that carry no meaning here and are dropped without counting against the parse. */
    private val FILLER = setOf(
        "the", "a", "an", "my", "your", "this", "that", "these", "those",
        "device", "devices", "phone", "phones", "please", "level", "levels",
        "setting", "settings", "and", "just", "now", "turn", "put", "set",
        "bit", "little", "slightly", "somewhat",
        "change", "make", "keep", "bring", "go", "get", "on", "of", "in", "into",
        "for", "all", "way", "s", "percent", "percentage", "per", "cent",
    )

    /**
     * Words that turn an instruction into a description, and must veto the whole parse.
     *
     * "The movie was silent" and "the screen is cracked" name a domain and an act
     * between them, and neither is a request to do anything. Without this the parser
     * would act on someone talking about their phone rather than to it.
     */
    private val COPULA = setOf("is", "was", "are", "were", "be", "been", "being", "am")

    /** Openers that make the utterance a question about a level rather than a change. */
    private val ASKING = Regex(
        """^(?:hey |ok )?(?:sentry[,\s]+)?""" +
            """(?:what(?:'s| is)|whats|hows|how(?:'s| is)|how much is|tell me|check)\s+"""
    )

    /** Trailing "off"/"on" for the ringer, which is a state rather than a level. */
    private val OFF_WORDS = setOf(
        "off", "no", "disable", "stop", "end", "exit", "cancel", "unmute",
    )

    fun match(raw: String): Command? {
        var text = raw.trim()
        val asked = ASKING.containsMatchIn(text)
        if (asked) text = text.replace(ASKING, "")

        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > MAX_WORDS) return null
        if (words.any { it in COPULA }) return null

        // A single word may only be a command when it names its own domain and its own
        // direction — "louder", "dimmer", "mute". A bare "silent" is refused: it is an
        // ordinary adjective, and the previous matcher required "silent mode" for
        // exactly this reason.
        if (words.size == 1) {
            val only = words[0]
            val own = SELF_DOMAINING[only]
            if (own == null || own == Domain.RINGER) return null
        }

        // Three ways to learn what is being changed, in order of authority: a noun the
        // user said ("the volume"), a verb that names its own domain ("brighter"), and
        // the pronoun "it". The order matters — in "make it brighter" the pronoun comes
        // first, and taking it at face value turned a brightness request into a volume
        // one, which is the same class of mistake this whole file exists to fix.
        var named: Domain? = null
        var namedWord: String? = null
        // A set, not a count: "the screen brightness" names one thing twice, which is
        // ordinary English, while "the volume and the brightness" names two and is a
        // sentence this parser has no business acting on.
        val namedDomains = mutableSetOf<Domain>()
        var fromAct: Domain? = null
        var pronoun: Domain? = null

        var act: Act? = null
        var acts = 0
        var offs = 0
        var namedAt = -1
        val strays = mutableListOf<String>()
        val strayAt = mutableListOf<Int>()

        // Amounts are taken out first: they are the only multi-word slot, and leaving
        // "to fifty percent" in the scan would produce three strays and a refusal.
        val amount = Amounts.take(words) ?: return null

        for ((index, word) in amount.rest.withIndex()) {
            when {
                word in FILLER -> Unit
                word in OFF_WORDS -> offs++
                DOMAINS.containsKey(stem(word)) -> {
                    val word = stem(word)
                    val found = DOMAINS.getValue(word)
                    if (strong(word)) {
                        namedDomains.add(found)
                        if (named == null) {
                            named = found
                            namedWord = word
                            namedAt = index
                        }
                    } else if (pronoun == null) {
                        pronoun = found
                    }
                }
                ACTS.containsKey(stem(word)) -> {
                    val word = stem(word)
                    acts++
                    if (act == null) act = ACTS.getValue(word)
                    SELF_DOMAINING[word]?.let { own -> if (fromAct == null) fromAct = own }
                }
                else -> {
                    strays.add(word)
                    strayAt.add(index)
                }
            }
        }

        val domain = named ?: fromAct ?: pronoun
        val domainWord = if (named != null) namedWord else null

        // Naming two different things, or the same thing twice, is not a command this
        // parser can be confident about. Neither is naming none, and neither is a word
        // it could not account for at all.
        if (domain == null || namedDomains.size > 1 || acts > 1) return null

        // One word the parser could not place, in a sentence that otherwise names a
        // level and nothing else. That is the shape of a command whose verb the
        // recogniser destroyed — "jellyfish the volume" — so rather than dropping the
        // word or guessing at it, ask which way and remember the answer.
        if (strays.size > 1) return null

        if (strays.size == 1) {
            val stray = strays[0]
            val target = named?.asTarget() ?: return null

            // An imperative puts its verb before its object. A word after the noun is
            // modifying it — "the volume of the box", "a large volume of water" —
            // and is not a verb the recogniser mangled.
            if (namedAt >= 0 && strayAt[0] > namedAt) return null

            if (act != null || amount.value != null) {
                // A direction is already known, so one unaccounted-for word is
                // decoder noise — "increased *devised* the brightness" — and dropping
                // it is safer than refusing a command that is otherwise complete. Not
                // if it looks like an ordinary modifier, though: "turn up the trading
                // volume" is somebody talking, not somebody asking.
                if (!plausibleVerb(stray)) return null
            } else {
                // Nothing but the noun and one word that should have been a verb.
                val taught = learnedVerb?.invoke(target, stray)
                if (taught != null) {
                    act = ACTS[taught]
                } else if (worthAsking(stray, words.size)) {
                    return Command.WhichWay(target, stray)
                } else {
                    return null
                }
            }
        }

        // "Turn it up" is a nudge and the pronoun is enough. "Set it to fifty" is not:
        // an absolute level for a thing that was never named is a guess about which
        // thing, and the two candidates are a loudspeaker and a screen.
        if (named == null && fromAct == null && amount.value != null) return null

        return build(domain, domainWord, act, amount.value, asked, offs > 0)
    }

    private const val MAX_WORDS = 9

    private fun Domain.asTarget(): LevelTarget? = when (this) {
        Domain.SOUND -> LevelTarget.SOUND
        Domain.SCREEN -> LevelTarget.SCREEN
        Domain.RINGER -> null
    }

    /**
     * Whether an unplaced word is worth one short question.
     *
     * Asking is cheap and changes nothing, but an unsolicited "up or down?" while
     * someone is talking about something else is still an interruption. So the
     * sentence has to be short enough to be an instruction rather than a remark, and
     * the word has to be substantial enough to have been a verb — a stray "of" or
     * "and" is filler the recogniser added, not a command it mangled.
     */
    private fun worthAsking(stray: String, words: Int): Boolean =
        words in 3..5 && stray.length >= 3 && plausibleVerb(stray)

    /**
     * Whether an unplaced word could have been a verb at all.
     *
     * "High volume", "trading volume" and "blood volume" are noun phrases, not
     * mangled instructions, and the words in front of the noun are the giveaway.
     * Refusing them here is what keeps Sentry from asking "up or down?" at somebody
     * discussing the stock market.
     */
    private fun plausibleVerb(stray: String): Boolean =
        stray !in NEVER_A_VERB &&
            stray !in MODIFIERS &&
            knownName?.invoke(stray) != true

    private val MODIFIERS = setOf(
        "high", "low", "loud", "quiet", "soft", "large", "small", "big", "huge",
        "total", "trading", "blood", "average", "normal", "same", "different",
        "good", "bad", "best", "worst", "new", "old", "whole", "half", "double",
    )

    /**
     * Strip an ending the recogniser welded on.
     *
     * "Increased devised the brightness" was "increase the device brightness". The
     * stems are all in the tables already, so this widens no vocabulary — it only
     * lets a word that is already a verb be recognised when it arrives inflected.
     */
    private fun stem(word: String): String {
        if (ACTS.containsKey(word) || DOMAINS.containsKey(word)) return word
        for (suffix in listOf("ing", "ed", "es", "s", "d")) {
            if (word.length > suffix.length + 2 && word.endsWith(suffix)) {
                val root = word.removeSuffix(suffix)
                if (ACTS.containsKey(root) || DOMAINS.containsKey(root)) return root
            }
        }
        return word
    }

    private val NEVER_A_VERB = setOf(
        "and", "but", "for", "with", "from", "into", "onto", "about", "over",
        "not", "you", "your", "our", "his", "her", "its", "was", "were", "are",
        "has", "had", "have", "can", "will", "would", "should", "could", "there",
        "here", "when", "then", "than", "some", "any", "all", "one", "two",
    )

    private fun build(
        domain: Domain,
        domainWord: String?,
        act: Act?,
        amount: Amounts.Value?,
        asked: Boolean,
        negated: Boolean,
    ): Command? {
        // The ringer is a switch, not a dial. Nothing else about a level applies to it.
        if (domain == Domain.RINGER) {
            if (amount != null) return null
            return Command.Silent(on = !negated)
        }

        // "Mute", "volume off", "no sound" — all the same request, and none of them
        // says a number.
        if (act == Act.MUTE || (negated && act == null && amount == null)) {
            if (domain == Domain.SCREEN) return null
            return Command.Volume(VolumeChange.Mute)
        }
        if (negated) return null

        if (act == null && amount == null) {
            // Nothing to do to it, so this was a question — but only about a noun
            // specific enough to answer. "Turn it" is not a question about the volume.
            if (domainWord == null || !strong(domainWord)) return null
            return when (domain) {
                Domain.SCREEN -> Command.BrightnessQuery
                else -> Command.VolumeQuery
            }
        }

        val change: Any = when {
            amount is Amounts.Value.Relative -> {
                val signed = if (act == Act.DOWN) -amount.percent else amount.percent
                if (domain == Domain.SCREEN) LevelChange.By(signed) else VolumeChange.By(signed)
            }
            amount is Amounts.Value.Absolute ->
                if (domain == Domain.SCREEN) LevelChange.Percent(amount.percent)
                else VolumeChange.Percent(amount.percent)

            act == Act.UP -> if (domain == Domain.SCREEN) LevelChange.Up else VolumeChange.Up
            act == Act.DOWN -> if (domain == Domain.SCREEN) LevelChange.Down else VolumeChange.Down
            act == Act.MAX -> if (domain == Domain.SCREEN) LevelChange.Max else VolumeChange.Max
            // A screen is never turned all the way off, but a stream is.
            act == Act.MIN ->
                if (domain == Domain.SCREEN) LevelChange.Min else VolumeChange.Mute
            else -> return null
        }

        return when (change) {
            is LevelChange -> Command.Brightness(change)
            is VolumeChange -> Command.Volume(change)
            else -> null
        }
    }

    /**
     * The amount slot: "to fifty percent", "by ten", "50%", or nothing.
     *
     * Split out because "to" and "by" mean genuinely different things — an
     * destination versus a distance — and collapsing them is what turned "lower the
     * volume by fifty percent" into a single four-percent step.
     */
    private object Amounts {

        sealed interface Value {
            val percent: Int

            data class Absolute(override val percent: Int) : Value
            data class Relative(override val percent: Int) : Value
        }

        data class Taken(val value: Value?, val rest: List<String>)

        private val UNITS = setOf("percent", "percentage", "%")

        /**
         * @return null when a number-shaped phrase was present but unreadable, so the
         *   caller refuses rather than silently acting on the half it understood.
         */
        fun take(words: List<String>): Taken? {
            val marker = words.indexOfLast { it == "to" || it == "at" || it == "by" }
            if (marker >= 0 && marker < words.lastIndex) {
                val tail = words.subList(marker + 1, words.size)
                val number = read(tail)
                if (number != null) {
                    val rest = words.subList(0, marker)
                    return Taken(
                        if (words[marker] == "by") Value.Relative(number) else Value.Absolute(number),
                        rest,
                    )
                }
                // "set the brightness to full" — a word, not a number. Leave it in
                // place so the act table can claim it.
                if (tail.none { it.toIntOrNull() != null || Arithmetic.number(it) != null }) {
                    return Taken(null, words)
                }
                return null
            }

            // A bare trailing number: "volume 5", "brightness 30 percent".
            val trailing = words.takeLastWhile { it in UNITS || Arithmetic.number(it) != null }
            if (trailing.isNotEmpty()) {
                val number = read(trailing)
                if (number != null) {
                    return Taken(
                        Value.Absolute(number),
                        words.subList(0, words.size - trailing.size),
                    )
                }
            }
            return Taken(null, words)
        }

        /**
         * Read "fifty percent" as 50.
         *
         * The unit has to be stripped before the number is parsed. [Arithmetic.number]
         * refuses any word it does not recognise, so it returned null for the whole of
         * "fifty percent" — which meant every spoken percentage in the system failed,
         * including the ones its own tests appeared to cover, because the tests said
         * "fifty" and people say "fifty percent".
         */
        private fun read(tail: List<String>): Int? {
            val digits = tail.filter { it !in UNITS }
            if (digits.isEmpty()) return null
            val value = Arithmetic.number(digits.joinToString(" ")) ?: return null
            val rounded = Math.round(value).toInt()
            return if (rounded in 0..100) rounded else null
        }
    }
}
