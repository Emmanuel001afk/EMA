# EMA for Windows — laptop edition

EMA's **same assistant brain as the Android app**, running natively on Windows 10/11 x64.
The Android understanding layer (`EmaCommandGrammar`, `EmaNativeCommandPlanner`,
`EmaTimeParser`, `EmaSelection`, `EmaAppResolver`) is pure Kotlin with no Android
imports, so it is ported faithfully to Python in `ema_windows/brain.py` and reused
verbatim. Only the *execution* layer changes: Android APIs become Windows adapters
(Start apps, CoreAudio, WMI brightness, UI Automation, media keys, browser).

The Android app is unchanged. No AI endpoint or API key is used on either platform.

## Use the portable build (Python is not required)

1. Download **EMA-Windows-x64** from the latest successful **EMA Windows Build** workflow
   run in this repository. GitHub may require signing in to download Actions artifacts.
2. Extract the **entire ZIP** to a normal user-writable folder. Do not run inside the ZIP
   or move only `EMA.exe`; `_internal`, `worker`, notices and model files must stay beside it.
3. Run `EMA.exe` on **Windows 10/11 x64** in your normal unlocked desktop session.
   This preview is unsigned; only run an artifact you trust. Administrator access is not
   required or recommended.
4. Type a command and press **Send** (or Enter), or press **Listen offline**, review the
   transcript, then press **Send**. Try the example chips: `open Notepad`,
   `play Calm Down on Spotify`, `set a timer for 10 minutes`.
5. For voice, allow desktop microphone access under Windows Settings → Privacy & security
   → Microphone. Choose your microphone in the dropdown, then **Listen offline**.
6. For screen actions, press **Select window in 3 seconds**, switch to the target app, then
   use `inspect screen`, `click <exact label>` or `type <text>`. Click/type/send/call
   ask for confirmation first.

The English model is included, checksum-pinned to the same upstream archive as Android.
No runtime model download, account or internet connection is needed for recognition.
Audio is processed in memory and is not stored or uploaded. Transcripts stay in a
bounded in-memory view. Explicit web search and maps need internet. Optional
**Speak replies** uses installed Windows SAPI voices. Recognition is press-to-talk /
review-to-run, not always-listening or a wake word.

## What EMA understands (shared brain)

| Request | Windows behavior |
|---|---|
| `open Notepad`, `list apps`, `find app Spotify` | Start-menu discovery + launch (requested, not verified) |
| `play Calm Down on Spotify`, `pause`, `next track` | Opens the app for in-app search; media keys control the active media app |
| `search football on YouTube`, `search with google weather` | In-app search guidance, or browser search |
| `set volume to 40 percent`, `mute`, `volume status` | Default output via CoreAudio, read back |
| `set brightness to 60 percent` | Built-in displays exposing WMI brightness, read back |
| `set an alarm for 7 am`, `set a timer for 10 minutes`, `remind me to call mom at 6 pm` | Scheduled in EMA; fires while EMA is open (Windows has no EMA alarm service) |
| `save 08012345678 as Ada`, `call Ada` | Contacts stored locally; calling opens your default Windows calling app (e.g. Phone Link) |
| `message Ada on WhatsApp saying hello` | Opens the app; EMA will not press Send for you |
| `open bluetooth settings`, `turn on wifi` | Opens the Windows Settings page (no silent toggles) |
| `navigate to Lekki` | Opens Google Maps directions in your browser |
| `inspect screen`, `click Save`, `type Hello`, `scroll down` | UI Automation on the selected window only; exact labels, no coordinates, no password fields |
| `open Spotify and play Arcade` | Compound planner: open, then play-on-app (stops after unverified launch) |
| `message Ada saying I will land then call you` | Message body is never split into extra commands |

## Honest limits (Windows is not Android)

- **No phone/SMS**: calls open a default handler if installed; SMS is unavailable.
  `text Ada` explains this instead of pretending.
- **No silent Wi-Fi/Bluetooth/DND toggles**: Windows does not expose safe direct
  toggles to this app; EMA opens the Settings page and you complete pairing/consent.
- **No Android app automation**: WhatsApp/Spotify/YouTube workflows are generic —
  EMA opens the app and guides you; it does not claim a message was sent or music
  started playing.
- **Alarms/timers/reminders fire only while EMA is open.**
- **No always-listening wake word**, no background service, no installer/updater.
- **No "what is playing"**: playback *control* works via media keys; track
  identification is not implemented.
- Elevated/protected apps, UAC screens, custom-rendered controls and some browsers
  may be inaccessible to UI Automation. EMA does not bypass Windows security.
- Microphone accuracy, accents, background noise, brightness hardware and app
  compatibility require testing on your own laptop. CI smoke tests are not a
  substitute. This is a preview, not full Android parity.

## Safety

- Installed identities and exact visible labels are evidence, not shell commands.
  User text is never executed as PowerShell. Settings URIs are allowlisted; web
  queries are URL-encoded. Screen text never becomes an instruction.
- Each screen action rechecks the selected HWND, process ID and foreground
  ownership. Duplicate/partial labels, conflicting drafts and password fields are
  refused. No coordinate guessing or hidden hotkeys.
- Consequential actions (send, call, click, type) require confirmation. Workers are
  separate processes with hard time budgets. Cancellation invalidates old command
  generations; it cannot undo an action already dispatched. Timeout/uncertainty
  never automatically repeats a click, send or form submission.
- "Requested" means Windows was asked — EMA says so instead of claiming success.

## Run from source

Install **Python 3.11 x64** (the speech resampler uses Python 3.11's `audioop`).
In PowerShell from `desktop/windows`:

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python tools/prepare_model.py
python -m ema_windows
```

`prepare_model.py` needs build-time internet, verifies the pinned SHA-256, and
extracts only safe paths into generated `resources/model`. With an existing copy
of Android's pinned `vosk-model-small-en-us-0.15.zip`:

```powershell
python tools/prepare_model.py --archive C:\path\vosk-model-small-en-us-0.15.zip
```

## Build and test

```powershell
python -m unittest discover -s tests -v
python -m pip install pyinstaller==6.11.1
.\build.ps1
```

`build.ps1` creates `dist/EMA` (windowed app + separate console UIA worker), refuses
an incomplete distribution, and smoke-tests both frozen runtimes plus GUI startup.
The portable brain tests also run on Linux:

```sh
python3 -m unittest discover -s desktop/windows/tests -v
```

Windows-only microphone, audio/display APIs, interactive UI behavior, code signing
and device-specific acceptance testing remain distinct from portable unit tests.
