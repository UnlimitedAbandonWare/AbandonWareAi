#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Tests for p6r2dbg_verify_codex_r2.py — run:
    python -B scripts/test_p6r2dbg_verify_codex_r2.py
Exit 0 on success. Pure stdlib; temp dirs only; never touches the live tree."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import p6r2dbg_verify_codex_r2 as v


REPORT_PASS = """# Codex P6-R2 REPORT

R1 PASS — owned routes enforced
R2 PASS — receipts at batch granularity
R3 PASS — no-work distinguished
R4 PASS — triage complete
R5 PASS — focused tests green
R6 PASS — protected files untouched

Changed product files: main/java/com/example/lms/api/ChatApiController.java
"""

T01 = """# T01_TRACE — allowed route provenance

/api/chat/stream -> main/java/com/example/lms/api/ChatApiController.java:1234
/api/chat/cancel -> main/java/com/example/lms/api/ChatApiController.java:1290
/api/chat/state  -> main/java/com/example/lms/api/ChatApiController.java:1350
/api/chat/sessions -> main/java/com/example/lms/api/ChatApiController.java:1500
/api/chat/sessions/{id} -> main/java/com/example/lms/api/ChatApiController.java:1600
"""

TRIAGE = """# VERIFY_TRIAGE

| bucket | count |
| 무해(known-noise) | 132 |
| 조사 필요 | 0 |
| 실제 결함 | 0 |
"""


def make_root(tmp: Path, with_report=True, report=REPORT_PASS,
              t01=T01, triage=TRIAGE) -> Path:
    cdir = tmp / "data" / "agent-handoff" / "codex-p6-r2"
    cdir.mkdir(parents=True, exist_ok=True)
    if with_report:
        (cdir / "REPORT.md").write_text(report, encoding="utf-8")
    if t01 is not None:
        (cdir / "T01_TRACE.md").write_text(t01, encoding="utf-8")
    if triage is not None:
        (cdir / "VERIFY_TRIAGE.md").write_text(triage, encoding="utf-8")
    return tmp


class PendingTest(unittest.TestCase):
    def test_absent_report_is_pending_exit2(self):
        with tempfile.TemporaryDirectory() as td:
            root = make_root(Path(td), with_report=False)
            code, out = v.verify(root)
            self.assertEqual(code, 2)
            self.assertEqual(out["status"], "PENDING")
            self.assertIn("never waits", out["reason"])


class PassFailFixtureTest(unittest.TestCase):
    def _snapshot(self, root):
        snap = v.capture_snapshot(root)
        sp = root / "snap.json"
        sp.write_text(json.dumps(snap), encoding="utf-8")
        return sp

    def test_fake_report_pass(self):
        with tempfile.TemporaryDirectory() as td:
            root = make_root(Path(td))
            sp = self._snapshot(root)
            code, out = v.verify(root, snapshot_path=sp)
            self.assertEqual(code, 0, json.dumps(out["checks"], indent=1))
            self.assertEqual(out["status"], "VERIFIED")
            self.assertEqual(
                out["checks"]["r_claims_vs_junit"]["r_claims"]["R1"], "PASS")
            self.assertEqual(out["checks"]["t01_trace"]["verdict"], "VERIFIED")
            self.assertEqual(out["checks"]["verify_triage_132"]["verdict"],
                             "VERIFIED")

    def test_fake_report_fail_testcount(self):
        bad = REPORT_PASS + "\ntests: 999\n"
        with tempfile.TemporaryDirectory() as td:
            root = make_root(Path(td), report=bad)
            sp = self._snapshot(root)
            code, out = v.verify(root, snapshot_path=sp)
            self.assertEqual(code, 1)
            self.assertEqual(out["status"], "MISMATCH")
            self.assertEqual(out["checks"]["r_claims_vs_junit"]["verdict"],
                             "MISMATCH")

    def test_missing_t01_trace_fails(self):
        with tempfile.TemporaryDirectory() as td:
            root = make_root(Path(td), t01=None)
            sp = self._snapshot(root)
            code, out = v.verify(root, snapshot_path=sp)
            self.assertEqual(code, 1)
            self.assertEqual(out["checks"]["t01_trace"]["verdict"], "MISMATCH")

    def test_wrong_132_sum_fails(self):
        bad_triage = TRIAGE.replace("132", "131")
        with tempfile.TemporaryDirectory() as td:
            root = make_root(Path(td), triage=bad_triage)
            sp = self._snapshot(root)
            code, out = v.verify(root, snapshot_path=sp)
            self.assertEqual(code, 1)
            self.assertEqual(out["checks"]["verify_triage_132"]["verdict"],
                             "MISMATCH")


