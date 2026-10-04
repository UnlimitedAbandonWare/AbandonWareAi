#!/usr/bin/env python3
"""Codex skill-performance monitor (read-only, bounded).

Scans recent Codex rollout *.jsonl session logs and reports, per session and
in aggregate:

  - assigned skill tier (tier0_light / tier1_tactical / tier2_strategic)
    inferred from which high-performance skills were invoked,
  - total consumed tokens (token_usage_record sum) and whether output
    truncation occurred,
  - high-performance skill invocation counts (Triad / Strategy /
    Evidence-Debug style skills),
  - patch friction: apply_patch hunk errors, source lease drift, refusal
    signatures.

Output: --brief one-line summary, or detailed JSON (default/--pretty).
All analysis is read-only; no secret values are printed (only counter names
and counts, never matched text).

Usage:
    python -B scripts/codex_skill_performance_monitor.py
        [--sessions-dir PATH]   (default: ~/.codex/sessions)
        [--days N]              (filename-date window; default 7)
        [--max-sessions N]      (cap on analyzed files, newest first; 200)
        [--brief]               (one-line summary)
        [--json-out PATH]
        [--pretty]
"""
import argparse
import json
import re
import sys
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

# ---------------------------------------------------------------------------
# Tunables — adjust here when re-tuning the allocation policy. Token lanes
# mirror docs/agents-rules/DEMO1-SKILL-PERFORMANCE-TIERING.md budgets.
# ---------------------------------------------------------------------------
TIER_THRESHOLDS = {
    "tier0_max_tokens": 15_000,     # Light lane budget (~15k)
    "tier1_max_tokens": 60_000,     # Tactical lane budget (30k~60k)
    "tier2_max_tokens": 120_000,    # Strategic lane budget (80k~120k)
    "checkpoint_advisory_tokens": 140_000,  # session-state-checkpoint 권고
}

TIER2_SKILL_NAMES = (
    "demo1-triad-deliberation",
    "positive-negative-neutral-judge",
    "demo1-triangulating-counter-evidence",
)
TIER1_SKILL_NAMES = (
    "demo1-evidence-debugging",
    "demo1-subsystem-patch-directive",
    "demo1-rag-strategy-orchestration",
    "awx-uaw-web-research",
)
HIGH_PERFORMANCE_SKILLS = TIER2_SKILL_NAMES + TIER1_SKILL_NAMES
SKILL_PATTERNS = {name: name.encode("utf-8")
                  for name in HIGH_PERFORMANCE_SKILLS}

# --- byte patterns (no json parse needed) -----------------------------------
TRUNC_TOKEN_RE = re.compile(
    rb"Warning: truncated output \(original token count: (\d+)\)")
REFUSED_WORD_RE = re.compile(
    rb"refus(?:ed|al)[- ]?as[- ][a-z0-9-]+|refused[- ][a-z0-9-]{2,60}")
FRICTION_PATTERNS = {
    "applypatch_expected_lines": rb"Failed to find expected lines",
    "applypatch_invalid_hunk": rb"invalid hunk",
    "source_lease_drift": rb"source-lease-drift",
    "goal_conflict": rb"cannot create a new goal because this thread "
                     rb"has an unfinished goal",
}
STRUCT_MARKERS = (b'token_usage_record', b'compacted', b'spawn_agent')
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


def classify_tier(high_perf_hits):
    """Observed-skill evidence -> assigned tier name."""
    hits = high_perf_hits or {}
    if any(hits.get(name, 0) > 0 for name in TIER2_SKILL_NAMES):
        return "tier2_strategic"
    if any(hits.get(name, 0) > 0 for name in TIER1_SKILL_NAMES):
        return "tier1_tactical"
    return "tier0_light"


def token_lane(total_tokens):
    """Cumulative tokens -> consumption lane vs TIER_THRESHOLDS budgets."""
    if total_tokens > TIER_THRESHOLDS["tier2_max_tokens"]:
        return "over_tier2"
    if total_tokens > TIER_THRESHOLDS["tier1_max_tokens"]:
        return "tier2_strategic"
    if total_tokens > TIER_THRESHOLDS["tier0_max_tokens"]:
        return "tier1_tactical"
    return "tier0_light"


def _classify_structured(rec, m):
    """token_usage_record / compacted / spawn_agent counts."""
    rtype = rec.get("type")
    payload = rec.get("payload") or {}
    if rtype == "token_usage_record":
        usage = payload.get("usage") or {}
        total = usage.get("total_tokens")
        if not isinstance(total, (int, float)):
            total = (usage.get("input_tokens") or 0) + \
                    (usage.get("output_tokens") or 0)
        m["tokens"] += int(total or 0)
    elif rtype == "compacted":
        m["compactions"] += 1
    elif rtype == "response_item" and \
            payload.get("type") == "function_call" and \
            payload.get("name") == "spawn_agent":
        m["spawn_agent"] += 1


def analyze_session_file(path, max_lines=None):
    """One rollout file -> metrics dict (read-only, line-bounded optional)."""
    m = {
        "tokens": 0, "compactions": 0, "spawn_agent": 0,
        "truncated": False, "trunc_count": 0, "trunc_tokens": 0,
        "high_perf": Counter(), "friction": Counter(), "refused": 0,
        "lines": 0,
    }
    try:
        stream = open(path, "rb")
    except OSError as exc:
        m["error"] = str(exc)
        return m
    with stream:
        for i, line in enumerate(stream):
            if max_lines is not None and i >= max_lines:
                m["line_bounded"] = True
                break
            m["lines"] += 1
            for key, pat in FRICTION_PATTERNS.items():
                if pat in line:
                    m["friction"][key] += line.count(pat)
            if b"refus" in line:
                m["refused"] += sum(1 for _ in REFUSED_WORD_RE.finditer(line))
            for match in TRUNC_TOKEN_RE.finditer(line):
                m["truncated"] = True
                m["trunc_count"] += 1
                m["trunc_tokens"] += int(match.group(1))
            for name, pat in SKILL_PATTERNS.items():
                if pat in line:
                    m["high_perf"][name] += line.count(pat)
            if any(mk in line for mk in STRUCT_MARKERS):
                try:
                    rec = json.loads(line)
                except (ValueError, UnicodeDecodeError):
                    continue
                _classify_structured(rec, m)
    return m


