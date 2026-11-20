# EMA for Windows — desktop preview

A separate native Windows desktop assistant, not an Android APK running in an emulator.
The Android app is unchanged. This edition uses a Python/Tk desktop shell, Vosk/PortAudio
for local speech and Windows APIs/UI Automation for desktop operations. It ports the
explicit-goal, observed-evidence and no-blind-retry design; it does **not** yet share the
Kotlin parser or all Android workflows. No AI endpoint or API key is used.

## Use the portable build (Python is not required)

1. Download **EMA-Windows-x64** from the latest successful **EMA Windows Build** workflow
   run in this repository. GitHub may require signing in to download Actions artifacts.
2. Extract the **entire ZIP** to a normal user-writable folder. Do not run inside the ZIP
   or move only `EMA.exe`; `_internal`, `worker`, notices and model files must stay beside it.
3. Run `EMA.exe` on **Windows 10/11 x64** in your normal unlocked desktop session.
   This preview is unsigned; only run an artifact you trust. Administrator access is not
   required or recommended. Antivirus/SmartScreen policies may block unsigned builds;
   do not disable your security software to work around this.
4. Type `help`, or press **Capabilities & limits**. Start with `list apps`,
   `open Notepad`, `volume status`, or `open bluetooth settings`.
5. For voice, allow desktop microphone access under Windows Settings → Privacy & security
   → Microphone. Press **Refresh inputs** and choose the microphone, then **Listen offline**.
   Review the transcript and press **Run reviewed command** (or Ctrl+Enter).
6. For screen actions, press **Select window in 3 seconds**, then switch to the desired app.
   Use `inspect screen` to see exposed control labels. `click <exact label>` and `type <text>`
   require explicit confirmation. EMA attempts to return focus to that selected window;
   if Windows refuses, it reports a block rather than typing into EMA or another app.

The English model is included in the portable build, checksum-pinned to the same upstream
archive as Android. No runtime model download, account or internet connection is needed
for recognition. Audio is processed in memory and is not stored or uploaded. Transcripts
and results are kept in a bounded in-memory view only. Explicit web search opens a browser
and does require internet. Optional **Speak replies locally** uses installed Windows SAPI
voices. Recognition is press-to-talk/review-to-run, not always-listening or a wake word.

## What is implemented versus Android

| Capability | Windows preview |
|---|---|
| Typed commands and local voice transcription | Implemented; bundled English model, selectable microphone, long-dictation mode |
| Spoken replies | Optional local Windows SAPI voice |
| Installed app discovery | Start-menu app identities from `Get-StartApps`; exact match preferred, ambiguous names ask |
| App launch | Requests the selected installed app; reports unverified rather than pretending an in-app task finished |
| Volume/mute | Default Windows output via CoreAudio, with read-back |
| Brightness | Built-in displays exposing WMI brightness, with read-back; external monitors may be unsupported |
| Settings discovery/navigation | Allowlisted Windows Settings URIs including Wi-Fi, Bluetooth, notifications/DND, privacy, display, sound and others |
| Wi-Fi/Bluetooth/DND toggles, pairing, hotspot | Guided Settings access only; **not automatic state changes or verified connections** |
| Screen inspection/control | UI Automation on an explicitly selected foreground window/process; exact labels, no coordinates, no password fields |
| Typing | One readable editable field, no overwriting different drafts, exact value read-back |
| Click/scroll verification | Observe the selected toggle or stable control-tree change; event acceptance alone is unverified |
| Multi-step requests | Explicit `then`, maximum 8 steps; stops on unverified prerequisites or requests for clarification |
| Phone calls, SMS, Android notifications | Not available as Android integrations on Windows |
| WhatsApp/Spotify/YouTube task adapters | Android-specific workflows are not ported; desktop controls are generic and app-dependent |
| Always-listening wake words, startup background service | Not implemented |
| General conversation/translation/human-like reasoning | Not provided by this bounded offline engine |

An example supported plan is `set volume to 30 percent then brightness status`.
`type I will arrive then call you` is one text payload, never an extra call request.
`open Notepad then type hello` stops after the unverified launch request: select the app's
window and issue `type hello` separately. EMA must not infer that a launched app is ready.

## Safety and limitations

- Installed identities and exact visible labels are evidence, not shell commands. User text
  is never executed as PowerShell or a command line. Settings URIs are allowlisted; web
  queries are URL-encoded. Screen text never becomes an instruction to execute.
- Each screen action rechecks the selected HWND, process ID and foreground ownership. A
  fresh control tree must still match before dispatch. Duplicate/partial labels, conflicting
  drafts and password fields are refused. No coordinate guessing or hidden hotkeys are used.
- UI workers are separate processes with hard time budgets. Cancellation invalidates old
  command generations and stops active workers; it cannot undo an action already dispatched.
  Timeout/uncertainty never automatically repeats a click, send or form submission.
- Successful generic activation proves only an observable effect, **not** delivery,
  payment, connection or another high-level task. Read and understand confirmations.
- Protected desktops, UAC/elevated applications, inaccessible/custom-rendered controls,
  browser accessibility settings and OEM hardware can prevent control. EMA does not bypass
  Windows security. Hardware accuracy, accents, background noise and app compatibility
  still require testing on your own laptop. CI smoke tests are not a substitute for this.
- This is a Windows **preview**, not full Android parity. Review the workflow checks and
  capability table instead of assuming all Android actions will work.

## Run from source

Install **Python 3.11 x64** (other versions are not supported by this preview; the speech
resampler uses Python 3.11's `audioop`). In PowerShell from `desktop/windows`:

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python tools/prepare_model.py
python -m ema_windows
```

`prepare_model.py` needs build-time internet, verifies the pinned SHA-256, and extracts only
safe paths into generated `resources/model`. To prepare without network using an existing
copy of Android's pinned `vosk-model-small-en-us-0.15.zip`:

```powershell
python tools/prepare_model.py --archive C:\path\vosk-model-small-en-us-0.15.zip
```

## Build and test

```powershell
python -m unittest discover -s tests -v
python -m pip install pyinstaller==6.11.1
.\build.ps1
```

`build.ps1` creates `dist/EMA`, with a windowed main app and a separate hidden-console UIA
worker. It refuses a missing model/license/worker distribution and smoke-tests both frozen
runtimes, including loading the packaged Vosk model. License notices and installed package
metadata are preserved in the portable folder. Build outputs, virtual environments and the
model are generated assets, not Git source.

The portable core and tests also run on Linux without Windows dependencies:

```sh
python3 -m unittest discover -s desktop/windows/tests -v
```

Windows-only microphone, audio/display APIs, actual interactive UI behavior, code signing,
installer/updater and device-specific acceptance testing remain distinct from portable
unit tests. There is no installer, auto-update service or silent system modification.
