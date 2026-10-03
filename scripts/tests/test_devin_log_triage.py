"""Tests for scripts/devin_log_triage.py. Stdlib only. No live secrets."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import devin_log_triage as triage  # noqa: E402


class MaskAndNormalizeTests(unittest.TestCase):
    def test_mask_hides_value(self):
        word = "tok" + "en"
        line = "%s=abcdEFGH1234 wxyz" % word
        masked = triage.mask_secrets(line)
        self.assertIn("=<masked>", masked)
        self.assertNotIn("abcdEFGH1234", masked)
        self.assertIn("wxyz", masked)

    def test_normalize_collapses_time_hex_and_numbers(self):
        a = triage.normalize_signature(
            "2026-10-02T10:01:02Z boom code 17 id abcd1234ef failed"
        )
        b = triage.normalize_signature(
            "2026-10-02T11:09:44Z boom code 88 id 00ffee11aa failed"
        )
        self.assertEqual(a, b)
        self.assertIn("<TS>", a)
        self.assertIn("<N>", a)
        self.assertIn("<HEX>", a)
        self.assertNotIn("abcd1234ef", a)

    def test_since_drops_older_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = Path(tmp) / "session"
            session.mkdir()
            log = session / "window.log"
            log.write_text(
                "2026-10-02T10:00:00 error old boom\n"
                "2026-10-02T13:00:00 error new boom\n",
                encoding="utf-8",
            )
            since = datetime.fromisoformat("2026-10-02T12:00:00").astimezone()
            report = triage.triage_session(session, since, 10)
            row = report["files"]["window.log"]
            self.assertEqual(row["matched"], 1)
            self.assertEqual(row["skippedBySince"], 1)
            self.assertIn("new boom", row["top"][0]["signature"])


class EmptyFolderTests(unittest.TestCase):
    def test_empty_logs_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            logs = Path(tmp) / "logs"
            logs.mkdir()
            out = Path(tmp) / "out.json"
            md = Path(tmp) / "out.md"
            code = triage.main([
                "--logs-root", str(logs),
                "--json", str(out),
                "--md", str(md),
            ])
            self.assertEqual(code, 0)
            report = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(report["fileCount"], 0)
            self.assertIn("Devin log triage", md.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
