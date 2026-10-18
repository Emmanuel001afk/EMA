# EMA

A native Android digital assistant. EMA performs phone and app actions **itself,
deterministically, on the device**. AI is an optional second layer that is only
consulted when the on-device layers genuinely cannot resolve a request.

EMA is fully usable with no AI backend at all — no API key, no network call, no
degraded mode.

---

## Design principle: native first, AI second

Every request passes through the same ordered layers. Each one is deterministic
and offline; the AI is last and optional.

| Layer | Component | Responsibility |
| --- | --- | --- |
| 0 | `EmaAssistantMode` | Which layers are allowed to answer (persisted, user-visible) |
| 1 | `EmaNativeCommandPlanner` | Normalizes input and splits compound requests into steps |
| 2 | `EmaCommandGrammar` | Parses speech into a typed `EmaCommand` (play, send, call, alarm, …) |
| — | `EmaCommandRouter` | Literal device commands: volume, brightness, torch, screen, clock, files |
| 3 | `EmaNativeFallbackInterpreter` | Recovers intent from loose wording using installed apps as ground truth |
| 4 | `CentralHubClient` + `EmaAiCommandInterpreter` | **Optional.** Knowledge answers, and canonical-command proposals for unusual wording |
| exec | `EmaAppActionEngine` | Intents → deep links → visible UI → screen control → OCR |
| screen | `EmaAccessibilityService` | Bounded search-and-select, send-button resolution, media transport, audio state |
| verify | `EmaExecutionVerifier` | Decides what EMA may honestly claim succeeded |

Supporting pure components (no Android types, fully unit tested):
`EmaAppResolver`, `EmaSendControlResolver`, `EmaTimeParser`, `EmaNodeSnapshot`,
`EmaJson`, `EmaSystemCommandVocabulary`, `ContactLookup`.

### Assistant modes

Selectable in the app under **Assistant brain**, or by voice:

- **Native first (AI as fallback)** — default. EMA performs every action itself
  and consults the AI only for requests needing outside knowledge.
- **Native only (no AI calls)** — EMA never contacts any AI service. Requests
  needing outside knowledge get an honest explanation instead of a network call.
- **AI-assisted wording (native execution)** — the AI may propose canonical
  commands for unusual phrasing. EMA still executes and verifies everything
  itself. The AI never performs an action and never authorises one.

Say *"what mode are you in"*, *"turn off AI"* or *"turn on AI"* to inspect or
change this by voice.

---

## How actions actually complete

Two flows are worth calling out, because "the app opened" is not the same as
"the task finished".

**Playing a specific song.** EMA opens the app, reveals its Search entry, types
the query, selects the first matching result, and starts playback. Completion is
decided from the requested app's active Android media session: its state must
be playing and its title/artist must match the request. This needs EMA notification
access. Audio from some other song or app is not proof of success. Without usable
metadata, EMA reports that it cannot verify playback.

**Sending a message.** EMA opens the conversation (deep link where the app
supports one: `wa.me`, `mailto:`, `smsto:`), types the body, then verifies the exact recipient in the chat header and the exact body in
the composer before pressing Send. Controls are scoped to the target app, not
mixed with keyboard or assistant controls. A new matching chat bubble and an
empty composer are required for confirmation; short bodies additionally require
composer focus. This confirms visible submission, not delivery.

**Reporting policy.** EMA claims success only on observable evidence. When a step
succeeds and a later one does not, the result is worded as partial progress —
"I opened WhatsApp, but could not find its Send control" — never as success.

---

## Setup

EMA needs a few Android grants before it can act as a system assistant. The app
lists them as buttons on its main screen.

1. **Microphone and Contacts** — requested at first launch.
2. **Assistant role** — *Set EMA as digital assistant*. Uses `RoleManager` on
   Android 10+; on older releases EMA opens the system assistant settings.
3. **Screen control** — *Enable EMA screen control* turns on the accessibility
   service. Required for UI automation, send-button resolution and screen
   reading. Without it EMA still performs every intent-based action and says
   plainly what it could not do.
4. **Notification access** — for reading and dismissing notifications.
5. **All-files access** — for the file commands.
6. **Exact alarms** — for reminders; Android 12+ requires the user to grant this.
7. **Modify system settings** — requested on demand by the brightness commands.
8. **Camera** — only for the torch on devices whose camera stack enforces it.

### Central Hub (optional AI layer)

The AI layer talks to the Central Hub shared AI tunnel. EMA holds **no LLM API
key**.

The endpoint is resolved in this order:

1. A URL saved in the app (*Central Hub address* field), stored in the
   `ema_tunnel` preferences.
2. The build-time default: `-PemaHubUrl=https://…`, or the `EMA_HUB_URL`
   environment variable, or the value in `app/build.gradle.kts`.

