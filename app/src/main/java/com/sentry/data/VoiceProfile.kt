package com.sentry.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.sentry.voice.VoiceEngine
import org.json.JSONArray
import kotlin.math.sqrt

/**
 * Who Sentry thinks you sound like.
 *
 * This is the thing people picture when they think of "Ok Google" enrollment, and it
 * is genuinely what that does: several recordings are turned into speaker embeddings
 * — x-vectors, 128 numbers describing a voice rather than the words in it — and kept
 * as a profile to compare later utterances against. It is speaker *verification*. It
 * does not make Sentry understand words better; that is a different problem, solved
 * by [PhraseBook].
 *
 * Scoring follows Apple's published rule for "Hey Siri": keep the enrollment vectors
 * as a set and take the *mean* cosine against all of them, rather than the maximum.
 * Max-similarity is nearest-neighbour matching, and it lets an impostor in as soon as
 * they resemble your single worst enrollment sample.
 *
 * Vosk scales every vector it emits to ‖v‖ = √128, so the cosine of two of them is
 * their dot product divided by 128 exactly. No normalisation is needed here, and the
 * arithmetic below relies on that.
 */
class VoiceProfile(context: Context) {

    companion object {
        private const val TAG = "Sentry/VoiceProfile"
        private const val FILE = "sentry_voice"
        private const val KEY_VECTORS = "vectors"
        private const val KEY_ENFORCE = "enforce"

        /** Enrollment recordings to collect. Apple uses five; so do we. */
        const val ENROLL_TARGET = 5

        /**
         * Similarity below which an utterance is treated as somebody else.
         *
         * Expressed as cosine similarity, so higher is more like you. The Android
         * example Vosk's maintainer points people at rejects above a cosine
         * *distance* of 0.5, i.e. below a similarity of 0.5; this starts marginally
         * more permissive because a false reject — Sentry ignoring its owner — is far
         * more annoying than a false accept on a phone in your pocket.
         *
         * Treat it as a starting point, not a constant of nature. The model is a
         * telephone-band diarisation model, and reported same-speaker scores with it
         * range widely; the enrollment screen shows you your own spread so the number
         * can be judged against reality rather than folklore.
         */
        const val DEFAULT_THRESHOLD = 0.45f
    }

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Volatile
    private var vectors: List<FloatArray> = load()

    /**
     * Whether a voice that does not match should actually be refused.
     *
     * Off by default, and deliberately so. Enrolling is useful on its own — it tells
     * you how well recognition separates you from everyone else — but silently
     * ignoring its owner is the single worst thing an assistant can do, and nobody
     * should discover that behaviour by surprise.
     */
    var enforce: Boolean
        get() = prefs.getBoolean(KEY_ENFORCE, false)
        set(value) = prefs.edit { putBoolean(KEY_ENFORCE, value) }

    val isEnrolled: Boolean get() = vectors.isNotEmpty()

    val sampleCount: Int get() = vectors.size

    private fun load(): List<FloatArray> = runCatching {
        val raw = prefs.getString(KEY_VECTORS, null) ?: return emptyList()
        val outer = JSONArray(raw)
        (0 until outer.length()).map { i ->
            val inner = outer.getJSONArray(i)
            FloatArray(inner.length()) { j -> inner.getDouble(j).toFloat() }
        }
    }.onFailure { Log.w(TAG, "could not read the voice profile", it) }.getOrDefault(emptyList())

    private fun persist(list: List<FloatArray>) {
        val outer = JSONArray()
        list.forEach { vector ->
            val inner = JSONArray()
            vector.forEach { inner.put(it.toDouble()) }
            outer.put(inner)
        }
        prefs.edit { putString(KEY_VECTORS, outer.toString()) }
        vectors = list
    }

    fun enroll(samples: List<FloatArray>) {
        persist(samples)
        Log.i(TAG, "enrolled ${samples.size} voice samples")
    }

    fun clear() {
        persist(emptyList())
        enforce = false
    }

    /**
     * How much this sounds like the enrolled voice, from 0 to 1.
     *
     * Null when there is no profile, or when the utterance was too short to have
     * produced a trustworthy vector — "too short" being a real cliff rather than a
     * gradient, since Vosk declines to emit one at all below ~0.5 s of speech.
     */
    fun similarity(print: VoiceEngine.Voiceprint?): Float? {
        if (print == null || vectors.isEmpty()) return null
        if (print.frames < VoiceEngine.MIN_VOICEPRINT_FRAMES) return null

        val scores = vectors.mapNotNull { cosine(print.vector, it) }
        if (scores.isEmpty()) return null
        return scores.average().toFloat()
    }

    /**
     * Whether to act on this utterance.
     *
     * Fails *open* everywhere it can: no profile, enforcement off, utterance too
     * short, or a vector we could not compare all return true. The only path to
     * false is an enrolled user, enforcement deliberately switched on, enough speech
     * to judge by, and a score that clearly is not them.
     */
    fun accepts(print: VoiceEngine.Voiceprint?): Boolean {
        if (!enforce || vectors.isEmpty()) return true
        val score = similarity(print) ?: return true
        val ok = score >= DEFAULT_THRESHOLD
        if (!ok) Log.i(TAG, "voice did not match (%.2f)".format(score))
        return ok
    }

    /**
     * Spread of the enrollment samples against each other.
     *
     * Shown to the user after enrolling, because it is the only honest way to say how
     * well this will work for *them*: if your own five recordings do not agree with
     * each other, no threshold is going to separate you from anyone else.
     */
    fun selfConsistency(): Pair<Float, Float>? {
        if (vectors.size < 2) return null
        val scores = mutableListOf<Float>()
        for (i in vectors.indices) {
            for (j in i + 1 until vectors.size) {
                cosine(vectors[i], vectors[j])?.let { scores.add(it) }
            }
        }
        if (scores.isEmpty()) return null
        return scores.min() to scores.max()
    }

    /**
     * Cosine similarity. Vosk normalises its vectors to ‖v‖ = √dim, so this is the
     * dot product over the dimension — but the norms are computed anyway rather than
     * assumed, because silently returning nonsense if that ever changes would be
     * worse than the handful of multiplications it costs.
     */
    private fun cosine(a: FloatArray, b: FloatArray): Float? {
        if (a.isEmpty() || a.size != b.size) return null
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i].toDouble()
            normA += a[i] * a[i].toDouble()
            normB += b[i] * b[i].toDouble()
        }
        if (normA <= 0.0 || normB <= 0.0) return null
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
    }
}
