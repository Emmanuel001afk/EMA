#!/usr/bin/env python3
"""Expose actionable Gradle errors without redirected logs/artifact storage."""
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def failure_excerpt(text: str) -> str:
    lines = text.splitlines()
    # Appended logs contain earlier successful tasks. Prioritize the LAST actual
    # failure, not the first '> Task' lines (GitHub limits annotations per step).
    starts = [i for i, line in enumerate(lines) if line.strip() == "* What went wrong:"]
    cause = []
    if starts:
        for line in lines[starts[-1]:]:
            if line.startswith(("* Try:", "* Exception is:", "* Get more help")):
                break
            cause.append(line)
    compiler = [line for line in lines if re.search(r"(^e: |^ERROR: |^error: |error: resource|Android resource linking failed|Missing class )", line)]
    selected = cause + compiler[-15:]
    if not selected:
        selected = lines[-50:]
    return "\n".join(dict.fromkeys(selected))[:12000]


def test_failure_excerpt(results_dir: Path = Path("app/build/test-results/testDebugUnitTest")) -> str:
    failures = []
    for report in results_dir.glob("TEST-*.xml"):
        try:
            suite = ET.parse(report).getroot()
        except (OSError, ET.ParseError):
            continue
        for case in suite.iter("testcase"):
            failure = next((node for node in case if node.tag in {"failure", "error"}), None)
            if failure is not None:
                detail = (failure.attrib.get("message") or failure.text or "").strip()
                failures.append(f"{case.attrib.get('classname', '')}.{case.attrib.get('name', '')}: {detail}")
    return "\n".join(failures)[:8000]


def report(path: Path) -> None:
    excerpt = failure_excerpt(path.read_text(errors="replace"))
    tests = test_failure_excerpt()
    if tests:
        excerpt += "\nFailed unit tests:\n" + tests
    escaped = excerpt.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    # One annotation preserves the useful cause under GitHub's per-step limit.
    print(f"::error title=Gradle build failure::{escaped}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as output:
            output.write("## Gradle failure diagnostics\n\n```text\n" + excerpt.replace("```", "'''") + "\n```\n")


if __name__ == "__main__":
    report(Path(sys.argv[1]))
