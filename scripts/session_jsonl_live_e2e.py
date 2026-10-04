#!/usr/bin/env python3
"""One-shot live E2E: real chat send -> real session trace -> real export.

DEMO1-DEVIN-SESSION-CONTEXT-E2E-20261004 (D2). Bounds:
  - local http://127.0.0.1:18180 only; public site never used
  - exactly 1 chat send per invocation; --max-sends is a cap guard (<=3)
  - the run token is server-minted (ChatRunRegistry.java:116); this tool reads
    it from the SSE ``session`` event and reports sha256-12 only, never raw.

Modes:
  --dry-run (default) print planned request, expected trace path, export command
  --live              perform the send + trace wait + export + checks (a)-(d)
  --self-test         fixture-based check of judgment logic, no network

Exit codes: 0 pass/dry-run | 2 usage | 3 refused-unsafe | 4 not-observed |
            5 check-failed
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "awx.session-jsonl-live-e2e.v1"
EXPORT_NAME_RE = re.compile(r"^export-[0-9a-f]{16}$")
TRACE_REL = Path("var/debug/chat-session-traces")
EXPORT_SCRIPT = Path("scripts/chat_session_debug_export.py")
EXPORT_POINTER = Path("var/debug/chat-session-traces/export/latest.json")
DEFAULT_BASE_URL = "http://127.0.0.1:18180"
MAX_SENDS_CAP = 3
SEND_PREFIX = "[devin-test]"
DEFAULT_MESSAGE = "[devin-test] short general question: what is 2+2?"
STREAM_READ_CAP_S = 300
TRACE_WAIT_CAP_S = 60
TRACE_POLL_S = 1.0
HTTP_FAIL_REASON = {401: "auth-blocked", 403: "auth-blocked", 429: "rate-limited"}


def sha12(value) -> str:
    """Same digest as SafeRedactor.hash12 / chat_session_debug_export.hash12."""
    return hashlib.sha256(str(value).strip().encode("utf-8")).hexdigest()[:12]


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def utc_day() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%d")


def trace_day_dir(root: Path) -> Path:
    return root / TRACE_REL / utc_day()


def expected_trace_file(root: Path, session_id) -> Path:
    return trace_day_dir(root) / ("s-" + sha12(session_id) + ".json")


def export_name_for_hash(h12: str) -> str:
    return "export-" + hashlib.sha256(h12.encode("ascii")).hexdigest()[:16]


def contains_raw(text: str, raw: str) -> bool:
    return bool(raw) and raw in text


def scan_paths_for_raw(paths, raw: str) -> list:
    """Return [(path, count)] where raw token bytes appear; missing files skip."""
    hits = []
    for p in paths:
        path = Path(p)
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        n = text.count(raw)
        if n:
            hits.append((str(path), n))
    return hits


def parse_sse_events(lines):
    """Yield (event_type, payload_dict) from an SSE byte-line iterator.

    Accepts both ``event:`` fields and JSON ``type`` inside ``data:`` —
    mirrors chat.js decodeSseEvent effectiveType semantics (read-only copy).
    """
    event_field, data_lines = None, []
    for raw in lines:
        line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
        if line == "":
            if data_lines:
                data_text = "\n".join(data_lines)
                payload = {}
                try:
                    payload = json.loads(data_text)
                except (ValueError, TypeError):
                    payload = {"_raw": data_text}
                effective = event_field or payload.get("type") or "message"
                yield effective, payload
            event_field, data_lines = None, []
            continue
        if line.startswith(":"):
            continue
        if line.startswith("event:"):
            event_field = line[6:].strip()
        elif line.startswith("data:"):
            data_lines.append(line[5:].lstrip(" "))
    if data_lines:
        data_text = "\n".join(data_lines)
        try:
            payload = json.loads(data_text)
        except (ValueError, TypeError):
            payload = {"_raw": data_text}
        yield event_field or payload.get("type") or "message", payload


def build_plan(root: Path, base_url: str, message: str, corr_id: str) -> dict:
    body = {
        "message": message,
        "question": message,
        "useRag": False,
        "useWebSearch": False,
        "searchMode": "OFF",
    }
    return {
        "schema": SCHEMA,
        "mode": "plan",
        "request": {
            "method": "POST",
            "url": base_url.rstrip("/") + "/api/chat/stream",
            "headers": {
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
                "X-Request-Id": corr_id,
            },
            "body": body,
        },
        "correlationId": corr_id,
        "observation": {
            "runTokenSource": "SSE 'session' event data field (server-minted; "
                              "ChatRunRegistry.java:116, ChatStreamEvent.java:186-189)",
            "traceFile": str(expected_trace_file(root, "<sessionId>")),
            "runIdField": "hash:<sha256-12(runToken)>",
        },
        "exportCommand": "python scripts/chat_session_debug_export.py "
                         "--root . export hash:<sha12(runToken)>",
        "checks": ["export-dir-name ^export-[0-9a-f]{16}$",
                   "manifest/latest: raw-token 0 + queryHash present",
                   "export stdout/stderr: raw-token 0",
                   "trace file sha256 unchanged pre/post export"],
        "caps": {"maxSends": MAX_SENDS_CAP, "streamReadCapSec": STREAM_READ_CAP_S,
                 "traceWaitCapSec": TRACE_WAIT_CAP_S, "publicSends": 0},
    }


def http_send_stream(base_url: str, body: dict, corr_id: str, send_deadline_s: float):
    """POST /api/chat/stream once; iterate SSE events until terminal/cap."""
    url = base_url.rstrip("/") + "/api/chat/stream"
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
            "X-Request-Id": corr_id,
        },
        method="POST",
    )
    resp = urllib.request.urlopen(req, timeout=send_deadline_s)
    session_id, run_token, terminal = None, None, None
    started = time.monotonic()
    for effective, payload in parse_sse_events(resp):
        if effective == "session" and isinstance(payload, dict):
            run_token = run_token or payload.get("data")
            session_id = session_id or payload.get("sessionId")
        if effective in ("final", "done", "error", "stream_failed"):
            terminal = effective
            break
        if time.monotonic() - started > send_deadline_s:
            terminal = "client-read-cap"
            break
    try:
        resp.close()
    except Exception:
        pass
    return {
        "httpStatus": getattr(resp, "status", None),
        "sessionId": session_id,
        "runToken": run_token,
        "terminalEvent": terminal or "eof",
        "elapsedSec": round(time.monotonic() - started, 2),
    }


def wait_for_run_record(trace_file: Path, run_hash: str, cap_s: float) -> dict:
    """Poll (bounded, no daemon) until the file holds runId=hash:<run_hash>."""
    needle = '"runId":"hash:' + run_hash + '"'
    deadline = time.monotonic() + cap_s
    while time.monotonic() < deadline:
        if trace_file.is_file():
            try:
                if needle in trace_file.read_text(encoding="utf-8", errors="replace"):
                    return {"found": True, "file": str(trace_file)}
            except OSError:
                pass
        time.sleep(TRACE_POLL_S)
    return {"found": False, "file": str(trace_file)}


def judge_export(root: Path, run_hash: str, export_proc: dict,
                 trace_sha_before: str, trace_file: Path,
                 new_export_dir: Path | None) -> dict:
    checks = {}
    name_ok = bool(new_export_dir) and bool(EXPORT_NAME_RE.fullmatch(new_export_dir.name))
    expected_name = export_name_for_hash(run_hash)
    checks["a_export_name"] = {
        "pass": name_ok and new_export_dir.name == expected_name,
        "dir": new_export_dir.name if new_export_dir else None,
        "expected": expected_name,
    }
    manifest = (new_export_dir / "manifest.json") if new_export_dir else None
    latest = root / EXPORT_POINTER
    raw_token = export_proc.get("_rawToken") or ""
    hits = scan_paths_for_raw([p for p in (manifest, latest) if p], raw_token)
    qh_ok = False
    for doc_path in (manifest, latest):
        if doc_path and doc_path.is_file():
            try:
                doc = json.loads(doc_path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            if doc.get("queryHash") == run_hash:
                qh_ok = True
    checks["b_manifest_clean"] = {
        "pass": qh_ok and not hits,
        "rawTokenHits": len(hits),
        "queryHashMatches": qh_ok,
    }
    stream_hits = export_proc.get("stdout", "").count(raw_token) + \
        export_proc.get("stderr", "").count(raw_token)
    checks["c_stdout_clean"] = {"pass": stream_hits == 0, "rawTokenHits": stream_hits}
    try:
        sha_after = sha256_file(trace_file)
    except OSError:
        sha_after = None
    checks["d_trace_readonly"] = {
        "pass": sha_after == trace_sha_before,
        "shaBefore": trace_sha_before,
        "shaAfter": sha_after,
    }
    return checks


def run_live(root: Path, base_url: str, message: str, max_sends: int) -> int:
    if max_sends > MAX_SENDS_CAP or max_sends < 1:
        print(json.dumps({"schema": SCHEMA, "verdict": "REFUSED",
                          "reason": "max-sends-out-of-range(1-3)"}, indent=2))
        return 3
    corr_id = "devin-e2e-" + hashlib.sha256(
        f"{time.time_ns()}".encode()).hexdigest()[:8]
    sends_used = 0
    # Preflight GET is free (not a chat send).
    try:
        with urllib.request.urlopen(base_url.rstrip("/") + "/chat", timeout=10) as probe:
            page_status = probe.status
    except urllib.error.HTTPError as e:
        page_status = e.code
    except (urllib.error.URLError, OSError) as e:
        print(json.dumps({"schema": SCHEMA, "verdict": "NOT_RUN",
                          "reason": "server-unreachable", "detail": type(e).__name__,
                          "sends": 0}, indent=2))
        return 4
    if page_status != 200:
        print(json.dumps({"schema": SCHEMA, "verdict": "NOT_RUN",
                          "reason": "chat-page-status", "pageStatus": page_status,
                          "sends": 0}, indent=2))
        return 4

    body = build_plan(root, base_url, message, corr_id)["request"]["body"]
    try:
        sends_used += 1
        result = http_send_stream(base_url, body, corr_id, STREAM_READ_CAP_S)
    except urllib.error.HTTPError as e:
        reason = HTTP_FAIL_REASON.get(e.code, "http-error")
        print(json.dumps({"schema": SCHEMA, "verdict": "NOT_RUN",
                          "reason": reason, "httpStatus": e.code,
                          "sends": sends_used}, indent=2))
        return 4
    except (urllib.error.URLError, OSError, TimeoutError) as e:
        print(json.dumps({"schema": SCHEMA, "verdict": "NOT_RUN",
                          "reason": "send-failed", "detail": type(e).__name__,
                          "sends": sends_used}, indent=2))
        return 4

    run_token = result.get("runToken")
    session_id = result.get("sessionId")
    if not run_token or session_id is None:
        print(json.dumps({"schema": SCHEMA, "verdict": "NOT_RUN",
                          "reason": "session-event-missing",
                          "terminalEvent": result.get("terminalEvent"),
                          "httpStatus": result.get("httpStatus"),
                          "sends": sends_used}, indent=2))
        return 4
    run_hash = sha12(run_token)
    session_hash = sha12(session_id)
    trace_file = expected_trace_file(root, session_id)
    wait = wait_for_run_record(trace_file, run_hash, TRACE_WAIT_CAP_S)
    if not wait["found"]:
        print(json.dumps({"schema": SCHEMA, "verdict": "NOT_RUN",
                          "reason": "trace-record-not-observed",
                          "traceFile": str(trace_file),
                          "hashes": {"runHash": run_hash, "sessionHash": session_hash},
                          "terminalEvent": result.get("terminalEvent"),
                          "sends": sends_used}, indent=2))
        return 4
    try:
        trace_sha_before = sha256_file(trace_file)
    except OSError:
        trace_sha_before = None

    export_root = root / TRACE_REL / "export"
    before_exports = {p.name for p in export_root.iterdir() if p.is_dir()} \
        if export_root.is_dir() else set()
    proc = subprocess.run(
        [sys.executable, str(root / EXPORT_SCRIPT), "--root", str(root),
         "export", "hash:" + run_hash],
        capture_output=True, text=True, timeout=120)
    after_exports = {p.name for p in export_root.iterdir() if p.is_dir()} \
        if export_root.is_dir() else set()
    new_names = sorted(after_exports - before_exports)
    expected_name = export_name_for_hash(run_hash)
    new_dir = None
    for name in new_names:
        if name == expected_name:
            new_dir = export_root / name
    if new_dir is None and (export_root / expected_name).is_dir():
        new_dir = export_root / expected_name  # idempotent re-export
    export_proc = {"stdout": proc.stdout, "stderr": proc.stderr,
                   "returncode": proc.returncode, "_rawToken": run_token}
    checks = judge_export(root, run_hash, export_proc, trace_sha_before,
                          trace_file, new_dir)
    all_pass = all(c.get("pass") for c in checks.values()) and proc.returncode == 0
    verdict = {
        "schema": SCHEMA,
        "verdict": "PASS" if all_pass else "FAIL",
        "sends": sends_used,
        "corrId": corr_id,
        "httpStatus": result.get("httpStatus"),
        "terminalEvent": result.get("terminalEvent"),
        "elapsedSec": result.get("elapsedSec"),
        "hashes": {"runHash": run_hash, "sessionHash": session_hash},
        "traceFile": str(trace_file.relative_to(root)),
        "exportDir": str(new_dir.relative_to(root)) if new_dir else None,
        "exportExit": proc.returncode,
        "checks": checks,
    }
    print(json.dumps(verdict, indent=2, ensure_ascii=False))
    return 0 if all_pass else 5


def self_test() -> int:
    import tempfile
    failures = []

    def check(name, cond):
        if not cond:
            failures.append(name)
        return cond

    sse = (b"event: session\n"
           b"data: {\"type\":\"session\",\"data\":\"tok-abc-123\",\"sessionId\":42}\n\n"
           b"data: {\"type\":\"token\",\"data\":\"hi\"}\n\n"
           b"event: final\n"
           b"data: {\"type\":\"final\",\"data\":\"4\",\"sessionId\":42}\n\n")
    events = list(parse_sse_events(iter(sse.splitlines(keepends=True))))
    check("sse.count", len(events) == 3)
    check("sse.session.data", events[0][1].get("data") == "tok-abc-123")
    check("sse.session.sid", events[0][1].get("sessionId") == 42)
    check("sse.final", events[2][0] == "final")

    check("name.good", bool(EXPORT_NAME_RE.fullmatch("export-0123456789abcdef")))
    check("name.bad", not EXPORT_NAME_RE.fullmatch("export-xyz"))
    h = sha12("some-token")
    check("name.expected", export_name_for_hash(h).startswith("export-")
          and len(export_name_for_hash(h)) == len("export-") + 16)

    with tempfile.TemporaryDirectory() as td:
        base = Path(td)
        raw = "tok-secret-raw-777"
        man = base / "manifest.json"
        man.write_text(json.dumps({"queryHash": sha12(raw), "x": 1}), encoding="utf-8")
        lat = base / "latest.json"
        lat.write_text(json.dumps({"queryHash": sha12(raw)}), encoding="utf-8")
        check("scan.clean", scan_paths_for_raw([man, lat], raw) == [])
        man.write_text(json.dumps({"queryHash": sha12(raw), "rawEcho": raw}),
                       encoding="utf-8")
        hits = scan_paths_for_raw([man, lat], raw)
        check("scan.hit", len(hits) == 1 and hits[0][1] == 1)
        man.write_text(json.dumps({"queryHash": sha12(raw)}), encoding="utf-8")

        tfile = base / "s-abc.json"
        tfile.write_text('{"runId":"hash:' + sha12("rt") + '"}\n', encoding="utf-8")
        check("wait.found", wait_for_run_record(tfile, sha12("rt"), 2)["found"])
        check("wait.miss", not wait_for_run_record(tfile, sha12("other"), 1)["found"])

        sha_before = sha256_file(tfile)
        export_dir = base / export_name_for_hash(sha12("rt"))
        export_dir.mkdir()
        (export_dir / "manifest.json").write_text(
            json.dumps({"queryHash": sha12("rt")}), encoding="utf-8")
        (base / "latest.json").write_text(
            json.dumps({"queryHash": sha12("rt")}), encoding="utf-8")
        proc = {"stdout": "ok", "stderr": "", "_rawToken": "rt"}
        checks = judge_export(base, sha12("rt"), proc, sha_before, tfile, export_dir)
        check("judge.allpass", all(c["pass"] for c in checks.values()))
        (export_dir / "manifest.json").write_text(
            json.dumps({"queryHash": sha12("rt"), "leak": "rt"}), encoding="utf-8")
        checks2 = judge_export(base, sha12("rt"), proc, sha_before, tfile, export_dir)
        check("judge.detect-leak", not checks2["b_manifest_clean"]["pass"])

    plan = build_plan(Path("."), DEFAULT_BASE_URL, DEFAULT_MESSAGE, "devin-e2e-deadbeef")
    check("plan.url", plan["request"]["url"].endswith("/api/chat/stream"))
    check("plan.body", plan["request"]["body"]["message"].startswith(SEND_PREFIX))
    check("plan.norawtoken",
          "runToken" not in plan["request"]["body"]
          and "X-Chat-Run-Token" not in plan["request"]["headers"])

    ok = not failures
    print(json.dumps({"schema": SCHEMA, "mode": "self-test",
                      "verdict": "PASS" if ok else "FAIL",
                      "failures": failures}, indent=2))
    return 0 if ok else 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="session_jsonl_live_e2e.py",
        description="Bounded live E2E: chat send -> session trace -> debug export.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true",
                      help="print planned request/paths/command (default)")
    mode.add_argument("--live", action="store_true",
                      help="perform exactly 1 send + trace wait + export checks")
    mode.add_argument("--self-test", action="store_true",
                      help="fixture checks of judgment logic, no network")
    parser.add_argument("--max-sends", type=int, default=1,
                        help="send cap guard; must be 1..3 (default 1)")
    parser.add_argument("--root", default=None, help="repo root (default: auto)")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--message", default=DEFAULT_MESSAGE)
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root \
        else Path(__file__).resolve().parents[1]
    if args.self_test:
        return self_test()
    if not args.message.startswith(SEND_PREFIX):
        print(json.dumps({"schema": SCHEMA, "verdict": "REFUSED",
                          "reason": "message-must-start-with-[devin-test]"},
                         indent=2))
        return 3
    if args.live:
        return run_live(root, args.base_url, args.message, args.max_sends)
    if args.max_sends > MAX_SENDS_CAP:
        print(json.dumps({"schema": SCHEMA, "verdict": "REFUSED",
                          "reason": "max-sends-out-of-range(1-3)"}, indent=2))
        return 3
    plan = build_plan(root, args.base_url, args.message,
                      "devin-e2e-")
    plan["mode"] = "dry-run"
    plan["correlationId"] = plan["correlationId"] + "<8hex-at-run>"
    print(json.dumps(plan, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
