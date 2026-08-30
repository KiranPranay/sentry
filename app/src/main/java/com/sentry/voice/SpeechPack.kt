package com.sentry.voice

/**
 * The bundled acoustic models.
 *
 * Two, because accent matters more than size. A US English model asked to transcribe
 * Indian-accented English mishears in ways no amount of extra parameters fixes, and
 * the reverse is equally true — so this is a choice the person using the phone has to
 * make, not one that can be made for them at build time.
 *
 * Both are *small* models on purpose. The large `en-us-0.22-lgraph` was tried and
 * abandoned: it decodes at well over real time on a Pixel 9a, the microphone buffer
 * overruns while it works, and the words it drops are the user's. "Set a timer for
 * five minutes" came back as "set a timer for". A model that cannot keep up does not
 * give worse answers — it gives truncated ones, which is worse than a smaller model
 * that finishes.
 */
enum class SpeechPack(
    /** Directory under assets/ and under filesDir. */
    val asset: String,
    val label: String,
    val detail: String,
) {
    EN_IN(
        asset = "model-en-in",
        label = "English (India)",
        detail = "Best for Indian-accented English.",
    ),
    EN_US(
        asset = "model-en-us",
        label = "English (US)",
        detail = "Best for American-accented English.",
    );

    companion object {
        val DEFAULT = EN_IN

        fun from(name: String?): SpeechPack =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
