#!/usr/bin/env python3
"""Expose useful Gradle errors even when artifact quota/log downloads fail."""
import os
import re
import sys
from pathlib import Path


def report(path: Path) -> None:
    text = path.read_text(errors="replace")
    lines = text.splitlines()
    errors = [line for line in lines if re.search(r"(^e: |^> |^\* What went wrong:|^Execution failed|^Could not|^Script compilation|^FAILURE:)", line)]
    if not errors:
        errors = lines[-40:]
    # The annotations endpoint remains accessible when Actions' redirected logs
    # or exhausted artifact storage are not. Escape workflow command data.
    for line in errors[:30]:
        escaped = line[:3000].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error title=Gradle build failure::{escaped}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        excerpt = "\n".join(errors[:60]).replace("```", "'''")
        with open(summary, "a") as output:
            output.write("## Gradle failure diagnostics\n\n```text\n" + excerpt[:16000] + "\n```\n")


if __name__ == "__main__":
    report(Path(sys.argv[1]))
