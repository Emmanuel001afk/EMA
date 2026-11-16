# EMA Offline Speech-to-Text & Speech Recognizer

EMA includes its own full on-device English speech-to-text (STT) engine and
Android `RecognitionService`. No internet connection, external speech app,
cloud account, API key or runtime model download is required.

## Architecture

```
Microphone (AudioRecord 16/48/44.1 kHz + HW NS/AEC)  or  PCM / RIFF-WAVE stream
  -> EmaSpeechAudioProcessor
       - 75 Hz first-order high-pass filter (DC offset & mic rumble removal)
       - 300-3600 Hz speech-band monitor + zero-crossing check + adaptive VAD
       - 360 ms pre-roll ring buffer (keeps initial consonants intact)
       - Speech-gated automatic gain control + C1-continuous soft-knee limiter
       - Continuation-aware smart endpointer (holds longer after open clauses)
       - Box-averaged / linear resampler (4-192 kHz, 1-8 ch -> 16 kHz mono)
       - Bounded RIFF/WAVE decoder (8/16/24/32-bit PCM & IEEE 32-bit float)
  -> EmaOfflineSpeechModel + Vosk lattice decoder
       - Shared reference-counted native Model lease (shared with Hey EMA)
       - Verified SHA-256 bundled archive (EmaWakeModel)
       - Background warm-up, 90 s idle eviction & onTrimMemory low-RAM release
       - N-best lattice decoding with word timings & partial MBR posteriors
  -> EmaVoskJsonParser + EmaUtteranceAssembler
       - Pure-Kotlin JSON parser, lattice/word confidence calibrator
       - Breath/cough hallucination filter (never discards short commands)
       - Multi-segment beam combiner across natural speaking pauses
  -> EmaInverseTextNormalizer + EmaPhoneticMatcher + EmaSpeechRescorer
       - Spoken clocks ("six thirty a m" -> "6:30 am"), dual alarms, decimals,
         compound numbers, percentages & spoken dictation punctuation
       - Content-boundary protection (message, search & typed bodies untouched)
       - Owner shortcut & package-spelling preservation
       - Unambiguous margin-gated phonetic slot grounding for apps & contacts
       - Contextual domain-aware N-best rescoring (shortcuts, learned phrases,
         aliases, installed apps, contacts, system/grammar/knowledge parseability)
```

## Speech Modes (`EmaSpeechMode`)

Selectable in **Setup & preferences** or by voice:

1. **`EMA offline first` (default)** — Runs `EmaOfflineSpeechEngine` directly
   in-process first. Falls back to Android on-device (API 31+) or an explicit
   external system recognizer only when the offline engine cannot serve the
   request (for example a non-English locale or severe low memory). Voice:
   *"offline speech first"*.
2. **`EMA offline only` (strict private)** — Uses only EMA's built-in offline
   engine. External and cloud speech services are never contacted. Voice:
   *"offline speech only"*.
3. **`System recognizer first`** — Tries Android's on-device / system recognizer
   first when online, switching immediately to EMA's built-in offline engine
   when the phone has no network or if the platform recognizer fails. Even
   platform results pass through `EmaSpeechRescorer` and
   `EmaInverseTextNormalizer`. Voice: *"system speech first"*.

Diagnostic voice commands: *"offline speech status"*, *"prepare offline speech"*.

## Capabilities

- **Live voice commands** in both `MainActivity` (**Speak**) and the system-wide
  `EmaVoiceInteractionSession` overlay, with streaming normalized partial
  transcripts, tap-to-finish flushing (`stopListening()`), transient microphone
  handoff retry and zero-duplicate-RAM sharing with `EmaWakeWordService`.
- **Live offline dictation** (**Dictate** button in `MainActivity`) using
  `EmaRecognitionProfile.DICTATION` with extended pause tolerance, sentence
  capitalization and spoken punctuation (*comma*, *period* / *full stop*,
  *question mark*, *exclamation mark*, *new line*, *new paragraph*).
- **Offline audio file transcription** (`EmaOfflineSpeechEngine.transcribeWav`
  and `transcribePcm`, or *"transcribe \<name\>.wav"*), confined to allowed local
  storage folders via `EmaFileAccessEngine`.
- **Android `RecognitionService`** (`EmaRecognitionService`) supporting Android
  8-15, including Android 13+ `onCheckRecognitionSupport`, `EXTRA_AUDIO_SOURCE`
  stream transcription, `EXTRA_BIASING_STRINGS`, and Android 13/14
  `onTriggerModelDownload` (warms the bundled model locally with zero network),
  emitting both `SpeechRecognizer.CONFIDENCE_SCORES` and
  `RecognizerIntent.EXTRA_CONFIDENCE_SCORES`.

## Privacy & Honest Limits

- PCM lives only inside bounded in-memory buffers; it is never written to disk
  or sent over any network by EMA's offline engine.
- Local contact names used for slot rescoring stay inside EMA's process memory
  and are never placed into `EXTRA_BIASING_STRINGS` on external recognizers or
  written to logcat.
- The bundled acoustic model (`vosk-model-small-en-us-0.15`, Apache 2.0) covers
  English. Non-English system locales automatically use platform recognizers in
  `EMA offline first` and `System recognizer first`, or explain the English-only
  scope honestly in `EMA offline only`.
- Real-device microphone acoustics, regional accent accuracy, Bluetooth SCO/BLE
  headset routing and memory headroom on low-RAM phones still require physical
  device verification alongside the pure-JVM test suite.
