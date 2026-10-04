#!/usr/bin/env python3
"""Query-flow notepad bundle builder (redacted, read-only inputs).

Collects the existing query/debug evidence lanes into ONE static bundle for
``docs/debug-ui/query-flow-notepad.html``:

  var/debug/chat-session-traces/**   sanitized per-run records (hash: ids only)
  var/rag-launcher/LATEST.json       latest launcher status (Read-RAG-Debug)
  var/meta-display-db/export/<run>/  export.sqlite only - never live JDBC
  logs/debug-events*.ndjson          debug event stream tails
  logs/trace*.ndjson                 request trace stream tails
  data/agent-handoff/codex-autonomy/*/journal.json   active work journals

Output: ``var/debug/query-flow/<stamp>/bundle.json`` + ``NO_SECRETS`` marker and
a derivative pointer ``var/debug/query-flow/latest.json`` (this lane's own
index only; it never replaces the chat-session-traces or rag-launcher SSOTs).

Prompt/response bodies and secret values are never copied verbatim: message
columns are truncated + hashed, secret-looking assignments are masked, and a
post-write scan must pass before NO_SECRETS is written.

Usage:
  query_flow_notepad_bundle.py [--root DIR] [--since-hours N]
      [--max-sessions N] [--tail-lines N] [--db-tail-rows N] [--db-max-tables N]
      [--offline] [--out DIR]
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import heapq
import json
import os
import re
import sqlite3
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import chat_session_debug_export as trace_reader  # noqa: E402

SCHEMA = "awx.query-flow-bundle.v1"
DEFAULT_SINCE_HOURS = 24
DEFAULT_MAX_SESSIONS = 500
DEFAULT_TAIL_LINES = 200
DEFAULT_DB_TAIL_ROWS = 10
DEFAULT_DB_MAX_TABLES = 12
READ_BYTES = 512 * 1024

# --- secret handling -------------------------------------------------------
# redact()는 "값이 붙은" 비밀 패턴 전체를 <redacted>로 바꾼다 — 키 이름을 남기면
# api_key|bearer|password|token 계열 할당은 단순 grep에도 걸리므로 키까지 통째로 제거.
SECRET_ASSIGN_RE = re.compile(
    r"(?i)\b(?:api[-_.]?key|apikey|secret|password|passwd|pwd|credential|"
    r"authorization|private[-_.]?key|access[-_.]?key|access[-_.]?token|"
    r"refresh[-_.]?token|id[-_.]?token|auth[-_.]?token|session[-_.]?token|"
    r"subscription[-_.]?token|token)[\"']?\s*[:=]\s*[\"']?[^\s,\"'}]{2,}")
AUTH_SCHEME_RE = re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{4,}")
PROVIDER_KEY_RE = re.compile(
    r"\b(?:sk|pk|ghp|gho|xox[baprs])-[A-Za-z0-9._-]{10,}|"
    r"AIza[0-9A-Za-z_-]{10,}|ya29\.[0-9A-Za-z_-]{10,}|"
    r"github_pat_[0-9A-Za-z_]{10,}")
PEM_RE = re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")
# 환경변수 "이름"은 허용이지만 OPENAI_API_KEY 형태는 naive grep(api_key)에 걸리므로
# 제공자 식별부만 남기고 비밀 접미사는 지운다: OPENAI_API_KEY -> OPENAI_<cred-env>.
ENV_NAME_RE = re.compile(
    r"\b([A-Z][A-Z0-9_]{2,})_(?:API_KEY|SECRET_KEY|ACCESS_KEY|API_TOKEN|"
    r"SECRET_TOKEN|ACCESS_TOKEN|PASSWORD|PASSWD|PRIVATE_KEY|CREDENTIAL)\b")
# 단순 grep이 잡을 수 있는 단독 단어도 마스킹한다 (값 유무와 무관하게 출력 금지).
BANNED_WORD_RE = re.compile(r"(?i)\b(?:password|passwd|api_key|bearer)\b")
SECRET_RESIDUE_RES = (SECRET_ASSIGN_RE, AUTH_SCHEME_RE, PROVIDER_KEY_RE,
                      PEM_RE, ENV_NAME_RE, BANNED_WORD_RE)

SENSITIVE_COL_RE = re.compile(
    r"(?i)(content|body|message|prompt|answer|response|payload|transcript|"
    r"query|sql|text|value)")
PREVIEW_CHARS = 80
CELL_CHARS = 200
DETAIL_CHARS = 500
MESSAGE_CHARS = 240


def sha12(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8", "replace")).hexdigest()[:12]


def redact(text: str) -> str:
    out = SECRET_ASSIGN_RE.sub("<redacted>", text)
    out = AUTH_SCHEME_RE.sub("<redacted-auth>", out)
    out = PROVIDER_KEY_RE.sub("<redacted-key>", out)
    out = PEM_RE.sub("<redacted-pem>", out)
    out = ENV_NAME_RE.sub(r"\1_<cred-env>", out)
    out = BANNED_WORD_RE.sub("<redacted-word>", out)
    return out


def secret_residue(text: str) -> list[str]:
    hits = []
    for rx in SECRET_RESIDUE_RES:
        m = rx.search(text)
        if m:
            hits.append(rx.pattern.split("|")[0][:24])
    return hits


def rel(root: Path, path: Path) -> str:
    try:
        return str(path.relative_to(root)).replace("\\", "/")
    except ValueError:
        return str(path)


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


# --- collectors ------------------------------------------------------------

def collect_sessions(root: Path, since_hours: float, limit: int, *, stats=None) -> list[dict]:
    if type(limit) is not int or limit <= 0:
        raise ValueError("max-sessions-must-be-positive")
    rows, retained_peak = [], 0
    for file, rec in trace_reader.iter_records(root, since_hours, stats=stats):
        key = (trace_reader.event_time(rec["ts"]), rec["_file"], rec["_line"])
        if len(rows) == limit and key <= rows[0][:3]:
            continue
        keys = rec.get("traceKeys")
        row = {
            "ts": str(rec.get("ts") or ""),
            "sessionId": rec.get("sessionId"),
            "runId": rec.get("runId"),
            "recordId": rec.get("recordId"),
            "surface": rec.get("surface"),
            "requestedModel": rec.get("requestedModel"),
            "effectiveModel": rec.get("effectiveModel"),
            "baseUrlClass": rec.get("baseUrlClass"),
            "ragEnabled": rec.get("ragEnabled"),
            "agentDbContextEnabled": rec.get("agentDbContextEnabled"),
            "harmonyWarn": rec.get("harmonyWarn"),
            "cfvmQueued": rec.get("cfvmQueued"),
            "outcome": rec.get("outcome"),
            "errorClass": rec.get("errorClass"),
            "fallbackCount": rec.get("fallbackCount"),
            "traceKeyCount": len(keys) if isinstance(keys, list) else None,
            "file": rec.get("_file") or rel(root, file),
        }
        item = (*key, row)
        if len(rows) < limit:
            heapq.heappush(rows, item)
        else:
            heapq.heapreplace(rows, item)
        retained_peak = max(retained_peak, len(rows))
    if stats is not None:
        stats["retained_peak"] = retained_peak if stats["rows_scanned"] is not None else None
    return [item[3] for item in sorted(rows)]


def collect_rag_trail(root: Path, use_subprocess: bool) -> dict:
    script = root / "scripts" / "read_rag_debug_trail.ps1"
    if use_subprocess and script.is_file():
        try:
            proc = subprocess.run(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                 "-File", str(script), "-JsonStdout"],
                capture_output=True, text=True, timeout=90, cwd=str(root))
            text = (proc.stdout or "").strip()
            if text:
                try:
                    data = json.loads(text)
                except json.JSONDecodeError:
                    data = json.loads(text.splitlines()[-1])
                if isinstance(data, dict):
                    data["_collect"] = "read_rag_debug_trail.ps1 -JsonStdout"
                    data["_exitCode"] = proc.returncode
                    return data
        except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError,
                IndexError):
            pass
    # 폴백: LATEST.json + 최신 var/debug status 요약만 직접 읽기 (스크립트와 동일 규칙).
    latest = read_json(root / "var" / "rag-launcher" / "LATEST.json")
    debug = {"found": False, "note": "no var/debug status/verify json observed"}
    dbg_dir = root / "var" / "debug"
    if dbg_dir.is_dir():
        cands = [p for p in dbg_dir.iterdir()
                 if p.is_file() and re.search(r"(?i)(-status|verify[^.]*)\.json$", p.name)]
        cands.sort(key=lambda p: p.stat().st_mtime, reverse=True)
        if cands:
            d = read_json(cands[0])
            if isinstance(d, dict):
                debug = {"found": True, "file": cands[0].name,
                         "action": d.get("action"), "ok": d.get("ok"),
                         "status": d.get("status"), "exitCode": d.get("exitCode"),
                         "role": d.get("role")}
    return {"schemaVersion": "awx.rag_debug_trail.v1",
            "_collect": "direct-read fallback (no powershell)",
            "source": "latest-pointer" if latest else "none",
            "latest": latest, "debug": debug,
            "warnings": [] if latest else ["LATEST.json missing or unreadable"],
            "exitCode": 0 if (latest and (latest.get("ok") is True
                                          or latest.get("status") == "ready")) else 3,
            "verdict": ("ready" if latest and (latest.get("ok") is True
                                               or latest.get("status") == "ready")
                        else "no-usable-trail")}


def open_export_sqlite(db_dir: Path):
    db = db_dir / "export.sqlite"
    if not db.is_file():
        return None
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    con.execute("PRAGMA query_only=ON")
    return con


def sanitize_cell(col: str, value):
    if value is None or isinstance(value, (int, float)):
        return value
    text = str(value)
    if SENSITIVE_COL_RE.search(col or ""):
        return {"preview": redact(text)[:PREVIEW_CHARS],
                "len": len(text), "sha12": sha12(text)}
    out = redact(text)
    return out[:CELL_CHARS] + ("..." if len(out) > CELL_CHARS else "")


def collect_db_export(root: Path, tail_rows: int, max_tables: int) -> dict:
    export_root = root / "var" / "meta-display-db" / "export"
    latest = read_json(export_root / "latest.json")
    out = {"exportRoot": rel(root, export_root), "latestPointer": latest,
           "db": None, "tables": [], "tails": {}, "note": None}
    db_dir = None
    if latest and latest.get("dir"):
        p = Path(str(latest["dir"]))
        db_dir = p if p.is_dir() else None
    if db_dir is None and export_root.is_dir():
        runs = sorted((d for d in export_root.iterdir() if d.is_dir()),
                      key=lambda d: d.name, reverse=True)
        db_dir = runs[0] if runs else None
    if db_dir is None:
        out["note"] = "no export bundle (run meta_display_db_export.py export first)"
        return out
    out["db"] = db_dir.name
    out["dir"] = rel(root, db_dir)
    manifest = read_json(db_dir / "manifest.json") or {}
    table_meta = {}
    for t in manifest.get("tables") or []:
        if isinstance(t, dict) and t.get("sqliteName"):
            table_meta[t["sqliteName"]] = t.get("rowCount")
    con = open_export_sqlite(db_dir)
    if con is None:
        out["note"] = f"no export.sqlite under {rel(root, db_dir)}"
        return out
    try:
        names = [r[0] for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
        skipped = names[max_tables:]
        for name in names[:max_tables]:
            try:
                if name in table_meta and table_meta[name] is not None:
                    count = table_meta[name]
                else:
                    count = con.execute(
                        f'SELECT COUNT(*) FROM "{name.replace(chr(34), chr(34) * 2)}"'
                    ).fetchone()[0]
            except sqlite3.Error:
                count = None
            out["tables"].append({"name": name, "rowCount": count})
            try:
                cur = con.execute(
                    f'SELECT * FROM "{name.replace(chr(34), chr(34) * 2)}" '
                    f"ORDER BY rowid DESC LIMIT ?", (tail_rows,))
                cols = [d[0] for d in (cur.description or [])]
                rows = [list(r) for r in reversed(cur.fetchall())]
            except sqlite3.Error as e:
                out["tails"][name] = {"error": str(e)[:160]}
                continue
            out["tails"][name] = {
                "columns": cols,
                "rows": [[sanitize_cell(c, v) for c, v in zip(cols, r)]
                         for r in rows],
                "tailRowCount": len(rows),
            }
        if skipped:
            out["tablesSkipped"] = skipped
    finally:
        con.close()
    out["note"] = ("export.sqlite copy only; live H2 never touched. "
                   "Sensitive columns are preview(<=80)+sha12, not full bodies.")
    return out


def iter_ndjson_tail(path: Path, read_bytes: int = READ_BYTES):
    try:
        size = path.stat().st_size
        with path.open("rb") as fh:
            if size > read_bytes:
                fh.seek(-read_bytes, os.SEEK_END)
            raw = fh.read().decode("utf-8", "replace")
    except OSError:
        return
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rec = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(rec, dict):
            yield rec


def ts_to_iso(value) -> str:
    if isinstance(value, (int, float)):
        try:
            return datetime.fromtimestamp(float(value), timezone.utc).isoformat()
        except (OverflowError, OSError, ValueError):
            return str(value)
    return str(value or "")


def collect_events(root: Path, tail_lines: int) -> list[dict]:
    logs = root / "logs"
    rows = []
    patterns = (("debug-events", "debug-events*.ndjson", "debug-event"),
                ("trace", "trace*.ndjson", "trace"))
    for _group, pat, kind in patterns:
        for path in sorted(logs.glob(pat)):
            for rec in iter_ndjson_tail(path):
                detail = rec.get("data") if "data" in rec else rec.get("kv")
                detail_text = ""
                if detail is not None:
                    try:
                        detail_text = redact(json.dumps(
                            detail, ensure_ascii=False))[:DETAIL_CHARS]
                    except (TypeError, ValueError):
                        detail_text = ""
                rows.append({
                    "ts": ts_to_iso(rec.get("ts")),
                    "kind": kind,
                    "file": rel(root, path),
                    "probe": rec.get("probe") or rec.get("type"),
                    "level": rec.get("level"),
                    "stage": rec.get("stage"),
                    "message": redact(str(rec.get("message") or ""))[:MESSAGE_CHARS] or None,
                    "where": rec.get("where"),
                    "sid": rec.get("sid"),
                    "traceId": rec.get("traceId") or rec.get("trace"),
                    "requestId": rec.get("requestId"),
                    "error": redact(str(rec.get("error") or ""))[:MESSAGE_CHARS] or None,
                    "detail": detail_text or None,
                })
    rows.sort(key=lambda r: r["ts"])
    return rows[-tail_lines:] if len(rows) > tail_lines else rows


def collect_journals(root: Path, use_subprocess: bool) -> list[dict]:
    slim = lambda j, d: {  # taskId/purpose 중심 — 본문·스코프 상세는 복사하지 않는다.
        "taskId": j.get("taskId") or d,
        "agent": j.get("agent"),
        "purpose": redact(str(j.get("purpose") or ""))[:160],
        "status": j.get("status"),
        "startedAtUtc": j.get("startedAtUtc"),
        "updatedAtUtc": j.get("updatedAtUtc"),
        "eventCount": j.get("eventCount"),
        "scopeCount": j.get("scopeCount"),
    }
    script = root / "scripts" / "work_journal.py"
    if use_subprocess and script.is_file():
        try:
            proc = subprocess.run(
                [sys.executable, "-B", str(script), "list", "--active"],
                capture_output=True, text=True, timeout=30, cwd=str(root))
            if proc.returncode == 0 and proc.stdout.strip():
                data = json.loads(proc.stdout)
                tasks = data.get("tasks") if isinstance(data, dict) else data
                if isinstance(tasks, list):
                    return [slim(t, None) for t in tasks if isinstance(t, dict)]
        except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError):
            pass
    base = root / "data" / "agent-handoff" / "codex-autonomy"
    out = []
    for jf in sorted(base.glob("*/journal.json")) if base.is_dir() else []:
        j = read_json(jf)
        if isinstance(j, dict) and str(j.get("status") or "") in (
                "in_progress", "open", "active"):
            out.append(slim(j, jf.parent.name))
    out.sort(key=lambda r: str(r.get("updatedAtUtc") or ""), reverse=True)
    return out


# --- bundle ----------------------------------------------------------------

def build_bundle(root: Path, since_hours: float = DEFAULT_SINCE_HOURS, *,
                 max_sessions: int = DEFAULT_MAX_SESSIONS,
                 tail_lines: int = DEFAULT_TAIL_LINES,
                 db_tail_rows: int = DEFAULT_DB_TAIL_ROWS,
                 db_max_tables: int = DEFAULT_DB_MAX_TABLES,
                 use_subprocess: bool = True) -> dict:
    root = Path(root).resolve()
    sources = {
        "chatSessionTraces": (root / "var" / "debug" / "chat-session-traces").is_dir(),
        "ragLauncherLatest": (root / "var" / "rag-launcher" / "LATEST.json").is_file(),
        "metaDbExport": (root / "var" / "meta-display-db" / "export").is_dir(),
        "debugEventsNdjson": bool(glob.glob(str(root / "logs" / "debug-events*.ndjson"))),
        "traceNdjson": bool(glob.glob(str(root / "logs" / "trace*.ndjson"))),
        "journals": (root / "data" / "agent-handoff" / "codex-autonomy").is_dir(),
    }
    sessions_coverage = {}
    sessions = collect_sessions(root, since_hours, max_sessions, stats=sessions_coverage)
    bundle = {
        "schema": SCHEMA,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "root": str(root),
        "sinceHours": since_hours,
        "sources": sources,
        "gaps": [
            "prompt/response bodies are absent by design (sanitized lanes only)",
            "live admin DebugEventStore is not queried; bundles are offline",
            "meta-db rows come from export.sqlite at its export time, not live",
        ],
        "sessions": sessions,
        "sessionsCoverage": sessions_coverage,
        "ragTrail": collect_rag_trail(root, use_subprocess),
        "dbExport": collect_db_export(root, db_tail_rows, db_max_tables),
        "events": collect_events(root, tail_lines),
        "journals": collect_journals(root, use_subprocess),
        "analyzer": {
            "tool": "scripts/request_trace_analyze.py",
            "usage": "python -B scripts/request_trace_analyze.py <file-with-request.events-lines>",
            "note": "expects seq|t+<ms>|stage|event|fields|remain= event lines; "
                    "sanitized ndjson tails carry ids (sid/requestId) not bodies",
        },
    }
    return bundle


def scan_bundle_text(text: str) -> list[str]:
    return secret_residue(text)


def write_bundle(root: Path, bundle: dict, out_root: Path | None = None) -> dict:
    out_base = out_root or (root / "var" / "debug" / "query-flow")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    dest = out_base / (stamp + "-" + sha12(bundle["generatedAtUtc"])[:6])
    dest.mkdir(parents=True, exist_ok=True)
    bundle_path = dest / "bundle.json"
    text = json.dumps(bundle, ensure_ascii=False, indent=1) + "\n"
    # 1차 마스킹은 collector에서 완료; 여기서는 방어선 — 재검사 후에도 남으면 실패.
    residue = scan_bundle_text(text)
    if residue:
        text = redact(text)
        residue = scan_bundle_text(text)
    bundle_path.write_text(text, encoding="utf-8")
    result = {"bundleDir": rel(root, dest), "bundleJson": rel(root, bundle_path),
              "clean": not residue, "residue": residue}
    if not residue:
        (dest / "NO_SECRETS").write_text(json.dumps({
            "schema": "awx.query-flow-no-secrets.v1",
            "scannedAtUtc": datetime.now(timezone.utc).isoformat(),
            "filesScanned": ["bundle.json"],
            "checks": ["key-assign", "auth-scheme", "provider-key",
                       "pem-block", "banned-words"],
            "result": "clean",
        }, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    latest = {
        "schema": "awx.query-flow-latest.v1",
        "bundleDir": rel(root, dest),
        "bundleJson": rel(root, bundle_path),
        "generatedAtUtc": bundle["generatedAtUtc"],
        "counts": {
            "sessions": len(bundle.get("sessions") or []),
            "events": len(bundle.get("events") or []),
            "journals": len(bundle.get("journals") or []),
            "dbTables": len((bundle.get("dbExport") or {}).get("tables") or []),
        },
        "noSecrets": not residue,
    }
    out_base.mkdir(parents=True, exist_ok=True)
    (out_base / "latest.json").write_text(
        json.dumps(latest, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    result["latest"] = rel(root, out_base / "latest.json")
    result["counts"] = latest["counts"]
    return result


def main(argv=None) -> int:
    p = argparse.ArgumentParser(
        prog="query_flow_notepad_bundle.py",
        description="Build a redacted query-flow bundle for the notepad viewer.")
    p.add_argument("--root", default=None,
                   help="repo root (default: script's parent dir)")
    p.add_argument("--since-hours", type=float, default=DEFAULT_SINCE_HOURS)
    p.add_argument("--max-sessions", type=int, default=DEFAULT_MAX_SESSIONS)
    p.add_argument("--tail-lines", type=int, default=DEFAULT_TAIL_LINES)
    p.add_argument("--db-tail-rows", type=int, default=DEFAULT_DB_TAIL_ROWS)
    p.add_argument("--db-max-tables", type=int, default=DEFAULT_DB_MAX_TABLES)
    p.add_argument("--offline", action="store_true",
                   help="skip powershell/work_journal subprocesses; direct reads only")
    p.add_argument("--out", default=None,
                   help="bundle parent dir (default: <root>/var/debug/query-flow)")
    args = p.parse_args(argv)

    root = Path(args.root).resolve() if args.root else \
        Path(__file__).resolve().parents[1]
    bundle = build_bundle(
        root, args.since_hours,
        max_sessions=args.max_sessions, tail_lines=args.tail_lines,
        db_tail_rows=args.db_tail_rows, db_max_tables=args.db_max_tables,
        use_subprocess=not args.offline)
    out_root = Path(args.out).resolve() if args.out else None
    result = write_bundle(root, bundle, out_root)
    payload = {"schema": "awx.query-flow-bundle-result.v1", **result}
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0 if result["clean"] else 5


if __name__ == "__main__":
    raise SystemExit(main())
