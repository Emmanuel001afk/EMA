"""Portable Windows assistant contract: explicit intent, bounded plans, truthful outcomes.

No shell, network, microphone or Windows imports belong in this module.
"""
from dataclasses import dataclass
from enum import Enum
import re
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


@dataclass(frozen=True)
class Intent:
    kind: str
    target: str = ""
    value: int | None = None
    confirmation: bool = False


SETTINGS = {
    "wifi": ("Wi-Fi", "ms-settings:network-wifi"),
    "wi-fi": ("Wi-Fi", "ms-settings:network-wifi"),
    "wi fi": ("Wi-Fi", "ms-settings:network-wifi"),
    "bluetooth": ("Bluetooth", "ms-settings:bluetooth"),
    "dnd": ("Do Not Disturb", "ms-settings:notifications"),
    "do not disturb": ("Do Not Disturb", "ms-settings:notifications"),
    "notifications": ("notifications", "ms-settings:notifications"),
    "display": ("display", "ms-settings:display"),
    "brightness": ("display", "ms-settings:display"),
    "sound": ("sound", "ms-settings:sound"),
    "volume": ("sound", "ms-settings:sound"),
    "microphone": ("microphone privacy", "ms-settings:privacy-microphone"),
    "camera": ("camera privacy", "ms-settings:privacy-webcam"),
    "privacy": ("privacy", "ms-settings:privacy"),
    "airplane mode": ("airplane mode", "ms-settings:network-airplanemode"),
    "battery": ("battery saver", "ms-settings:batterysaver"),
    "battery saver": ("battery saver", "ms-settings:batterysaver"),
    "storage": ("storage", "ms-settings:storagesense"),
    "apps": ("installed apps", "ms-settings:appsfeatures"),
    "accessibility": ("accessibility", "ms-settings:easeofaccess"),
    "keyboard": ("keyboard", "ms-settings:typing"),
    "language": ("language", "ms-settings:regionlanguage"),
    "date and time": ("date and time", "ms-settings:dateandtime"),
    "hotspot": ("mobile hotspot", "ms-settings:network-mobilehotspot"),
    "vpn": ("VPN", "ms-settings:network-vpn"),
    "updates": ("Windows Update", "ms-settings:windowsupdate"),
}

HELP = """EMA for Windows — local-first desktop assistant
• open <installed app>; list apps; find app <name>
• open <feature> settings; find settings for <feature>
• set volume to 40 percent; volume status; mute; unmute
• set brightness to 60 percent; brightness status
• inspect screen; click <exact control label>; type <exact text>
• scroll up; scroll down; search web for <query>
• speech status; help; cancel

Select a target window before screen actions. EMA never uses screen text as instructions.
Screen controls require a normal unlocked interactive Windows desktop. Elevated apps,
protected screens, custom-drawn controls and some browsers may be inaccessible.
Wi-Fi, Bluetooth and DND changes currently open their Windows controls, not automatic toggles.
Pairing/password/security consent is yours. Phone calls, SMS, Android notifications,
Android app automation and always-listening wake words are not implemented here.
Speech uses the bundled English Vosk model locally. No AI service is used.
"""


