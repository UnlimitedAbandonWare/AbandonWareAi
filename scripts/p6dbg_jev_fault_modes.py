#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P6-D5: Jev fault-mode scenarios — wraps jev_mock_gateway.py (never edits it).

Reused: jev_mock_gateway.{serve, scenario_response, ok_body, response_body,
CHOICES, MockServer}. Added: request-indexed SEQUENCE scenarios so a human can
watch whether a blocked flag (X-01 authBlocked / planGate) clears after recovery:
first K requests fail, request K+1.. succeeds — all on one port, one process.

Scenarios (sequence steps: {requests: N, status|scenario|ok|hang_ms}):
  auth_blocked_recover : 401 x K -> 200        (X-01: authBlocked must clear)
  plan_gate_recover    : 403 zdr_plan_gate x K -> 200
  policy_block_recover : 403 provider_403 x K -> 200
  schema_violation     : 200 probs_out_of_range always (caller clamp check)
  timeout_then_ok      : hang x K -> 200
  score_bait           : 200 normal probs; pair with a manipulative prompt to
                         observe whether caller accepts out-of-range probs
                         (use probs_out_of_range step for the bait response)

Loopback 127.0.0.1 only, stdlib only, request bodies counted not retained.
--fire K sends K probe POSTs and prints observed status sequence (manual X-01 view).
Exit 0 ok / 1 failure observed in fire mode / 2 bad args.
"""
from __future__ import annotations
import argparse, json, sys, threading, urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import jev_mock_gateway as gw  # noqa: E402  (wrapper, not a copy)

SCENARIOS = {
    "auth_blocked_recover": [
        {"requests": 3, "status": 401}, {"requests": -1, "ok": True}],
    "plan_gate_recover": [
        {"requests": 3, "scenario": "zdr_plan_gate"}, {"requests": -1, "ok": True}],
    "policy_block_recover": [
        {"requests": 3, "scenario": "provider_403"}, {"requests": -1, "ok": True}],
    "schema_violation": [
        {"requests": -1, "scenario": "probs_out_of_range"}],
    "timeout_then_ok": [
        {"requests": 2, "scenario": "hang", "hang_ms": 1500}, {"requests": -1, "ok": True}],
    "score_bait": [
        {"requests": -1, "scenario": "probs_out_of_range"}],
}


def make_seq_handler(steps, records, choice="HYBRID"):
    """Sequence handler: step i serves until its `requests` budget is consumed."""
    state = {"n": 0}
    lock = threading.Lock()

    def current_step():
        with lock:
            state["n"] += 1
            n = state["n"]
        acc = 0
        for s in steps:
            if s["requests"] < 0:
                return s
            acc += s["requests"]
            if n <= acc:
                return s
        return steps[-1]

    class Handler(gw.BaseHTTPRequestHandler.__mro__[1] if False else object):
        pass  # placeholder, real class below

    from http.server import BaseHTTPRequestHandler

    class SeqHandler(BaseHTTPRequestHandler):
        def do_POST(self):  # noqa: N802
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            records.append({
                "n": len(records) + 1, "path": self.path.split("?", 1)[0],
                "bodyBytes": len(raw),
                "authorizationPresent": self.headers.get("Authorization") is not None,
            })
            del raw
            step = current_step()
            if step.get("scenario") == "hang":
                import time
                time.sleep(step.get("hang_ms", 1000) / 1000.0)
                self.close_connection = True
                return
            if step.get("ok"):
                status, body = 200, gw.ok_body(b"", choice)
            elif step.get("scenario"):
                status, body, _slow = gw.scenario_response(step["scenario"], choice)
            else:
                status, body = step.get("status", 200), gw.response_body(step.get("status", 200))
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *a):
            return

    return SeqHandler


def fire(port, count, path="/v1/evaluate"):
    """Sequential probe POSTs; returns observed status list."""
    seen = []
    for i in range(count):
        body = json.dumps({"questions": {"routeDecision": True}, "n": i}).encode()
        req = urllib.request.Request(
            f"http://127.0.0.1:{port}{path}", data=body,
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer synthetic-local-mock"})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                seen.append(r.status)
        except urllib.error.HTTPError as e:
            seen.append(e.code)
        except Exception as e:
            seen.append(f"err:{type(e).__name__}")
    return seen


def serve_scenario(name, port, records):
    if name not in SCENARIOS:
        raise ValueError("scenario-not-allowed")
    server = gw.MockServer(("127.0.0.1", port), make_seq_handler(SCENARIOS[name], records))
    if server.server_address[0] != "127.0.0.1":
        server.server_close()
        raise RuntimeError("bind-not-loopback")
    return server


def main() -> int:
    ap = argparse.ArgumentParser(description="Jev fault-mode sequences (wraps jev_mock_gateway)")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--scenario", choices=sorted(SCENARIOS))
    ap.add_argument("--port", type=int, default=0)
    ap.add_argument("--fire", type=int, default=0, help="send K probe POSTs then report")
    ap.add_argument("--serve", action="store_true", help="stay up until Ctrl-C")
    a = ap.parse_args()

    if a.list:
        print(json.dumps({"scenarios": SCENARIOS, "loopbackOnly": True}, indent=2))
        return 0
    if not a.scenario:
        print(json.dumps({"status": "FAIL", "reason": "--scenario required"}))
        return 2

    records: list[dict] = []
    server = serve_scenario(a.scenario, a.port, records)
    port = server.server_address[1]
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    print(json.dumps({"status": "LISTENING", "port": port, "scenario": a.scenario}))
    try:
        if a.fire:
            seen = fire(port, a.fire)
            expected = [s.get("status", 403 if s.get("scenario") in ("zdr_plan_gate", "provider_403")
                               else ("hang" if s.get("scenario") == "hang" else 200))
                        for s in SCENARIOS[a.scenario]]
            ok = seen[-1] == 200 and seen[0] != 200
            print(json.dumps({"observed": seen, "records": records,
                              "flag_recovery_observable": ok}))
            return 0 if all(isinstance(s, int) for s in seen) else 1
        if a.serve:
            t.join()
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
