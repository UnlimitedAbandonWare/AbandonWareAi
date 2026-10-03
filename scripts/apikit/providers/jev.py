"""Jev — Vercel AI Gateway. Free checks: /v1/credits + /v1/models.
Paid check delegates to the canonical scripts/jev_gateway_smoke.mjs through
scripts/jev_api_smoke.py --live (single bounded POST /v1/evaluate).
P-1: every real send to ai-gateway.vercel.sh is ledgered and passes the
pre-send budget gate; a refused send returns a budget_refused row instead."""
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

try:
    from .. import common
except ImportError:  # dir-run context
    import common

try:
    from .. import jev_ledger
except ImportError:  # dir-run context
    try:
        import jev_ledger
    except ImportError:
        jev_ledger = None

# https://vercel.com/docs/ai-gateway/sdks-and-apis/rest-api — verified 2026-09-29
SPEC = {
    "name": "jev",
    "key_envs": ["AI_GATEWAY_API_KEY"],
    "hosts": ["ai-gateway.vercel.sh"],
    "fix_url": "https://vercel.com/d?to=%2F%5Bteam%5D%2F%7E%2Fai-gateway%2Fapi-keys",
    "doc_url": "https://vercel.com/docs/ai-gateway/sdks-and-apis/rest-api",
    "doc_checked": "2026-09-29",
    "code_paths": [("error", "type"), ("error", "code")],
    "code_map": {
        "authentication_error": common.KEY_INVALID,
        "invalid_api_key": common.KEY_INVALID,
        "billing_error": common.QUOTA,
        "insufficient_quota": common.QUOTA,
        "rate_limit": common.RATE_LIMIT,
        "rate_limit_exceeded": common.RATE_LIMIT,
        "invalid_request_error": common.BAD_SHAPE,
        "model_not_found": common.MODEL_NOT_FOUND,
    },
}

_DELEGATE_REASON_CLASS = {
    "auth_invalid": common.KEY_INVALID,
    "plan_gate": common.PLAN_GATE,
    "permission_denied": common.FORBIDDEN,
    "billing-blocked": common.QUOTA,
    "rate_limited": common.RATE_LIMIT,
    "upstream_error": common.SERVER_5XX,
    "timeout": common.NETWORK,
    "network": common.NETWORK,
    "missing-env": common.KEY_MISSING,
    "redirect": common.UNKNOWN,
    "auth-blocked": common.KEY_INVALID,  # jev-vocab: legacy-alias
    "key_invalid_or_expired": common.KEY_INVALID,  # jev-vocab: legacy-alias
    "forbidden": common.FORBIDDEN,  # jev-vocab: legacy-alias
}


def _gateway_gate(ki):
    """실제 전송 가능한 경우에만 사전 게이트. 키 부재는 전송 자체가 없다."""
    if not ki.get("value"):
        return None
    if jev_ledger is None:
        return "ledger-unavailable"
    return jev_ledger.gate("ai-gateway.vercel.sh")


def _ledger_record(step, row, sent):
    """전송이 실제 나간 스텝만 1줄 (키 없음/게이트 거부/미전송은 제외).
    timeout·network 오류는 httpStatus=null로 남긴다."""
    if jev_ledger is None or not sent:
        return
    jev_ledger.append(jev_ledger.new_entry(
        purpose=step, caller="apikit:jev.check",
        http_status=row.get("http"),
        reason=None if row.get("cls") == common.OK else row.get("cls"),
        usage=None, cost=None))