```bash
gradle :app:assembleRelease -PemaHubUrl="https://<your-hub>/functions/v1/ai-tunnel"
```

If no endpoint is configured, or the assistant mode disallows AI, EMA answers
from the device only and says so. The AI is never allowed to make a native action
fail.

---

## Building

| | |
| --- | --- |
| minSdk / target / compile | 26 / 35 / 35 |
| Kotlin / AGP / JVM | 2.0.21 / 8.7.3 / Java 17 |
| Dependencies | core-ktx, activity-ktx, ML Kit text recognition, ML Kit image labeling |

```bash
gradle :app:assembleDebug        # debug APK
gradle :app:assembleRelease      # minified, resource-shrunk release APK
gradle :app:testDebugUnitTest    # unit tests
```

The repository has no Gradle wrapper; CI provisions Gradle 8.9.

### Getting a build

CI attaches both APKs to the rolling release
[`apk-latest`](../../releases/tag/apk-latest):

- **`ema-debug.apk`** — debug-signed, installs directly. Use this one.
- **`app-release-unsigned.apk`** — minified and resource-shrunk, but unsigned;
  it needs a signing key before it can be installed.

They are published as release assets rather than run artifacts because GitHub
Actions artifact storage is quota-limited, and a quota failure silently drops the
upload. Release assets use separate storage.

### AEM Store publication

Publishing to the AEM Store is currently **paused** because the Supabase quota is
exhausted. The step is gated and skips cleanly, so it cannot fail a build.

To resume, set the repository variable `EMA_PUBLISH_AEM` to `true`
(Settings → Secrets and variables → Actions → Variables), or tick `publish_aem`
on a manual run. No code change is needed.

---

## Tests

Unit tests are pure JVM — no Robolectric, no emulator. They cover the layers
where correctness is decidable without a device: command parsing, app resolution,
send-control scoring, time understanding, contact ranking, playback evidence, the
JSON reader, and the AI contract.

```bash
gradle :app:testDebugUnitTest
```

CI runs them before building any APK, and publishes the HTML report as an
artifact.

Two tests are structural rather than behavioural, and are the ones to look at
first if a change breaks:

- **`everyAdvertisedCommandPassesTheDeviceActionGate`** — every canonical command
  EMA offers the AI must be one EMA can execute. The prompt's vocabulary is
  *generated* from `CANONICAL_COMMANDS`, so what EMA advertises and what EMA can
  do cannot drift apart.
- **`theGrammarCanReparseItsOwnCanonicalOutput`** — AI proposals are re-executed
  through the same grammar, so a command whose canonical form cannot be read back
  would be silently dropped.

Natural-language understanding, multi-step planning and result selection have
their own suites:

- **`EmaUnderstandingCorpusTest`** reads `app/src/test/resources/ema_understanding_corpus.tsv`.
  Each line is a request and the meaning frame it must produce. It covers the
  five equivalent "message Daniel" formulations, word order, corrections,
  pronouns, content words inside message bodies, private apps and follow-ups.
  To improve understanding, add a failing line, then fix the logic (see
  [docs/UNDERSTANDING_AND_EXECUTION.md](docs/UNDERSTANDING_AND_EXECUTION.md)).
- **`EmaPlanExecutorTest`** covers ordering, dependent-step skipping, bounded
  retries, timeouts with late callbacks ignored, cancellation, and
  duplicate-send protection.
- **`EmaResultExtractorTest` / `EmaResultSelectionFlowTest`** use recorded
  result screens (`EmaResultScreens`) and a fake device. They cover ads,
  first/third/last, scrolling for more, going back from a player to the list,
  refusing "longest" when no lengths are shown, and playback verified by the
  media-session title.
- Knowledge, memory, the capability registry, the wake detector and audio gate
  ([docs/WAKE_WORD.md](docs/WAKE_WORD.md)), and TTS voice selection.

Device-dependent behaviour — whether a specific app's accessibility tree exposes
its Send button — cannot be unit tested and is deliberately isolated in
`EmaSendControlResolver`, which is pure and scored against recorded node
snapshots.

---

## Known limitations

- UI automation depends on what each app exposes to accessibility. Where an app
  exposes nothing usable, EMA reports that honestly instead of guessing.
- The torch requires camera permission on some OEM builds; without it EMA says so.
- Screenshots use the accessibility global action and need Android 9+.
- Reminders are one-shot. Recurring work is handled by routines.
- Understanding is rule-based English. It covers families of phrasings, not all
  of English. Unknown phrasings get an honest "I didn't understand" and can be
  collected locally (opt-in) for the corpus.
- "Longest / newest / most viewed" work only when the app shows that metadata on
  screen. Playback can be verified only with notification access.
- Screen-off "Hey EMA" costs battery (continuous microphone and a partial wake
  lock). See [docs/WAKE_WORD.md](docs/WAKE_WORD.md).
