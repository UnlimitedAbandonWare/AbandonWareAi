#!/usr/bin/env python3
"""db_migration_ledger.py — migration SQL 원장: 파일 sha256 + DB 적용 여부.

Contract DEMO1-DEVIN-F01B-POST-TOOLS-20260929 항목 1 (G1).

이 프로젝트는 flyway/liquibase 가 없다 — build.gradle.kts 에 미등록, 따라서
"어느 SQL이 DB에 적용됐는가"는 handoff JSON 에만 남는다. 이 도구는
`main/resources/db/migration/*.sql` 을 파싱해 기대 객체(table/index/column/
constraint)를 뽑고, INFORMATION_SCHEMA 읽기 전용 SELECT 로 실재 여부를
대조해 applied/partial/missing 을 기록한다.

주의(정직성): 이 결과는 "객체 실재 추론"이지 진짜 마이그레이션 이력이 아니다.
히스토리 테이블(flyway_schema_history 등)이 있으면 같이 보고한다.

- live 조회는 scripts/db_agent.py query --via auto 위임 (SELECT only)
- --handoff-dir 로 migration-*-applied.json 적용 주장과 교차 대조
- 출력: db-ledger/LATEST.json
- --fixture <json> 으로 INFORMATION_SCHEMA 응답을 대체해 오프라인 실행

exit 0 executed / 1 usage·IO / 2 migrations-dir missing / 3 unreachable.
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

CONTRACT_ID = "DEMO1-DEVIN-F01B-POST-TOOLS-20260929"
SCHEMA = "awx.db-migration-ledger.v1"
DEFAULT_MIGRATIONS_DIR = "main/resources/db/migration"
DEFAULT_OUT = "db-ledger/LATEST.json"
HISTORY_TABLES = ("FLYWAY_SCHEMA_HISTORY", "DATABASECHANGELOG",
                  "DATABASECHANGELOGLOCK", "SCHEMA_VERSION",
                  "AWX_SCHEMA_HISTORY")

EXIT_OK = 0
EXIT_USAGE = 1
EXIT_NO_DIR = 2
EXIT_UNREACHABLE = 3

RE_CREATE_TABLE = re.compile(
    r"create\s+table\s+(?:if\s+not\s+exists\s+)?([A-Za-z0-9_]+)", re.I)
RE_CREATE_INDEX = re.compile(
    r"create\s+(?:unique\s+)?index\s+(?:if\s+not\s+exists\s+)?"
    r"([A-Za-z0-9_]+)\s+on\s+([A-Za-z0-9_]+)", re.I)
RE_ADD_COLUMN = re.compile(
    r"alter\s+table\s+([A-Za-z0-9_]+)\s+add\s+column\s+([A-Za-z0-9_]+)", re.I)
RE_ADD_CONSTRAINT = re.compile(
    r"alter\s+table\s+([A-Za-z0-9_]+)\s+add\s+constraint\s+([A-Za-z0-9_]+)", re.I)
RE_INLINE_INDEX = re.compile(r"\bindex\s+([A-Za-z0-9_]+)\s*\(", re.I)
RE_INLINE_CONSTRAINT = re.compile(r"\bconstraint\s+([A-Za-z0-9_]+)\b", re.I)


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_sql_objects(text: str) -> dict:
    """SQL 텍스트 → 기대 객체 {tables, indexes[{name,table}], columns[{table,name}],
    constraints[{name}]}. 마이그레이션 관용 패턴만 커버 — 범용 SQL 파서 아님."""
    objects = {"tables": [], "indexes": [], "columns": [], "constraints": []}
    statements = [s.strip() for s in text.split(";") if s.strip()]
    for stmt in statements:
        m = RE_CREATE_TABLE.search(stmt)
        if m:
            table = m.group(1).lower()
            objects["tables"].append(table)
            # CREATE TABLE 본문 안의 inline INDEX / CONSTRAINT 도 수집.
            for im in RE_INLINE_INDEX.finditer(stmt):
                objects["indexes"].append(
                    {"name": im.group(1).lower(), "table": table})
            for cm in RE_INLINE_CONSTRAINT.finditer(stmt):
                objects["constraints"].append(
                    {"name": cm.group(1).lower(), "table": table})
            continue
        m = RE_CREATE_INDEX.search(stmt)
        if m:
            objects["indexes"].append({"name": m.group(1).lower(),
                                       "table": m.group(2).lower()})
            continue
        m = RE_ADD_COLUMN.search(stmt)
        if m:
            objects["columns"].append({"table": m.group(1).lower(),
                                       "name": m.group(2).lower()})
            continue
        m = RE_ADD_CONSTRAINT.search(stmt)
        if m:
            objects["constraints"].append({"name": m.group(2).lower(),
                                           "table": m.group(1).lower()})
    return objects


def scan_migrations(migrations_dir: Path) -> list[dict]:
    files = []
    for path in sorted(migrations_dir.glob("*.sql")):
        raw = path.read_bytes()
        text = raw.decode("utf-8", errors="replace")
        files.append({
            "file": str(path).replace("\\", "/"),
            "name": path.name,
            "bytes": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest(),
            "mtimeUtc": dt.datetime.fromtimestamp(
                path.stat().st_mtime, dt.timezone.utc)
                .isoformat(timespec="seconds"),
            "objects": parse_sql_objects(text),
        })
    return files


def run_db_agent_query(root: Path, db_agent: Path, sql: str, env: dict,
                       h2_file: str | None, timeout: int) -> dict:
    cmd = [sys.executable, "-B", str(db_agent), "query", "--via", "auto",
           "--sql", sql, "--timeout", str(timeout)]
    if h2_file:
        cmd += ["--db-path", h2_file]
    try:
        proc = subprocess.run(cmd, cwd=str(root), env=env,
                              capture_output=True, text=True,
                              timeout=timeout + 15)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"ok": False, "error": type(exc).__name__}
    out = (proc.stdout or "").strip()
    try:
        payload = json.loads(out.splitlines()[-1]) if out else {}
    except ValueError:
        payload = {}
    if proc.returncode != 0 or not payload.get("ok"):
        return {"ok": False,
                "error": payload.get("reason") or payload.get("error")
                    or f"exit_{proc.returncode}"}
    cols = [str(c).upper() for c in (payload.get("columns") or [])]
    rows = [dict(zip(cols, r)) if isinstance(r, (list, tuple)) else r
            for r in (payload.get("rows") or [])]
    return {"ok": True, "via": payload.get("via"), "rows": rows}


def _in_list(names) -> str:
    return ",".join(f"'{str(n).upper()}'" for n in sorted(set(names)))


def live_objects(root: Path, db_agent: Path, env: dict, h2_file: str | None,
                 timeout: int, files: list[dict]) -> dict:
    """모든 기대 객체를 INFORMATION_SCHEMA 에서 한 번에 조회."""
    tables = sorted({t for f in files for t in f["objects"]["tables"]}
                    | {c["table"] for f in files
                       for c in f["objects"]["columns"]}
                    | {i["table"] for f in files
                       for i in f["objects"]["indexes"]})
    if not tables:
        return {"reachable": True, "tables": set(), "columns": set(),
                "indexes": set(), "history": []}
    t_in = _in_list(tables)
    out = {"reachable": True}
    q = run_db_agent_query(
        root, db_agent,
        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE "
        f"UPPER(TABLE_NAME) IN ({t_in})", env, h2_file, timeout)
    if not q.get("ok"):
        return {"reachable": False, "error": q.get("error")}
    out["via"] = q.get("via")
    out["tables"] = {str(r.get("TABLE_NAME", "")).lower()
                     for r in q["rows"]}
    q = run_db_agent_query(
        root, db_agent,
        "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
        f"WHERE UPPER(TABLE_NAME) IN ({t_in})", env, h2_file, timeout)
    out["columns"] = {(str(r.get("TABLE_NAME", "")).lower(),
                       str(r.get("COLUMN_NAME", "")).lower())
                      for r in (q["rows"] if q.get("ok") else [])}
    if not q.get("ok"):
        out["columnsError"] = q.get("error")
    q = run_db_agent_query(
        root, db_agent,
        "SELECT TABLE_NAME, INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
        f"WHERE UPPER(TABLE_NAME) IN ({t_in})", env, h2_file, timeout)
    out["indexes"] = {(str(r.get("TABLE_NAME", "")).lower(),
                       str(r.get("INDEX_NAME", "")).lower())
                      for r in (q["rows"] if q.get("ok") else [])}
    if not q.get("ok"):
        out["indexesError"] = q.get("error")
    q = run_db_agent_query(
        root, db_agent,
        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE "
        f"UPPER(TABLE_NAME) IN ({_in_list(HISTORY_TABLES)})",
        env, h2_file, timeout)
    out["history"] = [str(r.get("TABLE_NAME", "")).lower()
                      for r in (q["rows"] if q.get("ok") else [])]
    if not q.get("ok"):
        out["historyError"] = q.get("error")
    return out


def judge_file(file_entry: dict, live: dict) -> dict:
    """파일 하나의 applied/partial/missing 판정 + missing 객체 목록."""
    obj = file_entry["objects"]
    missing_parts = []
    present_parts = 0
    total_parts = 0
    for t in obj["tables"]:
        total_parts += 1
        if t in live["tables"]:
            present_parts += 1
        else:
            missing_parts.append(f"table:{t}")
    for c in obj["columns"]:
        total_parts += 1
        if (c["table"], c["name"]) in live["columns"]:
            present_parts += 1
        else:
            missing_parts.append(f"column:{c['table']}.{c['name']}")
    for i in obj["indexes"]:
        total_parts += 1
        if (i["table"], i["name"]) in live["indexes"]:
            present_parts += 1
        else:
            missing_parts.append(f"index:{i['name']}")
    # constraint 실재는 INFORMATION_SCHEMA 별도 확인 생략 — 판정 분모에 넣지 않고
    # constraintsNotChecked 목록으로만 보고한다 (not_observed).

    if total_parts == 0:
        status = "unparsed"
    elif not live.get("reachable"):
        status = "unknown"
    elif missing_parts and present_parts == 0:
        status = "missing"
    elif missing_parts:
        status = "partial"
    else:
        status = "applied"
    return {"file": file_entry["name"],
            "status": status,
            "presentParts": present_parts,
            "missingParts": missing_parts,
            "constraintsNotChecked": [c["name"] for c in obj["constraints"]]}


def handoff_claims(handoff: Path) -> dict:
    """migration-*-applied.json → 파일별 적용 주장 {name-ish: claim}."""
    claims = {}
    if not handoff.is_dir():
        return claims
    for path in sorted(handoff.glob("migration-*-applied.json")):
        data = _json(path) or {}
        target = data.get("file") or ""
        claims[str(target)] = {
            "applied": data.get("applied"),
            "approvedSha256": data.get("approvedMigrationSha256"),
            "statementCount": data.get("statementCount"),
            "evidence": path.name,
        }
    return claims


def _json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def load_fixture(path: Path) -> dict:
    data = _json(path) or {}
    live = data.get("live") or {}
    for key in ("tables", "columns", "indexes"):
        live[key] = {tuple(v) if isinstance(v, list) else v
                     for v in (live.get(key) or [])}
    live["reachable"] = bool(live.get("reachable", True))
    return live


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="migration SQL 원장: sha256 + INFORMATION_SCHEMA 적용 대조 "
                    "(read-only, flyway 미사용 프로젝트용)")
    ap.add_argument("--root", default=".")
    ap.add_argument("--migrations-dir", default=DEFAULT_MIGRATIONS_DIR)
    ap.add_argument("--db-agent", default="scripts/db_agent.py")
    ap.add_argument("--h2-file", default=None)
    ap.add_argument("--jdbc-url-env", default="AWX_JDBC_URL",
                    help="env 이름만 기록; 값 출력 금지")
    ap.add_argument("--timeout", type=int, default=20)
    ap.add_argument("--handoff-dir", default=None,
                    help="migration-*-applied.json 주장 교차 대조 디렉터리")
    ap.add_argument("--fixture", default=None,
                    help="live 응답 fixture JSON - 오프라인 실행")
    ap.add_argument("--json-out", default=DEFAULT_OUT)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    migrations_dir = root / args.migrations_dir
    if not migrations_dir.is_dir():
        print(f"migrations dir 없음: {migrations_dir}", file=sys.stderr)
        return EXIT_NO_DIR

    files = scan_migrations(migrations_dir)
    env_name = str(args.jdbc_url_env).strip()
    env_value = os.environ.get(env_name)
    child_env = dict(os.environ)
    if env_value:
        child_env.setdefault("LMS_DB_URL", env_value)

    if args.fixture:
        fpath = Path(args.fixture)
        if not fpath.is_absolute():
            fpath = root / fpath
        if not fpath.is_file():
            print(f"fixture 없음: {fpath}", file=sys.stderr)
            return EXIT_USAGE
        live = load_fixture(fpath)
        via = "fixture"
    else:
        db_agent = root / args.db_agent
        if not db_agent.is_file():
            print(f"db_agent 없음: {db_agent}", file=sys.stderr)
            return EXIT_USAGE
        live = live_objects(root, db_agent, child_env, args.h2_file,
                            args.timeout, files)
        via = live.get("via")

    code = EXIT_OK
    if not live.get("reachable"):
        verdict = "unreachable"
        code = EXIT_UNREACHABLE
    else:
        verdict = "executed"

    ledger = []
    claims = handoff_claims(root / args.handoff_dir) \
        if args.handoff_dir else {}
    for f in files:
        entry = {"file": f["name"], "path": f["file"],
                 "sha256": f["sha256"], "bytes": f["bytes"],
                 "mtimeUtc": f["mtimeUtc"],
                 "objects": {k: v for k, v in f["objects"].items()}}
        if live.get("reachable"):
            entry.update(judge_file(f, live))
        else:
            entry["status"] = "unknown"
        # handoff 주장 대조: path 또는 basename 매치.
        for claim_path, claim in claims.items():
            if claim_path.endswith(f["name"]) or \
                    Path(claim_path).name == f["name"] or \
                    claim_path == f["file"]:
                entry["handoffClaim"] = claim
                if claim.get("approvedSha256") and \
                        claim["approvedSha256"] != f["sha256"]:
                    entry["sha256DriftFromClaim"] = True
                break
        ledger.append(entry)

    payload = {
        "schemaVersion": SCHEMA,
        "contractId": CONTRACT_ID,
        "generatedAtUtc": utcnow(),
        "root": str(root),
        "migrationsDir": args.migrations_dir,
        "via": via,
        "reachable": live.get("reachable"),
        "historyTables": live.get("history") or [],
        "basis": ("객체 실재 추론 — flyway_schema_history/liquibase 로그가 아님. "
                  "constraints 는 미확인(not_observed)"),
        "liveErrors": {k: v for k, v in live.items()
                       if k.endswith("Error") or k == "error"},
        "ledger": ledger,
        "verdict": verdict,
        "exitCode": code,
        "never": ["ddl apply", "schema write", "print_secrets"],
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
        counts = {}
        for e in ledger:
            counts[e["status"]] = counts.get(e["status"], 0) + 1
        print(f"migration_ledger verdict={verdict} files={len(ledger)} "
              f"status={counts} json={out_path}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
