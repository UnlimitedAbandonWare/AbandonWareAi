import json
import tempfile
import unittest
from datetime import date
from pathlib import Path

from codex_skill_performance_monitor import (
    TIER_THRESHOLDS, analyze_session_file, build_report, classify_tier,
    format_brief, session_summary, token_lane)


def _fixture_path(tmpdir, name="rollout-2026-10-04T12-00-00-000.jsonl"):
    return Path(tmpdir) / name


def _write_lines(path, lines):
    path.write_bytes(b"\n".join(lines) + b"\n")


class SkillPerformanceMonitorTest(unittest.TestCase):

    def test_classify_tier_from_skill_hits(self):
        self.assertEqual(
            "tier2_strategic",
            classify_tier({"demo1-triad-deliberation": 2}))
        self.assertEqual(
            "tier2_strategic",
            classify_tier({"demo1-triangulating-counter-evidence": 1}))
        self.assertEqual(
            "tier1_tactical",
            classify_tier({"demo1-evidence-debugging": 3}))
        self.assertEqual("tier0_light", classify_tier({}))
        self.assertEqual("tier0_light", classify_tier(None))

    def test_token_lane_thresholds(self):
        self.assertEqual("tier0_light", token_lane(0))
        self.assertEqual("tier0_light",
                         token_lane(TIER_THRESHOLDS["tier0_max_tokens"]))
        self.assertEqual(
            "tier1_tactical",
            token_lane(TIER_THRESHOLDS["tier0_max_tokens"] + 1))
        self.assertEqual(
            "tier2_strategic",
            token_lane(TIER_THRESHOLDS["tier1_max_tokens"] + 1))
        self.assertEqual(
            "over_tier2",
            token_lane(TIER_THRESHOLDS["tier2_max_tokens"] + 1))

    def test_token_measurement_and_truncation(self):
        with tempfile.TemporaryDirectory() as td:
            p = _fixture_path(td)
            usage = {"type": "token_usage_record",
                     "payload": {"usage": {"total_tokens": 12345}}}
            usage2 = {"type": "token_usage_record",
                      "payload": {"usage": {"input_tokens": 100,
                                            "output_tokens": 55}}}
            _write_lines(p, [
                json.dumps(usage).encode(),
                b"Warning: truncated output (original token count: 8105)",
                json.dumps(usage2).encode(),
                b"Warning: truncated output (original token count: 42)",
            ])
            m = analyze_session_file(p)
            self.assertEqual(12500, m["tokens"])
            self.assertTrue(m["truncated"])
            self.assertEqual(2, m["trunc_count"])
            self.assertEqual(8147, m["trunc_tokens"])

    def test_friction_and_skill_extraction(self):
        with tempfile.TemporaryDirectory() as td:
            p = _fixture_path(td)
            _write_lines(p, [
                b"apply_patch failed: Failed to find expected lines in file",
                b"error: invalid hunk at line 3",
                b"source-lease-drift detected on target",
                b"invoke demo1-triad-deliberation for deliberation",
                b'refused-as-unavailable',
                json.dumps({"type": "compacted", "payload": {}}).encode(),
            ])
            m = analyze_session_file(p)
            self.assertEqual(1, m["friction"]["applypatch_expected_lines"])
            self.assertEqual(1, m["friction"]["applypatch_invalid_hunk"])
            self.assertEqual(1, m["friction"]["source_lease_drift"])
            self.assertEqual(1, m["refused"])
            self.assertEqual(1, m["compactions"])
            self.assertEqual(1, m["high_perf"]["demo1-triad-deliberation"])
            row = session_summary(p, m)
            self.assertEqual("tier2_strategic", row["assigned_tier"])
            self.assertEqual(3, row["friction_total"])

    def test_build_report_and_brief(self):
        with tempfile.TemporaryDirectory() as td:
            p = _fixture_path(td)
            _write_lines(p, [
                json.dumps({"type": "token_usage_record",
                            "payload": {"usage": {"total_tokens": 5000}}}
                           ).encode(),
                b"call demo1-evidence-debugging once",
            ])
            report = build_report(td, days=7,
                                  today=date(2026, 10, 4))
            self.assertEqual(1, report["files_scanned"])
            self.assertEqual(1, report["files_matched"])
            t = report["totals"]
            self.assertEqual(5000, t["tokens"])
            self.assertEqual(1, t["tier_counts"]["tier1_tactical"])
            self.assertEqual(
                1, t["by_skill"]["demo1-evidence-debugging"])
            brief = format_brief(report)
            self.assertIn("sessions=1", brief)
            self.assertIn("T1=1", brief)
            self.assertIn("highPerf=1", brief)

    def test_window_filters_old_files(self):
        with tempfile.TemporaryDirectory() as td:
            p = _fixture_path(
                td, name="rollout-2020-01-01T00-00-00-000.jsonl")
            _write_lines(p, [b"demo1-triad-deliberation"])
            report = build_report(td, days=7,
                                  today=date(2026, 10, 4))
            self.assertEqual(1, report["files_scanned"])
            self.assertEqual(0, report["files_matched"])
            self.assertEqual([], report["sessions"])


if __name__ == "__main__":
    unittest.main()
