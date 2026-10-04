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

``export`` writes ``var/debug/chat-session-traces/export/export-<16hex>/`` with
``records.json`` + ``manifest.json`` and refreshes ``export/latest.json``.
Display DB context stays a related link to the existing
meta_display_db_export lane (no live JDBC to H2).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

SCHEMA = "awx.chat-session-trace.v1"
DEFAULT_SINCE_HOURS = 24
HASH12_RE = re.compile(r"^[0-9a-f]{12}$")
EXPORT_NAME_RE = re.compile(r"^export-[0-9a-f]{16}$")
MAX_FILES = 5000
MAX_TOTAL_BYTES = 256 * 1024 * 1024
MAX_LINE_BYTES = 1024 * 1024
MAX_ROWS = 200000
MAX_PATHS = MAX_FILES * 2


class NonStandardJson(ValueError):
    """JSON extensions and duplicate object keys have separate coverage."""


def _reject_constant(_value):
    raise NonStandardJson("non-standard-json-constant")


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise NonStandardJson("duplicate-json-key")
        result[key] = value
    return result


STRICT_JSON = json.JSONDecoder(parse_constant=_reject_constant,
                               object_pairs_hook=_unique_object)


def repo_root(arg: str | None) -> Path:
    if arg:
        return Path(arg).resolve()
    return Path(__file__).resolve().parents[1]


def trace_dir(root: Path) -> Path:
    return root / "var" / "debug" / "chat-session-traces"


def hash12(value: str) -> str:
    """Same digest as SafeRedactor.hash12: first 12 hex of SHA-256(UTF-8, trimmed)."""
    return hashlib.sha256(value.strip().encode("utf-8")).hexdigest()[:12]


def event_time(value) -> datetime | None:
    """Unknown or timezone-less timestamps are not assigned an invented zone."""
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.astimezone(timezone.utc) if parsed.utcoffset() is not None else None
    except (ValueError, OverflowError):
        return None


def _trace_files(base: Path, stats: dict, max_paths: int):
    # Stream directory entries too; no unbounded sorted(glob(...)) inventory.
    def bounded(entries):
        while stats["paths_visited"] < max_paths:
            try:
                entry = next(entries)
            except StopIteration:
                return
            stats["paths_visited"] += 1
            yield entry
        stats["scan_limit_hit"] = True
    try:
        reject_reparse(base)
        with os.scandir(base) as days:
            for day in bounded(days):
                if not re.fullmatch(r"[0-9]{8}", day.name):
                    continue
                try:
                    reject_reparse(Path(day.path))
                    if not day.is_dir(follow_symlinks=False):
                        continue
                    with os.scandir(day.path) as files:
                        for file in bounded(files):
                            if file.name.endswith(".json"):
                                yield Path(file.path)
                except OSError:
                    stats["unreadable_files"] += 1
    except FileNotFoundError:
        stats["complete"] = False
    except OSError:
        stats["unreadable_files"] += 1


def _file_signature(info):
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns


