#!/usr/bin/env python3
"""api_first_mock_matrix -- 127.0.0.1-only OpenAI-compatible mock that replays
the failure scenarios Codex's Plan9 WP-B fallback rules must classify.

No outbound calls, no provider keys, bodies are synthetic. Scenario is chosen
per request via `X-Mock-Scenario: <name>` header or a trailing path segment
(`/v1/chat/completions/<scenario>` or `/<scenario>`).

Scenarios (11):
  ok                       200 chat.completion JSON
  timeout                  holds the socket --delay-ms then answers ok
  http503                  503 server_error
  http429_transient        429 rate_limit, Retry-After: 1
  http429_retry_after_long 429 rate_limit, Retry-After: 120
  http429_quota            429 insufficient_quota body
  http401                  401 authentication_error
  http403                  403 permission_error
  http400                  400 invalid_request_error
  partial_then_error       200 SSE: a few data: chunks then connection cut
                           (no [DONE]) -- must NOT trigger replay/fallback
  slow_first_token         200 SSE: first chunk delayed --delay-ms, then
                           completes normally

Subcommands:
  serve   --port N --pid-file P   bind 127.0.0.1:N, record owned PID, serve
  samples --out DIR [--port N]    save one response per scenario to DIR,
                                  then stop. In-process server; leaves 0
                                  processes.
  stop    --pid-file P            stop ONLY the PID recorded in the pid file
                                  when its command line still names this
                                  script; else refuse.
  table                           print the expected-action matrix only.

Ports 18180/18181/18182 (live app/mgmt/netty) are refused. Exit codes:
0 ok / 2 usage / 3 bad port / 4 stop refused or failed.
"""
from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SCENARIOS = (
    "ok", "timeout", "http503", "http429_transient", "http429_retry_after_long",
    "http429_quota", "http401", "http403", "http400", "partial_then_error",
    "slow_first_token",
)

# Expected application behavior per Codex plan9 directive (WP-B fallback rules).
EXPECTED = {
    "ok": "answer on this route; no fallback call",
    "timeout": "classify timeout -> NEXT route within deadline/call cap",
    "http503": "classify 5xx -> NEXT route within deadline/call cap",
    "http429_transient": "transient 429 -> NEXT route (bounded, no spin)",
    "http429_retry_after_long": "Retry-After exceeds remaining deadline -> no retry; surface rate_limited",
    "http429_quota": "quota -> NEVER retry same route; next route once; STOP_AND_ROOT_CAUSE for the caller",
    "http401": "auth invalid -> NEVER retry same route; next route once",
    "http403": "forbidden -> NEVER retry same route; next route once",
    "http400": "request error -> no fallback, no replay",
    "partial_then_error": "first tokens already sent -> no fallback, no replay",
    "slow_first_token": "slow start, normal finish -> no fallback",
}

BLOCKED_PORTS = {18180, 18181, 18182}
DEFAULT_TIMEOUT_MS = 30000
DEFAULT_SLOW_MS = 2500

_CHUNK = 'data: {"id":"mock","object":"chat.completion.chunk","choices":[{"delta":{"content":"%s"},"index":0}]}\n\n'


def _ok_body() -> bytes:
    return json.dumps({
        "id": "chatcmpl-mock", "object": "chat.completion",
        "created": 0, "model": "mock-model",
        "choices": [{"index": 0, "finish_reason": "stop",
                     "message": {"role": "assistant",
                                 "content": "synthetic mock answer"}}],
        "usage": {"prompt_tokens": 3, "completion_tokens": 4, "total_tokens": 7},
    }).encode()


def _err(status: int, etype: str, msg: str, code=None) -> bytes:
    body = {"error": {"message": msg, "type": etype}}
    if code:
        body["error"]["code"] = code
    return json.dumps(body).encode()