- Voice quality is limited to the TTS voices installed on the phone. EMA picks
  the best installed one and links to Android's voice downloads. No neural voice
  is bundled.

---

## Repository scope

Native Android only. **Projects Continuity** and **Central Hub** are separate
repositories and are not part of this one.

Public foundations reviewed: Orbit Assistant, DeVA, OpenDroid. Their provider
layers were not copied into this project.

## Native action polish and device validation

The home screen now prioritizes the command box, voice capture, status and
editable example requests. Permissions, voice preferences and the optional AI
configuration live under **Setup & preferences**. Examples populate the input;
they do not send messages just because they were tapped.

App-directed shorthand is handled without an AI call, including:

- `open WhatsApp text Daniel I am coming home`
- `open Spotify play Arcade`
- `open YouTube then play Arcade`
- `open Signal then message Daniel Smith saying I am coming home`

Use `saying` for multi-word contact names so EMA does not guess where the name
ends and the message begins. The same planning applies to installed apps, not
only the examples above. Execution still depends on each app's intents and
accessible controls; this is not a promise that arbitrary tasks work in every app.

**Session wake phrase:** opt in under Setup & preferences. While Android's EMA
assistant session is visible, speech requires `Hey EMA <request>`, or `Hey EMA`
followed by one request within ten seconds. Each request consumes the wake.
Typed input bypasses this voice-only gate. This session gate is separate from the opt-in screen-off service described below. Recognition uses Android's
selected speech provider, which may require a network connection even though
command planning itself is offline.

**Message safety:** only the exact requested body read back from the composer
may be submitted. Existing transcript text is not treated as a composed message.
Verification requires an empty composer plus transcript evidence, and verification
retries only observe — they never press Send again. A foreground-package change
stops composition/verification instead of typing into a different app. Visible
chat evidence confirms submission, not delivery to the recipient.

Before claiming release readiness, test on a physical Android phone with screen
control enabled:

1. Send to a uniquely named test contact in WhatsApp; verify recipient, exact body
   and only one sent message, including when that body already exists in chat.
2. Change apps while composition is loading; confirm EMA stops without typing.
3. Play a specific track in Spotify and YouTube; verify the actual title and audio,
   not merely app launch. Account restrictions, ads and login screens may block it.
4. Search an additional installed app and test a missing/ambiguous contact.
5. Enable the session wake gate: ambient speech does nothing, inline wake works,
   standalone wake accepts one request, and a second request needs a new wake.
6. Test reminders with exact-alarm permission both allowed and denied. Scheduling
   implementation is unchanged by this update.

The repository's JVM regression tests cover shorthand planning, message evidence
and wake-gate transitions; app UI behavior still requires the device checks above.


## Screen-off Hey EMA service

**Implemented but not yet device-certified.** A dedicated microphone foreground
service (`EmaWakeWordService`) uses Vosk locally with a restricted wake vocabulary.
It is not Android SpeechRecognizer in a retry loop and sends no idle audio to a
server. Once triggered, it releases AudioRecord before the assistant starts
normal speech recognition and native command execution. The command recognizer
may still use Android's configured network speech provider.

### Setup — no extra app or model import

1. Install EMA and enable **screen-off Hey EMA** in Setup & preferences. Grant
   microphone and notification permissions. No account, API key, file picker,
   companion application or runtime model download is required.
2. The first start automatically prepares the built-in model from APK assets
   into EMA's private, non-backed-up storage. The notification reports preparation
   rather than claiming to listen before the engine is ready.
3. Select EMA as Android's digital assistant for direct activation. Without that
   role, use the listening notification to open EMA.
4. Say **Hey EMA**, pause for activation, then give your request. The bundled
   English model uses the pronunciation **Hey Emma**; accents/pronunciations need
   physical-device validation.

### How the self-contained APK is built

The variant-specific `prepareDebugBundledWakeModel` /
`prepareReleaseBundledWakeModel` tasks retrieve the unmodified small English model during
**development/CI builds**, not on the user's phone. It pins SHA-256
`30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`, checks required
model files and packages the archive as `assets/ema-wake-model.zip`. A corrupt or
unavailable model fails the APK build; there is no model-less release fallback.
The primary source is the official Vosk distribution; the GitHub mirror is
accepted only if its bytes match the same pinned digest. Build-generated binary
assets remain outside Git. Normal cached Gradle builds reuse the generated asset.

The approximately 41 MB archive increases APK size; preparation needs additional
private storage (extracted model plus a temporary archive). No "download model"
step is exposed to end users. Updates preserve the prepared matching model. An
interrupted first preparation can be retried safely. Runtime integrity checking,
bounded extraction and private staging prevent partially prepared assets being
used as a ready model.

