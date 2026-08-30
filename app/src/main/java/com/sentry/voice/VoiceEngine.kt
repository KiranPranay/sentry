package com.sentry.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * The microphone, the wake word, and dictation — all in one place.
 *
 * This owns a *single* [AudioRecord] and switches which Vosk recogniser the samples
 * are fed to. That is the whole design: the obvious alternative, one recogniser per
 * job each with its own capture session, has to release and re-acquire the mic every
 * time the wake word fires. Re-acquiring costs a few hundred milliseconds and drops
 * the beginning of the very sentence the user just started saying — which is exactly
 * the "it didn't hear me" failure that makes an assistant feel broken.
 *
 * Switching a variable costs nothing, so the first syllable after "Sentry" lands.
 */
class VoiceEngine(private val context: Context) {

    companion object {
        private const val TAG = "Sentry/Voice"

        const val SAMPLE_RATE = 16_000

        /** Vosk's en-us model, unpacked from assets on first run. */
        private const val MODEL_ASSET = "model-en-us"

        /**
         * The wake word, plus Vosk's out-of-vocabulary token.
         *
         * Restricting the grammar to two options is what makes this cheap and
         * immediate: the decoder has almost no search space, so it settles within a
         * frame or two instead of scoring a 200k-word lattice.
         */
        private const val HOTWORD_GRAMMAR = """["sentry", "[unk]"]"""

        private const val WAKE_WORD = "sentry"

        /** Stop dictating after this much silence following speech. */
        private const val TRAILING_SILENCE_MS = 900L

        /** Give up if the user says nothing at all. */
        private const val NO_SPEECH_TIMEOUT_MS = 6_000L

        /** Never hold the mic open for a single utterance longer than this. */
        private const val MAX_UTTERANCE_MS = 20_000L

        /**
         * How many competing hypotheses to ask the decoder for, in command mode.
         *
         * The small acoustic model's top guess for "volume up" is quite often
         * "follow up" — the two are near-identical acoustically, and nothing in a
         * general language model prefers one. But we know something the decoder does
         * not: the set of things a person says to an assistant. Asking for several
         * hypotheses and picking one that is a real command turns that knowledge into
         * accuracy, without constraining the grammar and losing free conversation.
         */
        private const val N_BEST = 6

        /** Level above which a frame counts as somebody talking. */
        private const val SPEECH_LEVEL = 0.08f

        /**
         * Peak an utterance must reach before its transcription is believed.
         *
         * Set from measurement rather than taste: speech at arm's length peaks well
         * above this, while a quiet room sits below it even when the decoder insists
         * it heard a sentence.
         */
        private const val UTTERANCE_PEAK = 0.22f

        /** Longer than this and the utterance is speech, not a command to correct. */
        private const val MAX_RERANK_WORDS = 4

        /** How different a corrected hypothesis may be, as a fraction of its length. */
        private const val MAX_RERANK_DISTANCE = 0.5f

    }

    enum class Mode {
        /** Not listening at all. */
        OFF,

        /** Listening only for the wake word. */
        HOTWORD,

        /** Transcribing what the user is saying. */
        COMMAND,
    }

    sealed interface Event {
        data object WakeWord : Event
        data class Transcript(val text: String) : Event

        /** The user started talking while Sentry was. Stop talking and listen. */
        data object BargeIn : Event
        data object NoSpeech : Event
        data class Failed(val reason: String) : Event
        data object ModelReady : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val modelLock = Mutex()

    /**
     * Serialises every start and stop of capture.
     *
     * Without it two callers arriving together — the wake word firing at the same
     * moment the assistant screen opens — can each cancel the job the other had not
     * yet published and then start their own. The result is two live [AudioRecord]
     * loops with two recognisers, so the user says one sentence and Sentry hears it
     * twice and acts on it twice. That is unpleasant for the torch and unacceptable
     * for anything that places a call.
     */
    private val controlLock = Mutex()

    private val _mode = MutableStateFlow(Mode.OFF)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _partial = MutableStateFlow("")

