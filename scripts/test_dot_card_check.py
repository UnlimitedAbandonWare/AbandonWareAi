#!/usr/bin/env python3
"""Tests for scripts/dot_card_check.py — temp fixtures only, real Downloads untouched."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "dot_card_check.py"


def run_tool(*args):
    return subprocess.run(
        [sys.executable, "-B", str(TOOL), *args],
        capture_output=True, text=True, cwd=ROOT, timeout=30,
    )


class DotCardCheckTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dcc-test-"))
        self.dl = self.tmp / "downloads"
        self.dl.mkdir()

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def write_identity(self, **kw):
        p = self.tmp / "identity.json"
        p.write_text(json.dumps(kw), encoding="utf-8")
        return p

    def test_t1_identity_ok(self):
        p = self.write_identity(
            library_file_id="libfile_abcdef0123456789",
            file_id="file_00000000abcd",
            file_name="demo1_x_20261004.md",
            path="/demo1_x_20261004.md",
            local_path=str(self.dl / "demo1_x_20261004.md"),
        )
        r = run_tool("--identity", str(p))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("CARD_OK name=demo1_x_20261004.md libfile=libfile_abcd", r.stdout)

    def test_t2_identity_missing_keys(self):
        p = self.write_identity(file_id="file_x", path="/x.md")
        r = run_tool("--identity", str(p))
        self.assertEqual(r.returncode, 2)
        self.assertIn("CARD_MISSING missing=library_file_id", r.stdout)

    def test_t3_identity_bad_json(self):
        p = self.tmp / "bad.json"
        p.write_text("{not json", encoding="utf-8")
        r = run_tool("--identity", str(p))
        self.assertEqual(r.returncode, 2)
        self.assertIn("CARD_MISSING error=", r.stdout)

    def test_t4_audit_classifies_hook_and_user_download(self):
        (self.dl / "PASTE_DEVIN_x_20261004.txt").write_text("hook copy")
        (self.dl / "myreport_directive_2026.md").write_text("hook md")
        (self.dl / "demo1_x_20261004 (1).md").write_text("user clicked card")
        (self.dl / "random_notes.md").write_text("unrelated")
        # NTFS st_ctime = creation time and cannot be backdated via os.utime,
        # so window-exclusion of an "old" file is not fixture-testable here.
        r = run_tool("--downloads-audit", "--since-minutes", "60",
                     "--downloads", str(self.dl))
        self.assertEqual(r.returncode, 4, r.stdout + r.stderr)
        self.assertIn("HOOK_SUSPECT PASTE_DEVIN_x_20261004.txt", r.stdout)
        self.assertIn("HOOK_SUSPECT myreport_directive_2026.md", r.stdout)
        self.assertIn("USER_DOWNLOAD demo1_x_20261004 (1).md", r.stdout)
        self.assertIn("OTHER random_notes.md", r.stdout)
        self.assertIn("hook_suspect=2", r.stdout)
        self.assertIn("user_download=1", r.stdout)

    def test_t5_audit_clean_window_exit0(self):
        (self.dl / "notes.md").write_text("x")
        r = run_tool("--downloads-audit", "--since-minutes", "60",
                     "--downloads", str(self.dl))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("hook_suspect=0", r.stdout)

    def test_t6_audit_missing_dir_exit3(self):
        r = run_tool("--downloads-audit", "--since-minutes", "60",
                     "--downloads", str(self.tmp / "no-such"))
        self.assertEqual(r.returncode, 3)
        self.assertIn("AUDIT_ERROR", r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
