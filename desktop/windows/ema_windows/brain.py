"""EMA's digital-assistant brain, ported from the Android Kotlin sources.

The Android app's understanding layer (EmaCommandGrammar, EmaNativeCommandPlanner,
EmaTimeParser, EmaSelection, EmaAppResolver) is pure Kotlin with no Android imports,
so this is a faithful Python port of the same logic — not a rewrite. The Windows
edition reuses it verbatim and only replaces the *execution* layer (Android APIs)
with Windows adapters.

Pure module: no microphone, network, shell or Windows imports.
"""
from __future__ import annotations

import re
import time
from dataclasses import dataclass, field


# --------------------------------------------------------------------- commands

@dataclass(frozen=True)
class SendMessage:
    recipient: str
    app: str | None
    body: str
    def canonical(self) -> str:
        s = f"send a message to {self.recipient}"
        if self.app:
            s += f" on {self.app}"
        if self.body:
            s += f" saying {self.body}"
        return s


@dataclass(frozen=True)
class CallContact:
    name: str
    sim: int | None = None
    def canonical(self) -> str:
        return f"call {self.name}" + (f" with SIM {self.sim}" if self.sim else "")


@dataclass(frozen=True)
class PlayMedia:
    query: str
    app: str | None = None
    def canonical(self) -> str:
        return f"play {self.query}" + (f" on {self.app}" if self.app else "")


@dataclass(frozen=True)
class MediaControl:
    action: str  # TOGGLE PLAY PAUSE RESUME STOP NEXT PREVIOUS WHATS_PLAYING
    def canonical(self) -> str:
        return {"TOGGLE": "play or pause", "PLAY": "play", "PAUSE": "pause",
                "RESUME": "resume", "STOP": "stop", "NEXT": "next track",
                "PREVIOUS": "previous track", "WHATS_PLAYING": "what is playing"}[self.action]


@dataclass(frozen=True)
class SearchInApp:
    query: str
    app: str | None = None
    select_first: bool = False
    def canonical(self) -> str:
        s = f"search {self.query}"
        if self.app:
            s += f" on {self.app}"
        if self.select_first:
            s += " and open the first result"
        return s


@dataclass(frozen=True)
class WebSearch:
    query: str
    def canonical(self) -> str:
        return f"search with google {self.query}"


@dataclass(frozen=True)
class OpenApp:
    app: str
    def canonical(self) -> str:
        return f"open {self.app}"


@dataclass(frozen=True)
class OpenAppSection:
    app: str
    section: str
    def canonical(self) -> str:
        return f"go to {self.section} in {self.app}"


@dataclass(frozen=True)
class OpenSettingsPage:
    target: str
    def canonical(self) -> str:
        return "go to settings" if not self.target else f"go to {self.target} in settings"


@dataclass(frozen=True)
class NavigateTo:
    destination: str
    def canonical(self) -> str:
        return f"navigate to {self.destination}"


@dataclass(frozen=True)
class SetAlarm:
    time_text: str
    def canonical(self) -> str:
        return f"set an alarm for {self.time_text}"


@dataclass(frozen=True)
class SetTimer:
    duration_text: str
    def canonical(self) -> str:
        return f"set a timer for {self.duration_text}"


@dataclass(frozen=True)
class SetReminder:
    text: str
    time_text: str = ""
    def canonical(self) -> str:
        return f"remind me to {self.text}" + (f" at {self.time_text}" if self.time_text else "")


@dataclass(frozen=True)
class SaveContact:
    name: str
    number: str
    def canonical(self) -> str:
        return f"save {self.number} as {self.name}"


@dataclass(frozen=True)
class OpenSmsComposer:
    recipient: str
    body: str = ""
    def canonical(self) -> str:
        return f"text {self.recipient}"


@dataclass(frozen=True)
class SelectResult:
    selection: "Selection"
    app: str | None = None
    play: bool = False
    replaces_previous: bool = False
    def canonical(self) -> str:
        s = ("play " if self.play else "open ") + self.selection.phrase()
        if self.app:
            s += f" on {self.app}"
        return s


# Windows-native commands (no Android equivalent; executed by Windows adapters).

@dataclass(frozen=True)
class SetVolume:
    value: int
    def canonical(self) -> str:
        return f"set volume to {self.value} percent"


@dataclass(frozen=True)
class SetBrightness:
    value: int
    def canonical(self) -> str:
        return f"set brightness to {self.value} percent"


@dataclass(frozen=True)
class SetMute:
    muted: bool
    def canonical(self) -> str:
        return "mute" if self.muted else "unmute"


@dataclass(frozen=True)
class VolumeStatus:
    def canonical(self) -> str:
        return "volume status"


@dataclass(frozen=True)
class BrightnessStatus:
    def canonical(self) -> str:
        return "brightness status"


@dataclass(frozen=True)
class InspectScreen:
    def canonical(self) -> str:
        return "inspect screen"


@dataclass(frozen=True)
class ClickControl:
    label: str
    def canonical(self) -> str:
        return f"click {self.label}"


@dataclass(frozen=True)
class TypeText:
    text: str
    def canonical(self) -> str:
        return f"type {self.text}"


@dataclass(frozen=True)
class Scroll:
    direction: str  # up | down
    def canonical(self) -> str:
        return f"scroll {self.direction}"


@dataclass(frozen=True)
class ListApps:
    def canonical(self) -> str:
        return "list apps"


@dataclass(frozen=True)
class FindApp:
    name: str
    def canonical(self) -> str:
        return f"find app {self.name}"


@dataclass(frozen=True)
class Help:
    def canonical(self) -> str:
        return "help"


@dataclass(frozen=True)
class Cancel:
    def canonical(self) -> str:
        return "cancel"


@dataclass(frozen=True)
class SpeechStatus:
    def canonical(self) -> str:
        return "speech status"


# Consequential commands always need explicit confirmation before dispatch.
CONFIRM_KINDS = (SendMessage, CallContact, ClickControl, TypeText, OpenSmsComposer)


def needs_confirmation(command) -> bool:
    return isinstance(command, CONFIRM_KINDS)


# --------------------------------------------------------------------- selection

_ORDINALS = {"first": 1, "1st": 1, "top": 1, "second": 2, "2nd": 2, "third": 3, "3rd": 3,
             "fourth": 4, "4th": 4, "fifth": 5, "5th": 5, "sixth": 6, "6th": 6,
             "seventh": 7, "7th": 7, "eighth": 8, "8th": 8, "ninth": 9, "9th": 9,
             "tenth": 10, "10th": 10}
_CARDINALS = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6,
              "seven": 7, "eight": 8, "nine": 9, "ten": 10}
_ITEM_NOUNS = {"result", "results", "one", "ones", "video", "videos", "song", "songs",
               "track", "tracks", "movie", "movies", "podcast", "podcasts",
               "playlist", "playlists", "channel", "channels", "album", "albums"}


def _ordinal_word(n: int) -> str:
    words = {1: "first", 2: "second", 3: "third", 4: "fourth", 5: "fifth",
             6: "sixth", 7: "seventh", 8: "eighth", 9: "ninth", 10: "tenth"}
    return words.get(n, f"{n}th")