def iter_records(root: Path, since_hours: float | None, *, caps=None, stats=None):
    """Yield bounded JSONL rows; coverage is final when the iterator is exhausted."""
    limits = dict(max_files=MAX_FILES, max_total_bytes=MAX_TOTAL_BYTES,
                  max_line_bytes=MAX_LINE_BYTES, max_rows=MAX_ROWS, max_paths=MAX_PATHS)
    if caps is not None:
        for key, value in caps.items():
            if key not in limits or type(value) is not int or value <= 0:
                raise ValueError("invalid-scan-cap")
            limits[key] = value
    stats = stats if stats is not None else {}
    stats.clear()
    stats.update({k: 0 for k in ("files_scanned", "bytes_read", "rows_scanned",
                                "rows_selected", "parse_error", "partial_tail",
                                "oversized_line", "unknown_timestamp", "unreadable_files",
                                "changed_files")})
    stats["non_standard_json"] = 0
    stats["paths_visited"] = 0
    stats["source_cutoffs"] = {}
    stats.update(scan_limit_hit=False, complete=False)
    base = trace_dir(root)
    trace_available = base.is_dir()
    cutoff = (datetime.now(timezone.utc) - timedelta(hours=since_hours)
              if since_hours is not None else None)
    exhausted = False
    try:
        for file in _trace_files(base, stats, limits["max_paths"]):
            if (stats["files_scanned"] >= limits["max_files"]
                    or stats["bytes_read"] >= limits["max_total_bytes"]
                    or stats["rows_scanned"] >= limits["max_rows"]):
                stats["scan_limit_hit"] = True
                break
            stats["files_scanned"] += 1
            try:
                reject_reparse(file)
                with file.open("rb") as fh:
                    before = os.fstat(fh.fileno())
                    if not stat.S_ISREG(before.st_mode):
                        raise OSError("not-regular-file")
                    relative_file = file.relative_to(root).as_posix()
                    stats["source_cutoffs"][relative_file] = str(before.st_size)
                    position, line_number = 0, 0
                    def read_chunk():
                        nonlocal position
                        amount = min(limits["max_line_bytes"] + 1, before.st_size - position,
                                     limits["max_total_bytes"] - stats["bytes_read"])
                        if amount <= 0:
                            return b""
                        chunk = fh.readline(amount)
                        position += len(chunk)
                        stats["bytes_read"] += len(chunk)
                        return chunk
                    try:
                        while position < before.st_size:
                            if (stats["rows_scanned"] >= limits["max_rows"]
                                    or stats["bytes_read"] >= limits["max_total_bytes"]):
                                stats["scan_limit_hit"] = True
                                break
                            line_offset = position
                            line = read_chunk()
                            if not line:
                                break
                            line_number += 1
                            stats["rows_scanned"] += 1
                            if len(line) > limits["max_line_bytes"]:
                                stats["oversized_line"] += 1
                                stats["scan_limit_hit"] = True
                                # Drain a single oversized line in bounded pieces.
                                while not line.endswith(b"\n") and position < before.st_size:
                                    line = read_chunk()
                                    if not line:
                                        break
                                continue
                            if not line.endswith(b"\n") and position < before.st_size:
                                stats["scan_limit_hit"] = True
                                break
                            if not line.strip():
                                continue
                            try:
                                rec = STRICT_JSON.decode(line.decode("utf-8", errors="strict"))
                            except NonStandardJson:
                                stats["non_standard_json"] += 1
                                continue
                            except UnicodeDecodeError:
                                stats["parse_error"] += 1
                                continue
                            except (ValueError, RecursionError):
                                stats["parse_error" if line.endswith(b"\n") else "partial_tail"] += 1
                                continue
                            if not isinstance(rec, dict):
                                stats["parse_error"] += 1
                                continue
                            timestamp = event_time(rec.get("ts"))
                            if timestamp is None:
                                stats["unknown_timestamp"] += 1
                                continue
                            if cutoff is not None and timestamp < cutoff:
                                continue
                            rec["_file"] = relative_file
                            rec["_line"] = line_number
                            rec["_offset"] = str(line_offset)
                            rec["_sha12"] = hashlib.sha256(line).hexdigest()[:12]
                            stats["rows_selected"] += 1
                            yield file, rec
                    finally:
                        handle_after, path_after = os.fstat(fh.fileno()), file.stat()
                        if (_file_signature(before) != _file_signature(handle_after)
                                or _file_signature(before) != _file_signature(path_after)
                                or (position != before.st_size and not stats["scan_limit_hit"])):
                            stats["changed_files"] += 1
            except (OSError, RuntimeError):
                stats["unreadable_files"] += 1
        else:
            exhausted = True
    finally:
        trace_exists = base.is_dir()
        stats["complete"] = exhausted and trace_available and trace_exists and not (
            stats["scan_limit_hit"] or any(stats[k] for k in (
                "parse_error", "partial_tail", "oversized_line", "unknown_timestamp",
                "unreadable_files", "changed_files", "non_standard_json")))
        if not trace_available and not any(stats[k] for k in (
                "files_scanned", "paths_visited", "unreadable_files")):
            for key, value in stats.items():
                if type(value) is int:
                    stats[key] = None
            stats["source_cutoffs"] = None


