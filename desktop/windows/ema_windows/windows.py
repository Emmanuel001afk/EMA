"""Windows adapters. No user text is evaluated as PowerShell or shell code."""
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

from .core import HELP, SETTINGS, Intent, Result, Status


class Cancelled(Exception):
    pass


def run_process(args, alive=lambda: True, input_text=None, timeout=15):
    if not alive():
        raise Cancelled()
    process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, encoding="utf-8", errors="replace", creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
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
    # Only developer-defined static scripts reach this function. No command-string interpolation.
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


class WindowsAssistant:
    def __init__(self, speech_status=lambda: "Speech is not initialized."):
        if sys.platform != "win32":
            raise RuntimeError("This adapter requires Windows 10/11.")
        self.apps = []
        self.target = None
        self.speech_status = speech_status

    def discover(self, alive=lambda: True):
        output = powershell("@(Get-StartApps | Select-Object Name,AppID) | ConvertTo-Json -Compress", alive)
        data = json.loads(output or "[]")
        if isinstance(data, dict):
            data = [data]
        self.apps = sorted([a for a in data if a.get("Name") and a.get("AppID")], key=lambda a: a["Name"].casefold())
        return len(self.apps)

    def capture_target(self):
        hwnd, pid, title = foreground()
        if not hwnd or pid == os.getpid():
            return Result(Status.NEEDS_INPUT, "Switch to the application you want EMA to control, then select it again.")
        self.target = (hwnd, pid, title)
        return Result(Status.VERIFIED, f"Selected window: {title}. Screen commands stay scoped to this window and process.")

    def _uia(self, intent, alive):
        if not self.target:
            return Result(Status.NEEDS_INPUT, "Select a target window first using the 3-second window selector.")
        hwnd, pid, title = self.target
        # Explicitly selected window only. Never click through EMA's own command window.
        if not alive():
            raise Cancelled()
        ctypes.windll.user32.SetForegroundWindow(ctypes.c_void_p(hwnd))
        if foreground()[:2] != (hwnd, pid):
            return Result(Status.BLOCKED, "Windows did not foreground the selected target. Switch to it and retry; nothing was clicked or typed.")
        command = [str(Path(sys.executable).parent / "worker/EMA-worker.exe")] if getattr(sys, "frozen", False) else [sys.executable, "-m", "ema_windows", "--uia-worker"]
        request = {"hwnd": hwnd, "pid": pid, "operation": intent.kind, "target": intent.target}
        try:
            result = json.loads(run_process(command, alive, json.dumps(request), timeout=14))
            return Result(Status(result["status"]), result["message"])
        except TimeoutError:
            return Result(Status.REQUESTED, "UI observation timed out and its worker was stopped. An action may have been accepted; inspect the target before retrying.")

    def _volume(self, intent, alive):
        import comtypes
        from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
        from comtypes import CLSCTX_ALL
        comtypes.CoInitialize()
        try:
            device = AudioUtilities.GetSpeakers()
            endpoint = ctypes.cast(device.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None), ctypes.POINTER(IAudioEndpointVolume))
            if not alive():
                raise Cancelled()
            if intent.kind == "volume":
                endpoint.SetMasterVolumeLevelScalar(intent.value / 100, None)
                verified = abs(endpoint.GetMasterVolumeLevelScalar() * 100 - intent.value) <= 1
                return Result(Status.VERIFIED if verified else Status.REQUESTED, f"Windows reports volume {round(endpoint.GetMasterVolumeLevelScalar() * 100)} percent.")
            if intent.kind in {"mute", "unmute"}:
                wanted = intent.kind == "mute"
                endpoint.SetMute(wanted, None)
                verified = bool(endpoint.GetMute()) == wanted
                return Result(Status.VERIFIED if verified else Status.REQUESTED, f"Windows reports mute {'on' if endpoint.GetMute() else 'off'}.")
            return Result(Status.VERIFIED, f"Default output volume is {round(endpoint.GetMasterVolumeLevelScalar() * 100)} percent; mute is {'on' if endpoint.GetMute() else 'off'}.")
        finally:
            comtypes.CoUninitialize()

    def execute(self, intent: Intent, alive=lambda: True):
        if not alive():
            return Result(Status.CANCELLED, "Cancelled before dispatch.")
        try:
            if intent.kind == "help":
                return Result(Status.VERIFIED, HELP)
            if intent.kind == "speech_status":
                return Result(Status.VERIFIED, self.speech_status())
            if intent.kind == "cancel":
                return Result(Status.CANCELLED, "Cancelled. Earlier actions are not undone.")
            if intent.kind == "clarify":
                return Result(Status.NEEDS_INPUT, intent.target)
            if intent.kind in {"apps", "find_app", "open"}:
                if not self.apps:
                    self.discover(alive)
                if intent.kind == "apps":
                    return Result(Status.VERIFIED, "\n".join(a["Name"] for a in self.apps) or "No Start apps were found.")
                key = intent.target.casefold()
                exact = [a for a in self.apps if a["Name"].casefold() == key]
                matches = exact or [a for a in self.apps if key in a["Name"].casefold()]
                if intent.kind == "find_app":
                    return Result(Status.VERIFIED, "\n".join(a["Name"] for a in matches) or "No installed Start app matches.")
                if len(matches) != 1:
                    return Result(Status.NEEDS_INPUT, "Specify one installed app: " + (", ".join(a["Name"] for a in matches[:12]) or "no matches"))
                if not alive():
                    raise Cancelled()
                app = matches[0]
                os.startfile("shell:AppsFolder\\" + app["AppID"])
                return Result(Status.REQUESTED, f"Windows was asked to open {app['Name']}. This does not verify launch or an in-app task. Select its window before screen commands.")
            if intent.kind in {"settings", "guided_setting"}:
                feature = SETTINGS.get(intent.target.casefold())
                if not feature:
                    return Result(Status.UNSUPPORTED, "I don't have a verified Settings URI for that feature.")
                if not alive():
                    raise Cancelled()
                os.startfile(feature[1])
                return Result(Status.REQUESTED, f"Requested {feature[0]} settings. I have not changed its state. Some pages differ by Windows version; complete pairing, passwords and consent yourself.")
            if intent.kind in {"volume", "volume_status", "mute", "unmute"}:
                return self._volume(intent, alive)
            if intent.kind in {"brightness", "brightness_status"}:
                if intent.kind == "brightness":
                    # Value is an integer validated by the parser, not arbitrary user code.
                    if not isinstance(intent.value, int) or not 0 <= intent.value <= 100:
                        return Result(Status.BLOCKED, "Invalid brightness percentage.")
                    script = "$m=@(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods); if($m.Count -ne 1){throw 'Ambiguous or unavailable display'}; $m[0] | Invoke-CimMethod -MethodName WmiSetBrightness -Arguments @{Timeout=[uint32]0;Brightness=[byte]" + str(intent.value) + "} | Out-Null; "
                else:
                    script = ""
                values = json.loads(powershell(script + "@(Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightness | Select-Object -ExpandProperty CurrentBrightness) | ConvertTo-Json -Compress", alive))
                if not isinstance(values, list):
                    values = [values]
                verified = intent.kind == "brightness_status" or (len(values) == 1 and abs(int(values[0]) - intent.value) <= 1)
                return Result(Status.VERIFIED if verified else Status.REQUESTED, f"Windows reports display brightness: {values} percent. External monitors may not expose this control.")
            if intent.kind in {"inspect", "click", "type", "scroll"}:
                return self._uia(intent, alive)
            if intent.kind == "web":
                if not alive():
                    raise Cancelled()
                opened = webbrowser.open("https://www.google.com/search?q=" + quote_plus(intent.target))
                return Result(Status.REQUESTED if opened else Status.FAILED, "Web search was requested in your browser; internet is required. Results have not been inspected.")
            return Result(Status.UNSUPPORTED, intent.target or "This action is not implemented on Windows.")
        except Cancelled:
            return Result(Status.CANCELLED, "Cancelled. An already-dispatched action cannot be undone; check the target.")
        except Exception as exc:
            return Result(Status.FAILED, f"Windows could not verify this operation ({type(exc).__name__}). It will not be repeated automatically. Check the device state before retrying.")