@dataclass(frozen=True)
class Selection:
    kind: str  # ORDINAL LAST LONGEST SHORTEST NEWEST MOST_VIEWED ANOTHER TITLED
    position: int = 0
    text: str = ""

    def phrase(self) -> str:
        return {"ORDINAL": f"the {_ordinal_word(self.position)} result",
                "LAST": "the last result", "LONGEST": "the longest result",
                "SHORTEST": "the shortest result", "NEWEST": "the newest result",
                "MOST_VIEWED": "the most viewed result", "ANOTHER": "another result",
                "TITLED": f"the result called {self.text}"}[self.kind]

    @property
    def needs_metadata(self) -> bool:
        return self.kind in {"LONGEST", "SHORTEST", "NEWEST", "MOST_VIEWED"}


def parse_reference(phrase: str, pronoun_means_first: bool = False) -> Selection | None:
    reference = re.sub(r"(?i)\s+(?:(?:that\s+)?you\s+(?:can\s+)?see|shown(?:\s+(?:here|on screen))?|on\s+(?:the\s+)?screen)\s*[.!?]*$", "", phrase)
    words = [w for w in re.split(r"\s+", re.sub(r"[^a-z0-9#' ]", " ", reference.lower())) if w]
    if not words:
        return None
    trailing = {"instead", "please", "now", "then", "too", "again", "here"}
    while words and words[-1] in trailing:
        words.pop()
    if len(words) >= 2 and words[-2:] == ["for", "me"]:
        words = words[:-2]
    if len(words) >= 2 and words[-2:] == ["on", "screen"]:
        words = words[:-2]
    if not words:
        return None
    if len(words) == 1 and words[0] in {"it", "that", "this", "them"}:
        return Selection("ORDINAL", 1) if pronoun_means_first else None
    if words in (["that", "one"], ["this", "one"]):
        return Selection("ORDINAL", 1) if pronoun_means_first else None
    joined = " ".join(words)
    if re.match(r"^(?:an?\s+)?(?:another|different|other)(?:\s+\w+)?$", joined) or \
       re.match(r"^(?:the\s+)?next(?:\s+(?:one|result|video|song|track|item))?$", joined) or \
       joined in {"something else", "a different one"}:
        return Selection("ANOTHER")
    index = 1 if words[0] == "the" else 0
    if index >= len(words):
        return None
    if words[index] in {"number", "result", "item", "option", "video", "song"} and index + 1 < len(words):
        n = _to_int(words[index + 1].lstrip("#")) or _CARDINALS.get(words[index + 1])
        if n is not None and 1 <= n <= 50 and index + 2 == len(words):
            return Selection("ORDINAL", n)
    if words[index].startswith("#"):
        n = _to_int(words[index][1:])
        if n is not None and 1 <= n <= 50 and index + 1 == len(words):
            return Selection("ORDINAL", n)
    head = words[index]
    rest = words[index + 1:]
    rest_is_item_noun = not rest or (len(rest) <= 2 and all(w in _ITEM_NOUNS or w in {"on", "in", "list"} for w in rest))

    def rest_ok(require_noun: bool) -> bool:
        return bool(rest) and rest_is_item_noun if require_noun else rest_is_item_noun

    if head == "very" and rest:
        return parse_reference("the " + " ".join(rest), pronoun_means_first)
    if head in _ORDINALS:
        if head == "top" and not rest_ok(True):
            return None
        if rest_ok(False):
            return Selection("ORDINAL", _ORDINALS[head])
    m = re.match(r"^(\d{1,2})(?:st|nd|rd|th)$", head)
    if m:
        n = int(m.group(1))
        if 1 <= n <= 50 and rest_ok(False):
            return Selection("ORDINAL", n)
    if head in {"last", "final", "bottom"}:
        if rest_ok(head == "bottom"):
            return Selection("LAST")
    if head == "longest" and rest_ok(False):
        return Selection("LONGEST")
    if head == "shortest" and rest_ok(False):
        return Selection("SHORTEST")
    if head in {"newest", "latest"} and rest_ok(False):
        return Selection("NEWEST")
    if head == "most" and rest:
        second, after = rest[0], rest[1:]
        after_ok = not after or (len(after) <= 2 and all(w in _ITEM_NOUNS for w in after))
        if second == "recent" and after_ok:
            return Selection("NEWEST")
        if second in {"viewed", "popular", "watched"} and after_ok:
            return Selection("MOST_VIEWED")
    called_at = next((i for i, w in enumerate(words) if w in {"called", "titled", "named"}), -1)
    if 1 <= called_at < len(words) - 1 and all(w in _ITEM_NOUNS for w in words[index:called_at]):
        return Selection("TITLED", text=" ".join(words[called_at + 1:]))
    return None


def _to_int(s: str):
    try:
        return int(s)
    except (ValueError, TypeError):
        return None


# ------------------------------------------------------------------ time parser

_WORD_NUMBERS = {"zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5,
                 "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11,
                 "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15,
                 "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19,
                 "twenty": 20, "thirty": 30, "forty": 40, "fifty": 50, "sixty": 60,
                 "seventy": 70, "eighty": 80, "ninety": 90, "quarter": 15, "half": 30}
_TENS_WORDS = {"twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"}
_DAY_NAMES = {"sunday": 6, "monday": 0, "tuesday": 1, "wednesday": 2, "thursday": 3,
              "friday": 4, "saturday": 5}  # Python weekday(): Mon=0


@dataclass(frozen=True)
class Moment:
    millis: int
    kind: str  # CLOCK RELATIVE DAY_PART DAY_NAME
    spoken: str


@dataclass(frozen=True)
class Duration:
    seconds: int
    spoken: str


def _normalize_time(value: str) -> str:
    return re.sub(r"\s+", " ", value.lower().replace(" ", " ").replace(".", "")).strip()


def words_to_digits(value: str) -> str:
    words = _normalize_time(value).split(" ")
    if not words:
        return value
    joined, i = [], 0
    while i < len(words):
        a = words[i]
        b = words[i + 1] if i + 1 < len(words) else None
        if a in _TENS_WORDS:
            bv = _WORD_NUMBERS.get(b or "")
            if bv is not None and 1 <= bv <= 9:
                joined.append(str(_WORD_NUMBERS[a] + bv))
                i += 2
                continue
        av = _WORD_NUMBERS.get(a)
        c = words[i + 2] if i + 2 < len(words) else None
        cv = _WORD_NUMBERS.get(c or "")
        if av is not None and 1 <= av <= 23 and b in {"oh", "o"} and cv is not None and 1 <= cv <= 9:
            joined.append(f"{av}:0{cv}")
            i += 3
            continue
        joined.append(str(av) if av is not None else a)
        i += 1
    return " ".join(joined)


def parse_duration(value: str) -> Duration | None:
    text = words_to_digits(value)
    m = re.search(r"(\d+)\s*(second|sec|minute|min|hour|hr|day)s?", text)
    if not m:
        return None
    amount = int(m.group(1))
    if amount <= 0:
        return None
    unit = m.group(2)
    seconds = {"second": 1, "sec": 1, "minute": 60, "min": 60,
               "hour": 3600, "hr": 3600}.get(unit, 86400)
    return Duration(amount * seconds, value.strip())


def _at_time(now_ms: int, hour: int, minute: int) -> int:
    import datetime
    now = datetime.datetime.fromtimestamp(now_ms / 1000)
    target = now.replace(hour=max(0, min(23, hour)), minute=max(0, min(59, minute)), second=0, microsecond=0)
    if target.timestamp() * 1000 <= now_ms:
        target += datetime.timedelta(days=1)
    return int(target.timestamp() * 1000)


