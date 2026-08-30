# Sentry

**An Android assistant that answers before it thinks.**

Sentry replaces the system assistant. It hears its wake word in about 150 ms, does the
ordinary things — alarms, timers, calls, the torch, music — in about 10 ms, and only
reaches for a language model when the question actually needs one. Everything runs on
the phone. With the network off, all of it still works.

---

## Why it is built this way

The assistant this replaced sent every utterance through a language model and waited
for JSON to come back. "Turn on the flashlight" and "what caused the fall of Rome"
took the same path and, near enough, the same time. That is the whole problem, and no
faster model fixes it, because the model was never the right tool for the flashlight.

So there are three tiers, and almost everything stops at the first:

```
"turn on the flashlight"          "set an alarm for half seven"        "why is the sky blue"
         │                                    │                                  │
         ▼                                    ▼                                  ▼
  ┌─────────────────────────────────────────────────────────────────────────────────┐
  │  1. FastMatcher — regular expressions, no model                        ~1 ms    │
  └─────────────────────────────────────────────────────────────────────────────────┘
         │ hit                                │ hit                               │ miss
         ▼                                    ▼                                   ▼
       done                                 done              ┌──────────────────────────────┐
                                                              │ 2. Planner — is it a question?│
                                                              │    yes → straight to chat     │
                                                              │    no  → one constrained call │
                                                              └──────────────────────────────┘
                                                                             │
                                                                             ▼
                                                              ┌──────────────────────────────┐
                                                              │ 3. Tara Core / AICore         │
                                                              │    streamed, spoken sentence  │
                                                              │    by sentence as it arrives  │
                                                              └──────────────────────────────┘
```

Measured on a Pixel 9a, from the transcript landing to the answer being ready:

| Utterance | Path | Time |
|---|---|---|
| "turn off the flashlight" | FastMatcher → Skills | **18 ms** |
| "what time is it" | FastMatcher → Skills | **48 ms** |
| "set a timer for five minutes" | FastMatcher → Skills | **11 ms** |
| "what is the capital of france" | Planner → Tara Core | **8.2 s** |

The last row is the model generating, and it is the row that explains the design: it
is the only kind of request that should ever cost that, and it is a small minority of
what anyone actually says to a phone.

## The wake word

Vosk, running against a two-word grammar — `"sentry"` and Vosk's out-of-vocabulary
token. Restricting the grammar is what makes it cheap enough to leave on: the decoder
has almost no search space to cover.

```
21:35:05.981  wake word
21:35:05.984  opening the assistant          +3 ms
21:35:06.143  capture started in COMMAND    +162 ms
```

It needs a foreground service with a visible notification. That is not a design
choice — Android will not let an app hold the microphone from the background for any
length of time — so the notification says plainly what is happening and offers an off
switch.

**One microphone, two recognisers.** `VoiceEngine` owns a single `AudioRecord` and
switches which Vosk recogniser the samples feed. The obvious alternative — a separate
capture session per job — has to release and re-acquire the microphone every time the
wake word fires, which costs a few hundred milliseconds and eats the first syllable of
the sentence the user has already started saying.

## Conversation

After Sentry answers, the microphone reopens by itself. No wake word, no tapping, no
waiting for a beep. The conversation ends when you stop talking, say "stop", or dismiss
the screen — and it stays resumable for three minutes, so coming back with "and when
did he die" still knows who "he" is.

Interrupting a long answer is done by saying **"Sentry"** over it. That is a narrower
mechanism than it might be, and deliberately: the phone's speaker is inches from its
microphone, so the assistant hears itself, and what comes back is not its own sentence
but a garble of it. Trying to filter that textually failed exactly as you would expect
— asked for the capital of France, Sentry heard "that is his", decided a stranger was
talking, stopped mid-answer to listen, and did it again on the next fragment. A
two-word grammar cannot make that mistake, because garble decodes to `[unk]`, never to
"sentry".

Two other guards keep an always-open microphone from talking to itself:

- **An energy gate.** A small acoustic model asked to decode room tone returns *words*,
  not silence. Utterances that never got loud enough to be speech are dropped, with the
  measured peak logged so the threshold can be argued with.
- **A hangover on "speaking".** A streamed answer is spoken sentence by sentence, and
  the flag flickers false in the gaps while the speaker is still physically playing.

## Recognition

### The wall: names are not in the lexicon

Sentry kept hearing "call maa" as "karma". It is worth being precise about why,
because it decides what can be done about it. Vosk logs which grammar words it can
actually produce:

```
Ignoring word missing in vocabulary: 'maaaaaaa'
Ignoring word missing in vocabulary: 'ananya'
Ignoring word missing in vocabulary: 'akshitha'
Ignoring word missing in vocabulary: 'chintu'
```

The recogniser has a fixed pronunciation lexicon and **cannot emit those strings at
all**. "Karma" is simply the nearest thing it owns. No accent pack, no larger model
and no fuzzy matching on the contact name fixes that — by the time the audio is
transcribed the name is already gone, and so is the word "call" with it.

Apple hits the same wall and solves it two ways: generating pronunciations for unknown
names on the fly (needs a phone-level lexicon and a rebuilt decoding graph — not
available here), and **mapping rare entity words onto phonetically similar frequent
words the model already handles**, which is worth ~31% relative in their published
numbers. The second is what Sentry does.

### Teaching a phrase

Say the phrase a few times in **Settings → Teach a phrase**. Sentry records what it
actually heard and stores a rewrite, applied before anything else looks at the words:

```
PhraseBook: "voters" -> "what is the date" (learned)
Agent:      heard: "what is the date"
Agent:      reply: "It's Sunday, 30 August."
```

