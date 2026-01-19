#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""build_error_mitigator.py

Small, idempotent helper that applies known *build-error* mitigations based on
patterns matched in a Gradle build log.

Current scope (by design):
  - Gradle build file patching (e.g., add Lombok/FindBugs annotations) when a
    known pattern is detected.

Usage:
  python3 src/scripts/build_error_mitigator.py src/.internal/build_error_patterns/patterns.json build.log
"""

from __future__ import annotations

import glob
import json
import os
import re
import sys
from typing import Any, Dict, List


def load_patterns(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def read_text(path: str) -> str:
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        return f.read()


def write_text(path: str, content: str) -> None:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def patch_groovy(txt: str, injects: List[str]) -> str:
    """Inject dependency snippets into a Groovy build.gradle file."""
    if not injects:
        return txt
    if any(s in txt for s in injects):
        return txt

    lines = txt.splitlines()
    dep_idx = None
    for i, line in enumerate(lines):
        if re.match(r"\s*dependencies\s*\{", line):
            dep_idx = i
            break

    snippet = ["  // injected by build_error_mitigator.py"] + [f"  {s}" for s in injects]
    if dep_idx is not None:
        lines[dep_idx + 1 : dep_idx + 1] = snippet
    else:
        lines.append("")
        lines.append("dependencies {")
        lines.extend(snippet)
        lines.append("}")

    # keep encoding consistent across environments
    if "tasks.withType(JavaCompile)" not in txt:
        lines.append("")
        lines.append("tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8' }")

    return "\n".join(lines) + "\n"


def patch_kts(txt: str, injects: List[str]) -> str:
    """Inject dependency snippets into a Kotlin build.gradle.kts file."""
    if not injects:
        return txt
    if any(s in txt for s in injects):
        return txt

    lines = txt.splitlines()
    dep_idx = None
    for i, line in enumerate(lines):
        if re.match(r"\s*dependencies\s*\{", line):
            dep_idx = i
            break

    snippet = ["  // injected by build_error_mitigator.py"] + [f"  {s}" for s in injects]
    if dep_idx is not None:
        lines[dep_idx + 1 : dep_idx + 1] = snippet
    else:
        lines.append("")
        lines.append("dependencies {")
        lines.extend(snippet)
        lines.append("}")

    if "tasks.withType<JavaCompile>()" not in txt:
        lines.append("")
        lines.append('tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }')

    return "\n".join(lines) + "\n"


def main(argv: List[str]) -> int:
    if len(argv) < 3:
        print("Usage: build_error_mitigator.py <patterns.json> <build.log>", file=sys.stderr)
        return 2

    patterns_json = argv[1]
    build_log = argv[2]

    db = load_patterns(patterns_json)
    log_txt = read_text(build_log)

    applied: List[Dict[str, Any]] = []

    for p in db.get("patterns", []):
        match_any = p.get("match_any", [])
        if not match_any:
            continue

        if not any(re.search(rx, log_txt, re.IGNORECASE | re.MULTILINE) for rx in match_any):
            continue

        for act in p.get("actions", []):
            if act.get("type") != "patch_gradle":
                continue

            targets = act.get("targets", ["**/build.gradle", "**/build.gradle.kts"])
            injects_groovy = act.get("inject", {}).get("groovy", [])
            injects_kts = act.get("inject", {}).get("kts", [])

            paths: List[str] = []
            for t in targets:
                paths.extend(glob.glob(t, recursive=True))

            for path in sorted(set(paths)):
                try:
                    txt = read_text(path)
                    if path.endswith(".kts"):
                        new_txt = patch_kts(txt, injects_kts)
                    else:
                        new_txt = patch_groovy(txt, injects_groovy)

                    if new_txt != txt:
                        write_text(path, new_txt)
                        applied.append({"pattern": p.get("id"), "file": path})
                except Exception as e:
                    applied.append({"pattern": p.get("id"), "file": path, "error": str(e)})

    out = {
        "applied": applied,
        "patterns_considered": [p.get("id") for p in db.get("patterns", [])],
    }

    # Keep a latest report under src/ for quick inspection (matches README.buildfix.md)
    os.makedirs("src", exist_ok=True)
    with open("src/AUTO_BUILD_FIX_REPORT__latest.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)

    print(json.dumps(out, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
