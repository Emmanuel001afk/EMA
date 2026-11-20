"""Preserve installed dependency notices alongside the portable application."""
import importlib.metadata
from pathlib import Path
import shutil
import sys


def collect(destination):
    destination.mkdir(parents=True, exist_ok=True)
    versions = []
    for package in importlib.metadata.distributions():
        name = package.metadata.get("Name", "unknown")
        safe_name = "".join(c for c in name if c.isalnum() or c in "-_.")
        out = destination / safe_name
        out.mkdir(exist_ok=True)
        versions.append(f"{name}=={package.version}")
        (out / "METADATA.txt").write_text(package.read_text("METADATA") or str(package.metadata), encoding="utf-8")
        for relative in package.files or []:
            if relative.name.lower().startswith(("license", "copying", "notice")):
                source = Path(package.locate_file(relative))
                if source.is_file() and source.stat().st_size < 2_000_000:
                    target = out / ("_".join(relative.parts).replace(":", "_"))
                    shutil.copyfile(source, target)
    (destination / "installed-versions.txt").write_text("\n".join(sorted(versions)), encoding="utf-8")
    python_license = Path(sys.base_prefix) / "LICENSE.txt"
    if python_license.is_file():
        shutil.copyfile(python_license, destination / "PYTHON-LICENSE.txt")
    for path in (Path(sys.base_prefix) / "tcl").glob("*/license.terms"):
        shutil.copyfile(path, destination / f"{path.parent.name}-license.terms")


if __name__ == "__main__":
    collect(Path(sys.argv[1]) / "dependency-licenses")
