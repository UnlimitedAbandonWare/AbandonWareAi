"""Synthetic repositories only; no production index mutations."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import git_staged_guard as scanner

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

    def stage_bytes(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
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

    def test_exact_scope_and_index_drift_fail_closed(self):
        self.stage("owned.txt", "safe")
        with self.assertRaisesRegex(scanner.GuardFailure, "staged-scope-mismatch"):
            scanner.scan(self.root, ["another.txt"])
        original = scanner.snapshot(self.root)
        with mock.patch.object(scanner, "snapshot", side_effect=[original, ("drift", original[1])]):
            with self.assertRaisesRegex(scanner.GuardFailure, "index-changed-during-scan"):
                scanner.scan(self.root, ["owned.txt"])

    def test_staged_symlink_mode_and_aws_fixture_are_blocked(self):
        self.stage("note.txt", "safe")
        oid = self.git("rev-parse", ":note.txt").decode().strip()
        self.git("update-index", "--cacheinfo", "120000," + oid + ",note.txt")
        self.assertNotEqual(0, self.check().returncode)
        self.stage("note.txt", "AK" + "IA" + "Z" * 16)
        self.assertNotEqual(0, self.check().returncode)

    def test_tracked_readme_docs_skip_directory_path_rules(self):
        self.assertIsNone(scanner.path_rule("__patch_drop__/README.md"))
        self.assertIsNone(scanner.path_rule("main/resources/models/MODEL_README.md"))

    def test_non_readme_files_in_the_same_dirs_stay_blocked(self):
        self.assertEqual("private-or-generated-path",
                         scanner.path_rule("__patch_drop__/x.patch"))
        self.assertEqual("private-or-generated-path",
                         scanner.path_rule("main/resources/models/x.bin"))

    def test_credential_dirs_still_block_readme_docs(self):
        self.assertEqual("private-or-generated-path", scanner.path_rule(".secrets/README.md"))
        self.assertEqual("runtime-data-path", scanner.path_rule("config/secrets/README.md"))

    def test_readme_in_blocked_dirs_scans_content_and_passes(self):
        self.stage("__patch_drop__/README.md", "plain documentation")
        self.stage("main/resources/models/MODEL_README.md", "plain documentation")
        self.assertEqual(0, self.check().returncode)

    def test_readme_with_fake_key_keeps_content_finding(self):
        self.stage("__patch_drop__/README.md", "docs " + "sk-" + "B" * 30)
        self.assertNotEqual(0, self.check().returncode)

    def test_empty_env_assignments_do_not_read_the_next_line(self):
        self.stage_bytes(
            ".env.example",
            ("DEEPGRAM_API_" + "KEY=\r\n"
             "DEEPGRAM_API_" + "KEY_SECONDARY=\r\n"
             "NAVER_APIHUB_CLIENT_" + "ID=\r\n"
             "NAVER_APIHUB_CLIENT_" + "SECRET=\r\n").encode())
        result = scanner.scan(self.root)
        self.assertTrue(result["ok"], result["findings"])

    def test_placeholder_env_assignment_passes(self):
        self.stage(".env.example",
                   "API_" + "KEY=<your-key-here>\r\nCLIENT_" + "SECRET=changeme\r\n")
        result = scanner.scan(self.root)
        self.assertTrue(result["ok"], result["findings"])

    def test_real_value_sensitive_assignment_stays_blocked(self):
        self.stage(".env.example", "API_" + "KEY=" + "A" * 30 + "\r\n")
        result = scanner.scan(self.root)
        self.assertFalse(result["ok"])
        rules = {f["rule"] for f in result["findings"]}
        self.assertIn("sensitive-assignment", rules)

    def test_small_report_screenshot_is_not_binary_held(self):
        png = b"\x89PNG\r\n\x1a\n" + b"\0" * 256
        self.stage_bytes("docs/reports/agent-reviews/r1/evidence.png", png)
        result = scanner.scan(self.root)
        self.assertTrue(result["ok"], result["findings"])

    def test_report_machine_blob_and_large_image_stay_held(self):
        self.stage_bytes("docs/reports/agent-reviews/r1/state.bin",
                         b"\0" * 128)
        big = b"\x89PNG\r\n\x1a\n" + b"\0" * (1024 * 1024 + 16)
        self.stage_bytes("docs/reports/agent-reviews/r1/big.png", big)
        result = scanner.scan(self.root)
        rules = {f["rule"] for f in result["findings"]}
        self.assertEqual({"binary-scan-unavailable"}, rules)

    def test_secret_bytes_inside_report_image_still_blocked(self):
        blob = b"\x89PNG\r\n\x1a\n" + b"\0" * 8 + ("AK" + "IA" + "Z" * 16).encode()
        self.stage_bytes("docs/reports/agent-reviews/r1/evidence.png", blob)
        result = scanner.scan(self.root)
        self.assertFalse(result["ok"])
        rules = {f["rule"] for f in result["findings"]}
        self.assertIn("aws-access-key", rules)


if __name__ == "__main__":
    unittest.main()
