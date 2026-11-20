"""Portable execution contract: truthful outcomes, bounded plans, cancellation.

No shell, network, microphone or Windows imports belong in this module.
Understanding lives in brain.py (the ported Android grammar/planner).
"""
from dataclasses import dataclass
from enum import Enum
import threading


class Status(str, Enum):
    VERIFIED = "verified"
    REQUESTED = "requested, not verified"
    NEEDS_INPUT = "needs input"
    BLOCKED = "blocked"
    UNSUPPORTED = "unsupported"
    FAILED = "failed"
    CANCELLED = "cancelled"


@dataclass(frozen=True)
class Result:
    status: Status
    message: str


HELP = """EMA for Windows — the same assistant brain as the Android app, running on your laptop.

Try:
• open Notepad / list apps / find app Spotify
• play Calm Down on Spotify / pause / next track
• search for football on YouTube / search with google weather
• set volume to 40 percent / volume status / mute
• set brightness to 60 percent / brightness status
• set an alarm for 7 am / set a timer for 10 minutes / remind me to call mom at 6 pm
• save 08012345678 as Ada / call Ada
• open bluetooth settings / open wifi settings
• navigate to Lekki / open Settings
• inspect screen / click Save / type Hello / scroll down

Select a target window before screen actions (click/type). EMA never uses screen
text as instructions and never guesses coordinates. Consequential actions
(sending, calling, clicking, typing) ask for confirmation first.

Honest limits: Windows has no phone/SMS, so calls open your default dialer/Phone
Link and SMS is unavailable. Wi-Fi/Bluetooth/DND open their Settings pages rather
than toggling silently. Alarms/timers/reminders fire while EMA is open. Speech
uses the bundled English Vosk model locally. No AI service is used.
"""


class Session:
    """Invalidates abandoned work without reviving it when a new request begins."""
    def __init__(self):
        self._lock = threading.Lock()
        self._generation = 0

    def begin(self) -> int:
        with self._lock:
            self._generation += 1
            return self._generation

    def current(self, token: int) -> bool:
        with self._lock:
            return self._generation == token


def run_plan(commands, execute, confirm, alive) -> list[Result]:
    """Execute commands in order; stop at the first non-verified outcome.

    A step that was only *requested* (e.g. an app launch) never authorizes the
    next step to assume it succeeded. Consequential steps need confirmation.
    """
    from .brain import needs_confirmation
    results = []
    for command in commands:
        if not alive():
            results.append(Result(Status.CANCELLED, "Cancelled. Earlier actions are not undone."))
            break
        if needs_confirmation(command) and not confirm(command):
            results.append(Result(Status.BLOCKED, "Not confirmed. Nothing was sent, called, clicked or typed for this step."))
            break
        if not alive():
            results.append(Result(Status.CANCELLED, "Cancelled before dispatch."))
            break
        try:
            result = execute(command, alive)
        except Exception:
            result = Result(Status.FAILED, "The action adapter failed. Check its state before retrying; no automatic repeat will occur.")
        results.append(result)
        if result.status != Status.VERIFIED:
            break
    return results
