"""Windows desktop shell. All Tk access stays on the UI thread."""
import os
import queue
import sys
import threading
import tkinter as tk
from tkinter import ttk, messagebox

from .core import HELP, Session, plan, run_plan
from .speech import OfflineSpeech
from .windows import WindowsAssistant, powershell


class App:
    def __init__(self, root):
        self.root = root
        self.events = queue.Queue()
        self.session = Session()
        self.execution_lock = threading.Lock()
        self.speech = OfflineSpeech()
        self.backend = WindowsAssistant(self.speech.status)
        self.listening = False
        self.devices = [None]
        root.title("EMA • Windows")
        root.geometry("940x740")
        root.minsize(760, 580)
        root.configure(background="#101823")
        style = ttk.Style(root)
        style.theme_use("clam")
        style.configure("TFrame", background="#101823")
        style.configure("TLabel", background="#101823", foreground="#e4edf4", font=("Segoe UI", 10))
        style.configure("Title.TLabel", font=("Segoe UI Semibold", 26))
        style.configure("TButton", font=("Segoe UI", 10), padding=9)
        shell = ttk.Frame(root, padding=24)
        shell.pack(fill="both", expand=True)
        ttk.Label(shell, text="EMA", style="Title.TLabel").pack(anchor="w")
        ttk.Label(shell, text="WINDOWS EDITION  /  LOCAL VOICE & GROUNDED ACTIONS").pack(anchor="w", pady=(0, 18))
        self.status = tk.StringVar(value="Ready for typed commands. Voice stays off until you press Listen.")
        ttk.Label(shell, textvariable=self.status, wraplength=860).pack(anchor="w", pady=(0, 10))
        bar = ttk.Frame(shell)
        bar.pack(fill="x")
        ttk.Button(bar, text="Listen offline", command=self.listen).pack(side="left")
        ttk.Button(bar, text="Stop listening", command=self.speech.stop.set).pack(side="left", padx=7)
        ttk.Button(bar, text="Cancel task", command=self.cancel).pack(side="left")
        self.dictation = tk.BooleanVar(value=False)
        ttk.Checkbutton(bar, text="Long dictation", variable=self.dictation).pack(side="left", padx=12)
        micbar = ttk.Frame(shell)
        micbar.pack(fill="x", pady=10)
        ttk.Label(micbar, text="Microphone").pack(side="left", padx=(0, 10))
        self.microphone = ttk.Combobox(micbar, state="readonly", values=["Windows default input"], width=47)
        self.microphone.current(0)
        self.microphone.pack(side="left")
        ttk.Button(micbar, text="Refresh inputs", command=self.refresh_inputs).pack(side="left", padx=8)
        targetbar = ttk.Frame(shell)
        targetbar.pack(fill="x", pady=(0, 12))
        ttk.Button(targetbar, text="Select window in 3 seconds", command=self.select_window).pack(side="left")
        self.target = tk.StringVar(value="No window selected • screen actions require a target")
        ttk.Label(targetbar, textvariable=self.target, wraplength=535).pack(side="left", padx=12)
        self.transcript = tk.Text(shell, height=18, background="#192535", foreground="#e4edf4", insertbackground="white", wrap="word", relief="flat", padx=14, pady=12, font=("Segoe UI", 10))
        self.transcript.pack(fill="both", expand=True)
        self.transcript.configure(state="disabled")
        self.command = tk.Text(shell, height=3, background="#233248", foreground="white", insertbackground="white", wrap="word", relief="flat", padx=12, pady=9, font=("Segoe UI", 11))
        self.command.pack(fill="x", pady=(14, 8))
        self.command.bind("<Control-Return>", lambda event: self.submit())
        footer = ttk.Frame(shell)
        footer.pack(fill="x")
        ttk.Button(footer, text="Run reviewed command  ↵", command=self.submit).pack(side="left")
        ttk.Button(footer, text="Capabilities & limits", command=lambda: self.write("EMA", HELP)).pack(side="left", padx=8)
        self.read_aloud = tk.BooleanVar(value=False)
        ttk.Checkbutton(footer, text="Speak replies locally", variable=self.read_aloud).pack(side="left", padx=5)
        self.write("EMA", "Type a command, or press Listen and review the transcript before running it. No AI service is used. For screen controls, choose the target window first. Click and type requests require confirmation.")
        root.protocol("WM_DELETE_WINDOW", self.close)
        root.after(100, self.drain)

    def write(self, speaker, text):
        self.transcript.configure(state="normal")
        self.transcript.insert("end", f"{speaker}\n{text}\n\n")
        # In-memory view only; bounded to avoid accumulating sensitive transcripts indefinitely.
        if int(self.transcript.index("end-1c").split('.')[0]) > 2000:
            self.transcript.delete("1.0", "500.0")
        self.transcript.see("end")
        self.transcript.configure(state="disabled")

    def refresh_inputs(self):
        def work():
            try:
                import sounddevice as sd
                inputs = [(i, device["name"]) for i, device in enumerate(sd.query_devices()) if device["max_input_channels"] > 0]
                self.events.put(("inputs", inputs))
            except Exception as exc:
                self.events.put(("error", f"Cannot list microphones ({type(exc).__name__}). Check Windows microphone privacy."))
        threading.Thread(target=work, daemon=True).start()

    def select_window(self):
        self.cancel()
        self.status.set("Switch to the target application now — selecting its foreground window in 3 seconds…")
        token = self.session.begin()
        def capture():
            if not self.session.current(token):
                return
            result = self.backend.capture_target()
            self.target.set(self.backend.target[2] if self.backend.target else "No target selected")
            self.status.set(result.message)
            self.write("EMA", result.message)
        self.root.after(3000, capture)

    def listen(self):
        if self.listening:
            return
        self.cancel()
        self.listening = True
        token = self.session.begin()
        self.speech.stop.clear()
        dictation = self.dictation.get()
        selected = self.microphone.current()
        device = self.devices[selected] if 0 <= selected < len(self.devices) else None
        self.status.set("Preparing offline speech…")
        def worker():
            try:
                text = self.speech.listen(lambda kind, data: self.events.put(("speech_event", (token, kind, data))), dictation, device)
                self.events.put(("speech_event", (token, "recognized", text)))
            except Exception as exc:
                self.events.put(("speech_event", (token, "error", str(exc))))
            finally:
                self.events.put(("listening_done", None))
        threading.Thread(target=worker, daemon=True).start()

    def cancel(self):
        self.session.begin()
        self.speech.stop.set()
        self.status.set("Cancelled pending work. Earlier device actions are not undone.")

    def submit(self):
        text = self.command.get("1.0", "end").strip()
        if not text:
            return
        self.speech.stop.set()
        intents = plan(text)
        token = self.session.begin()
        alive = lambda: self.session.current(token)
        speak = self.read_aloud.get()
        self.write("You", text)
        self.status.set("Assessing and executing supported steps…")
        def confirm(intent):
            ready = threading.Event(); answer = {}
            self.events.put(("confirm", (intent, token, ready, answer)))
            for _ in range(600):
                if ready.wait(.1):
                    return bool(answer.get("yes")) and alive()
                if not alive():
                    return False
            return False
        def worker():
            with self.execution_lock:
                results = run_plan(intents, self.backend.execute, confirm, alive)
                for result in results:
                    if alive():
                        self.events.put(("result", (token, f"[{result.status.value}] {result.message}")))
                if speak and alive() and results:
                    try:
                        # Read reply as stdin data; it is never inserted into executable script.
                        from .windows import run_process
                        import base64
                        script = "[Console]::InputEncoding=[Text.UTF8Encoding]::new(); $t=[Console]::In.ReadToEnd(); $v=New-Object -ComObject SAPI.SpVoice; $null=$v.Speak($t)"
                        executable = str(__import__('pathlib').Path(os.environ.get('SystemRoot', r'C:\Windows')) / 'System32/WindowsPowerShell/v1.0/powershell.exe')
                        run_process([executable, '-NoProfile', '-NonInteractive', '-EncodedCommand', base64.b64encode(script.encode('utf-16-le')).decode()], alive, results[-1].message[:800], timeout=60)
                    except Exception:
                        pass
                if alive():
                    self.events.put(("task_done", token))
        threading.Thread(target=worker, daemon=True).start()

    def drain(self):
        for _ in range(30):
            try:
                kind, data = self.events.get_nowait()
            except queue.Empty:
                break
            if kind == "speech_event":
                token, kind, data = data
                if not self.session.current(token):
                    continue
            if kind in {"status", "partial"}:
                self.status.set(data or "Listening…")
            elif kind == "recognized":
                if data:
                    self.command.delete("1.0", "end")
                    self.command.insert("1.0", data)
                    self.status.set("Transcript ready. Review it, then press Run. No action has been executed.")
            elif kind == "error":
                self.write("EMA", data)
                self.status.set("Operation unavailable; typed commands remain available.")
            elif kind == "listening_done":
                self.listening = False
            elif kind == "inputs":
                self.devices = [None] + [i for i, _ in data]
                self.microphone.configure(values=["Windows default input"] + [f"{i}: {name}" for i, name in data])
                self.microphone.current(0)
            elif kind == "confirm":
                intent, token, ready, answer = data
                if self.session.current(token):
                    target = self.backend.target[2] if self.backend.target else "No target window selected"
                    answer["yes"] = messagebox.askyesno("Confirm explicit screen action", f"Target: {target}\n\nAction: {intent.kind}\nContent/label ({len(intent.target)} characters; preview up to 3,000 — review the command field for full text):\n{intent.target[:3000]}\n\nThis can change another application. Proceed?")
                ready.set()
            elif kind == "result":
                token, text = data
                if self.session.current(token):
                    self.write("EMA", text)
            elif kind == "task_done" and self.session.current(data):
                self.status.set("Finished. Review the result; requested is not the same as verified.")
        self.root.after(100, self.drain)

    def close(self):
        self.cancel()
        self.root.destroy()


def main():
    if sys.platform != "win32":
        raise SystemExit("EMA Windows requires Windows 10/11. The Android app is a separate build.")
    root = tk.Tk()
    App(root)
    root.mainloop()
