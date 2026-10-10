"""Contract tests for scripts/live_test_window.py (stdlib only)."""
import json
import os
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import live_test_window as ltw

KST = ltw.KST


class LiveTestWindowTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.marker = ltw.marker_path(self.root)
        self.addCleanup(self.tmp.cleanup)

    def _start(self, minutes=90, owner="devin"):
        rc = ltw.main(["--root", str(self.root), "start", "--minutes", str(minutes), "--owner", owner])
        self.assertEqual(rc, 0)

    def _verdict(self):
        return ltw._evaluate(self.marker)

    def test_start_writes_marker_with_future_until_kst(self):
        self._start(90)
        data = json.loads(self.marker.read_text(encoding="utf-8"))
        self.assertEqual(data["schemaVersion"], ltw.SCHEMA)
        self.assertEqual(data["owner"], "devin")
        self.assertEqual(data["devices"], ["fold6", "glasses"])
        until = datetime.fromisoformat(data["untilKst"])
        started = datetime.fromisoformat(data["startedAtKst"])
        self.assertEqual(until.tzinfo.utcoffset(None), timedelta(hours=9))
        self.assertAlmostEqual((until - started).total_seconds(), 90 * 60, delta=2)
        verdict = self._verdict()
        self.assertTrue(verdict["active"])
        self.assertEqual(verdict["status"], "active")
        self.assertGreaterEqual(verdict["remainingMinutes"], 89)

    def test_status_absent_without_marker(self):
        verdict = self._verdict()
        self.assertFalse(verdict["active"])
        self.assertEqual(verdict["status"], "absent")

    def test_expired_marker_is_inactive(self):
        self._start(90)
        data = json.loads(self.marker.read_text(encoding="utf-8"))
        data["untilKst"] = (datetime.now(KST) - timedelta(minutes=1)).isoformat(timespec="seconds")
        self.marker.write_text(json.dumps(data), encoding="utf-8")
        verdict = self._verdict()
        self.assertFalse(verdict["active"])
        self.assertEqual(verdict["status"], "expired")

    def test_end_removes_marker(self):
        self._start(90)
        rc = ltw.main(["--root", str(self.root), "end"])
        self.assertEqual(rc, 0)
        self.assertFalse(self.marker.exists())
        self.assertEqual(self._verdict()["status"], "absent")
        # end on absent marker is a no-op, still exit 0
        self.assertEqual(ltw.main(["--root", str(self.root), "end"]), 0)

    def test_corrupt_marker_stays_active_while_fresh(self):
        self.marker.parent.mkdir(parents=True, exist_ok=True)
        self.marker.write_text("{not json", encoding="utf-8")
        verdict = self._verdict()
        self.assertTrue(verdict["active"])
        self.assertEqual(verdict["status"], "corrupt")

    def test_corrupt_marker_older_than_protect_window_is_inactive(self):
        self.marker.parent.mkdir(parents=True, exist_ok=True)
        self.marker.write_text("{not json", encoding="utf-8")
        old = datetime.now().timestamp() - (ltw.CORRUPT_PROTECT_HOURS + 1) * 3600
        os.utime(self.marker, (old, old))
        verdict = self._verdict()
        self.assertFalse(verdict["active"])
        self.assertEqual(verdict["status"], "corrupt")

    def test_marker_missing_until_is_corrupt_protected(self):
        self.marker.parent.mkdir(parents=True, exist_ok=True)
        self.marker.write_text(json.dumps({"schemaVersion": ltw.SCHEMA}), encoding="utf-8")
        self.assertEqual(self._verdict()["status"], "corrupt")

    def test_naive_until_parsed_as_kst(self):
        self.marker.parent.mkdir(parents=True, exist_ok=True)
        future = (datetime.now(KST) + timedelta(minutes=30)).replace(tzinfo=None)
        self.marker.write_text(json.dumps({"untilKst": future.isoformat(timespec="seconds")}), encoding="utf-8")
        verdict = self._verdict()
        self.assertTrue(verdict["active"])
        self.assertEqual(verdict["untilKst"].endswith("+09:00"), True)

    def test_invalid_minutes_refused(self):
        for bad in ("0", "-5", "1441"):
            self.assertEqual(ltw.main(["--root", str(self.root), "start", "--minutes", bad]), 2)
        self.assertFalse(self.marker.exists())

    def test_owner_defaults_and_override(self):
        rc = ltw.main(["--root", str(self.root), "start", "--minutes", "90", "--owner", "codex", "--devices", "fold6", "--note", "walk test"])
        self.assertEqual(rc, 0)
        data = json.loads(self.marker.read_text(encoding="utf-8"))
        self.assertEqual(data["owner"], "codex")
        self.assertEqual(data["devices"], ["fold6"])
        self.assertEqual(data["note"], "walk test")


if __name__ == "__main__":
    unittest.main()
