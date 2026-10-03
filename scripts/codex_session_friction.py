#!/usr/bin/env python3
"""Codex session friction metrics (read-only).

Scans Codex rollout *.jsonl session logs and emits one JSON report:
truncation counts/token waste, wait-call counts, spawn_agent mix,
code-mode error signatures, checkpoint flow refusals, secret-pattern
holds (masked), apply_patch failures, MCP failure classes, compaction
counts, and per-session cumulative token estimates.

Usage:
    python -B scripts/codex_session_friction.py
        [--sessions-dir PATH]          (default: ~/.codex/sessions)
        [--days N]                     (default: 3; filename-date window)
        [--samples N]                  (max evidence samples per class; default 6)
        [--json-out PATH]              (also write the report JSON here)
        [--pretty]

Never prints secret VALUES: hold samples keep only file/line/pattern-name
fields; matched text is never echoed back.
"""
import argparse
import json
import re
import statistics
import sys
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

# --- byte patterns counted without json parsing ------------------------------
TRUNC_TOKEN_RE = re.compile(rb"Warning: truncated output \(original token count: (\d+)\)")
TRUNC_LEN_RE = re.compile(rb"\[truncated, original length (\d+)\]")
REASON_RE = re.compile(rb'"(?:reason|firstBlockingRule)":\s*"([^"\\]{1,120})"')
REFUSED_WORD_RE = re.compile(rb"refus(?:ed|al)[- ]?as[- ][a-z0-9-]+|refused[- ][a-z0-9-]{2,60}")

SIMPLE_COUNTERS = {
    "goal_conflict": rb"cannot create a new goal because this thread has an unfinished goal",
    "non_serializable": rb"Only plain serializable objects can be stored",
    "json_parse_mention": rb"JSON.parse",
    "unexpected_token": rb"Unexpected token",
    "applypatch_expected_lines": rb"Failed to find expected lines",
    "applypatch_invalid_hunk": rb"invalid hunk",
    "createprocess": rb"CreateProcess",
    "selector_deadline": rb"selector deadline exceeded",
    "dispatch_timeout": rb"Input.dispatchMouseEvent",
    "app_not_connected": rb"app is not connected",
    "secret_pattern_hold": rb'"firstBlockingRule":\s*"secret-pattern"',
    "secret_pattern_hold_escaped": rb'firstBlockingRule\\": \\"secret-pattern',
    "source_lease_drift": rb"source-lease-drift",
    "secret_pattern_any": rb"secret-pattern",
}

STRUCT_MARKERS = (b'"token_usage_record"', b'"type":"compacted"', b'"name":"wait"',
                  b'"name":"spawn_agent"', b'"name":"js"', b'McpToolCall')

FILENAME_DATE = re.compile(r"rollout-(\d{4})-(\d{2})-(\d{2})T")


def file_in_window(path, days, today=None):
    m = FILENAME_DATE.search(path.name)
    if not m:
        return False
    try:
        d = date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    except ValueError:
        return False
    today = today or date.today()
    return today - timedelta(days=days - 1) <= d <= today + timedelta(days=1)


def masked_hold_sample(line_bytes):
    """Return small masked dict from a checkpoint-hold JSON fragment.

    Keeps only safe keys (status/rule/scope/path/line). Never includes any
    matched secret text.
    """
    frag = line_bytes.decode("utf-8", "replace")
    out = {}
    for key in ("status", "firstBlockingRule", "holdScope", "blockingEvidence",
                "independentWorkCompleted", "nextAction"):
        m = re.search(r'"%s":\s*"([^"\\]{1,160})"' % re.escape(key), frag)
        if m:
            out[key] = m.group(1)
    for key in ("path", "file", "target", "line", "lineNumber", "pattern"):
        m = re.search(r'"%s":\s*("([^"\\]{1,200})"|\d{1,7})' % re.escape(key), frag)
        if m:
            out[key] = m.group(2) if m.group(2) is not None else int(m.group(1))
    return out or {"note": "hold-json-not-isolated"}


