#!/usr/bin/env python3
"""Fail release checks if an APK lacks EMA's complete, pinned offline model."""
import hashlib
import sys
import tempfile
import zipfile
from pathlib import Path

EXPECTED_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
MODEL_ROOT = "vosk-model-small-en-us-0.15/"
REQUIRED = ("am/final.mdl", "conf/mfcc.conf", "conf/model.conf", "graph/HCLr.fst", "graph/Gr.fst")
LICENSES = ("assets/licenses/vosk-Apache-2.0.txt", "assets/licenses/EMA-WAKE-MODEL-NOTICE.txt")


def verify_apk(path: Path, expected_sha256: str = EXPECTED_SHA256) -> None:
    with zipfile.ZipFile(path) as apk:
        for name in LICENSES:
            if not apk.read(name).strip():
                raise ValueError(f"Empty license notice: {name}")
        digest = hashlib.sha256()
        with tempfile.TemporaryFile() as model:
            with apk.open("assets/ema-wake-model.zip") as source:
                size = 0
                while chunk := source.read(65536):
                    size += len(chunk)
                    if size > 45 * 1024 * 1024:
                        raise ValueError("Unexpected embedded model size")
                    digest.update(chunk)
                    model.write(chunk)
            if digest.hexdigest() != expected_sha256:
                raise ValueError("Embedded wake model checksum mismatch")
            model.seek(0)
            with zipfile.ZipFile(model) as archive:
                for relative in REQUIRED:
                    if archive.getinfo(MODEL_ROOT + relative).file_size == 0:
                        raise ValueError(f"Empty model file: {relative}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("Usage: verify_bundled_wake_model.py <debug.apk> [release.apk]")
    for filename in sys.argv[1:]:
        verify_apk(Path(filename))
        print(f"PASS {filename}: built-in wake model, checksum, required files and licenses")
