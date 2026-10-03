#!/usr/bin/env python3
"""Temp-ledger fixtures for devin_report_gate. Stdlib only."""
from __future__ import annotations

import json
import os
import sys
import tempfile
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import devin_report_gate as gate  # noqa: E402


class ReportGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.handoff = self.root / "data" / "agent-handoff"
        self.old = time.time() - 5 * 3600
        self.now = datetime(2026, 10, 3, 6, 0, tzinfo=timezone.utc)

    def tearDown(self):
        self.tmp.cleanup()

    def _old_tree(self, path: Path) -> None:
        for item in [path, *path.rglob("*")]:
            if item.exists():
                os.utime(item, (self.old, self.old))

    def _write(self, rel: str, text: str | None) -> Path:
        folder = self.handoff / rel
        folder.mkdir(parents=True, exist_ok=True)
        if text is not None:
            target = folder / "report.md"
            target.write_text(text, encoding="utf-8")
        self._old_tree(folder)
        return folder

    def test_table_and_skips(self):
        self._write("devin-good", "외부 API: 0회\n\n## Acceptance\n\n| ID | 결과 |\n|---|---|\n| A1 | PASS |\n")
        self._write("devin-missing", None)
        self._write("devin-bad", "외부 API: 0회\n완료\n\n## Acceptance\n\n| A1 | NOT_RUN |\n")
        live = self._write("devin-live", "외부 API: 0회\n")
        os.utime(live, None)
        os.utime(live / "report.md", None)
        leased = self._write("devin-leased", "본문만 있음\n")
        lock = self.root / "__patch_drop__" / "source-edit-locks" / "devin-leased.lock"
        lock.mkdir(parents=True)
        (lock / "lease.json").write_text(json.dumps({
            "topic": "devin-leased",
            "expiresAtUtc": "2099-01-01T00:00:00+00:00",
            "targetPaths": [],
        }), encoding="utf-8")
        journal_live = self.handoff / "codex-autonomy" / "devin-journal-live"
        journal_live.mkdir(parents=True)
        (journal_live / "journal.json").write_text(
            json.dumps({"status": "in_progress"}), encoding="utf-8")
        self._old_tree(journal_live)

        result = gate.scan(self.root, hours=2, now=self.now)
        by_name = {row["ledger"]: row for row in result["rows"]}
        self.assertTrue(by_name["devin-good"]["ok"])
        self.assertFalse(by_name["devin-good"]["skip"])
        self.assertIn("Acceptance", by_name["devin-missing"]["nudge"])
        self.assertTrue(by_name["devin-bad"]["contradiction"])
        self.assertTrue(by_name["devin-live"]["skip"])
        self.assertIn("mtime", by_name["devin-live"]["skipReason"])
        self.assertTrue(by_name["devin-leased"]["skip"])
        self.assertIn("active-journal-or-lease", by_name["devin-leased"]["skipReason"])
        self.assertTrue(by_name["devin-journal-live"]["skip"])
        self.assertIn("active-journal-or-lease", by_name["devin-journal-live"]["skipReason"])
        self.assertGreaterEqual(result["skipped"], 3)

    def test_session_guard_is_not_required(self):
        text = Path(__file__).resolve().parent.joinpath("devin_report_gate.py").read_text(encoding="utf-8")
        self.assertIn("devin_session_guard.py", text)
        self.assertNotIn("subprocess", text)


if __name__ == "__main__":
    unittest.main()
