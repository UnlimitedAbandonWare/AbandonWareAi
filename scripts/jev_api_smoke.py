#!/usr/bin/env python3
# [AWX][jev] thin wrapper: delegates the single Jev smoke call to the canonical
# scripts/jev_gateway_smoke.mjs and persists one result JSON for handoff
# evidence. Contract DEMO1-DEVIN-SUB-RUNTIME-TRACE-R2-JEV-20260929 (dedupe of
# DEMO1-DEVIN-JEV-API-SMOKE-20260929): the classification vocabulary is the
# delegate's own (jevResult / reason / httpStatus / retryAfter) — no second
# classifier lives here.
# Credential: resolved per use_project_keys.ps1 non-Runtime semantics —
# .secrets/providers.json (user-selected snapshot) wins over an inherited
# process env on mismatch; a stale inherited key caused the 2026-09-29 401.
# Reports key len/sha8/src/mismatch only (never the value).
# AWX_JEV_CREDENTIAL_ENV may name a different source env var to resolve;
# AWX_JEV_KEY_SOURCE=env forces process-env only.
# zdr:"off" — the delegate has no zeroDataRetention path since A-2 removal;
# if an opt-in flag is ever reintroduced this field must follow it.
# Default is dry-run: prints the exact delegate command without calling;
# --live runs the delegate once (it self-bounds to a single POST).
# Result file: data/agent-handoff/jev-smoke/jev-smoke-<stamp>.json
#   (override dir: AWX_JEV_SMOKE_OUTDIR).
# Exit 0 = dry-run ok / delegate PASS; 2 = NOT_CONFIGURED (node missing);
# 3 = delegate FAIL or spawn/parse failure.

import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = Path(__file__).resolve().parent.parent
DELEGATE = ROOT / "scripts" / "jev_gateway_smoke.mjs"
DEFAULT_OUTDIR = ROOT / "data" / "agent-handoff" / "jev-smoke"
CREDENTIAL_ENV = os.environ.get("AWX_JEV_CREDENTIAL_ENV", "AI_GATEWAY_API_KEY")
KEY_SOURCE = os.environ.get("AWX_JEV_KEY_SOURCE", "auto")  # auto|env|store
DEFAULT_ENDPOINT = "https://ai-gateway.vercel.sh/v1/evaluate"
SPAWN_MARGIN_S = 20.0
TAIL_CHARS = 400
STORE_PATH = ROOT / ".secrets" / "providers.json"
GATEWAY_HOST = "ai-gateway.vercel.sh"

# P-1: 공용 spend 장부 — mock/loopback/dry-run은 기록하지 않고, 실제
# ai-gateway.vercel.sh 전송만 1줄씩 남긴다. 게이트가 없으면 라이브를 거부한다.
sys.path.insert(0, str(Path(__file__).resolve().parent / "apikit"))
try:
    import jev_ledger
except ImportError:
    jev_ledger = None


