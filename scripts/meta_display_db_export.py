#!/usr/bin/env python3
"""Read-only Meta Display H2 export + free SQL query for multi-agent handoff.

Lanes:
  status      live file lock/exports/live-endpoint probe (no mutate)
  snapshot    byte-copy lmsdb.mv.db -> export/<runId>/ (only while JVM unlocked)
  export      snapshot (or --from <mv.db|dir>) + convert to export.sqlite, csv/,
              schema.sql, manifest.json; updates export/latest.json
  query       arbitrary read-only SELECT against an exported sqlite bundle
  tables      list tables + row counts of an exported bundle
  schema      dump declared column types (all or one table)
  tail        last N rows of a table (insertion order via rowid)
  live        read-only HTTP lane -> /api/internal/db/meta/* on the running
              Meta Display server (token via env or var/dev-admin-token.txt)

Never mutates lmsdb.mv.db. Never live-JDBC the H2 file while the JVM holds it.
"""
from __future__ import annotations

import argparse
import csv
import glob
import hashlib
import io
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB_DIR = ROOT / "var" / "meta-display-db"
DB_FILE = DB_DIR / "lmsdb.mv.db"
TRACE_FILE = DB_DIR / "lmsdb.trace.db"
EXPORT_ROOT = DB_DIR / "export"
LATEST_FILE = EXPORT_ROOT / "latest.json"
DEV_TOKEN_FILE = ROOT / "var" / "dev-admin-token.txt"

H2_DB_OPTS = "MODE=MariaDB;DATABASE_TO_UPPER=false;ACCESS_MODE_DATA=r;IFEXISTS=TRUE"
DEFAULT_BASE_URL = "http://127.0.0.1:18180"
LIVE_API = "/api/internal/db/meta"

ALLOW_FIRST = ("select", "with", "explain", "values", "show", "pragma")
DENY_TOKENS = re.compile(
    r"\b(insert|update|delete|merge|create|alter|drop|truncate|grant|revoke|set|"
    r"script|shutdown|checkpoint|backup|call|prepare|execute|commit|rollback|"
    r"savepoint|lock|comment|analyze|runscript|rename|vacuum|attach|detach|"
    r"replace|upsert|grant)\b",
    re.IGNORECASE,
)
SQL_CHAR_CAP = 8000
DEFAULT_MAX_ROWS = 500
HARD_MAX_ROWS = 5000
CELL_CHAR_CAP = 64000

