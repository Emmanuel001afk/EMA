"""Fail packaging if a worker, model file or required notice is missing."""
from pathlib import Path
import sys
from prepare_model import REQUIRED


def verify(root):
    needed = [root / "EMA.exe", root / "worker/EMA-worker.exe", root / "README.md"]
    resources = root / "_internal/resources"
    needed += [resources / "model" / part for part in REQUIRED]
    needed += [resources / "licenses" / name for name in ("vosk-Apache-2.0.txt", "EMA-WAKE-MODEL-NOTICE.txt", "WINDOWS-NOTICE.txt")]
    for path in needed:
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f"Missing or empty distribution component: {path}")
    print("PASS: complete desktop app with offline model and UI Automation worker")


if __name__ == "__main__":
    verify(Path(sys.argv[1]))
