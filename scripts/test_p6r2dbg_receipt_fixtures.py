#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Tests for p6r2dbg_receipt_fixtures.py — run:
    python -B scripts/test_p6r2dbg_receipt_fixtures.py
Exit 0 on success. Pure stdlib; offline; writes only to a temp dir."""
from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import p6r2dbg_receipt_fixtures as rf


def _fixture(fid):
    spec = next(s for s in rf.SCENARIOS if s["fixtureId"] == fid)
    return rf.build_fixture(spec)


class SevenFixtureTest(unittest.TestCase):
    def test_each_fixture_judges_to_expected_class(self):
        for spec in rf.SCENARIOS:
            fx = _fixture(spec["fixtureId"])
            r = rf.judge(fx)
            self.assertEqual(r["verdict"], "PASS",
                             f"{spec['fixtureId']}: {r}")
            self.assertEqual(r["class"], spec["expectedClass"])
            self.assertEqual(r["violations"], [])

    def test_manifest_has_seven(self):
        self.assertEqual(len(rf.SCENARIOS), 7)
        ids = [s["fixtureId"] for s in rf.SCENARIOS]
        self.assertEqual(len(set(ids)), 7)


class ClassCoverageTest(unittest.TestCase):
    """All 8 classes reachable (7-class taxonomy + durable success)."""

    def _cls(self, **kw):
        base = {"durable": False, "succeededCount": 0, "pendingSize": 0,
                "reasonCode": None, "totalRecords": 3}
        base.update(kw)
        return rf.classify(base)

    def test_classes(self):
        self.assertEqual(self._cls(durable=True, succeededCount=3,
                                   totalRecords=3, reasonCode="durable"),
                         "durable")
        self.assertEqual(self._cls(reasonCode="empty"), "no_work")
        self.assertEqual(self._cls(totalRecords=0), "no_work")
        self.assertEqual(self._cls(succeededCount=1, pendingSize=2),
                         "partial")
        self.assertEqual(self._cls(reasonCode="store_failure",
                                   pendingSize=3), "store_failure")
        self.assertEqual(self._cls(responseLost=True), "unknown")
        self.assertEqual(self._cls(durable=True, succeededCount=3,
                                   totalRecords=3,
                                   checkpointWriteFailed=True),
                         "checkpoint_failure")
        self.assertEqual(self._cls(reasonCode="source_rejected"),
                         "policy_excluded")
        self.assertEqual(self._cls(reasonCode="backoff"),
                         "policy_excluded")
        self.assertEqual(self._cls(cancelled=True), "cancelled")


class ViolationCounterexampleTest(unittest.TestCase):
    """Each injected bug MUST be caught — a silent judge is a broken judge."""

    def test_v1_checkpoint_advanced_without_receipt(self):
        fx = _fixture("f02_partial_save")
        fx["outcome"]["checkpoint"]["after"] = 55  # covers 5, only 2 stored
        v = rf.check_violations(fx)
        self.assertTrue(any(x["violationId"] == "V1" for x in v), v)

    def test_v2_timeout_checkpoint_advanced(self):
        fx = _fixture("f07_unknown_commit")
        fx["outcome"]["checkpoint"]["after"] = 97  # timeout must not advance
        v = rf.check_violations(fx)
        self.assertTrue(any(x["violationId"] == "V2" for x in v), v)

    def test_v3_failure_backoff_reset(self):
        fx = _fixture("f03_other_batch_only_succeeded")
        fx["outcome"]["backoff"] = {"before": 3, "after": 0}
        v = rf.check_violations(fx)
        self.assertTrue(any(x["violationId"] == "V3" for x in v), v)

    def test_v4_nowork_counted_as_failure(self):
        fx = _fixture("f01_auto_flush_already_stored")
        fx["outcome"]["failedCountRecorded"] = 1
        v = rf.check_violations(fx)
        self.assertTrue(any(x["violationId"] == "V4" for x in v), v)

    def test_clean_fixtures_have_no_violations(self):
        for spec in rf.SCENARIOS:
            self.assertEqual(rf.check_violations(_fixture(spec["fixtureId"])),
                             [], spec["fixtureId"])

    def test_class_mismatch_fails_verdict(self):
        fx = _fixture("f02_partial_save")
        fx["expectedClass"] = "durable"  # wrong expectation
        r = rf.judge(fx)
        self.assertEqual(r["verdict"], "FAIL")
        self.assertFalse(r["classMatch"])


class GenerateTest(unittest.TestCase):
    def test_generate_is_deterministic_and_complete(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            r1 = rf.generate(root)
            snap = {p.name: p.read_bytes() for p in (root / rf.FIXTURE_DIR).glob("*.json")}
            rf.generate(root)
            snap2 = {p.name: p.read_bytes() for p in (root / rf.FIXTURE_DIR).glob("*.json")}
            self.assertEqual(snap, snap2)  # byte-identical re-run
            self.assertEqual(r1["fixtures"], 7)
            manifest = json.loads(
                (root / rf.FIXTURE_DIR / "manifest.json").read_text("utf-8"))
            self.assertEqual(len(manifest["fixtures"]), 7)
            md = (root / rf.REPORT_MD).read_text("utf-8")
            for s in rf.SCENARIOS:
                self.assertIn(s["junitName"], md)
            # next-round candidates table present, report-only
            for n in rf.RESULT_DISCARD_CANDIDATES:
                self.assertIn(n, md)

    def test_judge_all_exit_codes(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            rf.generate(root)
            self.assertEqual(rf.judge_all(root), 0)


if __name__ == "__main__":
    unittest.main(verbosity=1)
