#!/usr/bin/env python3
"""WP5 log redaction: strip secrets before AWX build_error_mine / glm_worker.

Masks, in order: Authorization/Cookie header values, Bearer/JWT/sk- style
tokens, `key=value` secrets (password/token/secret/apikey), JDBC URL
credentials, URL embedded userinfo, Windows user-home path segments. Keeps
the Gradle failure spine: `FAILURE:`/`What went wrong`/`error:`/`> Task`
/exception lines plus a bounded stack tail, so AWX classification still works.

Usage:
  python -B scripts/log_redact.py --in <raw.log> --out <sanitized.log>
  # or as the --sanitized-log-out hook inside gradle_truth_gate.ps1

NOTE: only feed the OUTPUT to downstream miners; if the primary AWX MCP lane
is healthy, do not route through recovery AWX. Originals stay on disk --
this never deletes the raw log.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

SCHEMA = "awx.log-redact.v1"

REDACTIONS = [
    ("header_secret", re.compile(
        r"(?im)^\s*(authorization|proxy-authorization|cookie|set-cookie|"
        r"x-api-key|x-auth-token|x-xsrf-token|x-csrf-token)"
        r"(\s*[:=]\s*)\S.*$")),
    ("bearer", re.compile(r"(?i)bearer\s+[A-Za-z0-9._~+/\-]{8,}={0,3}")),
    ("jwt", re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}")),
    ("api_key_token", re.compile(
        r"(?<![A-Za-z0-9_])(sk|pk|rt|pat|ghp|gho|ghu|ghs|ghr|xox[baprs])"
        r"[-_][A-Za-z0-9_-]{12,}")),
    ("kv_secret", re.compile(
        r"(?i)\b(password|passwd|pwd|secret|token|api[_-]?key|apikey|"
        r"client[_-]?secret|access[_-]?token|refresh[_-]?token|"
        r"private[_-]?key|code_verifier|_csrf|xsrf)"
        r"([\"'\s]*[=:][\"'\s]*)(\S+)")),
    ("url_cred", re.compile(r"(?i)\b([a-z][a-z0-9+.-]*://)([^/\s:@]+):([^/\s@]+)@")),
    ("jdbc_cred", re.compile(
        r"(?i)(jdbc:[a-z0-9]+:[^\s;\"']*(?:;[^\s;\"']*)*?)"
        r"(;?(?:user|username|password|uid|pwd)=)([^;\"'\s]+)")),
    ("env_key_value", re.compile(
        r"(?im)^\s*([A-Z][A-Z0-9_]{2,}(?:KEY|TOKEN|SECRET|PASSWORD|PASS|PWD))"
        r"(\s*[=:]\s*)\S.*$")),
]

HOME_PATTERN = re.compile(
    r"(?i)\b([A-Z]:[\\/]Users[\\/])([^\\/\s]+)([\\/])")


def redact_text(text: str) -> tuple[str, dict]:
    counts: dict[str, int] = {}
    out = text
    for name, pat in REDACTIONS:
        def repl(m, _name=name):
            counts[_name] = counts.get(_name, 0) + 1
            if _name in ("header_secret", "kv_secret", "env_key_value"):
                return m.group(1) + m.group(2) + "***REDACTED***"
            if _name == "url_cred":
                return m.group(1) + "***:***@"
            if _name == "jdbc_cred":
                return m.group(1) + m.group(2) + "***REDACTED***"
            return "***REDACTED***"
        out = pat.sub(repl, out)
    out, n = HOME_PATTERN.subn(lambda m: m.group(1) + "***USER***" + m.group(3), out)
    if n:
        counts["user_home"] = n
    return out, counts


def keep_gradle_spine(text: str, context: int = 2, max_stack: int = 40) -> str:
    """Keep failure-relevant lines; elide the rest with markers."""
    keep_re = re.compile(
        r"(FAILURE:|What went wrong|BUILD FAILED|BUILD SUCCESSFUL|error:|"
        r"Execution failed for task|Could not compile|>\s*Task|"
        r"Exception|Caused by:|^\s+at\s+[\w.$]+\(|tests completed|FAILED\b|"
        r"NO-SOURCE|UP-TO-DATE|Deprecated Gradle|VERDICT:|GRADLE_TRUTH)",
        re.I)
    lines = text.splitlines()
    keep: set[int] = set()
    stack_seen = 0
    for i, line in enumerate(lines):
        if keep_re.search(line):
            for j in range(max(0, i - context), min(len(lines), i + context + 1)):
                keep.add(j)
            if re.match(r"^\s+at\s+[\w.$]+\(", line):
                stack_seen += 1
                if stack_seen > max_stack:
                    keep.discard(i)
    out = [lines[i] if i in keep else None for i in range(len(lines))]
    compact, gap = [], False
    for line in out:
        if line is None:
            if not gap:
                compact.append("... [elided non-error lines] ...")
            gap = True
        else:
            compact.append(line)
            gap = False
    return "\n".join(compact)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="log_redact")
    ap.add_argument("--in", dest="src", required=True)
    ap.add_argument("--out", dest="dst", required=True)
    ap.add_argument("--spine-only", action="store_true",
                    help="also elide non-error lines")
    ap.add_argument("--report", default=None, help="write counts JSON")
    args = ap.parse_args(argv)

    raw = Path(args.src).read_text(encoding="utf-8", errors="replace")
    redacted, counts = redact_text(raw)
    if args.spine_only:
        redacted = keep_gradle_spine(redacted)
    Path(args.dst).parent.mkdir(parents=True, exist_ok=True)
    Path(args.dst).write_text(redacted, encoding="utf-8")
    report = {"schemaVersion": SCHEMA, "src": args.src, "dst": args.dst,
              "bytesIn": len(raw), "bytesOut": len(redacted),
              "redactions": counts, "spineOnly": bool(args.spine_only)}
    if args.report:
        Path(args.report).write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    total = sum(counts.values())
    print(f"LOG_REDACT: {args.src} -> {args.dst} redactions={total} "
          f"({json.dumps(counts)}) spineOnly={bool(args.spine_only)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