    /** What the user appears to be saying, updated as they say it. */
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)

    /** Smoothed 0..1 loudness, for the orb. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _ready = MutableStateFlow(false)

    /** False until the acoustic model has been unpacked and loaded. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    @Volatile
    private var model: Model? = null

    private var captureJob: Job? = null

    /**
     * True while Sentry is speaking.
     *
     * The microphone deliberately stays open through this. Muting for the length of
     * every answer is why an assistant feels like a walkie-talkie: you ask a question,
     * it talks for fifteen seconds, and nothing you say in that time exists. Keeping
     * the mic live means the user can cut in — see [Event.BargeIn].
     *
     * The cost is that we also hear ourselves, which [echoFilter] deals with.
     */
    @Volatile
    var speaking: Boolean = false

    /**
     * True while Sentry is working on an answer — thinking or speaking.
     *
     * Distinct from [speaking] because the two need different handling. Thinking is
     * silent, so the microphone can transcribe normally with no risk of hearing
     * ourselves; what it must not do is *time out*. The silence timer measures how
     * long the user has been quiet, and a user waiting for an answer that takes eight
     * seconds is not an abandoned conversation — but that is exactly how it was being
     * read, so the session ended before the reply was ever spoken.
     */
    @Volatile
    var busy: Boolean = false

    /**
     * What Sentry is currently saying, or null. Used only to make sure we do not
     * treat our own use of the wake word as the user interrupting us.
     */
    @Volatile
    var spokenText: String? = null

    /**
     * Re-ranks competing transcriptions. Set by the app to "is this a command I
     * recognise?"; left null it simply takes the decoder's first choice.
     *
     * Kept as a predicate rather than a dependency so this class stays about audio
     * and knows nothing about what the words mean.
     */
    @Volatile
    var preferHypothesis: ((String) -> Boolean)? = null

    // ----------------------------------------------------------------- model

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Unpack and load the acoustic model. Slow the first time (it copies ~68 MB out
     * of the APK), instant afterwards.
     */
    suspend fun prepare(): Boolean = modelLock.withLock {
        if (model != null) return@withLock true
        withContext(Dispatchers.IO) {
            runCatching {
                val target = File(context.filesDir, MODEL_ASSET)
                if (!isUnpacked(target)) {
                    Log.i(TAG, "unpacking acoustic model")
                    target.deleteRecursively()
                    unpack(MODEL_ASSET, target)
                }
                model = Model(target.absolutePath)
                _ready.value = true
                _events.tryEmit(Event.ModelReady)
                Log.i(TAG, "acoustic model ready")
                true
            }.getOrElse {
                Log.e(TAG, "could not load the acoustic model", it)
                _events.tryEmit(Event.Failed("Speech model failed to load"))
                false
            }
        }
    }

    /**
     * A marker written only after a complete copy, so an unpack interrupted by the
     * process dying is retried instead of loading a half-written model.
     */
    private fun isUnpacked(target: File): Boolean =
        File(target, ".complete").exists()

    private fun unpack(assetPath: String, target: File) {
        copyAsset(assetPath, target)
        File(target, ".complete").writeText("1")
    }

    private fun copyAsset(assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        for (child in children) copyAsset("$assetPath/$child", File(target, child))
    }

    // --------------------------------------------------------------- control

    /** Start listening for the wake word. Safe to call repeatedly. */
    fun startHotword() {
        if (_mode.value == Mode.HOTWORD) return
        restart(Mode.HOTWORD)
    }

    /**
     * Start transcribing. Called when the wake word fires, when the user taps the
     * mic, and when Sentry has asked a question and expects an answer.
     */
    fun startCommand() {
        restart(Mode.COMMAND)
    }

    /** True when a dictation capture is actually live right now. */
    val listening: Boolean
        get() = _mode.value == Mode.COMMAND && captureJob?.isActive == true

    fun stop() {
        scope.launch {
            controlLock.withLock {
                captureJob?.cancelAndJoin()
                captureJob = null
                _mode.value = Mode.OFF
                _partial.value = ""
                _amplitude.value = 0f
            }
        }
    }