This is *not* retraining the recogniser, and the screen says so. Nor is it what "Ok
Google" enrollment does — those phrases build a speaker embedding for wake-word
verification, confirmed in Apple's equivalent write-up of "Hey Siri". It learns a
consistent mistake so that the mistake stops mattering.

Two guards, both added after watching them be needed. Every distinct variant is
learned, not just repeated ones, because the mistake is stable in kind but not in
detail — one phrase produced "voters", "voters", "don't compete" and later "eat".
And a single very common word is refused: teaching "what is the date" produced "what"
twice, and binding *that* would have turned every later sentence into a date query.

### Biasing the decoder towards your contacts

A second decoder runs beside the open-vocabulary one, restricted by grammar to
commands built from starred contacts and anything taught. This is Siri's class-LM slot
substitution — the general model carries a placeholder where a contact name goes, and
a per-user grammar is spliced in at decode time.

It is consulted **only when the open decoder produced nothing actionable**. A
grammar-restricted decoder cannot say "I don't know" except through `[unk]`, so
letting it speak first would turn remarks about the weather into phone calls.

Taught phrases matter here twice over: a taught phrase is by construction something
the recogniser *did* produce, so unlike a contact's real name it survives into the
grammar intact.

### Accent packs

Two acoustic models ship, and the setup screen lets you pick:

| Pack | For |
|---|---|
| **English (India)** *(default)* | Indian-accented English |
| English (US) | American-accented English |

Accent matters more than size here. A US model transcribing Indian-accented English
mishears in ways more parameters do not fix, so this is a visible choice rather than a
build-time constant.

Both are *small* models, and that is a finding rather than a compromise.
`vosk-model-en-us-0.22-lgraph` was tried — 205 MB unpacked, and better on paper — and
abandoned: on a Pixel 9a it decodes full-vocabulary audio at **1.1–2.0× real time**.
The microphone keeps producing audio while it works, the buffer overruns, and the
words that get dropped are the user's. "Set a timer for five minutes" came back as
"set a timer for", and "who wrote the book Dune" as "the". Tuning the beam
(`max-active` 7000 → 3000) helped but did not close the gap. A decoder that cannot
keep up does not give worse answers; it gives truncated ones, which is worse than a
smaller model that finishes.

Endpointing counts **consumed audio, not elapsed time**, for the same reason: a
wall-clock timer declares the speaker finished while their words are still queued.

## The model

Sentry ships no weights and no inference engine. It asks
[Tara Core](https://github.com/weberq/taracore), which holds one copy of the model for
the whole device.

- **Which model** is whatever Tara Core has resident, and that is the user's choice,
  made in Tara Core. Sentry never names one, because naming one invites a
  multi-second swap and the engine is shared with every other app on the phone.
- **Structured output is constrained, not requested.** Intent labels and slot
  extraction go out with a GBNF grammar, so a 0.5B cannot answer in prose where a
  fixed shape was needed.
- **AICore** (Gemini Nano) is used instead on devices that have it, chosen once during
  warm-up behind a real availability probe, falling back to Tara Core on any failure.
  Nothing about it is on a critical path.

## Skills

Alarms · timers · calls · answer/hang up · SMS · music and transport controls · torch ·
volume · Do Not Disturb · camera · battery · open app · web search · navigation ·
Wi-Fi/Bluetooth/internet panels · time · date.

Two notes on judgement calls:

- **Messages are composed, not sent.** Sending silently needs `SEND_SMS` and means a
  misheard word reaches a real person with no chance to catch it. One tap is the right
  price.
- **Radios open a panel.** An app has not been able to toggle Wi-Fi since Android 10.
  Sentry puts you one tap away rather than pretending and quietly failing.

## Lock screen

The assistant surface is an activity with `showOnLockScreen`, so setting a timer,
answering a call or turning on the torch works without unlocking. It does *not* dismiss
the keyguard on open — that would defeat the point. `requireUnlock` exists for the few
things that should ask.

## Setup

The speech models are not in this repository — three third-party binaries totalling
about 140 MB, one of which ships no licence statement. Fetch them first:

```bash
./scripts/fetch-models.sh     # ~140 MB, once
./gradlew :app:installDebug
```

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for what that downloads and
under which licences.

Open Sentry. The first screen is a checklist of the five things Android will not let an
app arrange for itself, each with the one button that fixes it. Then turn on the wake
word.

To make Sentry the default assistant from a workstation:

```bash
adb shell settings put secure voice_interaction_service \
    "com.sentry/com.sentry.service.SentryInteractionService"
adb shell settings put secure assistant \
    "com.sentry/com.sentry.service.SentryInteractionService"
adb shell settings put secure voice_recognition_service \
    "com.sentry/com.sentry.service.SentryRecognitionService"
```

Requires Tara Core installed with a model downloaded. Without it the fast path still
works in full; only conversation is unavailable, and the setup screen says so.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

The suite covers the two places where being confidently wrong is expensive: what
`FastMatcher` matches — and, as importantly, what it refuses to match — and clock
parsing, where an alarm set twelve hours out is worse than one that failed to be set.

## Known limits

- **Speech recognition is the weakest link**, and it is bounded by what will decode
  in real time on a phone rather than by what is available. Uncommon words and names
  are where it shows. If Sentry keeps mishearing you, change the accent setting first;
  it is a bigger lever than anything else here.
- **`libvosk.so` is not 16 KB page aligned**, which Android 15+ warns about on devices
  with 16 KB pages. It runs fine on 4 KB devices; the fix is upstream in vosk-android.
- **Barge-in requires the wake word**, for the reasons above.
