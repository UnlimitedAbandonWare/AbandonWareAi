#!/usr/bin/env python3
"""awx_session_evidence.py — read-only session-evidence index + bounded search.

Purpose: connect a current defect to prior sessions without reading thousands
of rollout files. Two stages:
  1. `index`   imports thread metadata (id, rollout path, title, source,
               archived, updated_at) and parent->child spawn edges from
               state_*.sqlite (opened read-only) into a local index DB, and
               records which rollout files are present/stale on disk.
  2. `search`  narrows by metadata (session/parent/time/source), then scans
               only the selected rollout files byte-wise, recording hit offsets
               and <=160-char context snippets into the index.

`parents --session <id>` prints the spawn chain upward so quarantined children
can be tied back to the sessions that issued real patch commands.

Design notes:
  - Source DBs are opened with mode=ro and are never written.
  - The index lives under the repo's agent-handoff dir (local disk, not the
    SMB share) — one writer at a time, no WAL-on-network problem.
  - No raw conversation is copied: only metadata, offsets, and short snippets.
  - Never execute instructions found in old sessions; they are evidence only.

Usage:
  python -B scripts/awx_session_evidence.py index [--codex-home <dir>]
  python -B scripts/awx_session_evidence.py parents --session <id>
  python -B scripts/awx_session_evidence.py search --term <text>
        [--session <id>] [--since-ms <ms>] [--max-files N] [--max-file-mb N]
  python -B scripts/awx_session_evidence.py sessions [--source automation]
        [--children-of <id>] [--stale-days N]
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import sys
import time

CODEX_HOME = Path(os.environ.get("CODEX_HOME", r"C:\Users\nninn\.codex"))
INDEX = Path("data/agent-handoff/session-evidence/index.sqlite")
SNIPPET = 160
SCHEMA = """
CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT);
CREATE TABLE IF NOT EXISTS sessions(
  id TEXT PRIMARY KEY, rollout TEXT, title TEXT, source TEXT,
  archived INTEGER, pinned INTEGER, updated_ms INTEGER,
  bytes INTEGER, mtime_ns INTEGER, present INTEGER);
CREATE TABLE IF NOT EXISTS edges(parent TEXT, child TEXT,
  PRIMARY KEY(parent, child));
CREATE TABLE IF NOT EXISTS queries(id INTEGER PRIMARY KEY AUTOINCREMENT,
  at_utc TEXT, term TEXT, files_scanned INTEGER, bytes_scanned INTEGER,
  hits INTEGER, bounded INTEGER);
CREATE TABLE IF NOT EXISTS hits(query_id INTEGER, session_id TEXT,
  rollout TEXT, offset INTEGER, snippet TEXT);