def _store_value(name):
    try:
        data = json.loads(STORE_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    values = data.get("values")
    entry = values.get(name) if isinstance(values, dict) else None
    if isinstance(entry, dict) and isinstance(entry.get("value"), str):
        return entry["value"]
    return entry if isinstance(entry, str) else None


def _resolve_credential(env):
    env_val = (env.get(CREDENTIAL_ENV) or "").strip() or None
    store_val = (_store_value(CREDENTIAL_ENV) or "").strip() or None
    if KEY_SOURCE == "env":
        chosen, src = env_val, "process-env" if env_val else None
    elif KEY_SOURCE == "store":
        chosen = store_val or env_val
        src = "secrets-store" if store_val else ("process-env" if env_val else None)
    else:  # auto: store wins when present
        chosen = store_val or env_val
        src = "secrets-store" if store_val else ("process-env" if env_val else None)
    meta = {
        "credentialSrc": src,
        "credentialLen": len(chosen) if chosen else 0,
        "credentialSha8": hashlib.sha256(chosen.encode()).hexdigest()[:8] if chosen else None,
        "credentialMismatch": bool(env_val and store_val and env_val != store_val),
    }
    return chosen, meta


def _env_for_child(meta=None):
    env = dict(os.environ)
    value, info = _resolve_credential(env)
    if meta is not None:
        meta.update(info)
    if value:
        env["AI_GATEWAY_API_KEY"] = value
    return env


def _endpoint_host():
    from urllib.parse import urlparse

    raw = os.environ.get("AWX_JEV_ENDPOINT", DEFAULT_ENDPOINT)
    try:
        return urlparse(raw).hostname or "unparseable"
    except Exception:
        return "unparseable"


def _credential_present(env):
    return bool(env.get("AI_GATEWAY_API_KEY", "").strip())


def _write_result(result, stamp):
    outdir = Path(os.environ.get("AWX_JEV_SMOKE_OUTDIR") or DEFAULT_OUTDIR)
    outdir.mkdir(parents=True, exist_ok=True)
    out = outdir / ("jev-smoke-%s.json" % stamp)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return str(out)


def _spend_line(stamp, result):
    host = result.get("endpointHost")
    is_vercel = host == "ai-gateway.vercel.sh"
    delegate = result.get("delegateResult") or {}
    spend = {
        "session": "jev-smoke-" + stamp,
        # 애덤덤(2026-09-30) 스키마: provider=jev + agent + purpose 필수.
        "agent": os.environ.get("AWX_AGENT_NAME", "devin"),
        "purpose": os.environ.get("AWX_JEV_SPEND_PURPOSE", "probe"),
        "provider": "jev",
        "gateway": "vercel-ai-gateway" if is_vercel else "jev-smoke-target",
        "model": os.environ.get("AWX_JEV_MODEL", "typesafe-ai/jev"),
        "tier": "paid_quality" if is_vercel else "local_or_mock",
        "why": "connectivity_min",
        "caller": os.environ.get(
            "AWX_JEV_CALLER", "jev_api_smoke.py->jev_gateway_smoke.mjs"),
        "httpStatus": delegate.get("httpStatus"),
        "estCostClass": "llm_paid" if is_vercel else "local",
    }
    usage = delegate.get("usage")
    if isinstance(usage, dict):
        # D-2: 키 원명을 그대로 복사하고 `is not None` 판정 — 명시적 0을 지우지 않는다.
        # 기존 promptTokens/completionTokens 필드는 유지하되 없으면 inputTokens/outputTokens로 채운다.
        def _first(*keys):
            for k in keys:
                v = usage.get(k)
                if v is not None:
                    return v
            return None
        in_tok = _first("promptTokens", "prompt_tokens", "inputTokens", "input_tokens")
        out_tok = _first("completionTokens", "completion_tokens", "outputTokens", "output_tokens")
        if in_tok is not None:
            spend["promptTokens"] = in_tok
        if out_tok is not None:
            spend["completionTokens"] = out_tok
        for k in ("inputTokens", "outputTokens"):
            if usage.get(k) is not None:
                spend[k] = usage[k]
    cost = delegate.get("cost")
    # gateway.cost는 문자열로 올 수 있다(2026-09-30 라이브 46/46 관측) — 수치 문자열은
    # float으로 환산해 costUsd에 싣고, 파싱 불가/없음은 키 자체를 생략한다(0 위조 금지).
    if isinstance(cost, str):
        try:
            cost = float(cost.strip()) if cost.strip() else None
        except ValueError:
            cost = None
    if isinstance(cost, (int, float)) and not isinstance(cost, bool):
        spend["costUsd"] = cost
    print("[AWX][api-spend] " + json.dumps(spend, ensure_ascii=False))


def _ledger_append(result, env):
    """P-1: 실제 전송 1회마다 장부 1줄. delegateResult.attempts>=1일 때만 —
    missing-env/body-rejected/직접실행 거부처럼 fetch까지 가지 않은 종료는
    전송이 없었으므로 기록하지 않는다."""
    if jev_ledger is None:
        return
    if (result.get("endpointHost") or "").lower() != GATEWAY_HOST:
        return
    delegate = result.get("delegateResult")
    if not isinstance(delegate, dict):
        return
    try:
        attempts = int(delegate.get("attempts") or 0)
    except (TypeError, ValueError):
        attempts = 0
    if attempts < 1:
        return
    usage = delegate.get("usage")
    jev_ledger.append(jev_ledger.new_entry(
        session=env.get("AWX_AGENT_SESSION"),
        purpose=os.environ.get("AWX_JEV_SPEND_PURPOSE") or "probe",
        caller=os.environ.get("AWX_JEV_CALLER")
        or "jev_api_smoke.py->jev_gateway_smoke.mjs",
        http_status=delegate.get("httpStatus"),
        reason=delegate.get("reason"),
        usage=usage, cost=delegate.get("cost")))


def _budget_gate(base):
    """라이브 전송 직전 게이트. 거부 사유 문자열 또는 None."""
    host = (base.get("endpointHost") or "").lower()
    if jev_ledger is None:
        return "ledger-unavailable" if host == GATEWAY_HOST else None
    return jev_ledger.gate(host)


def _mtime_utc(path):
    try:
        return datetime.datetime.fromtimestamp(
            Path(path).stat().st_mtime, datetime.timezone.utc)
    except OSError:
        return datetime.datetime.now(datetime.timezone.utc)


def _line_ts(line):
    """로그 줄 선두의 ISO 시각. tz가 없는 'YYYY-MM-DD HH:MM:SS'는 KST로 본다."""
    import re
    m = re.match(
        r"(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?)(Z|[+-]\d{2}:?\d{2})?",
        line.strip())
    if not m:
        return None
    body, tzs = m.group(1).replace(" ", "T"), m.group(2)
    try:
        if not tzs:
            return datetime.datetime.fromisoformat(body).replace(
                tzinfo=jev_ledger.KST)
        tzs = tzs.replace("Z", "+00:00")
        if len(tzs) == 5 and ":" not in tzs:  # +0900 -> +09:00
            tzs = tzs[:3] + ":" + tzs[3:]
        return datetime.datetime.fromisoformat(body + tzs)
    except ValueError:
        return None


def _shadow_entries(log_path):
    """앱 로그의 [AWX][jev] 줄 중 httpStatus>0만 장부 행으로 — P-4."""
    import re
    p = Path(log_path)
    try:
        lines = p.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as exc:
        return None, "unreadable:%s" % exc
    out = []
    for i, line in enumerate(lines, 1):
        if "[AWX][jev]" not in line:
            continue
        kv = dict(re.findall(r"(\w+)=([^\s]+)",
                             line.split("[AWX][jev]", 1)[1]))
        try:
            status = int(kv.get("httpStatus") or 0)
        except ValueError:
            status = 0
        if status <= 0:
            continue  # httpStatus 0/없음 = 전송이 안 나간 줄 — 기록 안 함
        out.append(jev_ledger.new_entry(
            purpose="display-shadow", caller="app-shadow",
            http_status=status,
            reason=kv.get("reasonCode"), usage=None, cost=None,
            ts=_line_ts(line) or _mtime_utc(p),
            backfilled=True, src_ref="%s:L%d" % (p.name, i)))
    return out, None


def _result_file_ts(path, data):
    try:
        ts = datetime.datetime.fromisoformat(
            str(data.get("at", "")).replace("Z", "+00:00"))
        if ts.tzinfo is None:
            ts = ts.replace(tzinfo=datetime.timezone.utc)
        return ts
    except ValueError:
        return _mtime_utc(path)


def _entries_from_result_file(path):
    """jev-smoke-*.json 결과 -> 장부 행 목록 (live+실제 전송분만).
    두 스키마를 지원한다: delegateResult(v2, 전송 1회)와
    calls[](v1 `awx.jev-api-smoke.v1`, 전송 N회)."""
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    if data.get("mode") != "live":
        return []  # dry-run은 전송이 아니다
    host = (data.get("endpointHost") or "").lower()
    if not host:
        ep = str(data.get("endpoint") or "")
        m = re.match(r"https?://([^/:]+)", ep)
        host = (m.group(1) if m else "").lower()
    if host != GATEWAY_HOST:
        return []
    try:
        rel = str(Path(path).resolve().relative_to(ROOT)).replace("\\", "/")
    except ValueError:
        rel = Path(path).name
    ts = _result_file_ts(path, data)
    session = (data.get("session")
               or "jev-smoke-" + Path(path).stem.split("-", 2)[-1])
    delegate = data.get("delegateResult") or {}
    if isinstance(delegate, dict) and (delegate.get("attempts") or 0):
        usage = (delegate.get("usage")
                 if isinstance(delegate.get("usage"), dict) else None)
        return [jev_ledger.new_entry(
            agent="devin", session=session,
            purpose="probe", caller="jev_api_smoke.py->jev_gateway_smoke.mjs",
            http_status=delegate.get("httpStatus"),
            reason=delegate.get("reason"),
            usage=usage, cost=delegate.get("cost"), ts=ts,
            backfilled=True, src_ref=rel)]
    calls = data.get("calls")
    if isinstance(calls, list):  # v1 다중 호출 스키마
        return [jev_ledger.new_entry(
            agent="devin", session=session,
            purpose="probe", caller="jev_api_smoke.py(legacy-multi)",
            http_status=c.get("httpStatus"),
            reason=c.get("classification"),
            usage=None, cost=None, ts=ts,
            backfilled=True,
            src_ref="%s#%s" % (rel, c.get("name") or i))
            for i, c in enumerate(calls)
            if isinstance(c, dict) and c.get("httpStatus")]
    return []


def _entry_from_batch_record(rec, batch_dir):
    """devin-jev-live-*/batch-summary.json의 records[] 한 건 -> 장부 행."""
    tag = rec.get("tag") or "?"
    name = Path(rec.get("resultFile") or "").name
    m = re.match(r"jev-smoke-(\d{8})-(\d{6})", name)
    if m:  # 파일명 스탬프는 UTC
        ts = datetime.datetime.strptime(
            "%s %s" % (m.group(1), m.group(2)),
            "%Y%m%d %H%M%S").replace(tzinfo=datetime.timezone.utc)
    else:
        ts = None
    spend = rec.get("spend") if isinstance(rec.get("spend"), dict) else {}
    return jev_ledger.new_entry(
        agent=rec.get("agent") or spend.get("agent") or "devin",
        # 실제 전송마다 기록된 세션을 보존한다 (배치 id는 srcRef에 이미 있다)
        session=spend.get("session") or batch_dir,
        purpose=rec.get("purpose") or "probe",
        caller="jev_api_smoke.py->jev_gateway_smoke.mjs",
        http_status=rec.get("httpStatus"), reason=rec.get("reason"),
        usage=rec.get("usage"), cost=rec.get("cost"), ts=ts,
        backfilled=True, src_ref="%s#%s" % (batch_dir, tag))


def _backfill(path):
    if jev_ledger is None:
        print("jev_ledger module unavailable", file=sys.stderr)
        return 2
    p = Path(path)
    entries = []
    if p.is_dir():
        for f in sorted(p.glob("jev-smoke-*.json")):
            entries.extend(_entries_from_result_file(f))
    elif p.name == "batch-summary.json":
        try:
            summary = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            print("backfill: unreadable %s" % exc, file=sys.stderr)
            return 2
        batch_dir = p.parent.name
        for rec in summary.get("records") or []:
            if isinstance(rec, dict):
                entries.append(_entry_from_batch_record(rec, batch_dir))
    else:
        entries.extend(_entries_from_result_file(p))
    added = sum(1 for e in entries if e is not None
                and jev_ledger.append_once(e) is not None)
    print(json.dumps({"backfill": str(p), "candidates": len(entries),
                      "added": added, "skipped": len(entries) - added,
                      "ledger": str(jev_ledger.ledger_path())},
                     ensure_ascii=False))
    return 0


def _ledger_summary():
    if jev_ledger is None:
        print("jev_ledger module unavailable", file=sys.stderr)
        return 2
    s = jev_ledger.summarize(
        session=os.environ.get("AWX_AGENT_SESSION") or None)
    print("JEV_SPEND agent=%s calls=%d/%d day=%d/%d total=$%.5f/$%.2f fails=%s"
          % (os.environ.get("AWX_AGENT_NAME") or "unknown",
             s["sessionCount"], jev_ledger.SESSION_CAP,
             s["dayCount"], jev_ledger.DAY_CAP,
             s["totalUsd"], jev_ledger.PERIOD_CAP_USD,
             json.dumps(s["fails"], sort_keys=True, separators=(",", ":"))))
    print(json.dumps(s, ensure_ascii=False))
    return 0


def main():
    ap = argparse.ArgumentParser(
        description="Jev smoke thin wrapper -> jev_gateway_smoke.mjs (dry-run default)")
    ap.add_argument("--live", action="store_true",
                    help="run the delegate once (single POST)")
    ap.add_argument("--ledger-append-shadow", metavar="LOG",
                    help="append app-shadow [AWX][jev] httpStatus>0 lines from LOG")
    ap.add_argument("--ledger-backfill", metavar="PATH",
                    help="backfill ledger from batch-summary.json or a jev-smoke dir/file")
    ap.add_argument("--ledger-summary", action="store_true",
                    help="print the JEV_SPEND ledger summary line")
    args = ap.parse_args()

    if args.ledger_summary:
        return _ledger_summary()
    if args.ledger_backfill:
        return _backfill(args.ledger_backfill)
    if args.ledger_append_shadow:
        if jev_ledger is None:
            print("jev_ledger module unavailable", file=sys.stderr)
            return 2
        entries, err = _shadow_entries(args.ledger_append_shadow)
        if err:
            print("append-shadow: %s" % err, file=sys.stderr)
            return 2
        added = sum(1 for e in entries
                    if jev_ledger.append_once(e) is not None)
        print(json.dumps({"log": args.ledger_append_shadow,
                          "shadowSends": len(entries), "added": added,
                          "skipped": len(entries) - added,
                          "ledger": str(jev_ledger.ledger_path())},
                         ensure_ascii=False))
        return 0

    started = datetime.datetime.now(datetime.timezone.utc)
    stamp = started.strftime("%Y%m%d-%H%M%S")
    cred_meta = {}
    env = _env_for_child(cred_meta)
    cmd = ["node", str(DELEGATE)]

    base = {
        "schemaVersion": "awx.jev-api-smoke.v2",
        "contract": "DEMO1-DEVIN-SUB-RUNTIME-TRACE-R2-JEV-20260929",
        "at": started.isoformat(),
        "delegate": "scripts/jev_gateway_smoke.mjs",
        "endpointHost": _endpoint_host(),
        "endpointEnvSet": bool(os.environ.get("AWX_JEV_ENDPOINT")),
        "allowHostEnvSet": bool(os.environ.get("AWX_JEV_ALLOW_HOST")),
        "credentialEnv": CREDENTIAL_ENV,
        "credentialPresent": _credential_present(env),
        "command": "node scripts/jev_gateway_smoke.mjs",
        "zdr": "off",
        **cred_meta,
    }

    if not args.live:
        result = dict(base, mode="dry-run",
                      note="no call made; re-run with --live to delegate once")
        print(json.dumps(result, ensure_ascii=False, indent=2))
        print("[jev-smoke] dry-run only; delegate command: node scripts/jev_gateway_smoke.mjs")
        return 0

    node = shutil.which("node")
    if not node:
        result = dict(base, mode="live", verdict="NOT_CONFIGURED",
                      blockReason="node-not-on-PATH")
        out = _write_result(result, stamp)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        print("result=%s" % out)
        return 2
    cmd[0] = node

    # P-1 사전 차단: 실제 게이트웨이 라이브 전송만 게이트한다 (loopback/mock 제외).
    refuse = _budget_gate(base)
    if refuse:
        result = dict(base, mode="live", verdict="BLOCKED",
                      reason="budget_refused:" + refuse,
                      note="pre-send budget gate; no HTTP send, no ledger line")
        out = _write_result(result, stamp)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        print("budget_refused:%s" % refuse, file=sys.stderr)
        print("result=%s" % out)
        return jev_ledger.GATE_EXIT if jev_ledger else 5
    env["AWX_JEV_VIA_WRAPPER"] = "1"  # 직접 실행 차단 해제 신호 (P-1)

    timeout_s = max(5.0, float(os.environ.get("AWX_JEV_TIMEOUT_MS", "15000")) / 1000.0) + SPAWN_MARGIN_S
    try:
        proc = subprocess.run(cmd, env=env, capture_output=True, text=True,
                              timeout=timeout_s, cwd=str(ROOT))
        delegate_exit = proc.returncode
        stdout, stderr = proc.stdout or "", proc.stderr or ""
        spawn_error = None
    except (OSError, subprocess.TimeoutExpired) as exc:
        delegate_exit = 3
        stdout, stderr = "", ""
        spawn_error = "%s: %s" % (type(exc).__name__, str(exc)[:TAIL_CHARS])

    delegate_result = None
    if stdout.strip():
        try:
            delegate_result = json.loads(stdout.strip())
        except ValueError:
            delegate_result = None

    result = dict(base, mode="live", delegateExit=delegate_exit)
    if delegate_result is not None:
        result["delegateResult"] = delegate_result
        result["jevResult"] = delegate_result.get("jevResult")
        result["reason"] = delegate_result.get("reason")
        result["verdict"] = "PASS" if delegate_exit == 0 else "FAIL"
    else:
        result["delegateResult"] = None
        result["jevResult"] = "FAIL"
        result["reason"] = "spawn-failed" if spawn_error else "unparseable-delegate-output"
        result["verdict"] = "FAIL"
        if spawn_error:
            result["spawnError"] = spawn_error
        if stdout:
            result["stdoutTail"] = stdout[-TAIL_CHARS:]
        if stderr:
            result["stderrTail"] = stderr[-TAIL_CHARS:]

    _ledger_append(result, env)
    _spend_line(stamp, result)
    out = _write_result(result, stamp)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print("result=%s" % out)
    return delegate_exit if delegate_exit in (0, 3) else 3


if __name__ == "__main__":
    sys.exit(main())
