"""Fixture-repo tests for scripts/git_publish_review.py — disposable repos.

Validates: candidate-tree scan, outgoing-history scan, effective push-target
matching, pre-push stdin ref evaluation, ancestry checks. Secret fixture
values are synthentic strings shaped like keys; assertions check that they
are never echoed to stdout.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import git_publish_review as gpr  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "git_publish_review.py"
OLD_TOOL = ROOT / "var" / "gitopt-20261101" / "backup" / "git_publish_review.py"
GOOD_TARGET = "github.com/UnlimitedAbandonWare/AbandonWareAi"
GOOD_URL = "https://github.com/UnlimitedAbandonWare/AbandonWareAi.git"
FAKE_AWS_KEY = "AKIA" + "IOSFODNN7EXAMPLE"  # doc-shaped fixture; split so the literal is not itself a matchable secret


def git(repo: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(repo), *args],
                          capture_output=True, text=True, timeout=60)


def init_repo(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "init", str(path)], check=True,
                   capture_output=True, timeout=60)
    git(path, "config", "user.email", "fixture@test")
    git(path, "config", "user.name", "fixture")
    git(path, "config", "commit.gpgsign", "false")
    (path / "ok.txt").write_text("clean\n", encoding="utf-8")
    git(path, "add", "ok.txt")
    assert git(path, "commit", "-m", "init").returncode == 0
    return path


def head(repo: Path) -> str:
    return git(repo, "rev-parse", "HEAD").stdout.strip()


def run_review(repo: Path, *extra: str, stdin: str = "",
               env_extra: dict | None = None, tool: Path = TOOL) -> dict:
    env = dict(os.environ)
    env.pop("AWX_PUBLISH_APPROVED", None)
    if env_extra:
        env.update(env_extra)
    env["PYTHONPATH"] = (str(ROOT / "scripts") + os.pathsep +
                         env.get("PYTHONPATH", ""))
    proc = subprocess.run(
        [sys.executable, "-B", str(tool), "--root", str(repo),
         "--json", *extra],
        input=stdin, capture_output=True, text=True, timeout=180, env=env)
    return {"code": proc.returncode,
            "data": json.loads(proc.stdout) if proc.stdout.strip() else {},
            "stdout": proc.stdout, "stderr": proc.stderr}


@unittest.skipUnless(shutil.which("git"), "git not on PATH")
class PublishReviewTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="git-publish-review-"))
        self.repo = init_repo(self.tmp / "repo")
        self.base = head(self.repo)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def commit_file(self, name: str, text: str, msg: str = "add"):
        (self.repo / name).parent.mkdir(parents=True, exist_ok=True)
        (self.repo / name).write_text(text, encoding="utf-8")
        git(self.repo, "add", name)
        assert git(self.repo, "commit", "-m", msg).returncode == 0

    def test_clean_candidate_and_target_is_clean(self):
        git(self.repo, "remote", "add", "origin", GOOD_URL)
        res = run_review(self.repo, "--base", self.base, "--remote", "origin",
                         "--allow-target", GOOD_TARGET)
        self.assertEqual(res["code"], 0, res["stderr"])
        self.assertEqual(res["data"]["verdict"], "CLEAN")
        self.assertEqual(res["data"]["historyScan"]["commitCount"], 0)

    def test_secret_in_candidate_tree_blocks_without_echo(self):
        self.commit_file("conf.txt", f"key={FAKE_AWS_KEY}\n")
        res = run_review(self.repo, "--base", self.base)
        self.assertEqual(res["code"], 1)
        self.assertEqual(res["data"]["verdict"], "BLOCKED")
        self.assertIn("tree-secret-or-path-finding", res["data"]["reasons"])
        self.assertNotIn(FAKE_AWS_KEY, res["stdout"] + res["stderr"])

    def test_path_rule_allowance_cannot_skip_private_path_gate(self):
        path = 'main/resources/models/fixture.py'
        self.commit_file(path, 'safe = True\n')
        allow = self.repo / 'configs/git-guard-allow.json'
        allow.parent.mkdir(parents=True, exist_ok=True)
        allow.write_text(json.dumps({'entries': [{
            'path': path, 'rule': 'private-or-generated-path',
            'oid': git(self.repo, 'rev-parse', 'HEAD:' + path).stdout.strip(),
            'reason': 'must not suppress the private path gate'}]}), encoding='utf-8')
        result = run_review(self.repo, '--base', self.base)
        self.assertEqual(result['code'], 1)
        self.assertTrue(result['data']['treeScan']['findings'])

    def test_secret_removed_in_tip_still_found_in_history(self):
        self.commit_file("gone.txt", f"{FAKE_AWS_KEY}\n")
        (self.repo / "gone.txt").unlink()
        git(self.repo, "add", "-A")
        assert git(self.repo, "commit", "-m", "remove").returncode == 0
        res = run_review(self.repo, "--base", self.base)
        self.assertEqual(res["code"], 1)
        self.assertIn("history-secret-or-truncated", res["data"]["reasons"])
        self.assertTrue(res["data"]["treeScan"]["ok"],
                        "tip tree itself is clean — history must be the finding")

    def test_secret_in_tracked_file_or_merge_still_found_after_removal(self):
        for merge in (False, True):
            with self.subTest(merge=merge):
                base = head(self.repo)
                if merge:
                    self.assertEqual(git(self.repo, "branch", "side").returncode, 0)
                    self.commit_file("first.txt", "first parent\n")
                    first = head(self.repo)
                    self.assertEqual(git(self.repo, "switch", "side").returncode, 0)
                    self.commit_file("second.txt", "other parent\n")
                    self.assertEqual(git(self.repo, "merge", "--no-ff", "--no-commit",
                                         first).returncode, 0)
                self.commit_file("ok.txt", f"{FAKE_AWS_KEY}\n", "introduce fixture")
                self.commit_file("ok.txt", "clean\n", "remove fixture")

                result = run_review(self.repo, "--base", base)
                self.assertEqual(result["code"], 1)
                self.assertTrue(result["data"]["treeScan"]["ok"])
                self.assertIn("history-secret-or-truncated", result["data"]["reasons"])
                self.assertEqual(len(result["data"]["historyScan"]["findings"]), 1)
                self.assertNotIn(FAKE_AWS_KEY, result["stdout"] + result["stderr"])

    def test_wrong_push_target_blocked(self):
        git(self.repo, "remote", "add", "origin", GOOD_URL)
        res = run_review(self.repo, "--base", self.base, "--remote", "origin",
                         "--allow-target", "github.com/SomeoneElse/OtherRepo")
        self.assertEqual(res["code"], 1)
        self.assertTrue(any(r.startswith("push-target-mismatch")
                            for r in res["data"]["reasons"]))

    def test_url_with_embedded_credentials_blocked(self):
        git(self.repo, "remote", "add", "origin",
            "https://user:secret@github.com/UnlimitedAbandonWare/AbandonWareAi.git")
        res = run_review(self.repo, "--base", self.base, "--remote", "origin",
                         "--allow-target", GOOD_TARGET)
        self.assertEqual(res["code"], 1)
        self.assertIn("url-has-credentials", res["data"]["reasons"])
        self.assertNotIn("secret", res["stdout"])

    def test_unknown_base_oid_marks_unknown_not_pass(self):
        res = run_review(self.repo, "--base", "1" * 40)
        self.assertEqual(res["code"], 3)
        self.assertEqual(res["data"]["verdict"], "UNKNOWN")
        self.assertIn("history-not-verifiable", res["data"]["reasons"])

    def test_hook_stdin_multi_ref_blocked(self):
        mine = head(self.repo)
        stdin = (f"refs/heads/a {mine} refs/heads/a {self.base}\n"
                 f"refs/heads/b {mine} refs/heads/b {self.base}\n")
        res = run_review(self.repo, "--hook-stdin", "--base", self.base,
                         stdin=stdin)
        self.assertEqual(res["code"], 1)
        self.assertIn("multi-ref-push", res["data"]["reasons"])

    def test_hook_stdin_branch_delete_blocked(self):
        stdin = f"refs/heads/old {'0'*40} refs/heads/old {self.base}\n"
        res = run_review(self.repo, "--hook-stdin", "--base", self.base,
                         stdin=stdin)
        self.assertEqual(res["code"], 1)
        self.assertIn("push-delete", res["data"]["reasons"])

    def test_hook_stdin_non_fast_forward_blocked(self):
        other = self.tmp / "other"
        subprocess.run(["git", "init", str(other)], check=True,
                       capture_output=True)
        git(other, "config", "user.email", "o@t")
        git(other, "config", "user.name", "o")
        git(other, "config", "commit.gpgsign", "false")
        (other / "x.txt").write_text("diverged\n")
        git(other, "add", "x.txt")
        git(other, "commit", "-m", "foreign")
        foreign_tip = head(other)
        mine = head(self.repo)
        # foreign_tip exists in our object store after fetch-less transport:
        git(self.repo, "fetch", str(other), "HEAD")
        stdin = f"refs/heads/main {mine} refs/heads/main {foreign_tip}\n"
        res = run_review(self.repo, "--hook-stdin", "--base", self.base,
                         stdin=stdin,
                         env_extra={"AWX_PUBLISH_APPROVED": "yes"})
        self.assertEqual(res["code"], 1)
        self.assertIn("non-fast-forward", res["data"]["reasons"])

    def test_hook_stdin_clean_fast_forward_needs_approval_env(self):
        mine = head(self.repo)
        stdin = f"refs/heads/main {mine} refs/heads/main {self.base}\n"
        res = run_review(self.repo, "--hook-stdin", "--base", self.base,
                         stdin=stdin)
        self.assertEqual(res["code"], 1)
        self.assertIn("approval-env-absent", res["data"]["reasons"])
        res2 = run_review(self.repo, "--hook-stdin", "--base", self.base,
                          stdin=stdin,
                          env_extra={"AWX_PUBLISH_APPROVED": "yes"})
        self.assertNotIn("approval-env-absent", res2["data"]["reasons"])

    def test_remote_oid_not_in_object_store_is_unknown(self):
        mine = head(self.repo)
        stdin = f"refs/heads/main {mine} refs/heads/main {'9'*40}\n"
        res = run_review(self.repo, "--hook-stdin", "--base", self.base,
                         stdin=stdin,
                         env_extra={"AWX_PUBLISH_APPROVED": "yes"})
        self.assertIn("remote-oid-unknown", res["data"]["reasons"])

    def test_scan_tree_spawn_count_constant_per_blob(self):
        for i in range(6):
            self.commit_file(f"f{i}.txt", f"clean {i}\n")
        tip = head(self.repo)
        real_run, real_popen = gpr.subprocess.run, gpr.subprocess.Popen
        with mock.patch.object(gpr.subprocess, "run",
                               wraps=real_run), \
             mock.patch.object(gpr.subprocess, "Popen",
                               wraps=real_popen) as m_popen:
            scan = gpr.scan_tree(self.repo, tip)
        total = m_popen.call_count  # every spawn goes through Popen
        self.assertLessEqual(
            total, 3,
            f"blob reads must share one cat-file --batch; got {total} spawns")
        self.assertTrue(scan["ok"])
        self.assertEqual(scan["scannedBlobCount"], scan["fileCount"])

    def test_scan_history_spawn_count_constant_per_blob(self):
        for i in range(5):
            self.commit_file(f"h{i}.txt", f"clean {i}\n", msg=f"add h{i}")
        tip = head(self.repo)
        real_run, real_popen = gpr.subprocess.run, gpr.subprocess.Popen
        with mock.patch.object(gpr.subprocess, "run",
                               wraps=real_run), \
             mock.patch.object(gpr.subprocess, "Popen",
                               wraps=real_popen) as m_popen:
            scan = gpr.scan_history(self.repo, self.base, tip)
        total = m_popen.call_count  # every spawn goes through Popen
        # rev-list + diff-tree per commit (5) + one batch reader = 7;
        # never scales with blob count.
        self.assertLessEqual(
            total, 7,
            f"history blob reads must share one cat-file --batch; got {total}")
        self.assertTrue(scan["ok"])
        self.assertEqual(scan["scannedBlobCount"], 5)

    @unittest.skipUnless(OLD_TOOL.exists(), "pre-change backup copy missing")
    def test_findings_identical_to_per_blob_spawn_path(self):
        self.commit_file("conf.txt", f"key={FAKE_AWS_KEY}\n")
        for i in range(5):
            self.commit_file(f"extra{i}.txt", f"pad {i}\n")
        args = ("--base", self.base)
        old = run_review(self.repo, *args, tool=OLD_TOOL)
        new = run_review(self.repo, *args)
        self.assertEqual(new["code"], 1)
        self.assertEqual(old["data"]["verdict"], new["data"]["verdict"])
        self.assertEqual(old["data"]["reasons"], new["data"]["reasons"])
        for scope in ("treeScan", "historyScan"):
            for key in ("findings", "allowed", "scannedBlobCount"):
                self.assertEqual(old["data"][scope][key],
                                 new["data"][scope][key],
                                 f"{scope}.{key} diverged from old path")
        self.assertNotIn(FAKE_AWS_KEY, new["stdout"] + new["stderr"])


if __name__ == "__main__":
    unittest.main()
