#!/usr/bin/env python3
"""test_dot_evidence_feed.py — dot_evidence_feed.py 유닛테스트 (읽기 전용).

격리: DOT_FEED_EVENTS / DOT_FEED_LAUNCHER env를 tmp로 지정 — 실제 로그 무접촉.
실행: python -B scripts/test_dot_evidence_feed.py
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "dot_evidence_feed.py"

SECRET_MARK = "RAWQUERYMARKER-9f8e7d-개인쿼리원문"


def ndjson_row(ts, trace, probe="ORCHESTRATION", where="x.y", data=None, msg="m"):
    return json.dumps({"ts": ts, "probe": probe, "level": "INFO",
                       "fingerprint": "hash:fp", "message": msg,
                       "where": where, "sid": "hash:sid1", "traceId": trace,
                       "requestId": trace, "data": data or {},
                       "error": None}, ensure_ascii=True)


def make_events(tmp: Path, rows) -> Path:
    p = tmp / "debug-events.ndjson"
    p.write_text("\n".join(rows) + "\n", encoding="utf-8")
    return p


def now_iso(hours_ago=0.0):
    return (datetime.now(timezone.utc) - timedelta(hours=hours_ago)).isoformat().replace("+00:00", "Z")


class FeedCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dot-feed-"))
        self.launcher = self.tmp / "rag-launcher"
        (self.launcher / "20261005-190353-aaaa").mkdir(parents=True)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_feed(self, events: Path, *args):
        env = dict(os.environ)
        env["DOT_FEED_EVENTS"] = str(events)
        env["DOT_FEED_LAUNCHER"] = str(self.launcher)
        return subprocess.run(
            [sys.executable, "-B", str(SCRIPT), *args],
            capture_output=True, encoding="utf-8", errors="replace",
            env=env, timeout=60)

    def test_latest_prints_fixed_lines(self):
        rows = [
            ndjson_row(now_iso(1), "hash:t1", "WEB_SEARCH",
                       "SearchTraceConsoleLogger.maybeLog",
                       {"providers": {"brave": {"requestedCount": 12,
                                                "returnedCount": 3,
                                                "afterFilterCount": 0,
                                                "timeout": True}},
                        "queryHash": "hash:q1", "queryLength": 13}),
            ndjson_row(now_iso(0.9), "hash:t1", "ORCHESTRATION",
                       "RagEvidenceAttributionService.promoteForPrompt",
                       {"candidateCount": 3, "promotedCount": 0,
                        "citationMin": 2, "disabledReason": "no_citable_locator"}),
            ndjson_row(now_iso(0.8), "hash:t1", "ORCHESTRATION",
                       "rag-control-compose",
                       {"action": "HOLD", "reasonCode": "verifier_unavailable",
                        "hardGuard": True}),
            ndjson_row(now_iso(0.5), "hash:t2", "ORCHESTRATION", "x.y", {"action": "CONTINUE"}),
        ]
        ev = make_events(self.tmp, rows)
        r = self.run_feed(ev, "--latest")
        self.assertEqual(r.returncode, 0, r.stderr)
        out = r.stdout
        self.assertIn("[WINDOW]", out)
        self.assertIn("[REQUEST] reqHash:hash:t2", out)
        self.assertIn("[RETRIEVE]", out)
        self.assertIn("[PLAN/GATES]", out)
        self.assertIn("[VERIFIER]", out)
        self.assertIn("[HOLD_ACTION]", out)
        self.assertIn("[SOURCES]", out)
        self.assertIn("[SUMMARY]", out)

    def test_trace_select_and_hold_summary(self):
        rows = [
            ndjson_row(now_iso(1), "hash:t1", "ORCHESTRATION",
                       "rag-control-compose",
                       {"action": "HOLD", "reasonCode": "verifier_unavailable",
                        "hardGuard": True}),
            ndjson_row(now_iso(0.5), "hash:t2", "ORCHESTRATION", "x.y",
                       {"action": "CONTINUE"}),
        ]
        ev = make_events(self.tmp, rows)
        r = self.run_feed(ev, "--trace", "hash:t1")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("reqHash:hash:t1", r.stdout)
        self.assertIn("action:HOLD", r.stdout)
        self.assertIn("hardGuardHeld:true", r.stdout)
        self.assertIn("verifier_unavailable", r.stdout)

    def test_request_alias_and_official_only_summary(self):
        rows = [
            ndjson_row(now_iso(1), "hash:req9", "WEB_SEARCH",
                       "WebFailSoftSearchAspect.applyStages",
                       {"officialOnly": True, "minCitations": 2,
                        "outCount": 3, "starvationFallbackUsed": True}),
            ndjson_row(now_iso(0.9), "hash:req9", "WEB_SEARCH",
                       "SearchTraceConsoleLogger.maybeLog",
                       {"providers": {"brave": {"returnedCount": 3,
                                                "afterFilterCount": 0,
                                                "timeout": False}}}),
        ]
        ev = make_events(self.tmp, rows)
        r = self.run_feed(ev, "--request", "req9")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("officialOnly:true", r.stdout)
        self.assertIn("minCitations:2", r.stdout)
        self.assertIn("rawCount:3", r.stdout)
        self.assertIn("프롬프트 전 탈락", r.stdout)

    def test_window_expansion_finds_old_trace(self):
        rows = [ndjson_row(now_iso(5), "hash:old1", "ORCHESTRATION", "x.y",
                           {"action": "CONTINUE"})]
        ev = make_events(self.tmp, rows)
        r = self.run_feed(ev, "--latest", "--since-hours", "2")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("windowHours=24", r.stdout)

    def test_no_data_exit3(self):
        ev = make_events(self.tmp, [])
        r = self.run_feed(ev, "--latest")
        self.assertEqual(r.returncode, 3)

    def test_missing_file_exit3(self):
        r = self.run_feed(self.tmp / "absent.ndjson", "--latest")
        self.assertEqual(r.returncode, 3)

    def test_raw_query_and_secret_never_printed(self):
        rows = [ndjson_row(now_iso(0.5), "hash:tsec", "WEB_SEARCH", "x.y",
                           {"canonicalQuery": SECRET_MARK,
                            "executedQuery": SECRET_MARK,
                            "providers": {"brave": {"returnedCount": 1,
                                                    "afterFilterCount": 1,
                                                    "timeout": False}}})]
        ev = make_events(self.tmp, rows)
        r = self.run_feed(ev, "--latest")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn(SECRET_MARK, r.stdout)
        self.assertNotIn("RAWQUERYMARKER", r.stdout)

    def test_json_mode(self):
        rows = [ndjson_row(now_iso(0.5), "hash:tj", "ORCHESTRATION", "x.y",
                           {"action": "CONTINUE"})]
        ev = make_events(self.tmp, rows)
        r = self.run_feed(ev, "--latest", "--json")
        self.assertEqual(r.returncode, 0, r.stderr)
        payload = json.loads(r.stdout)
        self.assertEqual(payload["schemaVersion"], "awx.dot-evidence-feed.v1")
        self.assertEqual(payload["trace"], "hash:tj")


if __name__ == "__main__":
    unittest.main(verbosity=2)
