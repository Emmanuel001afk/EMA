# "Hey EMA" wake word: audit and fixes

This audit covers the screen-off wake service (`EmaWakeWordService`), which uses
Vosk locally with the bundled `vosk-model-small-en-us-0.15`. It also covers the
in-session wake gate (`EmaWakePhraseGate`) used while the assistant overlay is
open.

## How detection works

```
AudioRecord (16 kHz mono, 50 ms frames)
  -> EmaWakeAudioGate     cheap energy gate: only voice-like windows reach the decoder
  -> Vosk Recognizer      restricted grammar (EmaWakeDetector.grammarJson())
  -> EmaWakeDetector      token search + stability check on partial results
  -> activate()           opens the assistant session (or a notification fallback)
```

Everything in the first three boxes runs on the phone. No audio leaves the device
during wake detection.

## Root causes found

These are listed in order of impact. Each one is fixed and covered by JVM tests.

### 1. Detections were thrown away because the check used exact string equality

The service compared the whole decoder hypothesis with `"hey emma"`. With a
restricted grammar, Vosk routinely returns hypotheses such as `"[unk] hey emma"`
or `"hey emma [unk]"` when there is a breath, a click or a word before the
phrase. Those never equalled `"hey emma"`, so a correctly recognised wake phrase
was discarded. **This is the most likely reason "Hey EMA" seemed not to work.**

**Fix:** `EmaWakeDetector.containsWakePhrase` searches the tokens for the pair
`hey` + (`emma` | `ema`) and ignores `[unk]`.

To keep false triggers down, a *partial* result must contain the phrase in two
consecutive updates before EMA activates. A *final* result triggers immediately.

Tests: `EmaWakeDetectorTest.phraseInsideNoisyHypothesisIsFound`,
`partialsMustBeStableBeforeTriggering`.

### 2. Steady background noise kept the decoder running forever

`EmaWakeAudioGate` only adapted its noise floor while the gate was closed. A fan,
traffic or a TV is louder than the floor, so it opened the gate. Once the gate
was open the floor never adapted again, and it stayed open for as long as the
noise lasted.

As a result, Vosk decoded continuously, which cost CPU and battery. Its window
also filled with `[unk]`, which made real detections less likely. The old unit
test encoded this behaviour: it expected two decoder resets during 20 s of
constant noise, meaning the decoder never stopped.

**Fix:** after 4 s of uninterrupted "voice", the floor slowly follows the
level. Nobody says "Hey EMA" for four seconds. Steady noise is reclassified as
background within a few more seconds and stops reaching the decoder. Speech that
is clearly louder than the learned background still opens the gate.

Tests: `EmaWakeAudioGateTest.negativePcmAndContinuousNoiseAreBounded` (rewritten
to assert the desired behaviour), `speechAboveLearnedNoiseStillOpensGate`,
`shortSpeechDoesNotRaiseBackground`.

### 3. The grammar forced near-misses into the wake phrase

The grammar contained only `"hey emma"` and `[unk]`. With so few choices, Vosk
maps words like "hey Gemma", "hey Emmy" or "Emily" to the closest entry, which
is often the wake phrase. That causes false triggers.

**Fix:** the grammar now has:

- both spellings (`hey emma`, `hey ema`);
- competitor words and phrases, so near-misses have somewhere to go: `emmy`,
  `gemma`, `emily`, `ella`, `anna`, `emmet`, `amen`, `hey emmy`, `hey gemma`, and
  so on.

Every word was checked against the model's vocabulary, because Vosk silently
drops out-of-vocabulary words. `emma's` and `<unk>` are *not* in the model and
are not used.

Test: `grammarContainsBothSpellingsCompetitorsAndUnknown`.

### 4. The in-session gate rejected the spelling the recogniser actually returns

Android's speech recogniser usually writes the name as "Emma". The overlay's
`EmaWakePhraseGate` only accepted `hey ema`, so "Hey Emma, open YouTube" was
ignored when "Require Hey EMA" was on.

**Fix:** the gate now accepts `ema`, `emma`, `emmah`, `ima` and `e.m.a`. It still
rejects longer words such as "Emmanuel" and "emailed".

Test: `phraseGateAcceptsBothSpellingsAndStripsWakeWords`.

## Two stages: the decoder makes a candidate, the owner decides

The decoder answers *what* was said, never *who* said it. "Hey Emma" from a
television is the same words as "Hey EMA" from the owner, so one stage can
never be both sensitive enough and quiet enough. The wake word now has two
stages:

```
AudioRecord (16 kHz mono, 50 ms frames)
  -> EmaWakeAudioGate     cheap energy gate: only voice-like windows reach the decoder
  -> Vosk Recognizer      restricted grammar (EmaWakeDetector.grammarJson())
  -> EmaWakeDetector      stage 1: a lenient candidate
  -> EmaVoiceFeatures     stage 2: MFCCs of the sound around the candidate
  -> EmaVoiceVerifier     stage 2: DTW against the owner's own samples
  -> activate()           opens the assistant session (or a notification fallback)
```

**Stage 1 (lenient).** When the owner has enrolled their voice, one partial
result is enough to make a candidate. A missed wake word is worse than a false
candidate, because stage 2 can throw the false one away. Without an enrolled
voice the old rule applies unchanged (a partial must show the phrase twice), so
a phone that never enrolled behaves exactly as before.