class Handler(BaseHTTPRequestHandler):
    delay_ms = DEFAULT_TIMEOUT_MS
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):  # quiet
        pass

    def _scenario(self) -> str:
        h = (self.headers.get("X-Mock-Scenario") or "").strip().lower()
        if h in SCENARIOS:
            return h
        seg = self.path.rstrip("/").rsplit("/", 1)[-1].lower()
        return seg if seg in SCENARIOS else "ok"

    def _delay(self) -> int:
        try:
            return int(self.headers.get("X-Mock-Delay-Ms") or self.delay_ms)
        except ValueError:
            return self.delay_ms

    def _send(self, status: int, body: bytes, extra=None):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()

    def _sse(self, chunks, slow_ms=0, cut=False):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.close_connection = True  # no Content-Length: close marks end
        for i, piece in enumerate(chunks):
            if slow_ms and i == 0:
                time.sleep(slow_ms / 1000.0)
            try:
                self.wfile.write((_CHUNK % piece).encode())
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return
        if cut:
            try:
                self.connection.shutdown(2)  # cut mid-stream, no [DONE]
            except OSError:
                pass
            return
        try:
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):  # noqa: N802
        sc = self._scenario()
        delay = self._delay()
        try:
            n = int(self.headers.get("Content-Length") or 0)
            if n:
                self.rfile.read(n)  # request body counted and discarded
        except (ValueError, OSError):
            pass

        if sc == "ok":
            self._send(200, _ok_body())
        elif sc == "timeout":
            time.sleep(max(0, delay) / 1000.0)
            self._send(200, _ok_body(), {"X-Mock-Note": "answered-after-delay"})
        elif sc == "http503":
            self._send(503, _err(503, "server_error", "synthetic 503"))
        elif sc == "http429_transient":
            self._send(429, _err(429, "rate_limit", "synthetic transient 429"),
                       {"Retry-After": "1"})
        elif sc == "http429_retry_after_long":
            self._send(429, _err(429, "rate_limit", "synthetic 429 long retry"),
                       {"Retry-After": "120"})
        elif sc == "http429_quota":
            self._send(429, _err(429, "insufficient_quota",
                                 "synthetic quota exhausted", "insufficient_quota"))
        elif sc == "http401":
            self._send(401, _err(401, "authentication_error",
                                 "synthetic invalid key", "invalid_api_key"))
        elif sc == "http403":
            self._send(403, _err(403, "permission_error", "synthetic forbidden"))
        elif sc == "http400":
            self._send(400, _err(400, "invalid_request_error", "synthetic bad request"))
        elif sc == "partial_then_error":
            self._sse(["hel", "lo", "-par"], cut=True)
        else:  # slow_first_token
            self._sse(["slow", "-ok"], slow_ms=max(0, delay))


class MockServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def check_port(port: int) -> int:
    if port in BLOCKED_PORTS:
        print(f"port {port} refused: live app/mgmt/netty port", file=sys.stderr)
        return 3
    if not (0 < port < 65536):
        print(f"port {port} out of range", file=sys.stderr)
        return 3
    return 0