def _nearest_future(now_ms: int, hour: int, minute: int) -> int:
    import datetime
    now = datetime.datetime.fromtimestamp(now_ms / 1000)
    candidates = []
    for h in {hour % 24, (hour + 12) % 24}:
        t = now.replace(hour=h, minute=minute, second=0, microsecond=0)
        candidates.append(int(t.timestamp() * 1000))
    future = [c for c in candidates if c > now_ms]
    return min(future) if future else min(candidates) + 86400000


def _normalize_hour(hour: int, meridiem: str) -> int:
    if not 0 <= hour <= 23:
        return hour
    if meridiem == "am":
        return 0 if hour == 12 else hour
    if meridiem == "pm":
        return hour + 12 if hour < 12 else hour
    return hour


def _clock(raw: str, text: str, now_ms: int) -> int | None:
    phrase = re.search(r"(half|quarter)\s+(past|after|to|before)\s+([a-z0-9]+)", raw) or \
        re.search(r"(30|15)\s+(past|after|to|before)\s+(\d{1,2})", text)
    if phrase:
        is_half = phrase.group(1) in {"half", "30"}
        offset = 30 if is_half else 15
        hour_word = phrase.group(3)
        hour = _to_int(hour_word)
        if hour is None:
            hour = _WORD_NUMBERS.get(hour_word)
        if hour is None:
            return None
        toward = phrase.group(2)
        base_hour = hour
        if toward in {"to", "before"}:
            base_hour -= 1
            minute = 60 - offset
        else:
            minute = offset
        if base_hour < 0:
            base_hour += 12
        following = (raw if phrase.re is not None else text)[phrase.end():].lstrip()
        m = re.match(r"^(a\s*m|p\s*m|am|pm|in the morning|in the afternoon|in the evening|at night)\b", following)
        meridiem = re.sub(r"\s+", "", m.group(1)) if m else ""
        return _at_time(now_ms, _normalize_hour(base_hour, meridiem), minute)
    m = re.search(r"(\d{1,2})(?::(\d{2})|\s+([1-5]\d)(?!\s*(?:second|sec|minute|min|hour|hr|day|percent|%)|:\d))?\s*(a\s*m|p\s*m|am|pm|in the morning|in the afternoon|in the evening|at night)?", text)
    if not m:
        return None
    hour = int(m.group(1))
    minute_str = m.group(2) or m.group(3) or "0"
    minute = int(minute_str)
    if not 0 <= minute <= 59 or not 0 <= hour <= 23:
        return None
    meridiem = re.sub(r"\s+", "", m.group(4) or "")
    if not meridiem and 1 <= hour <= 12:
        return _nearest_future(now_ms, hour, minute)
    return _at_time(now_ms, _normalize_hour(hour, meridiem), minute)


def _named_day_part(text: str, now_ms: int) -> int | None:
    hour = None
    if re.search(r"\bnoon\b", text):
        hour = 12
    elif re.search(r"\bmidnight\b", text):
        hour = 0
    elif re.search(r"\btonight\b", text) or re.search(r"\bthis night\b", text):
        hour = 20
    elif re.search(r"\bthis evening\b", text) or re.search(r"\bevening\b", text):
        hour = 18
    elif re.search(r"\bthis afternoon\b", text) or re.search(r"\bafternoon\b", text):
        hour = 15
    elif re.search(r"\bthis morning\b", text) or re.search(r"\bmorning\b", text):
        hour = 8
    if hour is None:
        return None
    stripped = re.sub(r"\b(noon|midnight|tonight|this evening|evening|this afternoon|afternoon|this morning|morning|night)\b", " ", text)
    m = re.search(r"(\d{1,2})(?::(\d{2}))?\s*(?:am|pm)?", stripped)
    if m and m.group(1):
        h = int(m.group(1))
        minute = int(m.group(2) or "0")
        if hour >= 18 and 1 <= h <= 11:
            h += 12
        elif hour == 15 and 1 <= h <= 7:
            h += 12
        return _at_time(now_ms, max(0, min(23, h)), max(0, min(59, minute)))
    return _at_time(now_ms, hour, 0)


def _named_day_part_only(text: str) -> int | None:
    if re.search(r"\bnoon\b", text):
        return 12
    if re.search(r"\bmidnight\b", text):
        return 0
    if re.search(r"\btonight\b", text) or re.search(r"\bevening\b", text) or re.search(r"\bnight\b", text):
        return 20
    if re.search(r"\bafternoon\b", text):
        return 15
    if re.search(r"\bmorning\b", text):
        return 8
    return None


def _named_day(text: str, now_ms: int) -> int | None:
    import datetime
    tomorrow = bool(re.search(r"\btomorrow\b", text) or re.search(r"\bnext day\b", text))
    day_entry = next((k for k in _DAY_NAMES if re.search(rf"\b{k}\b", text)), None)
    if not tomorrow and day_entry is None:
        return None
    time_part = re.sub(r"\b(tomorrow|next day|on|the|at|by)\b", " ", text)
    hm = re.search(r"(\d{1,2})(?::(\d{2}))?\s*(am|pm)?", time_part)
    part = _named_day_part_only(text)
    if hm and hm.group(1):
        h = int(hm.group(1))
        meridiem = hm.group(3) or ""
        if meridiem == "pm" and h < 12:
            h += 12
        if meridiem == "am" and h == 12:
            h = 0
        if not meridiem and part is not None and 1 <= h <= 7 and part >= 12:
            h += 12
        hour = h
        minute = int(hm.group(2) or "0")
    elif part is not None:
        hour, minute = part, 0
    else:
        hour, minute = 9, 0
    now = datetime.datetime.fromtimestamp(now_ms / 1000)
    if day_entry is not None:
        delta = (_DAY_NAMES[day_entry] - now.weekday()) % 7
        if delta == 0:
            delta = 7
        target = now + datetime.timedelta(days=delta)
    else:
        target = now + datetime.timedelta(days=1)
    target = target.replace(hour=max(0, min(23, hour)), minute=max(0, min(59, minute)), second=0, microsecond=0)
    result = int(target.timestamp() * 1000)
    return result if result > now_ms else result + 86400000


def parse_time(value: str, now_ms: int | None = None) -> Moment | None:
    if now_ms is None:
        now_ms = int(time.time() * 1000)
    raw = _normalize_time(value)
    text = words_to_digits(value)
    if not text.strip():
        return None
    duration = parse_duration(text)
    if duration:
        relative = bool(re.search(r"\b(?:in|after|within|from now)\b", text) or re.search(r"\d", text))
        if relative:
            return Moment(now_ms + duration.seconds * 1000, "RELATIVE", value.strip())
    part = _named_day_part(text, now_ms)
    if part is not None:
        return Moment(part, "DAY_PART", value.strip())
    day = _named_day(text, now_ms)
    if day is not None:
        return Moment(day, "DAY_NAME", value.strip())
    clock = _clock(raw, text, now_ms)
    if clock is not None:
        return Moment(clock, "CLOCK", value.strip())
    return None


def describe_time(millis: int) -> str:
    import datetime
    return datetime.datetime.fromtimestamp(millis / 1000).strftime("%A %I:%M %p").lstrip("0")