def check(ctx):
    rows = []
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    if ki["mismatch"]:
        rows.append(common.key_row(SPEC, ki, cls=common.KEY_MISMATCH,
                                   detail="sources disagree: %s" % ",".join(ki["srcs"])))
    refused = _gateway_gate(ki)
    if refused:
        rows.append(common.key_row(SPEC, ki, step="credits", cls=common.UNKNOWN,
                                   detail="budget_refused:%s" % refused))
        return rows
    rows.append(common.run_step(
        SPEC, ctx, ki=ki, step="credits", auth="bearer",
        url="https://ai-gateway.vercel.sh/v1/credits",
        ok=_credits_ok))
    _ledger_record("credits", rows[-1], sent=bool(ki["value"]))
    if rows[-1]["cls"] in (common.KEY_INVALID, common.FORBIDDEN):
        rows.append(common.key_row(SPEC, ki, step="models", cls=common.UNKNOWN,
                                   detail="skipped after auth failure"))
        return rows

    refused = _gateway_gate(ki)
    if refused:
        rows.append(common.key_row(SPEC, ki, step="models", cls=common.UNKNOWN,
                                   detail="budget_refused:%s" % refused))
        return rows

    def _models_ok(parsed, _r):
        items = parsed if isinstance(parsed, list) else \
            (parsed or {}).get("data", (parsed or {}).get("models", []))
        ids = [m.get("id") for m in items or [] if isinstance(m, dict)]
        if "typesafe-ai/jev" in ids:
            return common.OK, "typesafe-ai/jev present (models=%d)" % len(ids)
        if ids:
            return common.MODEL_NOT_FOUND, "typesafe-ai/jev absent (models=%d)" % len(ids)
        if parsed is None:
            return common.UNKNOWN, "models: non-json body"
        return common.UNKNOWN, "models: unexpected shape"

    rows.append(common.run_step(
        SPEC, ctx, ki=ki, step="models", auth="bearer",
        url="https://ai-gateway.vercel.sh/v1/models",
        ok=_models_ok))
    _ledger_record("models", rows[-1], sent=bool(ki["value"]))
    return rows


def _credits_ok(parsed, _r):
    if not isinstance(parsed, dict):
        return common.UNKNOWN, "credits: non-json body"
    bal = parsed.get("balance")
    used = parsed.get("total_used")
    try:
        if bal is not None and float(bal) <= 0.0:
            return common.QUOTA, "balance=0 total_used=%s" % used
    except (TypeError, ValueError):
        pass
    return common.OK, "balance=%s total_used=%s" % (bal, used)


def call(ctx):
    """Paid path: reuse the existing wrapper (node cross-check included)."""
    ki = common.resolve_key(SPEC["key_envs"], ctx["secrets"], ctx["scopes"])
    if not ki["value"]:
        return [common.key_row(SPEC, ki, step="evaluate", cls=common.KEY_MISSING,
                               detail="AI_GATEWAY_API_KEY absent")]
    script = common.ROOT / "scripts" / "jev_api_smoke.py"
    if not shutil.which("node"):
        return [common.key_row(SPEC, ki, step="evaluate", cls=common.UNKNOWN,
                               detail="node not on PATH (delegate unavailable)")]
    env = dict(os.environ)
    env.setdefault("AWX_JEV_CALLER",
                   "apikit->jev_api_smoke.py->jev_gateway_smoke.mjs")
    try:
        proc = subprocess.run(
            [sys.executable, "-B", str(script), "--live"],
            capture_output=True, text=True, timeout=ctx["timeout"] + 40,
            cwd=str(common.ROOT), env=env)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return [common.key_row(SPEC, ki, step="evaluate", cls=common.NETWORK,
                               detail="delegate spawn: %s" % type(exc).__name__)]
    # 래퍼 stdout에는 spend 줄 + 결과 JSON + result=<path> 줄이 함께 나온다 —
    # 전체 stdout을 json.loads하면 실패하므로 result= 파일을 우선 읽는다.
    data = None
    out_path = None
    for line in (proc.stdout or "").splitlines():
        if line.startswith("result="):
            out_path = line[len("result="):].strip()
    if out_path:
        try:
            data = json.loads(Path(out_path).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = None
    if data is None:
        try:
            data = json.loads((proc.stdout or "").strip())
        except ValueError:
            data = None
    delegate = (data or {}).get("delegateResult") or {}
    reason = (data or {}).get("reason") or "unparseable-delegate"
    cls = common.OK if (data or {}).get("jevResult") == "PASS" else \
        _DELEGATE_REASON_CLASS.get(reason, common.UNKNOWN)
    row = common.key_row(SPEC, ki, step="evaluate", cls=cls,
                         detail="delegate reason=%s http=%s" % (
                             reason, delegate.get("httpStatus")),
                         ms=delegate.get("latencyMs"))
    row["http"] = delegate.get("httpStatus")
    row["code"] = reason
    cost = delegate.get("cost")
    # gateway.cost는 문자열로 온다(라이브 관측 "0.0000197") — 수치 문자열을
    # float으로 환산하고, 실패 시 costUsd 자체를 싣지 않는다(0 위조 금지).
    if isinstance(cost, str):
        try:
            cost = float(cost.strip()) if cost.strip() else None
        except ValueError:
            cost = None
    if isinstance(cost, (int, float)) and not isinstance(cost, bool):
        row["cost"] = cost
    common.spend_line(ctx, "jev", "typesafe-ai/jev", "evaluate",
                      delegate.get("httpStatus"), delegate.get("usage"), cost)
    return [row]
