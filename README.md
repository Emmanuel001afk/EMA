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
decided from two independent signals: Android's audio state
(`AudioManager.isMusicActive`) and the visible transport UI. A bare "Pause"
control is *not* treated as proof of playback — every media app draws one while
stopped.

**Sending a message.** EMA opens the conversation (deep link where the app
supports one: `wa.me`, `mailto:`, `smsto:`), types the body, then finds and
presses the real Send control. A body already present in the composer is never
typed twice. The send is confirmed by checking that the message text moved out
of the composer and into the transcript, and short bodies additionally require
the composer to be empty while still focused.

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

---

## Repository scope

Native Android only. **Projects Continuity** and **Central Hub** are separate
repositories and are not part of this one.

Public foundations reviewed: Orbit Assistant, DeVA, OpenDroid. Their provider
layers were not copied into this project.