    private fun restart(mode: Mode) {
        scope.launch {
            controlLock.withLock {
                // Cancel and *join* under the lock: the old loop still holds the
                // microphone until it returns, and starting a second AudioRecord
                // before it has released is how you get two recognisers at once.
                captureJob?.cancelAndJoin()
                captureJob = null

                if (!prepare()) return@launch
                if (!hasMicPermission()) {
                    _events.tryEmit(Event.Failed("Microphone permission is not granted"))
                    return@launch
                }
                _mode.value = mode
                _partial.value = ""
                captureJob = scope.launch { capture(mode) }
            }
        }
    }

    // --------------------------------------------------------------- capture

    @SuppressLint("MissingPermission")
    private suspend fun capture(mode: Mode) = withContext(Dispatchers.IO) {
        val currentModel = model ?: return@withContext

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            _events.tryEmit(Event.Failed("This device cannot record at 16 kHz"))
            return@withContext
        }
        // Four times the minimum: enough slack that a scheduling hiccup does not
        // overrun the buffer and lose audio mid-word.
        val bufferSize = minBuffer * 4
        val chunk = ShortArray(minBuffer / 2)

        val recorder = runCatching {
            AudioRecord(
                // VOICE_RECOGNITION gets the tuned mic path: less aggressive AGC and
                // no echo canceller fighting the assistant's own output.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }.getOrNull()

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder?.release() }
            _events.tryEmit(Event.Failed("Another app is using the microphone"))
            return@withContext
        }

        // A second, tiny recogniser used only to hear the wake word over our own
        // voice. Costs almost nothing: its grammar has two entries.
        val barge = runCatching {
            if (mode == Mode.COMMAND) Recognizer(currentModel, SAMPLE_RATE.toFloat(), HOTWORD_GRAMMAR)
            else null
        }.getOrNull()

        val recogniser = runCatching {
            if (mode == Mode.HOTWORD) {
                Recognizer(currentModel, SAMPLE_RATE.toFloat(), HOTWORD_GRAMMAR)
            } else {
                Recognizer(currentModel, SAMPLE_RATE.toFloat()).apply {
                    setMaxAlternatives(N_BEST)
                }
            }
        }.getOrElse {
            Log.e(TAG, "could not create the recogniser", it)
            runCatching { recorder.release() }
            _events.tryEmit(Event.Failed("Speech recogniser failed to start"))
            return@withContext
        }

