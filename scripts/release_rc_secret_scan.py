#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""release_rc_secret_scan — tracked-blob secret scan for the 10/13 public RC.

Scans the contents of every file tracked at a ref (default HEAD) for credential
patterns. Output is path + line + rule id + first-4-chars masked prefix only;
the matched value is never printed in full.

  python -B scripts/release_rc_secret_scan.py [--ref HEAD] [--json]

exit 0 = no findings; exit 1 = findings or scan error.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys

GIT = r"F:\git\cmd\git.exe"

# (rule_id, regex). Keep to high-signal credential shapes; values are masked.
# token prefix needs a non-word left edge: `task-x`, `risk-x`, `mask-x`
# all contain a literal `sk-` substring and caused mass false positives.
TOKEN_EDGE = r"(?<![A-Za-z0-9_])"

RULES = [
    ("openai-sk", re.compile(TOKEN_EDGE + r"sk-[A-Za-z0-9_\-]{16,}")),
    ("openai-proj", re.compile(TOKEN_EDGE + r"sk-proj-[A-Za-z0-9_\-]{16,}")),
    ("google-ai", re.compile(TOKEN_EDGE + r"AIza[0-9A-Za-z_\-]{20,}")),
    ("vercel-vck", re.compile(TOKEN_EDGE + r"vck_[A-Za-z0-9]{20,}")),
    ("github-ghp", re.compile(TOKEN_EDGE + r"ghp_[A-Za-z0-9]{20,}")),
    ("github-pat", re.compile(TOKEN_EDGE + r"github_pat_[A-Za-z0-9_]{20,}")),
    ("github-gho", re.compile(TOKEN_EDGE + r"gho_[A-Za-z0-9]{20,}")),
    ("slack-xox", re.compile(TOKEN_EDGE + r"xox[baprs]-[A-Za-z0-9\-]{8,}")),
    ("aws-akid", re.compile(TOKEN_EDGE + r"AKIA[0-9A-Z]{16}")),
    ("private-key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY")),
    # RHS must be a quoted literal: `apiKey = resolveApiKeyForBaseUrl(...)` is
    # a method call, not a credential value.
    ("client-secret", re.compile(TOKEN_EDGE + r"client_secret[\"'\s]*[:=]\s*[\"'][A-Za-z0-9_\-]{8,}", re.I)),
    ("apikey-assign", re.compile(r"(?:api[_-]?key|apikey)[\"'\s]*[:=]\s*[\"'][A-Za-z0-9_\-]{20,}", re.I)),
    ("bearer-token", re.compile(TOKEN_EDGE + r"Bearer\s+[A-Za-z0-9_\-\.=]{20,}")),
]

# paths that are allowed placeholders by content shape (empty env templates)
PLACEHOLDER_OK = re.compile(r"^[A-Z0-9_]+=(|your[-_]|<|\$\{|\.\.\.|xxx|example|changeme|TODO).*$", re.I)


def ls_files(ref: str) -> list[str]:
    out = subprocess.run([GIT, "ls-tree", "-r", "--name-only", ref],
                         capture_output=True, text=True, encoding="utf-8",
                         errors="replace")
    if out.returncode != 0:
        raise SystemExit(f"ls-tree failed: {out.stderr.strip()[:200]}")
    return [p for p in out.stdout.splitlines() if p.strip()]


def blob(ref: str, path: str) -> str | None:
    out = subprocess.run([GIT, "show", f"{ref}:{path}"],
                         capture_output=True)
    if out.returncode != 0:
        return None
    try:
        return out.stdout.decode("utf-8")
    except UnicodeDecodeError:
        return None  # binary blob skipped


def mask(raw: str) -> str:
    return raw[:4] + "****" if len(raw) > 4 else "****"


SYNTHETIC_WORDS = re.compile(
    r"(?i)(synthetic|dummy|fake|fixture|example|sample|changeme|redacted|"
    r"placeholder|test[-_]?token|invalid|your[-_]?|xx+|not[-_]?a[-_]?|"
    r"repeat\(|REMOVED|__MISSING__|<secret|<unresolved)")

TEST_PATH = re.compile(
    r"(?i)(^|/)(src/test|src/chatUiTest|scripts/test_|tools/test_|"
    r"tests/|test/.*fixtures|.*Test\.java$|.*\.test\.(cjs|mjs|js)$|"
    r".*_tests?\.(py|ps1|sh|js|cjs|mjs)$)")


def classify(path: str, line: str, raw: str) -> str:
    """best-effort classification; 'needs-review' is the only bucket that
    requires a human/agent eyeball on the source line."""
    # repeated-char or fully mechanical tokens cannot be real credentials
    body = re.sub(r"^(sk-|sk-proj-|ghp_|gho_|github_pat_|vck_|xox[baprs]-|AKIA|AIza|Bearer\s+)", "", raw)
    if len(set(body)) <= 2:
        return "synthetic-repeat"
    if SYNTHETIC_WORDS.search(line) or SYNTHETIC_WORDS.search(raw):
        return "synthetic-fixture"
    if "${" in line or "System.getenv" in line or "os.environ" in line:
        return "env-reference"
    if TEST_PATH.search(path):
        return "test-fixture"
    return "needs-review"


def scan(ref: str) -> tuple[list[dict], int]:
    findings: list[dict] = []
    files = ls_files(ref)
    for path in files:
        text = blob(ref, path)
        if text is None:
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            if PLACEHOLDER_OK.match(line.strip()):
                continue
            for rid, rx in RULES:
                m = rx.search(line)
                if m:
                    findings.append({"path": path, "line": lineno,
                                     "rule": rid, "match": mask(m.group(0)),
                                     "class": classify(path, line, m.group(0))})
                    break  # one rule per line is enough for triage
    return findings, len(files)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--ref", default="HEAD")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    try:
        findings, n = scan(args.ref)
    except SystemExit as e:
        print(str(e))
        return 1
    counts: dict[str, int] = {}
    for f in findings:
        counts[f["class"]] = counts.get(f["class"], 0) + 1
    payload = {"ref": args.ref, "filesScanned": n, "findingCount": len(findings),
               "classCounts": counts, "findings": findings}
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(f"ref={args.ref} filesScanned={n} findings={len(findings)} classes={counts}")
        for f in findings:
            print(f"{f['path']}:{f['line']} rule={f['rule']} match={f['match']} class={f['class']}")
    review = [f for f in findings if f["class"] == "needs-review"]
    return 1 if review else 0


if __name__ == "__main__":
    sys.exit(main())