def classify_line(line, out, samples, session):
    """Structured (json) classification for lines carrying markers."""
    try:
        rec = json.loads(line)
    except (ValueError, UnicodeDecodeError):
        return
    rtype = rec.get("type")
    payload = rec.get("payload") or {}
    if rtype == "token_usage_record":
        usage = payload.get("usage") or {}
        total = usage.get("total_tokens")
        if not isinstance(total, (int, float)):
            total = (usage.get("input_tokens") or 0) + (usage.get("output_tokens") or 0)
        session["tokens"] += int(total or 0)
        return
    if rtype == "compacted":
        out["compactions"] += 1
        session["compactions"] += 1
        return
    if rtype == "response_item":
        ptype = payload.get("type")
        if ptype == "function_call":
            name = payload.get("name")
            if name == "wait":
                out["wait_calls"] += 1
                session["wait_calls"] += 1
            elif name == "spawn_agent":
                out["spawn_agent"]["total"] += 1
                session["spawn_agent"] += 1
                atype = "unknown"
                try:
                    args = json.loads(payload.get("arguments") or "{}")
                    atype = (args.get("agent_type") or args.get("subagent_type")
                             or args.get("role") or args.get("type") or "unknown")
                except ValueError:
                    raw = payload.get("arguments") or ""
                    m = re.search(r'"(?:agent_type|subagent_type|role|type)"\s*:\s*"([a-zA-Z0-9_]+)"', raw)
                    atype = m.group(1) if m else "unparsed"
                out["spawn_agent"]["by_type"][str(atype)] += 1
            elif name == "js":
                out["js_calls"] += 1
        return
    if rtype == "event_msg" and payload.get("type") == "item_completed":
        item = payload.get("item") or {}
        if item.get("type") == "McpToolCall":
            server = item.get("server", "?")
            tool = item.get("tool", "?")
            failed = (item.get("status") == "failed"
                      or (isinstance(item.get("result"), dict)
                          and item["result"].get("isError")))
            if failed:
                key = "%s.%s" % (server, tool)
                out["mcp_failures"]["by_tool"][key] += 1
                err = ""
                try:
                    for part in (item.get("result") or {}).get("content") or []:
                        err += str(part.get("text", "")) + "\n"
                except AttributeError:
                    pass
                first = next((ln.strip() for ln in err.splitlines() if ln.strip()), "")
                if len(out["mcp_failures"]["samples"].get(key, [])) < 3:
                    out["mcp_failures"]["samples"].setdefault(key, []).append(first[:200])
        return


