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
- battery drain.

The checklist in the README ("Acceptance checklist (physical device required)")
still applies. In particular, test:

- quiet room, 1 m and 3 m;
- TV or fan noise;
- "hey Gemma" and "hey Emmy" (they must not trigger);
- screen off for 30 minutes, then "Hey EMA".