def id_candidates(query: str) -> set[str]:
    """All forms a query id may take: raw, hash12, hash:<h12>."""
    q = query.strip()
    out = {q}
    canonical, _form = query_identity(q)
    out.update((canonical, "hash:" + canonical))
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


def find_records(root: Path, query: str, since_hours: float | None = None, *, caps=None, stats=None):
    candidates = id_candidates(query)
    return [(f, r) for f, r in iter_records(root, since_hours, caps=caps, stats=stats)
            if record_matches(r, candidates)]


def export_root(root: Path) -> Path:
    return trace_dir(root) / "export"


def read_latest_pointer(root: Path):
    latest_file = export_root(root) / "latest.json"
    try:
        reject_reparse(latest_file)
        if not latest_file.is_file():
            return None
        with latest_file.open("rb") as fh:
            data = fh.read(4097)
        if len(data) > 4096:
            raise ValueError("oversized-pointer")
        latest = STRICT_JSON.decode(data.decode("utf-8", errors="strict"))
        if not isinstance(latest, dict) or latest.get("schema") != "awx.chat-session-trace-latest.v2":
            return {"status": "legacy-pointer-not-disclosed"}
        query_hash, form = latest.get("queryHash"), latest.get("queryForm")
        stamp, count = event_time(latest.get("exportedAtUtc")), latest.get("recordCount")
        if (not isinstance(query_hash, str) or not HASH12_RE.fullmatch(query_hash)
                or form not in ("raw", "hash", "bare-hash", "stem")
                or stamp is None or type(count) is not int or count < 0):
            raise ValueError("invalid-pointer")
        # Reconstruct the path from the digest; never echo arbitrary pointer text.
        return {"schema": latest["schema"], "queryHash": query_hash, "queryForm": form,
                "exportDir": str((export_root(root) / safe_export_name(query_hash)).relative_to(root)),
                "exportedAtUtc": stamp.isoformat(), "recordCount": count}
    except (OSError, ValueError, RuntimeError):
        return {"error": "unreadable-latest-json"}