# ------------------------------------------------------------------ app resolver

@dataclass(frozen=True)
class InstalledApp:
    label: str
    app_id: str = ""


@dataclass(frozen=True)
class AppMatch:
    app: InstalledApp
    score: int
    via: str
    @property
    def confident(self) -> bool:
        return self.score >= 980


_ALIASES = {
    "whatsapp": ["whatsapp"], "wa": ["whatsapp"], "whats app": ["whatsapp"],
    "messenger": ["messenger"], "facebook messenger": ["messenger"],
    "telegram": ["telegram"], "signal": ["signal"],
    "instagram": ["instagram"], "insta": ["instagram"], "ig": ["instagram"],
    "youtube": ["youtube"], "yt": ["youtube"], "you tube": ["youtube"],
    "spotify": ["spotify"], "apple music": ["music"], "soundcloud": ["soundcloud"],
    "audible": ["audible"], "podcasts": ["podcast"],
    "chrome": ["chrome"], "edge": ["edge"], "firefox": ["firefox"],
    "browser": ["browser"], "web browser": ["browser"],
    "gmail": ["gmail"], "mail": ["mail", "outlook"], "outlook": ["outlook"],
    "messages": ["messages", "messaging"], "sms": ["messages"],
    "phone": ["phone", "dialer"], "contacts": ["contacts", "people"],
    "camera": ["camera"], "clock": ["clock", "alarm"], "alarm": ["clock", "alarm"],
    "calendar": ["calendar"], "settings": ["settings"], "maps": ["maps"],
    "google maps": ["maps"], "photos": ["photos"], "gallery": ["gallery", "photos"],
    "files": ["files", "file explorer"], "file explorer": ["file explorer", "explorer"],
    "explorer": ["explorer"], "notepad": ["notepad"], "notes": ["notepad", "notes"],
    "calculator": ["calculator"], "word": ["word"], "excel": ["excel"],
    "powerpoint": ["powerpoint"], "teams": ["teams"], "zoom": ["zoom"],
    "discord": ["discord"], "slack": ["slack"], "netflix": ["netflix"],
    "amazon": ["amazon"], "steam": ["steam"], "twitch": ["twitch"],
    "reddit": ["reddit"], "twitter": ["twitter"], "tiktok": ["tiktok"],
    "linkedin": ["linkedin"], "weather": ["weather"], "translate": ["translate"],
    "recorder": ["recorder"], "voice recorder": ["recorder"],
    "store": ["store"], "microsoft store": ["store"],
    "terminal": ["terminal", "powershell"], "powershell": ["powershell"],
    "cmd": ["cmd", "terminal"],
}
_NOISE = {"the", "app", "application", "for", "me", "my", "please", "open", "launch",
          "start", "up", "on", "in", "into", "some", "a", "an"}


def _app_normalize(value: str) -> str:
    return re.sub(r"\s+", " ", value.lower().replace(" ", " ")).strip()


def resolve_app(spoken: str, installed: list[InstalledApp]) -> AppMatch | None:
    """Score installed apps against a spoken name. Returns the best match."""
    query = _app_normalize(spoken)
    if not query or not installed:
        return None
    words = [w for w in query.split(" ") if w not in _NOISE]
    query = " ".join(words) if words else query
    best = None
    for app in installed:
        label = _app_normalize(app.label)
        score, via = 0, ""
        if query == label:
            score, via = 1000, "exact label"
        elif query in _ALIASES and any(s in label for s in _ALIASES[query]):
            score, via = 950, "alias"
        elif all(w in label.split(" ") for w in query.split(" ")):
            score, via = 850, "all words"
        elif label.startswith(query) or query.startswith(label):
            score, via = 700, "prefix"
        elif query in label:
            score, via = 500, "substring"
        if score and (best is None or score > best.score):
            best = AppMatch(app, score, via)
    return best


# ----------------------------------------------------------------------- grammar

_POLITE_PIECE = re.compile(
    r"(?i)^(?:hey|ok|okay|hi|hello|yo|ema|please|kindly|could you|can you|would you|"
    r"will you|i want you to|i need you to|i'd like you to|i would like you to|"
    r"i want to|help me|let's|lets)\s*[,;:!?]?\s+")
_BODY_MARKERS = ["that says", "which says", "that reads", "to say", "saying",
                 "with the message", "with the text", "with a message", "reading"]
_MESSAGE_NOUN = re.compile(r"(?i)^(?:a\s+|an\s+|the\s+|this\s+|that\s+)?(?:message|text|dm|note|sms|chat|mail|email|voice note)\b\s*")
_MESSAGE_VERB = re.compile(r"(?i)^(?:send|message|text|dm|ping|whatsapp|chat|write|mail|email)\b")
_APP_PREP = r"(?:on|in|using|via|through)"
_TO_PREFIX = re.compile(r"(?i)^to\s+")
_MEDIA_DECORATION = re.compile(
    r"(?i)^(?:some|the|a|an|my|that|this|please)\s+"
    r"(?:song|track|music|tune|album|playlist|video|episode|podcast|something|anything)\s+"
    r"(?:called|named|titled)?\s*")
_SEARCH_VERB = r"(?:search for|search|look up|look for|find)"
_SENTENCE_VERBS = {"is", "are", "was", "were", "will", "would", "should", "can", "could",
                   "have", "has", "had", "does", "did", "been", "being"}
_PLAY_VERB = re.compile(r"(?i)^(?:play|put on|start playing|listen to|stream|replay|shuffle play|play me)\b")
_CALL_VERB = re.compile(r"(?i)^(?:call|dial|phone|ring|buzz|make a call to|give a call to|call up)\b")


def normalize(raw: str) -> str:
    text = raw.strip().replace(" ", " ")
    text = re.sub(r"\s+", " ", text)
    text = re.sub(r"[.!?]+$", "", text)
    text = re.sub(r"^[,;:\s]+", "", text).strip()
    for _ in range(8):
        stripped = re.sub(r"^[,;:\s]+", "", _POLITE_PIECE.sub("", text)).strip()
        if stripped == text:
            break
        text = stripped
    return re.sub(r"\s+", " ", text.strip())


def _plausible_recipient(value: str) -> bool:
    words = [w for w in value.strip().split() if w]
    if not words or len(words) > 6:
        return False
    if any(w.lower() in _SENTENCE_VERBS for w in words):
        return False
    return all(len(w) <= 24 for w in words)


def _plausible_app(value: str) -> bool:
    words = [w for w in value.strip().split() if w]
    if not words or len(words) > 4:
        return False
    return all(w.lower() not in _SENTENCE_VERBS for w in words)


def _starts_with_article(value: str) -> bool:
    first = value.strip().split()[0].lower() if value.strip() else ""
    return first in {"the", "a", "an", "my", "his", "her", "our", "their", "this", "that", "it"}


def _index_of_marker(text: str, marker: str) -> int:
    lower = text.lower()
    start = 0
    while True:
        idx = lower.find(marker, start)
        if idx < 0:
            return -1
        before = lower[idx - 1] if idx > 0 else " "
        after_idx = idx + len(marker)
        after = lower[after_idx] if after_idx < len(lower) else " "
        if not (before.isalnum()) and not (after.isalnum()):
            return idx
        start = idx + 1


def _looks_like_time_only(value: str) -> bool:
    return bool(re.match(r"(?i)^[\d\s:.]*$", value.strip()))