def cmd_serve(args) -> int:
    rc = check_port(args.port)
    if rc:
        return rc
    Handler.delay_ms = args.delay_ms
    server = MockServer(("127.0.0.1", args.port), Handler)
    pid_path = Path(args.pid_file)
    pid_path.parent.mkdir(parents=True, exist_ok=True)
    pid_path.write_text(json.dumps({
        "pid": os.getpid(), "port": server.server_address[1],
        "script": Path(__file__).name,
        "startedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }), encoding="utf-8")
    print(json.dumps({"listening": f"127.0.0.1:{server.server_address[1]}",
                      "pidFile": str(pid_path)}))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        try:
            pid_path.unlink(missing_ok=True)
        except OSError:
            pass
    return 0


def _request(port: int, scenario: str, delay_ms: int):
    """One loopback POST; returns {status, headers, body} or {error}."""
    import http.client
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=30)
    body = json.dumps({"model": "mock", "messages": [{"role": "user", "content": "x"}],
                       "stream": scenario in ("partial_then_error", "slow_first_token")})
    headers = {"Content-Type": "application/json", "X-Mock-Scenario": scenario,
               "X-Mock-Delay-Ms": str(delay_ms)}
    try:
        conn.request("POST", "/v1/chat/completions", body=body, headers=headers)
        res = conn.getresponse()
        data = res.read()
        return {"status": res.status,
                "headers": {k: v for k, v in res.getheaders()
                            if k.lower() in ("retry-after", "content-type", "x-mock-note")},
                "body": data.decode("utf-8", errors="replace")}
    except Exception as e:  # connection cut is the point of partial_then_error
        return {"status": None, "error": f"{type(e).__name__}: {e}"}
    finally:
        conn.close()


def cmd_samples(args) -> int:
    rc = check_port(args.port) if args.port else 0
    if rc:
        return rc
    Handler.delay_ms = args.delay_ms
    server = MockServer(("127.0.0.1", args.port), Handler)
    port = server.server_address[1]
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    index = []
    try:
        for sc in SCENARIOS:
            d = args.short_delay_ms if sc in ("timeout", "slow_first_token") else args.delay_ms
            r = _request(port, sc, d)
            sample = {"scenario": sc, "expectedAction": EXPECTED[sc], **r}
            (out / f"{sc}.json").write_text(
                json.dumps(sample, indent=2, ensure_ascii=False), encoding="utf-8")
            index.append({"scenario": sc, "status": r.get("status"),
                          "error": r.get("error")})
        (out / "index.json").write_text(json.dumps({
            "schema": "awx.api-first-mock-samples.v1",
            "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "samples": index}, indent=2, ensure_ascii=False), encoding="utf-8")
        md = ["# mock scenario -> expected action (Codex plan9 WP-B)", "",
              "| scenario | response | expected app action |", "|---|---|---|"]
        for sc in SCENARIOS:
            md.append(f"| {sc} | see {sc}.json | {EXPECTED[sc]} |")
        (out / "EXPECTED.md").write_text("\n".join(md) + "\n", encoding="utf-8")
        print(json.dumps({"samples": index, "out": str(out)}))
        return 0
    finally:
        server.shutdown()
        server.server_close()
        t.join(timeout=5)


def cmd_stop(args) -> int:
    pid_path = Path(args.pid_file)
    if not pid_path.is_file():
        print(f"pid file missing: {pid_path}", file=sys.stderr)
        return 4
    try:
        info = json.loads(pid_path.read_text(encoding="utf-8"))
        pid = int(info["pid"])
    except (json.JSONDecodeError, KeyError, ValueError) as e:
        print(f"pid file unreadable: {e}", file=sys.stderr)
        return 4
    if pid == os.getpid():
        print("refusing to stop own process", file=sys.stderr)
        return 4
    # verify the recorded process is still this script's `serve`
    try:
        q = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             f"(Get-CimInstance Win32_Process -Filter 'ProcessId={pid}').CommandLine"],
            capture_output=True, text=True, timeout=15)
        cmdline = (q.stdout or "").strip()
    except (OSError, subprocess.TimeoutExpired):
        cmdline = ""
    if Path(__file__).name not in cmdline or "serve" not in cmdline:
        print(f"pid {pid} does not look like this mock (cmdline mismatch); refusing",
              file=sys.stderr)
        return 4
    try:
        os.kill(pid, signal.SIGTERM)
    except OSError:
        try:
            subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                           capture_output=True, timeout=15)
        except (OSError, subprocess.TimeoutExpired):
            print(f"stop failed for pid {pid}", file=sys.stderr)
            return 4
    pid_path.unlink(missing_ok=True)
    print(json.dumps({"stopped": pid}))
    return 0


def cmd_table() -> int:
    for sc in SCENARIOS:
        print(f"{sc:26s} -> {EXPECTED[sc]}")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("serve")
    p.add_argument("--port", type=int, required=True)
    p.add_argument("--pid-file", required=True)
    p.add_argument("--delay-ms", type=int, default=DEFAULT_TIMEOUT_MS)
    p = sub.add_parser("samples")
    p.add_argument("--out", required=True)
    p.add_argument("--port", type=int, default=0)
    p.add_argument("--delay-ms", type=int, default=DEFAULT_TIMEOUT_MS)
    p.add_argument("--short-delay-ms", type=int, default=DEFAULT_SLOW_MS,
                   help="delay actually used for timeout/slow_first_token samples")
    p = sub.add_parser("stop")
    p.add_argument("--pid-file", required=True)
    sub.add_parser("table")
    args = ap.parse_args(argv)
    if args.cmd == "serve":
        return cmd_serve(args)
    if args.cmd == "samples":
        return cmd_samples(args)
    if args.cmd == "stop":
        return cmd_stop(args)
    return cmd_table()


if __name__ == "__main__":
    sys.exit(main())