class HashDriftCounterexampleTest(unittest.TestCase):
    """Prove the hash guard catches a real protected-file change."""

    def test_r51_drift_detected(self):
        with tempfile.TemporaryDirectory() as td:
            root = make_root(Path(td))
            rel = v.R51_PROTECTED[0]
            target = root / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("// original\n", encoding="utf-8")
            snap = v.capture_snapshot(root)
            sp = root / "snap.json"
            sp.write_text(json.dumps(snap), encoding="utf-8")
            target.write_text("// MUTATED by codex\n", encoding="utf-8")
            code, out = v.verify(root, snapshot_path=sp)
            self.assertEqual(code, 1)
            ph = out["checks"]["protected_hashes"]
            self.assertEqual(ph["verdict"], "MISMATCH")
            self.assertTrue(ph["r51_protected"]["changed"])

    def test_hold_drift_detected(self):
        with tempfile.TemporaryDirectory() as td:
            root = make_root(Path(td))
            rel = v.HOLD_FILES[0]
            target = root / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("a\n", encoding="utf-8")
            snap = v.capture_snapshot(root)
            sp = root / "snap.json"
            sp.write_text(json.dumps(snap), encoding="utf-8")
            target.write_text("b\n", encoding="utf-8")
            code, out = v.verify(root, snapshot_path=sp)
            self.assertEqual(code, 1)
            self.assertEqual(out["checks"]["hold_untouched"]["verdict"],
                             "MISMATCH")

    def test_demo_interview_flip_detected(self):
        with tempfile.TemporaryDirectory() as td:
            root = make_root(Path(td))
            rel = "main/resources/application.properties"
            target = root / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("demo.interview.enabled=true\n", encoding="utf-8")
            snap = v.capture_snapshot(root)
            sp = root / "snap.json"
            sp.write_text(json.dumps(snap), encoding="utf-8")
            target.write_text("demo.interview.enabled=false\n",
                              encoding="utf-8")
            code, out = v.verify(root, snapshot_path=sp)
            self.assertEqual(code, 1)
            self.assertEqual(
                out["checks"]["demo_interview_lines"]["verdict"], "MISMATCH")


class UnitTest(unittest.TestCase):
    def test_parse_report(self):
        rep = v.parse_report(REPORT_PASS)
        self.assertEqual(rep["r_claims"]["R1"], "PASS")
        self.assertIn("main/java/com/example/lms/api/ChatApiController.java",
                      rep["changed_files"])

    def test_lease_residual_ignores_self_and_foreign(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            locks = root / v.LOCKS_DIR
            for topic, owner in (("devin-p6-r2-kit", "devin"),
                                 ("codex-p6-r2-lease", "codex"),
                                 ("clean-primitive-debug-ai-impl-0926", "other")):
                d = locks / (topic + ".lock")
                d.mkdir(parents=True)
                (d / "lease.json").write_text(
                    json.dumps({"topic": topic, "ownerId": owner}))
            r = v.check_lease_residual(root, "devin-p6-r2-kit")
            # codex p6-r2 lease flagged; devin self + unrelated foreign ignored
            self.assertEqual(len(r["codex_r2_residuals"]), 1)
            self.assertEqual(r["codex_r2_residuals"][0]["topic"],
                             "codex-p6-r2-lease")
            self.assertEqual(r["verdict"], "MISMATCH")


class LiveSmokeTest(unittest.TestCase):
    def test_real_tree_pending(self):
        # the real root: codex-p6-r2/REPORT.md must not exist yet -> PENDING
        real = Path(__file__).resolve().parent.parent
        if not (real / v.CODEX_REPORT).is_file():
            code, out = v.verify(real)
            self.assertEqual(code, 2)
            self.assertEqual(out["status"], "PENDING")


if __name__ == "__main__":
    unittest.main(verbosity=1)
