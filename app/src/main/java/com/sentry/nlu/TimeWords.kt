package com.sentry.nlu

import java.util.Calendar

/**
 * Spoken numbers and times, turned into integers.
 *
 * Speech recognisers are inconsistent about this: the same utterance comes back as
 * "seven thirty", "7 30" or "7:30" depending on the acoustic model's mood, so every
 * one of those has to parse. Getting this right is most of what makes "set an alarm
 * for half seven" work without a language model.
 */
internal object TimeWords {

    private val UNITS = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19,
    )

    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    private val ORDINALS = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
        "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10,
        "last" to -1,
    )

    fun ordinal(word: String): Int? = ORDINALS[word.lowercase()]

    /**
     * A bare integer, written either as digits or as words ("twenty five", "twenty-five").
     * Returns null rather than guessing when the phrase is not purely a number.
     */
    fun number(text: String): Int? {
        val cleaned = text.trim().lowercase().replace('-', ' ')
        if (cleaned.isEmpty()) return null
        cleaned.toIntOrNull()?.let { return it }

        var total = 0
        var matched = false
        for (word in cleaned.split(' ')) {
            if (word.isBlank() || word == "and") continue
            val digits = word.toIntOrNull()
            when {
                digits != null -> {
                    total += digits; matched = true
                }
                TENS.containsKey(word) -> {
                    total += TENS.getValue(word); matched = true
                }
                UNITS.containsKey(word) -> {
                    total += UNITS.getValue(word); matched = true
                }
                word == "a" || word == "an" -> {
                    total += 1; matched = true
                }
                // Anything unrecognised means this was not a number phrase at all.
                else -> return null
            }
        }
        return if (matched) total else null
    }

    private val DURATION_UNITS = mapOf(
        "second" to 1, "seconds" to 1, "sec" to 1, "secs" to 1,
        "minute" to 60, "minutes" to 60, "min" to 60, "mins" to 60,
        "hour" to 3600, "hours" to 3600, "hr" to 3600, "hrs" to 3600,
    )

    private val DURATION = Regex(
        """(\d+|[a-z]+(?:[ -][a-z]+)?)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)"""
    )

    /**
     * "5 minutes", "an hour and a half", "1 hour 30 minutes" → seconds.
     *
     * Sums every unit it finds, so compound durations work without a special case.
     */
    fun duration(text: String): Int? {
        var total = 0
        var found = false
        for (match in DURATION.findAll(text.lowercase())) {
            val unit = DURATION_UNITS[match.groupValues[2]] ?: continue
            val count = number(match.groupValues[1]) ?: continue
            total += count * unit
            found = true
        }
        if (text.lowercase().contains("half an hour") || text.lowercase().contains("half hour")) {
            return 1800
        }
        return if (found && total > 0) total else null
    }

    /** A wall-clock time, as (hour24, minute). */
    data class Clock(val hour: Int, val minute: Int)

    private val HH_MM = Regex("""\b(\d{1,2})[:.](\d{2})\b""")
    private val HH_MM_WORDS = Regex("""\b(\d{1,2})\s+(\d{2})\b""")
    private val BARE_HOUR = Regex("""\b(\d{1,2})\b""")
    private val MERIDIEM = Regex("""\b([ap])\.?\s?m\.?\b""")

    /**
     * Spoken alternatives to "am" and "pm".
     *
     * People say "seven thirty in the morning" far more often than "seven thirty
     * a.m.", and a recogniser transcribes it literally. Without these, the phrase
     * falls through to the next-occurrence guess below, which at nine at night
     * produced a 7:30 *pm* alarm for a request that could not have been clearer.
     */
    private val MORNING = Regex("""\b(in the morning|this morning|tomorrow morning)\b""")
    private val EVENING = Regex(
        """\b(in the evening|this evening|at night|tonight|in the afternoon|this afternoon)\b"""
    )
    private val PAST_TO = Regex(
        """\b(quarter|half|\d{1,2}|[a-z]+)\s+(past|after|to|till|until)\s+(\d{1,2}|[a-z]+)\b"""
    )

    /**
     * Parse a spoken time. [now] decides am/pm when the speaker did not say which:
     * "set an alarm for 7" at 9pm means tomorrow at 7am, not seven minutes ago.
     */
    fun clock(text: String, now: Calendar = Calendar.getInstance()): Clock? {
        val s = text.lowercase()

        val meridiem = MERIDIEM.find(s)?.groupValues?.get(1)
        val saidMorning = MORNING.containsMatchIn(s)
        val saidEvening = EVENING.containsMatchIn(s)

        val isPm = meridiem == "p" || saidEvening
        val explicit = meridiem != null || saidMorning || saidEvening

        // "quarter past seven", "half past six", "ten to eight"
        PAST_TO.find(s)?.let { m ->
            val amountWord = m.groupValues[1]
            val direction = m.groupValues[2]
            val hourWord = m.groupValues[3]
            val minutes = when (amountWord) {
                "quarter" -> 15
                "half" -> 30
                else -> number(amountWord)
            }
            val baseHour = number(hourWord)
            if (minutes != null && baseHour != null && baseHour in 1..12) {
                val toward = direction == "to" || direction == "till" || direction == "until"
                var hour = baseHour
                var minute = minutes
                if (toward) {
                    minute = 60 - minutes
                    hour -= 1
                    if (hour <= 0) hour = 12
                }
                if (minute in 0..59) return resolve(hour, minute, isPm, explicit, now)
            }
        }

        // "half seven" — British for 7:30, and a phrasing recognisers produce often.
        Regex("""\bhalf\s+(\d{1,2}|[a-z]+)\b""").find(s)?.let { m ->
            if (!s.contains("past") && !s.contains("to ")) {
                val hour = number(m.groupValues[1])
                if (hour != null && hour in 1..12) return resolve(hour, 30, isPm, explicit, now)
            }
        }

        HH_MM.find(s)?.let { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            if (hour in 0..23 && minute in 0..59) return resolve(hour, minute, isPm, explicit, now)
        }

        HH_MM_WORDS.find(s)?.let { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            if (hour in 1..12 && minute in 0..59) return resolve(hour, minute, isPm, explicit, now)
        }

        // "seven thirty", "six fifteen"
        val words = s.replace('-', ' ').split(' ').filter { it.isNotBlank() }
        for (i in 0 until words.size - 1) {
            val hour = UNITS[words[i]]
            val minute = TENS[words[i + 1]] ?: UNITS[words[i + 1]]
            if (hour != null && hour in 1..12 && minute != null && minute in 0..59) {
                // "seven five" is a coin toss, so only accept a minute that was
                // clearly spoken as one: a tens word, or "oh five".
                val explicitMinute = TENS.containsKey(words[i + 1]) ||
                    (i > 0 && words[i - 1] == "oh")
                if (explicitMinute) return resolve(hour, minute, isPm, explicit, now)
            }
        }

        BARE_HOUR.find(s)?.let { m ->
            val hour = m.groupValues[1].toInt()
            if (hour in 0..23) return resolve(hour, 0, isPm, explicit, now)
        }

        for (word in words) {
            val hour = UNITS[word]
            if (hour != null && hour in 1..12) return resolve(hour, 0, isPm, explicit, now)
        }

        return null
    }

    /**
     * Turn a spoken hour into a 24-hour one.
     *
     * When the speaker said "am" or "pm" we obey them. When they did not, we pick the
     * next occurrence: nobody asking at 9pm for "an alarm at 7" means an alarm that
     * has already gone.
     */
    private fun resolve(
        hour: Int,
        minute: Int,
        isPm: Boolean,
        explicit: Boolean,
        now: Calendar,
    ): Clock {
        if (explicit) {
            val h = when {
                isPm && hour < 12 -> hour + 12
                !isPm && hour == 12 -> 0
                else -> hour
            }
            return Clock(h, minute)
        }
        if (hour > 12) return Clock(hour, minute)

        // Pick the next time this reads on a clock face. Both readings can be in the
        // past — "set an alarm for 7:30" at nine at night — and the answer then is
        // tomorrow morning, not this evening, which is the one already gone.
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val morning = Clock(hour % 12, minute)
        val evening = Clock(hour % 12 + 12, minute)

        val morningMinutes = morning.hour * 60 + morning.minute
        val eveningMinutes = evening.hour * 60 + evening.minute

        return when {
            morningMinutes > currentMinutes -> morning
            eveningMinutes > currentMinutes -> evening
            // Both have passed today, so the next one is tomorrow morning.
            else -> morning
        }
    }
}
