#!/usr/bin/env python3
"""search_decision_query.py - headless JSON client for the internal search
debug surface (QF-06/QF-07).

Endpoints (same /api/internal/** admin-token guard as the db meta lane):
    GET /api/internal/search/decision         SearchDecisionService dry-run
    GET /api/internal/search/runtime-status   routing policy + provider inventory

Commands (run from the Project Root):
    decision --q "..." [--mode AUTO|OFF|FORCE_LIGHT|FORCE_DEEP]
             [--providers NAVER,TAVILY] [--top-k N] [--no-infer-general]
    runtime-status

The decision endpoint never executes a search - it only returns what
SearchDecisionService.decide() would do. This client never calls a paid
provider itself. One-line JSON on stdout.

Exit codes: 0 ok | 2 args/input | 3 unreachable/auth-blocked/endpoint-missing
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.parse
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent))
from meta_display_db_export import live_request  # noqa: E402

SEARCH_API = "/api/internal/search"


def emit(payload: dict, code: int, pretty: bool) -> int:
    if pretty:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(json.dumps(payload, ensure_ascii=False))
    return code


def call(args, path: str):
    """-> (http_status|None, payload, token_source)."""
    shim = SimpleNamespace(
        base_url=getattr(args, "base_url", None),
        timeout=getattr(args, "timeout", 15),
    )
    return live_request(shim, "GET", path)


def fail(cmd: str, code, payload, token_src) -> tuple[dict, int]:
    """-> (error_payload, exit_code)."""
    err = {"cmd": cmd, "via": "live-http", "ok": False,
           "tokenSource": token_src or "none"}
    if code is None:
        err["reason"] = "unreachable"
        err["detail"] = (payload or {}).get("reason") or (payload or {}).get("error")
        return err, 3
    err["httpStatus"] = code
    if code in (401, 403):
        err["reason"] = "auth-blocked"
        err["hint"] = "set X-Admin-Token via env DOMAIN_ALLOWLIST_ADMIN_TOKEN/"
        err["hint"] += "AWX_ADMIN_TOKEN or var/dev-admin-token.txt"
    elif code == 404:
        err["reason"] = "endpoint-missing"
        err["hint"] = ("running server predates /api/internal/search - "
                       "rebuild+reload the app")
    else:
        err["reason"] = (payload or {}).get("error") or "http-" + str(code)
    if isinstance(payload, dict):
        for k in ("error", "reason", "allowed"):
            if k in payload and k not in err:
                err[k] = payload[k]
    return err, 3


def cmd_decision(args) -> int:
    if not args.q or not args.q.strip():
        return emit({"cmd": "decision", "ok": False,
                     "reason": "missing-q", "hint": "pass --q \"...\""},
                    2, args.pretty)
    qs = {"q": args.q}
    if args.mode:
        qs["mode"] = args.mode
    if args.providers:
        qs["providers"] = args.providers
    if args.top_k is not None:
        qs["topK"] = str(args.top_k)
    if args.no_infer_general:
        qs["inferGeneral"] = "false"
    code, payload, tsrc = call(args, SEARCH_API + "/decision?" + urllib.parse.urlencode(qs))
    if code != 200 or not (payload or {}).get("ok"):
        err, exit_code = fail("decision", code, payload, tsrc)
        return emit(err, exit_code, args.pretty)
    out = {"cmd": "decision", "via": "live-http", "ok": True,
           "tokenSource": tsrc or "none", "dryRun": True, "executed": False}
    for k in ("mode", "shouldSearch", "depth", "providers", "topK", "reason"):
        out[k] = payload.get(k)
    return emit(out, 0, args.pretty)


def cmd_runtime_status(args) -> int:
    code, payload, tsrc = call(args, SEARCH_API + "/runtime-status")
    if code != 200 or not (payload or {}).get("ok"):
        err, exit_code = fail("runtime-status", code, payload, tsrc)
        return emit(err, exit_code, args.pretty)
    out = {"cmd": "runtime-status", "via": "live-http",
           "tokenSource": tsrc or "none"}
    out.update(payload)
    return emit(out, 0, args.pretty)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        prog="search_decision_query.py",
        description="Headless JSON client for /api/internal/search (dry-run "
                    "decision + runtime status; no provider call is executed).")
    sub = ap.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("decision", help="SearchDecisionService dry-run")
    d.add_argument("--q", required=True)
    d.add_argument("--mode", choices=["AUTO", "OFF", "FORCE_LIGHT", "FORCE_DEEP"])
    d.add_argument("--providers", help="comma-separated ProviderId names")
    d.add_argument("--top-k", type=int, dest="top_k")
    d.add_argument("--no-infer-general", action="store_true",
                   help="inferSearchFromGeneralQuestion=false")
    d.add_argument("--base-url")
    d.add_argument("--timeout", type=int, default=15)
    d.add_argument("--pretty", action="store_true")
    d.set_defaults(func=cmd_decision)

    s = sub.add_parser("runtime-status", help="search routing/provider status")
    s.add_argument("--base-url")
    s.add_argument("--timeout", type=int, default=15)
    s.add_argument("--pretty", action="store_true")
    s.set_defaults(func=cmd_runtime_status)

    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
