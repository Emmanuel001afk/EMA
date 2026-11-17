# EMA Offline Speech-to-Text & Speech Recognizer

EMA includes its own full on-device English speech-to-text (STT) engine,
system-registered Android 8–15 `RecognitionService`, system-wide
`ACTION_RECOGNIZE_SPEECH` / `ACTION_WEB_SEARCH` voice dialog, and universal
local audio file transcriber. No internet connection, external speech app,
cloud account, API key or runtime model download is required.

## Architecture

```
Live Mic (EmaAudioCaptureRouter)          or   Pipe / Local Audio (EmaOfflineAudioDecoder)
  - AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE          - Streaming O(1)-memory MediaExtractor
  - BLE headset / USB / wired / BT SCO /           + MediaCodec + direct RIFF/WAVE
    built-in mic routing (API 26-35)             - .wav (8/16/24/32 PCM, float32/64,
  - Ordered source & rate fallback                 A-law, μ-law, extensible), .m4a, .mp3,
    (16 / 48 / 44.1 kHz) + HW NS/AEC/AGC           .opus, .ogg, .flac, .aac, .amr, .3gp,
                                                   .webm, .mp4, .mkv (up to 20 min)
  -> EmaSpeechAudioProcessor
       - 75 Hz first-order high-pass filter (DC offset & mic handling rumble removal)
       - Exact-reconstruction 3-band adaptive Wiener spectral noise suppressor
         (0-300 Hz rumble, 300-3400 Hz voiced formants, 3400-8000 Hz sibilants)
       - Multi-cue speech-band + zero-crossing adaptive VAD with asymmetric noise tracker
       - 360 ms pre-roll ring buffer (keeps initial consonants and plosives intact)
       - Speech-gated automatic gain control + C1-continuous soft-knee limiter
       - Cadence-, hesitation-, continuation- & confirmation-aware smart endpointer
       - AudioQuality analyzer (muted mic, clipping, low SNR, quiet voice hints)
       - 13-tap Hann-windowed sinc FIR anti-aliased downsampler & cubic upsampler
         (4-192 kHz, 1-8 channels -> 16 kHz mono PCM16)
  -> EmaOfflineSpeechModel + Vosk lattice decoder
       - Shared reference-counted native Model lease (zero-copy sharing with Hey EMA)
       - Verified SHA-256 bundled archive (EmaWakeModel)
       - Background warm-up, 90 s idle eviction, onTrimMemory release & session telemetry
       - N-best lattice decoding with word timings & partial MBR posteriors
  -> EmaVoskJsonParser + EmaUtteranceAssembler
       - Pure-Kotlin JSON parser, lattice/word confidence calibrator & WPM estimator
       - Breath/cough/click hallucination filter (preserves short 1-word commands)
       - Multi-segment beam combiner with O(1) prefix compaction for long recordings,
         boundary-echo deduplication & pause-aware sentence/paragraph punctuation
  -> EmaInverseTextNormalizer + EmaPhoneticMatcher + EmaSpeechRescorer
       - Spoken clocks ("six thirty a m" -> "6:30 am"), phone/digit runs with
         plus/double/triple/oh, compound ordinals ("21st"), signed/decimal/compound
         numbers, percentages, spoken emails & web domains
       - Disfluency, word-stutter & spoken self-repair ("scratch that") cleanup
       - Rich voice dictation: extended punctuation, quote/bracket pairs, hashtags,
         clause deletion, paragraph preservation & proper-noun/contact/app casing
       - Content-boundary protection (message, search & typed bodies untouched)
       - Margin-gated phonetic grounding across apps, contacts (preserving shared
         given-name ambiguity) and live on-screen UI controls, including global
         and West-African names/fintech spellings (gb/kp digraphs, split homophones)
       - MBR/ROVER N-best word consensus + verified owner mishearing personalization
         + profile-aware ranking (COMMAND, DICTATION, WEB_SEARCH, CONFIRMATION)
```

## Speech Modes (`EmaSpeechMode`) & Profiles (`EmaRecognitionProfile`)

Selectable in **Setup & preferences** or by voice across 19 regional English
BCP-47 locales (`en-US`, `en-GB`, `en-NG`, `en-AU`, `en-CA`, `en-IN`, `en-IE`,
`en-NZ`, `en-ZA`, `en-GH`, `en-KE`, `en-TZ`, `en-UG`, `en-SG`, `en-PH`,
`en-HK`, `en-JM`, `en-001`, `en`):

