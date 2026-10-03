#!/usr/bin/env python3
"""settings_defaults_budget_meter -- audit-only budget report over the
Codex settings-defaults ledger(s).

Reads - never writes - files under
    data/agent-handoff/codex-settings-defaults-*/
    data/agent-handoff/codex-autonomy/codex-settings-defaults-*/
and reports the Codex budget contract metrics:

    chat sends n/25            (API_CALLS ledger rows / journal evidence)
    external api calls n/cap   (same ledger, non-loopback or paid routes)
    server restarts n/2        (journal/checkpoint evidence only)
    same-failure fix attempts n/2
    public-site sends          (kro.kr / non-loopback targets)
    retries after 401/403/429/400

A metric is UNKNOWN when its evidence file is missing or in a format this
tool cannot parse - UNKNOWN is reported, never rewritten to 0. This tool
creates no counters and no runner for Codex; it is strictly an auditor.

    report [--codex-glob GLOB] [--json] [--out FILE]
    exit 0 always (audit output is data, not a gate)
"""
from __future__ import annotations

import argparse
import glob
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = "awx.settings-defaults-budget-meter.v1"
LEDGER_GLOBS = (
    "data/agent-handoff/codex-settings-defaults-*",
    "data/agent-handoff/codex-autonomy/codex-settings-defaults-*",
)
STOP_CODES = {400, 401, 403, 429}
PUBLIC_RE = re.compile(r"kro\.kr|abandonwareai", re.IGNORECASE)
RESTART_RE = re.compile(r"restart|재시작|기동|launcher", re.IGNORECASE)


def _now_utc() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, UnicodeDecodeError):
        return None


def _rel(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        return str(path)


def _api_call_records(ledger_dir: Path):
    """-> (records, source) from an api_call_budget-style ledger pair."""
    for name in ("API_CALLS.md.json", "API_CALLS.json"):
        p = ledger_dir / name
        if p.is_file():
            doc = _read_json(p)
            if isinstance(doc, dict) and isinstance(doc.get("records"), list):
                return doc["records"], _rel(p), doc.get("cap")
    md = ledger_dir / "API_CALLS.md"
    if md.is_file():
        try:
            rows = []
            for line in md.read_text(encoding="utf-8").splitlines():
                if not line.startswith("|") or "route" in line \
                        or line.startswith("|---"):
                    continue
                cells = [c.strip() for c in line.strip("|").split("|")]
                if len(cells) >= 5 and cells[3].isdigit():
                    rows.append({"time": cells[0], "route": cells[1],
                                 "provider": cells[2],
                                 "http": int(cells[3]), "result": cells[4],
                                 "note": cells[5] if len(cells) > 5 else ""})
            return rows, _rel(md), None
        except OSError:
            pass
    return None, None, None


def _journal_events(ledger_dir: Path):
    doc = _read_json(ledger_dir / "journal.json")
    if not isinstance(doc, dict):
        return None, None
    return doc.get("events") or [], doc


def _scan_files(ledger_dir: Path) -> dict:
    files = {"journal": (ledger_dir / "journal.json").is_file(),
             "apiCalls": any((ledger_dir / n).is_file()
                             for n in ("API_CALLS.md", "API_CALLS.md.json",
                                       "API_CALLS.json")),
             "report": (ledger_dir / "REPORT.md").is_file(),
             "experimentResults": sorted(
                 str(p.relative_to(ledger_dir))
                 for p in ledger_dir.rglob("*")
                 if p.is_file() and re.search(
                     r"experiment|result|answer|response", p.name,
                     re.IGNORECASE))[:40]}
    return files


def audit_ledger(ledger_dir: Path) -> dict:
    files = _scan_files(ledger_dir)
    metrics = {"chatSends": {"value": "UNKNOWN", "cap": 25},
               "externalApiCalls": {"value": "UNKNOWN"},
               "restarts": {"value": "UNKNOWN", "cap": 2},
               "sameFailureFixAttempts": {"value": "UNKNOWN", "cap": 2},
               "publicSiteSends": {"value": "UNKNOWN"},
               "retriesAfterStopCodes": {"value": "UNKNOWN"}}
    evidence = []

    records, src, cap = _api_call_records(ledger_dir)
    if records is not None:
        metrics["chatSends"] = {"value": len(records), "cap": cap or 25,
                                "source": src}
        public = [r for r in records if PUBLIC_RE.search(
            f"{r.get('route','')} {r.get('note','')}")]
        metrics["publicSiteSends"] = {"value": len(public), "source": src}
        retries = 0
        seen_stop = False
        for r in records:
            try:
                code = int(r.get("http"))
            except (TypeError, ValueError):
                continue
            if seen_stop:
                retries += 1
            if code in STOP_CODES:
                seen_stop = True
        metrics["retriesAfterStopCodes"] = {"value": retries, "source": src}
        evidence.append(src)

    events, journal = _journal_events(ledger_dir)
    if events is not None:
        evidence.append("journal.json")
        restarts = sum(1 for e in events
                       if RESTART_RE.search(str(e.get("text", ""))))
        metrics["restarts"] = {"value": restarts, "cap": 2,
                               "source": "journal.json events text scan"
                               " (mentions, not authority)"}
        if records is None:
            send_hits = sum(1 for e in events
                            if re.search(r"채팅|sends|/chat", str(e.get("text", ""))))
            metrics["chatSends"] = {
                "value": "UNKNOWN", "cap": 25,
                "note": "no API_CALLS ledger; "
                        f"journal mention-count={send_hits} not a counter"}
        fix_hits = [e for e in events
                    if re.search(r"같은 실패|same.fail|retry.*fix|재수정",
                                 str(e.get("text", "")), re.IGNORECASE)]
        if fix_hits:
            metrics["sameFailureFixAttempts"] = {
                "value": "UNKNOWN",
                "note": f"{len(fix_hits)} journal events mention repeated "
                        "fixes; no structured counter"}

    status = "NO_LEDGER_FILES"
    if files["journal"] or files["apiCalls"]:
        status = "READ"
    return {"ledgerDir": str(ledger_dir), "files": files,
            "metrics": metrics, "evidenceRead": evidence, "status": status}


def cmd_report(args) -> int:
    globs = args.codex_glob or list(LEDGER_GLOBS)
    dirs = []
    for g in globs:
        dirs.extend(Path(p) for p in glob.glob(str(ROOT / g))
                    if Path(p).is_dir())
    dirs = sorted(set(dirs))
    report = {"schema": SCHEMA, "createdAtUtc": _now_utc(),
              "status": "PENDING" if not dirs else "READ",
              "note": "audit only - Codex ledger is never written; "
                      "UNKNOWN means unreadable/missing evidence, not 0",
              "ledgers": [audit_ledger(d) for d in dirs]}
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(json.dumps(report, indent=2,
                                             ensure_ascii=False),
                                  encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("report")
    p.add_argument("--codex-glob", action="append")
    p.add_argument("--out")
    args = ap.parse_args(argv)
    return {"report": cmd_report}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
