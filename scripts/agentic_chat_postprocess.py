#!/usr/bin/env python3
"""agentic_chat_postprocess.py — clean agent-produced commit messages and
reports before they leave the session (0916a0 lineage, directive P6).

Removes pasted-directive / original-conversation residue, normalizes
Korean text (NFC, whitespace), applies the report template check, and
hard-blocks on secret patterns (match names only, never the value).

Actions:
  clean --input <file> [--output <file>] [--kind commit|report]
        [--apply-template]
      commit: strip residue lines, normalize, collapse blank runs
      report: same + report standard-section gap list
              (## 요약/## 변경/## 검증/## 잔여); --apply-template appends
              missing section stubs at the end
  scan --input <file>      secret-pattern scan only

Exit codes: 0 ok, 2 usage/io, 5 secret pattern detected (no output text).
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
import unicodedata

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

SCHEMA = "awx.agentic-chat-postprocess.v1"
REPORT_SECTIONS = ("## 요약", "## 변경", "## 검증", "## 잔여")

RESIDUE_RES = [
    re.compile(r"^\s*(user|assistant|human|system|codex|grok|devin|cline)"
               r"\s*[:：]\s", re.IGNORECASE),
    re.compile(r"^\s*>\s*\S"),                       # quoted conversation
    re.compile(r"^(?:\s*@[A-Za-z0-9_-]+){2,}\s*$"),  # @mention piles
    re.compile(r"^\s*#{0,6}\s*(지시서?|instruction|directive)s?\s*[:：]?\s*$",
               re.IGNORECASE),                        # bare directive header
    re.compile(r"^\s*---+\s*(BEGIN|END)\b.*$", re.IGNORECASE),
]

SECRET_RES = {
    "aws-access-key": re.compile(r"AKIA[0-9A-Z]{16}"),
    "private-key-block": re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY"),
    "openai-style-key": re.compile(r"\bsk-[A-Za-z0-9_-]{20,}"),
    "supabase-pat": re.compile(r"\bsbp_[A-Za-z0-9_-]{10,}"),
    "bearer-token": re.compile(r"Bearer\s+[A-Za-z0-9._~+-]{20,}"),
    "generic-secret-assign": re.compile(
        r"(?i)(api[_-]?key|secret|token|password)\s*[:=]\s*"
        r"['\"]?[A-Za-z0-9._~/+=-]{12,}"),
}


def scan_secrets(text: str) -> list[str]:
    return sorted(name for name, rx in SECRET_RES.items()
                  if rx.search(text))


def strip_residue(text: str) -> tuple[str, int]:
    kept, removed = [], 0
    for line in text.splitlines():
        if any(rx.search(line) for rx in RESIDUE_RES):
            removed += 1
            continue
        kept.append(line)
    return "\n".join(kept), removed


def normalize_korean(text: str) -> str:
    text = unicodedata.normalize("NFC", text)
    lines = [re.sub(r"[ \t]+$", "", line) for line in text.split("\n")]
    out, blank = [], 0
    for line in lines:
        if line.strip():
            blank = 0
            out.append(line)
        else:
            blank += 1
            if blank <= 1:
                out.append("")
    return "\n".join(out).strip("\n") + "\n"


def template_gaps(text: str) -> list[str]:
    return [sec for sec in REPORT_SECTIONS if sec not in text]


def cmd_clean(args) -> tuple[dict, int]:
    src = Path(args.input)
    if not src.is_file():
        return {"schemaVersion": SCHEMA, "ok": False,
                "error": "input-missing"}, 2
    text = src.read_text(encoding="utf-8-sig", errors="replace")
    hits = scan_secrets(text)
    if hits:
        return {"schemaVersion": SCHEMA, "ok": False,
                "blocked": "secret-pattern", "patterns": hits}, 5

    cleaned, removed = strip_residue(text)
    cleaned = normalize_korean(cleaned)
    result = {"schemaVersion": SCHEMA, "ok": True, "kind": args.kind,
              "removedLines": removed}
    if args.kind == "report":
        gaps = template_gaps(cleaned)
        result["templateGaps"] = gaps
        if args.apply_template and gaps:
            cleaned = cleaned.rstrip("\n") + "\n\n" + \
                "\n\n".join(gaps) + "\n"
            result["appliedTemplate"] = True
    if args.output:
        Path(args.output).write_text(cleaned, encoding="utf-8")
        result["output"] = str(args.output)
    else:
        result["cleaned"] = cleaned
    return result, 0


def cmd_scan(args) -> tuple[dict, int]:
    src = Path(args.input)
    if not src.is_file():
        return {"schemaVersion": SCHEMA, "ok": False,
                "error": "input-missing"}, 2
    text = src.read_text(encoding="utf-8-sig", errors="replace")
    hits = scan_secrets(text)
    return {"schemaVersion": SCHEMA, "ok": not hits,
            "blocked": "secret-pattern" if hits else None,
            "patterns": hits}, (5 if hits else 0)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    for name in ("clean", "scan"):
        p = sub.add_parser(name)
        p.add_argument("--input", required=True)
        if name == "clean":
            p.add_argument("--output")
            p.add_argument("--kind", default="commit",
                           choices=("commit", "report"))
            p.add_argument("--apply-template", action="store_true")
    args = parser.parse_args(argv)
    result, code = (cmd_clean(args) if args.action == "clean"
                    else cmd_scan(args))
    print(json.dumps(result, ensure_ascii=True))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
