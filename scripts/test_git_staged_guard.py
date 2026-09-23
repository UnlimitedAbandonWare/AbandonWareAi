"""Synthetic repositories only; no production index mutations."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

GUARD = Path(__file__).with_name("git_secret_guard.ps1").resolve()


class StagedGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="awx-staged-fixture-")
        self.root = Path(self.temp.name)
        self.env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
        self.git("init", "--quiet")

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *args):
        result = subprocess.run(["git", *args], cwd=self.root, env=self.env,
                                capture_output=True, timeout=15)
        self.assertEqual(0, result.returncode, "synthetic Git operation failed")
        return result.stdout

    def stage(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        self.git("add", "--", name)
        return path

    def check(self):
        return subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(GUARD),
                               "-Mode", "pre-commit"], cwd=self.root, env=self.env,
                              capture_output=True, timeout=30)

    def test_staged_secret_is_blocked_after_worktree_is_cleaned(self):
        fake = "sk-" + "A" * 30
        path = self.stage("README.md", fake)
        path.write_text("safe worktree", encoding="utf-8")
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn(fake.encode(), result.stdout + result.stderr)

    def test_safe_unicode_path_scans_the_index_even_if_worktree_removed(self):
        path = self.stage("fixture notes/한글.md", "synthetic safe source")
        path.unlink()
        self.assertEqual(0, self.check().returncode)

    def test_oversized_staged_blob_is_not_silently_skipped(self):
        self.stage("README.md", "x" * (2 * 1024 * 1024 + 1))
        self.assertNotEqual(0, self.check().returncode)

    def test_generated_staged_path_is_blocked(self):
        self.stage("build/generated.txt", "fixture")
        self.assertNotEqual(0, self.check().returncode)


if __name__ == "__main__":
    unittest.main()
