#!/usr/bin/env python3
"""Shared Jev agent-spend ledger — data/agent-handoff/jev-spend/ledger.jsonl.

PASTE_DEVIN_JEV_V2_POSTCODEX_20260930 P-1 + ADDENDUM_JEV_AGENT_BUDGET_20260930.
One append-only JSONL line per real HTTP send to host == ai-gateway.vercel.sh
(timeouts/errors included; retries are separate lines; mock/loopback/dry-run
are never recorded). The agent budget day rolls at KST midnight — unrelated
to the product WP-3 UTC budget day. /data/ is .gitignore'd so the ledger is
never committed; the product "no JSONL" rule applies to Java, not this tool.

Record fields: tsUtc, dayKst, agent, session, purpose, caller, httpStatus,
reason, inputTokens, outputTokens, costUsd, reservedUsd, retryOf, backfilled
(+ srcRef on backfilled rows for idempotent re-run). costUsd stays null when
the gateway did not report one — it is never forged to 0; the period total
counts null as reservedUsd (0.0002). Question text, bodies, keys and sha8
are never stored.

Pre-send gate (gate()): returns a refusal reason for live sends to the
gateway host, else None. Refusals:
  day>=500 sends (all agents, KST day) | period total >=$0.50 |
  now >= 2026-10-07 23:59 KST | any 401/402/403 today | >=2x 429 today |
  AWX_AGENT_SESSION missing | same session >=150.
>=80% of any cap prints SPEND_80 to stderr and still allows the send.
"""
from __future__ import annotations

import datetime
import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]  # scripts/apikit/jev_ledger.py -> src
DEFAULT_LEDGER = ROOT / "data" / "agent-handoff" / "jev-spend" / "ledger.jsonl"

GATEWAY_HOST = "ai-gateway.vercel.sh"
DAY_CAP = 500            # sends per KST day, all agents
SESSION_CAP = 150        # sends per AWX_AGENT_SESSION
PERIOD_CAP_USD = 0.50    # period total
RESERVED_USD = 0.0002    # assumed cost when costUsd is null
WARN_FRACTION = 0.80
GATE_EXIT = 5            # exit code for budget_refused (was unused: 0/2/3 taken)

KST = datetime.timezone(datetime.timedelta(hours=9))
# "2026-10-07 23:59 KST 이후" -> refused from 23:59:00 KST onward.
PERIOD_END_KST = datetime.datetime(2026, 10, 7, 23, 59, 0, tzinfo=KST)

_LOCK_WAIT_S = 2.0


def ledger_path():
    override = os.environ.get("AWX_JEV_LEDGER")
    return Path(override) if override else DEFAULT_LEDGER


def day_kst(dt):
    """KST calendar date (YYYY-MM-DD) for an aware datetime."""
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=datetime.timezone.utc)
    return dt.astimezone(KST).date().isoformat()


def _now_utc(now=None):
    now = now or datetime.datetime.now(datetime.timezone.utc)
    if now.tzinfo is None:
        now = now.replace(tzinfo=datetime.timezone.utc)
    return now


def _cost_float(cost):
    """gateway.cost arrives as a JSON string ('0.0000197'); parse to float.
    Unparseable/missing -> None (recorded as null, never 0)."""
    if isinstance(cost, bool):
        return None
    if isinstance(cost, (int, float)):
        return float(cost)
    if isinstance(cost, str) and cost.strip():
        try:
            return float(cost.strip())
        except ValueError:
            return None
    return None


def iter_entries(path=None):
    p = Path(path) if path else ledger_path()
    try:
        lines = p.read_text(encoding="utf-8").splitlines()
    except OSError:
        return []
    out = []
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        if isinstance(entry, dict):
            out.append(entry)
    return out


