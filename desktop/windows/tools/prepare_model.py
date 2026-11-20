"""Build-time only: download/import the SAME SHA-256-pinned English model as Android.

The released desktop application never downloads a model at runtime.
"""
import argparse
import hashlib
from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile

SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
ROOT = "vosk-model-small-en-us-0.15/"
REQUIRED = ("am/final.mdl", "conf/mfcc.conf", "conf/model.conf", "graph/HCLr.fst", "graph/Gr.fst")
SOURCES = (
    "https://github.com/darsantiago/vosk-models-mirror/releases/download/v1/vosk-model-small-en-us-0.15.zip",
    "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
)


def extract_verified(archive: Path, destination: Path):
    if archive.stat().st_size > 45 * 1024 * 1024:
        raise ValueError("Unexpected model archive size")
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise ValueError("Model SHA-256 mismatch; refusing to package it")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as model, tempfile.TemporaryDirectory(dir=destination.parent) as temp:
        for required in REQUIRED:
            if model.getinfo(ROOT + required).file_size <= 0:
                raise ValueError("Empty required model file")
        total = 0
        for info in model.infolist():
            total += info.file_size
            if total > 150 * 1024 * 1024 or not info.filename.startswith(ROOT):
                raise ValueError("Unexpected model contents")
            relative = Path(info.filename[len(ROOT):])
            if relative.is_absolute() or ".." in relative.parts or ":" in str(relative) or "\\" in info.filename:
                raise ValueError("Unsafe archive path")
            if info.is_dir():
                continue
            target = Path(temp) / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with model.open(info) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output)
        # Replace only the generated model directory, never a user-selected arbitrary path.
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(temp, destination)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, help="Use an existing pinned archive without any network request")
    args = parser.parse_args()
    destination = Path(__file__).resolve().parents[1] / "resources/model"
    if args.archive:
        extract_verified(args.archive, destination)
    else:
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp) / "model.zip"
            for url in SOURCES:
                try:
                    with urllib.request.urlopen(url, timeout=60) as source, archive.open("wb") as output:
                        total = 0
                        while chunk := source.read(65536):
                            total += len(chunk)
                            if total > 45 * 1024 * 1024:
                                raise ValueError("Download too large")
                            output.write(chunk)
                    extract_verified(archive, destination)
                    break
                except Exception as exc:
                    print(f"Source unavailable: {type(exc).__name__}")
            else:
                raise SystemExit("Cannot prepare the pinned model. No model-less release will be built.")
    print("PASS: pinned offline English model prepared")


if __name__ == "__main__":
    main()
