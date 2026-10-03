#!/usr/bin/env python3
"""P-1 jev spend ledger tests — stdlib unittest, temp ledger per test,
no outbound network (loopback only, for the direct-run exemption proof).
Run: python -B scripts/test_jev_spend_ledger.py   (from src root)"""
from __future__ import annotations

import datetime
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from contextlib import redirect_stderr
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts" / "apikit"))
import jev_ledger  # noqa: E402

UTC = datetime.timezone.utc
KST = jev_ledger.KST
# 2026-09-30 12:00 KST == 2026-09-30 03:00 UTC — a mid-day "now" for tests.
NOW = datetime.datetime(2026, 9, 30, 3, 0, 0, tzinfo=UTC)
TODAY = "2026-09-30"


def _ts(day_utc=TODAY, h=0, m=0):
    return datetime.datetime.strptime("%s %02d:%02d" % (day_utc, h, m),
                                      "%Y-%m-%d %H:%M").replace(tzinfo=UTC)


def _e(ts=None, **kw):
    kw.setdefault("agent", "devin")
    kw.setdefault("session", "s-test")
    kw.setdefault("caller", "test")
    kw.setdefault("http_status", 200)
    return jev_ledger.new_entry(ts=ts or _ts(), **kw)


class LedgerCase(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = Path(self.dir.name) / "ledger.jsonl"

    def tearDown(self):
        self.dir.cleanup()

    def gate(self, env=None, now=NOW):
        env = {"AWX_AGENT_SESSION": "s-run", "AWX_AGENT_NAME": "devin",
               **(env or {})}
        return jev_ledger.gate(jev_ledger.GATEWAY_HOST, env=env,
                               now=now, path=self.path)

    def fill(self, n, **kw):
        for _ in range(n):
            jev_ledger.append(_e(**kw), path=self.path)


class AppendRoundtrip(LedgerCase):
    def test_append_one_line_and_read(self):
        e = _e(cost="0.0000197")
        jev_ledger.append(e, path=self.path)
        lines = self.path.read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(lines), 1)
        got = json.loads(lines[0])
        self.assertEqual(got["costUsd"], 0.0000197)  # str -> float
        self.assertEqual(got["reservedUsd"], 0.0002)
        for k in ("tsUtc", "dayKst", "agent", "session", "purpose", "caller",
                  "httpStatus", "reason", "inputTokens", "outputTokens",
                  "retryOf", "backfilled"):
            self.assertIn(k, got)

    def test_null_cost_stays_null(self):
        e = _e(cost=None)
        jev_ledger.append(e, path=self.path)
        got = json.loads(self.path.read_text().splitlines()[0])
        self.assertIsNone(got["costUsd"])

    def test_src_ref_dedupe(self):
        e = _e(src_ref="file.json:1")
        self.assertIsNotNone(jev_ledger.append_once(e, path=self.path))
        self.assertIsNone(jev_ledger.append_once(_e(src_ref="file.json:1"),
                                                 path=self.path))
        self.assertEqual(len(jev_ledger.iter_entries(self.path)), 1)

    def test_no_secret_fields(self):
        e = _e()
        self.assertNotIn("credentialSha8", e)
        self.assertNotIn("key", e)
        self.assertNotIn("question", e)


class GateRules(LedgerCase):
    def test_499_allows_500_blocks(self):
        self.fill(499)
        self.assertIsNone(self.gate())
        jev_ledger.append(_e(), path=self.path)
        self.assertEqual(self.gate(), "day-cap")

    def test_day_counts_all_agents(self):
        jev_ledger.append(_e(agent="grok"), path=self.path)
        jev_ledger.append(_e(agent="codex"), path=self.path)
        self.assertEqual(jev_ledger.summarize(now=NOW, path=self.path)
                         ["dayCount"], 2)

    def test_period_cap_boundary(self):
        # 0.4999 < 0.50 -> allowed; exactly 0.50 -> refused.
        jev_ledger.append(_e(cost=0.4999), path=self.path)
        self.assertIsNone(self.gate())
        jev_ledger.append(_e(cost=0.0001), path=self.path)
        self.assertEqual(self.gate(), "period-cap")

    def test_null_cost_counts_reserved(self):
        jev_ledger.append(_e(cost=None), path=self.path)
        jev_ledger.append(_e(cost=None), path=self.path)
        s = jev_ledger.summarize(now=NOW, path=self.path)
        self.assertAlmostEqual(s["totalUsd"], 0.0004, places=9)

    def test_403_today_blocks_other_agent(self):
        jev_ledger.append(_e(agent="grok", http_status=403), path=self.path)
        # 다른 에이전트 세션도 오늘의 403으로 차단된다.
        self.assertEqual(self.gate(env={"AWX_AGENT_SESSION": "s-other"}),
                         "auth-fail-today")

    def test_401_402_also_block(self):
        for status in (401, 402):
            self.dir2 = tempfile.TemporaryDirectory()
            p = Path(self.dir2.name) / "l.jsonl"
            jev_ledger.append(_e(http_status=status), path=p)
            got = jev_ledger.gate(jev_ledger.GATEWAY_HOST,
                                  env={"AWX_AGENT_SESSION": "s"}, now=NOW,
                                  path=p)
            self.assertEqual(got, "auth-fail-today")
            self.dir2.cleanup()

    def test_403_yesterday_does_not_block_today(self):
        jev_ledger.append(_e(ts=_ts("2026-09-29"), http_status=403),
                          path=self.path)
        self.assertIsNone(self.gate())

    def test_two_429_today_block(self):
        self.fill(1, http_status=429)
        self.assertIsNone(self.gate())
        jev_ledger.append(_e(http_status=429), path=self.path)
        self.assertEqual(self.gate(), "rate-limit-today")

    def test_session_required(self):
        self.assertEqual(jev_ledger.gate(jev_ledger.GATEWAY_HOST, env={},
                                         now=NOW, path=self.path),
                         "session-missing")

    def test_session_cap(self):
        self.fill(150, session="s-big")
        self.assertEqual(self.gate(env={"AWX_AGENT_SESSION": "s-big"}),
                         "session-cap")
        # 다른 세션은 영향 없음
        self.assertIsNone(self.gate(env={"AWX_AGENT_SESSION": "s-run"}))

    def test_period_end(self):
        # 2026-10-07 23:59 KST = 14:59 UTC — 이후 거부, 직전 허용.
        before = datetime.datetime(2026, 10, 7, 14, 58, 59, tzinfo=UTC)
        after = datetime.datetime(2026, 10, 7, 15, 0, 0, tzinfo=UTC)
        self.assertIsNone(self.gate(now=before))
        self.assertEqual(self.gate(now=after), "period-ended")

    def test_non_gateway_host_never_gated(self):
        self.fill(600)  # 한도 초과 상태에서도
        for host in ("127.0.0.1", "localhost", "mock.internal", ""):
            self.assertIsNone(jev_ledger.gate(
                host, env={"AWX_AGENT_SESSION": "s"}, now=NOW,
                path=self.path))

    def test_spend_80_warns_but_allows(self):
        self.fill(400)  # 400/500 = 80%
        buf = io.StringIO()
        with redirect_stderr(buf):
            got = self.gate()
        self.assertIsNone(got)
        self.assertIn("SPEND_80", buf.getvalue())


