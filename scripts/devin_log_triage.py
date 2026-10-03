#!/usr/bin/env python3
"""Triage Devin session logs into masked, normalized error signatures.

Stdlib only. Opens logs shared-read so a live Devin window can keep writing.
Does not print secret values.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

SCHEMA = "devin-log-triage.v1"
INTEREST_RE = re.compile(r"(?i)(error|warn|fail|enoent)")
SECRET_RE = re.compile(
    r"(?i)\b(token|key|secret|authorization|bearer)\b\s*[:=]?\s*(\S+)"
)
TS_RE = re.compile(
    r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?(?:Z|[+-]\d{2}:?\d{2})?"
)
HEX_RE = re.compile(r"\b[0-9a-fA-F]{8,}\b")
NUM_RE = re.compile(r"\d+")
FOCUS = (
    ("git-mcp", re.compile(r"(?i)MCP server 'git'|cannot find binary path|mcp/git")),
    ("github-mcp-server", re.compile(r"(?i)github-mcp-server|api\.githubcopilot\.com")),
    ("supabase-mcp", re.compile(r"(?i)supabase-mcp-server|mcp\.supabase\.com")),
    ("Codex-Engine", re.compile(r"(?i)Codex-Engine|server-filesystem")),
)


def mask_secrets(line: str) -> str:
    """Replace the value after token/key/secret/authorization/bearer."""
    def repl(match: re.Match) -> str:
        return match.group(1) + "=<masked>"
    return SECRET_RE.sub(repl, line)


def normalize_signature(line: str) -> str:
    text = mask_secrets(line).strip()
    text = TS_RE.sub("<TS>", text)
    text = HEX_RE.sub("<HEX>", text)
    text = NUM_RE.sub("<N>", text)
    return re.sub(r"\s+", " ", text).strip()


def parse_stamp(line: str):
    """Return an aware datetime when the line starts with a timestamp."""
    match = TS_RE.match(line.strip())
    if not match:
        return None
    raw = match.group(0).replace(",", ".")
    if " " in raw and "T" not in raw:
        raw = raw.replace(" ", "T", 1)
    if raw.endswith("Z"):
        raw = raw[:-1] + "+00:00"
    if re.search(r"[+-]\d{2}$", raw):
        raw = raw + ":00"
    try:
        stamp = datetime.fromisoformat(raw)
    except ValueError:
        return None
    if stamp.tzinfo is None:
        stamp = stamp.astimezone()
    return stamp


def parse_since(text: str):
    if not text:
        return None
    raw = text.strip()
    if raw.endswith("Z"):
        raw = raw[:-1] + "+00:00"
    stamp = datetime.fromisoformat(raw)
    if stamp.tzinfo is None:
        stamp = stamp.astimezone()
    return stamp


def newest_session(logs_root: Path) -> Path | None:
    if not logs_root.is_dir():
        return None
    dirs = [p for p in logs_root.iterdir() if p.is_dir()]
    if not dirs:
        return None
    dirs.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    return dirs[0]


def iter_log_lines(path: Path):
    """Yield text lines. Shared-read; locked files raise OSError."""
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            yield line.rstrip("\r\n")


def triage_file(path: Path, since, top_n: int) -> dict:
    row = {
        "bytes": None,
        "lines": 0,
        "matched": 0,
        "skippedBySince": 0,
        "locked": False,
        "error": "",
        "top": [],
        "focus": {name: 0 for name, _ in FOCUS},
    }
    try:
        row["bytes"] = path.stat().st_size
    except OSError as exc:
        row["locked"] = True
        row["error"] = type(exc).__name__
        return row
    counts: Counter = Counter()
    try:
        for line in iter_log_lines(path):
            row["lines"] += 1
            if since is not None:
                stamp = parse_stamp(line)
                if stamp is None or stamp < since:
                    row["skippedBySince"] += 1
                    continue
            if not INTEREST_RE.search(line):
                continue
            row["matched"] += 1
            signature = normalize_signature(line)
            counts[signature] += 1
            for name, pattern in FOCUS:
                if pattern.search(line):
                    row["focus"][name] += 1
    except OSError as exc:
        row["locked"] = True
        row["error"] = type(exc).__name__
    row["top"] = [{"signature": sig, "count": count} for sig, count in counts.most_common(top_n)]
    return row


def triage_session(session: Path | None, since, top_n: int) -> dict:
    files = {}
    if session is not None and session.is_dir():
        for path in sorted(session.rglob("*.log")):
            rel = str(path.relative_to(session)).replace("\\", "/")
            files[rel] = triage_file(path, since, top_n)
    global_counts: Counter = Counter()
    focus = {name: 0 for name, _ in FOCUS}
    for row in files.values():
        for item in row["top"]:
            global_counts[item["signature"]] += item["count"]
        for name in focus:
            focus[name] += row["focus"].get(name, 0)
    return {
        "schemaVersion": SCHEMA,
        "session": str(session) if session else "",
        "sessionName": session.name if session else "",
        "since": since.isoformat() if since else "",
        "fileCount": len(files),
        "files": files,
        "globalTop": [
            {"signature": sig, "count": count}
            for sig, count in global_counts.most_common(top_n)
        ],
        "focus": focus,
    }


def render_markdown(report: dict) -> str:
    lines = [
        "# Devin log triage",
        "",
        "- session: `%s`" % (report.get("sessionName") or "(none)"),
        "- since: `%s`" % (report.get("since") or "(all)"),
        "- files: %s" % report.get("fileCount"),
        "",
        "## Focus counts",
        "",
    ]
    for name, count in (report.get("focus") or {}).items():
        lines.append("- %s: %s" % (name, count))
    lines.extend(["", "## Global top signatures", ""])
    for item in report.get("globalTop") or []:
        lines.append("- %s x %s" % (item["count"], item["signature"][:240]))
    if not report.get("globalTop"):
        lines.append("- (none)")
    lines.append("")
    return "\n".join(lines)


def write_report(report: dict, json_path: Path, md_path: Path | None) -> None:
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if md_path is not None:
        md_path.write_text(render_markdown(report), encoding="utf-8")


def default_logs_root() -> Path:
    appdata = os.environ.get("APPDATA") or ""
    return Path(appdata) / "Devin" / "logs"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Mask and count Devin log signatures")
    parser.add_argument("--logs-root", default="")
    parser.add_argument("--session", default="")
    parser.add_argument("--since", default="")
    parser.add_argument("--top", type=int, default=15)
    parser.add_argument("--json", dest="json_path", required=True)
    parser.add_argument("--md", dest="md_path", default="")
    args = parser.parse_args(argv)
    logs_root = Path(args.logs_root) if args.logs_root else default_logs_root()
    session = Path(args.session) if args.session else newest_session(logs_root)
    since = parse_since(args.since) if args.since else None
    report = triage_session(session, since, max(1, args.top))
    write_report(report, Path(args.json_path), Path(args.md_path) if args.md_path else None)
    print("session=%s files=%s focus=%s" % (
        report["sessionName"], report["fileCount"], json.dumps(report["focus"], ensure_ascii=True)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
