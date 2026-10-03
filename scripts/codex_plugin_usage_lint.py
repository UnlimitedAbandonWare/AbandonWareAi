#!/usr/bin/env python3
"""codex_plugin_usage_lint.py — validate the mandatory plugin-usage report block.

Contract: `.agents/skills/demo1-codex-plugin-roles/SKILL.md` v2 (report block)
+ `docs/operations/codex-plugin-roles-footer.txt`. Every Codex final report
must carry:

    외부 API: <one-line summary, or 외부 API: 없음>
    PLUGIN_USAGE:
    - <plugin>: USED(이유) | NOT_USED | NOT_RUN(이유) | UNAVAILABLE(이유)
    - GLM: USED(n/3, marker ok) | GLM=SESSION_UNAVAILABLE(이유) | NOT_USED

Usage:
    python -B scripts/codex_plugin_usage_lint.py --report <path> [--json]

Exit codes: 0=OK (warnings allowed), 2=format missing/invalid,
            3=secret-suspect, 4=no input.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

SCHEMA = "awx.codex-plugin-usage-lint.v1"

EXIT_OK = 0
EXIT_FORMAT = 2
EXIT_SECRET = 3
EXIT_NO_INPUT = 4

VALID_STATUS = ("USED", "NOT_USED", "NOT_RUN", "UNAVAILABLE")

API_LINE_RE = re.compile(r"(?m)^\s*외부\s*API\s*:")
USAGE_BLOCK_RE = re.compile(r"(?m)^\s*PLUGIN_USAGE\s*:\s*$")
BULLET_RE = re.compile(r"^\s*-\s*(?P<name>[^:]+?)\s*:\s*(?P<rest>.*)$")
GLM_EQ_RE = re.compile(r"^\s*-?\s*GLM\s*=\s*(?P<rest>.+)$", re.IGNORECASE)
GLM_BARE_RE = re.compile(r"^\s*GLM\s*=", re.IGNORECASE)
GLM_COUNT_RE = re.compile(r"\((\d+)\s*/\s*3")
STATUS_RE = re.compile(r"^(USED|NOT_USED|NOT_RUN|UNAVAILABLE)\b")

# Secret-shaped strings. Values are never echoed back — only pattern names.
SECRET_RES = (
    ("openai-key", re.compile(r"sk-[A-Za-z0-9_\-]{16,}")),
    ("github-pat", re.compile(r"(?:ghp|gho|ghu|ghs|ghr|github_pat)_[A-Za-z0-9_]{16,}")),
    ("slack-token", re.compile(r"xox[baprs]-[A-Za-z0-9-]{10,}")),
    ("aws-akid", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("jwt", re.compile(r"eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{5,}")),
    ("private-key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY")),
    ("bearer-token", re.compile(r"Bearer\s+[A-Za-z0-9_\-\.]{20,}")),
    ("assigned-secret", re.compile(
        r"(?:api[_-]?key|token|secret|password|passwd|authorization)\s*[:=]\s*[\"']?[A-Za-z0-9_\-+/=.]{20,}",
        re.IGNORECASE)),
)

# Lines that claim a pass solely from transport-level signals.
WEAK_PASS_RE = re.compile(
    r"(HTTP\s*200|SSE\s*(시작|started|start))[^\n]{0,80}(PASS|성공|verified|success)|"
    r"(PASS|성공|verified|success)[^\n]{0,80}(HTTP\s*200|SSE\s*(시작|started|start))",
    re.IGNORECASE)


def lint_text(text: str) -> dict:
    errors: list[str] = []
    warnings: list[str] = []
    secret_hits: list[str] = []

    for name, rx in SECRET_RES:
        if rx.search(text):
            secret_hits.append(name)

    if not API_LINE_RE.search(text):
        errors.append("missing-line:외부 API")

    m = USAGE_BLOCK_RE.search(text)
    plugin_lines: list[str] = []
    if not m:
        errors.append("missing-block:PLUGIN_USAGE")
    else:
        tail = text[m.end():].splitlines()
        for line in tail:
            if not line.strip():
                if plugin_lines:
                    break
                continue
            if line.lstrip().startswith("-") or GLM_BARE_RE.match(line):
                plugin_lines.append(line)
            elif plugin_lines:
                break
        if not plugin_lines:
            errors.append("empty-block:PLUGIN_USAGE")

    glm_seen = False
    for line in plugin_lines:
        glm = GLM_EQ_RE.match(line)
        if glm:
            glm_seen = True
            rest = glm.group("rest")
            upper = rest.upper()
            if upper.startswith("SESSION_UNAVAILABLE"):
                if "(" not in rest or ")" not in rest:
                    errors.append("glm-no-reason:SESSION_UNAVAILABLE")
            elif upper.startswith("NOT_USED"):
                pass
            elif upper.startswith("USED"):
                cnt = GLM_COUNT_RE.search(rest)
                if not cnt:
                    errors.append("glm-used-no-count")
                elif int(cnt.group(1)) > 3:
                    errors.append(f"glm-over-budget:{cnt.group(1)}/3")
                if "marker ok" not in rest.lower():
                    errors.append("glm-used-no-marker-ok")
            elif upper.startswith("NOT_RUN") or upper.startswith("UNAVAILABLE"):
                if "(" not in rest or ")" not in rest:
                    errors.append("glm-no-reason")
            else:
                errors.append(f"glm-bad-status:{rest[:24]}")
            continue
        b = BULLET_RE.match(line)
        if not b:
            errors.append(f"bad-line:{line.strip()[:40]}")
            continue
        name, rest = b.group("name").strip(), b.group("rest").strip()
        sm = STATUS_RE.match(rest.upper())
        if not sm:
            errors.append(f"{name}:bad-status:{rest[:24]}")
            continue
        status = sm.group(1)
        if status in ("USED", "NOT_RUN", "UNAVAILABLE"):
            reason = rest[sm.end():].strip()
            if not (reason.startswith("(") and ")" in reason):
                errors.append(f"{name}:{status}-no-reason")
        if name.upper() == "GLM":
            glm_seen = True
            if status == "USED":
                cnt = GLM_COUNT_RE.search(rest)
                if not cnt:
                    errors.append("glm-used-no-count")
                elif int(cnt.group(1)) > 3:
                    errors.append(f"glm-over-budget:{cnt.group(1)}/3")
                if "marker ok" not in rest.lower():
                    errors.append("glm-used-no-marker-ok")

    for i, line in enumerate(text.splitlines(), 1):
        if WEAK_PASS_RE.search(line):
            warnings.append(f"weak-pass-claim:line{i}")

    verdict = ("SECRET" if secret_hits else
               "FORMAT" if errors else
               "WARN" if warnings else "OK")
    return {"schemaVersion": SCHEMA, "verdict": verdict, "errors": errors,
            "warnings": warnings, "secretPatterns": sorted(secret_hits),
            "pluginLines": len(plugin_lines), "glmSeen": glm_seen}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--report", required=True, help="report file path")
    ap.add_argument("--json", action="store_true", help="JSON output")
    args = ap.parse_args(argv)
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass

    fp = Path(args.report)
    if not fp.is_file():
        payload = {"schemaVersion": SCHEMA, "verdict": "NO_INPUT",
                   "errors": [f"file-not-found:{args.report}"],
                   "warnings": [], "secretPatterns": []}
        print(json.dumps(payload, ensure_ascii=False) if args.json
              else f"NO_INPUT file-not-found:{args.report}")
        return EXIT_NO_INPUT
    try:
        text = fp.read_text(encoding="utf-8-sig", errors="replace")
    except OSError as exc:
        print(f"NO_INPUT {type(exc).__name__}")
        return EXIT_NO_INPUT
    if not text.strip():
        print("FORMAT empty-report")
        return EXIT_FORMAT

    res = lint_text(text)
    if args.json:
        print(json.dumps(res, ensure_ascii=False))
    else:
        print(f"{res['verdict']} pluginLines={res['pluginLines']} "
              f"glmSeen={res['glmSeen']}")
        for e in res["errors"]:
            print(f"  ERROR {e}")
        for w in res["warnings"]:
            print(f"  WARN {w}")
        for s in res["secretPatterns"]:
            print(f"  SECRET {s}")
    return {"OK": EXIT_OK, "WARN": EXIT_OK,
            "FORMAT": EXIT_FORMAT, "SECRET": EXIT_SECRET}[res["verdict"]]


if __name__ == "__main__":
    raise SystemExit(main())
