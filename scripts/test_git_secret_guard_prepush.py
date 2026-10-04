"""Both pre-push gates use committed blobs and exact reviewed allowances."""
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.test_git_publish_review import git, head, init_repo, run_review


@unittest.skipUnless(shutil.which("powershell"), "PowerShell unavailable")
class PrePushAllowanceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="prepush-allowance-"))
        self.repo = init_repo(self.tmp / "repo")
        self.base = head(self.repo)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def commit_file(self, name, text):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        self.assertEqual(git(self.repo, "add", name).returncode, 0)
        self.assertEqual(git(self.repo, "commit", "-m", "fixture").returncode, 0)

    def secret_fixture(self, path="scripts/test_reviewed.py", reason="reviewed synthetic fixture"):
        self.value = "sk-" + "F" * 24
        self.commit_file(path, 'KEY = "' + self.value + '"\n')
        self.path = path
        self.oid = git(self.repo, "rev-parse", "HEAD:" + path).stdout.strip()
        self.allow_file = self.repo / "configs/git-guard-allow.json"
        self.allow_file.parent.mkdir(parents=True, exist_ok=True)
        self.write_allow(reason=reason)

    def write_allow(self, reason="reviewed synthetic fixture", oid=None):
        self.allow_file.write_text(json.dumps({"schema": "awx.git-guard-allow.v1", "entries": [
            {"path": self.path, "rule": "provider-key", "oid": oid or self.oid,
             "reason": reason}]}), encoding="utf-8")

    def gates(self, expected):
        tool = Path(__file__).resolve().with_name("git_secret_guard.ps1")
        ps = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                             "-File", str(tool), "-Mode", "pre-push"], cwd=self.repo,
                            capture_output=True, text=True, timeout=60)
        review = run_review(self.repo, "--base", self.base)
        if expected == 1:
            self.assertIn('[BLOCK]', ps.stdout, 'must block a finding, not a scanner error')
            self.assertEqual(review['data'].get('verdict'), 'BLOCKED')
        for name, code, output in (("powershell", ps.returncode, ps.stdout + ps.stderr),
                                   ("publish", review["code"], review["stdout"] + review["stderr"])):
            with self.subTest(gate=name):
                self.assertNotIn(self.value, output, "fixture value leaked")
                self.assertEqual(code, expected, name + " gate exit mismatch")
        return ps, review

    def test_a_exact_reviewed_test_blob_allowed(self):
        self.secret_fixture()
        self.gates(0)

    def test_b_changed_committed_oid_blocked(self):
        self.secret_fixture()
        self.commit_file(self.path, 'KEY = "' + self.value + 'x"\n')
        self.gates(1)

    def test_c_non_test_path_blocked(self):
        self.secret_fixture("main/fixture.py")
        self.gates(1)

    def test_d_empty_reason_blocked(self):
        self.secret_fixture(reason="  ")
        self.gates(1)

    def test_e_uncommitted_worktree_secret_ignored(self):
        self.value = "sk-" + "F" * 24
        self.commit_file("scripts/test_dirty.py", "clean = True\n")
        (self.repo / "scripts/test_dirty.py").write_text(self.value + "\n", encoding="utf-8")
        self.gates(0)

    def test_e_worktree_clean_cannot_hide_committed_secret(self):
        self.secret_fixture()
        self.allow_file.unlink()
        (self.repo / self.path).write_text("clean = True\n", encoding="utf-8")
        self.gates(1)

    def test_f_missing_or_broken_allowlist_blocked(self):
        self.secret_fixture()
        for payload in (None, "{", "[]", '{"entries": null}'):
            with self.subTest(payload=payload):
                if payload is None:
                    self.allow_file.unlink()
                else:
                    self.allow_file.write_text(payload, encoding="utf-8")
                self.gates(1)

    def test_abbreviated_allow_oid_blocked(self):
        self.secret_fixture()
        self.write_allow(oid=self.oid[:12])
        self.gates(1)

    def test_reviewed_blob_in_history_allowed_after_removal(self):
        self.secret_fixture()
        git(self.repo, "rm", self.path)
        self.assertEqual(git(self.repo, "commit", "-m", "remove fixture").returncode, 0)
        self.gates(0)

    def test_same_blob_at_non_test_path_still_blocked(self):
        self.secret_fixture("scripts/test_reviewed.py")
        self.commit_file("main/z_copy.py", 'KEY = "' + self.value + '"\n')
        self.gates(1)

    def test_case_distinct_git_paths_both_scanned(self):
        self.secret_fixture('scripts/test_case.py')
        self.assertEqual(git(self.repo, 'update-index', '--add', '--cacheinfo',
                             '100644,' + self.oid + ',scripts/Test_case.py').returncode, 0)
        self.assertEqual(git(self.repo, 'commit', '-m', 'case-distinct index path').returncode, 0)
        self.gates(1)

    def test_explicit_push_commit_controls_both_gates(self):
        self.secret_fixture()
        self.allow_file.unlink()
        tool = Path(__file__).resolve().with_name('git_secret_guard.ps1')
        proc = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                               '-File', str(tool), '-Mode', 'pre-push', '-Commit', self.base],
                              cwd=self.repo, capture_output=True, text=True, timeout=60)
        self.assertEqual(proc.returncode, 0)
        review = run_review(self.repo, '--candidate', self.base, '--base', self.base)
        self.assertEqual(review['code'], 0)
        self.assertNotIn(self.value, proc.stdout + proc.stderr + review['stdout'])

    def test_invalid_commit_fails_closed(self):
        tool = Path(__file__).resolve().with_name('git_secret_guard.ps1')
        proc = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                               '-File', str(tool), '-Mode', 'pre-push', '-Commit', 'absent-ref'],
                              cwd=self.repo, capture_output=True, text=True, timeout=60)
        self.assertNotEqual(proc.returncode, 0)

    def test_manual_mode_still_scans_worktree_without_allowance(self):
        self.secret_fixture()
        tool = Path(__file__).resolve().with_name('git_secret_guard.ps1')
        proc = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                               '-File', str(tool), '-Mode', 'manual', '-Path', self.path],
                              cwd=self.repo, capture_output=True, text=True, timeout=60)
        self.assertEqual(proc.returncode, 1)
        self.assertNotIn(self.value, proc.stdout + proc.stderr)


if __name__ == "__main__":
    unittest.main()