1. **`EMA offline first` (optional system fallback)** — Runs `EmaOfflineSpeechEngine` directly
   in-process first. Falls back to Android on-device (API 31+) or an explicit
   external system recognizer only when the offline engine cannot serve the
   request (e.g. non-English locale or severe low memory). Voice: *"offline speech first"*.
2. **`EMA offline only` (default for new installations; strict private)** — Uses only EMA's built-in offline
   engine. External and cloud speech services are never contacted. Voice:
   *"offline speech only"*.
3. **`System recognizer first`** — Tries Android's on-device / system recognizer
   first when online, switching immediately to EMA's built-in offline engine
   offline or on any platform error. All platform results still pass through
   `EmaSpeechRescorer` and `EmaInverseTextNormalizer`. Voice: *"system speech first"*.

Existing explicit speech-mode choices are preserved on upgrade. Select *"offline speech only"* to enforce local-only recognition on an existing installation. The model is bundled; a model-open I/O failure triggers at most one local reinstall/reload from verified APK assets. Memory and permission failures are not treated as corruption. No availability or accuracy guarantee can override Android resource limits.

Recognition profiles adapt endpointing, normalization and rescoring per task:
`COMMAND` (assistant actions), `CONFIRMATION` (snappy yes/no/choice replies when
EMA asks a follow-up question), `DICTATION` (long-form voice typing & audio file
transcription), and `WEB_SEARCH` (search queries & domains).

Diagnostic voice commands: *"offline speech status"*, *"prepare offline speech"*.

## Capabilities

- **Live voice commands & follow-ups** in `MainActivity` (**Speak**) and
  `EmaVoiceInteractionSession`, with live RMS voice metering, headset/mic route
  indication, streaming normalized partials, automatic `CONFIRMATION` mode for
  follow-up answers, on-screen `AssistStructure` label grounding, tap-to-finish
  flushing and instant release handoff from `EmaWakeWordService`.
- **Live offline voice dictation** (**Dictate** button in `MainActivity`) with
  extended pause tolerance, `"scratch that"` clause editing, proper-noun and
  sentence capitalization, spoken emails/domains and full punctuation commands.
- **Universal offline audio & voice-note transcription** via the **Audio** file
  picker button (no broad storage permission needed), Android's **Share** sheet
  (`audio/*` — e.g. WhatsApp `.opus` or Voice Recorder `.m4a` clips), voice
  commands (*"transcribe last recording"*, *"transcribe meeting.m4a"*), or
  `EmaOfflineSpeechEngine.transcribeFile` / `transcribeUri` / `transcribeWav` /
  `transcribePcm`, with live progress, cancellation and automatic clipboard copy.
- **Android 8–15 `RecognitionService` & `EmaRecognizeSpeechActivity`** supporting
  `onCheckRecognitionSupport` (API 33/34), `EXTRA_SEGMENTED_SESSION` continuous
  multi-utterance streaming (`segmentResults` / `endOfSegmentedSession`),
  `EXTRA_AUDIO_SOURCE` (8/16/24/32-bit PCM, float & WAV pipes),
  `EXTRA_ENABLE_FORMATTING`, `EXTRA_MASK_OFFENSIVE_WORDS`,
  `EXTRA_BIASING_STRINGS`, `LANGUAGE_MODEL_WEB_SEARCH`, `onTriggerModelDownload`
  (API 33/34, zero network), and system-wide `ACTION_RECOGNIZE_SPEECH` /
  `ACTION_WEB_SEARCH` dialogs with `EXTRA_RESULTS_PENDINGINTENT` support.

## Privacy & Honest Limits

- PCM lives only inside bounded in-memory buffers; it is never written to disk
  or sent over any network by EMA's offline engine.
- Caller UID / package isolation ensures third-party apps calling
  `EmaRecognitionService` or `EmaRecognizeSpeechActivity` never access the
  owner's private contacts, shortcuts or learned corrections; contact names are
  likewise never attached to external `EXTRA_BIASING_STRINGS` or logged.
- The bundled acoustic model (`vosk-model-small-en-us-0.15`, Apache 2.0) covers
  English. Non-English locales automatically delegate in *EMA offline first* /
  *System recognizer first* or report the English-only limit honestly in
  *EMA offline only*.
- Real-device microphone acoustics, regional accent accuracy, vendor `MediaCodec`
  behaviour, Bluetooth BLE/SCO headset switching and low-RAM headroom still
  require physical device verification alongside the pure-JVM test suite.
