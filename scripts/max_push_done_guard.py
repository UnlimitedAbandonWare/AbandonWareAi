"""Warn when a completion sentence treats a full suite or admin-browser scenarios as Done.

Pass the completion sentence. Do not point this at PLUGIN_ROLE_MAP.md.
Lines that already say the item is forbidden are ignored.
Exit 0 means the sentence is clear. Exit 2 means the sentence is a forbidden Done claim.
"""
from __future__ import annotations

import argparse
import json
import re
import sys

SCHEMA = "awx.max-push.done-guard.v1"
ALLOW = ("하지", "금지", "NOT_RUN", "≠")


def claim_blocked(text: str) -> list[str]:
    hits = []
    lines = text.splitlines() or [text]
    for line in lines:
        if any(mark in line for mark in ALLOW):
            continue
        full_suite = bool(re.search(r"full suite", line, re.I)) and bool(
            re.search(r"\bDone\b|통과", line))
        if full_suite or "전체 검증 통과" in line or re.search(r"전체\s*suite\s*=\s*done", line, re.I):
            hits.append("full-suite-done")
        admin = bool(re.search(r"admin", line, re.I)) and "5" in line and bool(
            re.search(r"\bDone\b|완료", line))
        if admin:
            hits.append("admin-browser-done")
    return list(dict.fromkeys(hits))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--text", required=True)
    args = parser.parse_args(argv)
    hits = claim_blocked(args.text)
    json.dump({"schemaVersion": SCHEMA, "blocked": hits, "ok": not hits}, sys.stdout, ensure_ascii=False)
    sys.stdout.write("\n")
    return 2 if hits else 0


if __name__ == "__main__":
    raise SystemExit(main())