The model is third-party Apache-2.0 data, not an EMA-trained model. Its copyright,
license and attribution are included in APK assets. CI verifies **both** debug and
release APKs contain the exact archive, required files and license notices using
`tools/verify_bundled_wake_model.py` before publication.

### Power, lifecycle and security

- EMA uses the phone's physical microphone and Android audio HAL for capture,
  a cheap PCM energy gate with 300 ms pre-roll/1.2 s trailing silence, then local
  restricted-vocabulary decoding. Quiet-room frames bypass the heavier decoder.
  These optimizations are **not a hardware DSP hotword engine** and do not
  establish a measured low-power battery budget. Continuous AudioRecord and a partial wake lock keep it eligible to run
  with the screen off; the bounded wake lock is renewed only during capture and
  released on pause/stop/error. Battery consumption must be measured before a
  production release, especially on Android Go.
- The notification reports listening, handoff or blocked activation and includes
  **Stop listening**. The app detects when no live service exists and labels
  listening stopped instead of trusting an old persisted “listening” message.
- Android may stop or silence microphone capture (privacy toggle, permission
  revocation, task-manager Stop, force-stop, OEM policy). Capture errors stop the
  service; there is no restart loop or evasion of Android restrictions. Reopening
  EMA and pressing Resume is the recovery path.
- A reboot restores schedules as before, but **does not automatically start a
  microphone service**. It records stopped status and posts a resume notification
  when notification permission permits. Modern Android restricts microphone FGS
  starts from boot/background. Force-stopped apps cannot promise reboot recovery.
- The selected VoiceInteractionService requests assistant activation and screen
  wake where Android permits. A notification PendingIntent is the fallback when
  Android blocks background UI. A secure keyguard is never bypassed: the assistant
  blocks command execution while locked, and **Unlock securely** invokes Android's
  keyguard credential/biometric UI. App message typing/sending also rechecks lock.
- This is not a guarantee of immediate screen-on activation on every OEM/Go build.
  A notification tap may be necessary. No full-screen-intent permission is used.

### Acceptance checklist (physical device required)

- Test Android 8/10/12/14/15+ and an actual Android Go device, on battery with the
  screen off for at least 30 minutes; measure battery drain and capture continuity.
- Deny/revoke microphone and notifications; toggle microphone privacy; stop the
  service through its notification, Android task manager and force-stop. Confirm
  truthful stopped status, no automatic permission prompts in the background, and
  successful user-initiated resume.
- Reboot with listening opted in: verify resume notification rather than an
  illegal microphone-service start. Repeat with notifications disabled.
- Test wake false triggers against music/conversation and multiple Lagos accents.
  Verify that the detector releases the mic, one command is captured, and closing
  the session resumes idle capture without double listeners.
- Test secure PIN/biometric lock: no message is typed/sent while locked; credentials
  are required before commands. Repeat with EMA not selected as system assistant.
- WhatsApp: check exact resolved contact, draft and one new bubble; include an old
  identical message, wrong-chat header, local-format phone number and app switch
  during composition. Unsupported/ambiguous accessibility evidence must stop, not
  guess. Local numbers without an international prefix use visible chat search.
- Spotify/YouTube: start another song first, request Arcade, and verify session
  package/title/state. Wrong track, paused state, missing notification permission,
  login/ads/paid playback restrictions must never be reported as confirmed play.

Added pure regression tests cover recipient/body evidence, transcript counting,
and requested-app/title playback evidence. Static Kotlin syntax/resource checks
are not a substitute for an Android build or these device acceptance checks.

Wake-triggered assistant sessions are one-shot: after the response finishes
speaking (or a short display period when TTS is unavailable), the session closes
and idle detection can resume. Manually opened conversation sessions retain their
existing microphone-mode behavior. If a command needs clarification, the current
one-shot response asks for it; invoke EMA again with the clarified full request.


### Hardware wake-word boundary

The stock Android APK does not claim a DSP integration. On Android 15's platform
source, `AlwaysOnHotwordDetector` and its `VoiceInteractionService` creation APIs
are hidden/system APIs, not normal third-party SDK APIs. Selecting the assistant
role does not manufacture access to a DSP or enroll a new manufacturer's sound
model. EMA does not use reflection, hidden-API exemptions, accessibility tricks,
or fake "hardware enabled" flags to conceal this boundary.

A genuine hardware path requires an OEM/system-image integration: a compatible
SoundTrigger HAL/DSP, an enrolled **Hey EMA** sound model in the format that
hardware accepts, a platform-approved hotword service, appropriate privileges,
and a verified hardware-trigger callback into EMA's existing activation flow.
Those pieces must be built/tested for the specific phone or firmware. A Vosk ZIP
is not a DSP sound model. That firmware work cannot be supplied by an ordinary
APK update or inferred from whether a microphone exists. Until such integration
is available, the built-in microphone/local-engine path is the honest portable
implementation; no external app or end-user model installation is required.