def _media_control(lower: str):
    table = {
        "play": "TOGGLE", "play or pause": "TOGGLE", "toggle playback": "TOGGLE",
        "play music": "TOGGLE", "play it": "TOGGLE", "keep playing": "TOGGLE",
        "pause": "PAUSE", "pause the music": "PAUSE", "pause playback": "PAUSE",
        "pause music": "PAUSE", "hold": "PAUSE",
        "resume": "RESUME", "resume playback": "RESUME", "resume music": "RESUME",
        "continue": "RESUME", "continue playing": "RESUME", "unpause": "RESUME",
        "stop": "STOP", "stop playback": "STOP", "stop the music": "STOP",
        "stop playing": "STOP", "stop music": "STOP",
        "next": "NEXT", "next track": "NEXT", "next song": "NEXT", "skip": "NEXT",
        "skip track": "NEXT", "skip song": "NEXT", "skip this": "NEXT",
        "play the next song": "NEXT", "play next song": "NEXT",
        "change song": "NEXT", "change the song": "NEXT",
        "previous": "PREVIOUS", "previous track": "PREVIOUS", "previous song": "PREVIOUS",
        "last song": "PREVIOUS", "go back a song": "PREVIOUS",
        "play the previous song": "PREVIOUS", "play previous song": "PREVIOUS",
        "rewind": "PREVIOUS", "back a song": "PREVIOUS",
        "what is playing": "WHATS_PLAYING", "what's playing": "WHATS_PLAYING",
        "what song is this": "WHATS_PLAYING", "which song is this": "WHATS_PLAYING",
        "what is this song": "WHATS_PLAYING", "current song": "WHATS_PLAYING",
        "what song is playing": "WHATS_PLAYING", "name this song": "WHATS_PLAYING",
    }
    action = table.get(lower.strip())
    return MediaControl(action) if action else None


def _tell_form(command: str):
    m = re.search(r"(?i)^(?:tell|inform|let)\s+(.+?)\s+(?:(?:on|in|using|via|through)\s+(.+?)\s+)?(?:that|to say|saying|that he|that she|that they)\s+(.+)$", command)
    if not m:
        return None
    who, app, body = m.group(1).strip(), (m.group(2) or "").strip() or None, m.group(3).strip()
    if not who or not body or not _plausible_recipient(who) or _looks_like_time_only(body):
        return None
    return SendMessage(who, app, body)


def _message(command: str, lower: str):
    tell = _tell_form(command)
    if tell:
        return tell
    verb_match = _MESSAGE_VERB.search(command)
    if not verb_match:
        return None
    verb = verb_match.group(0).lower()
    verb_app = "WhatsApp" if verb == "whatsapp" else None
    after_verb = command[verb_match.end():].strip()
    noun_match = _MESSAGE_NOUN.search(after_verb)
    if noun_match:
        subject = _TO_PREFIX.sub("", after_verb[noun_match.end():]).strip()
    else:
        subject = after_verb
    if not subject or subject.lower() in {"messages", "message", "texts", "emails", "sms", "chats"}:
        return None
    body_group = "|".join(re.escape(m) for m in _BODY_MARKERS)
    m = re.search(rf"(?i)^(.+?)\s+{_APP_PREP}\s+(.+?)\s+(?:{body_group})\s+(.+)$", subject)
    if m:
        who, app, body = m.group(1).strip(), m.group(2).strip(), m.group(3).strip()
        if who and app and body and _plausible_recipient(who) and _plausible_app(app):
            return SendMessage(who, app, body)
    m = re.search(rf"(?i)^(.+?)\s+to\s+(.+?)\s+{_APP_PREP}\s+(.+)$", subject)
    if m:
        body, who, app = m.group(1).strip(), m.group(2).strip(), m.group(3).strip()
        if body and who and _plausible_recipient(who) and _plausible_app(app):
            return SendMessage(who, app, body)
    m = re.search(rf"(?i)^(.+?)\s+(?:{body_group})\s+(.+)$", subject)
    if m:
        who = m.group(1).strip()
        body = m.group(2).strip()
        if who and body and _plausible_recipient(who):
            app = verb_app
            if app is None:
                trailing = re.search(rf"(?i)\s+{_APP_PREP}\s+([^,.!?]+)$", body)
                if trailing:
                    candidate = trailing.group(1).strip()
                    if _plausible_app(candidate) and not _starts_with_article(candidate):
                        app = candidate
                        body = body[:trailing.start()].strip()
            if body:
                return SendMessage(who, app, body)
    m = re.search(rf"(?i)^(.+?)\s+to\s+(.+?)(?:\s+{_APP_PREP}\s+(.+))?$", subject)
    if m:
        body, who = m.group(1).strip(), m.group(2).strip()
        app = (m.group(3) or "").strip() or None
        sentence_like = len(body.split()) >= 2 or any(w in _SENTENCE_VERBS or w in {"i", "we", "you", "my", "our", "the", "a", "an", "to", "for"} for w in body.lower().split())
        if body and _plausible_recipient(who) and (app is not None or sentence_like):
            if app is None or _plausible_app(app):
                return SendMessage(who, app or verb_app, body)
    m = re.search(rf"(?i)^(.+?)\s+{_APP_PREP}\s+(.+)$", subject)
    if m:
        who, app = m.group(1).strip(), m.group(2).strip()
        if who and _plausible_recipient(who) and _plausible_app(app):
            return SendMessage(who, app, "")
    who_only = _TO_PREFIX.sub("", subject).strip()
    if who_only and _plausible_recipient(who_only):
        return SendMessage(who_only, verb_app, "")
    return None


def _clean_media_query(value: str) -> str:
    cleaned = _MEDIA_DECORATION.sub("", value)
    cleaned = re.sub(r"(?i)^(?:for me|please|now)\s*", "", cleaned)
    cleaned = re.sub(r"(?i)\s+(?:please|now|for me)$", "", cleaned)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return cleaned or value.strip()


def _play(command: str, lower: str):
    m = re.search(r"(?i)^(?:open|launch|go to|go into|start)\s+(.+?)\s+and\s+(?:play|listen to|put on|stream)\s+(.+)$", command)
    if m:
        app, query = m.group(1).strip(), _clean_media_query(m.group(2).strip())
        if app and query:
            return PlayMedia(query, app)
    m = re.search(r"(?i)^(?:in|inside|within|on|using)\s+(.+?)\s*,?\s+(?:play|listen to|put on|stream)\s+(.+)$", command)
    if m:
        app, query = m.group(1).strip(), _clean_media_query(m.group(2).strip())
        if app and query and _plausible_app(app):
            return PlayMedia(query, app)
    if not _PLAY_VERB.search(lower):
        return None
    rest = _PLAY_VERB.sub("", command).strip()
    rest = re.sub(r"(?i)^me\s+", "", rest).strip()
    if not rest:
        return None
    app = None
    for prep in ["on", "in", "using", "via", "through", "from", "inside"]:
        m = re.search(rf"(?i)\s+{prep}\s+(.+)$", rest)
        if not m:
            continue
        candidate_app = m.group(1).strip()
        candidate_query = rest[:m.start()].strip()
        if not candidate_query or not candidate_app or not _plausible_app(candidate_app):
            continue
        if candidate_app.lower() in {"me", "us", "him", "her", "them", "myself"}:
            continue
        rest, app = candidate_query, candidate_app
        break
    query = _clean_media_query(rest)
    if not query:
        return None
    return PlayMedia(query, app)


