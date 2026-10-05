#!/usr/bin/env python3
"""server_trace_digest.py -- one-command read of scattered server traces.

Folds var/rag-launcher run logs, logs/*.ndjson|jsonl, dev-reload logs and
port-lease traces into a normalized awx.server_trace_digest.v1 digest and
writes var/agent-trace/latest.json + latest.md (+ history/). Read-only: it
never starts, stops or probes the server, never opens secret files, and every
sampled message passes scripts/log_redact.redact_text first.

Exit contract (same meaning as read_rag_debug_trail):
  0 = no non-noise ERROR/exception inside the window (WARN-only -> warn)
  3 = no run / no readable sources (verdict=no_data)
  4 = non-noise ERROR/exception OR unexpected_exit / stage_fail
  1 = tool error (bad args, python < 3.9)

Interpreter: $env:AWX_PYTHON if set, else PATH python; stdlib only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

if sys.version_info < (3, 9):
    print("server_trace_digest: python>=3.9 required (set AWX_PYTHON or fix PATH)",
          file=sys.stderr)
    raise SystemExit(1)

try:                                    # imported as scripts.server_trace_digest
    from scripts.log_redact import redact_text
except Exception:                       # run as python -B scripts/server_trace_digest.py
    try:
        from log_redact import redact_text
    except Exception:
        redact_text = None
try:
    from scripts.awx_paths import resolve as _awx_resolve
except Exception:
    try:
        from awx_paths import resolve as _awx_resolve
    except Exception:
        _awx_resolve = None

SCHEMA = "awx.server_trace_digest.v1"
KST = timezone(timedelta(hours=9))
ROOT = Path(__file__).resolve().parents[1]

LOGBACK_RE = re.compile(
    r"^(\d{4}-\d{2}-\d{2}T[\d:.]+[+-]\d{4})\s+([A-Z]+)\s+\[(.*?)\]\s+(\S+)\s+-\s?(.*)$")
STACK_LINE_RE = re.compile(
    r"^(\s+at\s+[\w.$]+\([^)]*\)|\s+\.\.\.\s*\d+\s+more|\s*Caused by:|\s*Suppressed:|"
    r"[\w.$]+(?:Exception|Error|Throwable)\b)")
GRADLE_RE = re.compile(
    r"^(> Task|FAILURE:|BUILD (?:FAILED|SUCCESSFUL)|\d+ actionable tasks?|"
    r"Execution failed for task|Process '.+' finished with non-zero exit value)")
LAUNCHER_RE = re.compile(
    r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+\[([A-Z-]+)\]\s+([A-Z]+)\s*(.*)$")
DEVRELOAD_RE = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+\[DEV-RELOAD\]\s*(.*)$")
APP_FRAME_RE = re.compile(r"at\s+((?:com\.example|com\.abandonware|ai\.abandonware)[\w.$]+)\(")
ANY_FRAME_RE = re.compile(r"at\s+([\w.$]+)\(")
EXC_RE = re.compile(r"([\w.$]+(?:Exception|Error|Throwable))\b")
CAUSED_RE = re.compile(r"Caused by:\s+([\w.$]+)")
EXIT_RE = re.compile(r"finished with non-zero exit value\s+(-?\d+)")
STOP_REST_RE = re.compile(r"^pid=(\d+)\s+process=(\S+)\s+role=(\S+)(?:\s+(.*))?$")
RUN_DIR_RE = re.compile(r"^\d{8}-\d{6}-[0-9a-fA-F]{6,}$")
UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", re.I)
HEX_RE = re.compile(r"\b[0-9a-f]{12,}\b", re.I)
WINPATH_RE = re.compile(r"[A-Za-z]:\\[^\s\"']+")
DIGITS_RE = re.compile(r"\d+")
FAIL_TAG_RE = re.compile(r"\b(FAIL|FAILED|ERROR|TIMEOUT)\b")

LEVELS = ("TRACE", "DEBUG", "INFO", "WARN", "ERROR")
LEVEL_RANK = {n: i for i, n in enumerate(LEVELS)}


# ---------------------------------------------------------------- time utils
def now_kst():
    return datetime.now(tz=KST)


def iso_kst(dt):
    return dt.astimezone(KST).strftime("%Y-%m-%dT%H:%M:%S+09:00") if dt else None


def parse_ts_logback(s):
    try:
        return datetime.strptime(s, "%Y-%m-%dT%H:%M:%S.%f%z")
    except ValueError:
        return None


def parse_ts_iso(s):
    if not isinstance(s, str) or not s:
        return None
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None


def parse_ts_epoch(v):
    try:
        return datetime.fromtimestamp(float(v), tz=timezone.utc)
    except (TypeError, ValueError, OverflowError, OSError):
        return None


def parse_ts_naive_kst(s):
    try:
        return datetime.strptime(s, "%Y-%m-%d %H:%M:%S").replace(tzinfo=KST)
    except ValueError:
        return None


def parse_since(value, default):
    """30m|2h|45s|1d or ISO timestamp -> aware datetime (KST assumed when naive)."""
    if not value:
        return default
    m = re.fullmatch(r"(\d+)\s*([smhd])", value.strip(), re.I)
    if m:
        mult = {"s": 1, "m": 60, "h": 3600, "d": 86400}[m.group(2).lower()]
        return now_kst() - timedelta(seconds=int(m.group(1)) * mult)
    dt = parse_ts_iso(value)
    if dt is None:
        dt = parse_ts_naive_kst(value)
    if dt is None:
        return default
    return dt if dt.tzinfo else dt.replace(tzinfo=KST)


# ---------------------------------------------------------------- io helpers
def read_tail(path, max_bytes):
    """Shared-read tail of a file; returns (text, truncated, total_bytes)."""
    p = Path(path)
    size = p.stat().st_size
    truncated = size > max_bytes
    with open(p, "rb") as fh:                       # shared read; never locked
        if truncated:
            fh.seek(-max_bytes, os.SEEK_END)
        raw = fh.read()
    text = raw.decode("utf-8", errors="replace")
    if truncated:
        nl = text.find("\n")
        text = text[nl + 1:] if nl >= 0 else ""     # drop partial first line
    return text, truncated, size


def rel_path(path, root):
    try:
        return Path(path).resolve().relative_to(Path(root).resolve()).as_posix()
    except ValueError:
        return str(path)


def src_entry(sources, name, path, root):
    p = Path(path)
    ent = {"name": name, "path": rel_path(p, root), "exists": p.is_file(),
           "bytes": 0, "mtime": None, "linesRead": 0, "truncated": False,
           "parseErrors": 0}
    if ent["exists"]:
        st = p.stat()
        ent["bytes"] = st.st_size
        ent["mtime"] = iso_kst(datetime.fromtimestamp(st.st_mtime, tz=KST))
    sources.append(ent)
    return ent


def redact(text):
    if redact_text is None or text is None:
        return None
    try:
        out, _counts = redact_text(str(text))
        return out
    except Exception:
        return None


def normalize_msg(msg):
    s = WINPATH_RE.sub("<path>", msg or "")
    s = UUID_RE.sub("<uuid>", s)
    s = HEX_RE.sub("<hex>", s)
    s = DIGITS_RE.sub("<n>", s)
    return s[:200]


def fingerprint(exc_class, top_frame, msg):
    base = "|".join([exc_class or "", top_frame or "", normalize_msg(msg)])
    return hashlib.sha1(base.encode("utf-8", "replace")).hexdigest()[:12]


# ------------------------------------------------------------------ context
class Ctx:
    def __init__(self, args, root):
        self.args = args
        self.root = root
        self.events = []
        self.signals = []
        self.counts = {"byLevel": {}, "byKind": {}, "bySource": {}, "noise": {}}
        self.sources = []
        self.dbg_json_dup = 0
        self.request_ids_parsed = 0
        self.request_ids_total = 0
        self.deadline = time.monotonic() + args.max_seconds
        self.stopped_early = False
        self.file_ts = None
        self.cur_path = ""

    def over_budget(self):
        if time.monotonic() > self.deadline:
            self.stopped_early = True
            return True
        return False

    def level(self, name):
        self.counts["byLevel"][name] = self.counts["byLevel"].get(name, 0) + 1

    def emit(self, ev):
        ent = dict(ev)
        ent.setdefault("noise", None)
        if ent.get("sample"):
            red = redact(ent["sample"])
            ent["sample"] = red[:300] if red else None
        elif ent.get("sample") is not None:
            ent["sample"] = None
        self.counts["bySource"][ent["source"]] = \
            self.counts["bySource"].get(ent["source"], 0) + 1
        self.counts["byKind"][ent["kind"]] = \
            self.counts["byKind"].get(ent["kind"], 0) + 1
        if ent["noise"]:
            self.counts["noise"][ent["noise"]] = \
                self.counts["noise"].get(ent["noise"], 0) + 1
        self.events.append(ent)

    def signal(self, name, **kw):
        sig = {"signal": name}
        sig.update(kw)
        self.signals.append(sig)


# ------------------------------------------------------------------ noise
def load_noise(root):
    cfg = Path(root) / "configs" / "server-trace-noise.json"
    if not cfg.is_file():
        cfg = ROOT / "configs" / "server-trace-noise.json"
    rules = []
    try:
        data = json.loads(cfg.read_text(encoding="utf-8"))
        for r in data.get("rules", []):
            rules.append({"id": r.get("id", "noise"),
                          "kinds": r.get("sourceKinds") or ["*"],
                          "re": re.compile(r.get("pattern", ""), re.I),
                          "noise": r.get("noise", r.get("id", "noise"))})
    except FileNotFoundError:
        pass
    except Exception as exc:
        print(f"[digest] noise config unreadable: {exc}", file=sys.stderr)
    return rules


def classify_noise(rules, source, text):
    for r in rules:
        if "*" not in r["kinds"] and source not in r["kinds"]:
            continue
        if r["re"].search(text or ""):
            return r["noise"]
    return None


# ------------------------------------------------------------------ parsers
def _block_event(ctx, src, m, block, line_no, noise_rules):
    level = m.group(2)
    sid = trace = None
    parts = [t for t in m.group(3).split(" ") if t != ""]
    if len(parts) >= 2:
        sid, trace = parts[0], parts[1]
    elif len(parts) == 1:
        if "-" in parts[0]:
            trace = parts[0]
        else:
            sid = parts[0]
    body = "\n".join([m.group(5)] + block)
    caused = CAUSED_RE.findall(body)
    exc_class = caused[0] if caused else None
    if exc_class is None:
        exm = EXC_RE.search(body)
        exc_class = exm.group(1) if exm else None
    frames = APP_FRAME_RE.findall(body) or ANY_FRAME_RE.findall(body)
    top_frame = frames[0] if frames else None
    has_stack = bool(block) or bool(EXC_RE.search(body))
    if level == "ERROR":
        kind = "exception" if has_stack else "error"
    elif has_stack:
        kind = "exception"
    else:
        kind = "warn" if level == "WARN" else "info"
    return {"ts": iso_kst(parse_ts_logback(m.group(1))), "source": src,
            "level": level, "kind": kind,
            "fingerprint": fingerprint(exc_class, top_frame, body),
            "logger": m.group(4), "exceptionClass": exc_class,
            "rootCause": caused[-1] if caused else None,
            "topFrame": top_frame, "sid": sid, "traceId": trace,
            "requestId": None,
            "noise": classify_noise(noise_rules, src, body),
            "sample": "\n".join([m.group(5)] + block[:3])[:300],
            "at": f"{ctx.cur_path}:{line_no}", "tzAssumed": None}


def parse_console(ctx, path, src, noise_rules):
    ent = src_entry(ctx.sources, src, path, ctx.root)
    if not ent["exists"]:
        return
    ctx.cur_path = ent["path"]
    try:
        text, truncated, _size = read_tail(path, ctx.args.max_bytes)
    except OSError as exc:
        ent["parseErrors"] += 1
        ctx.signal("source_unreadable", source=src, detail=str(exc)[:120])
        return
    ent["truncated"] = truncated
    ctx.file_ts = ent["mtime"]
    cur_m, block, block_no = None, [], 0

    def flush():
        nonlocal cur_m, block, block_no
        if cur_m is None:
            return
        ev = _block_event(ctx, src, cur_m, block, block_no, noise_rules)
        ctx.level(cur_m.group(2))
        if ev["kind"] != "info" or ev["level"] in ("WARN", "ERROR"):
            ctx.emit(ev)
        cur_m, block = None, []

    for i, line in enumerate(text.splitlines(), 1):
        if ctx.over_budget():
            ent["truncated"] = True
            break
        ent["linesRead"] += 1
        if line.startswith('{"ts"'):
            ctx.dbg_json_dup += 1
            continue
        m = LOGBACK_RE.match(line)
        if m:
            flush()
            cur_m, block, block_no = m, [], i
            continue
        if GRADLE_RE.match(line):
            flush()
            emit_gradle_line(ctx, line, ent["path"], i)
            continue
        if cur_m is not None and STACK_LINE_RE.match(line):
            block.append(line)
    flush()


def emit_gradle_line(ctx, line, at_path, line_no):
    exm = EXIT_RE.search(line)
    if exm:
        kind, level = "exit", "ERROR"
    elif "FAILED" in line or line.startswith("FAILURE:"):
        kind, level = "build_fail", "ERROR"
    elif "BUILD SUCCESSFUL" in line or "actionable" in line:
        return
    else:
        return
    ctx.emit({"ts": ctx.file_ts, "source": "gradle", "level": level,
              "kind": kind, "fingerprint": fingerprint(None, None, line),
              "logger": "gradle", "exceptionClass": None, "rootCause": None,
              "topFrame": None, "sid": None, "traceId": None,
              "requestId": None, "noise": None, "sample": line[:300],
              "at": f"{at_path}:{line_no}", "tzAssumed": "mtime",
              "exitValue": int(exm.group(1)) if exm else None})


def parse_err_log(ctx, path, noise_rules):
    ent = src_entry(ctx.sources, "console_err", path, ctx.root)
    if not ent["exists"]:
        return
    try:
        text, truncated, _ = read_tail(path, ctx.args.max_bytes)
    except OSError:
        ent["parseErrors"] += 1
        return
    ent["truncated"] = truncated
    ctx.file_ts = ent["mtime"]
    for i, line in enumerate(text.splitlines(), 1):
        ent["linesRead"] += 1
        if GRADLE_RE.match(line) or EXIT_RE.search(line):
            emit_gradle_line(ctx, line, ent["path"], i)


def parse_jsonl_file(ctx, path, src, noise_rules, row_fn, levels=("WARN", "ERROR")):
    ent = src_entry(ctx.sources, src, path, ctx.root)
    if not ent["exists"]:
        return
    try:
        text, truncated, _ = read_tail(path, ctx.args.max_bytes)
    except OSError:
        ent["parseErrors"] += 1
        return
    ent["truncated"] = truncated
    lines = text.splitlines()
    ent["linesRead"] = len(lines)
    for i, line in enumerate(lines, 1):
        if ctx.over_budget():
            ent["truncated"] = True
            break
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            ent["parseErrors"] += 1
            continue
        row_fn(row, i, ent)


def parse_debug_events(ctx, root, noise_rules):
    files = [Path(root) / "logs" / "debug-events.ndjson"]
    files += sorted(Path(root).glob("logs/debug-events.*.ndjson"))[-2:]
    seen = set()

    def row_fn(row, i, ent):
        ctx.level(row.get("level") or "INFO")
        ctx.request_ids_total += 1
        if row.get("requestId"):
            ctx.request_ids_parsed += 1
        if (row.get("level") or "") not in ("WARN", "ERROR"):
            return
        key = (row.get("ts"), row.get("fingerprint"), row.get("message"))
        if key in seen:
            return
        seen.add(key)
        msg = row.get("message") or ""
        err = row.get("error") or {}
        exc = err.get("exceptionClass") if isinstance(err, dict) else None
        ctx.emit({"ts": iso_kst(parse_ts_iso(row.get("ts"))),
                  "source": "debug_event", "level": row["level"],
                  "kind": "exception" if exc else ("error" if row["level"] == "ERROR" else "warn"),
                  "fingerprint": row.get("fingerprint") or fingerprint(exc, None, msg),
                  "logger": row.get("where") or row.get("probe"),
                  "exceptionClass": exc, "rootCause": None, "topFrame": None,
                  "sid": row.get("sid"), "traceId": row.get("traceId"),
                  "requestId": row.get("requestId"),
                  "noise": classify_noise(noise_rules, "debug_event", msg),
                  "sample": msg[:300], "at": f"{ent['path']}:{i}",
                  "tzAssumed": None})
    for f in files:
        parse_jsonl_file(ctx, f, "debug_event", noise_rules, row_fn)


def parse_trace(ctx, root, noise_rules):
    def row_fn(row, i, ent):
        kv = row.get("kv") or {}
        ctx.request_ids_total += 1
        if row.get("requestId"):
            ctx.request_ids_parsed += 1
        if not (kv.get("failureClass") or kv.get("error")):
            return
        ctx.emit({"ts": iso_kst(parse_ts_epoch(row.get("ts"))),
                  "source": "trace", "level": "WARN", "kind": "warn",
                  "fingerprint": fingerprint(None, None, str(kv)[:200]),
                  "logger": row.get("stage") or row.get("type"),
                  "exceptionClass": None, "rootCause": None, "topFrame": None,
                  "sid": row.get("sid"), "traceId": row.get("trace"),
                  "requestId": row.get("requestId"),
                  "noise": classify_noise(noise_rules, "trace", str(kv)),
                  "sample": json.dumps(kv, ensure_ascii=False)[:300],
                  "at": f"{ent['path']}:{i}", "tzAssumed": None})
    parse_jsonl_file(ctx, Path(root) / "logs" / "trace.ndjson",
                     "trace", noise_rules, row_fn)


def parse_failure_pattern(ctx, root, noise_rules, since):
    def row_fn(row, i, ent):
        dt = parse_ts_epoch((row.get("tsEpochMillis") or 0) / 1000.0)
        if dt and dt < since:
            return
        lvl = row.get("level") or "WARN"
        ctx.level(lvl)
        if lvl not in ("WARN", "ERROR"):
            return
        ctx.emit({"ts": iso_kst(dt), "source": "failure_pattern", "level": lvl,
                  "kind": "error" if lvl == "ERROR" else "warn",
                  "fingerprint": fingerprint(None, None, row.get("message") or row.get("key")),
                  "logger": row.get("logger") or row.get("source"),
                  "exceptionClass": row.get("exceptionType"),
                  "rootCause": None, "topFrame": None,
                  "sid": None, "traceId": None, "requestId": None,
                  "noise": classify_noise(noise_rules, "failure_pattern",
                                          row.get("message") or ""),
                  "sample": (row.get("message") or "")[:300],
                  "at": f"{ent['path']}:{i}", "tzAssumed": None})
    parse_jsonl_file(ctx, Path(root) / "logs" / "failure-pattern.jsonl",
                     "failure_pattern", noise_rules, row_fn)


def parse_dev_reload(ctx, root):
    log = Path(root) / "var" / "dev-reload" / "dev-reload.log"
    ent = src_entry(ctx.sources, "dev_reload", log, ctx.root)
    if not ent["exists"]:
        return
    try:
        text, truncated, _ = read_tail(log, ctx.args.max_bytes)
    except OSError:
        ent["parseErrors"] += 1
        return
    ent["truncated"] = truncated
    for i, line in enumerate(text.splitlines(), 1):
        ent["linesRead"] += 1
        m = DEVRELOAD_RE.match(line)
        if not m:
            continue
        dt = parse_ts_naive_kst(m.group(1))
        rest = m.group(2)
        if "Spring restart" in rest:
            kind = "restart"
            ctx.emit({"ts": iso_kst(dt), "source": "dev_reload",
                      "level": "INFO", "kind": kind,
                      "fingerprint": fingerprint(None, None, "spring restart"),
                      "logger": "dev-reload", "exceptionClass": None,
                      "rootCause": None, "topFrame": None, "sid": None,
                      "traceId": None, "requestId": None, "noise": None,
                      "sample": rest[:300], "at": f"{ent['path']}:{i}",
                      "tzAssumed": "KST"})


def parse_launcher(ctx, path, run_id, stops, restarts, tag="launcher"):
    ent = src_entry(ctx.sources, f"launcher:{run_id}", path, ctx.root)
    if not ent["exists"]:
        return
    try:
        text, truncated, _ = read_tail(path, ctx.args.max_bytes)
    except OSError:
        ent["parseErrors"] += 1
        return
    ent["truncated"] = truncated
    lines = text.splitlines()
    ent["linesRead"] = len(lines)
    for i, line in enumerate(lines, 1):
        m = LAUNCHER_RE.match(line)
        if not m:
            continue
        dt = parse_ts_naive_kst(m.group(1))
        stage, tagv, rest = m.group(2), m.group(3), m.group(4)
        sm = STOP_REST_RE.match(rest) if tagv == "STOP" else None
        if sm:
            stops.append({"pid": int(sm.group(1)), "ts": dt,
                          "run": run_id, "line": i, "how": sm.group(4)})
            ctx.emit({"ts": iso_kst(dt), "source": "launcher", "level": "INFO",
                      "kind": "stop",
                      "fingerprint": fingerprint(None, None, f"stop {sm.group(2)}"),
                      "logger": stage, "exceptionClass": None, "rootCause": None,
                      "topFrame": None, "sid": None, "traceId": None,
                      "requestId": None, "noise": None, "sample": line[:300],
                      "at": f"{ent['path']}:{i}", "tzAssumed": "KST"})
            ctx.signal("spring_stop", pid=int(sm.group(1)), ts=iso_kst(dt),
                       how=sm.group(4) or None, run=run_id)
        elif tagv == "RESTART":
            restarts.append({"ts": dt, "run": run_id})
            ctx.emit({"ts": iso_kst(dt), "source": "launcher", "level": "INFO",
                      "kind": "restart",
                      "fingerprint": fingerprint(None, None, "launcher restart"),
                      "logger": stage, "exceptionClass": None, "rootCause": None,
                      "topFrame": None, "sid": None, "traceId": None,
                      "requestId": None, "noise": None, "sample": line[:300],
                      "at": f"{ent['path']}:{i}", "tzAssumed": "KST"})
            ctx.signal("spring_restart", ts=iso_kst(dt), run=run_id)
        elif FAIL_TAG_RE.search(tagv) or FAIL_TAG_RE.search(rest or ""):
            ctx.emit({"ts": iso_kst(dt), "source": "launcher", "level": "ERROR",
                      "kind": "stage_fail",
                      "fingerprint": fingerprint(None, None, rest[:120]),
                      "logger": stage, "exceptionClass": None, "rootCause": None,
                      "topFrame": None, "sid": None, "traceId": None,
                      "requestId": None, "noise": None, "sample": line[:300],
                      "at": f"{ent['path']}:{i}", "tzAssumed": "KST"})
    ent["_last_line"] = lines[-1] if lines else ""


def parse_port_lease(ctx, root, noise_rules):
    def row_fn(row, i, ent):
        bad = row.get("exceptionType") or \
            (row.get("endStatus") not in (None, "ok", "released"))
        if not bad:
            return
        ctx.emit({"ts": iso_kst(parse_ts_iso(row.get("endedAt") or row.get("startedAt"))),
                  "source": "port_lease", "level": "WARN", "kind": "lease_fail",
                  "fingerprint": fingerprint(row.get("exceptionType"), None,
                                             row.get("cause") or row.get("endStatus")),
                  "logger": row.get("service"), "exceptionClass": row.get("exceptionType"),
                  "rootCause": None, "topFrame": None, "sid": row.get("session"),
                  "traceId": row.get("traceId"), "requestId": None,
                  "noise": classify_noise(noise_rules, "port_lease",
                                          row.get("cause") or ""),
                  "sample": json.dumps(
                      {k: row.get(k) for k in ("service", "port", "endStatus",
                                               "httpStatus", "exceptionType",
                                               "cause", "phase")},
                      ensure_ascii=False)[:300],
                  "at": f"{ent['path']}:{i}", "tzAssumed": None})
    parse_jsonl_file(ctx, Path(root) / "var" / "agent-port-lease" / "traces.jsonl",
                     "port_lease", noise_rules, row_fn)


# -------------------------------------------------------------- run/select
def list_run_dirs(root):
    base = Path(root) / "var" / "rag-launcher"
    try:
        return sorted([p.name for p in base.iterdir()
                       if p.is_dir() and RUN_DIR_RE.match(p.name)])
    except OSError:
        return []


def load_latest(root):
    p = Path(root) / "var" / "rag-launcher" / "LATEST.json"
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except Exception:
        return None


def select_run(ctx, root):
    runs = list_run_dirs(root)
    latest = load_latest(root)
    chosen, sel, reason = None, None, None
    if ctx.args.run:
        chosen = ctx.args.run
        sel, reason = "explicit", f"--run {ctx.args.run}"
    elif latest and latest.get("runId"):
        chosen = latest["runId"]
        sel, reason = "latest_completed", "LATEST.json runId"
    elif runs:
        chosen = runs[-1]
        sel, reason = "newest_dir", "no LATEST.json; newest run dir"
    ctx.launcher_info = latest or {}
    ctx.run_id = chosen
    ctx.run_dir = Path(root) / "var" / "rag-launcher" / chosen if chosen else None
    if sel == "explicit" and ctx.run_dir and ctx.run_dir.is_dir():
        try:
            ctx.launcher_info = json.loads(
                (ctx.run_dir / "result.json").read_text(encoding="utf-8"))
        except Exception:
            pass
    ctx.run_selection = {"mode": sel, "reason": reason}
    if chosen and runs and runs[-1] != chosen:
        newer = [r for r in runs if r > chosen]
        ctx.newer_runs = newer[-4:]
        ctx.signal("newer_run_in_progress", dir=newer[-1],
                   detail="LATEST points at an older completed run")
    else:
        ctx.newer_runs = []


def run_start_guess(run_id):
    m = re.match(r"^(\d{8})-(\d{6})", run_id or "")
    if not m:
        return None
    try:
        return datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S").replace(tzinfo=KST)
    except ValueError:
        return None


# -------------------------------------------------------------- correlation
def correlate_exits(ctx):
    """exit N vs launcher STOP pid within +-60s of the err-log mtime."""
    spring_pids = set()
    li = ctx.launcher_info or {}
    if li.get("springPid"):
        spring_pids.add(int(li["springPid"]))
    owned = ctx.run_dir / "spring-owned.json" if ctx.run_dir else None
    if owned and owned.is_file():
        try:
            data = json.loads(owned.read_text(encoding="utf-8"))
            pid = (data.get("listener") or {}).get("processId")
            if pid:
                spring_pids.add(int(pid))
        except Exception:
            pass
    expected = False
    for ev in ctx.events:
        if ev["kind"] != "exit":
            continue
        ets = parse_ts_iso(ev["ts"]) if ev.get("ts") else None
        hit = None
        for st in ctx.stops:
            if st["pid"] not in spring_pids:
                continue
            if ets is None or st["ts"] is None or \
                    abs((st["ts"] - ets).total_seconds()) <= 60:
                hit = st
                break
        ev["reason"] = "expected_restart" if hit else "unexpected_exit"
        if hit:
            expected = True
            ev["stoppedBy"] = {"run": hit["run"], "ts": iso_kst(hit["ts"]),
                               "how": hit["how"]}
            ctx.signal("expected_restart", pid=list(spring_pids) or None,
                       stopTs=iso_kst(hit["ts"]), stoppedByRun=hit["run"])
        else:
            ctx.signal("unexpected_exit",
                       detail=f"gradle exit {ev.get('exitValue')} with no "
                              f"matching launcher STOP pid")
    if expected:
        # The kill itself makes gradle report the bootRun task as failed;
        # keep those side-effect lines out of the ERROR verdict while real
        # (non-gradle) exceptions keep their level.
        for ev in ctx.events:
            if ev["source"] == "gradle" and ev["level"] == "ERROR" and \
                    ev["kind"] in ("exit", "build_fail"):
                ev["level"] = "INFO"
                ev.setdefault("reason", "expected_restart")


# -------------------------------------------------------------- disk report
def disk_report(root):
    rep = {"runs": {"count": 0, "totalBytes": 0, "oldest": None, "newest": None,
                    "olderThanDays": 7, "olderCount": 0, "topRuns": []},
           "logs": []}
    base = Path(root) / "var" / "rag-launcher"
    cutoff = time.time() - rep["runs"]["olderThanDays"] * 86400
    sizes = []
    try:
        names = [p for p in base.iterdir()
                 if p.is_dir() and RUN_DIR_RE.match(p.name)]
    except OSError:
        names = []
    for d in names:
        total = 0
        try:
            for _dp, _dn, fn in os.walk(d):
                for f in fn:
                    try:
                        total += (Path(_dp) / f).stat().st_size
                    except OSError:
                        pass
        except OSError:
            continue
        mt = d.stat().st_mtime
        sizes.append({"run": d.name, "bytes": total,
                      "mtime": iso_kst(datetime.fromtimestamp(mt, tz=KST))})
        if mt < cutoff:
            rep["runs"]["olderCount"] += 1
    sizes.sort(key=lambda r: r["run"])
    rep["runs"]["count"] = len(sizes)
    rep["runs"]["totalBytes"] = sum(r["bytes"] for r in sizes)
    if sizes:
        rep["runs"]["oldest"] = sizes[0]["run"]
        rep["runs"]["newest"] = sizes[-1]["run"]
    rep["runs"]["topRuns"] = sorted(sizes, key=lambda r: -r["bytes"])[:10]
    logs = Path(root) / "logs"
    if logs.is_dir():
        for f in sorted(logs.iterdir()):
            if f.is_file():
                rep["logs"].append({"file": f.name, "bytes": f.stat().st_size})
    return rep


# ----------------------------------------------------------------- filters
def apply_filters(ctx):
    out = []
    lvl_min = LEVEL_RANK.get(ctx.args.level, 0)
    for ev in ctx.events:
        if ev.get("ts"):
            dt = parse_ts_iso(ev["ts"])
            if dt and dt < ctx.since:
                continue
        if LEVEL_RANK.get(ev["level"], 0) < lvl_min:
            continue
        if ctx.args.request and ev.get("requestId") != ctx.args.request:
            continue
        if ctx.args.sid and ev.get("sid") != ctx.args.sid:
            continue
        if ev["noise"] and not ctx.args.include_noise:
            continue
        out.append(ev)
    return out


def aggregate(events):
    groups = {}
    for ev in events:
        g = groups.get(ev["fingerprint"])
        if g is None:
            g = dict(ev)
            g["count"] = 0
            g["firstTs"] = ev.get("ts")
            groups[ev["fingerprint"]] = g
        g["count"] += 1
        g["lastTs"] = ev.get("ts") or g.get("lastTs")
    top = sorted(groups.values(),
                 key=lambda e: (-e["count"], e.get("lastTs") or ""))
    return top


# ----------------------------------------------------------------- outputs
def agent_trace_dir(root):
    if Path(root).resolve() != ROOT:
        return Path(root) / "var" / "agent-trace"   # fixture/test roots stay local
    env = os.environ.get("AWX_PATH_AGENT_TRACE")
    if env:
        return Path(env)
    if _awx_resolve:
        try:
            return Path(_awx_resolve("var.agent_trace"))
        except Exception:
            pass
    return Path(root) / "var" / "agent-trace"


def write_outputs(ctx, digest, out_dir):
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "history").mkdir(exist_ok=True)
    payload = json.dumps(digest, ensure_ascii=False, indent=1)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    for name, text in (("latest.json", payload),
                       ("latest.md", render_md(digest)),
                       (f"history/{stamp}.json", payload)):
        tmp = out_dir / (name + ".tmp")
        tmp.write_text(text, encoding="utf-8")
        os.replace(tmp, out_dir / name)
    hist = sorted((out_dir / "history").glob("*.json"))
    for old in hist[:-20]:
        try:
            old.unlink()
        except OSError:
            pass


def render_md(digest):
    L = ["# server_trace_digest latest",
         f"verdict: **{digest['verdict']}**  |  generatedAt: {digest['generatedAt']}",
         f"run: {digest['runId']} ({digest['runSelection']['mode']})  "
         f"window: {digest['window']['since']} -> {digest['window']['until']}",
         f"exit-hint: {digest['exitCodeMeaning']}", ""]
    if digest.get("signals"):
        L.append("## signals")
        for s in digest["signals"][:15]:
            detail = {k: v for k, v in s.items() if k != "signal"}
            L.append(f"- {s['signal']} {json.dumps(detail, ensure_ascii=False)[:160]}")
        L.append("")
    L.append("## top fingerprints (non-noise)")
    for t in digest["top"][:10]:
        L.append(f"- `{t['fingerprint']}` x{t['count']} {t['level']}/{t['kind']} "
                 f"{(t.get('exceptionClass') or t.get('logger') or '')[:60]} "
                 f"last={t.get('lastTs')}")
        if t.get("sample"):
            one = str(t["sample"]).splitlines()[0][:140]
            L.append(f"  {one}")
    L += ["", f"noise: {json.dumps(digest['counts']['noise'], ensure_ascii=False)}",
          f"coverage: {json.dumps(digest['coverage'], ensure_ascii=False)}",
          f"next: `{digest['nextCommand']}` (printed only, not executed)"]
    return "\n".join(L[:60]) + "\n"


def summarize_console(digest, args):
    if args.json:
        print(json.dumps(digest, ensure_ascii=False))
        return
    c = digest["counts"]
    print(f"[DIGEST] verdict={digest['verdict']} run={digest['runId']} "
          f"sel={digest['runSelection']['mode']} exit={digest['exitCode']}")
    print(f"[DIGEST] window {digest['window']['since']} .. {digest['window']['until']}  "
          f"levels={json.dumps(c['byLevel'], sort_keys=True)} "
          f"noise={sum(c['noise'].values())} sources={len(digest['sources'])}")
    if digest.get("signals"):
        names = ",".join(sorted({s['signal'] for s in digest['signals']}))
        print(f"[DIGEST] signals={names}")
    for t in digest["top"][:args.top]:
        cls = t.get("exceptionClass") or ""
        print(f"[DIGEST] top x{t['count']} {t['fingerprint']} "
              f"{t['level']}/{t['kind']} {cls[:80]}")
    print(f"[DIGEST] coverage requestIdInConsole={digest['coverage']['requestIdInConsole']} "
          f"requestIdInDebugEvents={digest['coverage']['requestIdInDebugEvents']}")
    if digest.get("disk"):
        r = digest["disk"]["runs"]
        print(f"[DISK] runs={r['count']} totalMB={r['totalBytes'] / 1048576:.1f} "
              f"olderThan{r['olderThanDays']}d={r['olderCount']} moved=0 deleted=0")
    print(f"[DIGEST] files -> {digest['outDir']} (latest.json latest.md history/)")
    print(f"[DIGEST] next: {digest['nextCommand']}   (hint only - not executed)")


# ------------------------------------------------------------------- main
def build_arg_parser():
    ap = argparse.ArgumentParser(prog="server_trace_digest")
    ap.add_argument("--root", default=str(ROOT), help="repo root (fixtures/tests)")
    ap.add_argument("--run", default=None, help="explicit runId")
    ap.add_argument("--since", default=None,
                    help="window start: 30m|2h|1d|ISO (default: run start)")
    ap.add_argument("--request", default=None, help="filter requestId")
    ap.add_argument("--sid", default=None, help="filter sid")
    ap.add_argument("--level", default="INFO", choices=list(LEVELS))
    ap.add_argument("--top", type=int, default=20)
    ap.add_argument("--include-noise", action="store_true")
    ap.add_argument("--json", action="store_true", help="single JSON doc on stdout")
    ap.add_argument("--no-write", action="store_true")
    ap.add_argument("--disk-report", action="store_true")
    ap.add_argument("--max-bytes", type=int, default=8 * 1024 * 1024)
    ap.add_argument("--max-seconds", type=float, default=20.0)
    return ap


def main(argv=None):
    t0 = time.monotonic()
    ap = build_arg_parser()
    try:
        args = ap.parse_args(argv)
    except SystemExit as e:
        return 0 if e.code == 0 else 1
    root = Path(args.root).resolve()
    if not (root / "var").is_dir():
        print(f"[DIGEST] root has no var/: {root}", file=sys.stderr)
        return 1
    try:
        ctx = Ctx(args, root)
        noise_rules = load_noise(root)
        select_run(ctx, root)
        ctx.stops, ctx.restarts = [], []
        if ctx.run_dir is None or not ctx.run_dir.is_dir():
            if not list_run_dirs(root):
                digest = empty_digest(args, root, "no run directories")
                return finish(ctx, digest, args, t0, no_data=True)
        ctx.since = parse_since(args.since, run_start_guess(ctx.run_id)
                                or (now_kst() - timedelta(hours=2)))
        rd = ctx.run_dir
        if rd and rd.is_dir():
            ctx.file_ts = None
            parse_console(ctx, rd / "chat-ui-vibe-listener-18180.out.log",
                          "console", noise_rules)
            parse_err_log(ctx, rd / "chat-ui-vibe-listener-18180.err.log",
                          noise_rules)
            parse_launcher(ctx, rd / "launcher.log", ctx.run_id,
                           ctx.stops, ctx.restarts)
        for nr in ctx.newer_runs:
            if ctx.over_budget():
                break
            parse_launcher(ctx, Path(root) / "var" / "rag-launcher" / nr /
                           "launcher.log", nr, ctx.stops, ctx.restarts)
        parse_debug_events(ctx, root, noise_rules)
        parse_trace(ctx, root, noise_rules)
        parse_failure_pattern(ctx, root, noise_rules, ctx.since)
        parse_dev_reload(ctx, root)
        parse_port_lease(ctx, root, noise_rules)
        if list(Path(root).glob("hs_err_pid*.log")):
            ctx.signal("jvm_crash_file", detail="hs_err_pid*.log present in root")
        correlate_exits(ctx)
        digest = assemble(ctx, args, root, t0)
        if args.disk_report:
            digest["disk"] = disk_report(root)
        return finish(ctx, digest, args, t0)
    except KeyboardInterrupt:
        print("[DIGEST] interrupted; latest.* left untouched", file=sys.stderr)
        return 1
    except Exception as exc:                              # noqa: BLE001
        print(f"[DIGEST] tool error: {exc}", file=sys.stderr)
        return 1


def empty_digest(args, root, reason):
    return {"schemaVersion": SCHEMA, "generatedAt": iso_kst(now_kst()),
            "runId": None,
            "runSelection": {"mode": "none", "reason": reason},
            "launcher": {}, "window": {"since": None, "until": iso_kst(now_kst())},
            "sources": [], "counts": {"byLevel": {}, "byKind": {},
                                      "bySource": {}, "noise": {}},
            "top": [], "signals": [],
            "coverage": {"requestIdInConsole": False,
                         "requestIdInDebugEvents": "0/0"},
            "verdict": "no_data", "signalNote": SIGNAL_NOTE,
            "nextCommand": "Debug-RAG.bat -Action status",
            "outDir": str(agent_trace_dir(root)),
            "exitCode": 3, "exitCodeMeaning": EXIT_MEANING}


SIGNAL_NOTE = ("Windows build: no POSIX signals; exit code + launcher "
               "STOP/RESTART record is the declared substitute")
EXIT_MEANING = "0=clean|warn 3=no_data 4=error/unexpected_exit 1=tool-error"


def assemble(ctx, args, root, t0):
    events = apply_filters(ctx)
    top = aggregate([e for e in events if not e["noise"]])
    if args.include_noise:
        top = aggregate(events)
    has_error = any(e["level"] == "ERROR" or
                    (e["kind"] == "exception" and not e["noise"]) or
                    (e["kind"] == "exit" and e.get("reason") == "unexpected_exit") or
                    e["kind"] == "stage_fail"
                    for e in events if not e["noise"])
    has_warn = any(e["level"] == "WARN" or e["kind"] in ("warn", "lease_fail")
                   for e in events if not e["noise"])
    if any(s["signal"] == "jvm_crash_file" for s in ctx.signals):
        has_error = True
    if not events and not ctx.signals:
        verdict = "no_data" if all(not s["exists"] for s in ctx.sources) else "clean"
    elif has_error:
        verdict = "error"
    elif has_warn:
        verdict = "warn"
    else:
        verdict = "clean"
    if ctx.args.request or ctx.args.sid:
        nxt = (f"python -B scripts/server_trace_digest.py --request "
               f"{ctx.args.request or ctx.args.sid} --include-noise")
    elif verdict == "error":
        nxt = "Debug-RAG.bat -Action verify"
    elif verdict == "no_data":
        nxt = "Debug-RAG.bat -Action status"
    else:
        nxt = "Read-RAG-Debug.bat"
    li = ctx.launcher_info or {}
    coverage = {"requestIdInConsole": False,
                "requestIdInDebugEvents":
                    f"{ctx.request_ids_parsed}/{ctx.request_ids_total}",
                "consoleDebugJsonDupes": ctx.dbg_json_dup}
    if ctx.args.request and not coverage["requestIdInConsole"]:
        coverage["note"] = ("console has no requestId MDC slot "
                            "(logback-spring.xml:7); see FOR_CODEX F-1")
    exit_code = 4 if verdict == "error" else (3 if verdict == "no_data" else 0)
    return {"schemaVersion": SCHEMA,
            "generatedAt": iso_kst(now_kst()),
            "runId": ctx.run_id,
            "runSelection": ctx.run_selection,
            "launcher": {"ok": li.get("ok"), "status": li.get("status"),
                         "stage": li.get("stage"),
                         "failurePoint": li.get("failurePoint"),
                         "listenerStatus": li.get("listenerStatus"),
                         "springPid": li.get("springPid"),
                         "profile": li.get("springProfile")},
            "window": {"since": iso_kst(ctx.since),
                       "until": iso_kst(now_kst())},
            "sources": [{k: v for k, v in s.items() if not k.startswith("_")}
                        for s in ctx.sources],
            "counts": ctx.counts,
            "top": top[: max(1, args.top)],
            "signals": ctx.signals,
            "coverage": coverage,
            "verdict": verdict,
            "signalNote": SIGNAL_NOTE,
            "nextCommand": nxt,
            "outDir": str(agent_trace_dir(root)),
            "exitCode": exit_code,
            "exitCodeMeaning": EXIT_MEANING,
            "tookMs": int((time.monotonic() - t0) * 1000),
            "truncated": ctx.stopped_early or any(
                s.get("truncated") for s in ctx.sources)}


def finish(ctx, digest, args, t0, no_data=False):
    if not args.no_write:
        try:
            write_outputs(ctx, digest, agent_trace_dir(Path(args.root).resolve()))
        except OSError as exc:
            print(f"[DIGEST] write failed: {exc}", file=sys.stderr)
            return 1
    summarize_console(digest, args)
    return int(digest.get("exitCode", 0))


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(errors="replace")   # cp949 console safety
    except Exception:
        pass
    raise SystemExit(main())
