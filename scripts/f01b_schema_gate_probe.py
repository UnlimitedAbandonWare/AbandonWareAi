#!/usr/bin/env python3
"""f01b_schema_gate_probe.py - F01-B GATE-0 read-only schema probe (Devin assist rail).

Contract: DEMO1-DEVIN-ASSIST-F01B-TRACE-RAILS-20260929 (WP1, track F01B only).
Wraps scripts/db_agent.py - the lmsdb file-H2 SSOT lane. It never opens JDBC
directly, never applies DDL, never prints secrets/DSN/credential values, and
keeps DDL *file* evidence separate from live *DB* evidence.

Items probed (all lowercase-compared; this DB runs DATABASE_TO_UPPER=false):
  tables  : awx_jobs, awx_job_results            (required by F01-B)
            awx_understanding_receipts           (informational - Codex's NEW
                                                  migration; ABSENT pre-impl is expected)
  columns : awx_jobs.admission_key, awx_jobs.request_fingerprint
  index   : awx_jobs_admission_key with INDEX_TYPE_NAME='UNIQUE INDEX'
  ddl     : sha256/mtime of V20260912__durable_jobs.sql + V20260912_03__job_idempotency.sql

verdict:
  GATE0_PASS                  only when observation=complete AND every required item PRESENT
  GATE0_FAIL_EVIDENCE_NEEDED  otherwise (absent items OR incomplete observation)

exit codes:
  0  GATE0_PASS
  4  GATE0_FAIL_EVIDENCE_NEEDED (probe ran, at least one item ABSENT)
  5  probe incomplete (lane error / query rejected) - observation incomplete,
     NOT a schema verdict; record evidence_needed and stop
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB_AGENT = ROOT / "scripts" / "db_agent.py"
DDL_FILES = [
    "main/resources/db/migration/V20260912__durable_jobs.sql",
    "main/resources/db/migration/V20260912_03__job_idempotency.sql",
]
REQUIRED_TABLES = ("awx_jobs", "awx_job_results")
INFO_TABLES = ("awx_understanding_receipts",)
REQUIRED_COLUMNS = ("admission_key", "request_fingerprint")
REQUIRED_INDEX = "awx_jobs_admission_key"

SQL_TABLES = (
    "SELECT LOWER(TABLE_NAME) AS t FROM INFORMATION_SCHEMA.TABLES "
    "WHERE TABLE_SCHEMA='PUBLIC' AND LOWER(TABLE_NAME) IN "
    "('awx_jobs','awx_job_results','awx_understanding_receipts')"
)
SQL_COLUMNS = (
    "SELECT LOWER(COLUMN_NAME) AS c FROM INFORMATION_SCHEMA.COLUMNS "
    "WHERE TABLE_SCHEMA='PUBLIC' AND LOWER(TABLE_NAME)='awx_jobs' AND "
    "LOWER(COLUMN_NAME) IN ('admission_key','request_fingerprint')"
)
SQL_INDEX = (
    "SELECT LOWER(INDEX_NAME) AS i, INDEX_TYPE_NAME FROM INFORMATION_SCHEMA.INDEXES "
    "WHERE TABLE_SCHEMA='PUBLIC' AND LOWER(TABLE_NAME)='awx_jobs' AND "
    "LOWER(INDEX_NAME)='" + REQUIRED_INDEX + "'"
)


def sha256(path: Path):
    if not path.is_file():
        return None
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def db_agent(args: list[str]) -> dict:
    """Run db_agent.py, return parsed JSON payload or an error payload."""
    cmd = [sys.executable, "-B", str(DB_AGENT), *args]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as e:
        return {"ok": False, "laneError": f"spawn:{type(e).__name__}"}
    out = proc.stdout.strip().splitlines()
    payload = None
    for line in reversed(out):
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
    # Token source file *names* are dropped; values are never present anyway.
    payload.pop("tokenSource", None)
    return payload


def query_rows(sql: str, via: str) -> dict:
    return db_agent(["query", "--sql", sql, "--via", via])


def rows_first_col(payload: dict) -> set[str]:
    rows = payload.get("rows") or []
    return {str(r[0]).strip().lower() for r in rows if isinstance(r, list) and r}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--via", default="auto", choices=("auto", "file", "live"),
                    help="db_agent lane: auto=file->live-fallback on lock")
    ap.add_argument("--pretty", action="store_true", help="indented JSON")
    args = ap.parse_args()

    items = []
    lane_notes = []
    lane_error = False

    tables_pl = db_agent(["tables", "--via", args.via])
    if not tables_pl.get("tables") and tables_pl.get("agentExit") not in (0,):
        lane_error = True
        lane_notes.append("tables:agent-error")
    names = {str(t.get("name", "")).strip().lower()
             for t in (tables_pl.get("tables") or [])}
    lane_via = tables_pl.get("via")
    jdbc = tables_pl.get("jdbc") or {}
    if jdbc.get("locked") is True:
        lane_notes.append("file-lane locked -> live-http fallback in use")

    if names:
        for t in REQUIRED_TABLES:
            items.append({"item": f"table:{t}", "status": "PRESENT" if t in names else "ABSENT",
                          "required": True})
        for t in INFO_TABLES:
            items.append({"item": f"table:{t}", "status": "PRESENT" if t in names else "ABSENT",
                          "required": False,
                          "note": "Codex NEW migration; ABSENT before impl is expected"})
    else:
        for t in REQUIRED_TABLES + INFO_TABLES:
            items.append({"item": f"table:{t}", "status": "ERROR",
                          "required": t in REQUIRED_TABLES,
                          "note": "table list not observable"})

    col_pl = query_rows(SQL_COLUMNS, args.via)
    if col_pl.get("ok") is True and col_pl.get("agentExit") == 0:
        cols = rows_first_col(col_pl)
        for c in REQUIRED_COLUMNS:
            items.append({"item": f"column:awx_jobs.{c}",
                          "status": "PRESENT" if c in cols else "ABSENT", "required": True})
    else:
        lane_error = True
        lane_notes.append("columns:query-error")
        for c in REQUIRED_COLUMNS:
            items.append({"item": f"column:awx_jobs.{c}", "status": "ERROR",
                          "required": True,
                          "note": str(col_pl.get("reason") or col_pl.get("laneError") or "query failed")[:160]})

    idx_pl = query_rows(SQL_INDEX, args.via)
    if idx_pl.get("ok") is True and idx_pl.get("agentExit") == 0:
        idx_rows = [(str(r[0]).lower(), str(r[1])) for r in (idx_pl.get("rows") or []) if isinstance(r, list) and len(r) >= 2]
        found = [r for r in idx_rows if r[0] == REQUIRED_INDEX]
        if found and any("UNIQUE" in r[1].upper() for r in found):
            items.append({"item": f"index:{REQUIRED_INDEX}", "status": "PRESENT",
                          "required": True, "detail": found[0][1]})
        elif found:
            items.append({"item": f"index:{REQUIRED_INDEX}", "status": "ABSENT",
                          "required": True,
                          "detail": f"exists but INDEX_TYPE_NAME={found[0][1]!r} (need UNIQUE INDEX)"})
        else:
            items.append({"item": f"index:{REQUIRED_INDEX}", "status": "ABSENT",
                          "required": True})
    else:
        lane_error = True
        lane_notes.append("index:query-error")
        items.append({"item": f"index:{REQUIRED_INDEX}", "status": "ERROR",
                      "required": True,
                      "note": str(idx_pl.get("reason") or idx_pl.get("laneError") or "query failed")[:160]})

    ddl = []
    for rel in DDL_FILES:
        p = ROOT / rel
        exists = p.is_file()
        st = p.stat() if exists else None
        ddl.append({"path": rel, "exists": exists,
                    "sha256": sha256(p) if exists else None,
                    "bytes": st.st_size if st else None,
                    "mtimeUtc": dt.datetime.fromtimestamp(st.st_mtime, dt.timezone.utc).isoformat(timespec="seconds") if st else None,
                    "note": "file-existence evidence only; NOT applied-schema evidence"})

    errors = [i["item"] for i in items if i["status"] == "ERROR"]
    absent_required = [i["item"] for i in items if i["required"] and i["status"] == "ABSENT"]
    if errors or lane_error:
        observation = "partial" if names else "none"
    else:
        observation = "complete"
    verdict = ("GATE0_PASS" if observation == "complete" and not absent_required
               else "GATE0_FAIL_EVIDENCE_NEEDED")
    exit_code = 0 if verdict == "GATE0_PASS" else (5 if observation != "complete" else 4)

    out = {
        "schemaVersion": "awx.f01b-schema-gate-probe.v1",
        "contract": "DEMO1-DEVIN-ASSIST-F01B-TRACE-RAILS-20260929",
        "track": "F01B",
        "probedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "lane": {"via": lane_via, "fileLocked": jdbc.get("locked"), "notes": lane_notes},
        "observation": observation,
        "items": items,
        "ddlFileEvidence": ddl,
        "verdict": verdict,
        "exitCode": exit_code,
        "reminders": [
            "verdict is schema-presence evidence only; product enablement is the Codex contract's job",
            "GATE0_PASS does NOT authorize flipping abandonware.understanding.deferred.enabled",
            "GATE0_FAIL: stay on F01-A, fill evidence_needed - never apply DDL or invent in-memory fallback",
        ],
    }
    print(json.dumps(out, ensure_ascii=False, indent=2 if args.pretty else None))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
