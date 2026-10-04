#!/usr/bin/env python3
"""codex_context_status.py — context/token budget status for Codex sessions
and system-prompt footprint measurement.

Resumed from 0913h0 + 0924d0/0924c0 (weekly directive P5):
  codex_context_status.py --json --detailed + budget threshold auto-stop,
  and before/after token measurement for system-prompt -> skill moves.

Actions:
  status  --session <rollout.jsonl> | --latest | --input <metrics.json>
          [--budget-tokens N] [--json] [--detailed]
      Estimates tokens (utf-8 chars/4) of a Codex rollout session file,
      counts events by type, reports budget usage and recommended action
      (ok | compress | stop) when --budget-tokens is set.
      --input accepts a pre-computed {"chars": N, "events": M} JSON for
      callers that already measured elsewhere.
  footprint --files <f1> [f2 ...] | --defaults
      Token estimate of the system-prompt file set (default: AGENTS.md +
      .windsurf/rules/*.md). Use before and after a compression pass and
      diff the JSON for the required measurement report.
  compare --before <footprint.json> --after <footprint.json>
      Emits {beforeTokens, afterTokens, deltaPct, targetMet} where
      targetMet = deltaPct >= --target-pct (default 30).

Exit codes: 0 ok, 2 usage/io, 4 over-budget or compression target missed.
Never prints message contents — counts and estimates only.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
from pathlib import Path
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

SCHEMA = "awx.codex-context-status.v1"
ROOT = Path(__file__).resolve().parents[1]
CHARS_PER_TOKEN = 4
DEFAULT_BUDGET = 180_000
WARN_PCT = 80
ACTION_OK, ACTION_COMPRESS, ACTION_STOP = "ok", "compress", "stop"


def estimate_tokens(chars: int) -> int:
    return max(0, int(chars) // CHARS_PER_TOKEN)


def codex_sessions_dir() -> Path:
    home = os.environ.get("CODEX_HOME") or str(
        Path(os.environ.get("USERPROFILE") or Path.home()) / ".codex")
    return Path(home) / "sessions"


def latest_rollout(base: Path) -> Path | None:
    files = [p for p in base.rglob("rollout-*.jsonl") if p.is_file()]
    return max(files, key=lambda p: p.stat().st_mtime) if files else None


def measure_session(path: Path, detailed: bool) -> dict:
    events, chars, by_type = 0, 0, {}
    biggest = []
    with path.open("r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            events += 1
            chars += len(line)
            etype = None
            try:
                rec = json.loads(line)
                etype = (rec.get("type") or
                         (rec.get("payload") or {}).get("type"))
            except ValueError:
                etype = "unparsed"
            by_type[etype or "unknown"] = by_type.get(etype or "unknown", 0) + 1
            if detailed:
                biggest.append(len(line))
    out = {
        "session": str(path),
        "bytes": path.stat().st_size,
        "events": events,
        "chars": chars,
        "estimatedTokens": estimate_tokens(chars),
        "eventTypes": dict(sorted(by_type.items(),
                                  key=lambda kv: -kv[1])),
    }
    if detailed:
        biggest.sort(reverse=True)
        out["largestEventsChars"] = biggest[:10]
    return out


def budget_view(measured: dict, budget: int) -> dict:
    used = measured["estimatedTokens"]
    pct = round(100.0 * used / budget, 1) if budget else None
    if pct is None:
        action = ACTION_OK
    elif pct >= 100:
        action = ACTION_STOP
    elif pct >= 80:
        action = ACTION_COMPRESS
    else:
        action = ACTION_OK
    view = {"budgetTokens": budget, "budgetUsedPct": pct,
            "warnThresholdPct": WARN_PCT,
            "warnAtTokens": int(budget * WARN_PCT / 100) if budget else None,
            "overBudget": bool(pct is not None and pct >= 100),
            "recommendedAction": action}
    if action == ACTION_COMPRESS:
        view["earlyWarning"] = True
        view["recommendSkill"] = "demo1-session-state-checkpoint"
        view["warnMessage"] = ("early warning: persist a state.md via "
                               "demo1-session-state-checkpoint before the "
                               "180k budget is hit")
    return view


def cmd_status(args) -> tuple[dict, int]:
    if args.input:
        data = json.loads(Path(args.input).read_text(encoding="utf-8-sig"))
        measured = {
            "session": None,
            "bytes": int(data.get("chars", 0)),
            "events": int(data.get("events", 0)),
            "chars": int(data.get("chars", 0)),
            "estimatedTokens": estimate_tokens(int(data.get("chars", 0))),
            "eventTypes": data.get("eventTypes") or {},
        }
    else:
        if args.latest:
            session = latest_rollout(codex_sessions_dir())
            if session is None:
                return {"schemaVersion": SCHEMA, "status": "error",
                        "reason": "no-rollout-found"}, 2
        elif args.session:
            session = Path(args.session)
        else:
            return {"schemaVersion": SCHEMA, "status": "error",
                    "reason": "session-or-latest-or-input-required"}, 2
        if not session.is_file():
            return {"schemaVersion": SCHEMA, "status": "error",
                    "reason": f"session-missing:{session.name}"}, 2
        measured = measure_session(session, bool(args.detailed))

    budget = int(args.budget_tokens or DEFAULT_BUDGET)
    result = {"schemaVersion": SCHEMA, "status": "ok", **measured,
              **budget_view(measured, budget)}
    return result, 4 if result["overBudget"] else 0


def footprint(files: list[Path]) -> dict:
    rows, total_chars = [], 0
    for path in files:
        if not path.is_file():
            rows.append({"file": str(path), "missing": True})
            continue
        chars = len(path.read_text(encoding="utf-8", errors="replace"))
        total_chars += chars
        rows.append({"file": str(path), "chars": chars,
                     "estimatedTokens": estimate_tokens(chars)})
    return {"schemaVersion": SCHEMA, "files": rows,
            "totalChars": total_chars,
            "estimatedTokens": estimate_tokens(total_chars)}


def cmd_footprint(args) -> tuple[dict, int]:
    if args.defaults:
        files = [ROOT / "AGENTS.md"]
        files += [Path(p) for p in
                  sorted(glob.glob(str(ROOT / ".windsurf" / "rules" / "*.md")))]
    else:
        files = [Path(f) for f in (args.files or [])]
        if not files:
            return {"schemaVersion": SCHEMA, "status": "error",
                    "reason": "files-or-defaults-required"}, 2
    return footprint(files), 0


def cmd_compare(args) -> tuple[dict, int]:
    before = json.loads(Path(args.before).read_text(encoding="utf-8-sig"))
    after = json.loads(Path(args.after).read_text(encoding="utf-8-sig"))
    b, a = int(before["estimatedTokens"]), int(after["estimatedTokens"])
    delta = round(100.0 * (b - a) / b, 1) if b else 0.0
    target = float(args.target_pct)
    result = {"schemaVersion": SCHEMA, "beforeTokens": b, "afterTokens": a,
              "deltaPct": delta, "targetPct": target,
              "targetMet": delta >= target}
    return result, 0 if result["targetMet"] else 4


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    p = sub.add_parser("status")
    p.add_argument("--session")
    p.add_argument("--latest", action="store_true")
    p.add_argument("--input")
    p.add_argument("--budget-tokens", type=int, default=DEFAULT_BUDGET)
    p.add_argument("--json", action="store_true", help="output is always JSON")
    p.add_argument("--detailed", action="store_true")
    p = sub.add_parser("footprint")
    p.add_argument("--files", nargs="*")
    p.add_argument("--defaults", action="store_true")
    p = sub.add_parser("compare")
    p.add_argument("--before", required=True)
    p.add_argument("--after", required=True)
    p.add_argument("--target-pct", type=float, default=30.0)
    args = parser.parse_args(argv)

    try:
        if args.action == "status":
            result, code = cmd_status(args)
        elif args.action == "footprint":
            result, code = cmd_footprint(args)
        else:
            result, code = cmd_compare(args)
    except (OSError, ValueError, KeyError, TypeError) as error:
        result, code = {"schemaVersion": SCHEMA, "status": "error",
                        "reason": str(error)}, 2
    print(json.dumps(result, ensure_ascii=True))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
