#!/usr/bin/env python3
"""Shared chat/display session debug evidence reader (Grok/Devin/Codex/Cline).

The server appends one sanitized JSON object per completed/failed chat run to
``var/debug/chat-session-traces/YYYYMMDD/<id>.json`` (JSONL within the file).
Identifiers are stored as ``hash:<sha256-12>``; ``show``/``export`` accept a raw
sessionId, a raw run token, a ``hash:`` value, a bare 12-hex hash, or a file
stem. Prompt/response bodies and secret values are never present in traces.

Usage:
  chat_session_debug_export.py status [--root DIR]
  chat_session_debug_export.py list [--since-hours N] [--root DIR]
  chat_session_debug_export.py show <sessionId|runId> [--root DIR]
  chat_session_debug_export.py export <id> [--root DIR]

``export`` writes ``var/debug/chat-session-traces/export/<id>/`` with
``records.json`` + ``manifest.json`` and refreshes ``export/latest.json``.
Display DB context stays a related link to the existing
meta_display_db_export lane (no live JDBC to H2).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

SCHEMA = "awx.chat-session-trace.v1"
DEFAULT_SINCE_HOURS = 24
HASH12_RE = re.compile(r"^[0-9a-f]{12}$")
# Windows 디렉터리 이름에는 ':'가 불가 — hash:<h12> 는 hash- 접두로 재작성한다.
SAFE_ID_RE = re.compile(r"^[A-Za-z0-9_.-]{1,128}$")


def repo_root(arg: str | None) -> Path:
    if arg:
        return Path(arg).resolve()
    return Path(__file__).resolve().parents[1]


def trace_dir(root: Path) -> Path:
    return root / "var" / "debug" / "chat-session-traces"


def hash12(value: str) -> str:
    """Same digest as SafeRedactor.hash12: first 12 hex of SHA-256(UTF-8, trimmed)."""
    return hashlib.sha256(value.strip().encode("utf-8")).hexdigest()[:12]


def iter_records(root: Path, since_hours: float | None):
    """Yield (file_path, record_dict) for each JSONL row under the trace dir."""
    base = trace_dir(root)
    if not base.is_dir():
        return
    cutoff = None
    if since_hours is not None:
        cutoff = datetime.now(timezone.utc) - timedelta(hours=since_hours)
    for day_dir in sorted(base.iterdir()):
        if not day_dir.is_dir() or not day_dir.name.isdigit() or len(day_dir.name) != 8:
            continue
        for file in sorted(day_dir.glob("*.json")):
            try:
                if cutoff is not None and datetime.fromtimestamp(
                        file.stat().st_mtime, timezone.utc) < cutoff:
                    continue
                with file.open("r", encoding="utf-8", errors="replace") as fh:
                    for line in fh:
                        line = line.strip()
                        if not line:
                            continue
                        try:
                            rec = json.loads(line)
                        except json.JSONDecodeError:
                            continue
                        if isinstance(rec, dict):
                            rec["_file"] = str(file.relative_to(root))
                            yield file, rec
            except OSError:
                continue


def id_candidates(query: str) -> set[str]:
    """All forms a query id may take: raw, hash12, hash:<h12>."""
    q = query.strip()
    out = {q}
    if q.startswith("hash:"):
        out.add(q[5:])
    if HASH12_RE.match(q.lower()):
        out.add("hash:" + q.lower())
        out.add(q.lower())
    else:
        h = hash12(q)
        out.add(h)
        out.add("hash:" + h)
    return out


def record_matches(rec: dict, candidates: set[str]) -> bool:
    for field in ("sessionId", "runId", "recordId"):
        value = rec.get(field)
        if value is None:
            continue
        v = str(value).strip()
        if v in candidates or (v.startswith("hash:") and v[5:] in candidates):
            return True
    stem = Path(str(rec.get("_file", ""))).stem
    if stem in candidates or stem.split("-", 1)[-1] in candidates:
        return True
    return False


def find_records(root: Path, query: str, since_hours: float | None = None):
    candidates = id_candidates(query)
    return [(f, r) for f, r in iter_records(root, since_hours)
            if record_matches(r, candidates)]


def export_root(root: Path) -> Path:
    return trace_dir(root) / "export"


def read_latest_pointer(root: Path):
    latest_file = export_root(root) / "latest.json"
    if not latest_file.is_file():
        return None
    try:
        return json.loads(latest_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"error": "unreadable-latest-json"}


def cmd_status(root: Path) -> int:
    base = trace_dir(root)
    records = list(iter_records(root, None)) if base.is_dir() else []
    days = sorted({f.parent.name for f, _r in records})
    latest_ts = max((str(r.get("ts") or "") for _f, r in records), default=None)
    exports_root = export_root(root)
    exports = sorted(p.name for p in exports_root.iterdir()
                     if p.is_dir()) if exports_root.is_dir() else []
    payload = {
        "schema": "awx.chat-session-trace-status.v1",
        "root": str(root),
        "traceDir": str(base),
        "traceDirExists": base.is_dir(),
        "recordCount": len(records),
        "days": days,
        "latestRecordTs": latest_ts,
        "exportRoot": str(exports_root),
        "exports": exports,
        "latest": read_latest_pointer(root),
        "writerKillSwitch": "abandonware.debug.chat-session-traces.enabled=false",
        "policy": "sanitized per-run records; share export paths, not record bodies",
    }
    print(json.dumps(payload, indent=2, ensure_ascii=False))
    return 0 if base.is_dir() else 2


def cmd_list(root: Path, since_hours: float) -> int:
    rows = list(iter_records(root, since_hours))
    print(f"# {len(rows)} session trace record(s) under {trace_dir(root)} "
          f"(since {since_hours}h)")
    for _file, rec in rows:
        print("{ts}  {surface:7} {sid}  {rid}  model={model}  err={err}  "
              "fb={fb}  file={file}".format(
                  ts=str(rec.get("ts", "?"))[:19],
                  surface=str(rec.get("surface", "?")),
                  sid=str(rec.get("sessionId") or "-"),
                  rid=str(rec.get("runId") or "-"),
                  model=str(rec.get("effectiveModel") or "-"),
                  err=str(rec.get("errorClass") or "-"),
                  fb=str(rec.get("fallbackCount", "-")),
                  file=str(rec.get("_file", "-"))))
    return 0


def cmd_show(root: Path, query: str) -> int:
    matches = find_records(root, query)
    if not matches:
        print(f"no session trace found for id '{query}'", file=sys.stderr)
        return 4
    for _file, rec in matches:
        print(json.dumps(rec, indent=2, ensure_ascii=False, sort_keys=False))
    return 0


def safe_export_name(query: str, matches) -> str:
    q = query.strip()
    if q.startswith("hash:") and HASH12_RE.match(q[5:].lower()):
        return "hash-" + q[5:].lower()
    if SAFE_ID_RE.match(q):
        return q
    if HASH12_RE.match(q.lower()):
        return q.lower()
    # Raw id -> stable hashed directory name.
    return "id-" + hash12(q)


def cmd_export(root: Path, query: str) -> int:
    matches = find_records(root, query)
    if not matches:
        print(f"no session trace found for id '{query}'", file=sys.stderr)
        return 4
    name = safe_export_name(query, matches)
    out_dir = export_root(root) / name
    out_dir.mkdir(parents=True, exist_ok=True)
    records = []
    for _file, rec in matches:
        clean = {k: v for k, v in rec.items() if k != "_file"}
        clean["sourceFile"] = rec.get("_file")
        records.append(clean)
    (out_dir / "records.json").write_text(
        json.dumps(records, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    manifest = {
        "schema": "awx.chat-session-trace-export.v1",
        "exportedAtUtc": datetime.now(timezone.utc).isoformat(),
        "query": query,
        "exportDir": str(out_dir.relative_to(root)),
        "recordCount": len(records),
        "sourceFiles": sorted({str(r.get("sourceFile")) for r in records}),
        "sanitization": [
            "sessionId/runId stored as hash:<sha256-12> only",
            "no prompt bodies, response bodies, tokens, or API keys",
            "traceKeys are key names only, never values",
        ],
        "related": {
            "metaDisplayDbExport": "var/meta-display-db/export/",
            "metaDisplayDbExportCli": "scripts/meta_display_db_export.py",
            "note": "Display conversation DB stays a separate read-only lane; "
                    "never open the live H2 file while the JVM holds it.",
        },
    }
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    # 최신 export 포인터 — meta_display_db_export.py 의 latest.json 관례와 동일.
    latest = {
        "schema": "awx.chat-session-trace-latest.v1",
        "query": query,
        "exportDir": str(out_dir.relative_to(root)),
        "exportedAtUtc": manifest["exportedAtUtc"],
        "recordCount": len(records),
    }
    (export_root(root) / "latest.json").write_text(
        json.dumps(latest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(str(out_dir.relative_to(root)))
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="chat_session_debug_export.py",
        description="Shared chat/display session debug evidence reader.")
    parser.add_argument("--root", default=None,
                        help="repo root (default: script's parent dir)")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status", help="JSON status of trace dir + exports (no mutate)")
    p_list = sub.add_parser("list", help="list recent session traces")
    p_list.add_argument("--since-hours", type=float, default=DEFAULT_SINCE_HOURS)
    p_show = sub.add_parser("show", help="show records for a sessionId or runId")
    p_show.add_argument("id")
    p_export = sub.add_parser("export", help="export a shared evidence bundle")
    p_export.add_argument("id")
    args = parser.parse_args(argv)

    root = repo_root(args.root)
    if args.cmd == "status":
        return cmd_status(root)
    if args.cmd == "list":
        return cmd_list(root, args.since_hours)
    if args.cmd == "show":
        return cmd_show(root, args.id)
    if args.cmd == "export":
        return cmd_export(root, args.id)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
