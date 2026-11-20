"""Windows adapters: execute brain commands with Windows APIs.

No user text is evaluated as PowerShell or shell code. PowerShell scripts are
static, developer-defined strings; user values travel as validated integers or
URL-encoded data.
"""
import base64
import ctypes
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from urllib.parse import quote_plus
import webbrowser

from . import brain
from .core import HELP, Result, Status


class Cancelled(Exception):
    pass


def run_process(args, alive=lambda: True, input_text=None, timeout=15):
    if not alive():
        raise Cancelled()
    process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, encoding="utf-8", errors="replace",
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    deadline = time.monotonic() + timeout
    try:
        first = True
        while True:
            try:
                output, error = process.communicate(input=input_text if first else None, timeout=.15)
                if process.returncode:
                    raise RuntimeError("Windows could not complete this operation.")
                return output
            except subprocess.TimeoutExpired:
                first = False
                if not alive():
                    raise Cancelled()
                if time.monotonic() >= deadline:
                    raise TimeoutError("The Windows operation exceeded its time budget. Check its state before retrying.")
    finally:
        if process.poll() is None:
            process.kill()
            process.communicate()


def powershell(script, alive=lambda: True):
    # Only developer-defined static scripts reach this function.
    encoded = base64.b64encode(("[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(); $ErrorActionPreference='Stop'; " + script).encode("utf-16-le")).decode("ascii")
    executable = str(Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32/WindowsPowerShell/v1.0/powershell.exe")
    return run_process([executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded], alive)


def foreground():
    user = ctypes.windll.user32
    user.GetForegroundWindow.restype = ctypes.c_void_p
    hwnd = user.GetForegroundWindow()
    pid = ctypes.c_ulong()
    user.GetWindowThreadProcessId(ctypes.c_void_p(hwnd), ctypes.byref(pid))
    buffer = ctypes.create_unicode_buffer(1024)
    user.GetWindowTextW(ctypes.c_void_p(hwnd), buffer, len(buffer))
    return int(hwnd or 0), pid.value, buffer.value


def _media_key(vk: int):
    """Send a Windows media key (global, handled by the active media app)."""
    KEYEVENTF_KEYUP = 0x0002
    user = ctypes.windll.user32
    user.keybd_event(vk, 0, 0, 0)
    user.keybd_event(vk, 0, KEYEVENTF_KEYUP, 0)


_MEDIA_KEYS = {"TOGGLE": 0xB3, "PLAY": 0xB3, "PAUSE": 0xB3, "RESUME": 0xB3,
               "STOP": 0xB2, "NEXT": 0xB0, "PREVIOUS": 0xB1}


class WindowsAssistant:
    def __init__(self, speech_status=lambda: "Speech is not initialized."):
        if sys.platform != "win32":
            raise RuntimeError("This adapter requires Windows 10/11.")
        self.apps = []
        self.target = None
        self.speech_status = speech_status
        self.scheduled = []  # list of dicts: {when_ms, text, kind}
        self.contacts = self._load_contacts()

    # ------------------------------------------------------------ persistence

    def _contacts_path(self) -> Path:
        base = Path(os.environ.get("LOCALAPPDATA", str(Path.home())))
        return base / "EMA" / "contacts.json"

    def _load_contacts(self):
        try:
            return json.loads(self._contacts_path().read_text(encoding="utf-8"))
        except Exception:
            return {}

    def _save_contacts(self):
        path = self._contacts_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.contacts, indent=1), encoding="utf-8")

    def find_contact(self, name: str):
        key = name.strip().casefold()
        for saved, number in self.contacts.items():
            if saved.casefold() == key:
                return saved, number
        return None

    # -------------------------------------------------------------- scheduling

    def schedule(self, when_ms: int, text: str, kind: str):
        self.scheduled.append({"when_ms": when_ms, "text": text, "kind": kind})
        self.scheduled.sort(key=lambda s: s["when_ms"])

    def due(self):
        now = int(time.time() * 1000)
        fired = [s for s in self.scheduled if s["when_ms"] <= now]
        self.scheduled = [s for s in self.scheduled if s["when_ms"] > now]
        return fired

    # ------------------------------------------------------------------- apps

    def discover(self, alive=lambda: True):
        output = powershell("@(Get-StartApps | Select-Object Name,AppID) | ConvertTo-Json -Compress", alive)
        data = json.loads(output or "[]")
        if isinstance(data, dict):
            data = [data]
        self.apps = sorted([a for a in data if a.get("Name") and a.get("AppID")],
                           key=lambda a: a["Name"].casefold())
        return len(self.apps)

    def _resolve(self, spoken: str, alive):
        if not self.apps:
            self.discover(alive)
        installed = [brain.InstalledApp(a["Name"], a["AppID"]) for a in self.apps]
        match = brain.resolve_app(spoken, installed)
        return match

    def _launch(self, app, alive):
        if not alive():
            raise Cancelled()
        os.startfile("shell:AppsFolder\\" + app.app_id)
        return Result(Status.REQUESTED,
                      f"Windows was asked to open {app.label}. This does not verify launch "
                      "or an in-app task. Select its window before screen commands.")

    def capture_target(self):
        hwnd, pid, title = foreground()
        if not hwnd or pid == os.getpid():
            return Result(Status.NEEDS_INPUT, "Switch to the application you want EMA to control, then select it again.")
        self.target = (hwnd, pid, title)
        return Result(Status.VERIFIED, f"Selected window: {title}. Screen commands stay scoped to this window and process.")

    # --------------------------------------------------------------------- UIA

    def _uia(self, operation, target, alive):
        if not self.target:
            return Result(Status.NEEDS_INPUT, "Select a target window first using the 3-second window selector.")
        hwnd, pid, title = self.target
        if not alive():
            raise Cancelled()
        ctypes.windll.user32.SetForegroundWindow(ctypes.c_void_p(hwnd))
        if foreground()[:2] != (hwnd, pid):
            return Result(Status.BLOCKED, "Windows did not foreground the selected target. Switch to it and retry; nothing was clicked or typed.")
        command = [str(Path(sys.executable).parent / "worker/EMA-worker.exe")] if getattr(sys, "frozen", False) \
            else [sys.executable, "-m", "ema_windows", "--uia-worker"]
        request = {"hwnd": hwnd, "pid": pid, "operation": operation, "target": target}
        try:
            result = json.loads(run_process(command, alive, json.dumps(request), timeout=14))
            return Result(Status(result["status"]), result["message"])
        except TimeoutError:
            return Result(Status.REQUESTED, "UI observation timed out and its worker was stopped. An action may have been accepted; inspect the target before retrying.")

    def _inspect_controls(self, alive):
        """Return (Result, controls) — controls is a list of dicts from the worker."""
        if not self.target:
            return Result(Status.NEEDS_INPUT, "Select a target window first."), []
        hwnd, pid, title = self.target
        if not alive():
            raise Cancelled()
        ctypes.windll.user32.SetForegroundWindow(ctypes.c_void_p(hwnd))
        if foreground()[:2] != (hwnd, pid):
            return Result(Status.BLOCKED, "Windows did not foreground the selected target."), []
        command = [str(Path(sys.executable).parent / "worker/EMA-worker.exe")] if getattr(sys, "frozen", False) \
            else [sys.executable, "-m", "ema_windows", "--uia-worker"]
        request = {"hwnd": hwnd, "pid": pid, "operation": "inspect", "target": ""}
        try:
            result = json.loads(run_process(command, alive, json.dumps(request), timeout=14))
            return Result(Status(result["status"]), result["message"]), result.get("controls", [])
        except TimeoutError:
            return Result(Status.REQUESTED, "UI observation timed out."), []

    # ------------------------------------------------------------------- audio

    def _volume(self, command, alive):
        import comtypes
        from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
        from comtypes import CLSCTX_ALL
        comtypes.CoInitialize()
        try:
            device = AudioUtilities.GetSpeakers()
            endpoint = ctypes.cast(device.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None),
                                   ctypes.POINTER(IAudioEndpointVolume))
            if not alive():
                raise Cancelled()
            if isinstance(command, brain.SetVolume):
                endpoint.SetMasterVolumeLevelScalar(command.value / 100, None)
                verified = abs(endpoint.GetMasterVolumeLevelScalar() * 100 - command.value) <= 1
                return Result(Status.VERIFIED if verified else Status.REQUESTED,
                              f"Windows reports volume {round(endpoint.GetMasterVolumeLevelScalar() * 100)} percent.")
            if isinstance(command, brain.SetMute):
                endpoint.SetMute(command.muted, None)
                verified = bool(endpoint.GetMute()) == command.muted
                return Result(Status.VERIFIED if verified else Status.REQUESTED,
                              f"Windows reports mute {'on' if endpoint.GetMute() else 'off'}.")
            return Result(Status.VERIFIED,
                          f"Default output volume is {round(endpoint.GetMasterVolumeLevelScalar() * 100)} percent; "
                          f"mute is {'on' if endpoint.GetMute() else 'off'}.")
        finally:
            comtypes.CoUninitialize()

    def _brightness(self, command, alive):
        if isinstance(command, brain.SetBrightness):
            if not isinstance(command.value, int) or not 0 <= command.value <= 100:
                return Result(Status.BLOCKED, "Invalid brightness percentage.")
            script = ("$m=@(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods); "
                      "if($m.Count -ne 1){throw 'Ambiguous or unavailable display'}; "
                      "$m[0] | Invoke-CimMethod -MethodName WmiSetBrightness -Arguments "
                      "@{Timeout=[uint32]0;Brightness=[byte]" + str(command.value) + "} | Out-Null; ")
        else:
            script = ""
        values = json.loads(powershell(script + "@(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightness | Select-Object -ExpandProperty CurrentBrightness) | ConvertTo-Json -Compress", alive))
        if not isinstance(values, list):
            values = [values]
        verified = isinstance(command, brain.BrightnessStatus) or (len(values) == 1 and abs(int(values[0]) - command.value) <= 1)
        return Result(Status.VERIFIED if verified else Status.REQUESTED,
                      f"Windows reports display brightness: {values} percent. External monitors may not expose this control.")

    # ----------------------------------------------------------------- execute

    def execute(self, command, alive=lambda: True):
        if not alive():
            return Result(Status.CANCELLED, "Cancelled before dispatch.")
        try:
            return self._execute(command, alive)
        except Cancelled:
            return Result(Status.CANCELLED, "Cancelled. An already-dispatched action cannot be undone; check the target.")
        except Exception as exc:
            requested_kinds = (brain.OpenApp, brain.OpenAppSection, brain.OpenSettingsPage,
                               brain.ClickControl, brain.TypeText, brain.Scroll, brain.SetVolume,
                               brain.SetBrightness, brain.SetMute, brain.WebSearch, brain.NavigateTo,
                               brain.CallContact, brain.MediaControl)
            status = Status.REQUESTED if isinstance(command, requested_kinds) else Status.FAILED
            return Result(status, f"Windows could not verify this operation ({type(exc).__name__}). "
                                  "It will not be repeated automatically. Check the device state before retrying.")

    def _execute(self, command, alive):
        if isinstance(command, brain.Help):
            return Result(Status.VERIFIED, HELP)
        if isinstance(command, brain.SpeechStatus):
            return Result(Status.VERIFIED, self.speech_status())
        if isinstance(command, brain.Cancel):
            return Result(Status.CANCELLED, "Cancelled. Earlier actions are not undone.")
        if isinstance(command, brain.ListApps):
            if not self.apps:
                self.discover(alive)
            return Result(Status.VERIFIED, "\n".join(a["Name"] for a in self.apps) or "No Start apps were found.")
        if isinstance(command, brain.FindApp):
            match = self._resolve(command.name, alive)
            if match and match.confident:
                return Result(Status.VERIFIED, f"{match.app.label} (matched via {match.via})")
            if not self.apps:
                self.discover(alive)
            key = "".join(c for c in command.name.casefold() if c.isalnum())
            names = [a["Name"] for a in self.apps if key in "".join(c for c in a["Name"].casefold() if c.isalnum())]
            return Result(Status.VERIFIED, "\n".join(names[:20]) or "No installed Start app matches.")
        if isinstance(command, brain.OpenApp):
            match = self._resolve(command.app, alive)
            if not match:
                return Result(Status.NEEDS_INPUT, f"I could not find an installed app matching '{command.app}'. Try 'list apps'.")
            if not match.confident:
                return Result(Status.NEEDS_INPUT, f"'{command.app}' is ambiguous. Closest matches: {match.app.label}. Be more specific.")
            return self._launch(match.app, alive)
        if isinstance(command, brain.OpenAppSection):
            match = self._resolve(command.app, alive)
            if not match or not match.confident:
                return Result(Status.NEEDS_INPUT, f"I could not confidently find '{command.app}'. Try 'open {command.app}' first.")
            launched = self._launch(match.app, alive)
            if launched.status != Status.REQUESTED:
                return launched
            return Result(Status.REQUESTED,
                          f"Opened {match.app.label} (requested). Select its window, then say 'click {command.section}' "
                          "to go to that section — EMA will not guess inside the app.")
        if isinstance(command, brain.OpenSettingsPage):
            feature = brain._WINDOWS_SETTINGS.get(command.target.casefold())
            if not feature:
                if not command.target:
                    os.startfile("ms-settings:")
                    return Result(Status.REQUESTED, "Requested the Settings app. Nothing was changed.")
                return Result(Status.UNSUPPORTED, f"I don't have a verified Settings page for '{command.target}'. "
                                                  "Try: wifi, bluetooth, display, sound, privacy, notifications, updates.")
            if not alive():
                raise Cancelled()
            os.startfile(feature[1])
            return Result(Status.REQUESTED, f"Requested {feature[0]} settings. I have not changed its state. "
                                            "Some pages differ by Windows version; complete pairing, passwords and consent yourself.")
        if isinstance(command, (brain.SetVolume, brain.SetMute, brain.VolumeStatus)):
            return self._volume(command, alive)
        if isinstance(command, (brain.SetBrightness, brain.BrightnessStatus)):
            return self._brightness(command, alive)
        if isinstance(command, brain.InspectScreen):
            return self._uia("inspect", "", alive)
        if isinstance(command, brain.ClickControl):
            return self._uia("click", command.label, alive)
        if isinstance(command, brain.TypeText):
            return self._uia("type", command.text, alive)
        if isinstance(command, brain.Scroll):
            return self._uia("scroll", command.direction, alive)
        if isinstance(command, brain.WebSearch):
            if not alive():
                raise Cancelled()
            opened = webbrowser.open("https://www.google.com/search?q=" + quote_plus(command.query))
            return Result(Status.REQUESTED if opened else Status.FAILED,
                          "Web search was requested in your browser; internet is required. Results have not been inspected.")
        if isinstance(command, brain.NavigateTo):
            if not alive():
                raise Cancelled()
            opened = webbrowser.open("https://www.google.com/maps/dir/?api=1&destination=" + quote_plus(command.destination))
            return Result(Status.REQUESTED if opened else Status.FAILED,
                          f"Directions to {command.destination} were requested in your browser. "
                          "EMA cannot drive navigation or verify arrival.")
        if isinstance(command, brain.SetAlarm):
            moment = brain.parse_time(command.time_text)
            if not moment:
                return Result(Status.NEEDS_INPUT, "I could not understand that alarm time.")
            self.schedule(moment.millis, "Alarm", "alarm")
            return Result(Status.VERIFIED, f"Alarm set for {brain.describe_time(moment.millis)}. "
                                           "It fires while EMA is open; Windows has no EMA alarm service.")
        if isinstance(command, brain.SetTimer):
            duration = brain.parse_duration(command.duration_text)
            if not duration:
                return Result(Status.NEEDS_INPUT, "I could not understand that timer duration.")
            when = int(time.time() * 1000) + duration.seconds * 1000
            self.schedule(when, "Timer finished", "timer")
            return Result(Status.VERIFIED, f"Timer set for {duration.seconds} seconds. It fires while EMA is open.")
        if isinstance(command, brain.SetReminder):
            if command.time_text:
                moment = brain.parse_time(command.time_text)
                if not moment:
                    return Result(Status.NEEDS_INPUT, "I could not understand that reminder time.")
                when = moment.millis
                when_text = brain.describe_time(when)
            else:
                when = int(time.time() * 1000) + 60 * 1000
                when_text = "in 1 minute (no time given)"
            self.schedule(when, command.text, "reminder")
            return Result(Status.VERIFIED, f"Reminder set: '{command.text}' at {when_text}. "
                                           "It fires while EMA is open.")
        if isinstance(command, brain.SaveContact):
            self.contacts[command.name] = command.number
            self._save_contacts()
            return Result(Status.VERIFIED, f"Saved {command.name} as {command.number} on this laptop.")
        if isinstance(command, brain.CallContact):
            found = self.find_contact(command.name)
            number = found[1] if found else None
            if not number:
                return Result(Status.NEEDS_INPUT,
                              f"I don't have a number for '{command.name}'. Save one first: "
                              f"'save <number> as {command.name}'. Windows has no phone dialer of its own — "
                              "calling opens your default handler (e.g. Phone Link) if one is installed.")
            if not alive():
                raise Cancelled()
            opened = webbrowser.open("tel:" + quote_plus(number))
            return Result(Status.REQUESTED if opened else Status.FAILED,
                          f"Requested a call to {command.name} ({number}) via your default Windows calling app. "
                          "EMA cannot verify the call was placed.")
        if isinstance(command, brain.OpenSmsComposer):
            return Result(Status.UNSUPPORTED,
                          "Windows has no SMS. EMA cannot text from this laptop. "
                          "Use a messaging app instead: 'message <name> on WhatsApp saying <text>'.")
        if isinstance(command, brain.MediaControl):
            if command.action == "WHATS_PLAYING":
                return Result(Status.UNSUPPORTED,
                              "Reading the current track needs Windows media-session access that this build "
                              "does not implement. Playback controls work; track identification does not.")
            if not alive():
                raise Cancelled()
            _media_key(_MEDIA_KEYS[command.action])
            return Result(Status.REQUESTED,
                          f"Sent the {command.action.lower()} media key to the active media app. "
                          "Windows does not confirm playback state to EMA, so this is requested, not verified.")
        if isinstance(command, brain.PlayMedia):
            return self._play_media(command, alive)
        if isinstance(command, brain.SearchInApp):
            return self._search_in_app(command, alive)
        if isinstance(command, brain.SendMessage):
            return self._send_message(command, alive)
        if isinstance(command, brain.SelectResult):
            return self._select_result(command, alive)
        return Result(Status.UNSUPPORTED, f"Unsupported command: {command.canonical()}")

    # ------------------------------------------------------- app-directed flows

    def _open_app_for(self, app_name, alive):
        """Resolve + launch an app. Returns (Result, match)."""
        match = self._resolve(app_name, alive)
        if not match:
            return Result(Status.NEEDS_INPUT, f"I could not find an installed app matching '{app_name}'."), None
        if not match.confident:
            return Result(Status.NEEDS_INPUT, f"'{app_name}' is ambiguous (closest: {match.app.label})."), None
        return self._launch(match.app, alive), match

    def _play_media(self, command, alive):
        if command.app:
            launched, match = self._open_app_for(command.app, alive)
            if not match:
                return launched
            return Result(Status.REQUESTED,
                          f"Opened {match.app.label} (requested). Select its window, then say "
                          f"'type {command.query}' and 'click <first result>' — EMA will not guess "
                          "inside the app or claim playback started.")
        opened = webbrowser.open("https://www.youtube.com/results?search_query=" + quote_plus(command.query))
        return Result(Status.REQUESTED if opened else Status.FAILED,
                      f"Opened YouTube search for '{command.query}' in your browser. "
                      "EMA cannot verify playback started.")

    def _search_in_app(self, command, alive):
        if command.app:
            launched, match = self._open_app_for(command.app, alive)
            if not match:
                return launched
            return Result(Status.REQUESTED,
                          f"Opened {match.app.label} (requested). Select its window, then say "
                          f"'type {command.query}' to search. EMA will not guess inside the app.")
        opened = webbrowser.open("https://www.google.com/search?q=" + quote_plus(command.query))
        return Result(Status.REQUESTED if opened else Status.FAILED,
                      f"Opened a web search for '{command.query}'. Results have not been inspected.")

    def _send_message(self, command, alive):
        if not command.app:
            return Result(Status.NEEDS_INPUT,
                          f"Which app should I message {command.recipient} on? "
                          "For example: 'message Ada on WhatsApp saying hello'.")
        if not command.body:
            return Result(Status.NEEDS_INPUT, "What should the message say? Nothing was sent.")
        launched, match = self._open_app_for(command.app, alive)
        if not match:
            return launched
        return Result(Status.REQUESTED,
                      f"Opened {match.app.label} (requested). To send: select its window, search for "
                      f"'{command.recipient}', then type and send the message yourself — EMA will not "
                      "press Send for you. Nothing has been sent yet.")

    def _select_result(self, command, alive):
        result, controls = self._inspect_controls(alive)
        if result.status != Status.VERIFIED:
            return result
        items = [c for c in controls if c.get("kind") in {"ListItem", "Button", "Hyperlink", "TreeItem"}
                 and c.get("enabled", True) and not c.get("password")]
        selection = command.selection
        chosen = None
        if selection.kind == "ORDINAL" and 1 <= selection.position <= len(items):
            chosen = items[selection.position - 1]
        elif selection.kind == "LAST" and items:
            chosen = items[-1]
        elif selection.kind == "TITLED":
            chosen = next((c for c in items if selection.text.casefold() in (c.get("name") or "").casefold()), None)
        if chosen is None:
            return Result(Status.NEEDS_INPUT,
                          f"I could not resolve '{selection.phrase()}' among {len(items)} visible items. "
                          "Use 'inspect screen' to see what EMA can actually see.")
        return self._uia("click", chosen["name"], alive)
