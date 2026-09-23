"""Local-GPU failure -> API fallback decision helper (demo-1, RTX 3090 power peaks).

When local Ollama / embedding / LLM calls time out, go silent, or power-limit
is suspected, classify the failure once, cap local retries (<=1, 0 for
power/oom/driver signs), and pick the next route from configs/api-routing.yaml
in policy order free_local -> low_cost -> paid_quality.

Read-only: no network, no subprocess, no secrets (env NAMES only), no Git.
Subcommands: classify | route | decide. JSON on stdout.
Exit codes: 0 ok (decision in JSON) - 2 usage/input error - 3 routing config unreadable.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

SCHEMA = "awx.gpu-power-fallback.v1"
LOCAL_RETRY_BUDGET = 1  # AGENTS.md DEMO1-RTX3090-WATCH / $demo1-gpu-power-fallback
PAID_GATE_ENV = "AWX_AGENT_ALLOW_PAID_MODELS"  # configs/agent-api-spend-guard.yaml
TIER_ORDER = ("free_local", "low_cost", "paid_quality")
PURPOSES = ("search", "asr", "embed", "llm")

# Reason codes. Patterns also accept scripts/rtx3090_health_watch.ps1 anomaly ids.
REASON_PATTERNS = (
    ("power_limit_suspect", (
        r"hw_power_brake", r"hw_slowdown", r"sw_power_cap", r"power[_ ]?limit",
        r"power[_ ]?brake", r"power[_ ]?peak", r"throttl", r"전력", r"위이잉")),
    ("driver_reset", (
        r"nvlddmkm", r"\btdr\b", r"\bxid\b", r"driver.*(?:reset|recover|crash)",
        r"gpu.*(?:lost|reset|hang)", r"new_error_events", r"smi_error_text",
        r"smi_failed")),
    ("oom_suspect", (
        r"out of memory", r"\boom\b", r"cuda.*(?:memory|alloc)", r"allocat\w* fail",
        r"vram", r"insufficient memory")),
    ("timeout", (
        r"timeout", r"timed out", r"deadline", r"ollama_timeout_streak",
        r"TIMEOUT_SOFT", r"read.*stalled")),
    ("no_response", (
        r"connection refused", r"econnrefused", r"unreachable", r"no response",
        r"empty response", r"unexpected eof", r"reset by peer", r"\b5\d\d\b")),
    ("model_missing", (
        r"model.*not found", r"no such model", r"\b404\b", r"manifest unknown")),
)

# Reasons where another local attempt can re-trigger the spike or cannot help.
NO_LOCAL_RETRY = frozenset({"power_limit_suspect", "driver_reset", "oom_suspect"})
TRANSIENT = frozenset({"timeout", "no_response"})


def classify_local_inference_failure(text):
    """Map an error string / watch signal id to a stable reason code."""
    haystack = (text or "").strip().lower()
    if not haystack:
        return "unknown"
    for reason, patterns in REASON_PATTERNS:
        if any(re.search(p, haystack) for p in patterns):
            return reason
    return "unknown"


def _scalar(text):
    text = text.strip()
    if text.startswith("[") and text.endswith("]"):
        return [item.strip() for item in text[1:-1].split(",") if item.strip()]
    return text.strip('"').strip("'")


def load_routing(root):
    """Parse just routes.<purpose> entries (id/tier/env) from api-routing.yaml.

    Deliberately a tiny subset parser — stdlib only, no PyYAML dependency.
    The routes block has a stable shape (`  <purpose>:` then `    - id:` items);
    anything unreadable raises ValueError -> caller reports
    routing-config-unreadable.
    """
    path = Path(root) / "configs" / "api-routing.yaml"
    lines = path.read_text(encoding="utf-8").splitlines()
    routes, purpose, current, in_routes = {}, None, None, False
    for raw in lines:
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip())
        text = line.strip()
        if indent == 0:
            in_routes = text == "routes:"
            purpose = None
            continue
        if not in_routes:
            continue
        if indent == 2 and text.endswith(":"):
            purpose = text[:-1]
            routes[purpose] = []
            current = None
        elif indent == 4 and text.startswith("- ") and purpose:
            current = {}
            routes[purpose].append(current)
            item = text[2:].strip()
            if ":" in item:
                key, _, val = item.partition(":")
                current[key.strip()] = _scalar(val)
        elif indent >= 6 and current is not None and ":" in text:
            key, _, val = text.partition(":")
            current[key.strip()] = _scalar(val)
    if not routes:
        raise ValueError("routes-block-missing")
    return {"routes": routes}


def suggest_fallback_route(purpose, config, allow_paid=False, failed_route_ids=()):
    """Ordered non-local candidates for purpose, honoring the paid gate.

    Returns list of {id, tier, env, allowed, paidGate?} in policy order.
    """
    if purpose not in PURPOSES:
        raise ValueError("purpose-unknown")
    routes = (config or {}).get("routes", {}).get(purpose, []) or []
    skipped = set(failed_route_ids) | {
        r.get("id") for r in routes if r.get("tier") == "free_local"}
    candidates = []
    for tier in TIER_ORDER:
        for route in routes:
            if route.get("tier") != tier or route.get("id") in skipped:
                continue
            paid = tier == "paid_quality"
            env = route.get("env") or []
            entry = {
                "id": route.get("id"),
                "tier": tier,
                "env": list(env) if isinstance(env, list) else [env],
                "allowed": bool(allow_paid) or not paid,
            }
            if paid:
                entry["paidGate"] = PAID_GATE_ENV
            candidates.append(entry)
    return candidates


def decide(purpose, text, attempts=0, allow_paid=False, root="."):
    """Full decision: reason + local-retry budget + fallback route + report line."""
    reason = classify_local_inference_failure(text)
    attempts = max(0, int(attempts))
    budget = LOCAL_RETRY_BUDGET if (reason in TRANSIENT and attempts == 0) else 0
    if reason in NO_LOCAL_RETRY:
        budget = 0
    config = load_routing(root)
    candidates = suggest_fallback_route(
        purpose, config, allow_paid=allow_paid,
        failed_route_ids=[r for r in _local_route_ids(config, purpose)])
    allowed = [c for c in candidates if c["allowed"]]
    if allowed:
        action, nxt = "fallback_to_api", allowed[0]
    elif candidates:
        action, nxt = "stop_paid_not_authorized", candidates[0]
    else:
        action, nxt = "stop_no_route", None
    decision = {
        "schema": SCHEMA,
        "purpose": purpose,
        "reason": reason,
        "localAttemptsObserved": attempts,
        "localRetryBudget": budget,
        "auto": True,  # first classified failure falls back without a question card
        "action": action,
        "nextRoute": nxt,
        "candidates": candidates,
        "apiSpend": {
            "logPrefix": "[AWX][api-spend]",
            "why": "local_failover",
            "purpose": purpose,
            "errorClass": reason,
            "provider": nxt["id"] if nxt else "none",
            "tier": nxt["tier"] if nxt else "none",
        },
        "report": _report_line(reason, action, nxt),
        "hardStops": [
            "no-secret-values-in-logs-or-commits",
            "paid-tier-requires-" + PAID_GATE_ENV,
            "no-add-A-or-push",
            "no-routing-yaml-rewrite-for-one-incident",
        ],
    }
    return decision


def _local_route_ids(config, purpose):
    return [r.get("id") for r in (config or {}).get("routes", {}).get(purpose, [])
            if r.get("tier") == "free_local"]


def _report_line(reason, action, nxt):
    if action == "fallback_to_api":
        return (f"로컬 GPU 불안정(reason={reason}) -> API 폴백: "
                f"{nxt['id']} ({nxt['tier']})")
    if action == "stop_paid_not_authorized":
        return (f"로컬 GPU 불안정(reason={reason}) -> 폴백 후보가 paid 뿐: "
                f"{PAID_GATE_ENV} 없이 중단")
    return f"로컬 GPU 불안정(reason={reason}) -> 폴백 후보 없음"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=".")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_cls = sub.add_parser("classify", help="reason code only")
    p_cls.add_argument("--text", required=True, help="error text or watch signal id")

    p_route = sub.add_parser("route", help="ordered fallback candidates")
    p_route.add_argument("--purpose", required=True, choices=PURPOSES)
    p_route.add_argument("--allow-paid", action="store_true",
                         help="defaults to env " + PAID_GATE_ENV)

    p_dec = sub.add_parser("decide", help="reason + retry budget + next route")
    p_dec.add_argument("--purpose", required=True, choices=PURPOSES)
    p_dec.add_argument("--text", required=True)
    p_dec.add_argument("--attempts", type=int, default=0,
                       help="local attempts already made this incident")
    p_dec.add_argument("--allow-paid", action="store_true")

    args = parser.parse_args(argv)
    allow_paid = getattr(args, "allow_paid", False) or bool(
        os.environ.get(PAID_GATE_ENV))
    try:
        if args.cmd == "classify":
            out = {"schema": SCHEMA,
                   "reason": classify_local_inference_failure(args.text)}
        elif args.cmd == "route":
            out = {"schema": SCHEMA, "purpose": args.purpose,
                   "candidates": suggest_fallback_route(
                       args.purpose, load_routing(args.root), allow_paid)}
        else:
            out = decide(args.purpose, args.text, args.attempts,
                         allow_paid, root=args.root)
    except ValueError:
        print(json.dumps({"schema": SCHEMA, "ok": False,
                          "reason": "invalid-input"}))
        return 2
    except Exception:
        print(json.dumps({"schema": SCHEMA, "ok": False,
                          "reason": "routing-config-unreadable"}))
        return 3
    out["ok"] = True
    print(json.dumps(out, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
