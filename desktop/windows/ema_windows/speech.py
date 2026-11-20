"""Local microphone -> Vosk English text. Audio is never uploaded or written to disk."""
import audioop
from collections import deque
import json
from pathlib import Path
import queue
import sys
import threading
import time


def resource_root():
    return Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[1])) / "resources"


class OfflineSpeech:
    def __init__(self):
        self._model = None
        self.stop = threading.Event()
        self._lock = threading.Lock()
        self.state = "Bundled English model; not loaded yet. Microphone activates only when Listen is pressed."

    def status(self):
        return self.state

    def listen(self, emit, dictation=False, device=None):
        if not self._lock.acquire(blocking=False):
            raise RuntimeError("A speech session is already running.")
        try:
            import sounddevice as sd
            from vosk import Model, KaldiRecognizer, SetLogLevel
            if self._model is None:
                model_path = resource_root() / "model"
                if not (model_path / "am/final.mdl").is_file():
                    raise RuntimeError("Offline model missing. Use the complete Windows release or run tools/prepare_model.py before starting from source.")
                self.state = "Loading bundled English model locally…"
                emit("status", self.state)
                SetLogLevel(-1)
                self._model = Model(str(model_path))
            if self.stop.is_set():
                return ""
            try:
                sd.check_input_settings(device=device, channels=1, dtype="int16", samplerate=16000)
                rate = 16000
            except Exception:
                rate = int(sd.query_devices(device, "input")["default_samplerate"])
                sd.check_input_settings(device=device, channels=1, dtype="int16", samplerate=rate)
            decoder = KaldiRecognizer(self._model, 16000)
            decoder.SetWords(True)
            chunks = queue.Queue(maxsize=100)
            overflow = threading.Event()
            def capture(indata, frames, timing, status):
                if status:
                    overflow.set()
                try:
                    chunks.put_nowait(bytes(indata))
                except queue.Full:
                    overflow.set()
            segments = []
            last_preview = ""
            resample_state = None
            start = time.monotonic()
            last_voice = None
            voiced_frames = 0
            energies = deque(maxlen=8)
            self.state = "Listening locally. Stop to finish; no audio is stored."
            emit("status", self.state)
            with sd.RawInputStream(device=device, samplerate=rate, channels=1, dtype="int16", blocksize=max(1, int(rate * .1)), callback=capture):
                while not self.stop.is_set():
                    now = time.monotonic()
                    if now - start > (90 if dictation else 30):
                        break
                    try:
                        raw = chunks.get(timeout=.2)
                    except queue.Empty:
                        if now - start > 8 and last_voice is None:
                            break
                        continue
                    if overflow.is_set():
                        raise RuntimeError("Microphone audio was interrupted or overflowed. No command was executed; choose another microphone or retry in a quieter app session.")
                    if rate != 16000:
                        raw, resample_state = audioop.ratecv(raw, 2, 1, rate, 16000, resample_state)
                    rms = audioop.rms(raw, 2) if raw else 0
                    energies.append(rms)
                    if rms > 220:
                        last_voice = now
                        voiced_frames += 1
                    if decoder.AcceptWaveform(raw):
                        segment = json.loads(decoder.Result()).get("text", "").strip()
                        if segment:
                            segments.append(segment)
                    partial = json.loads(decoder.PartialResult()).get("partial", "").strip()
                    preview = " ".join(segments + ([partial] if partial else []))
                    if preview != last_preview:
                        emit("partial", preview)
                        last_preview = preview
                    if last_voice is None and now - start > 8:
                        break
                    # Bare target/content introducers need more patience than completed utterances.
                    incomplete = preview.split()[-1:] in [[w] for w in ("to", "for", "saying", "type", "open", "set", "then", "and")]
                    silence = 3.5 if incomplete else (2.8 if dictation else 1.5)
                    if last_voice is not None and now - last_voice >= silence and preview:
                        break
            tail = json.loads(decoder.FinalResult()).get("text", "").strip()
            text = " ".join(segments + ([tail] if tail else []))
            if voiced_frames < 2 or not text:
                raise RuntimeError("No reliable speech was captured. Check Windows microphone privacy, input device and microphone level.")
            self.state = "Offline English engine ready. Last transcript is local; review it before executing."
            return text
        except Exception:
            self.state = "Speech unavailable for this turn. Typed commands still work; check microphone/model diagnostics."
            raise
        finally:
            self._lock.release()
