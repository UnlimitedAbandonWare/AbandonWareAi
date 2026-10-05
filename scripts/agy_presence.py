#!/usr/bin/env python3
"""agy presence SSOT (stdlib only).

Single source of truth for whether agy-cli (Antigravity CLI) is usable by
the fleet (Devin, orchestra router, launchers). Writes/reads
data/agent-handoff/agy-presence.json:

    {"status": "ONLINE|SWITCHING|OFFLINE", "account": "<alias>",
     "updatedAtKst": "<iso8601 KST>"}

Writers:
  - Start-Agy-CLI.bat -> ONLINE before agy.exe launches, OFFLINE after it
    exits (plus agy_session_seed.py --on-exit snapshot).
  - scripts/agy_auth_switch.ps1 'use' -> SWITCHING before the credential
    swap, OFFLINE on every exit path after that point (agy is absent).

Readers treat a missing/corrupt file as status=UNKNOWN (fail-safe
unavailable). Stale semantics (AUTO 2026-10-05, option a): a SWITCHING
entry with no refresh for PRESENCE_TTL_SECONDS (30s) collapses to OFFLINE
-- a crashed account switch must not pin the fleet in 'wait'. ONLINE is
explicit launcher state: it stays authoritative until OFFLINE is written,
but is-available flags it "stale" once it exceeds the TTL so a strict
consumer may degrade instead of trust.

Account is an alias only (^[A-Za-z0-9_-]{1,32}$, e.g. saved names like
"A"/"B"); anything resembling an e-mail or token is stored as MASKED.
Never writes credential material, tokens, or e-mail addresses.

CLI:
  set --status ONLINE|SWITCHING|OFFLINE [--account NAME]
  get                     -> full doc + effective status (exit 0)
  is-available            -> exit 0 when effective status is ONLINE, else 1

Override the state file with AWX_AGY_PRESENCE_FILE (tests/offline use).
"""

import argparse
import json
import os
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PATH = ROOT / "data" / "agent-handoff" / "agy-presence.json"
KST = timezone(timedelta(hours=9))
PRESENCE_TTL_SECONDS = int(os.environ.get("AWX_AGY_PRESENCE_TTL_S", "30"))
STATUSES = ("ONLINE", "SWITCHING", "OFFLINE")
SAFE_NAME = re.compile(r"^[A-Za-z0-9_-]{1,32}$")
EMAILISH = re.compile(r"@|[A-Za-z0-9+/=_-]{24,}")


def presence_path() -> Path:
    override = os.environ.get("AWX_AGY_PRESENCE_FILE")
    return Path(override) if override else DEFAULT_PATH


def now_kst() -> datetime:
    return datetime.now(KST)


def sanitize_account(name):
    """Alias only: strip anything that could be an e-mail or token."""
    if not name:
        return "UNSAVED"
    name = str(name).strip()
    if SAFE_NAME.fullmatch(name) and not EMAILISH.search(name):
        return name
    return "MASKED"


def read_presence(path=None):
    path = path or presence_path()
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(doc, dict):
            return doc
    except Exception:
        pass
    return {"status": "UNKNOWN", "account": "UNSAVED", "updatedAtKst": None}


def age_seconds(doc):
    ts = doc.get("updatedAtKst")
    if not ts:
        return None
    try:
        dt = datetime.fromisoformat(str(ts))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=KST)
    except ValueError:
        return None
    return (now_kst() - dt).total_seconds()


def effective_status(doc):
    """Stale-collapse: SWITCHING older than the TTL is a crashed switch.

    Returns (effectiveStatus, stale, ageSeconds)."""
    raw = str(doc.get("status") or "UNKNOWN")
    age = age_seconds(doc)
    stale = age is None or age > PRESENCE_TTL_SECONDS
    eff = raw if raw in STATUSES else "UNKNOWN"
    if eff == "SWITCHING" and stale:
        eff = "OFFLINE"
    return eff, stale, age


def write_presence(doc, path=None):
    path = path or presence_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    data = json.dumps(doc, ensure_ascii=False, indent=2) + "\n"
    tmp.write_text(data, encoding="utf-8")
    os.replace(tmp, path)


def describe(doc):
    eff, stale, age = effective_status(doc)
    return {
        "status": doc.get("status"),
        "effectiveStatus": eff,
        "account": doc.get("account", "UNSAVED"),
        "updatedAtKst": doc.get("updatedAtKst"),
        "stale": stale,
        "staleAfterS": PRESENCE_TTL_SECONDS,
        "ageSeconds": round(age, 1) if age is not None else None,
        "path": str(presence_path().relative_to(ROOT))
        if str(presence_path()).startswith(str(ROOT)) else str(presence_path()),
    }


def main() -> int:
    ap = argparse.ArgumentParser(prog="agy_presence.py")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sp = sub.add_parser("set")
    sp.add_argument("--status", required=True, choices=STATUSES)
    sp.add_argument("--account", default=None)
    sub.add_parser("get")
    sub.add_parser("is-available")
    args = ap.parse_args()

    if args.cmd == "set":
        doc = read_presence()
        account = (sanitize_account(args.account) if args.account is not None
                   else doc.get("account") or "UNSAVED")
        doc = {"status": args.status, "account": account,
               "updatedAtKst": now_kst().isoformat(timespec="seconds")}
        try:
            write_presence(doc)
        except Exception as exc:  # presence must never break the caller
            print(json.dumps({"ok": False, "error": type(exc).__name__}))
            return 0
        print(json.dumps(describe(doc), ensure_ascii=False))
        return 0

    doc = read_presence()
    if args.cmd == "get":
        print(json.dumps(describe(doc), ensure_ascii=False))
        return 0

    # is-available
    eff, _, _ = effective_status(doc)
    available = eff == "ONLINE"
    out = describe(doc)
    out["available"] = available
    out["reason"] = ("online" if available else
                     "missing-or-unknown" if eff == "UNKNOWN" else
                     eff.lower())
    print(json.dumps(out, ensure_ascii=False))
    return 0 if available else 1


if __name__ == "__main__":
    sys.exit(main())
