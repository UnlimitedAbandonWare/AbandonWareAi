#!/usr/bin/env python3
"""db_agent.py - agent-facing CLI for the Start-RAG file H2 store.

Target DB (same SoT as profile local,meta-display):
    jdbc:h2:file:./var/meta-display-db/lmsdb;MODE=MariaDB;DATABASE_TO_UPPER=false

Commands (run from the Project Root):
    status                            url(redacted)/file/locked via real JDBC probe
    tables [--with-counts]            INFORMATION_SCHEMA.TABLES (+COUNT(*) per table)
    schema [--table NAME]             INFORMATION_SCHEMA.COLUMNS
    get-user --username NAME          administrators row (username/role/hashPrefix only)
    verify-admin --username NAME      present + bcrypt-prefix check (exit 5 on miss)
    query --sql "..." [--max-rows N]  read-only SELECT gate, CSVWRITE result
    apply --file F (--dry-run|--i-mean-it) [--allow-tables a,b]
    upsert-admin --username N [--role ROLE_ADMIN] [--name N]
                [--via auto|file|live-http] [--verify-only|--dry-run]

Lane select: --via auto (default; env AWX_DB_VIA overrides) = file first, and
when the JDBC probe reports locked the READ commands (status/tables/schema/
get-user/verify-admin/query) automatically delegate to the live HTTP lane
(GET/POST /api/internal/db/meta/* via meta_display_db_export.live_request,
token from DOMAIN_ALLOWLIST_ADMIN_TOKEN/AWX_ADMIN_TOKEN/LLM_OWNER_TOKEN or
var/dev-admin-token.txt). --via file = JDBC only (old behavior), --via live
= HTTP only (no jar needed). Writes never auto-fall back silently:
upsert-admin --via auto falls to the guarded live upsert endpoint when the
store is locked; --via file stays offline-JDBC.

stdout is one-line JSON (add --pretty for indented output). Secrets stay in env
only: LMS_LOCAL_ADMIN_PASSWORD (admin upsert), LMS_DB_USER/LMS_DB_PASSWORD (H2
credentials; the password is forwarded to the java -password argv transiently -
local dev DB only). Never prints a plaintext password or a full bcrypt hash;
query results mask password|secret|token|credential|api_key|rrn columns to a
7-char prefix + "...masked" (same as Protect-AwxRow).

Lock contract: a running Spring JVM holds lmsdb.mv.db exclusively. The probe is
a REAL JDBC open (org.h2.tools.RunScript), never an OS file-open guess. Locked
=> exit 3 + {"reason": "locked"} for writes; reads try live HTTP first when a
token/base URL resolves. Never kill the server to unlock.

Exit codes: 0 ok | 2 bad args/prereq (file/url missing) | 3 locked/busy
| 4 toolchain missing (java/h2 jar/ps1) | 5 sql rejected/verify/row missing.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))
# Reuse the proven helpers (h2 jar resolve, read-only gate, literal scrubber,
# guarded live HTTP lane to /api/internal/db/meta/*).
from meta_display_db_export import (  # noqa: E402
    LIVE_API, check_sql, find_h2_jar, live_request, quote_ident, scrub_literals)

DEFAULT_DB_BASE = ROOT / "var" / "meta-display-db" / "lmsdb"
EXPORT_LATEST = ROOT / "var" / "meta-display-db" / "export" / "latest.json"
URL_OPTS = "MODE=MariaDB;DATABASE_TO_UPPER=false;IFEXISTS=TRUE"
UPSERT_PS1 = ROOT / "scripts" / "create-local-admin.ps1"
ADMIN_PASSWORD_ENV = "LMS_LOCAL_ADMIN_PASSWORD"
DB_USER_ENV = "LMS_DB_USER"
DB_PASS_ENV = "LMS_DB_PASSWORD"

LOCK_RE = re.compile(
    r"already in use|may be already in use|FileLocked|file is locked|"
    r"access denied|AccessDenied", re.IGNORECASE)
MAX_ROWS_DEFAULT = 100
MAX_ROWS_HARD = 5000
JAVA_TIMEOUT = 180

# Write lane gate. First keyword decides the class; the deny list is absolute.
APPLY_ALLOW_FIRST = {"insert", "update", "delete", "merge", "create",
                     "select", "with", "values", "explain"}
APPLY_DENY_TOKENS = re.compile(
    r"\b(drop|truncate|alter|rename|grant|revoke|shutdown|script|backup|"
    r"checkpoint|runscript)\b", re.IGNORECASE)
DEFAULT_WRITE_ALLOWLIST = {"administrators"}
# Operator approval is still required. This opt-in permits only these immutable
# F01 migrations on the explicitly named canonical development file store.
F01_LOCAL_MIGRATIONS = {
    "main/resources/db/migration/V20260912__durable_jobs.sql":
        "b575731f241548755d59953d28536f08fcf0dd0da8645a17b52fe05f37a023b3",
    "main/resources/db/migration/V20260912_03__job_idempotency.sql":
        "784b9c766145cc54908d3dc8907fd8a10f146bea2df12720b57f5187377984c0",
    "docs/diagnostics/f01b-understanding-receipt-proposal.sql":
        "837e1b9b03312b09d4f48534c465b79427f4918c3055d8f2d2ab32ae7118ccb5",
}
TARGET_RES = (
    re.compile(r"\binsert\s+into\s+([\"`\w.]+)", re.IGNORECASE),
    re.compile(r"\bmerge\s+into\s+([\"`\w.]+)", re.IGNORECASE),
    re.compile(r"\bupdate\s+(?:only\s+)?([\"`\w.]+)", re.IGNORECASE),
    re.compile(r"\bdelete\s+from\s+([\"`\w.]+)", re.IGNORECASE),
    re.compile(r"\bcreate\s+table\s+(?:if\s+not\s+exists\s+)?([\"`\w.]+)", re.IGNORECASE),
)

# Result-cell masking - mirrors Protect-AwxRow in scripts/db/h2-common.ps1:
# sensitive columns keep only a 7-char prefix + "...masked".
MASK_COLS_RE = re.compile(
    r"password|passwd|secret|token|credential|api_key|apikey|rrn",
    re.IGNORECASE)
VIA_ENV = "AWX_DB_VIA"  # auto|file|live (reads) / auto|file|live-http (upsert)


# ---------------------------------------------------------------- output / io

def emit(payload: dict, code: int = 0, pretty: bool = False) -> int:
    payload.setdefault("ok", code == 0)
    payload.setdefault("exitCode", code)
    if pretty:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
    return code


def redact(text: str) -> str:
    return re.sub(r"(?i)(password\s*=)[^;]*", r"\1****", text or "")


def sql_literal(value: str) -> str:
    return "'" + (value or "").replace("'", "''") + "'"


def db_base_from_url(url: str) -> Path | None:
    m = re.match(r"jdbc:h2:file:([^;]+)", url or "", re.IGNORECASE)
    if not m:
        return None
    p = Path(m.group(1))
    return p if p.is_absolute() else (ROOT / p)


def resolve_db(args) -> tuple[dict | None, dict | None]:
    """-> (info, error). info: {url, dbBase, mvDb, urlSource}."""
    db_path = getattr(args, "db_path", None)
    env_url = os.environ.get("LMS_DB_URL", "").strip()
    if db_path:
        base = Path(db_path)
        if not base.is_absolute():
            base = ROOT / base
        s = str(base)
        if s.endswith(".mv.db"):
            base = Path(s[:-6])
        url = f"jdbc:h2:file:{base.resolve().as_posix()};{URL_OPTS}"
        src = "--db-path"
    elif env_url:
        if not env_url.lower().startswith("jdbc:h2:file:"):
            return None, {"error": "non-file-url",
                          "reason": "LMS_DB_URL is not jdbc:h2:file:* - this tool only handles the local file H2"}
        url = env_url if "IFEXISTS" in env_url.upper() else env_url + ";IFEXISTS=TRUE"
        base = db_base_from_url(env_url) or DEFAULT_DB_BASE
        src = "LMS_DB_URL"
    else:
        base = DEFAULT_DB_BASE
        url = f"jdbc:h2:file:{base.resolve().as_posix()};{URL_OPTS}"
        src = "default"
    mv_db = Path(str(base) + ".mv.db")
    return {"url": url, "dbBase": str(base), "mvDb": str(mv_db), "urlSource": src}, None


def run_runscript(jar: str, url: str, sql_text: str,
                  timeout: int = JAVA_TIMEOUT) -> tuple[int, str, str]:
    """One JVM launch per call. Returns (exit, stdout, stderr)."""
    java = shutil.which("java")
    if not java:
        return -1, "", "java.exe not on PATH (JDK 17+ required)"
    work = Path(tempfile.mkdtemp(prefix="awx-dbagent-"))
    try:
        script = work / "run.sql"
        script.write_text(sql_text, encoding="utf-8")
        argv = [java, "-Dfile.encoding=UTF-8", "-cp", jar,
                "org.h2.tools.RunScript", "-url", url,
                "-user", os.environ.get(DB_USER_ENV, "sa"),
                "-script", str(script)]
        db_pw = os.environ.get(DB_PASS_ENV, "")
        if db_pw:
            argv += ["-password", db_pw]
        try:
            proc = subprocess.run(argv, capture_output=True, text=True,
                                  timeout=timeout)
            return proc.returncode, proc.stdout or "", proc.stderr or ""
        except subprocess.TimeoutExpired:
            return -2, "", f"runscript-timeout>{timeout}s"
        except OSError as e:
            return -3, "", f"java-spawn-failed:{e}"
    finally:
        shutil.rmtree(work, ignore_errors=True)


def jdbc_probe(info: dict, jar: str) -> dict:
    """REAL lock probe: open the store via JDBC. Never an OS file-open guess."""
    exit_code, out, err = run_runscript(jar, info["url"], "SELECT 1 FROM DUAL;\n")
    if exit_code == 0:
        return {"probe": "jdbc-open", "locked": False, "jdbcExit": 0}
    tail = " ".join((err or out).split())[:300]
    locked = bool(LOCK_RE.search(tail))
    return {"probe": "jdbc-open", "locked": locked, "jdbcExit": exit_code,
            "detail": tail or f"runscript-exit:{exit_code}"}


def query_via_csvwrite(info: dict, jar: str, sql: str) -> tuple[dict | None, dict | None]:
    """Run `CALL CSVWRITE(...)` for a single read query. -> (result, error)."""
    work = Path(tempfile.mkdtemp(prefix="awx-dbagent-q-"))
    try:
        csv_out = (work / "out.csv").resolve().as_posix()
        script = (f"CALL CSVWRITE('{csv_out}', {sql_literal(sql)}, "
                  "'charset=UTF-8');\n")
        code, out, err = run_runscript(jar, info["url"], script)
        csv_path = work / "out.csv"
        if code != 0 or not csv_path.is_file():
            tail = " ".join((err or out).split())[:400]
            locked = bool(LOCK_RE.search(tail))
            return None, {"reason": "locked" if locked else "query-failed",
                          "locked": locked, "jdbcExit": code, "detail": tail}
        with csv_path.open(newline="", encoding="utf-8") as f:
            rows = list(csv.reader(f))
        if not rows:
            return {"columns": [], "rows": []}, None
        return {"columns": rows[0], "rows": rows[1:]}, None
    finally:
        shutil.rmtree(work, ignore_errors=True)


def lock_gate(info: dict, jar: str, cmd: str) -> dict | None:
    """-> error payload when the store cannot be opened, else None."""
    probe = jdbc_probe(info, jar)
    if probe["locked"] is False:
        return None
    err = {"cmd": cmd, "dbFile": info["mvDb"], "jdbc": probe}
    if probe["locked"]:
        err["reason"] = "locked"
        err["hint"] = ("Start-RAG JVM holds lmsdb.mv.db. Reads while live: "
                       "python scripts/meta_display_db_export.py live "
                       "status|tables|query ... ; writes: Close-RAG.bat first.")
        err["_exit"] = 3
    elif probe["jdbcExit"] < 0:
        err["reason"] = probe["detail"]
        err["_exit"] = 4 if "java" in probe["detail"] else 5
    else:
        err["reason"] = "db-open-failed"
        err["_exit"] = 5
    return err


def require_ready(args, cmd: str) -> tuple[dict | None, str | None, dict | None]:
    """resolve + file check + jar + lock probe -> (info, jar, error)."""
    info, err = resolve_db(args)
    if err:
        err.update({"cmd": cmd, "_exit": 2})
        return None, None, err
    if not Path(info["mvDb"]).is_file():
        return None, None, {"cmd": cmd, "reason": "db-file-missing",
                            "dbFile": info["mvDb"], "_exit": 2,
                            "hint": "start once with Start-RAG.bat or pass --db-path to a copy"}
    jar = find_h2_jar()
    if not jar:
        return None, None, {"cmd": cmd, "reason": "h2-jar-not-found", "_exit": 4,
                            "hint": "set META_DISPLAY_H2_JAR or restore the Gradle cache"}
    gate = lock_gate(info, jar, cmd)
    if gate:
        return info, jar, gate
    return info, jar, None


# ------------------------------------------------- live lane (HTTP fallback)

def via_mode(args) -> str:
    v = (getattr(args, "via", None) or os.environ.get(VIA_ENV, "") or "auto")
    return v.strip().lower()


def live_call(args, method: str, path: str, body: dict | None = None):
    """meta_display_db_export.live_request with an argparse-shaped shim.
    Token is NEVER an argv flag here - env/var-file only (see resolve_token)."""
    shim = SimpleNamespace(token=None,
                           base_url=getattr(args, "base_url", None),
                           timeout=getattr(args, "timeout", 15))
    return live_request(shim, method, path, body)


def live_fail(cmd: str, code, payload, token_src, jdbc=None) -> tuple[dict, int]:
    """Failed live-lane response -> db_agent error payload + exit code."""
    err = {"cmd": cmd, "via": "live-http", "httpStatus": code,
           "tokenSource": token_src or "none"}
    if jdbc is not None:
        err["jdbc"] = jdbc
    if code is None:
        err["reason"] = "live-unreachable"
        err["hint"] = ("server not answering on the base URL - never kill a "
                       "foreign runtime to unlock; use --via file after "
                       "Close-RAG or start the server first")
        return err, 3
    if code in (401, 403):
        err["reason"] = "auth-blocked"
        err["hint"] = ("admin token required - set DOMAIN_ALLOWLIST_ADMIN_TOKEN/"
                       "AWX_ADMIN_TOKEN/LLM_OWNER_TOKEN or keep "
                       "var/dev-admin-token.txt in sync")
        return err, 5
    if code == 404:
        err["reason"] = "live-endpoint-missing"
        err["hint"] = ("running server predates this endpoint - reload via "
                       "compile + owned restart, or --via file after Close-RAG")
        return err, 3
    err["reason"] = ((payload or {}).get("reason")
                     or (payload or {}).get("error") or f"http-{code}")
    err["detail"] = payload
    return err, 5


def _is_default_store(info) -> bool:
    try:
        return (info is not None
                and Path(info["dbBase"]).resolve() == DEFAULT_DB_BASE.resolve())
    except (OSError, TypeError, KeyError):
        return False


def live_wanted(args, err, info) -> bool:
    """auto live fallback applies only to a locked default store - the live
    endpoint always reads the server's own datasource, so a custom --db-path
    that is locked must NOT silently answer from lmsdb."""
    return bool(err and err.get("reason") == "locked"
                and via_mode(args) == "auto" and _is_default_store(info))


def live_guard(args, cmd: str) -> dict | None:
    """--via live against a non-default --db-path is a wrong-store answer."""
    info, err = resolve_db(args)
    if err:
        err.update({"cmd": cmd, "_exit": 2})
        return err
    if not _is_default_store(info):
        return {"cmd": cmd, "reason": "live-lane-targets-default-store",
                "dbBase": info["dbBase"], "_exit": 2,
                "hint": "the live endpoint reads the server's own datasource; "
                        "--db-path copies need --via file"}
    return None


def mask_cells(columns: list, rows: list) -> list:
    idx = [i for i, c in enumerate(columns) if MASK_COLS_RE.search(str(c))]
    if not idx:
        return rows
    out = []
    for r in rows:
        r2 = list(r)
        for i in idx:
            if i < len(r2) and isinstance(r2[i], str) and r2[i]:
                r2[i] = r2[i][:7] + "...masked"
        out.append(r2)
    return out


def live_tables(args, jdbc=None) -> int:
    code, payload, tsrc = live_call(args, "GET", f"{LIVE_API}/tables")
    if code != 200 or not (payload or {}).get("ok"):
        err, exit_code = live_fail("tables", code, payload, tsrc, jdbc)
        return emit(err, exit_code, args.pretty)
    out = {"cmd": "tables", "via": "live-http", "tokenSource": tsrc or "none",
           "tables": payload.get("tables") or [],
           "tableCount": payload.get("tableCount")}
    if jdbc is not None:
        out["jdbc"] = jdbc
    return emit(out, 0, args.pretty)


def live_schema(args, jdbc=None) -> int:
    targets = [args.table] if args.table else None
    tsrc = None
    if targets is None:
        code, payload, tsrc = live_call(args, "GET", f"{LIVE_API}/tables")
        if code != 200 or not (payload or {}).get("ok"):
            err, exit_code = live_fail("schema", code, payload, tsrc, jdbc)
            return emit(err, exit_code, args.pretty)
        targets = ["{}.{}".format(t.get("schema", "PUBLIC"), t.get("name"))
                   for t in (payload.get("tables") or [])]
    grouped = []
    for t in targets:
        code, payload, tsrc = live_call(
            args, "GET",
            f"{LIVE_API}/columns?table={urllib.parse.quote(str(t), safe='.')}")
        if code != 200 or not (payload or {}).get("ok"):
            err, exit_code = live_fail("schema", code, payload, tsrc, jdbc)
            return emit(err, exit_code, args.pretty)
        grouped.append({"schema": payload.get("schema"),
                        "table": payload.get("table"),
                        "columns": payload.get("columns") or []})
    if args.table and not (grouped and grouped[0]["columns"]):
        return emit({"cmd": "schema", "via": "live-http",
                     "table": args.table, "reason": "table-not-found"},
                    5, args.pretty)
    out = {"cmd": "schema", "via": "live-http", "tokenSource": tsrc or "none",
           "tables": grouped,
           "fieldsNote": "live lane emits name/type only (no ordinal/nullable)"}
    if jdbc is not None:
        out["jdbc"] = jdbc
    return emit(out, 0, args.pretty)


def live_get_user(args, jdbc=None):
    """-> (payload, code). Masked administrators row over the live lane."""
    uname = urllib.parse.quote(str(args.username), safe="")
    code, payload, tsrc = live_call(
        args, "GET", f"{LIVE_API}/admin?username={uname}")
    if code == 404:
        # running build predates /admin - masked SELECT through the query gate
        code, payload, tsrc = live_call(args, "POST", f"{LIVE_API}/query", {
            "sql": ("SELECT username AS \"username\", role AS \"role\", "
                    "LEFT(password, 7) AS \"hash_prefix\" FROM administrators "
                    "WHERE username = " + sql_literal(args.username)),
            "maxRows": 1})
        rows = (payload or {}).get("rows") or []
        if code == 200 and (payload or {}).get("ok"):
            payload = {"ok": True,
                       "found": bool(rows),
                       "username": (rows[0] or {}).get("username") if rows else None,
                       "role": (rows[0] or {}).get("role") if rows else None,
                       "hashPrefix": (rows[0] or {}).get("hash_prefix") if rows else None}
    if code != 200 or not (payload or {}).get("ok"):
        err, exit_code = live_fail("get-user", code, payload, tsrc, jdbc)
        return emit(err, exit_code, args.pretty)
    if payload.get("found") is False:
        return emit({"cmd": "get-user", "via": "live-http",
                     "username": args.username, "reason": "row-missing"},
                    5, args.pretty)
    out = {"cmd": "get-user", "via": "live-http",
           "tokenSource": tsrc or "none",
           "username": payload.get("username"), "role": payload.get("role"),
           "hashPrefix": payload.get("hashPrefix"),
           "name": payload.get("name"), "createdAt": payload.get("createdAt")}
    if jdbc is not None:
        out["jdbc"] = jdbc
    return emit(out, 0, args.pretty)


def live_query(args, sql: str, jdbc=None) -> int:
    max_rows = max(1, min(args.max_rows, MAX_ROWS_HARD))
    code, payload, tsrc = live_call(args, "POST", f"{LIVE_API}/query",
                                    {"sql": sql, "maxRows": max_rows})
    if code != 200 or not (payload or {}).get("ok"):
        err, exit_code = live_fail("query", code, payload, tsrc, jdbc)
        return emit(err, exit_code, args.pretty)
    cols = [str(c) for c in (payload.get("columns") or [])]
    rows = []
    for r in (payload.get("rows") or []):
        rows.append([r.get(c) for c in cols] if isinstance(r, dict) else list(r))
    rows = mask_cells(cols, rows)
    out = {"cmd": "query", "via": "live-http", "tokenSource": tsrc or "none",
           "columns": cols, "rows": rows,
           "rowCount": payload.get("rowCount", len(rows)),
           "totalMatched": payload.get("totalMatched"),
           "truncated": bool(payload.get("truncated"))}
    if jdbc is not None:
        out["jdbc"] = jdbc
    return emit(out, 0, args.pretty)


# ---------------------------------------------------------------- commands

def cmd_status(args) -> int:
    info, err = resolve_db(args)
    if err:
        err.update({"cmd": "status"})
        return emit(err, 2, args.pretty)
    mv = Path(info["mvDb"])
    jar = find_h2_jar()
    payload = {
        "cmd": "status", "dbFile": info["mvDb"],
        "exists": mv.is_file(),
        "size": mv.stat().st_size if mv.is_file() else 0,
        "mtimeUtc": (int(mv.stat().st_mtime) if mv.is_file() else None),
        "url": redact(info["url"]), "urlSource": info["urlSource"],
        "h2Jar": jar, "java": bool(shutil.which("java")),
        "latestExport": None,
    }
    if EXPORT_LATEST.is_file():
        try:
            payload["latestExport"] = json.loads(
                EXPORT_LATEST.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            pass
    if not mv.is_file():
        payload["reason"] = "db-file-missing"
        return emit(payload, 2, args.pretty)
    if not jar:
        payload["reason"] = "h2-jar-not-found"
        return emit(payload, 4, args.pretty)
    if not payload["java"]:
        payload["reason"] = "java-not-on-path"
        return emit(payload, 4, args.pretty)
    probe = jdbc_probe(info, jar)
    payload["jdbc"] = probe
    payload["locked"] = probe["locked"]
    if probe["locked"]:
        payload["reason"] = "locked"
        if via_mode(args) in ("auto", "live") and _is_default_store(info):
            code, lp, tsrc = live_call(args, "GET", f"{LIVE_API}/tables")
            payload["live"] = {"reachable": code is not None,
                               "httpStatus": code,
                               "ok": bool((lp or {}).get("ok")),
                               "tokenSource": tsrc or "none"}
            if code == 200 and isinstance(lp, dict):
                payload["live"]["tableCount"] = lp.get("tableCount")
            elif code in (401, 403):
                payload["live"]["hint"] = ("auth-blocked - set "
                                           "DOMAIN_ALLOWLIST_ADMIN_TOKEN/"
                                           "LLM_OWNER_TOKEN or "
                                           "var/dev-admin-token.txt")
        payload["hint"] = ("server holds the file - reads auto-fallback to "
                           "the live HTTP lane (or --via live); writes need "
                           "Close-RAG.bat first. Never kill a foreign runtime.")
        return emit(payload, 3, args.pretty)
    if probe["jdbcExit"] != 0:
        payload["reason"] = "db-open-failed"
        return emit(payload, 5, args.pretty)
    return emit(payload, 0, args.pretty)


def cmd_tables(args) -> int:
    if via_mode(args) == "live":
        guard = live_guard(args, "tables")
        if guard:
            return emit(guard, guard.pop("_exit"), args.pretty)
        return live_tables(args)
    info, jar, err = require_ready(args, "tables")
    if err:
        if live_wanted(args, err, info):
            return live_tables(args, jdbc=err.get("jdbc"))
        return emit(err, err.pop("_exit"), args.pretty)
    res, qerr = query_via_csvwrite(
        info, jar,
        "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES "
        "WHERE TABLE_SCHEMA <> 'INFORMATION_SCHEMA' ORDER BY TABLE_SCHEMA, TABLE_NAME")
    if qerr:
        qerr["cmd"] = "tables"
        return emit(qerr, 3 if qerr.get("locked") else 5, args.pretty)
    tables = [{"schema": r[0], "name": r[1], "type": r[2] if len(r) > 2 else ""}
              for r in res["rows"]]
    if args.with_counts:
        bases = [t for t in tables if t["type"].upper() in ("BASE TABLE", "TABLE")]
        if bases:
            union = " UNION ALL ".join(
                f"SELECT {sql_literal(t['name'])} AS T, COUNT(*) FROM "
                f"{quote_ident(t['schema'])}.{quote_ident(t['name'])}"
                for t in bases)
            cres, cerr = query_via_csvwrite(info, jar, union)
            if cerr:
                for t in tables:
                    t["rowCount"] = None
            else:
                counts = {r[0]: int(r[1]) for r in cres["rows"] if len(r) >= 2}
                for t in tables:
                    t["rowCount"] = counts.get(t["name"])
    return emit({"cmd": "tables", "dbFile": info["mvDb"], "tables": tables},
                0, args.pretty)


def cmd_schema(args) -> int:
    if via_mode(args) == "live":
        guard = live_guard(args, "schema")
        if guard:
            return emit(guard, guard.pop("_exit"), args.pretty)
        return live_schema(args)
    info, jar, err = require_ready(args, "schema")
    if err:
        if live_wanted(args, err, info):
            return live_schema(args, jdbc=err.get("jdbc"))
        return emit(err, err.pop("_exit"), args.pretty)
    where = ("WHERE TABLE_SCHEMA <> 'INFORMATION_SCHEMA'")
    if args.table:
        where += f" AND UPPER(TABLE_NAME) = UPPER({sql_literal(args.table)})"
    res, qerr = query_via_csvwrite(
        info, jar,
        "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, DATA_TYPE, "
        "CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
        f"{where} ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION")
    if qerr:
        qerr["cmd"] = "schema"
        return emit(qerr, 3 if qerr.get("locked") else 5, args.pretty)
    grouped: dict[str, dict] = {}
    for r in res["rows"]:
        if len(r) < 7:
            continue
        key = f"{r[0]}.{r[1]}"
        g = grouped.setdefault(key, {"schema": r[0], "table": r[1], "columns": []})
        g["columns"].append({
            "name": r[2], "ordinal": int(r[3]) if r[3].isdigit() else r[3],
            "type": r[4] + (f"({r[5]})" if r[5] else ""),
            "nullable": r[6].upper() == "YES"})
    if args.table and not grouped:
        return emit({"cmd": "schema", "table": args.table,
                     "reason": "table-not-found"}, 5, args.pretty)
    return emit({"cmd": "schema", "dbFile": info["mvDb"],
                 "tables": list(grouped.values())}, 0, args.pretty)


def cmd_get_user(args) -> int:
    if via_mode(args) == "live":
        guard = live_guard(args, "get-user")
        if guard:
            return emit(guard, guard.pop("_exit"), args.pretty)
        return live_get_user(args)
    info, jar, err = require_ready(args, "get-user")
    if err:
        if live_wanted(args, err, info):
            return live_get_user(args, jdbc=err.get("jdbc"))
        return emit(err, err.pop("_exit"), args.pretty)
    sql = ("SELECT username AS \"username\", role AS \"role\", "
           "LEFT(password, 7) AS \"hash_prefix\", name AS \"name\", "
           "created_at AS \"created_at\" FROM administrators WHERE username = "
           + sql_literal(args.username))
    res, qerr = query_via_csvwrite(info, jar, sql)
    if qerr or not res["rows"]:
        if qerr:
            qerr["cmd"] = "get-user"
            return emit(qerr, 3 if qerr.get("locked") else 5, args.pretty)
        # created_at name may differ on old schemas - retry minimal columns once.
        sql = ("SELECT username AS \"username\", role AS \"role\", "
               "LEFT(password, 7) AS \"hash_prefix\" FROM administrators "
               "WHERE username = " + sql_literal(args.username))
        res, qerr = query_via_csvwrite(info, jar, sql)
        if qerr:
            qerr["cmd"] = "get-user"
            return emit(qerr, 3 if qerr.get("locked") else 5, args.pretty)
    if not res["rows"]:
        return emit({"cmd": "get-user", "username": args.username,
                     "reason": "row-missing"}, 5, args.pretty)
    row = dict(zip(res["columns"], res["rows"][0]))
    return emit({"cmd": "get-user", "username": row.get("username"),
                 "role": row.get("role"), "hashPrefix": row.get("hash_prefix"),
                 "name": row.get("name"), "createdAt": row.get("created_at")},
                0, args.pretty)


def cmd_verify_admin(args) -> int:
    """Read-only admin check mirroring db-agent.ps1 -Action VerifyAdmin:
    exit 0 = row present AND hashPrefix is bcrypt ($2a/$2b/$2y);
    exit 5 = row missing or non-bcrypt; 3 = still locked (no live lane)."""
    if via_mode(args) == "live":
        guard = live_guard(args, "verify-admin")
        if guard:
            return emit(guard, guard.pop("_exit"), args.pretty)
        return _verify_admin_emit(args, live_get_user_capture(args), "live-http")
    info, jar, err = require_ready(args, "verify-admin")
    if err:
        if live_wanted(args, err, info):
            return _verify_admin_emit(args, live_get_user_capture(
                args, jdbc=err.get("jdbc")), "live-http")
        return emit(err, err.pop("_exit"), args.pretty)
    sql = ("SELECT LEFT(password, 7) AS \"hash_prefix\" FROM administrators "
           "WHERE username = " + sql_literal(args.username))
    res, qerr = query_via_csvwrite(info, jar, sql)
    if qerr:
        qerr["cmd"] = "verify-admin"
        return emit(qerr, 3 if qerr.get("locked") else 5, args.pretty)
    prefix = res["rows"][0][0] if res["rows"] else None
    return _verify_admin_emit(args, ({"username": args.username,
                                      "hashPrefix": prefix}, None), "file")


def _verify_admin_emit(args, captured, lane: str) -> int:
    row, err = captured
    if err is not None:
        return emit(err, err.get("_exit", 5), args.pretty)
    prefix = row.get("hashPrefix") or ""
    out = {"cmd": "verify-admin", "via": lane, "username": args.username,
           "present": bool(prefix), "bcrypt": bool(re.match(r"^\$2[aby]\$", prefix)),
           "hashPrefix": prefix or None}
    if row.get("role"):
        out["role"] = row["role"]
    return emit(out, 0 if out["present"] and out["bcrypt"] else 5, args.pretty)


def live_get_user_capture(args, jdbc=None) -> tuple[dict | None, dict | None]:
    """live_get_user payload without printing: -> (row, err)."""
    uname = urllib.parse.quote(str(args.username), safe="")
    code, payload, tsrc = live_call(
        args, "GET", f"{LIVE_API}/admin?username={uname}")
    if code == 404:
        code, payload, tsrc = live_call(args, "POST", f"{LIVE_API}/query", {
            "sql": ("SELECT username AS \"username\", role AS \"role\", "
                    "LEFT(password, 7) AS \"hash_prefix\" FROM administrators "
                    "WHERE username = " + sql_literal(args.username)),
            "maxRows": 1})
        rows = (payload or {}).get("rows") or []
        if code == 200 and (payload or {}).get("ok"):
            payload = {"ok": True, "found": bool(rows),
                       "username": (rows[0] or {}).get("username") if rows else None,
                       "role": (rows[0] or {}).get("role") if rows else None,
                       "hashPrefix": (rows[0] or {}).get("hash_prefix") if rows else None}
    if code != 200 or not (payload or {}).get("ok"):
        err, exit_code = live_fail("verify-admin", code, payload, tsrc, jdbc)
        err["_exit"] = exit_code
        return None, err
    if payload.get("found") is False:
        return {"username": args.username, "hashPrefix": None,
                "role": payload.get("role")}, None
    return {"username": payload.get("username"), "role": payload.get("role"),
            "hashPrefix": payload.get("hashPrefix")}, None


def cmd_query(args) -> int:
    if args.file:
        try:
            sql = Path(args.file).read_text(encoding="utf-8-sig")
        except OSError as e:
            return emit({"cmd": "query", "reason": f"sql-file-unreadable:{e}"},
                        2, args.pretty)
    else:
        sql = args.sql or ""
    reason = check_sql(sql)
    if reason:
        return emit({"cmd": "query", "reason": "sql-rejected",
                     "gate": reason}, 2, args.pretty)
    if via_mode(args) == "live":
        guard = live_guard(args, "query")
        if guard:
            return emit(guard, guard.pop("_exit"), args.pretty)
        return live_query(args, sql)
    info, jar, err = require_ready(args, "query")
    if err:
        if live_wanted(args, err, info):
            return live_query(args, sql, jdbc=err.get("jdbc"))
        return emit(err, err.pop("_exit"), args.pretty)
    max_rows = max(1, min(args.max_rows, MAX_ROWS_HARD))
    body = sql.strip().rstrip(";").strip()
    first = re.match(r"([a-zA-Z]+)", body)
    if first and first.group(1).lower() in ("select", "with"):
        body = f"SELECT * FROM ({body}) LIMIT {max_rows + 1}"
    res, qerr = query_via_csvwrite(info, jar, body)
    if qerr:
        qerr["cmd"] = "query"
        return emit(qerr, 3 if qerr.get("locked") else 5, args.pretty)
    rows = mask_cells(res["columns"], res["rows"])
    truncated = len(rows) > max_rows
    return emit({"cmd": "query", "columns": res["columns"],
                 "rows": rows[:max_rows], "rowCount": min(len(rows), max_rows),
                 "truncated": truncated}, 0, args.pretty)


# ---------------------------------------------------------------- write lane

def live_upsert_admin(args, jdbc=None) -> int:
    """Guarded live upsert via POST /api/internal/db/meta/admin/upsert.
    Password travels only inside the request body to the loopback server -
    never argv, never logged."""
    pw = os.environ.get(ADMIN_PASSWORD_ENV, "")
    body = {"username": args.username, "password": pw, "role": args.role}
    if args.name:
        body["name"] = args.name
    code, payload, tsrc = live_call(args, "POST", f"{LIVE_API}/admin/upsert", body)
    if code != 200 or not (payload or {}).get("ok"):
        err, exit_code = live_fail("upsert-admin", code, payload, tsrc, jdbc)
        if exit_code == 3 and err.get("reason") == "live-endpoint-missing":
            err["hint"] = ("running server predates /admin/upsert - reload it, "
                           "or Close-RAG + `--via file` (offline JDBC MERGE)")
        return emit(err, exit_code, args.pretty)
    out = {"cmd": "upsert-admin", "via": "live-http",
           "tokenSource": tsrc or "none",
           "applied": bool(payload.get("applied")),
           "username": payload.get("username"), "role": payload.get("role"),
           "hashPrefix": payload.get("hashPrefix")}
    if jdbc is not None:
        out["jdbc"] = jdbc
    return emit(out, 0, args.pretty)


def cmd_upsert_admin(args) -> int:
    via = via_mode(args)
    if args.verify_only:
        # read-back only: reuse the verify-admin path (any lane)
        return cmd_verify_admin(args)
    if args.dry_run:
        return emit({"cmd": "upsert-admin", "dryRun": True, "via": via,
                     "username": args.username, "role": args.role,
                     "passwordEnvSet": bool(os.environ.get(ADMIN_PASSWORD_ENV)),
                     "note": "no mutation; file lane needs an unlocked store, "
                             "live-http lane needs the admin/upsert endpoint"},
                    0, args.pretty)
    if not os.environ.get(ADMIN_PASSWORD_ENV):
        return emit({"cmd": "upsert-admin", "reason": "password-env-missing",
                     "hint": f"set env {ADMIN_PASSWORD_ENV} (never argv, never logged)"},
                    2, args.pretty)
    if via == "live-http":
        guard = live_guard(args, "upsert-admin")
        if guard:
            return emit(guard, guard.pop("_exit"), args.pretty)
        return live_upsert_admin(args)
    if not UPSERT_PS1.is_file():
        return emit({"cmd": "upsert-admin", "reason": "create-local-admin.ps1 missing"},
                    4, args.pretty)
    # Lock probe stays in-process: the ps1 aborts on java stderr under
    # ErrorActionPreference=Stop before it can print its JSON when locked.
    info, jar, err = require_ready(args, "upsert-admin")
    if err:
        if err.get("reason") == "locked" and via == "auto" and _is_default_store(info):
            # babysit-free write lane: guarded live HTTP upsert (QF-03).
            return live_upsert_admin(args, jdbc=err.get("jdbc"))
        if err.get("reason") == "locked":
            err["hint"] = ("store locked by the running server - either "
                           "`--via live-http` (guarded endpoint) or Close-RAG "
                           "then retry. Never kill a foreign runtime.")
        return emit(err, err.pop("_exit"), args.pretty)
    argv = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", str(UPSERT_PS1), "-Username", args.username,
            "-Role", args.role, "-DbPath", info["dbBase"]]
    if args.name:
        argv += ["-Name", args.name]
    if args.verify_only:
        argv += ["-VerifyOnly"]
    try:
        proc = subprocess.run(argv, capture_output=True, text=True, timeout=600)
    except (OSError, subprocess.TimeoutExpired) as e:
        return emit({"cmd": "upsert-admin", "reason": f"ps1-spawn-failed:{e}"},
                    4, args.pretty)
    out = proc.stdout or ""
    payload = None
    for line in reversed([l for l in out.splitlines() if l.strip()]):
        try:
            payload = json.loads(line)
            break
        except json.JSONDecodeError:
            continue
    ps1_exit = proc.returncode
    mapped = 6 - 1 if ps1_exit == 6 else ps1_exit  # ps1 6 verify -> tool 5
    if payload is None:
        payload = {"ok": False, "reason": "ps1-output-unparsed",
                   "stderrTail": " ".join((proc.stderr or "").split())[:300]}
        mapped = 5
    payload.update({"cmd": "upsert-admin", "via": "create-local-admin.ps1",
                    "ps1Exit": ps1_exit})
    return emit(payload, mapped, args.pretty)


def mask_sql(sql: str) -> str:
    """Length-preserving mask: '...' literals and comments become spaces so
    positions still map to the original text. Double-quoted identifiers are
    KEPT - they are object names, not values."""
    out = list(sql)
    i, n = 0, len(sql)
    while i < n:
        c = sql[i]
        if c == "'":
            j = i + 1
            while j < n:
                if sql[j] == "'":
                    if j + 1 < n and sql[j + 1] == "'":
                        j += 2
                        continue
                    break
                j += 1
            end = min(j + 1, n)
            for k in range(i, end):
                out[k] = " "
            i = end
        elif sql.startswith("--", i):
            j = sql.find("\n", i)
            end = n if j < 0 else j
            for k in range(i, end):
                out[k] = " "
            i = end
        elif sql.startswith("/*", i):
            j = sql.find("*/", i + 2)
            end = n if j < 0 else j + 2
            for k in range(i, min(end, n)):
                out[k] = " "
            i = end
        else:
            i += 1
    return "".join(out)


def split_statements(sql: str) -> list[str]:
    """Split on real `;` only - literals/comments are masked in-place."""
    masked = mask_sql(sql)
    out, start = [], 0
    for i, ch in enumerate(masked):
        if ch == ";":
            part = sql[start:i]
            start = i + 1
            if mask_sql(part).strip():
                out.append(part)
    tail = sql[start:]
    if mask_sql(tail).strip():
        out.append(tail)
    return out


def classify_statement(stmt: str, allowlist: set[str]) -> dict:
    scrubbed = mask_sql(stmt).lstrip("﻿").strip()
    m = re.match(r"([a-zA-Z]+)", scrubbed)
    first = m.group(1).lower() if m else ""
    denied = APPLY_DENY_TOKENS.search(scrubbed)
    if denied:
        return {"verdict": "deny", "reason": f"denied-token({denied.group(1).lower()})",
                "keyword": first}
    if first not in APPLY_ALLOW_FIRST:
        return {"verdict": "deny", "reason": f"unsupported-statement({first or 'empty'})",
                "keyword": first}
    if first == "create" and not re.match(r"create\s+table\b", scrubbed, re.IGNORECASE):
        return {"verdict": "deny", "reason": "unsupported-statement(create-*)",
                "keyword": first}
    if first in ("insert", "update", "delete", "merge", "create"):
        target = None
        for rx in TARGET_RES:
            t = rx.search(scrubbed)
            if t:
                target = t.group(1)
                break
        if not target:
            return {"verdict": "deny", "reason": "target-parse-failed",
                    "keyword": first}
        short = target.strip('"`').split(".")[-1].casefold()
        if short not in allowlist:
            return {"verdict": "deny",
                    "reason": f"table-not-allowlisted({short})",
                    "keyword": first, "targetTable": target}
        return {"verdict": "allow", "keyword": first, "targetTable": target}
    return {"verdict": "allow", "keyword": first}  # select/with/values/explain


def cmd_apply(args) -> int:
    sql_file = Path(args.file)
    if not sql_file.is_file():
        return emit({"cmd": "apply", "reason": f"sql-file-missing:{args.file}"},
                    2, args.pretty)
    sql_bytes = sql_file.read_bytes()
    sql_text = sql_bytes.decode("utf-8-sig")
    allowlist = set(DEFAULT_WRITE_ALLOWLIST)
    for t in (args.allow_tables or ""):
        for name in t.split(","):
            if name.strip():
                allowlist.add(name.strip().casefold())
    approved_hash = getattr(args, "approved_migration_sha256", None)
    if approved_hash:
        relative = sql_file.absolute().relative_to(ROOT).as_posix() if sql_file.absolute().is_relative_to(ROOT) else ""
        expected = F01_LOCAL_MIGRATIONS.get(relative)
        canonical = ROOT / relative
        if (not expected or approved_hash != expected
                or hashlib.sha256(sql_bytes).hexdigest() != expected
                or sql_file.resolve() != canonical.absolute()):
            return emit({"cmd": "apply", "applied": False,
                         "reason": "approved-migration-identity-mismatch"}, 5, args.pretty)
        # Require --db-path so LMS_DB_URL (including remote/INIT URL options)
        # cannot redirect an authorized local migration. No live HTTP write lane.
        info, error = resolve_db(args)
        if (not getattr(args, "db_path", None) or error
                or Path(info["dbBase"]).resolve() != DEFAULT_DB_BASE.absolute()
                or DEFAULT_DB_BASE.parent.resolve() != DEFAULT_DB_BASE.parent.absolute()
                or Path(str(DEFAULT_DB_BASE) + ".mv.db").resolve() != Path(str(DEFAULT_DB_BASE) + ".mv.db").absolute()):
            return emit({"cmd": "apply", "applied": False,
                         "reason": "approved-migration-local-store-required"}, 5, args.pretty)
        required_tables = {"awx_jobs", "awx_job_results"}
        if sql_file.name == "f01b-understanding-receipt-proposal.sql":
            required_tables.add("awx_understanding_receipts")
        if not required_tables.issubset(allowlist):
            return emit({"cmd": "apply", "applied": False,
                         "reason": "approved-migration-table-allowlist-required"}, 5, args.pretty)
    statements = split_statements(sql_text)
    plan = []
    all_allowed = True
    for i, stmt in enumerate(statements):
        verdict = classify_statement(stmt, allowlist)
        if approved_hash and verdict["verdict"] == "deny":
            # Exact approved bytes above bind additive columns and indexes.
            # Generic apply continues to reject ALTER and CREATE INDEX.
            verdict = {"verdict": "allow", "keyword": verdict["keyword"],
                       "targetTable": "awx_jobs", "approval": "sha256-bound-local-migration"}
        verdict["index"] = i
        plan.append(verdict)
        if verdict["verdict"] != "allow":
            all_allowed = False
    base = {"cmd": "apply", "file": str(sql_file), "statementCount": len(plan),
            "statements": plan, "writeAllowlist": sorted(allowlist)}
    if approved_hash:
        base["approvedMigrationSha256"] = approved_hash
        base["dbFile"] = str(DEFAULT_DB_BASE) + ".mv.db"
    if args.dry_run or not args.i_mean_it:
        base["dryRun"] = True
        base["wouldApply"] = all_allowed
        if not all_allowed:
            base["reason"] = "denied-statements-present"
        return emit(base, 0 if all_allowed else 5, args.pretty)
    if not all_allowed:
        base["reason"] = "denied-statements-present"
        base["applied"] = False
        return emit(base, 5, args.pretty)
    if not plan:
        return emit({**base, "reason": "empty-script", "applied": False},
                    2, args.pretty)
    info, jar, err = require_ready(args, "apply")
    if err:
        err.update(base)
        return emit(err, err.pop("_exit"), args.pretty)
    code, out, serr = run_runscript(jar, info["url"], sql_text)
    if code != 0:
        tail = " ".join((serr or out).split())[:400]
        locked = bool(LOCK_RE.search(tail))
        return emit({**base, "applied": False, "jdbcExit": code,
                     "reason": "locked" if locked else "runscript-failed",
                     "detail": tail}, 3 if locked else 5, args.pretty)
    return emit({**base, "applied": True, "jdbcExit": 0}, 0, args.pretty)


# ---------------------------------------------------------------- main

def main() -> int:
    p = argparse.ArgumentParser(
        description="Agent CLI over the Start-RAG file H2 (SELECT default; "
                    "writes need explicit flags). One-line JSON on stdout. "
                    "Flags go AFTER the subcommand: db_agent.py status --pretty",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--pretty", action="store_true",
                        help="indented JSON instead of one-line")
    common.add_argument("--db-path", default=None,
                        help="file H2 base (no .mv.db) or .mv.db path; overrides LMS_DB_URL")
    common.add_argument("--base-url", default=None,
                        help="live lane base (default META_DISPLAY_BASE_URL or "
                             "http://127.0.0.1:18180)")
    common.add_argument("--timeout", type=int, default=15,
                        help="live lane HTTP timeout seconds")
    sub = p.add_subparsers(dest="cmd", required=True)

    read_via = {"choices": ("auto", "file", "live"), "default": None,
                "help": "auto=file->live-fallback on lock (env AWX_DB_VIA), "
                        "file=JDBC only, live=HTTP only"}

    st = sub.add_parser("status", parents=[common],
                        help="url/file/lock probe (real JDBC open)")
    st.add_argument("--via", **read_via)
    t = sub.add_parser("tables", parents=[common],
                       help="list tables; --with-counts adds COUNT(*)")
    t.add_argument("--with-counts", action="store_true")
    t.add_argument("--via", **read_via)
    s = sub.add_parser("schema", parents=[common],
                       help="columns for --table or all tables")
    s.add_argument("--table", default=None)
    s.add_argument("--via", **read_via)
    g = sub.add_parser("get-user", parents=[common],
                       help="administrators row; hash prefix only")
    g.add_argument("--username", required=True)
    g.add_argument("--via", **read_via)
    v = sub.add_parser("verify-admin", parents=[common],
                       help="row present + bcrypt hash prefix (exit 5 on miss)")
    v.add_argument("--username", required=True)
    v.add_argument("--via", **read_via)
    q = sub.add_parser("query", parents=[common],
                       help="read-only SELECT (gate: meta_display_db_export.check_sql)")
    q.add_argument("--sql", default=None)
    q.add_argument("--file", default=None, help="read SQL from a file")
    q.add_argument("--max-rows", type=int, default=MAX_ROWS_DEFAULT)
    q.add_argument("--via", **read_via)
    a = sub.add_parser("apply", parents=[common],
                       help="apply a .sql file; --dry-run or --i-mean-it required")
    a.add_argument("--file", required=True)
    g2 = a.add_mutually_exclusive_group(required=True)
    g2.add_argument("--dry-run", action="store_true")
    g2.add_argument("--i-mean-it", action="store_true")
    a.add_argument("--allow-tables", action="append",
                   help="extend the write allowlist beyond administrators (csv)")
    a.add_argument("--approved-migration-sha256",
                   help="explicitly approved, pinned F01 migration only; requires canonical --db-path")
    u = sub.add_parser("upsert-admin", parents=[common],
                       help="admin MERGE; file lane wraps create-local-admin.ps1, "
                            "live-http lane posts the guarded endpoint; "
                            "password via env only")
    u.add_argument("--username", default="admin")
    u.add_argument("--role", default="ROLE_ADMIN")
    u.add_argument("--name", default=None)
    u.add_argument("--via", choices=("auto", "file", "live-http"), default=None,
                   help="auto=file->live-http on lock, file=offline JDBC, "
                        "live-http=guarded endpoint (env AWX_DB_VIA)")
    ux = u.add_mutually_exclusive_group()
    ux.add_argument("--verify-only", action="store_true",
                    help="read-back only (runs verify-admin on the same lane)")
    ux.add_argument("--dry-run", action="store_true",
                    help="report the plan + env readiness, no mutation")

    args = p.parse_args()
    handlers = {
        "status": cmd_status, "tables": cmd_tables, "schema": cmd_schema,
        "get-user": cmd_get_user, "verify-admin": cmd_verify_admin,
        "query": cmd_query,
        "apply": cmd_apply, "upsert-admin": cmd_upsert_admin,
    }
    return handlers[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
