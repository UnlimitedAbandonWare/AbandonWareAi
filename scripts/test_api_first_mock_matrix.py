#!/usr/bin/env python3
"""Contract tests for scripts/api_first_mock_matrix.py. Loopback only; the
mock never leaves 127.0.0.1 and never calls a real provider.

Run: python -B scripts/test_api_first_mock_matrix.py
"""
from __future__ import annotations

import http.client
import json
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "api_first_mock_matrix.py"
SCENARIOS = [
    "ok", "timeout", "http503", "http429_transient", "http429_retry_after_long",
    "http429_quota", "http401", "http403", "http400", "partial_then_error",
    "slow_first_token",
]


def run_tool(*argv, timeout=60):
    return subprocess.run(
        [sys.executable, "-B", str(TOOL), *argv],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=timeout,
    )


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def post(port, scenario, delay_ms=100, timeout=15):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        conn.request("POST", "/v1/chat/completions",
                     body='{"model":"m","messages":[]}',
                     headers={"X-Mock-Scenario": scenario,
                              "X-Mock-Delay-Ms": str(delay_ms)})
        res = conn.getresponse()
        return res.status, res.read().decode("utf-8", errors="replace"), dict(res.getheaders())
    finally:
        conn.close()


class TableTest(unittest.TestCase):
    def test_table_lists_all_scenarios(self):
        r = run_tool("table")
        self.assertEqual(r.returncode, 0)
        for sc in SCENARIOS:
            self.assertIn(sc, r.stdout)
        self.assertIn("NEVER retry", r.stdout)

    def test_serve_refuses_live_ports(self):
        for port in ("18180", "18181", "18182"):
            r = run_tool("serve", "--port", port, "--pid-file", "x.pid")
            self.assertEqual(r.returncode, 3, port)


class ServeStopTest(unittest.TestCase):
    def test_serve_then_stop_only_owned_pid(self):
        with tempfile.TemporaryDirectory() as td:
            port = free_port()
            pidf = Path(td) / "mock.pid.json"
            proc = subprocess.Popen(
                [sys.executable, "-B", str(TOOL), "serve", "--port", str(port),
                 "--pid-file", str(pidf)],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, text=True)
            try:
                for _ in range(60):
                    if pidf.exists():
                        break
                    time.sleep(0.1)
                self.assertTrue(pidf.is_file(), "pid file never appeared")
                status, body, headers = post(port, "http401")
                self.assertEqual(status, 401)
                r = run_tool("stop", "--pid-file", str(pidf))
                self.assertEqual(r.returncode, 0, r.stderr)
                proc.wait(timeout=10)
            finally:
                if proc.poll() is None:
                    proc.kill()
            self.assertFalse(pidf.exists())


class SamplesTest(unittest.TestCase):
    def test_samples_cover_all_scenarios_and_stop(self):
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "samples"
            r = run_tool("samples", "--out", str(out), "--short-delay-ms", "200",
                         timeout=120)
            self.assertEqual(r.returncode, 0, r.stderr)
            index = json.loads((out / "index.json").read_text(encoding="utf-8"))
            self.assertEqual(len(index["samples"]), len(SCENARIOS))
            for sc in SCENARIOS:
                self.assertTrue((out / f"{sc}.json").is_file(), sc)
            self.assertTrue((out / "EXPECTED.md").is_file())
            payload = json.loads(r.stdout)
            statuses = {s["scenario"]: s["status"] for s in payload["samples"]}
            self.assertEqual(statuses["ok"], 200)
            self.assertEqual(statuses["http401"], 401)
            self.assertEqual(statuses["http403"], 403)
            self.assertEqual(statuses["http400"], 400)
            self.assertEqual(statuses["http503"], 503)
            for sc in ("http429_transient", "http429_retry_after_long",
                       "http429_quota"):
                self.assertEqual(statuses[sc], 429, sc)
            # no leftover mock process: samples uses an in-process server

    def test_retry_after_headers(self):
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "s"
            r = run_tool("samples", "--out", str(out), "--short-delay-ms", "100",
                         timeout=120)
            self.assertEqual(r.returncode, 0, r.stderr)
            tr = json.loads((out / "http429_transient.json").read_text(encoding="utf-8"))
            self.assertEqual(tr["headers"].get("Retry-After"), "1")
            lng = json.loads((out / "http429_retry_after_long.json").read_text(encoding="utf-8"))
            self.assertEqual(lng["headers"].get("Retry-After"), "120")


if __name__ == "__main__":
    unittest.main()