def session_summary(path, metrics):
    """metrics dict -> report row."""
    high_perf = dict(metrics["high_perf"])
    friction = dict(metrics["friction"])
    return {
        "session": path.name,
        "assigned_tier": classify_tier(high_perf),
        "token_lane": token_lane(metrics["tokens"]),
        "tokens": metrics["tokens"],
        "compactions": metrics["compactions"],
        "truncated": metrics["truncated"],
        "trunc_count": metrics["trunc_count"],
        "trunc_tokens": metrics["trunc_tokens"],
        "high_perf_invocations": sum(high_perf.values()),
        "high_perf_by_skill": high_perf,
        "spawn_agent": metrics["spawn_agent"],
        "friction": friction,
        "friction_total": sum(friction.values()),
        "refused": metrics["refused"],
        "checkpoint_advised":
            metrics["tokens"] >=
            TIER_THRESHOLDS["checkpoint_advisory_tokens"],
    }


def build_report(sessions_dir, days=7, max_sessions=200, today=None):
    root = Path(sessions_dir)
    report = {
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "sessionsDir": str(root), "days": days,
        "tierThresholds": dict(TIER_THRESHOLDS),
        "files_scanned": 0, "files_matched": 0,
        "sessions": [], "totals": {}, "errors": [],
    }
    if not root.is_dir():
        report["errors"].append("sessions-dir-missing")
        return report
    files = [p for p in root.rglob("*.jsonl") if p.is_file()]
    report["files_scanned"] = len(files)
    in_window = [p for p in files if file_in_window(p, days, today=today)]
    in_window.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    for path in in_window[:max_sessions]:
        report["files_matched"] += 1
        metrics = analyze_session_file(path)
        if "error" in metrics:
            report["errors"].append("%s: %s" % (path.name, metrics["error"]))
            continue
        report["sessions"].append(session_summary(path, metrics))
    totals = {
        "sessions": len(report["sessions"]),
        "tokens": 0, "compactions": 0,
        "trunc_count": 0, "trunc_tokens": 0,
        "high_perf_invocations": 0,
        "by_skill": Counter(), "friction": Counter(), "refused": 0,
        "tier_counts": Counter(),
    }
    for row in report["sessions"]:
        totals["tokens"] += row["tokens"]
        totals["compactions"] += row["compactions"]
        totals["trunc_count"] += row["trunc_count"]
        totals["trunc_tokens"] += row["trunc_tokens"]
        totals["high_perf_invocations"] += row["high_perf_invocations"]
        totals["refused"] += row["refused"]
        totals["tier_counts"][row["assigned_tier"]] += 1
        for name, n in row["high_perf_by_skill"].items():
            totals["by_skill"][name] += n
        for key, n in row["friction"].items():
            totals["friction"][key] += n
    totals["by_skill"] = dict(totals["by_skill"])
    totals["friction"] = dict(totals["friction"])
    totals["tier_counts"] = dict(totals["tier_counts"])
    report["totals"] = totals
    return report


def format_brief(report):
    t = report["totals"]
    tier = t.get("tier_counts") or {}
    friction = t.get("friction") or {}
    hunk = friction.get("applypatch_expected_lines", 0) + \
        friction.get("applypatch_invalid_hunk", 0)
    return (
        "sessions=%(sessions)d tokens=%(tokens)d compactions=%(comp)d "
        "trunc=%(trunc)d(%(trunc_tok)d tok) highPerf=%(hp)d "
        "tiers[T0=%(t0)d T1=%(t1)d T2=%(t2)d] "
        "friction[hunk=%(hunk)d lease=%(lease)d refused=%(ref)d]"
    ) % {
        "sessions": t.get("sessions", 0),
        "tokens": t.get("tokens", 0),
        "comp": t.get("compactions", 0),
        "trunc": t.get("trunc_count", 0),
        "trunc_tok": t.get("trunc_tokens", 0),
        "hp": t.get("high_perf_invocations", 0),
        "t0": tier.get("tier0_light", 0),
        "t1": tier.get("tier1_tactical", 0),
        "t2": tier.get("tier2_strategic", 0),
        "hunk": hunk,
        "lease": friction.get("source_lease_drift", 0),
        "ref": t.get("refused", 0),
    }


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--sessions-dir",
                    default=str(Path.home() / ".codex" / "sessions"))
    ap.add_argument("--days", type=int, default=7)
    ap.add_argument("--max-sessions", type=int, default=200)
    ap.add_argument("--brief", action="store_true")
    ap.add_argument("--json-out", default=None)
    ap.add_argument("--pretty", action="store_true")
    args = ap.parse_args(argv)

    report = build_report(args.sessions_dir, days=args.days,
                          max_sessions=args.max_sessions)
    if args.brief:
        print(format_brief(report))
        return 0 if report["files_matched"] or not report["errors"] else 2
    text = json.dumps(report, ensure_ascii=False, default=str,
                      indent=2 if args.pretty else None)
    if args.json_out:
        Path(args.json_out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.json_out).write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
