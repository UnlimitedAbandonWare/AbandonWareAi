"""Read-only Jev spend tally. Does not write the ledger or campaign journal.

Primary sources are the ledger jsonl and campaign journals. Missing ledger
falls back to [AWX][api-spend] lines. Ledger amounts are floats; sums are
shown as Decimal and labelled float-ledger.

Exit 0 under 80% of the legacy caps, 3 at or above 80%, 5 at or above 100%.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LEDGER = ROOT / "data" / "agent-handoff" / "jev-spend" / "ledger.jsonl"
PERIOD_CAP = Decimal("0.50")
DAY_CAP = Decimal(500)
SESSION_CAP = Decimal(150)
WARN = Decimal("0.8")


def _budget():
    path = Path(__file__).with_name("jev_campaign_budget.py")
    spec = importlib.util.spec_from_file_location("jev_campaign_budget", path)
    if spec is None or spec.loader is None:
        return None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _decimal_float(value) -> Decimal | None:
    if isinstance(value, bool) or not isinstance(value, (int, float, str)):
        return None
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None


def read_ledger(path: Path) -> list:
    if not path.is_file():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(row, dict):
            rows.append(row)
    return rows


def read_spend_lines(text: str) -> list:
    rows = []
    marker = "[AWX][api-spend]"
    for line in text.splitlines():
        index = line.find(marker)
        if index < 0:
            continue
        raw = line[index + len(marker):].strip()
        try:
            row = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if isinstance(row, dict):
            rows.append(row)
    return rows


def _agent_days(rows: list) -> list:
    buckets: dict[tuple, dict] = {}
    for row in rows:
        agent = str(row.get("agent") or "unknown")
        day = str(row.get("dayKst") or "unknown")
        key = (agent, day)
        slot = buckets.setdefault(key, {"agent": agent, "dayKst": day, "count": 0, "sumUsd": Decimal("0")})
        slot["count"] += 1
        amount = _decimal_float(row.get("costUsd"))
        if amount is None:
            amount = _decimal_float(row.get("reservedUsd")) or Decimal("0")
        slot["sumUsd"] += amount
    out = []
    for slot in buckets.values():
        out.append({
            "agent": slot["agent"],
            "dayKst": slot["dayKst"],
            "count": slot["count"],
            "sumUsd": format(slot["sumUsd"], "f"),
        })
    out.sort(key=lambda item: (item["dayKst"], item["agent"]))
    return out


def _campaigns(directory: Path | None) -> list:
    if directory is None or not directory.is_dir():
        return []
    module = _budget()
    if module is None:
        return []
    found = []
    for path in sorted(directory.glob("*.jsonl")):
        events = []
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(row, dict):
                events.append(row)
        try:
            state = module.fold(events)
        except Exception:
            continue
        found.append({
            "campaignId": path.stem,
            "settledUsd": module.money_str(state["settled"]),
            "inflightUsd": module.money_str(state["inflight"]),
            "unknownHeldUsd": module.money_str(state["held"]),
        })
    return found


def _ratio(used: Decimal, cap: Decimal) -> Decimal:
    if cap == 0:
        return Decimal("0")
    return used / cap


def summarize(rows: list, directory: Path | None, source: str) -> dict:
    total = Decimal("0")
    nulls = 0
    for row in rows:
        amount = _decimal_float(row.get("costUsd"))
        if amount is None:
            nulls += 1
            amount = _decimal_float(row.get("reservedUsd")) or Decimal("0")
        total += amount
    by_session: dict[str, int] = {}
    by_day: dict[str, int] = {}
    for row in rows:
        session = str(row.get("session") or "")
        day = str(row.get("dayKst") or "")
        if session:
            by_session[session] = by_session.get(session, 0) + 1
        if day:
            by_day[day] = by_day.get(day, 0) + 1
    max_session = max(by_session.values()) if by_session else 0
    max_day = max(by_day.values()) if by_day else 0
    ratios = {
        "period": _ratio(total, PERIOD_CAP),
        "day": _ratio(Decimal(max_day), DAY_CAP),
        "session": _ratio(Decimal(max_session), SESSION_CAP),
    }
    worst = max(ratios.values()) if ratios else Decimal("0")
    if worst >= 1:
        exit_code = 5
    elif worst >= WARN:
        exit_code = 3
    else:
        exit_code = 0
    return {
        "amountSource": "float-ledger" if source == "ledger" else source,
        "note": "ledger costUsd values are floats; displayed sums are Decimal conversions",
        "legacyCaps": {"periodUsd": "0.50", "dayCount": 500, "sessionCount": 150},
        "totals": {
            "rows": len(rows),
            "nullCostRows": nulls,
            "sumUsd": format(total, "f"),
            "maxDayCount": max_day,
            "maxSessionCount": max_session,
        },
        "ratios": {key: format(value, "f") for key, value in ratios.items()},
        "byAgentDay": _agent_days(rows),
        "campaigns": _campaigns(directory),
        "exit": exit_code,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read-only spend tally. Writes nothing.")
    parser.add_argument("--ledger", default=str(DEFAULT_LEDGER))
    parser.add_argument("--campaign-dir")
    parser.add_argument("--spend-log")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    ledger = Path(args.ledger)
    rows = read_ledger(ledger) if ledger.is_file() else []
    source = "ledger" if rows or ledger.is_file() else "absent"
    if not rows and args.spend_log:
        try:
            rows = read_spend_lines(Path(args.spend_log).read_text(encoding="utf-8"))
        except OSError:
            print("usage spend-log", file=sys.stderr)
            return 2
        source = "spend-line"
    directory = Path(args.campaign_dir) if args.campaign_dir else None
    body = summarize(rows, directory, source)
    print(json.dumps(body, ensure_ascii=True))
    return int(body["exit"])


if __name__ == "__main__":
    sys.exit(main())
