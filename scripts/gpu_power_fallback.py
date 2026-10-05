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
INCIDENT_FLAG_REL = ("var", "incident", "gpu.json")  # scripts/gpu_incident.py
INCIDENT_STATES = ("GPU3090_LOST", "GPU3090_DEGRADED")
# 사고 시 3090 레인 건너뛰기 대상의 보조 GPU 레인 (3060 = fast/embed, 11435)
AUX_3060_LANE_ID = "ollama:11435"

# Reason codes. Patterns also accept scripts/rtx3090_health_watch.ps1 anomaly ids.
REASON_PATTERNS = (
    # gpu_lost는 driver_reset의 gpu.*(lost|..)보다 먼저 — 소실 문구 전용
    ("gpu_lost", (
        r"gpu is lost", r"unable to determine the device handle",
        r"has fallen off", r"gpu3090_lost", r"\bgpu_lost\b",
        r"\bgpu\s+lost\b")),
    ("power_limit_suspect", (
        r"hw_power_brake", r"hw_slowdown", r"sw_power_cap", r"power[_ ]?limit",
        r"power[_ ]?brake", r"power[_ ]?peak", r"throttl", r"전력", r"위이잉",
        # 케이블/순시 전력 피크 watch 신호 (compound anchor — 일반 문구 오분류 방지)
        r"cable[_ ]?(?:power[_ ]?)?trip", r"power[_ ]?trip",
        r"transient[_ ]?(?:power[_ ]?)?(?:spike|excursion)", r"power[_ ]?excursion")),
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
NO_LOCAL_RETRY = frozenset({"power_limit_suspect", "driver_reset",
                            "oom_suspect", "gpu_lost"})
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


def read_incident_flag(root):
    """var/incident/gpu.json 읽기 (없으면 not_observed — 스냅샷만, 라우팅 영향 없음)."""
    try:
        data = json.loads(
            (Path(root).joinpath(*INCIDENT_FLAG_REL))
            .read_text(encoding="utf-8"))
    except Exception:
        return {"state": "not_observed", "since_kst": None, "active": False}
    state = data.get("state") or "unknown"
    return {"state": state, "since_kst": data.get("since_kst"),
            "active": state in INCIDENT_STATES}


def _oauth_candidate(config):
    """chatgpt_oauth 라우트가 SSOT에 있고 enabledProp 기본값이 true면 후보 반환."""
    for route in (config or {}).get("routes", {}).get("llm", []) or []:
        if route.get("id") != "chatgpt_oauth":
            continue
        prop = route.get("enabledProp")
        enabled = True
        if prop:
            val = os.environ.get(prop)
            # ${CHATGPT_OAUTH_ENABLED:true} 의미와 동일 — 미설정은 켜짐
            enabled = val is None or val.strip().lower() not in (
                "0", "false", "no", "off")
        if enabled:
            return {"id": "chatgpt_oauth", "tier": "subscription",
                    "env": [], "allowed": True,
                    "note": "subscription quota first; incident llm fallback #1"}
    return None


def _aux_3060_candidate():
    return {"id": AUX_3060_LANE_ID, "tier": "free_local_aux",
            "env": ["LLM_FAST_BASE_URL", "EMBED_BASE_URL", "OLLAMA_HOST"],
            "allowed": True, "lane": "rtx3060",
            "note": "same-PC second GPU lane switch (not a 3090 retry)"}


def decide(purpose, text, attempts=0, allow_paid=False, root=".",
           from_incident=False):
    """Full decision: reason + local-retry budget + fallback route + report line."""
    incident = read_incident_flag(root)
    reason = classify_local_inference_failure(text)
    if incident["active"] and (
            not (text or "").strip()
            or reason in TRANSIENT or reason == "unknown"):
        # 활성 사고 플래그가 서 있는데 텍스트가 없거나 순간신호뿐 → 소실로 대표.
        # 플래그는 공유 사고 SSOT라 --from-incident 없이도 자동 감지한다.
        reason = "gpu_lost"
    incident_active = (reason == "gpu_lost" or incident["active"])
    attempts = max(0, int(attempts))
    budget = LOCAL_RETRY_BUDGET if (reason in TRANSIENT and attempts == 0) else 0
    if reason in NO_LOCAL_RETRY:
        budget = 0
    config = load_routing(root)
    candidates = suggest_fallback_route(
        purpose, config, allow_paid=allow_paid,
        failed_route_ids=[r for r in _local_route_ids(config, purpose)])
    if incident_active:
        # 3090 레인 건너뛰기: llm은 구독 OAuth 1순위, embed는 3060 레인 선행
        if purpose == "llm":
            oauth = _oauth_candidate(config)
            if oauth:
                candidates = [oauth] + candidates
        elif purpose == "embed":
            candidates = [_aux_3060_candidate()] + candidates
    allowed = [c for c in candidates if c["allowed"]]
    if allowed:
        nxt = allowed[0]
        action = ("lane_switch_then_api" if nxt["id"] == AUX_3060_LANE_ID
                  else "fallback_to_api")
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
        "incident": {"state": incident["state"],
                     "since_kst": incident["since_kst"],
                     "active": incident_active},
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
    if action == "lane_switch_then_api":
        return (f"로컬 GPU 사고(reason={reason}) -> 3060 레인"
                f"({AUX_3060_LANE_ID}) 우선, 이후 API 순서")
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
    p_dec.add_argument("--text", default="",
                       help="error text or watch signal id (optional with "
                            "--from-incident)")
    p_dec.add_argument("--attempts", type=int, default=0,
                       help="local attempts already made this incident")
    p_dec.add_argument("--allow-paid", action="store_true")
    p_dec.add_argument("--from-incident", action="store_true",
                       help="활성 플래그는 자동 반영 — 명시 호환용 옵션")

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
                         allow_paid, root=args.root,
                         from_incident=args.from_incident)
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