**Stage 2 (the personal check).** The sound around the candidate is turned into
MFCCs (`EmaVoiceFeatures`: pre-emphasis, framing, a mel filterbank, a DCT; the
loudness coefficient is dropped so the same words at another volume still
match). The candidate is compared with the owner's own recorded "Hey EMA" using
dynamic time warping (`EmaDtw`), because two recordings of the same words never
line up frame by frame.

The threshold is **measured, not guessed**: the owner's own samples are
compared with each other and the threshold is a little above that distance
(`EmaVoiceVerifier.CALIBRATION_FACTOR`). A different voice is much further
away, so it does not pass. Because the threshold is a factor of a measured
distance, it works whatever the microphone and the room sound like.

### Teaching EMA the owner's voice

* In Setup & preferences: **Teach EMA my voice** (and **Forget my voice**).
* By voice: "EMA, learn my voice" (or "teach EMA my voice").
* The owner says "Hey EMA" eight times. Each round records until the sound has
  stopped, so the template holds the words and not the room around them. Every
  sample is kept (not only their average), because the threshold is measured
  from how far the owner's own samples are from each other.
* The samples stay in the app's private storage and never leave the phone.

### Learning after the enrollment

* A wake that was followed by a real command is a **positive** example.
* A candidate that the personal check threw away, and a wake the owner
  rejects with "not you", are **negative** examples.
* A negative raises the threshold (`noteNegative`, bounded by
  `EmaVoiceVerifier.MAX_OFFSET`).
* The template list is bounded (`EmaVoiceVerifier.MAX_TEMPLATES`, 30). When it
  is full, the template that fits the others least well is replaced: an
  outlier teaches the verifier less than a typical sample does.

## Lifecycle (reviewed, unchanged)

- The service starts only from a visible activity. That is required on Android
  14 and later for a microphone foreground service.
- It shows a persistent notification while listening.
- It pauses and releases the microphone while the assistant session is visible
  or while Android's speech recogniser is capturing.
- It resumes afterwards (`pauseForSpeech` / `resumeAfterSession`).
- If the model or the microphone fails, the service stops and records a status
  message. It does not leave a notification claiming that it is listening.

## Battery and screen-off behaviour (honest note)

Screen-off listening needs continuous `AudioRecord` capture and a
`PARTIAL_WAKE_LOCK`. The lock is acquired for 60 minutes and renewed every 30
minutes while capture runs. It is released whenever capture stops (paused,
assistant open, or service stopped).

Phones that detect hotwords in a low-power DSP reserve that for privileged system
apps, so a regular app like EMA cannot use it. **Screen-off wake therefore has a
real battery cost.** The audio gate fix (root cause 2) lowers the CPU cost in
noisy places, because the decoder no longer runs constantly. The microphone and
the wake lock remain.

The battery cost has not been measured on a device. Measuring it should be part
of device validation. OEM battery managers ("Android Go", aggressive vendors) can
still stop the service. The settings screen links to battery-optimisation
settings for that reason.

## What was not verified

There was no phone or microphone in the development environment. All of the
following still need a physical device:

- detection rate;
- false-trigger rate;
- screen-off behaviour;
- battery drain;
- whether the measured threshold separates the owner from a television on a
  real phone (the rules are unit tested with synthetic features, not with a
  real voice).

No claim is made here about how well this works on a phone. The numbers below
are the ones to write down when the phone is in hand.

## On-phone checklist (a phone is required)

Run this on the phone that will be used. Write the numbers down: they are the
only honest answer about the wake word.

**Before the enrollment (the decoder alone)**

1. Quiet room, 1 m: say "Hey EMA" ten times, counting how many wake the
   phone. Write down *hits per 10 tries*.
2. Quiet room, 3 m: the same ten times. Write down *hits per 10 tries*.
3. TV or talk playing for one hour, phone on the table: count every wake.
   Write down *false wakes per hour*.
4. "Hey Gemma", "Hey Emmy", "Emily" must not wake the phone.

**After the enrollment (the personal check)**

5. Teach EMA the voice (Setup & preferences, or "EMA, learn my voice"), then
   repeat steps 1 to 4. Write down the new numbers.
6. The owner says "Hey EMA" ten times in a row: all ten must wake the phone.
   If some do not, the voice was not learned properly - teach it again.
7. The television says "Hey EMA" ten times: none of them may wake the phone.
8. A different person says "Hey EMA" ten times: none of them may wake the
   phone.
9. "Not you" right after a wrong wake: the next attempt with the same sound
   must not wake the phone.
10. The phone must still wake the owner with the screen off, and the assistant
    must still open.

**What to report**

- hits per 10 tries, at 1 m and 3 m, before and after the enrollment;
- false wakes per hour of television and per hour of talk, before and after
  the enrollment;
- how long the enrollment took and whether all eight rounds were heard.

The checklist in the README ("Acceptance checklist (physical device required)")
still applies. In particular, test:

- quiet room, 1 m and 3 m;
- TV or fan noise;
- "hey Gemma" and "hey Emmy" (they must not trigger);
- screen off for 30 minutes, then "Hey EMA".
