#!/usr/bin/env python3
"""api_call_budget -- the 25-call live-verification ledger for Codex plan9.

It never calls an API itself. Codex records each live /chat send here; the
ledger enforces the directive's cap and the no-retry rule.

  init   --ledger PATH --cap 25          create PATH (API_CALLS.md table) + PATH.json
  record --ledger PATH --route ID --http CODE --result ok|fail
         [--provider NAME] [--note TXT] [--time ISO]
  status --ledger PATH [--catalog FILE]  counts + remaining + provider split
  check  --ledger PATH                   exit 0 headroom / 3 cap reached /
                                         4 a 401|403|429 was recorded

record exit codes: 0 written; 3 cap already exhausted (row still written and
marked over_cap); 4 http in {401,403,429} -> STOP_AND_ROOT_CAUSE (row written).
Other nonzero http codes do not auto-stop.

Provider judgment comes from --catalog (a saved `GET /api/chat/models` JSON) or
an explicit --provider. The model/route NAME is never used to guess a
provider. Ledger format matches the directive's API_CALLS.md:
| time(UTC) | route | provider | http | result | note |
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "awx.api-call-budget.v1"
STOP_CODES = {401, 403, 429}
HEADER = "| time(UTC) | route | provider | http | result | note |\n|---|---|---|---|---|---|\n"


def _paths(ledger: str):
    p = Path(ledger)
    return p, Path(str(p) + ".json")


def _load(ledger: str):
    p, jp = _paths(ledger)
    if not jp.is_file():
        print(f"ledger not initialized: {jp} (run init first)", file=sys.stderr)
        sys.exit(2)
    try:
        return json.loads(jp.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"ledger sidecar unreadable: {e}", file=sys.stderr)
        sys.exit(2)


def _save(ledger: str, data: dict):
    p, jp = _paths(ledger)
    jp.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    lines = ["# API call budget ledger", "",
             f"cap: {data['cap']}  used: {len(data['records'])}  "
             f"remaining: {data['cap'] - len(data['records'])}",
             f"stop: {'STOP_AND_ROOT_CAUSE' if data.get('stop') else 'no'}", "",
             HEADER.rstrip("\n")]
    for r in data["records"]:
        note = (r.get("note") or "").replace("|", "\\|")
        lines.append("| %s | %s | %s | %s | %s | %s |" % (
            r["time"], r["route"], r.get("provider") or "", r["http"],
            r["result"], note))
    p.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _catalog_providers(path: str):
    """route/model id -> provider from a saved /api/chat/models JSON.
    Accepts a top-level list or {models:[...]}. Returns {} when unreadable."""
    try:
        doc = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    items = doc if isinstance(doc, list) else doc.get("models") or doc.get("data") or []
    out = {}
    for it in items:
        if not isinstance(it, dict):
            continue
        ident = it.get("id") or it.get("modelId") or it.get("route")
        prov = it.get("provider") or it.get("ownedBy") or it.get("owned_by")
        if ident and prov:
            out[str(ident)] = str(prov)
    return out


def cmd_init(args) -> int:
    p, jp = _paths(args.ledger)
    if jp.exists() and not args.force:
        print(f"ledger exists: {jp} (use --force to reinitialize)", file=sys.stderr)
        return 2
    p.parent.mkdir(parents=True, exist_ok=True)
    data = {"schema": SCHEMA, "cap": int(args.cap), "records": [], "stop": False,
            "createdAt": datetime.now(timezone.utc).isoformat(timespec="seconds")}
    _save(str(p), data)
    print(json.dumps({"ledger": str(p), "cap": data["cap"]}))
    return 0


def cmd_record(args) -> int:
    data = _load(args.ledger)
    rec = {
        "time": args.time or datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "route": args.route, "http": int(args.http), "result": args.result,
    }
    if args.provider:
        rec["provider"] = args.provider
    elif args.catalog:
        rec["provider"] = _catalog_providers(args.catalog).get(args.route, "unknown")
    if args.note:
        rec["note"] = args.note
    used = len(data["records"])
    over_cap = used >= data["cap"]
    if over_cap:
        rec["overCap"] = True
    data["records"].append(rec)
    if rec["http"] in STOP_CODES:
        data["stop"] = True
        data["stopReason"] = f"http_{rec['http']}_recorded"
    _save(args.ledger, data)
    remaining = data["cap"] - len(data["records"])
    print(json.dumps({"recorded": rec, "used": len(data["records"]),
                      "remaining": max(0, remaining),
                      "stop": bool(data.get("stop"))}, ensure_ascii=False))
    if rec["http"] in STOP_CODES:
        print("STOP_AND_ROOT_CAUSE: 401/403/429 recorded - do not retry; "
              "investigate first", file=sys.stderr)
        return 4
    if over_cap:
        print(f"BUDGET_EXCEEDED: cap {data['cap']} already used", file=sys.stderr)
        return 3
    return 0


def cmd_status(args) -> int:
    data = _load(args.ledger)
    prov = _catalog_providers(args.catalog) if args.catalog else {}
    by_provider = {}
    for r in data["records"]:
        p = r.get("provider") or prov.get(r["route"], "unknown")
        by_provider[p] = by_provider.get(p, 0) + 1
    used = len(data["records"])
    print(json.dumps({
        "cap": data["cap"], "used": used,
        "remaining": max(0, data["cap"] - used),
        "stop": bool(data.get("stop")),
        "stopReason": data.get("stopReason"),
        "byProvider": by_provider,
        "records": data["records"],
    }, ensure_ascii=False))
    return 0


def cmd_check(args) -> int:
    data = _load(args.ledger)
    used = len(data["records"])
    remaining = max(0, data["cap"] - used)
    out = {"cap": data["cap"], "used": used, "remaining": remaining,
           "stop": bool(data.get("stop"))}
    print(json.dumps(out))
    if data.get("stop"):
        print("STOP_AND_ROOT_CAUSE is set - a 401/403/429 was recorded",
              file=sys.stderr)
        return 4
    if remaining <= 0:
        print("cap reached - no more live calls allowed", file=sys.stderr)
        return 3
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("init")
    p.add_argument("--ledger", required=True)
    p.add_argument("--cap", type=int, default=25)
    p.add_argument("--force", action="store_true")
    p = sub.add_parser("record")
    p.add_argument("--ledger", required=True)
    p.add_argument("--route", required=True)
    p.add_argument("--http", type=int, required=True)
    p.add_argument("--result", choices=["ok", "fail"], required=True)
    p.add_argument("--provider")
    p.add_argument("--catalog")
    p.add_argument("--note")
    p.add_argument("--time")
    p = sub.add_parser("status")
    p.add_argument("--ledger", required=True)
    p.add_argument("--catalog")
    p = sub.add_parser("check")
    p.add_argument("--ledger", required=True)
    args = ap.parse_args(argv)
    return {"init": cmd_init, "record": cmd_record,
            "status": cmd_status, "check": cmd_check}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