        // Best-effort hardware help. The textual echo filter is the guarantee; this
        // just means less of our own voice reaches the decoder in the first place.
        val effects = mutableListOf<AudioEffect>()
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(recorder.audioSessionId)
                    ?.apply { enabled = true }
                    ?.let { effects.add(it) }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(recorder.audioSessionId)
                    ?.apply { enabled = true }
                    ?.let { effects.add(it) }
            }
        }.onFailure { Log.d(TAG, "audio effects unavailable", it) }

        runCatching { recorder.startRecording() }.onFailure {
            runCatching { recorder.release() }
            _events.tryEmit(Event.Failed("Could not start recording"))
            return@withContext
        }

        Log.d(TAG, "capture started in $mode")

        var startedAt = System.currentTimeMillis()
        var lastSpeechAt = 0L
        var heardAnything = false

        // Loudest thing heard during this utterance. A small acoustic model asked to
        // decode room tone returns words rather than nothing — "the cats", "i saw me"
        // — and with the microphone held open across a whole conversation, every one
        // of those becomes a command. Requiring that something was actually said is
        // cheaper and far more reliable than trying to filter the nonsense afterwards.
        var peak = 0f

        try {
            while (isActive) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) continue

                val level = level(chunk, read)
                _amplitude.value = smooth(_amplitude.value, level)

                val complete = runCatching { recogniser.acceptWaveForm(chunk, read) }
                    .getOrDefault(false)

                // While Sentry is speaking, the only thing that counts as the user
                // cutting in is the wake word — decoded by a *separate* recogniser
                // restricted to that one word.
                //
                // The obvious approach, transcribing normally and discarding anything
                // that looks like our own speech, does not survive contact with a
                // phone. The speaker is inches from the microphone, and what comes
                // back is not our sentence but a garble of it — answering "what is
                // the capital of France" produced "that is his" and "i went on".
                // Those match no echo filter, so Sentry heard a stranger, stopped
                // mid-answer to listen, and did it again on the next fragment: a
                // loop where it interrupts itself forever and never finishes a
                // sentence. A two-word grammar cannot do that, because garble
                // decodes to [unk], not to "sentry".
                if (speaking && barge != null) {
                    // Hold the clocks: they measure how long the *user* has been
                    // silent, and the user is not being silent, they are listening.
                    startedAt = System.currentTimeMillis()
                    lastSpeechAt = 0L

                    val interrupt = runCatching {
                        if (barge.acceptWaveForm(chunk, read)) textOf(barge.result)
                        else partialOf(barge.partialResult)
                    }.getOrDefault("")

                    val saidItOurselves = spokenText?.contains(WAKE_WORD, ignoreCase = true) == true
                    if (interrupt.contains(WAKE_WORD, ignoreCase = true) && !saidItOurselves) {
                        Log.i(TAG, "barge-in")
                        _events.tryEmit(Event.BargeIn)
                        speaking = false
                        barge.reset()
                        // Drop whatever our own voice put into the dictation decoder
                        // so the user's sentence starts from silence.
                        recogniser.reset()
                    }
                    continue
                }

                if (mode == Mode.HOTWORD) {
                    val text = if (complete) textOf(recogniser.result)
                    else partialOf(recogniser.partialResult)

                    if (text.contains(WAKE_WORD, ignoreCase = true)) {
                        Log.i(TAG, "wake word")
                        _events.tryEmit(Event.WakeWord)
                        return@withContext
                    }
                    if (complete) recogniser.reset()
                    continue
                }

                // ------------------------------------------------- command mode
                val now = System.currentTimeMillis()

                // Thinking: transcribe as normal, but do not let the clocks run out
                // from under an answer that is still being produced.
                if (busy) {
                    startedAt = now
                    if (!heardAnything) lastSpeechAt = 0L
                }

                if (level > peak) peak = level
                if (level > SPEECH_LEVEL) {
                    lastSpeechAt = now
                    heardAnything = true
                }

                if (complete) {
                    val text = textOf(recogniser.result)
                    if (text.isNotBlank()) {
                        _partial.value = ""
                        if (peak < UTTERANCE_PEAK) {
                            Log.d(TAG, "ignoring \"$text\" (peak $peak, likely noise)")
                            peak = 0f
                            recogniser.reset()
                            continue
                        }
                        Log.d(TAG, "transcript (endpoint): \"$text\"")
                        _events.tryEmit(Event.Transcript(text))
                        return@withContext
                    }
                } else {
                    val partial = partialOf(recogniser.partialResult)
                    if (partial.isNotBlank()) {
                        _partial.value = partial
                        heardAnything = true
                    }
                }

                // Vosk's own endpointing is conservative, so we also stop on a plain
                // pause. Waiting for the decoder alone leaves the user staring at an
                // open microphone seconds after they finished talking.
                val silentFor = if (lastSpeechAt == 0L) 0 else now - lastSpeechAt
                val finished = heardAnything && lastSpeechAt > 0 && silentFor > TRAILING_SILENCE_MS
                val gaveUp = !heardAnything && now - startedAt > NO_SPEECH_TIMEOUT_MS
                val ranLong = now - startedAt > MAX_UTTERANCE_MS

                if (finished || ranLong) {
                    val text = textOf(recogniser.finalResult).ifBlank { _partial.value }
                    _partial.value = ""
                    if (text.isNotBlank() && peak >= UTTERANCE_PEAK) {
                        Log.d(TAG, "transcript (silence): \"$text\"")
                        _events.tryEmit(Event.Transcript(text))
                    } else {
                        _events.tryEmit(Event.NoSpeech)
                    }
                    return@withContext
                }
                if (gaveUp) {
                    _partial.value = ""
                    _events.tryEmit(Event.NoSpeech)
                    return@withContext
                }
            }
        } finally {
            effects.forEach { runCatching { it.release() } }
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { recogniser.close() }
            runCatching { barge?.close() }
            _amplitude.value = 0f
            Log.d(TAG, "capture stopped")
        }
    }

    // --------------------------------------------------------------- helpers

    /** Peak-based level, 0..1. Cheap, and closer to what an orb should follow than RMS. */
    private fun level(buffer: ShortArray, length: Int): Float {
        var peak = 0
        // Every fourth sample is plenty for a visual level and a quarter of the work.
        var i = 0
        while (i < length) {
            val value = abs(buffer[i].toInt())
            if (value > peak) peak = value
            i += 4
        }
        return min(1f, peak / 12_000f)
    }

    /** Asymmetric smoothing: jump to a rising level, fall back gently. */
    private fun smooth(previous: Float, next: Float): Float =
        if (next > previous) previous + (next - previous) * 0.6f
        else previous + (next - previous) * 0.15f

    /**
     * The best transcription in a Vosk result.
     *
     * With alternatives enabled the payload is `{"alternatives":[{"text":...}, ...]}`
     * ordered by the decoder's own confidence. We take its first choice unless a
     * later one is something Sentry can actually act on — see [preferHypothesis].
     * Falls back to the plain `{"text":...}` shape, which is what the hotword
     * recogniser and any un-aliased result produce.
     */
    private fun textOf(json: String?): String {
        if (json == null) return ""
        return runCatching {
            val root = JSONObject(json)
            val alternatives = root.optJSONArray("alternatives")
                ?: return@runCatching root.optString("text").trim()

            val hypotheses = (0 until alternatives.length())
                .mapNotNull { alternatives.optJSONObject(it)?.optString("text")?.trim() }
                .filter { it.isNotEmpty() }

            if (hypotheses.isEmpty()) return@runCatching ""

            val top = hypotheses.first()
            val best = reRank(top, hypotheses)
            if (best != top) Log.d(TAG, "re-ranked \"$top\" -> \"$best\"")
            best
        }.getOrDefault("")
    }

    /**
     * Prefer a hypothesis that is a real command — but only ever as a *correction*,
     * never as a reinterpretation.
     *
     * Left unrestricted this does real damage. Asked "who wrote the book Dune", the
     * decoder offered "open the book turn" somewhere down its list, that parsed as an
     * "open app" command, and the question became a failed app launch. Biasing towards
     * commands trades a rare transcription fix for turning conversation into wrong
     * actions, which is the same bad trade [com.sentry.nlu.FastMatcher] refuses to
     * make.
     *
     * So the swap has to look like a mishearing of the same short phrase: commands are
     * brief, and a correction keeps the shape of what was said. "follow up" to
     * "volume up" qualifies. A five-word question does not.
     */
    private fun reRank(top: String, hypotheses: List<String>): String {
        val prefer = preferHypothesis ?: return top
        if (prefer(top)) return top

        val topWords = top.split(' ').filter { it.isNotBlank() }
        if (topWords.size > MAX_RERANK_WORDS) return top

        return hypotheses.drop(1).firstOrNull { candidate ->
            val words = candidate.split(' ').filter { it.isNotBlank() }
            words.size == topWords.size &&
                similar(candidate, top) &&
                prefer(candidate)
        } ?: top
    }

    /** Close enough to be the same utterance heard differently. */
    private fun similar(a: String, b: String): Boolean {
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return true
        return editDistance(a, b).toFloat() / longest <= MAX_RERANK_DISTANCE
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun partialOf(json: String?): String = runCatching {
        JSONObject(json ?: return "").optString("partial").trim()
    }.getOrDefault("")

    fun close() {
        stop()
        runCatching { model?.close() }
        model = null
        _ready.value = false
    }
}
