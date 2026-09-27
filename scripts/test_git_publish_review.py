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

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "git_publish_review.py"
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
               env_extra: dict | None = None) -> dict:
    env = dict(os.environ)
    env.pop("AWX_PUBLISH_APPROVED", None)
    if env_extra:
        env.update(env_extra)
    proc = subprocess.run(
        [sys.executable, "-B", str(TOOL), "--root", str(repo),
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


if __name__ == "__main__":
    unittest.main()
