#!/usr/bin/env python3
"""Bounded file peeker — never prints more than --max-chars.

Replaces `Get-Content <big file>` / `print(<whole json>)` habits that
produce 5k-740k token tool outputs. Picks a view (head/tail/grep/json-keys),
emits at most --max-chars of content, then one summary line:

    [out_peek] total_lines=<n> shown=<n> truncated=yes|no

Usage:
    python -B scripts/out_peek.py <file>
        [--grep PATTERN]      regex; matching lines only
        [--context N]         lines of context around each grep hit (default 0)
        [--head N]            first N lines (default 80 when no other view)
        [--tail N]            last N lines
        [--json-keys a.b,c]   for JSON/JSONL: emit only these dotted keys
        [--max-chars N]       hard output cap (default 6000)
        [--encoding NAME]     default utf-8 (errors=replace)

Exit 0 always when the file was readable (even when truncated); exit 2 on
usage/read errors so callers can fall back.
"""
import argparse
import json
import re
import sys
from pathlib import Path


def dotted_get(obj, dotted):
    cur = obj
    for part in dotted.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            return None
    return cur


def json_key_lines(text, keys):
    """Project each JSON line (or one JSON doc) onto the requested keys."""
    out = []
    docs = []
    stripped = text.strip()
    try:
        docs = [json.loads(stripped)]
    except ValueError:
        for line in stripped.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                docs.append(json.loads(line))
            except ValueError:
                continue
    for doc in docs:
        row = {}
        for key in keys:
            val = dotted_get(doc, key)
            if val is not None:
                row[key] = val
        if row:
            out.append(json.dumps(row, ensure_ascii=False, default=str))
    return out or ["[out_peek] no lines carried the requested keys"]


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("file")
    ap.add_argument("--grep", dest="grep", default=None)
    ap.add_argument("--context", type=int, default=0)
    ap.add_argument("--head", type=int, default=None)
    ap.add_argument("--tail", type=int, default=None)
    ap.add_argument("--json-keys", default=None)
    ap.add_argument("--max-chars", type=int, default=6000)
    ap.add_argument("--encoding", default="utf-8")
    args = ap.parse_args()

    path = Path(args.file)
    if not path.is_file():
        print("[out_peek] error: not a file: %s" % args.file)
        return 2
    try:
        text = path.read_text(encoding=args.encoding, errors="replace")
    except OSError as exc:
        print("[out_peek] error: %s" % exc)
        return 2

    lines = text.splitlines()
    total = len(lines)

    if args.json_keys:
        shown = json_key_lines(text, [k.strip() for k in args.json_keys.split(",") if k.strip()])
    elif args.grep:
        try:
            pat = re.compile(args.grep)
        except re.error as exc:
            print("[out_peek] error: bad --grep regex: %s" % exc)
            return 2
        shown, ctx = [], args.context
        for i, line in enumerate(lines):
            if pat.search(line):
                if ctx:
                    lo, hi = max(0, i - ctx), min(total, i + ctx + 1)
                    shown.extend("%d| %s" % (n + 1, lines[n]) for n in range(lo, hi))
                else:
                    shown.append("%d| %s" % (i + 1, line))
    else:
        head = args.head if args.head is not None else (80 if args.tail is None else 0)
        tail = args.tail or 0
        shown = lines[:head] + (["…"] if head and tail and head + tail < total else []) + \
            (lines[-tail:] if tail else [])

    body, truncated = [], False
    marker_max = len("[out_peek] total_lines=%d shown=%d truncated=yes" % (total, total)) + 1
    budget = max(0, args.max_chars - marker_max)
    for line in shown:
        if sum(len(x) + 1 for x in body) + len(line) + 1 > budget:
            truncated = True
            break
        body.append(line)
    if len(body) < len(shown):
        truncated = True
    print("\n".join(body))
    print("[out_peek] total_lines=%d shown=%d truncated=%s"
          % (total, len(body), "yes" if truncated else "no"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