def percentage(text: str) -> int | None:
    if text.isdigit():
        return int(text)
    words = text.replace("-", " ").split()
    small = dict(zip("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split(), range(20)))
    tens = dict(zip("twenty thirty forty fifty sixty seventy eighty ninety".split(), range(20, 100, 10)))
    if text in {"one hundred", "a hundred", "hundred"}:
        return 100
    if len(words) == 1:
        return small.get(words[0], tens.get(words[0]))
    if len(words) == 2 and words[0] in tens and words[1] in small and 0 < small[words[1]] < 10:
        return tens[words[0]] + small[words[1]]
    return None


def app_key(text: str) -> str:
    return "".join(c for c in text.casefold() if c.isalnum())


def parse(text: str) -> Intent:
    raw = text.strip()
    if not raw or len(raw) > 16000:
        return Intent("clarify", "Enter a request of between 1 and 16,000 characters.")
    raw = re.sub(r"^(?:hey ema[, ]+)?(?:please\s+)?", "", raw, flags=re.I)
    raw = re.sub(r"^(?:can|could|would|will) you(?: please)?\s+(?=(?:open|click|tap|type|scroll|set|find|search)\b)", "", raw, flags=re.I)
    low = raw.casefold().rstrip("?!. ")
    if re.match(r"^(?:don't|do not|never|stop|cancel)\b", low) and not re.fullmatch(r"do not disturb (?:on|off)", low):
        return Intent("cancel")
    # Content after an explicit type/search marker is data, not another command.
    for prefix, kind in (("type ", "type"), ("search web for ", "web")):
        if raw.casefold().startswith(prefix):
            payload = raw[len(prefix):]
            return Intent(kind, payload, confirmation=kind == "type")
    if low in {"help", "what can you do", "capabilities"}:
        return Intent("help")
    if low in {"speech status", "offline speech status"}:
        return Intent("speech_status")
    if low in {"inspect screen", "read screen", "show controls"}:
        return Intent("inspect")
    if low == "list apps":
        return Intent("apps")
    if low in {"volume status", "brightness status"}:
        return Intent(low.replace(" ", "_"))
    if low in {"mute", "unmute"}:
        return Intent(low)
    match = re.fullmatch(r"set (volume|brightness)(?: to)? (.+?)(?: percent|%)?", low)
    if match:
        value = percentage(match[2])
        return Intent(match[1], value=value) if value is not None and 0 <= value <= 100 else Intent("clarify", "Use a percentage from 0 to 100.")
    match = re.fullmatch(r"(?:click|tap|press) (.+)", raw, re.I)
    if match:
        return Intent("click", match[1], confirmation=True)
    if low in {"scroll up", "scroll down"}:
        return Intent("scroll", low.split()[-1])
    match = re.fullmatch(r"(?:find|search) settings for (.+)", low)
    if match:
        return Intent("settings", match[1])
    match = re.fullmatch(r"(?:open|show|go to) (.+?) settings", low)
    if match:
        return Intent("settings", match[1])
    match = re.fullmatch(r"(?:(?:turn|switch) (?:on|off) |(?:enable|disable) )(.+)|(.+?) (?:on|off)", low)
    if match and (target := (match[1] or match[2])) in SETTINGS:
        return Intent("guided_setting", target)
    match = re.fullmatch(r"find app (.+)", raw, re.I)
    if match:
        return Intent("find_app", match[1])
    match = re.fullmatch(r"(?:open|launch|start) (.+)", raw, re.I)
    if match:
        return Intent("open", match[1])
    return Intent("unsupported", "I don't have a grounded Windows action for that request. See Help for implemented capabilities.")


def plan(text: str) -> list[Intent]:
    """Split only explicit 'then' steps; preserve type/search payloads verbatim."""
    text = re.sub(r"^(?:hey ema[, ]+)?(?:please\s+)?", "", text.strip(), flags=re.I)
    text = re.sub(r"^(?:can|could|would|will) you(?: please)?\s+(?=(?:open|click|tap|type|scroll|set|find|search)\b)", "", text, flags=re.I)
    if re.search(r"\b(?:type|search web for)\s", text, re.I):
        # A payload command can be the final step, never split inside its content.
        marker = re.search(r"\b(?:type|search web for)\s", text, re.I)
        head, payload = text[:marker.start()], text[marker.start():]
        if head.strip() and not re.search(r"\bthen\s*$", head, re.I):
            return [Intent("clarify", "Separate the navigation and content command with 'then'.")]
        parts = re.split(r"\s+then\s+", head, flags=re.I)
        parts = [p for p in parts if p.strip() and p.strip().casefold() != "then"] + [payload]
    else:
        parts = re.split(r"\s+then\s+", text, flags=re.I)
    if len(parts) > 8:
        return [Intent("clarify", "Use at most eight steps per request.")]
    intents = [parse(part) for part in parts]
    if any(i.kind == "cancel" for i in intents):
        return [Intent("cancel")]
    if any(i.kind in {"unsupported", "clarify"} for i in intents):
        return [Intent("clarify", "I cannot safely resolve the whole request. Use supported steps from Help; no part was executed.")]
    return intents


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


def run_plan(intents, execute, confirm, alive) -> list[Result]:
    results = []
    for intent in intents:
        if not alive():
            results.append(Result(Status.CANCELLED, "Cancelled. Earlier actions are not undone."))
            break
        if intent.confirmation and not confirm(intent):
            results.append(Result(Status.BLOCKED, "Not confirmed. Nothing was clicked or typed for this step."))
            break
        if not alive():
            results.append(Result(Status.CANCELLED, "Cancelled before dispatch."))
            break
        try:
            result = execute(intent, alive)
        except Exception:
            result = Result(Status.FAILED, "The action adapter failed. Check its state before retrying; no automatic repeat will occur.")
        results.append(result)
        if result.status != Status.VERIFIED:
            break  # An opened Settings page/accepted event is not a completed prerequisite.
    return results
