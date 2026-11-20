"""EMA Windows desktop shell — same brain as Android, Windows execution.

Light theme matching the Android app. All Tk access stays on the UI thread.
"""
import os
import queue
import sys
import threading
import tkinter as tk
from tkinter import ttk, messagebox

from . import brain
from .core import HELP, Session, run_plan
from .speech import OfflineSpeech
from .windows import WindowsAssistant

BG = "#F4F6FB"
CARD = "#FFFFFF"
ACCENT = "#6254C7"
ACCENT_DARK = "#4F43A8"
INK = "#1B2238"
MUTED = "#525B72"
BORDER = "#D9DEEB"

EXAMPLES = ["open Notepad", "play Calm Down on Spotify", "set volume to 40 percent",
            "set a timer for 10 minutes", "open bluetooth settings", "list apps",
            "search with google weather", "inspect screen"]


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
        root.title("EMA — Windows")
        root.geometry("1024x780")
        root.minsize(860, 620)
        root.configure(background=BG)
        self._center(root)
        style = ttk.Style(root)
        style.theme_use("clam")
        style.configure("TFrame", background=BG)
        style.configure("Card.TFrame", background=CARD)
        style.configure("TLabel", background=BG, foreground=INK, font=("Segoe UI", 10))
        style.configure("Muted.TLabel", background=BG, foreground=MUTED, font=("Segoe UI", 9))
        style.configure("Title.TLabel", background=BG, foreground=INK, font=("Segoe UI Semibold", 22))
        style.configure("TButton", font=("Segoe UI", 10), padding=8)
        style.configure("Accent.TButton", background=ACCENT, foreground="white",
                        font=("Segoe UI Semibold", 11), padding=10)
        style.map("Accent.TButton", background=[("active", ACCENT_DARK)])
        style.configure("Ghost.TButton", background=CARD, foreground=INK, padding=8)
        style.configure("TCheckbutton", background=BG, foreground=INK, font=("Segoe UI", 9))

        shell = ttk.Frame(root, padding=20)
        shell.pack(fill="both", expand=True)

        # Header
        header = ttk.Frame(shell)
        header.pack(fill="x", pady=(0, 12))
        logo = tk.Label(header, text="E", bg=ACCENT, fg="white",
                        font=("Segoe UI Semibold", 16), width=2, height=1)
        logo.pack(side="left")
        titles = ttk.Frame(header)
        titles.pack(side="left", padx=12)
        ttk.Label(titles, text="EMA", style="Title.TLabel").pack(anchor="w")
        ttk.Label(titles, text="Windows edition — same assistant brain as Android",
                  style="Muted.TLabel").pack(anchor="w")
        self.status = tk.StringVar(value="Ready. Type a command or press Listen.")
        status_card = tk.Frame(header, bg=CARD, highlightbackground=BORDER, highlightthickness=1)
        status_card.pack(side="right", padx=(12, 0))
        tk.Label(status_card, textvariable=self.status, bg=CARD, fg=INK,
                 font=("Segoe UI", 9), wraplength=380, justify="left",
                 padx=12, pady=8).pack()

        # Voice row
        voice = ttk.Frame(shell)
        voice.pack(fill="x", pady=(0, 10))
        self.listen_btn = ttk.Button(voice, text="🎤  Listen offline", style="Accent.TButton",
                                     command=self.listen)
        self.listen_btn.pack(side="left")
        ttk.Button(voice, text="Stop", command=self.speech.stop.set).pack(side="left", padx=6)
        ttk.Button(voice, text="Cancel task", command=self.cancel).pack(side="left")
        self.dictation = tk.BooleanVar(value=False)
        ttk.Checkbutton(voice, text="Long dictation", variable=self.dictation).pack(side="left", padx=10)
        ttk.Label(voice, text="Microphone", style="Muted.TLabel").pack(side="left", padx=(14, 6))
        self.microphone = ttk.Combobox(voice, state="readonly", values=["Windows default input"], width=34)
        self.microphone.current(0)
        self.microphone.pack(side="left")
        ttk.Button(voice, text="Refresh", command=self.refresh_inputs).pack(side="left", padx=6)

        # Command input
        input_card = tk.Frame(shell, bg=CARD, highlightbackground=BORDER, highlightthickness=1)
        input_card.pack(fill="x", pady=(0, 8))
        self.command = tk.Text(input_card, height=2, bg=CARD, fg=INK, insertbackground=INK,
                               wrap="word", relief="flat", padx=12, pady=8,
                               font=("Segoe UI", 11))
        self.command.pack(side="left", fill="both", expand=True)
        self.command.bind("<Return>", lambda e: self.submit() or "break")
        self.command.bind("<Control-Return>", lambda e: self.submit() or "break")
        ttk.Button(input_card, text="Send  ↵", style="Accent.TButton",
                   command=self.submit).pack(side="right", padx=10, pady=8)
        self.command.focus_set()

        # Example chips
        chips = ttk.Frame(shell)
        chips.pack(fill="x", pady=(0, 10))
        ttk.Label(chips, text="Try:", style="Muted.TLabel").pack(side="left", padx=(0, 6))
        for example in EXAMPLES:
            chip = ttk.Button(chips, text=example, style="Ghost.TButton",
                              command=lambda e=example: self._use_example(e))
            chip.pack(side="left", padx=3)

        # Transcript
        self.transcript = tk.Text(shell, bg=CARD, fg=INK, insertbackground=INK, wrap="word",
                                  relief="flat", padx=14, pady=10, font=("Segoe UI", 10),
                                  highlightbackground=BORDER, highlightthickness=1)
        self.transcript.pack(fill="both", expand=True)
        self.transcript.configure(state="disabled")

        # Bottom row
        bottom = ttk.Frame(shell)
        bottom.pack(fill="x", pady=(10, 0))
        ttk.Button(bottom, text="Select window in 3 seconds", command=self.select_window).pack(side="left")
        self.target = tk.StringVar(value="No window selected — screen actions need a target")
        ttk.Label(bottom, textvariable=self.target, style="Muted.TLabel",
                  wraplength=340).pack(side="left", padx=10)
        ttk.Button(bottom, text="Help & limits", command=lambda: self.write("EMA", HELP)).pack(side="left")
        self.read_aloud = tk.BooleanVar(value=False)
        ttk.Checkbutton(bottom, text="Speak replies", variable=self.read_aloud).pack(side="left", padx=8)

        self.write("EMA", "Hi, I'm EMA. Type a command below, or press Listen and review the "
                          "transcript before it runs. I use the same understanding as the Android app — "
                          "try 'open Notepad', 'set a timer for 10 minutes' or 'play Calm Down on Spotify'.")
        root.protocol("WM_DELETE_WINDOW", self.close)
        root.after(100, self.drain)
        root.after(1000, self._check_schedule)

    def _center(self, root):
        root.update_idletasks()
        w, h = root.winfo_width(), root.winfo_height()
        x = max(0, (root.winfo_screenwidth() - w) // 2)
        y = max(0, (root.winfo_screenheight() - h) // 2)
        root.geometry(f"{w}x{h}+{x}+{y}")

    def _use_example(self, text):
        self.command.delete("1.0", "end")
        self.command.insert("1.0", text)
        self.command.focus_set()

    def write(self, speaker, text):
        self.transcript.configure(state="normal")
        self.transcript.insert("end", f"{speaker}\n{text}\n\n")
        if int(self.transcript.index("end-1c").split('.')[0]) > 2000:
            self.transcript.delete("1.0", "500.0")
        self.transcript.see("end")
        self.transcript.configure(state="disabled")

    def refresh_inputs(self):
        def work():
            try:
                import sounddevice as sd
                inputs = [(i, d["name"]) for i, d in enumerate(sd.query_devices())
                          if d["max_input_channels"] > 0]
                self.events.put(("inputs", inputs))
            except Exception:
                self.events.put(("error", "Cannot list microphones. Check Windows microphone privacy."))
        threading.Thread(target=work, daemon=True).start()

    def select_window(self):
        self.cancel()
        self.status.set("Switch to the target app now — selecting its window in 3 seconds…")
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
                text = self.speech.listen(
                    lambda kind, data: self.events.put(("speech_event", (token, kind, data))),
                    dictation, device)
                self.events.put(("speech_event", (token, "recognized", text)))
            except Exception as exc:
                self.events.put(("speech_event", (token, "error", str(exc))))
            finally:
                self.events.put(("listening_done", None))
        threading.Thread(target=worker, daemon=True).start()

    def cancel(self):
        self.session.begin()
        self.speech.stop.set()
        self.status.set("Cancelled pending work. Earlier actions are not undone.")

    def submit(self):
        text = self.command.get("1.0", "end").strip()
        if not text:
            return
        self.speech.stop.set()
        commands = brain.plan_commands(text)
        token = self.session.begin()
        alive = lambda: self.session.current(token)
        speak = self.read_aloud.get()
        self.write("You", text)
        if commands is None:
            self.write("EMA", "I don't understand that yet — nothing was executed. "
                              "Press Help & limits for what I can do, or try one of the examples above.")
            self.status.set("Not understood. Nothing was executed.")
            return
        self.status.set("Working…")
        def confirm(command):
            ready = threading.Event()
            answer = {}
            self.events.put(("confirm", (command, token, ready, answer)))
            for _ in range(600):
                if ready.wait(.1):
                    return bool(answer.get("yes")) and alive()
                if not alive():
                    return False
            return False
        def worker():
            with self.execution_lock:
                results = run_plan(commands, self.backend.execute, confirm, alive)
                for result in results:
                    if alive():
                        self.events.put(("result", (token, f"[{result.status.value}] {result.message}")))
                if speak and alive() and results:
                    self._speak(results[-1].message[:800], alive)
                if alive():
                    self.events.put(("task_done", token))
        threading.Thread(target=worker, daemon=True).start()

    def _speak(self, text, alive):
        try:
            from .windows import run_process
            import base64
            script = ("[Console]::InputEncoding=[Text.UTF8Encoding]::new(); "
                      "$t=[Console]::In.ReadToEnd(); $v=New-Object -ComObject SAPI.SpVoice; $null=$v.Speak($t)")
            executable = str(__import__('pathlib').Path(os.environ.get('SystemRoot', r'C:\Windows'))
                             / 'System32/WindowsPowerShell/v1.0/powershell.exe')
            run_process([executable, '-NoProfile', '-NonInteractive', '-EncodedCommand',
                         base64.b64encode(script.encode('utf-16-le')).decode()],
                        alive, text, timeout=60)
        except Exception:
            pass

    def _check_schedule(self):
        for item in self.backend.due():
            message = f"⏰ {item['kind'].capitalize()}: {item['text']}"
            self.write("EMA", message)
            self.status.set(message)
            if self.read_aloud.get():
                threading.Thread(target=lambda m=item['text']: self._speak(m, lambda: True),
                                 daemon=True).start()
        self.root.after(1000, self._check_schedule)

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
                    self.status.set("Transcript ready. Review it, then press Send. Nothing has run yet.")
            elif kind == "error":
                self.write("EMA", data)
                self.status.set("Operation unavailable; typed commands remain available.")
            elif kind == "listening_done":
                self.listening = False
            elif kind == "inputs":
                self.devices = [None] + [i for i, _ in data]
                self.microphone.configure(values=["Windows default input"] +
                                          [f"{i}: {name}" for i, name in data])
                self.microphone.current(0)
            elif kind == "confirm":
                command, token, ready, answer = data
                if self.session.current(token):
                    target = self.backend.target[2] if self.backend.target else "No target window selected"
                    answer["yes"] = messagebox.askyesno(
                        "Confirm action",
                        f"Target: {target}\n\nAction: {command.canonical()}\n\n"
                        "This can change another application or send something. Proceed?")
                ready.set()
            elif kind == "result":
                token, text = data
                if self.session.current(token):
                    self.write("EMA", text)
            elif kind == "task_done" and self.session.current(data):
                self.status.set("Finished. 'Requested' means Windows was asked — not that it is done.")
        self.root.after(100, self.drain)

    def close(self):
        self.cancel()
        self.root.destroy()


def main():
    if sys.platform != "win32":
        raise SystemExit("EMA Windows requires Windows 10/11. The Android app is a separate build.")
    root = tk.Tk()
    app = App(root)
    if "--gui-smoke-test" in sys.argv:
        root.after(600, app.close)
    root.mainloop()