def cmd_status(root: Path) -> int:
    base = trace_dir(root)
    coverage = {}
    records = list(iter_records(root, None, stats=coverage))
    days = sorted({f.parent.name for f, _r in records})
    latest_ts = max((str(r.get("ts") or "") for _f, r in records), default=None)
    exports_root = export_root(root)
    exports = sorted(p.name for p in exports_root.iterdir()
                     if p.is_dir() and EXPORT_NAME_RE.fullmatch(p.name)) if exports_root.is_dir() else []
    payload = {
        "schema": "awx.chat-session-trace-status.v1",
        "root": str(root),
        "traceDir": str(base),
        "traceDirExists": base.is_dir(),
        "recordCount": len(records) if coverage["rows_selected"] is not None else None,
        "coverage": coverage,
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


def cmd_list(root: Path, since_hours: float, *, as_json: bool = False) -> int:
    coverage = {}
    rows = list(iter_records(root, since_hours, stats=coverage))
    if as_json:
        print(json.dumps({"records": [r for _, r in rows], "coverage": coverage},
                         indent=2, ensure_ascii=False))
        return 0
    print("# coverage=" + json.dumps(coverage, sort_keys=True))
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
        print(f"no session trace found (queryHash={query_identity(query)[0]})", file=sys.stderr)
        return 4
    for _file, rec in matches:
        print(json.dumps(rec, indent=2, ensure_ascii=False, sort_keys=False))
    return 0


def query_identity(query: str) -> tuple[str, str]:
    q = query.strip()
    if q.startswith("hash:") and HASH12_RE.match(q[5:].lower()):
        return q[5:].lower(), "hash"
    if HASH12_RE.match(q.lower()):
        return q.lower(), "bare-hash"
    if q[:2] in ("s-", "r-") and HASH12_RE.fullmatch(q[2:].lower()):
        return q[2:].lower(), "stem"
    return hash12(q), "raw"


def safe_export_name(query: str, matches=None) -> str:
    canonical, _form = query_identity(query)
    return "export-" + hashlib.sha256(canonical.encode("ascii")).hexdigest()[:16]


def reject_reparse(path: Path) -> None:
    """Check lexical ancestors too: resolve alone would hide junction traversal."""
    for part in (path, *path.parents):
        try:
            info = part.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or (
                getattr(info, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT):
            raise OSError("reparse-path-rejected")


def validate_export_paths(base: Path, dest: Path) -> None:
    reject_reparse(dest)
    if dest == base or not dest.resolve().is_relative_to(base.resolve()):
        raise OSError("export-boundary-rejected")
    # Existing output files can themselves be links, including latest.json.
    for path in (dest / "records.json", dest / "manifest.json", base / "latest.json"):
        reject_reparse(path)


def write_export_json(path: Path, payload, base: Path, dest: Path) -> None:
    validate_export_paths(base, dest)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def cmd_export(root: Path, query: str) -> int:
    coverage = {}
    matches = find_records(root, query, stats=coverage)
    if not matches:
        print(f"no session trace found (queryHash={query_identity(query)[0]})", file=sys.stderr)
        return 4
    name = safe_export_name(query, matches)
    out_dir = export_root(root) / name
    try:
        validate_export_paths(export_root(root), out_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        validate_export_paths(export_root(root), out_dir)
    except (OSError, ValueError, RuntimeError):
        print("export refused: unsafe or unavailable output path", file=sys.stderr)
        return 3
    records = []
    for _file, rec in matches:
        clean = {k: v for k, v in rec.items() if k not in ("_file", "_line")}
        clean["sourceFile"] = rec.get("_file")
        records.append(clean)
    manifest = {
        "schema": "awx.chat-session-trace-export.v2",
        "exportedAtUtc": datetime.now(timezone.utc).isoformat(),
        "queryHash": query_identity(query)[0],
        "queryForm": query_identity(query)[1],
        "exportDir": str(out_dir.relative_to(root)),
        "recordCount": len(records),
        "coverage": coverage,
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
    # 최신 export 포인터 — meta_display_db_export.py 의 latest.json 관례와 동일.
    latest = {
        "schema": "awx.chat-session-trace-latest.v2",
        "queryHash": manifest["queryHash"],
        "queryForm": manifest["queryForm"],
        "exportDir": str(out_dir.relative_to(root)),
        "exportedAtUtc": manifest["exportedAtUtc"],
        "recordCount": len(records),
    }
    try:
        for path, data in ((out_dir / "records.json", records),
                           (out_dir / "manifest.json", manifest),
                           (export_root(root) / "latest.json", latest)):
            write_export_json(path, data, export_root(root), out_dir)
    except (OSError, ValueError, RuntimeError):
        print("export refused: unsafe or unavailable output path", file=sys.stderr)
        return 3
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
    p_list.add_argument("--json", action="store_true", help="JSON records with scan coverage")
    p_show = sub.add_parser("show", help="show records for a sessionId or runId")
    p_show.add_argument("id")
    p_export = sub.add_parser("export", help="export a shared evidence bundle")
    p_export.add_argument("id")
    args = parser.parse_args(argv)

    root = repo_root(args.root)
    if args.cmd == "status":
        return cmd_status(root)
    if args.cmd == "list":
        return cmd_list(root, args.since_hours, as_json=args.json)
    if args.cmd == "show":
        return cmd_show(root, args.id)
    if args.cmd == "export":
        return cmd_export(root, args.id)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
