#!/usr/bin/env python3
"""job_receipt_inspect.py — awx_jobs / awx_job_results / awx_understanding_receipts 읽기 전용 점검.

Contract DEMO1-DEVIN-F01B-POST-TOOLS-20260929 항목 5. TRACE-DOCK 이 소비할 JSON.

- live 조회는 scripts/db_agent.py query --via auto 위임 (직접 JDBC/연결 문자열
  파싱 없음, SELECT only). file H2 잠김(exit 3)은 live-http 폴백으로 처리된다.
- 상태별 카운트 / stuck 후보 / 실패 / TTL 후보 / receipt 요약을 한 번에 낸다.
- 마스킹: payload/body/owner_hash/worker_token/admission_key/request_fingerprint 는
  SELECT 절에 올리지 않는다 — 값을 가져온 뒤 지우는 방식이 아니라 애초에 읽지 않는다.
- stuck 판정 상수는 JdbcJobService.java 소스 추적값:
  phase COMPUTE 재시도 상한 2, COMMIT 상한 3, terminal expires_at = +24h.

exit 0 executed / 1 usage·IO / 3 unreachable·schema-absent (evidence_needed) / 4 safety abort.
--fixture <json> 으로 db_agent 응답을 대체해 완전 오프라인 실행 가능.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import sys
import time

CONTRACT_ID = "DEMO1-DEVIN-F01B-POST-TOOLS-20260929"
SCHEMA = "awx.f01b-job-receipt-inspect.v1"
DEFAULT_OUT = "data/diagnostics/f01b-post-tools-0929/job_receipt_inspect.json"

EXIT_OK = 0
EXIT_USAGE = 1
EXIT_UNREACHABLE = 3
EXIT_SAFETY = 4

# JdbcJobService 소스 추적 상수 (main/java/.../jobs/JdbcJobService.java)
COMPUTE_CAP = 2   # phase='COMPUTE' 재시도 상한
COMMIT_CAP = 3    # phase='COMMIT' 재시도 상한
RUNNING_STATES = ("RUNNING", "CANCEL_REQUESTED")

# 마스킹 정책: 아래 컬럼은 어떤 SELECT 에도 올리지 않는다.
NEVER_SELECTED = ("payload", "body", "owner_hash", "worker_token",
                  "admission_key", "request_fingerprint", "result_sha256",
                  "callback_token")


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def build_queries(now_ms: int, limit: int) -> dict:
    """읽기 전용 SELECT 모음. now/limit 은 정수만 삽입 (사용자 입력 문자열 없음)."""
    running = ",".join(f"'{s}'" for s in RUNNING_STATES)
    return {
        "stateCounts":
            "SELECT state, COUNT(*) AS CNT FROM awx_jobs GROUP BY state ORDER BY state",
        "typeCounts":
            "SELECT job_type, state, COUNT(*) AS CNT FROM awx_jobs "
            "GROUP BY job_type, state ORDER BY job_type, state",
        "stuckRunning":
            "SELECT task_id, job_type, state, phase, error_code, created_at, "
            f"updated_at, lease_until FROM awx_jobs WHERE state IN ({running}) "
            f"AND (lease_until IS NULL OR lease_until < {now_ms}) "
            f"ORDER BY updated_at LIMIT {limit}",
        "outcomeUnknown":
            "SELECT task_id, job_type, phase, error_code, compute_attempts, "
            "commit_attempts, next_attempt_at, updated_at FROM awx_jobs "
            "WHERE state='OUTCOME_UNKNOWN' ORDER BY next_attempt_at "
            f"LIMIT {limit}",
        "failed":
            "SELECT task_id, job_type, error_code, updated_at, completed_at "
            f"FROM awx_jobs WHERE state='FAILED' ORDER BY updated_at DESC LIMIT {limit}",
        "ttlCandidates":
            "SELECT state, COUNT(*) AS CNT FROM awx_jobs WHERE expires_at IS NOT NULL "
            f"AND expires_at <= {now_ms} GROUP BY state",
        "callbackCounts":
            "SELECT callback_state, COUNT(*) AS CNT FROM awx_jobs "
            "GROUP BY callback_state",
        "receiptCounts":
            "SELECT receipt_state, COUNT(*) AS CNT FROM awx_understanding_receipts "
            "GROUP BY receipt_state",
        "receiptOrphans":
            "SELECT COUNT(*) AS CNT FROM awx_understanding_receipts r "
            "LEFT JOIN awx_jobs j ON r.job_task_id = j.task_id "
            "WHERE j.task_id IS NULL",
        "resultOrphans":
            "SELECT COUNT(*) AS CNT FROM awx_job_results r "
            "LEFT JOIN awx_jobs j ON r.task_id = j.task_id WHERE j.task_id IS NULL",
    }


def run_db_agent_query(root: Path, db_agent: Path, sql: str, env: dict,
                       h2_file: str | None, timeout: int) -> dict:
    """db_agent.py query 위임. transport ok + rows 만 반환."""
    cmd = [sys.executable, "-B", str(db_agent), "query", "--via", "auto",
           "--sql", sql, "--timeout", str(timeout)]
    if h2_file:
        cmd += ["--db-path", h2_file]
    try:
        proc = subprocess.run(cmd, cwd=str(root), env=env,
                              capture_output=True, text=True,
                              timeout=timeout + 15)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"ok": False, "error": type(exc).__name__, "rows": None}
    out = (proc.stdout or "").strip()
    try:
        payload = json.loads(out.splitlines()[-1]) if out else {}
    except ValueError:
        payload = {}
    if proc.returncode != 0 or not payload.get("ok"):
        return {"ok": False,
                "error": payload.get("reason") or payload.get("error")
                    or f"exit_{proc.returncode}",
                "rows": None}
    # db_agent 출력은 {"columns": [...], "rows": [[...]]} — 컬럼명 dict 로 정규화.
    cols = [str(c).upper() for c in (payload.get("columns") or [])]
    rows = [dict(zip(cols, r)) if isinstance(r, (list, tuple)) else r
            for r in (payload.get("rows") or [])]
    return {"ok": True, "via": payload.get("via"), "rows": rows}


def classify_outcome_unknown(row: dict) -> str:
    """OUTCOME_UNKNOWN 세부 분류. 시도 상한 초과 = 재시도 소진(stuck)."""
    err = str(row.get("ERROR_CODE") or row.get("error_code") or "")
    if err == "cancelled_worker_lost":
        return "cancel_finalize_pending"
    phase = str(row.get("PHASE") or row.get("phase") or "").upper()
    try:
        comp = int(row.get("COMPUTE_ATTEMPTS") or row.get("compute_attempts") or 0)
        comm = int(row.get("COMMIT_ATTEMPTS") or row.get("commit_attempts") or 0)
    except (TypeError, ValueError):
        comp = comm = 0
    if (phase == "COMPUTE" and comp >= COMPUTE_CAP) or \
            (phase == "COMMIT" and comm >= COMMIT_CAP):
        return "exhausted"
    return "retryable"


def summarize(results: dict, limit: int) -> dict:
    """쿼리 결과 dict → 판정 요약. 순수 함수 — fixture 테스트는 여기만 쓴다."""
    counts = {str(r.get("STATE") or r.get("state") or "?"):
              int(r.get("CNT") or r.get("cnt") or 0)
              for r in (results.get("stateCounts", {}).get("rows") or [])}
    by_type = {}
    for r in (results.get("typeCounts", {}).get("rows") or []):
        jt = str(r.get("JOB_TYPE") or r.get("job_type") or "?")
        st = str(r.get("STATE") or r.get("state") or "?")
        by_type.setdefault(jt, {})[st] = int(r.get("CNT") or r.get("cnt") or 0)

    stuck_rows = results.get("stuckRunning", {}).get("rows") or []
    stuck = [{"taskId": r.get("TASK_ID") or r.get("task_id"),
              "jobType": r.get("JOB_TYPE") or r.get("job_type"),
              "state": r.get("STATE") or r.get("state"),
              "phase": r.get("PHASE") or r.get("phase"),
              "errorCode": r.get("ERROR_CODE") or r.get("error_code"),
              "updatedAt": r.get("UPDATED_AT") or r.get("updated_at"),
              "leaseUntil": r.get("LEASE_UNTIL") or r.get("lease_until"),
              "kind": "lease_expired"} for r in stuck_rows]

    outcome_rows = results.get("outcomeUnknown", {}).get("rows") or []
    outcome = {"retryable": 0, "exhausted": 0, "cancel_finalize_pending": 0,
               "rows": []}
    for r in outcome_rows:
        kind = classify_outcome_unknown(r)
        outcome[kind] += 1
        outcome["rows"].append({
            "taskId": r.get("TASK_ID") or r.get("task_id"),
            "jobType": r.get("JOB_TYPE") or r.get("job_type"),
            "phase": r.get("PHASE") or r.get("phase"),
            "errorCode": r.get("ERROR_CODE") or r.get("error_code"),
            "nextAttemptAt": r.get("NEXT_ATTEMPT_AT") or r.get("next_attempt_at"),
            "kind": kind})

    failed = [{"taskId": r.get("TASK_ID") or r.get("task_id"),
               "jobType": r.get("JOB_TYPE") or r.get("job_type"),
               "errorCode": r.get("ERROR_CODE") or r.get("error_code"),
               "completedAt": r.get("COMPLETED_AT") or r.get("completed_at")}
              for r in (results.get("failed", {}).get("rows") or [])]

    ttl = {str(r.get("STATE") or r.get("state") or "?"):
           int(r.get("CNT") or r.get("cnt") or 0)
           for r in (results.get("ttlCandidates", {}).get("rows") or [])}

    callbacks = {str(r.get("CALLBACK_STATE") or r.get("callback_state") or "?"):
                 int(r.get("CNT") or r.get("cnt") or 0)
                 for r in (results.get("callbackCounts", {}).get("rows") or [])}

    receipt_q = results.get("receiptCounts", {})
    receipts = {"available": bool(receipt_q.get("ok")),
                "error": receipt_q.get("error"),
                "byState": {str(r.get("RECEIPT_STATE") or r.get("receipt_state") or "?"):
                            int(r.get("CNT") or r.get("cnt") or 0)
                            for r in (receipt_q.get("rows") or [])}}
    for key, name in (("receiptOrphans", "orphanReceipts"),
                      ("resultOrphans", "orphanResults")):
        q = results.get(key, {})
        cnt = 0
        if q.get("ok") and q.get("rows"):
            cnt = int(q["rows"][0].get("CNT") or q["rows"][0].get("cnt") or 0)
        receipts[name] = {"count": cnt, "observed": bool(q.get("ok")),
                          "error": q.get("error")}

    total = sum(counts.values())
    attention = (len(stuck) > 0 or outcome["exhausted"] > 0 or
                 outcome["cancel_finalize_pending"] > 0 or len(failed) > 0 or
                 receipts.get("orphanReceipts", {}).get("count", 0) > 0)
    verdict = "attention" if attention else "healthy"
    return {
        "totalJobs": total,
        "stateCounts": counts,
        "jobTypeCounts": by_type,
        "stuck": {"count": len(stuck), "truncated": len(stuck) >= limit,
                  "candidates": stuck},
        "outcomeUnknown": {k: v for k, v in outcome.items() if k != "rows"} |
                          {"candidates": outcome["rows"][:limit]},
        "failed": {"count": len(failed), "candidates": failed[:limit]},
        "ttlCandidates": {"total": sum(ttl.values()), "byState": ttl},
        "callbackCounts": callbacks,
        "receipts": receipts,
        "verdict": verdict,
        "retryCaps": {"compute": COMPUTE_CAP, "commit": COMMIT_CAP,
                      "source": "JdbcJobService.java next-attempt caps"},
    }


def load_fixture(path: Path) -> dict:
    """fixture JSON: {"queries": {"<key>": {"ok": true, "rows": [...]}}} 형식."""
    data = json.loads(path.read_text(encoding="utf-8"))
    queries = data.get("queries") or {}
    return {k: {"ok": bool(v.get("ok", True)), "via": "fixture",
                "rows": v.get("rows"), "error": v.get("error")}
            for k, v in queries.items()}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="awx_jobs/awx_understanding_receipts read-only inspect "
                    "(state counts, stuck, failed, TTL, receipts). TRACE-DOCK JSON.")
    ap.add_argument("--root", default=".")
    ap.add_argument("--db-agent", default="scripts/db_agent.py")
    ap.add_argument("--h2-file", default=None)
    ap.add_argument("--jdbc-url-env", default="AWX_JDBC_URL",
                    help="env 이름만 기록; 값은 출력/저장 금지")
    ap.add_argument("--timeout", type=int, default=20)
    ap.add_argument("--limit", type=int, default=50,
                    help="후보 행 최대 개수 (기본 50)")
    ap.add_argument("--now-ms", type=int, default=None,
                    help="기준 시각 epoch ms (기본: 현재; fixture 테스트용)")
    ap.add_argument("--fixture", default=None,
                    help="db_agent 응답 fixture JSON - 완전 오프라인 실행")
    ap.add_argument("--json-out", default=DEFAULT_OUT)
    ap.add_argument("--json", action="store_true", help="stdout JSON 출력")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    now_ms = args.now_ms if args.now_ms is not None else int(time.time() * 1000)
    limit = max(1, int(args.limit))
    queries = build_queries(now_ms, limit)

    env_name = str(args.jdbc_url_env).strip()
    env_value = os.environ.get(env_name)
    child_env = dict(os.environ)
    if env_value:
        child_env.setdefault("LMS_DB_URL", env_value)

    results = {}
    reachable = True
    first_error = None
    if args.fixture:
        fpath = Path(args.fixture)
        if not fpath.is_absolute():
            fpath = root / fpath
        if not fpath.is_file():
            print(f"fixture 없음: {fpath}", file=sys.stderr)
            return EXIT_USAGE
        fixture = load_fixture(fpath)
        for key in queries:
            results[key] = fixture.get(key, {"ok": False,
                                             "error": "fixture-missing",
                                             "rows": None})
        via = "fixture"
    else:
        db_agent = root / args.db_agent
        if not db_agent.is_file():
            print(f"db_agent 없음: {db_agent}", file=sys.stderr)
            return EXIT_USAGE
        via = None
        for key, sql in queries.items():
            res = run_db_agent_query(root, db_agent, sql, child_env,
                                     args.h2_file, args.timeout)
            results[key] = res
            if res.get("via"):
                via = res["via"]
            if key == "stateCounts" and not res.get("ok"):
                reachable = False
                first_error = res.get("error")
                break

    summary = None
    verdict = "unreachable"
    code = EXIT_OK
    if reachable:
        summary = summarize(results, limit)
        verdict = summary["verdict"]
    else:
        code = EXIT_UNREACHABLE

    payload = {
        "schemaVersion": SCHEMA,
        "contractId": CONTRACT_ID,
        "generatedAtUtc": utcnow(),
        "root": str(root),
        "nowMs": now_ms,
        "via": via,
        "reachable": reachable,
        "firstError": first_error,
        "jdbcUrlEnv": {"name": env_name, "configured": env_value is not None},
        "verdict": verdict,
        "summary": summary,
        "queryErrors": {k: v.get("error") for k, v in results.items()
                        if not v.get("ok")},
        "masking": {"neverSelected": list(NEVER_SELECTED),
                    "note": "identity/status 컬럼만 SELECT; payload·body·자격 값은 읽지 않음"},
        "limits": {"candidateLimit": limit,
                   "never": ["payload_dump", "owner_hash_values", "ddl_or_dml",
                             "print_secrets"]},
        "exitCode": code,
    }
    out_path = Path(args.json_out)
    if not out_path.is_absolute():
        out_path = root / out_path
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                        encoding="utf-8")
    if args.json:
        print(json.dumps(payload, ensure_ascii=False))
    else:
        print(f"job_receipt_inspect verdict={verdict} reachable={reachable} "
              f"exit={code} json={out_path}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
