"""Fixture tests for foreign_hunk_preserve_check.py."""
from __future__ import annotations

import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("foreign_hunk_preserve_check.py")
SPEC = importlib.util.spec_from_file_location("foreign_hunk_preserve_check", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)

CANARY = "CANARY-FOREIGN-LINE-7f3a"
GIT = MOD.git_bin()


def git(repo: Path, *args: str) -> None:
    env = os.environ.copy()
    env["GIT_AUTHOR_NAME"] = "fixture"
    env["GIT_AUTHOR_EMAIL"] = "fixture@example.com"
    env["GIT_COMMITTER_NAME"] = "fixture"
    env["GIT_COMMITTER_EMAIL"] = "fixture@example.com"
    subprocess.run([GIT, "-C", str(repo), *args], check=True, env=env,
                   capture_output=True, text=True)


class ForeignHunkTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        git(self.repo, "init", "-q")
        git(self.repo, "config", "user.email", "fixture@example.com")
        git(self.repo, "config", "user.name", "fixture")
        target = self.repo / "sample.txt"
        target.write_text("alpha\nbeta\ngamma\n", encoding="utf-8")
        git(self.repo, "add", "sample.txt")
        git(self.repo, "commit", "-q", "-m", "base")
        target.write_text(f"alpha\n{CANARY}\ngamma\n", encoding="utf-8")

    def test_later_addition_keeps_foreign_line(self) -> None:
        snap = self.repo / "snap.json"
        self.assertEqual(MOD.main(
            ["--root", str(self.repo), "snapshot", "--out", str(snap), "--path", "sample.txt"]), 0)
        target = self.repo / "sample.txt"
        target.write_text(f"alpha\n{CANARY}\nCODEX-ADDED\ngamma\n", encoding="utf-8")
        code = MOD.main(["--root", str(self.repo), "check", "--snapshot", str(snap)])
        self.assertEqual(code, 0)

    def test_removed_foreign_line_exits_4_without_printing_text(self) -> None:
        snap = self.repo / "snap.json"
        MOD.main(["--root", str(self.repo), "snapshot", "--out", str(snap), "--path", "sample.txt"])
        (self.repo / "sample.txt").write_text("alpha\ngamma\n", encoding="utf-8")
        from io import StringIO
        stdout, stderr = StringIO(), StringIO()
        old_out, old_err = sys.stdout, sys.stderr
        sys.stdout, sys.stderr = stdout, stderr
        try:
            code = MOD.main(["--root", str(self.repo), "check", "--snapshot", str(snap)])
        finally:
            sys.stdout, sys.stderr = old_out, old_err
        self.assertEqual(code, 4)
        printed = stdout.getvalue() + stderr.getvalue()
        self.assertNotIn(CANARY, printed)
        self.assertIn("LOST path=sample.txt", printed)
        self.assertNotIn(CANARY, snap.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
