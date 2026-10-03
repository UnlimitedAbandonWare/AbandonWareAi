#!/usr/bin/env python3
"""f01b_job_receipt_read.py - awx_jobs/awx_job_results/awx_understanding_receipts
읽기 전용 요약 (Devin post-tools, Codex WP-4 지원).

Contract: DEMO1-CODEX-POSTF01B-TRACE-R2-TOOLMAP-20260929.
모든 읽기는 scripts/db_agent.py 경유(`--via auto`: file H2 -> live HTTP fallback
on lock). SELECT 만 사용하고 사용자 텍스트 컬럼은 절대 select 하지 않는다 -
payload/body 는 바이트 수, token 컬럼은 present/absent 플래그, hash/key 컬럼은
8자 접두사로 축약한다. DDL/쓰기/재시도 없음, 비밀 출력 없음.

exit codes:
  0  observation=complete (ABSENT 테이블도 관측된 사실로 기록)
  1  usage/IO (--json-out 쓰기 실패 등)
  3  observation=partial|none (lane/query 오류 - 관측 불완전, 실패 아님)
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB_AGENT = ROOT / "scripts" / "db_agent.py"

# 테이블별 select 정책. raw 외 컬럼은 목적별 변환만 하고, 정책에 없는 컬럼은
# 선택하지 않는다(미래의 텍스트 컬럼이 새어나가지 않도록 allowlist 방식).
TABLE_SPECS: dict[str, dict] = {
    "awx_jobs": {
        "orderBy": "created_at",
        "counts": ("state", "job_type", "error_code", "phase", "callback_state"),
        "raw": ("task_id", "job_type", "state", "phase", "error_code",
                "callback_state", "callback_attempts", "created_at",
                "updated_at", "completed_at", "expires_at", "lease_until",
                "callback_next", "callback_until", "result_ref",
                "source_session_id", "original_run_id",
                "compute_attempts", "commit_attempts", "next_attempt_at"),
        "prefix8": ("owner_hash", "admission_key", "request_fingerprint",
                    "effect_key"),
        "presence": ("worker_token", "callback_token"),
        "bytesOf": ("payload",),
    },
    "awx_job_results": {
        "orderBy": None,
        "counts": (),
        "raw": ("result_id", "task_id", "body_bytes"),
        "prefix8": ("body_sha256",),
        "presence": (),
        "bytesOf": ("body",),
    },
    "awx_understanding_receipts": {
        "orderBy": "persisted_at",
        "counts": ("receipt_state", "kind", "channel"),
        "raw": ("session_id", "channel", "consent_epoch", "user_message_id",
                "user_revision", "assistant_message_id", "assistant_revision",
                "kind", "original_run_id", "job_task_id", "usum_message_id",
                "persisted_at", "receipt_state"),
        "prefix8": ("effect_key", "owner_namespace", "result_sha256"),
        "presence": (),
        "bytesOf": (),
    },
}


def db_agent(args: list[str], agent_path: Path) -> dict:
    """db_agent.py 실행 후 마지막 JSON 라인을 반환. 실패 시 laneError 페이로드."""
    cmd = [sys.executable, "-B", str(agent_path), *args]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as e:
        return {"ok": False, "laneError": f"spawn:{type(e).__name__}"}
    payload = None
    for line in reversed(proc.stdout.strip().splitlines()):
        line = line.strip()
        if line.startswith("{"):
            try:
                payload = json.loads(line)
                break
            except ValueError:
                continue
    if payload is None:
        return {"ok": False, "laneError": f"no-json exit={proc.returncode}",
                "stderrTail": (proc.stderr or "")[-200:]}
    payload["agentExit"] = proc.returncode
    payload.pop("tokenSource", None)
    return payload


def query_rows(sql: str, via: str, agent_path: Path) -> dict:
    return db_agent(["query", "--sql", sql, "--via", via], agent_path)


def ok_rows(payload: dict) -> bool:
    return payload.get("ok") is True and payload.get("agentExit") == 0


def first_col_set(payload: dict) -> set[str]:
    out = set()
    for r in (payload.get("rows") or []):
        if isinstance(r, list) and r:
            out.add(str(r[0]).strip().lower())
        elif isinstance(r, dict) and r:
            out.add(str(next(iter(r.values()))).strip().lower())
    return out


def rows_as_dicts(payload: dict) -> list[dict]:
    cols = [str(c) for c in (payload.get("columns") or [])]
    out = []
    for r in (payload.get("rows") or []):
        if isinstance(r, dict):
            out.append({str(k): v for k, v in r.items()})
        elif isinstance(r, list):
            out.append({cols[i] if i < len(cols) else f"c{i}": v
                        for i, v in enumerate(r)})
    return out


def scalar_count(payload: dict) -> int | None:
    rows = payload.get("rows") or []
    if not rows:
        return None
    first = rows[0]
    val = first[0] if isinstance(first, list) else next(iter(first.values()))
    try:
        return int(val)
    except (TypeError, ValueError):
        return None


def build_recent_select(spec: dict, present: set[str]) -> tuple[list[str], str, list[str]]:
    """현재 컬럼 ∩ 정책 allowlist 로 SELECT 절 조립. (exprs, orderClause, maskingNotes)"""
    exprs: list[str] = []
    notes: list[str] = []
    for col in spec["raw"]:
        if col in present:
            exprs.append(col)
    for col in spec["prefix8"]:
        if col in present:
            exprs.append(f"LEFT({col}, 8) AS {col}_prefix")
            notes.append(f"{col}->prefix8")
    for col in spec["presence"]:
        if col in present:
            # db_agent live lane masks column *names* containing 'token';
            # 'tok' aliases keep the present/absent flag readable.
            alias = col.replace("token", "tok") + "_presence"
            exprs.append(
                f"CASE WHEN {col} IS NULL OR {col}='' THEN 'absent' "
                f"ELSE 'present' END AS {alias}")
            notes.append(f"{col}->presence")
    for col in spec["bytesOf"]:
        if col in present:
            exprs.append(f"LENGTH({col}) AS {col}_len")
            notes.append(f"{col}->bytes-only")
    order = f" ORDER BY {spec['orderBy']} DESC" if spec["orderBy"] in present else ""
    return exprs, order, notes


def summarize_table(name: str, spec: dict, present_cols_payload: dict,
                    via: str, agent_path: Path, recent: int) -> tuple[dict, bool]:
    """테이블 하나 요약. (tableResult, laneOk)"""
    result: dict = {"status": "PRESENT"}
    lane_ok = True
    if not ok_rows(present_cols_payload):
        result["status"] = "ERROR"
        result["error"] = str(present_cols_payload.get("reason")
                              or present_cols_payload.get("laneError")
                              or "column-query-failed")[:160]
        return result, False
    present = first_col_set(present_cols_payload)
    result["columnsPresent"] = sorted(present)

    policy_cols = set(spec["raw"]) | set(spec["prefix8"]) | set(spec["presence"]) \
        | set(spec["bytesOf"])
    result["columnsNotSelected"] = sorted(present - policy_cols)

    total_pl = query_rows(f"SELECT COUNT(*) AS c FROM {name}", via, agent_path)
    if ok_rows(total_pl):
        result["totalRows"] = scalar_count(total_pl)
    else:
        lane_ok = False
        result["totalRowsError"] = str(total_pl.get("reason")
                                     or total_pl.get("laneError"))[:160]

    for col in spec["counts"]:
        if col not in present:
            continue
        pl = query_rows(
            f"SELECT {col} AS k, COUNT(*) AS c FROM {name} "
            f"GROUP BY {col} ORDER BY 2 DESC", via, agent_path)
        key = f"{col}Counts"
        if ok_rows(pl):
            counts = {}
            for row in rows_as_dicts(pl):
                k = row.get("k")
                label = "(null)" if k is None else str(k)
                try:
                    counts[label] = int(row.get("c"))
                except (TypeError, ValueError):
                    counts[label] = row.get("c")
            result[key] = counts
        else:
            lane_ok = False
            result[key + "Error"] = str(pl.get("reason")
                                      or pl.get("laneError"))[:160]

    exprs, order, notes = build_recent_select(spec, present)
    result["masked"] = notes
    if exprs and recent > 0:
        sql = (f"SELECT {', '.join(exprs)} FROM {name}{order} "
               f"LIMIT {recent}")
        pl = query_rows(sql, via, agent_path)
        if ok_rows(pl):
            result["recent"] = rows_as_dicts(pl)
            result["recentReturned"] = len(result["recent"])
            if pl.get("truncated"):
                result["recentTruncated"] = True
        else:
            lane_ok = False
            result["recentError"] = str(pl.get("reason")
                                      or pl.get("laneError"))[:160]
    return result, lane_ok


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--via", default="auto", choices=("auto", "file", "live"),
                    help="db_agent lane: auto=file->live-fallback on lock")
    ap.add_argument("--recent", type=int, default=10,
                    help="recent rows per table, 0 disables (cap 200)")
    ap.add_argument("--table", choices=tuple(TABLE_SPECS), default=None,
                    help="limit to one table")
    ap.add_argument("--json-out", default=None,
                    help="also write the JSON payload to this path")
    ap.add_argument("--db-agent", default=None,
                    help="db_agent.py path override (tests)")
    ap.add_argument("--pretty", action="store_true")
    args = ap.parse_args(argv)

    agent_path = Path(args.db_agent) if args.db_agent else DB_AGENT
    recent = max(0, min(args.recent, 200))
    wanted = [args.table] if args.table else list(TABLE_SPECS)

    lane_notes: list[str] = []
    lane_ok = True
    tables_pl = db_agent(["tables", "--via", args.via], agent_path)
    lane_via = tables_pl.get("via")
    jdbc = tables_pl.get("jdbc") or {}
    if jdbc.get("locked") is True:
        lane_notes.append("file-lane locked -> live-http fallback in use")

    names = {str(t.get("name", "")).strip().lower()
             for t in (tables_pl.get("tables") or [])}
    if not names and not ok_rows(tables_pl):
        lane_ok = False
        lane_notes.append("tables:agent-error")

    tables_out: dict[str, dict] = {}
    for name in wanted:
        spec = TABLE_SPECS[name]
        if name not in names:
            tables_out[name] = {"status": "ABSENT" if names else "ERROR",
                                "note": "table list observed"
                                if names else "table list not observable"}
            if not names:
                lane_ok = False
            continue
        col_pl = query_rows(
            "SELECT LOWER(COLUMN_NAME) AS c FROM INFORMATION_SCHEMA.COLUMNS "
            "WHERE TABLE_SCHEMA='PUBLIC' AND "
            f"LOWER(TABLE_NAME)='{name}'", args.via, agent_path)
        res, ok = summarize_table(name, spec, col_pl, args.via, agent_path,
                                  recent)
        tables_out[name] = res
        lane_ok = lane_ok and ok

    observation = "complete" if lane_ok and names else \
        ("partial" if names or any(t.get("status") == "PRESENT"
                                   for t in tables_out.values()) else "none")
    out = {
        "schemaVersion": "awx.f01b-job-receipt-read.v1",
        "contract": "DEMO1-CODEX-POSTF01B-TRACE-R2-TOOLMAP-20260929",
        "probedAtUtc": dt.datetime.now(dt.timezone.utc)
            .isoformat(timespec="seconds"),
        "lane": {"via": lane_via, "fileLocked": jdbc.get("locked"),
                 "notes": lane_notes},
        "observation": observation,
        "recentRequested": recent,
        "tables": tables_out,
        "maskingPolicy": {
            "neverSelected": "user text columns (payload/body) are never "
                             "SELECTed - byte counts only",
            "tokenColumns": "worker_token/callback_token -> present|absent",
            "hashColumns": "hash/key/fingerprint columns -> LEFT(col,8) prefix",
            "allowlist": "columns not in the per-table policy are not selected",
        },
        "reminders": [
            "read-only summary for Codex WP-4; write/retry actions stay forbidden",
            "ABSENT is an observed fact (pre-migration), not a lane failure",
        ],
    }
    exit_code = 0 if observation == "complete" else 3
    out["exitCode"] = exit_code
    text = json.dumps(out, ensure_ascii=False,
                      indent=2 if args.pretty else None)
    if args.json_out:
        try:
            target = Path(args.json_out)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text + "\n", encoding="utf-8")
        except OSError as e:
            print(json.dumps({"status": "json-out-failed", "error": str(e)},
                             ensure_ascii=False))
            return 1
    print(text)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