def _call(command: str, lower: str):
    if not _CALL_VERB.search(lower):
        return None
    rest = _CALL_VERB.sub("", command).strip()
    rest = _TO_PREFIX.sub("", rest).strip()
    if not rest:
        return None
    sim = None
    m = re.search(r"(?i)\s+(?:with|on|using)\s+(?:(?:sim|slot|line)\s*([12])|(?:the\s+)?(first|1st|second|2nd)\s+sim)\s*$", rest)
    if m:
        sim = int(m.group(1)) if m.group(1) else (1 if m.group(2).lower() in {"first", "1st"} else 2)
        rest = rest[:m.start()].strip()
    rest = re.sub(r"(?i)^(?:a call to|call to)\s+", "", rest)
    rest = re.sub(r"(?i)\s+(?:please|now|for me)$", "", rest).strip()
    if not rest:
        return None
    return CallContact(rest, sim)


def _reminder(command: str, lower: str):
    starts = lower.startswith(("remind me", "remind us", "set a reminder", "set reminder",
                               "create a reminder", "reminder", "don't let me forget",
                               "dont let me forget"))
    if not starts:
        return None
    rest = re.sub(r"(?i)^(?:don't let me forget|dont let me forget|remind me|remind us|set a reminder|set reminder|create a reminder|reminder)\s*", "", command)
    rest = re.sub(r"(?i)^(?:to|that|about|for)\s+", "", rest).strip()
    if not rest:
        return None
    m = re.search(r"(?i)^(.+?)\s+to\s+(.+)$", rest)
    if m:
        maybe_time, text = m.group(1).strip(), m.group(2).strip()
        if text and parse_time(maybe_time) is not None and len(maybe_time.split()) <= 4:
            return SetReminder(text, maybe_time)
    m = re.search(r"(?i)^(.+?)\s+(?:at|by|on|in|before)\s+(.+)$", rest)
    if m:
        text, time_text = m.group(1).strip(), m.group(2).strip()
        if text and parse_time(time_text) is not None:
            return SetReminder(text, time_text)
    return SetReminder(rest, "")


def _alarm(command: str, lower: str):
    if "alarm" not in lower:
        return None
    if "alarm clock app" in lower or lower == "open alarm":
        return None
    m = re.search(r"(?i)^(?:(?:set|create|make|put|schedule|add)\s+)?(?:an?\s+)?alarms?\s*(?:clock)?\s*(?:in\s+clock\s+)?(?:for|at|to|of|in)?\s*(.+)$", command)
    if not m:
        return None
    value = re.sub(r"(?i)^(?:in|at|for)\s+", "", m.group(1).strip())
    value = re.sub(r"(?i)\s+(?:please|in clock|on clock)$", "", value).strip()
    if not value or parse_time(value) is None:
        return None
    return SetAlarm(value)


def _wake_me(command: str, lower: str):
    if not lower.startswith("wake me"):
        return None
    m = re.search(r"(?i)^wake me(?: up)?\s*(?:at|by|around|to)?\s*(.+)$", command)
    if not m:
        return None
    value = m.group(1).strip()
    if not value or parse_time(value) is None:
        return None
    return SetAlarm(value)


def _timer(command: str, lower: str):
    if "timer" not in lower and "countdown" not in lower and "count down" not in lower:
        return None
    m = re.search(r"(?i)^(?:(?:set|start|create|put|make)\s+)?(?:a\s+|the\s+)?(?:timer|countdown|count down)\s*(?:for|of|to|at)?\s*(.+)$", command)
    if not m:
        return None
    value = m.group(1).strip()
    if not value or parse_duration(value) is None:
        return None
    return SetTimer(value)


def _save_contact(command: str, lower: str):
    if not lower.startswith(("save", "add", "create", "store")):
        return None
    if "contact" not in lower and "number" not in lower and " as " not in lower:
        return None
    patterns = [
        r"(?i)^(?:save|add|store|create)\s+(.+?)\s+as\s+(?:a\s+)?(?:contact\s+)?(?:named\s+)?(.+)$",
        r"(?i)^(?:save|add|store|create)\s+(.+?)['’]s\s+number\s+(?:as\s+)?(.+)$",
        r"(?i)^(?:save|add|store|create)\s+(?:contact\s+)?(.+?)\s+(?:with\s+)?number\s+(.+)$",
    ]
    number_pattern = re.compile(r"[+0-9()\-\s]{7,}")
    for pattern in patterns:
        m = re.search(pattern, command)
        if not m:
            continue
        first, second = m.group(1).strip(), m.group(2).strip()
        if not first or not second:
            continue
        first_is_number = bool(number_pattern.fullmatch(first))
        second_is_number = bool(number_pattern.fullmatch(second))
        if first_is_number and not second_is_number:
            return SaveContact(second, first)
        if second_is_number and not first_is_number:
            return SaveContact(first, second)
    return None


def _navigate(command: str, lower: str):
    m = re.search(r"(?i)^(?:navigate to|navigate|directions to|get directions to|show me directions to|show directions to|map out|route to|drive to|walk to|find the way to|how do i get to)\s+(.+)$", command)
    if not m:
        return None
    destination = m.group(1).strip()
    return NavigateTo(destination) if destination else None


_SETTINGS_PAGES = ["wi fi", "wifi", "wi-fi", "bluetooth", "display", "sound", "battery",
                   "storage", "notification", "notifications", "accessibility", "security",
                   "location", "apps", "about phone", "developer options", "network",
                   "data usage", "hotspot", "tethering", "airplane mode", "brightness",
                   "wallpaper", "lock screen", "date and time", "language",
                   "connected devices", "digital wellbeing", "privacy", "password",
                   "accounts", "default apps", "assistant", "voice", "gesture", "nfc",
                   "sim", "memory", "theme", "font", "ringtone", "do not disturb", "focus"]


def _settings(command: str, lower: str):
    if re.match(r"(?i)^(?:open|go to|show|launch|start|take me to|enter)?\s*(?:the\s+)?settings(?:\s+app)?$", command.strip()):
        return OpenSettingsPage("")
    if "setting" not in lower:
        return None
    target = re.sub(r"(?i)^(?:open|go to|show|launch|start|take me to|enter)\s+", "", command)
    target = re.sub(r"(?i)\s+in\s+settings\s*$", "", target)
    target = re.sub(r"(?i)\s+settings\s*$", "", target)
    target = re.sub(r"(?i)^settings\s*[>»\-]?\s*", "", target).strip()
    if not target or target.lower() == "settings":
        return OpenSettingsPage("")
    return OpenSettingsPage(target)


def _web_search(command: str, lower: str):
    m = re.search(r"(?i)^(?:search with google|search google for|search on google|google search for|google search|google|search the web for|search online for|web search|look up online)\s+(.+)$", command)
    if not m:
        return None
    query = m.group(1).strip()
    return WebSearch(query) if query else None


def _wants_first_result(lower: str) -> bool:
    return any(p in lower for p in ["first result", "first one", "top result",
                                    "and open it", "and play it", "and click it"])


