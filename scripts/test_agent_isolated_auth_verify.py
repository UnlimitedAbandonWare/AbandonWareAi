#!/usr/bin/env python3
"""test_agent_isolated_auth_verify.py — 격리 인증 검증 러너 단위 테스트.

외부 실행 없음: Gradle·브라우저·포트 lease는 돌리지 않고 순수 함수와
결과 스키마만 검증한다. 실제 격리 실행은 세션당 1회 별도 수행.

실행: python -B scripts/test_agent_isolated_auth_verify.py
"""
from __future__ import annotations

import json
import re
import sys
import unittest
import tempfile
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import agent_isolated_auth_verify as m  # noqa: E402


def _pass_evidence():
    return {
        "scope": "isolated-production-DAO-auth",
        "protectedPath": "/admin/debug-events",
        "freshContexts": 2,
        "restoredAuthState": False,
        "generationPosts": 0,
        "requests": [
            {"endpoint": "/admin/debug-events", "method": "GET",
             "status": 302, "requestHash": "ab12cd34ef56",
             "locationPath": "/login"},
            {"endpoint": "/login", "method": "POST", "status": 302,
             "requestHash": "98fe76dc54ba", "locationPath": "/login",
             "loginError": True},
            {"endpoint": "/admin/debug-events", "method": "GET",
             "status": 200, "requestHash": "1122aabbccdd",
             "locationPath": None},
            {"endpoint": "/logout", "method": "POST", "status": 302,
             "requestHash": "eeff00112233", "locationPath": "/login"},
        ],
        "checks": {"anonymousProtected": 302, "invalidLogin": 302,
                   "invalidAccountProtected": 302, "freshLogin": 302,
                   "authenticatedProtected": 200, "logout": 302,
                   "postLogoutProtected": 302},
        "verdict": "PASS", "failures": [],
    }


class IsolatedAuthVerifyTest(unittest.TestCase):

    def test_verdict_pass_maps_isolated_pass(self):
        verdict, reason = m.verdict_from_evidence(_pass_evidence(), 0, False)
        self.assertEqual("ISOLATED_PASS", verdict)
        self.assertIn("harness", reason)

    def test_pass_evidence_requires_successful_execution(self):
        # A stale/partial PASS receipt cannot overrule the process outcome.
        cases = ((1, False), (None, True), (None, False), (0, True))
        for run_exit, timed_out in cases:
            with self.subTest(run_exit=run_exit, timed_out=timed_out):
                verdict, reason = m.verdict_from_evidence(
                    _pass_evidence(), run_exit, timed_out)
                self.assertNotEqual("ISOLATED_PASS", verdict)
                self.assertIn("execution", reason)

    def test_verdict_fail_maps_isolated_fail(self):
        ev = _pass_evidence()
        ev["verdict"] = "FAIL"
        ev["failures"] = ["post_logout_protected_not_blocked"]
        verdict, reason = m.verdict_from_evidence(ev, 1, False)
        self.assertEqual("ISOLATED_FAIL", verdict)
        self.assertIn("post_logout", reason)

    def test_missing_evidence_is_not_run(self):
        self.assertEqual(("NOT_RUN", "verify_timeout"),
                         m.verdict_from_evidence(None, None, True))
        verdict, reason = m.verdict_from_evidence(None, 1, False)
        self.assertEqual("NOT_RUN", verdict)
        self.assertIn("no_evidence", reason)

    def test_incomplete_checks_fail(self):
        ev = _pass_evidence()
        del ev["checks"]["postLogoutProtected"]
        verdict, reason = m.verdict_from_evidence(ev, 0, False)
        self.assertEqual("ISOLATED_FAIL", verdict)
        self.assertIn("postLogoutProtected", reason)

    def test_secret_string_in_evidence_fails(self):
        ev = _pass_evidence()
        ev["requests"][0]["note"] = "password leak probe"
        verdict, reason = m.verdict_from_evidence(ev, 0, False)
        self.assertEqual("ISOLATED_FAIL", verdict)
        self.assertIn("secret", reason)

    def test_output_schema_has_no_password_or_token(self):
        # 출력 스키마 전체에 password·token 문자열이 0개 — 필드명 포함.
        result = m.emit(
            "ISOLATED_PASS", "harness_pass", port=18234, leaseId="apl-x",
            leasedPortReleased=True,
            evidencePath="data/agent-handoff/codex-autonomy/t/browser-auth-evidence.json",
            checks=_pass_evidence()["checks"],
            requests=_pass_evidence()["requests"],
            generationPosts=0, restoredAuthState=False,
            sharedPortContacts=0,
            secretLeakCount=m.secret_leak_count(
                json.dumps(_pass_evidence(), ensure_ascii=False)),
            gradleExit=0)
        blob = json.dumps(result, ensure_ascii=False)
        self.assertEqual(0, len(re.findall(r"(?i)password|token", blob)))
        self.assertEqual(0, result["secretLeakCount"])
        self.assertIn(result["verdict"],
                      ("ISOLATED_PASS", "ISOLATED_FAIL", "NOT_RUN"))

    def test_protected_port_rejected(self):
        for port in m.PROTECTED_PORTS:
            with self.assertRaises(ValueError):
                m.port_guard(port)
        m.port_guard(18234)  # 정상 포트는 통과

    def test_spawn_error_does_not_publish_private_exception_text(self):
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / "run.log"
            with patch.object(m.subprocess, "run", side_effect=OSError(
                    "pass" + "word=" + "synthetic-private-marker")):
                observed = m.run_harness(Path(folder), 18234,
                    Path(folder) / m.EVIDENCE_NAME, "synthetic", 1, log)
            self.assertEqual((None, False), observed)
            self.assertNotIn("synthetic-private-marker", log.read_text(encoding="utf-8"))
            self.assertIn("harness_spawn_failed", log.read_text(encoding="utf-8"))
            self.assertIn("OSError", log.read_text(encoding="utf-8"))

    def test_release_failure_cannot_complete_a_pass_run(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            def fake_harness(root, port, evidence, *args):
                evidence.write_text(json.dumps(_pass_evidence()), encoding="utf-8")
                return 0, False
            with patch.object(m, "acquire_port", return_value=(
                    {"port": 18234, "leaseId": "synthetic-lease"}, {})), \
                    patch.object(m, "run_harness", side_effect=fake_harness), \
                    patch.object(m, "release_port", return_value=False):
                result = m.verify(root, "synthetic", "synthetic-task", "18200-18399",
                                  1, None, False, mode="browser")
            self.assertFalse(result["leasedPortReleased"])
            self.assertEqual("ISOLATED_FAIL", result["verdict"])
            self.assertEqual("port_release_failed", result["reason"])
            self.assertIsNone(result["sharedPortContacts"])

    def test_dry_run_is_not_run(self):
        root = Path(__file__).resolve().parent.parent
        result = m.verify(root, "devin", "dryrun-task", "18200-18399",
                          60, None, dry_run=True)
        self.assertEqual("NOT_RUN", result["verdict"])
        self.assertEqual("dry_run", result["reason"])
        blob = json.dumps(result, ensure_ascii=False)
        self.assertEqual(0, m.secret_leak_count(blob))


if __name__ == "__main__":
    unittest.main(verbosity=2)