def scan_file(path, out, samples_max):
    session = {"tokens": 0, "compactions": 0, "wait_calls": 0, "spawn_agent": 0}
    try:
        stream = open(path, "rb")
    except OSError as exc:
        out["errors"].append("%s: %s" % (path.name, exc))
        return session
    with stream:
        for line in stream:
            if b'"reason"' in line or b'firstBlockingRule' in line:
                for m in REASON_RE.finditer(line):
                    out["reasons"][m.group(1).decode("utf-8", "replace")] += 1
            if b'refus' in line:
                for m in REFUSED_WORD_RE.finditer(line):
                    out["refused_tokens"][m.group(0).decode("utf-8", "replace")[:80]] += 1
            for key, pat in SIMPLE_COUNTERS.items():
                if pat in line:
                    cnt = line.count(pat)
                    out["counters"][key] += cnt
                    session.setdefault(key, 0)
                    session[key] += cnt
                    if key.startswith("secret_pattern") and len(out["secret_samples"]) < samples_max:
                        out["secret_samples"].append(
                            {"session": path.name} | masked_hold_sample(line))
            for m in TRUNC_TOKEN_RE.finditer(line):
                out["truncated_output"]["count"] += 1
                n = int(m.group(1))
                out["truncated_output"]["token_counts"].append(n)
                session.setdefault("trunc_warn", 0)
                session["trunc_warn"] += 1
            for m in TRUNC_LEN_RE.finditer(line):
                out["truncated_length"]["count"] += 1
                out["truncated_length"]["bytes_sum"] += int(m.group(1))
            if any(mk in line for mk in STRUCT_MARKERS):
                classify_line(line, out, samples_max, session)
    out["bytes_scanned"] += path.stat().st_size if path.exists() else 0
    return session


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--sessions-dir", default=str(Path.home() / ".codex" / "sessions"))
    ap.add_argument("--days", type=int, default=3)
    ap.add_argument("--samples", type=int, default=6)
    ap.add_argument("--json-out", default=None)
    ap.add_argument("--pretty", action="store_true")
    args = ap.parse_args()

    root = Path(args.sessions_dir)
    out = {
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "sessionsDir": str(root), "days": args.days,
        "files_scanned": 0, "files_matched": 0, "bytes_scanned": 0,
        "compactions": 0, "wait_calls": 0, "js_calls": 0,
        "spawn_agent": {"total": 0, "by_type": Counter()},
        "truncated_output": {"count": 0, "token_counts": []},
        "truncated_length": {"count": 0, "bytes_sum": 0},
        "counters": Counter(), "reasons": Counter(), "refused_tokens": Counter(),
        "mcp_failures": {"by_tool": Counter(), "samples": defaultdict(list)},
        "secret_samples": [], "sessions": {}, "errors": [],
    }
    if not root.is_dir():
        print(json.dumps({"status": "error", "reason": "sessions-dir-missing",
                          "path": str(root)}))
        return 2
    files = [p for p in root.rglob("*.jsonl") if p.is_file()]
    out["files_scanned"] = len(files)
    for path in files:
        if not file_in_window(path, args.days):
            continue
        out["files_matched"] += 1
        session = scan_file(path, out, args.samples)
        out["sessions"][path.name] = session

    counts = sorted(out["truncated_output"]["token_counts"])
    tok = out["truncated_output"]
    tok_report = {"count": tok["count"], "token_sum": sum(counts),
                  "median": (statistics.median(counts) if counts else 0),
                  "p90": (counts[int(len(counts) * 0.9)] if counts else 0),
                  "max": (max(counts) if counts else 0)}
    sessions_sorted = sorted(out["sessions"].items(),
                             key=lambda kv: kv[1].get("tokens", 0), reverse=True)
    report = {
        "generatedAtUtc": out["generatedAtUtc"],
        "sessionsDir": out["sessionsDir"], "days": out["days"],
        "files_scanned": out["files_scanned"],
        "files_matched": out["files_matched"],
        "bytes_scanned": out["bytes_scanned"],
        "compactions": out["compactions"],
        "wait_calls": out["wait_calls"],
        "js_calls": out["js_calls"],
        "spawn_agent": {"total": out["spawn_agent"]["total"],
                        "by_type": dict(out["spawn_agent"]["by_type"])},
        "truncated_output": tok_report,
        "truncated_length": out["truncated_length"],
        "counters": dict(out["counters"]),
        "reasons_top": out["reasons"].most_common(40),
        "refused_tokens": out["refused_tokens"].most_common(40),
        "mcp_failures": {"by_tool": dict(out["mcp_failures"]["by_tool"]),
                         "samples": dict(out["mcp_failures"]["samples"])},
        "secret_samples": out["secret_samples"],
        "session_tokens_top": [{"session": k, "tokens": v["tokens"],
                                "compactions": v["compactions"],
                                "wait_calls": v["wait_calls"],
                                "trunc_warn": v.get("trunc_warn", 0)}
                               for k, v in sessions_sorted[:10]],
        "errors": out["errors"][:20],
    }
    text = json.dumps(report, ensure_ascii=False,
                      indent=2 if args.pretty else None, default=str)
    if args.json_out:
        Path(args.json_out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.json_out).write_text(text + "\n", encoding="utf-8")
    print(text if args.pretty else json.dumps(report, ensure_ascii=False, default=str))
    return 0


if __name__ == "__main__":
    sys.exit(main())