def _app_search(command: str, lower: str):
    if not any(w in lower for w in ["search", "find", "look up", "look for"]):
        return None
    m = re.search(rf"(?i)^(?:open|launch|go to|go into|start)\s+(.+?)\s+and\s+{_SEARCH_VERB}\s+(.+)$", command)
    if m:
        app, query = m.group(1).strip(), m.group(2).strip()
        if app and query:
            return SearchInApp(query, app, _wants_first_result(lower))
    m = re.search(rf"(?i)^{_SEARCH_VERB}\s+(.+?)\s+(?:on|in|inside|using|via|within)\s+(.+)$", command)
    if m:
        query, app = m.group(1).strip(), m.group(2).strip()
        if query and app and _plausible_app(app):
            return SearchInApp(query, app, _wants_first_result(lower))
    m = re.search(rf"(?i)^(.+?)\s+(?:search for|search|look up|look for|find)\s+(.+)$", command)
    if m:
        app, query = m.group(1).strip(), m.group(2).strip()
        app_lower = app.lower()
        if app and query and len(app.split()) <= 4 and not app_lower.startswith(("search", "find", "look")) \
                and app_lower not in {"google", "the web"}:
            return SearchInApp(query, app, _wants_first_result(lower))
    m = re.search(rf"(?i)^{_SEARCH_VERB}\s+(.+)$", command)
    if m:
        query = m.group(1).strip()
        if query:
            return SearchInApp(query, None, _wants_first_result(lower))
    return None


def _open(command: str, lower: str):
    m = re.search(r"(?i)^(?:open|open up|launch|start|load|bring up|fire up|run|go to|go into|enter|switch to)\s+(.+)$", command)
    if not m:
        return None
    target = m.group(1).strip()
    if not target:
        return None
    target = re.sub(r"(?i)^(?:the|my|an|a)\s+", "", target)
    target = re.sub(r"(?i)\s+(?:app|application)$", "", target).strip()
    if not target:
        return None
    sectioned = re.search(r"(?i)^(.+?)\s+(?:in|on|inside|within|from)\s+(.+)$", target)
    if sectioned:
        section, app = sectioned.group(1).strip(), sectioned.group(2).strip()
        if section and app and _plausible_app(app):
            return OpenAppSection(app, section)
    return OpenApp(target)


def _sms_composer(command: str, lower: str):
    m = re.search(r"(?i)^(?:text|sms|send a text to|send an sms to)\s+(.+)$", command)
    if not m:
        return None
    rest = m.group(1).strip()
    if not rest:
        return None
    recipient, body = rest, ""
    for marker in _BODY_MARKERS:
        idx = _index_of_marker(rest, marker)
        if idx <= 0:
            continue
        recipient = rest[:idx].strip()
        body = rest[idx + len(marker):].strip()
        break
    if not recipient or not _plausible_recipient(recipient):
        return None
    return OpenSmsComposer(recipient, body)


def _leading_app_clause(text: str):
    m = re.search(r"(?i)^(?:on|in|inside|using|via|through)\s+(.+?)\s*,\s*(.+)$", text)
    if not m:
        return None
    app, rest = m.group(1).strip(), m.group(2).strip()
    if not app or not rest or len(app.split()) > 4:
        return None
    return app, rest


def _with_app_hint(command, app: str):
    if isinstance(command, SendMessage) and not command.app:
        return SendMessage(command.recipient, app, command.body)
    if isinstance(command, PlayMedia) and not command.app:
        return PlayMedia(command.query, app)
    if isinstance(command, SearchInApp) and not command.app:
        return SearchInApp(command.query, app, command.select_first)
    return command


def interpret_core(command: str):
    lower = command.lower()
    return (_media_control(lower) or _reminder(command, lower) or _alarm(command, lower)
            or _wake_me(command, lower) or _timer(command, lower) or _save_contact(command, lower)
            or _message(command, lower) or _play(command, lower) or _call(command, lower)
            or _navigate(command, lower) or _settings(command, lower) or _web_search(command, lower)
            or _app_search(command, lower) or _open(command, lower) or _sms_composer(command, lower))


def interpret(raw: str):
    """Interpret one utterance into a command, or None when not understood."""
    command = normalize(raw)
    if not command:
        return None
    leading = _leading_app_clause(command)
    remainder = leading[1] if leading else command
    result = interpret_core(remainder)
    if result is None:
        return None
    return _with_app_hint(result, leading[0]) if leading else result


# ------------------------------------------------------------------ Windows verbs