INT_TYPES = {"INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "MEDIUMINT", "SERIAL"}
REAL_TYPES = {"REAL", "FLOAT", "DOUBLE", "DOUBLE PRECISION", "DECIMAL", "NUMERIC", "DECFLOAT"}
BOOL_TYPES = {"BOOLEAN", "BIT", "BOOL"}


# ---------------------------------------------------------------- helpers

def sha256(path: Path, limit: int = 32 * 1024 * 1024):
    if not path.is_file():
        return None
    h = hashlib.sha256()
    n = 0
    try:
        with path.open("rb") as f:
            while True:
                chunk = f.read(1024 * 1024)
                if not chunk:
                    break
                n += len(chunk)
                h.update(chunk)
                if n >= limit:
                    return h.hexdigest() + f"+partial:{n}"
    except OSError:
        return None
    return h.hexdigest()


def probe_lock(path: Path) -> dict:
    out = {"path": str(path), "exists": path.is_file(), "locked": None, "error": None}
    if not path.is_file():
        return out
    try:
        fd = os.open(str(path), os.O_RDWR | getattr(os, "O_BINARY", 0))
        os.close(fd)
        out["locked"] = False
    except OSError as e:
        out["locked"] = True
        out["error"] = str(e)
    return out


def emit(payload, code: int = 0) -> int:
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return code


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def find_h2_jar():
    env = os.environ.get("META_DISPLAY_H2_JAR", "").strip()
    candidates = []
    if env:
        candidates.append(env)
    home = Path.home()
    for base in (
        home / ".gradle" / "caches" / "modules-2" / "files-2.1" / "com.h2database" / "h2",
        home / ".awx-gradle-user-home" / "caches" / "modules-2" / "files-2.1" / "com.h2database" / "h2",
    ):
        if base.is_dir():
            candidates += glob.glob(str(base / "*" / "*" / "h2-*.jar"))
    candidates += glob.glob(str(ROOT / "tools" / "h2-*.jar"))

    def version_key(p):
        m = re.search(r"h2-(\d+)\.(\d+)\.(\d+)", os.path.basename(p))
        return tuple(int(x) for x in m.groups()) if m else (0, 0, 0)

    for c in sorted(set(candidates), key=version_key, reverse=True):
        if os.path.isfile(c):
            return c
    return None


def quote_ident(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


def sqlite_name(schema: str, table: str) -> str:
    return table if schema.upper() == "PUBLIC" else f"{schema}__{table}"


def scrub_literals(sql: str) -> str:
    """Blank string literals, quoted identifiers and comments for gate checks."""
    out = []
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
            out.append(" ")
            i = min(j + 1, n)
        elif c == '"':
            j = i + 1
            while j < n:
                if sql[j] == '"':
                    if j + 1 < n and sql[j + 1] == '"':
                        j += 2
                        continue
                    break
                j += 1
            out.append(" ")
            i = min(j + 1, n)
        elif sql.startswith("--", i):
            j = sql.find("\n", i)
            i = n if j < 0 else j
            out.append(" ")
        elif sql.startswith("/*", i):
            j = sql.find("*/", i + 2)
            i = n if j < 0 else j + 2
            out.append(" ")
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_sql(sql: str):
    """Return None when the statement is an allowed single read-only query."""
    if not isinstance(sql, str) or not sql.strip():
        return "empty-sql"
    if len(sql) > SQL_CHAR_CAP:
        return f"sql-too-long>{SQL_CHAR_CAP}"
    scrubbed = scrub_literals(sql).strip()
    if not scrubbed:
        return "empty-sql"
    body = scrubbed[:-1].strip() if scrubbed.endswith(";") else scrubbed
    if ";" in body:
        return "multi-statement-rejected"
    first = re.match(r"([a-zA-Z]+)", body)
    if not first or first.group(1).lower() not in ALLOW_FIRST:
        return "first-keyword-not-read-only"
    denied = DENY_TOKENS.search(body)
    if denied:
        return f"denied-token({denied.group(1).lower()})"
    return None


def resolve_db_dir(arg: str | None) -> Path | None:
    """Resolve an export bundle dir: runId | 'latest' (default) | explicit path."""
    if not arg or arg == "latest":
        info = read_json(LATEST_FILE)
        if info and info.get("dir"):
            p = Path(info["dir"])
            return p if p.is_dir() else None
        return None
    p = Path(arg)
    if p.is_dir():
        return p
    p2 = EXPORT_ROOT / arg
    return p2 if p2.is_dir() else None


def open_sqlite(db_dir: Path):
    db = db_dir / "export.sqlite"
    if not db.is_file():
        return None, f"no export.sqlite under {db_dir} (run export or live export first)"
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    con.execute("PRAGMA query_only=ON")
    return con, None


def fetch_rows(con, sql: str, max_rows: int):
    cur = con.execute(sql)
    cols = [d[0] for d in (cur.description or [])]
    rows = cur.fetchmany(max_rows + 1)
    truncated = len(rows) > max_rows
    return cols, [list(r) for r in rows[:max_rows]], truncated


# ---------------------------------------------------------------- status / snapshot

def status(_: argparse.Namespace) -> int:
    lock = probe_lock(DB_FILE)
    digest = None
    if lock.get("exists") and lock.get("locked") is not True:
        digest = sha256(DB_FILE)
        if digest is None and lock.get("locked") is False:
            lock["locked"] = True
            lock["error"] = lock.get("error") or "read-denied-after-open"
    exports = sorted(
        [p.name for p in EXPORT_ROOT.iterdir() if p.is_dir()] if EXPORT_ROOT.is_dir() else []
    )
    payload = {
        "root": str(ROOT),
        "dbDir": str(DB_DIR),
        "dbFile": {
            **lock,
            "size": DB_FILE.stat().st_size if DB_FILE.is_file() else 0,
            "mtime": DB_FILE.stat().st_mtime if DB_FILE.is_file() else None,
            "sha256": digest,
        },
        "traceFile": {
            "exists": TRACE_FILE.is_file(),
            "size": TRACE_FILE.stat().st_size if TRACE_FILE.is_file() else 0,
        },
        "exportRoot": str(EXPORT_ROOT),
        "exports": exports,
        "latest": read_json(LATEST_FILE),
        "h2Jar": find_h2_jar(),
        "policy": "read-only-snapshot; no live JDBC while JVM holds lock",
    }
    return emit(payload, 0 if lock.get("exists") else 2)


def copy_source_db(dest: Path):
    """Copy the live db file into dest when it is not locked. Returns (ok, lock)."""
    lock = probe_lock(DB_FILE)
    if not DB_FILE.is_file():
        return False, lock
    if lock.get("locked"):
        return False, lock
    shutil.copy2(DB_FILE, dest / DB_FILE.name)
    if TRACE_FILE.is_file():
        try:
            shutil.copy2(TRACE_FILE, dest / TRACE_FILE.name)
        except OSError:
            pass
    return True, lock


def snapshot(_: argparse.Namespace) -> int:
    EXPORT_ROOT.mkdir(parents=True, exist_ok=True)
    run_id = time.strftime("%Y%m%d-%H%M%S")
    dest = EXPORT_ROOT / run_id
    dest.mkdir(parents=True, exist_ok=False)
    ok, lock = copy_source_db(dest)
    manifest = {
        "runId": run_id,
        "mode": "snapshot",
        "createdAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "sourceDb": str(DB_FILE),
        "lock": lock,
        "copied": [
            {"name": p.name, "size": p.stat().st_size, "sha256": sha256(p)}
            for p in dest.iterdir()
            if p.is_file()
        ],
        "ok": ok,
    }
    if not DB_FILE.is_file():
        note = "DB file missing. Start once with `-MetaDisplay` so the file store is created."
        code = 2
    elif not ok:
        note = ("File locked by another process (usually the Spring JVM). "
                "Stop/Close the Meta Display server, re-run `snapshot`, "
                "or use `live export` against the running endpoint.")
        code = 3
    else:
        note = "Snapshot copy completed. Run `export --from <runId>` to build a queryable bundle."
        code = 0
    (dest / "NOTE.md").write_text(
        "# Meta Display DB export\n\n"
        f"- runId: `{run_id}`\n- source: `{DB_FILE}`\n- locked: `{lock.get('locked')}`\n\n"
        "Agents (Grok / Devin / Codex / Cline): read this folder only.\n"
        "Do not open the live H2 URL while Start-RAG is running.\n\n" + note + "\n",
        encoding="utf-8",
    )
    (dest / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    if ok:
        LATEST_FILE.write_text(
            json.dumps({"runId": run_id, "dir": str(dest), "mode": "snapshot",
                        "sqlite": None, "createdAt": manifest["createdAt"]},
                       ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    return emit(manifest, code)


# ---------------------------------------------------------------- export (convert)

def run_h2_script(jar: str, url: str, script: Path) -> tuple[bool, str]:
    try:
        proc = subprocess.run(
            ["java", "-cp", jar, "org.h2.tools.RunScript",
             "-url", url, "-user", "sa", "-script", str(script)],
            capture_output=True, text=True, timeout=180,
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        return False, f"java-runscript-failed:{e}"
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout or "").strip().splitlines()
        return False, "runscript-exit:%s %s" % (proc.returncode, " ".join(err)[:400])
    return True, (proc.stdout or "")[-400:]


def sqlite_type(h2_type: str) -> str:
    t = (h2_type or "").upper().split("(")[0].strip()
    if t in INT_TYPES:
        return "INTEGER"
    if t in REAL_TYPES:
        return "REAL"
    if t in BOOL_TYPES:
        return "INTEGER"
    return "TEXT"


def convert_bundle(dest: Path, mv_db: Path, mode: str, source_note: str) -> dict:
    """Turn <dest>/<name>.mv.db into export.sqlite + csv/ + schema.sql."""
    manifest = {"ok": False, "runId": dest.name, "mode": mode,
                "createdAt": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
    jar = find_h2_jar()
    if not jar:
        manifest["error"] = "h2-jar-not-found (set META_DISPLAY_H2_JAR or drop h2-*.jar under tools/)"
        return manifest
    manifest["h2Jar"] = jar

    name = mv_db.name
    base_name = name[:-len(".mv.db")] if name.endswith(".mv.db") else mv_db.stem
    db_base = mv_db.parent / base_name  # H2 appends .mv.db itself
    csv_dir = dest / "csv"
    csv_dir.mkdir(exist_ok=True)
    url = f"jdbc:h2:file:{db_base.as_posix()};{H2_DB_OPTS}"

    def sp(p: Path) -> str:
        return p.resolve().as_posix()

    csv_opts = "charset=UTF-8"
    catalog_sql = dest / "_catalog.sql"
    catalog_sql.write_text(
        f"CALL CSVWRITE('{sp(csv_dir / '_tables.csv')}', "
        "'SELECT TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
        "WHERE TABLE_TYPE = ''BASE TABLE'' AND TABLE_SCHEMA <> ''INFORMATION_SCHEMA'' "
        "ORDER BY TABLE_SCHEMA, TABLE_NAME'"
        f", '{csv_opts}');\n"
        f"CALL CSVWRITE('{sp(csv_dir / '_columns.csv')}', "
        "'SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, DATA_TYPE, "
        "CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, IS_NULLABLE "
        "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA <> ''INFORMATION_SCHEMA'' "
        "ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION'"
        f", '{csv_opts}');\n",
        encoding="utf-8",
    )
    ok, log = run_h2_script(jar, url, catalog_sql)
    if not ok:
        manifest["error"] = f"catalog-export-failed: {log}"
        return manifest

    tables = []
    with (csv_dir / "_tables.csv").open(newline="", encoding="utf-8") as f:
        for row in csv.reader(f):
            if len(row) >= 2 and row[0] != "TABLE_SCHEMA":
                tables.append((row[0], row[1]))

    col_types: dict[tuple[str, str], list[tuple[str, str, str]]] = {}
    cols_file = csv_dir / "_columns.csv"
    if cols_file.is_file():
        with cols_file.open(newline="", encoding="utf-8") as f:
            for row in csv.reader(f):
                if len(row) >= 5 and row[0] != "TABLE_SCHEMA":
                    key = (row[0], row[1])
                    col_types.setdefault(key, []).append((row[2], row[4], row[8] if len(row) > 8 else ""))

    data_sql = dest / "_data.sql"
    with data_sql.open("w", encoding="utf-8") as f:
        for schema, name in tables:
            fname = re.sub(r"[^A-Za-z0-9_-]", "_", f"{schema}__{name}")
            f.write(
                f"CALL CSVWRITE('{sp(csv_dir / (fname + '.csv'))}', "
                f"'SELECT * FROM {quote_ident(schema)}.{quote_ident(name)}', '{csv_opts}');\n"
            )
    if tables:
        ok, log = run_h2_script(jar, url, data_sql)
        if not ok:
            manifest["error"] = f"data-export-failed: {log}"
            return manifest

    # Build export.sqlite + schema.sql
    sqlite_path = dest / "export.sqlite"
    if sqlite_path.exists():
        sqlite_path.unlink()
    con = sqlite3.connect(str(sqlite_path))
    schema_lines = []
    table_rows = []
    for schema, name in tables:
        fname = re.sub(r"[^A-Za-z0-9_-]", "_", f"{schema}__{name}")
        csv_path = csv_dir / f"{fname}.csv"
        sname = sqlite_name(schema, name)
        cols = col_types.get((schema, name), [])
        if csv_path.is_file():
            with csv_path.open(newline="", encoding="utf-8") as f:
                reader = csv.reader(f)
                header = next(reader, [])
                if not cols:
                    cols = [(h, "", "") for h in header]
                col_names = [c[0] for c in cols]
                col_decl = [sqlite_type(c[1]) for c in cols]
                ddl = ", ".join(f"{quote_ident(cn)} {ct}" for cn, ct in zip(col_names, col_decl))
                con.execute(f"CREATE TABLE {quote_ident(sname)} ({ddl})")
                schema_lines.append(
                    f"CREATE TABLE {quote_ident(schema)}.{quote_ident(name)} ({ddl});"
                )
                is_bool = [t.upper().split("(")[0].strip() in BOOL_TYPES for _, t, _ in cols]
                is_int = [d == "INTEGER" and not b for d, b in zip(col_decl, is_bool)]
                is_real = [d == "REAL" for d in col_decl]
                batch, count = [], 0
                for row in reader:
                    vals = []
                    for i, raw in enumerate(row):
                        if raw == "":
                            vals.append(None)
                        elif i < len(is_bool) and is_bool[i]:
                            vals.append(1 if raw.upper() == "TRUE" else 0)
                        elif i < len(is_int) and is_int[i]:
                            try:
                                vals.append(int(raw))
                            except ValueError:
                                vals.append(raw)
                        elif i < len(is_real) and is_real[i]:
                            try:
                                vals.append(float(raw))
                            except ValueError:
                                vals.append(raw)
                        else:
                            vals.append(raw)
                    batch.append(vals)
                    count += 1
                    if len(batch) >= 1000:
                        con.executemany(
                            f"INSERT INTO {quote_ident(sname)} VALUES ({','.join('?' * len(col_names))})",
                            batch,
                        )
                        batch = []
                if batch:
                    con.executemany(
                        f"INSERT INTO {quote_ident(sname)} VALUES ({','.join('?' * len(col_names))})",
                        batch,
                    )
                table_rows.append({
                    "schema": schema, "name": name, "sqliteName": sname,
                    "rowCount": count, "csv": f"csv/{fname}.csv",
                    "csvSha256": sha256(csv_path),
                })
        else:
            table_rows.append({"schema": schema, "name": name, "sqliteName": sname,
                               "rowCount": None, "csv": None, "csvSha256": None})
    con.commit()
    con.close()
    (dest / "schema.sql").write_text("\n".join(schema_lines) + "\n", encoding="utf-8")

    manifest.update({
        "ok": True,
        "sourceNote": source_note,
        "tables": table_rows,
        "sqlite": {"path": "export.sqlite", "sha256": sha256(sqlite_path),
                   "bytes": sqlite_path.stat().st_size},
        "schemaSql": "schema.sql",
        "nullVsEmpty": "H2 CSVWRITE emits empty for NULL and for '' - both became NULL in sqlite",
        "consistency": "consistent-copy" if mode != "live-api" else "live-read-committed",
    })
    (dest / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    LATEST_FILE.write_text(
        json.dumps({"runId": dest.name, "dir": str(dest), "mode": mode,
                    "sqlite": str(sqlite_path), "createdAt": manifest["createdAt"]},
                   ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return manifest


def export_cmd(args: argparse.Namespace) -> int:
    EXPORT_ROOT.mkdir(parents=True, exist_ok=True)
    src_file = None
    mode = "snapshot"
    if args.from_path:
        p = Path(args.from_path)
        if p.is_dir():
            cand = p / DB_FILE.name
            src_file = cand if cand.is_file() else None
            if src_file is None:
                mvs = sorted(p.glob("*.mv.db"))
                src_file = mvs[0] if mvs else None
        elif p.is_file():
            src_file = p
        if src_file is None:
            return emit({"ok": False, "error": f"no .mv.db under {p}"}, 2)
        mode = "from"
    run_id = time.strftime("%Y%m%d-%H%M%S") if mode == "snapshot" else \
        time.strftime("from-%Y%m%d-%H%M%S")
    dest = EXPORT_ROOT / run_id
    dest.mkdir(parents=True, exist_ok=False)
    if mode == "snapshot":
        ok, lock = copy_source_db(dest)
        if not ok:
            (dest / "manifest.json").write_text(json.dumps(
                {"ok": False, "runId": run_id, "lock": lock,
                 "error": "locked" if lock.get("locked") else "missing"},
                indent=2), encoding="utf-8")
            return emit({"ok": False, "runId": run_id, "lock": lock,
                         "error": "file locked - stop server or use `live export`"},
                        3 if lock.get("locked") else 2)
        src_file = dest / DB_FILE.name
    else:
        target = dest / DB_FILE.name
        shutil.copy2(src_file, target)
        src_file = target
    manifest = convert_bundle(dest, src_file, mode,
                              str(src_file))
    return emit(manifest, 0 if manifest.get("ok") else 4)


# ---------------------------------------------------------------- query family

def cmd_query(args: argparse.Namespace) -> int:
    db_dir = resolve_db_dir(args.db)
    if db_dir is None:
        return emit({"ok": False, "error": "no export bundle (run export first)"}, 2)
    reason = check_sql(args.sql)
    if reason:
        return emit({"ok": False, "error": "sql_rejected", "reason": reason}, 2)
    con, err = open_sqlite(db_dir)
    if con is None:
        return emit({"ok": False, "error": err}, 2)
    try:
        max_rows = max(1, min(args.max_rows, HARD_MAX_ROWS))
        cols, rows, truncated = fetch_rows(con, args.sql, max_rows)
    except sqlite3.Error as e:
        con.close()
        return emit({"ok": False, "error": "query_failed", "reason": str(e)[:400]}, 3)
    con.close()
    payload = {"ok": True, "db": db_dir.name, "columns": cols, "rows": rows,
               "rowCount": len(rows), "truncated": truncated}
    if args.format == "json":
        return emit(payload)
    if args.format == "csv":
        w = csv.writer(sys.stdout)
        w.writerow(cols)
        w.writerows(rows)
        return 0
    # md
    print("| " + " | ".join(cols) + " |")
    print("| " + " | ".join("---" for _ in cols) + " |")
    for r in rows:
        print("| " + " | ".join("" if v is None else str(v).replace("|", "\\|") for v in r) + " |")
    return 0


def cmd_tables(args: argparse.Namespace) -> int:
    db_dir = resolve_db_dir(args.db)
    if db_dir is None:
        return emit({"ok": False, "error": "no export bundle (run export first)"}, 2)
    manifest = read_json(db_dir / "manifest.json")
    tables = (manifest or {}).get("tables") or []
    if not tables:
        con, err = open_sqlite(db_dir)
        if con is None:
            return emit({"ok": False, "error": err}, 2)
        rows = con.execute(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
        con.close()
        tables = [{"sqliteName": r[0], "rowCount": None} for r in rows]
    return emit({"ok": True, "db": db_dir.name, "tables": tables})


def cmd_schema(args: argparse.Namespace) -> int:
    db_dir = resolve_db_dir(args.db)
    if db_dir is None:
        return emit({"ok": False, "error": "no export bundle (run export first)"}, 2)
    con, err = open_sqlite(db_dir)
    if con is None:
        return emit({"ok": False, "error": err}, 2)
    names = [args.table] if args.table else [
        r[0] for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
    out = []
    for n in names:
        try:
            cols = con.execute(f"PRAGMA table_info({quote_ident(n)})").fetchall()
        except sqlite3.Error:
            cols = []
        out.append({"table": n, "columns": [
            {"name": c[1], "type": c[2], "notnull": bool(c[3]), "pk": bool(c[5])}
            for c in cols]})
    con.close()
    return emit({"ok": True, "db": db_dir.name, "tables": out})


def cmd_tail(args: argparse.Namespace) -> int:
    db_dir = resolve_db_dir(args.db)
    if db_dir is None:
        return emit({"ok": False, "error": "no export bundle (run export first)"}, 2)
    con, err = open_sqlite(db_dir)
    if con is None:
        return emit({"ok": False, "error": err}, 2)
    n = max(1, min(args.n, HARD_MAX_ROWS))
    try:
        cols = [d[0] for d in con.execute(
            f"SELECT * FROM {quote_ident(args.table)} LIMIT 0").description]
        rows = con.execute(
            f"SELECT * FROM {quote_ident(args.table)} ORDER BY rowid DESC LIMIT ?",
            (n,)).fetchall()
    except sqlite3.Error as e:
        con.close()
        return emit({"ok": False, "error": str(e)[:300]}, 3)
    con.close()
    rows = [list(r) for r in reversed(rows)]
    return emit({"ok": True, "db": db_dir.name, "table": args.table,
                 "columns": cols, "rows": rows, "rowCount": len(rows)})


# ---------------------------------------------------------------- live lane

def resolve_token(args) -> tuple[str | None, str | None]:
    tok = getattr(args, "token", None) or ""
    if tok.strip():
        return tok.strip(), "arg"
    for env in ("DOMAIN_ALLOWLIST_ADMIN_TOKEN", "AWX_ADMIN_TOKEN", "LLM_OWNER_TOKEN"):
        v = os.environ.get(env, "").strip()
        if v:
            return v, f"env:{env}"
    if DEV_TOKEN_FILE.is_file():
        try:
            v = DEV_TOKEN_FILE.read_text(encoding="utf-8").splitlines()[0].strip()
            if v:
                return v, "var/dev-admin-token.txt"
        except (OSError, IndexError):
            pass
    return None, None


def live_request(args, method: str, path: str, body: dict | None = None):
    base = (getattr(args, "base_url", None) or
            os.environ.get("META_DISPLAY_BASE_URL", "") or DEFAULT_BASE_URL).rstrip("/")
    token, token_src = resolve_token(args)
    url = base + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/json")
    if body is not None:
        req.add_header("Content-Type", "application/json")
    admin_token = token
    if admin_token:
        req.add_header("X-Admin-Token", admin_token)
    try:
        with urllib.request.urlopen(req, timeout=getattr(args, "timeout", 15)) as res:
            return res.status, json.loads(res.read().decode("utf-8", "replace")), token_src
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read().decode("utf-8", "replace"))
        except Exception:
            payload = {"httpStatus": e.code}
        payload["httpStatus"] = e.code
        return e.code, payload, token_src
    except (urllib.error.URLError, OSError) as e:
        return None, {"error": "unreachable", "reason": str(e)[:300]}, token_src


def cmd_live(args: argparse.Namespace) -> int:
    sub = args.live_cmd
    if sub == "status":
        code, payload, token_src = live_request(args, "GET", f"{LIVE_API}/tables")
        payload["tokenSource"] = token_src or "none"
        payload["endpoint"] = LIVE_API
        if code is None:
            payload["hint"] = ("server down or endpoint not built yet - use "
                               "`export` while server is stopped")
        elif code in (401, 403):
            payload["hint"] = ("admin token required - set DOMAIN_ALLOWLIST_ADMIN_TOKEN/"
                               "LLM_OWNER_TOKEN or keep var/dev-admin-token.txt in sync")
        return emit(payload, 0 if code == 200 else 3)
    if sub == "tables":
        code, payload, _ = live_request(args, "GET", f"{LIVE_API}/tables")
        return emit(payload, 0 if code == 200 else 3)
    if sub == "columns":
        code, payload, _ = live_request(args, "GET", f"{LIVE_API}/columns")
        return emit(payload, 0 if code == 200 else 3)
    if sub == "query":
        reason = check_sql(args.sql)
        if reason:
            return emit({"ok": False, "error": "sql_rejected", "reason": reason}, 2)
        body = {"sql": args.sql, "maxRows": max(1, min(args.max_rows, HARD_MAX_ROWS))}
        code, payload, _ = live_request(args, "POST", f"{LIVE_API}/query", body)
        return emit(payload, 0 if code == 200 else 3)
    if sub == "export":
        return live_export(args)
    return emit({"error": f"unknown live subcommand {sub}"}, 2)


def live_export(args: argparse.Namespace) -> int:
    code, payload, token_src = live_request(args, "GET", f"{LIVE_API}/tables")
    if code != 200 or not payload.get("ok"):
        payload["hint"] = "live endpoint unavailable - use `export` while server is stopped"
        return emit({"ok": False, "stage": "tables", "detail": payload}, 3)
    run_id = time.strftime("live-%Y%m%d-%H%M%S")
    dest = EXPORT_ROOT / run_id
    (dest / "csv").mkdir(parents=True, exist_ok=False)
    sqlite_path = dest / "export.sqlite"
    con = sqlite3.connect(str(sqlite_path))
    chunk = 1000
    max_rows_table = max(1000, min(getattr(args, "max_table_rows", 200000), 1000000))
    table_rows, failed = [], []
    for t in payload.get("tables", []):
        schema, name = t.get("schema", "PUBLIC"), t.get("name", "")
        sname = sqlite_name(schema, name)
        offset, created, count = 0, False, 0
        while offset < max_rows_table:
            sql = (f"SELECT * FROM {quote_ident(schema)}.{quote_ident(name)} "
                   f"LIMIT {chunk} OFFSET {offset}")
            c2, res, _ = live_request(args, "POST", f"{LIVE_API}/query",
                                      {"sql": sql, "maxRows": chunk})
            if c2 != 200 or not res.get("ok"):
                failed.append({"table": name, "status": c2,
                               "reason": (res or {}).get("reason")})
                break
            col_names = [c["name"] if isinstance(c, dict) else c
                         for c in (res.get("columns") or [])]
            rows = res.get("rows") or []
            if not created:
                ddl = ", ".join(quote_ident(c) + " TEXT" for c in col_names) or "\"_\" TEXT"
                con.execute(f"CREATE TABLE {quote_ident(sname)} ({ddl})")
                created = True
            for r in rows:
                vals = [None if v is None else
                        (v[:CELL_CHAR_CAP] if isinstance(v, str) and len(v) > CELL_CHAR_CAP else v)
                        for v in r]
                vals = vals[:len(col_names)] + [None] * max(0, len(col_names) - len(vals))
                con.execute(
                    f"INSERT INTO {quote_ident(sname)} VALUES ({','.join('?' * len(col_names))})",
                    vals,
                )
                count += 1
            offset += len(rows)
            if len(rows) < chunk:
                break
        con.commit()
        table_rows.append({"schema": schema, "name": name, "sqliteName": sname,
                           "rowCount": count})
    con.close()
    manifest = {
        "ok": not failed,
        "runId": run_id, "mode": "live-api",
        "createdAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "tokenSource": token_src or "none",
        "tables": table_rows, "failedTables": failed,
        "sqlite": {"path": "export.sqlite", "sha256": sha256(sqlite_path),
                   "bytes": sqlite_path.stat().st_size},
        "consistency": "live-read-committed (per-statement snapshot; tables not transactional)",
        "nullVsEmpty": "JSON lane preserves NULL vs empty string",
    }
    (dest / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    LATEST_FILE.write_text(
        json.dumps({"runId": run_id, "dir": str(dest), "mode": "live-api",
                    "sqlite": str(sqlite_path), "createdAt": manifest["createdAt"]},
                   ensure_ascii=False, indent=2), encoding="utf-8")
    return emit(manifest, 0 if not failed else 4)


# ---------------------------------------------------------------- main

def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status", help="JSON status of live H2 file + exports (no mutate)")
    sub.add_parser("snapshot", help="Copy DB into var/meta-display-db/export/<runId>/ when unlocked")
    e = sub.add_parser("export", help="snapshot + convert to export.sqlite/csv/schema.sql")
    e.add_argument("--from", dest="from_path", default=None,
                   help="convert an existing .mv.db file or snapshot dir instead of copying live")
    for name, helptext in (
        ("query", "run a read-only SELECT against an export bundle"),
        ("tables", "list tables of an export bundle"),
        ("schema", "show column types of an export bundle"),
        ("tail", "last N rows of a table"),
    ):
        sp = sub.add_parser(name, help=helptext)
        sp.add_argument("--db", default="latest",
                        help="runId under export/, 'latest' (default) or a bundle dir path")
        if name == "query":
            sp.add_argument("sql")
            sp.add_argument("--max-rows", type=int, default=DEFAULT_MAX_ROWS)
            sp.add_argument("--format", choices=("json", "csv", "md"), default="json")
        elif name == "schema":
            sp.add_argument("table", nargs="?", default=None)
        elif name == "tail":
            sp.add_argument("table")
            sp.add_argument("-n", type=int, default=20)
    lp = sub.add_parser("live", help="HTTP lane to the running server (/api/internal/db/meta)")
    lsub = lp.add_subparsers(dest="live_cmd", required=True)

    def live_opts(sp_):
        sp_.add_argument("--base-url", default=None, help="default http://127.0.0.1:18180")
        sp_.add_argument("--token", default=None,
                         help="admin token; default env DOMAIN_ALLOWLIST_ADMIN_TOKEN/"
                              "AWX_ADMIN_TOKEN/LLM_OWNER_TOKEN then var/dev-admin-token.txt")
        sp_.add_argument("--timeout", type=int, default=15)

    for name in ("status", "tables", "columns"):
        live_opts(lsub.add_parser(name))
    q = lsub.add_parser("query")
    q.add_argument("sql")
    q.add_argument("--max-rows", type=int, default=DEFAULT_MAX_ROWS)
    live_opts(q)
    lx = lsub.add_parser("export", help="pull all tables over HTTP into an export bundle")
    lx.add_argument("--max-table-rows", type=int, default=200000)
    live_opts(lx)

    args = p.parse_args()
    if args.cmd == "status":
        return status(args)
    if args.cmd == "snapshot":
        return snapshot(args)
    if args.cmd == "export":
        return export_cmd(args)
    if args.cmd == "query":
        return cmd_query(args)
    if args.cmd == "tables":
        return cmd_tables(args)
    if args.cmd == "schema":
        return cmd_schema(args)
    if args.cmd == "tail":
        return cmd_tail(args)
    if args.cmd == "live":
        return cmd_live(args)
    return 1


if __name__ == "__main__":
    sys.exit(main())