class KstBoundary(LedgerCase):
    def test_day_rollover(self):
        # KST 자정 = UTC 15:00. 14:59:59Z는 전날, 15:00:00Z는 다음날.
        a = _e(ts=datetime.datetime(2026, 9, 30, 14, 59, 59, tzinfo=UTC))
        b = _e(ts=datetime.datetime(2026, 9, 30, 15, 0, 0, tzinfo=UTC))
        self.assertEqual(a["dayKst"], "2026-09-30")
        self.assertEqual(b["dayKst"], "2026-10-01")
        jev_ledger.append(a, path=self.path)
        jev_ledger.append(b, path=self.path)
        s = jev_ledger.summarize(now=NOW, path=self.path)
        self.assertEqual(s["dayCount"], 1)  # 오늘(09-30)은 a만

    def test_yesterday_429_not_counted(self):
        jev_ledger.append(_e(ts=_ts("2026-09-29"), http_status=429),
                          path=self.path)
        jev_ledger.append(_e(ts=_ts("2026-09-29"), http_status=429),
                          path=self.path)
        self.assertIsNone(self.gate())


class EnvOverride(LedgerCase):
    def test_awx_jev_ledger_env(self):
        old = os.environ.get("AWX_JEV_LEDGER")
        os.environ["AWX_JEV_LEDGER"] = str(self.path)
        try:
            self.assertEqual(jev_ledger.ledger_path(), self.path)
            jev_ledger.append(_e())
            self.assertEqual(len(jev_ledger.iter_entries()), 1)
        finally:
            if old is None:
                del os.environ["AWX_JEV_LEDGER"]
            else:
                os.environ["AWX_JEV_LEDGER"] = old


@unittest.skipUnless(shutil.which("node"), "node not on PATH")
class DirectRunRefusal(unittest.TestCase):
    """smoke.mjs 직접 실행 차단: 비-loopback + AWX_JEV_VIA_WRAPPER 없음."""

    SMOKE = ROOT / "scripts" / "jev_gateway_smoke.mjs"

    def _run(self, env_extra):
        env = dict(os.environ)
        env.pop("AWX_JEV_VIA_WRAPPER", None)
        env.update(env_extra)
        env.setdefault("AI_GATEWAY_API_KEY", "synthetic-local-test")
        proc = subprocess.run(
            ["node", str(self.SMOKE)], capture_output=True, text=True,
            timeout=30, cwd=str(ROOT), env=env)
        try:
            return proc.returncode, json.loads(proc.stdout.strip())
        except ValueError:
            return proc.returncode, {}

    def test_remote_without_wrapper_refused(self):
        # 게이트가 fetch 이전에 발동하므로 이 unroutable 주소에는 아무 패킷도
        # 나가지 않는다 (TEST-NET-1, 그리고 allowlist에 넣어야 도달하는 지점).
        rc, out = self._run({
            "AWX_JEV_ENDPOINT": "https://192.0.2.1/v1/evaluate",
            "AWX_JEV_ALLOW_HOST": "192.0.2.1",
            "AWX_JEV_TIMEOUT_MS": "2000",
        })
        self.assertEqual(rc, 3)
        self.assertEqual(out.get("reason"), "direct-live-refused")

    def test_loopback_without_wrapper_allowed(self):
        class H(BaseHTTPRequestHandler):
            def do_POST(self):
                n = int(self.headers.get("Content-Length") or 0)
                self.rfile.read(n)
                body = json.dumps({
                    "model": "typesafe-ai/jev",
                    "answers": {
                        "routeProfile": {"choice": "KEEP_BASELINE"},
                        "freshnessNeeded": {"probability": 0},
                    }}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *a):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), H)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            port = server.server_address[1]
            rc, out = self._run({
                "AWX_JEV_ENDPOINT": "http://127.0.0.1:%d/v1/evaluate" % port,
                "AWX_JEV_ALLOW_HOST": "127.0.0.1",
            })
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)
        self.assertEqual(rc, 0)
        self.assertEqual(out.get("jevResult"), "PASS")


if __name__ == "__main__":
    unittest.main()
