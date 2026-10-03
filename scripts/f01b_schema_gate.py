#!/usr/bin/env python3
"""f01b_schema_gate.py — Codex GATE-0 스키마 존재 프로브 (F01-B narrow JDBC).

Contract DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929 §4.1.

FILE 모드: 마이그레이션 파일 존재 + 필요 DDL 문자열 + sha256/mtime 증거.
LIVE 모드: scripts/db_agent.py query --via auto 로 INFORMATION_SCHEMA 읽기 전용
SELECT만 위임 (직접 JDBC/연결 문자열 파싱 없음). --h2-file 은 db_agent --db-path로
전달. --jdbc-url-env 는 env 이름만 기록하고 값은 절대 출력하지 않는다; 값이
설정되어 있으면 자식 프로세스 env 의 LMS_DB_URL 로만 전달한다.

판정 (절대 PRESENT 를 지어내지 않음):
  exit 0 GATE0_PASS                    — file OK AND (live 생략 OR live 전부 PRESENT)
  exit 2 GATE0_FAIL                    — file DDL 결함 OR live 연결됐는데 필수 객체 ABSENT
  exit 3 GATE0_PARTIAL_EVIDENCE_NEEDED — file OK 이지만 live 미연결/미설정 → Codex stay-A
  exit 1 사용/IO 오류, exit 4 secrets/안전 중단

NEVER: DDL APPLY. NEVER flip abandonware.understanding.deferred.enabled.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

CONTRACT_ID = "DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929"
SCHEMA = "awx.f01b-schema-gate.v1"
DEFAULT_OUT_DIR = "data/diagnostics/f01b-trace-access-0929"
DEFAULT_MIGRATIONS_DIR = "main/resources/db/migration"
DURABLE_JOBS_SQL = "V20260912__durable_jobs.sql"
IDEMPOTENCY_SQL = "V20260912_03__job_idempotency.sql"
REQUIRED_TABLES = ("awx_jobs", "awx_job_results")
REQUIRED_COLUMNS = ("admission_key", "request_fingerprint")
REQUIRED_INDEX = "awx_jobs_admission_key"

EXIT_PASS = 0
EXIT_USAGE = 1
EXIT_FAIL = 2
EXIT_PARTIAL = 3
EXIT_SAFETY = 4


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_evidence(path: Path) -> dict:
    """마이그레이션 파일 존재·문자열·해시 증거 (없으면 missing 표시만)."""
    entry = {"path": str(path), "exists": False}
    if not path.is_file():
        return entry
    try:
        raw = path.read_bytes()
    except OSError as exc:
        entry["error"] = type(exc).__name__
        return entry
    entry.update({
        "exists": True,
        "bytes": len(raw),
        "sha256": sha256_bytes(raw),
        "mtimeUtc": dt.datetime.fromtimestamp(
            path.stat().st_mtime, dt.timezone.utc).isoformat(timespec="seconds"),
    })
    return entry


def scan_migration_files(migrations_dir: Path) -> dict:
    """파일 기반 스키마 단서: 테이블/컬럼/유니크 인덱스 DDL 문자열 존재 확인."""
    durable = migrations_dir / DURABLE_JOBS_SQL
    idem = migrations_dir / IDEMPOTENCY_SQL
    result = {
        "durableJobs": file_evidence(durable),
        "jobIdempotency": file_evidence(idem),
        "checks": {},
    }
    text_d = durable.read_text(encoding="utf-8", errors="replace") \
        if result["durableJobs"].get("exists") else ""
    text_i = idem.read_text(encoding="utf-8", errors="replace") \
        if result["jobIdempotency"].get("exists") else ""

    def has(text: str, *needles: str) -> bool:
        low = text.lower()
        return all(n.lower() in low for n in needles)

    checks = result["checks"]
    checks["awx_jobs_create"] = bool(
        re.search(r"create\s+table\s+if\s+not\s+exists\s+awx_jobs\b", text_d, re.I))
    checks["awx_job_results_create"] = bool(
        re.search(r"create\s+table\s+if\s+not\s+exists\s+awx_job_results\b", text_d, re.I))
    checks["admission_key_column"] = has(text_i, "admission_key")
    checks["request_fingerprint_column"] = has(text_i, "request_fingerprint")
    checks["unique_admission_key_index"] = bool(
        re.search(r"create\s+unique\s+index\s+awx_jobs_admission_key\b", text_i, re.I))
    result["ok"] = all(checks.values()) and result["durableJobs"]["exists"] \
        and result["jobIdempotency"]["exists"]
    return result


def run_db_agent_query(root: Path, db_agent: Path, sql: str, env: dict,
                       h2_file: str | None, timeout: int) -> dict:
    """db_agent.py query 위임. 반환: transport ok 여부 + rows."""
    cmd = [sys.executable, "-B", str(db_agent), "query", "--via", "auto",
           "--sql", sql, "--timeout", str(timeout)]
    if h2_file:
        cmd += ["--db-path", h2_file]
    try:
        proc = subprocess.run(cmd, cwd=str(root), env=env,
                              capture_output=True, text=True, timeout=timeout + 15)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"reachable": False, "error": type(exc).__name__, "rows": None}
    out = (proc.stdout or "").strip()
    try:
        payload = json.loads(out.splitlines()[-1]) if out else {}
    except ValueError:
        payload = {}
    if proc.returncode != 0 or not payload.get("ok"):
        return {"reachable": False, "error": payload.get("error") or
                    f"exit_{proc.returncode}",
                "reason": payload.get("reason", "")[:200], "rows": None}
    return {"reachable": True, "via": payload.get("via"), "rows": payload.get("rows") or []}


def probe_live(root: Path, db_agent: Path, env: dict, h2_file: str | None,
               timeout: int) -> dict:
    """INFORMATION_SCHEMA 읽기 전용 프로브. 행 본문/시크릿 덤프 금지."""
    live = {"dbAgent": str(db_agent), "reachable": False, "via": None,
            "tables": {}, "columns": {}, "index": {}, "error": None}
    if not db_agent.is_file():
        live["error"] = "db_agent_missing"
        return live

    t_sql = ("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE "
             "UPPER(TABLE_NAME) IN ('AWX_JOBS','AWX_JOB_RESULTS')")
    c_sql = ("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE "
             "UPPER(TABLE_NAME)='AWX_JOBS' AND UPPER(COLUMN_NAME) IN "
             "('ADMISSION_KEY','REQUEST_FINGERPRINT')")
    i_sql = ("SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE "
             "UPPER(TABLE_NAME)='AWX_JOBS' AND UPPER(INDEX_NAME)='AWX_JOBS_ADMISSION_KEY'")

    first = run_db_agent_query(root, db_agent, t_sql, env, h2_file, timeout)
    if not first["reachable"]:
        live["error"] = first.get("error") or "unreachable"
        live["reason"] = first.get("reason")
        return live
    live["reachable"] = True
    live["via"] = first.get("via")

    found = {str(r.get("TABLE_NAME") or r.get("table_name") or "").lower()
             for r in first["rows"]}
    for t in REQUIRED_TABLES:
        live["tables"][t] = "PRESENT" if t in found else "ABSENT"

    cols = run_db_agent_query(root, db_agent, c_sql, env, h2_file, timeout)
    if cols["reachable"]:
        got = {str(r.get("COLUMN_NAME") or r.get("column_name") or "").lower()
               for r in cols["rows"]}
        for c in REQUIRED_COLUMNS:
            live["columns"][c] = "PRESENT" if c in got else "ABSENT"
    else:
        for c in REQUIRED_COLUMNS:
            live["columns"][c] = "ERROR"

    idx = run_db_agent_query(root, db_agent, i_sql, env, h2_file, timeout)
    if idx["reachable"]:
        names = {str(r.get("INDEX_NAME") or r.get("index_name") or "").lower()
                 for r in idx["rows"]}
        live["index"][REQUIRED_INDEX] = \
            "PRESENT" if REQUIRED_INDEX in names else "ABSENT"
    else:
        live["index"][REQUIRED_INDEX] = "ERROR"
    return live


def verdict_and_exit(mode: str, file_res: dict, live: dict | None) -> tuple[str, int]:
    if not file_res["ok"]:
        return "GATE0_FAIL", EXIT_FAIL
    if mode == "file":
        return "GATE0_PASS", EXIT_PASS
    if live is None or not live.get("reachable"):
        return "GATE0_PARTIAL_EVIDENCE_NEEDED", EXIT_PARTIAL
    checks = (list(live["tables"].values()) + list(live["columns"].values())
              + list(live["index"].values()))
    if any(v == "ERROR" for v in checks):
        return "GATE0_PARTIAL_EVIDENCE_NEEDED", EXIT_PARTIAL
    if all(v == "PRESENT" for v in checks):
        return "GATE0_PASS", EXIT_PASS
    return "GATE0_FAIL", EXIT_FAIL


def write_md(path: Path, payload: dict):
    lines = [
        "# GATE0_PROBE — F01-B narrow JDBC schema gate", "",
        f"- contractId: `{payload['contractId']}`",
        f"- generatedAtUtc: {payload['generatedAtUtc']}",
        f"- mode: `{payload['mode']}`",
        f"- verdict: **{payload['verdict']}**",
        f"- productEnablement: `{payload['productEnablement']}`", "",
        "## FILE evidence", "",
    ]
    for name, ev in (("durableJobs", payload["file"]["durableJobs"]),
                     ("jobIdempotency", payload["file"]["jobIdempotency"])):
        lines.append(f"- `{name}` {ev['path']}: exists={ev.get('exists')} "
                     f"sha256={str(ev.get('sha256'))[:16]}… mtimeUtc={ev.get('mtimeUtc')}")
    lines.append("### checks")
    for k, v in payload["file"]["checks"].items():
        lines.append(f"- {k}: {v}")
    lines += ["", "## LIVE probe", ""]
    live = payload.get("live") or {}
    lines.append(f"- reachable: {live.get('reachable')} via={live.get('via')} "
                 f"error={live.get('error')}")
    lines.append(f"- tables: {live.get('tables')}")
    lines.append(f"- columns: {live.get('columns')}")
    lines.append(f"- index: {live.get('index')}")
    lines += ["", "## NEVER", "",
              "- DDL apply 없음 (read-only INFORMATION_SCHEMA only)",
              "- `abandonware.understanding.deferred.enabled` 플립 금지",
              "- GATE0 exit 2/3 → Codex stay F01-A + evidence_needed", ""]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="F01-B GATE-0 schema presence probe (file + optional live read-only)")
    ap.add_argument("--root", default=".")
    ap.add_argument("--mode", choices=["file", "live", "both"], default="both")
    ap.add_argument("--jdbc-url-env", default="AWX_JDBC_URL",
                    help="env 이름만 기록; 값은 출력/저장 금지")
    ap.add_argument("--h2-file", default=None)
    ap.add_argument("--migrations-dir", default=DEFAULT_MIGRATIONS_DIR)
    ap.add_argument("--db-agent", default="scripts/db_agent.py")
    ap.add_argument("--timeout", type=int, default=20)
    ap.add_argument("--json-out",
                    default=f"{DEFAULT_OUT_DIR}/f01b_schema_gate.json")
    ap.add_argument("--md-out",
                    default="docs/diagnostics/f01b-narrow-jdbc-0929/GATE0_PROBE.md")
    ap.add_argument("--json", action="store_true", help="stdout JSON 출력")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    migrations_dir = root / args.migrations_dir
    env_name = str(args.jdbc_url_env).strip()
    env_value = os.environ.get(env_name)
    env_note = {"name": env_name, "configured": env_value is not None}

    child_env = dict(os.environ)
    # env 값은 자식 프로세스로만 전달 (LMS_DB_URL 로 db_agent가 읽음). 출력 금지.
    if env_value:
        child_env.setdefault("LMS_DB_URL", env_value)

    file_res = scan_migration_files(migrations_dir)
    live = None
    if args.mode in ("live", "both"):
        live = probe_live(root, root / args.db_agent, child_env,
                          args.h2_file, args.timeout)

    verdict, code = verdict_and_exit(args.mode, file_res, live)
    payload = {
        "schemaVersion": SCHEMA,
        "contractId": CONTRACT_ID,
        "gate": "GATE0",
        "generatedAtUtc": utcnow(),
        "root": str(root),
        "mode": args.mode,
        "verdict": verdict,
        "exitCode": code,
        "jdbcUrlEnv": env_note,
        "file": file_res,
        "live": live,
        "productEnablement": "blocked_until_codex",
        "never": ["ddl_apply", "print_secrets", "flip_deferred_flag",
                  "invent_present"],
    }
    out_path = root / args.json_out
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                        encoding="utf-8")
    if args.md_out:
        try:
            write_md(root / args.md_out, payload)
        except OSError:
            pass
    if args.json:
        print(json.dumps(payload, ensure_ascii=False))
    else:
        print(f"GATE0 verdict={verdict} exit={code} json={out_path}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