_WINDOWS_SETTINGS = {
    "wifi": ("Wi-Fi", "ms-settings:network-wifi"), "wi-fi": ("Wi-Fi", "ms-settings:network-wifi"),
    "wi fi": ("Wi-Fi", "ms-settings:network-wifi"), "bluetooth": ("Bluetooth", "ms-settings:bluetooth"),
    "dnd": ("Do Not Disturb", "ms-settings:notifications"),
    "do not disturb": ("Do Not Disturb", "ms-settings:notifications"),
    "notifications": ("notifications", "ms-settings:notifications"),
    "display": ("display", "ms-settings:display"), "brightness": ("display", "ms-settings:display"),
    "sound": ("sound", "ms-settings:sound"), "volume": ("sound", "ms-settings:sound"),
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


def _percentage(text: str) -> int | None:
    text = text.strip()
    if text.isdigit():
        return int(text)
    words = text.replace("-", " ").split()
    small = {w: i for i, w in enumerate(
        "zero one two three four five six seven eight nine ten eleven twelve thirteen "
        "fourteen fifteen sixteen seventeen eighteen nineteen".split())}
    tens = {w: v for w, v in zip("twenty thirty forty fifty sixty seventy eighty ninety".split(),
                                 range(20, 100, 10))}
    if text in {"one hundred", "a hundred", "hundred"}:
        return 100
    if len(words) == 1:
        return small.get(words[0], tens.get(words[0]))
    if len(words) == 2 and words[0] in tens and words[1] in small and 0 < small[words[1]] < 10:
        return tens[words[0]] + small[words[1]]
    return None


def interpret_windows(raw: str):
    """Windows-native verbs the Android grammar has no equivalent for."""
    text = normalize(raw)
    low = text.lower()
    if low in {"help", "what can you do", "capabilities"}:
        return Help()
    if low in {"speech status", "offline speech status"}:
        return SpeechStatus()
    if low in {"inspect screen", "read screen", "show controls"}:
        return InspectScreen()
    if low == "list apps":
        return ListApps()
    if low in {"volume status"}:
        return VolumeStatus()
    if low in {"brightness status"}:
        return BrightnessStatus()
    if low in {"mute", "unmute"}:
        return SetMute(low == "mute")
    if low in {"cancel", "stop", "never mind"}:
        return Cancel()
    m = re.fullmatch(r"set (volume|brightness)(?: to)? (.+?)(?: percent|%)?", low)
    if m:
        value = _percentage(m.group(2))
        if value is None or not 0 <= value <= 100:
            return None
        return SetVolume(value) if m.group(1) == "volume" else SetBrightness(value)
    m = re.fullmatch(r"(?:click|tap|press) (.+)", text, re.I)
    if m:
        return ClickControl(m.group(1))
    m = re.fullmatch(r"type (.+)", text, re.I)
    if m:
        return TypeText(m.group(1))
    if low in {"scroll up", "scroll down"}:
        return Scroll(low.split()[-1])
    m = re.fullmatch(r"find app (.+)", text, re.I)
    if m:
        return FindApp(m.group(1))
    # Settings toggles on Windows open the settings page (no safe direct toggle).
    m = re.fullmatch(r"(?:turn|switch) (?:on|off) (.+)|(?:enable|disable) (.+)|(.+?) (?:on|off)", low)
    if m:
        target = (m.group(1) or m.group(2) or m.group(3) or "").strip()
        if target in _WINDOWS_SETTINGS:
            return OpenSettingsPage(target)
    m = re.fullmatch(r"(?:find|search) settings for (.+)", low)
    if m:
        return OpenSettingsPage(m.group(1))
    m = re.fullmatch(r"(?:open|show|go to) (.+?) settings", low)
    if m:
        return OpenSettingsPage(m.group(1))
    return None


def interpret_any(raw: str):
    """Windows verbs first, then the shared Android grammar."""
    text = normalize(raw)
    if text.endswith("?"):
        return None  # A question never authorizes an action.
    if re.match(r"(?i)^(?:don't|do not|never|stop|cancel)\b", text) and \
            not re.fullmatch(r"do not disturb (?:on|off)", text.lower()):
        return Cancel()
    return interpret_windows(raw) or interpret(raw)


# ----------------------------------------------------------------------- planner

_ACTION_VERBS = (r"(?:open|go|search|find|play|listen|send|message|call|dial|set|turn|enable|"
                 r"disable|increase|decrease|lock|unlock|read|show|dismiss|clear|tap|press|"
                 r"type|scroll|navigate|restart|power|switch|click|mute|unmute|inspect|list)")
_COMPOUND_APP_GATE = re.compile(r"(?i)^(?:open|go\s+(?:into|to))\s+.+?\s+and\s+(?:play|listen|search|find|message|send)\b")
_THEN_BOUNDARY = re.compile(rf"(?i)\s+then\s+(?={_ACTION_VERBS}\b)")
_AND_BOUNDARY = re.compile(rf"(?i)\s+and\s+(?={_ACTION_VERBS}\b)")
_MESSAGE_SHAPE = re.compile(r"(?i)^(?:send|message|text|email)\s+.+?\s+(?:that says|saying|to say|that)\s+.+")
_COMPOUND_MESSAGE_SHAPE = re.compile(r"(?i)^open\s+.+?\s+(?:message|send).+\s+(?:that says|saying|to say|that)\s+.+")


def _looks_like_message_body(command: str) -> bool:
    lower = command.lower()
    openers = ("message ", "send a message ", "send message ", "send a text ", "send text ",
               "text ", "tell ", "email ", "send an email ", "send email ", "whatsapp ")
    if any(lower.startswith(o) for o in openers):
        return True
    return bool(_MESSAGE_SHAPE.search(command) or _COMPOUND_MESSAGE_SHAPE.search(command))


def _clean_part(part: str) -> str:
    part = part.strip().strip(",;")
    part = re.sub(r"(?i)[,;]?\s*(?:and|then)$", "", part)
    return part.strip()


def _translate_compound_app(command: str):
    m = re.search(r"(?i)^(?:open|go\s+(?:into|to))\s+(.+?)\s+and\s+(?:play|listen\s+to)\s+(.+)$", command)
    if m:
        app, query = m.group(1).strip(), m.group(2).strip()
        if app and query:
            return [f"open {app}", f"play {query} on {app}"]
    m = re.search(r"(?i)^(?:open|go\s+(?:into|to))\s+(.+?)\s+and\s+(?:search(?:\s+for)?|find)\s+(.+)$", command)
    if m:
        app, query = m.group(1).strip(), m.group(2).strip()
        if app and query:
            return [f"open {app}", f"search {query} on {app}"]
    m = re.search(r"(?i)^(?:open|go\s+(?:into|to))\s+(.+?)\s+and\s+(?:message|send(?:\s+(?:a\s+)?message)?)\s+(.+?)\s+(?:that says|saying|to say|that)\s+(.+?)(?:\s+and\s+send)?$", command)
    if m:
        app, recipient, body = m.group(1).strip(), m.group(2).strip(), m.group(3).strip()
        if app and recipient and body:
            return [f"message {recipient} on {app} saying {body}"]
    return None


def _app_directed_action(command: str):
    m = re.search(r"(?i)^(?:open|launch|go\s+(?:to|into))\s+(.+?)\s+(?:(?:and\s+then|and|then)\s+)?(play|listen to|search for|search|find|text|message)\s+(.+)$", command)
    if not m:
        return None
    app = m.group(1).strip().rstrip(",")
    verb = m.group(2).lower()
    content = m.group(3).strip()
    if verb in {"play", "listen to"}:
        return [f"play {content} on {app}"]
    if verb in {"search", "search for", "find"}:
        return [f"search {content} on {app}"]
    explicit = re.search(r"(?i)^(.+?)\s+(?:that says|saying|to say)\s+(.+)$", content)
    shorthand = re.search(r"(?i)^(\S+)\s+((?:I|I'm|I'll|we|we're|you|please|hi|hello)\b.*)$", content)
    message = explicit or shorthand
    if not message:
        return None
    return [f"message {message.group(1)} on {app} saying {message.group(2)}"]


def _planner_normalize(raw: str) -> str:
    text = re.sub(r"\s+", " ", raw.strip())
    text = re.sub(r"(?i)^(?:(?:hey[ ,]+ema|please|kindly|could you|can you)[ ,]+)+", "", text)
    return text.strip()


def plan_steps(raw: str) -> list[str]:
    """Split a compound request into canonical step strings (Kotlin planner port)."""
    command = _planner_normalize(raw)
    if not command:
        return []
    if _COMPOUND_APP_GATE.search(command):
        translated = _translate_compound_app(command)
        if translated:
            return translated
    directed = _app_directed_action(command)
    if directed:
        return directed
    if not _looks_like_message_body(command):
        then_parts = [_clean_part(p) for p in _THEN_BOUNDARY.split(command)]
        then_parts = [p for p in then_parts if p]
        if len(then_parts) > 1:
            return [step for part in then_parts for step in plan_steps(part)]
    if not _looks_like_message_body(command):
        and_parts = [_clean_part(p) for p in _AND_BOUNDARY.split(command)]
        and_parts = [p for p in and_parts if p]
        if len(and_parts) > 1:
            return [step for part in and_parts for step in plan_steps(part)]
    if not _looks_like_message_body(command):
        semi_parts = [_clean_part(p) for p in command.split(";")]
        semi_parts = [p for p in semi_parts if p]
        if len(semi_parts) > 1:
            return [step for part in semi_parts for step in plan_steps(part)]
    return [command]


def plan_commands(raw: str) -> list | None:
    """Full pipeline: plan steps, interpret each. None if any step is not understood."""
    if len(raw.strip()) > 16000:
        return None
    steps = plan_steps(raw)
    if not steps or len(steps) > 8:
        return None
    for step in steps:
        low_step = step.lower()
        if not low_step.startswith(("type ", "search web for ", "search with google ")) and \
                re.search(r"\b(?:never|don't|do not)\b", low_step) and \
                not re.fullmatch(r"do not disturb (?:on|off)", low_step):
            return [Cancel()]
    commands = []
    for step in steps:
        command = interpret_any(step)
        if command is None:
            return None
        commands.append(command)
    if any(isinstance(c, Cancel) for c in commands):
        return [Cancel()]
    return commands