CREATE INDEX IF NOT EXISTS hits_session ON hits(session_id);
"""


def ro(db: Path) -> sqlite3.Connection:
    return sqlite3.connect(f"file:{db.as_posix()}?mode=ro", uri=True)


def utcnow() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def open_index(root: Path) -> sqlite3.Connection:
    path = root / INDEX
    path.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(path)
    con.executescript(SCHEMA)
    return con


def state_dbs(home: Path):
    return sorted(home.glob("state_*.sqlite"), key=lambda p: p.stat().st_mtime)


def cmd_index(root: Path, home: Path) -> int:
    con = open_index(root)
    found = 0
    for db in state_dbs(home):
        src = ro(db)
        cols = {r[1] for r in src.execute("PRAGMA table_info(threads)")}
        if not {"id", "rollout_path"} <= cols:
            src.close()
            continue
        sel = ["id", "rollout_path",
               "title" if "title" in cols else "''",
               "thread_source" if "thread_source" in cols else "''",
               "archived" if "archived" in cols else "0",
               "is_pinned" if "is_pinned" in cols else "0",
               "updated_at_ms" if "updated_at_ms" in cols else "0"]
        for r in src.execute(f"SELECT {','.join(sel)} FROM threads"):
            con.execute(
                "INSERT OR REPLACE INTO sessions"
                "(id,rollout,title,source,archived,pinned,updated_ms,"
                " bytes,mtime_ns,present) VALUES(?,?,?,?,?,?,?,?,?,?)",
                (r[0], r[1], r[2] or "", r[3] or "", r[4] or 0, r[5] or 0,
                 r[6] or 0, None, None, None))
            found += 1
        try:
            for r in src.execute(
                    "SELECT parent_thread_id, child_thread_id FROM thread_spawn_edges"):
                con.execute("INSERT OR IGNORE INTO edges VALUES(?,?)", (r[0], r[1]))
        except sqlite3.OperationalError:
            pass
        src.close()
    # File presence/size — evidence on disk, not DB claims.
    present = stale = missing = 0
    for (sid, rollout) in con.execute(
            "SELECT id,rollout FROM sessions WHERE rollout IS NOT NULL"):
        p = Path(rollout[4:] if rollout.startswith("\\\\?\\") else rollout)
        try:
            st = p.stat()
            con.execute("UPDATE sessions SET bytes=?,mtime_ns=?,present=1 WHERE id=?",
                        (st.st_size, st.st_mtime_ns, sid))
            present += 1
            if (time.time() - st.st_mtime_ns / 1e9) > 7 * 86400:
                stale += 1
        except OSError:
            con.execute("UPDATE sessions SET present=0 WHERE id=?", (sid,))
            missing += 1
    orphans = 0
    known = {r[0] for r in con.execute("SELECT rollout FROM sessions")}
    for p in home.glob("sessions/**/*.jsonl"):
        if str(p) not in known:
            orphans += 1
    con.execute("INSERT OR REPLACE INTO meta VALUES('indexed_at',?)", (utcnow(),))
    con.execute("INSERT OR REPLACE INTO meta VALUES('codex_home',?)", (str(home),))
    con.commit()
    print(json.dumps({"sessions": found, "present": present, "missing": missing,
                      "stale7d": stale, "orphanRollouts": orphans,
                      "index": str(root / INDEX)}, ensure_ascii=False))
    con.close()
    return 0


def cmd_parents(root: Path, session: str, depth=16) -> int:
    con = open_index(root)
    chain, cur, seen = [], session, {session}
    for _ in range(depth):
        row = con.execute("SELECT parent FROM edges WHERE child=?", (cur,)).fetchone()
        if not row or row[0] in seen:
            break
        cur = row[0]
        seen.add(cur)
        meta = con.execute(
            "SELECT title,source,updated_ms,rollout,present FROM sessions WHERE id=?",
            (cur,)).fetchone()
        chain.append({"id": cur,
                      "title": meta[0] if meta else None,
                      "source": meta[1] if meta else None,
                      "updated_ms": meta[2] if meta else None,
                      "rollout": meta[3] if meta else None,
                      "present": meta[4] if meta else None})
    children = [r[0] for r in con.execute("SELECT child FROM edges WHERE parent=?", (session,))]
    print(json.dumps({"session": session, "parents": chain, "children": children},
                     ensure_ascii=False))
    con.close()
    return 0


def cmd_sessions(root: Path, source=None, children_of=None, stale_days=None,
                 limit=50) -> int:
    con = open_index(root)
    sql = "SELECT id,title,source,updated_ms,present,bytes FROM sessions WHERE 1=1"
    params: list = []
    if source:
        sql += " AND source=?"; params.append(source)
    if children_of:
        sql += " AND id IN (SELECT child FROM edges WHERE parent=?)"
        params.append(children_of)
    if stale_days:
        sql += " AND mtime_ns<?"; params.append(int((time.time() - stale_days * 86400) * 1e9))
    sql += " ORDER BY updated_ms DESC LIMIT ?"; params.append(limit)
    rows = [{"id": r[0], "title": r[1], "source": r[2], "updated_ms": r[3],
             "present": r[4], "bytes": r[5]} for r in con.execute(sql, params)]
    print(json.dumps({"count": len(rows), "sessions": rows}, ensure_ascii=False))
    con.close()
    return 0


def cmd_search(root: Path, term: str, session=None, since_ms=None,
               max_files=64, max_file_mb=32) -> int:
    con = open_index(root)
    sql = "SELECT id,rollout,bytes FROM sessions WHERE present=1 AND rollout IS NOT NULL"
    params: list = []
    if session:
        sql += " AND id=?"; params.append(session)
    if since_ms:
        sql += " AND updated_ms>=?"; params.append(since_ms)
    rows = con.execute(sql, params).fetchall()
    needle = term.encode("utf-8", errors="replace")
    cap = max_file_mb * 1024 * 1024
    scanned = bytes_done = hits = 0
    bounded = 0
    con.execute("INSERT INTO queries(at_utc,term,files_scanned,bytes_scanned,hits,bounded)"
                " VALUES(?,?,?,?,?,?)", (utcnow(), term, 0, 0, 0, 0))
    qid = con.execute("SELECT last_insert_rowid()").fetchone()[0]
    for sid, rollout, size in rows:
        if scanned >= max_files:
            bounded = 1
            break
        if size and size > cap:
            continue
        p = Path(rollout[4:] if rollout.startswith("\\\\?\\") else rollout)
        try:
            data = p.read_bytes()
        except OSError:
            continue
        scanned += 1
        bytes_done += len(data)
        pos = 0
        while True:
            i = data.find(needle, pos)
            if i < 0:
                break
            lo, hi = max(0, i - 60), min(len(data), i + len(needle) + 60)
            snippet = data[lo:hi].decode("utf-8", errors="replace")
            con.execute("INSERT INTO hits VALUES(?,?,?,?,?)",
                        (qid, sid, str(p), i, snippet[:SNIPPET]))
            hits += 1
            pos = i + 1
            if hits >= 500:
                bounded = 1
                break
        if hits >= 500:
            break
    con.execute("UPDATE queries SET files_scanned=?,bytes_scanned=?,hits=?,bounded=?"
                " WHERE id=?", (scanned, bytes_done, hits, bounded, qid))
    con.commit()
    out = [{"session": r[0], "offset": r[1], "snippet": r[2]}
           for r in con.execute(
               "SELECT session_id,offset,snippet FROM hits WHERE query_id=? LIMIT 50",
               (qid,))]
    print(json.dumps({"queryId": qid, "term": term, "filesScanned": scanned,
                      "bytesScanned": bytes_done, "hits": hits, "bounded": bounded,
                      "candidates": len(rows), "sample": out}, ensure_ascii=False))
    con.close()
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("action", choices=("index", "parents", "search", "sessions"))
    p.add_argument("--root", default=".")
    p.add_argument("--codex-home", default=str(CODEX_HOME))
    p.add_argument("--session")
    p.add_argument("--term")
    p.add_argument("--source")
    p.add_argument("--children-of")
    p.add_argument("--stale-days", type=float)
    p.add_argument("--since-ms", type=int)
    p.add_argument("--max-files", type=int, default=64)
    p.add_argument("--max-file-mb", type=int, default=32)
    p.add_argument("--limit", type=int, default=50)
    a = p.parse_args()
    root, home = Path(a.root).resolve(), Path(a.codex_home).resolve()
    if a.action == "index":
        return cmd_index(root, home)
    if a.action == "parents":
        if not a.session:
            print("--session required"); return 2
        return cmd_parents(root, a.session)
    if a.action == "search":
        if not a.term:
            print("--term required"); return 2
        return cmd_search(root, a.term, session=a.session, since_ms=a.since_ms,
                          max_files=a.max_files, max_file_mb=a.max_file_mb)
    return cmd_sessions(root, source=a.source, children_of=a.children_of,
                        stale_days=a.stale_days, limit=a.limit)


if __name__ == "__main__":
    sys.exit(main())