def append(entry, path=None):
    """Append one line under a short lock-file exclusive section. A lock that
    cannot be acquired in ~2s never drops the record — the single-line append
    still runs (a missed spend line is worse than a rare interleave)."""
    p = Path(path) if path else ledger_path()
    p.parent.mkdir(parents=True, exist_ok=True)
    lock = p.with_name(p.name + ".lock")
    deadline = time.monotonic() + _LOCK_WAIT_S
    got = False
    while True:
        try:
            fd = os.open(str(lock), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            os.close(fd)
            got = True
            break
        except FileExistsError:
            if time.monotonic() >= deadline:
                break
            time.sleep(0.02)
        except OSError:
            break
    try:
        line = json.dumps(entry, ensure_ascii=False, separators=(",", ":"))
        with open(p, "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    finally:
        if got:
            try:
                os.unlink(str(lock))
            except OSError:
                pass
    return entry


def append_once(entry, path=None):
    """Idempotent append: skipped when an entry with the same srcRef exists."""
    src = entry.get("srcRef")
    if src and any(e.get("srcRef") == src for e in iter_entries(path)):
        return None
    return append(entry, path=path)


def new_entry(*, agent=None, session=None, purpose=None, caller=None,
              http_status=None, reason=None, usage=None, cost=None,
              ts=None, retry_of=None, backfilled=False, src_ref=None):
    ts = _now_utc(ts)
    usage = usage if isinstance(usage, dict) else {}
    entry = {
        "tsUtc": ts.astimezone(datetime.timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z",
        "dayKst": day_kst(ts),
        "agent": agent or os.environ.get("AWX_AGENT_NAME") or "unknown",
        "session": session if session is not None
        else os.environ.get("AWX_AGENT_SESSION"),
        "purpose": purpose or os.environ.get("AWX_JEV_SPEND_PURPOSE") or "probe",
        "caller": caller,
        "httpStatus": http_status,
        "reason": reason,
        "inputTokens": usage.get("inputTokens"),
        "outputTokens": usage.get("outputTokens"),
        "costUsd": _cost_float(cost),
        "reservedUsd": RESERVED_USD,
        "retryOf": retry_of,
        "backfilled": bool(backfilled),
    }
    if src_ref:
        entry["srcRef"] = src_ref
    return entry


def summarize(now=None, session=None, path=None):
    """Cross-agent totals for the gate and the JEV_SPEND report line."""
    now = _now_utc(now)
    entries = iter_entries(path)
    today = day_kst(now)
    day_entries = [e for e in entries if e.get("dayKst") == today]
    total = 0.0
    for e in entries:
        c = e.get("costUsd")
        total += c if isinstance(c, (int, float)) and not isinstance(c, bool) \
            else RESERVED_USD
    fails = {}
    for e in entries:
        s = e.get("httpStatus")
        if isinstance(s, int) and s != 200:
            bucket = str(s) if s < 500 else "5xx"
            fails[bucket] = fails.get(bucket, 0) + 1
    return {
        "entries": len(entries),
        "dayKst": today,
        "dayCount": len(day_entries),
        "totalUsd": total,
        "session": session,
        "sessionCount": sum(1 for e in entries
                            if session and e.get("session") == session),
        "authFailToday": any(isinstance(e.get("httpStatus"), int)
                             and e["httpStatus"] in (401, 402, 403)
                             for e in day_entries),
        "rateLimitToday": sum(1 for e in day_entries
                              if e.get("httpStatus") == 429),
        "fails": fails,
    }


def gate(host, env=None, now=None, path=None):
    """Pre-send block for the real gateway host only. Returns a refusal reason
    string, or None when the send may proceed. Callers must NOT send on a
    reason and should exit GATE_EXIT with 'budget_refused:<reason>'."""
    if (host or "").strip().lower() != GATEWAY_HOST:
        return None
    env = os.environ if env is None else env
    now = _now_utc(now)
    session = (env.get("AWX_AGENT_SESSION") or "").strip() or None
    s = summarize(now=now, session=session, path=path)
    if s["dayCount"] >= DAY_CAP:
        return "day-cap"
    if s["totalUsd"] >= PERIOD_CAP_USD:
        return "period-cap"
    if now.astimezone(KST) >= PERIOD_END_KST:
        return "period-ended"
    if s["authFailToday"]:
        return "auth-fail-today"
    if s["rateLimitToday"] >= 2:
        return "rate-limit-today"
    if not session:
        return "session-missing"
    if s["sessionCount"] >= SESSION_CAP:
        return "session-cap"
    if (s["dayCount"] >= DAY_CAP * WARN_FRACTION
            or s["totalUsd"] >= PERIOD_CAP_USD * WARN_FRACTION
            or s["sessionCount"] >= SESSION_CAP * WARN_FRACTION):
        print("[AWX][jev-ledger] SPEND_80 day=%d/%d session=%d/%d "
              "total=$%.5f/$%.2f" % (s["dayCount"], DAY_CAP,
                                     s["sessionCount"], SESSION_CAP,
                                     s["totalUsd"], PERIOD_CAP_USD),
              file=sys.stderr)
    return None
