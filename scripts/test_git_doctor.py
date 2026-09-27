"""Fixture-repo tests for scripts/git_doctor.py — disposable repos only.

Never touches the operational repository: every case builds a fresh
tempdir repo and git writes happen only inside it.
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
DOCTOR = ROOT / "scripts" / "git_doctor.py"


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
    (path / "a.txt").write_text("seed\n", encoding="utf-8")
    git(path, "add", "a.txt")
    commit = git(path, "commit", "-m", "init")
    assert commit.returncode == 0, commit.stderr
    return path


def run_doctor(repo: Path, *extra: str) -> dict:
    proc = subprocess.run(
        [sys.executable, "-B", str(DOCTOR), "--root", str(repo),
         "--json", *extra],
        capture_output=True, text=True, timeout=120)
    return {"code": proc.returncode,
            "data": json.loads(proc.stdout) if proc.stdout.strip() else {},
            "stderr": proc.stderr}


def issue_codes(data: dict) -> set:
    return {i["code"] for i in data.get("issues", [])}


def issue_for(data: dict, code: str) -> dict:
    return next((i for i in data.get("issues", []) if i["code"] == code), {})


@unittest.skipUnless(shutil.which("git"), "git not on PATH")
class DoctorFixtureTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="git-doctor-test-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_clean_repo_is_clear(self):
        repo = init_repo(self.tmp / "clean")
        res = run_doctor(repo)
        self.assertEqual(res["code"], 0, res["stderr"])
        self.assertEqual(res["data"]["decision"], "CLEAR")
        self.assertTrue(res["data"]["head"]["ref"])
        self.assertEqual(res["data"]["issues"], [])

    def test_stale_index_lock_blocks_writes_not_reads(self):
        repo = init_repo(self.tmp / "locked")
        lock = repo / ".git" / "index.lock"
        lock.write_bytes(b"")
        res = run_doctor(repo)
        issue = issue_for(res["data"], "index-lock-present")
        self.assertTrue(issue, res["data"])
        self.assertIn("git-commit", issue["blockedActions"])
        self.assertIn("git-status", issue["continuableActions"])
        self.assertEqual(issue["nextOwner"], "user")
        self.assertTrue(lock.exists(), "doctor must never delete index.lock")

    def test_foreign_staged_paths_split_by_owned_set(self):
        repo = init_repo(self.tmp / "staged")
        (repo / "b.txt").write_text("foreign\n", encoding="utf-8")
        (repo / "c.txt").write_text("owned\n", encoding="utf-8")
        git(repo, "add", "b.txt", "c.txt")
        res = run_doctor(repo, "--owned-path", "c.txt")
        staging = res["data"]["staging"]
        self.assertEqual(staging["foreignStagedPaths"], ["b.txt"])
        issue = issue_for(res["data"], "foreign-staged-paths")
        self.assertEqual(issue["nextOwner"], "foreign-session")
        self.assertIn("work-ledger-checkpoint", issue["continuableActions"])

    def test_staged_without_owned_list_reports_no_foreign_issue(self):
        repo = init_repo(self.tmp / "staged2")
        (repo / "b.txt").write_text("x\n", encoding="utf-8")
        git(repo, "add", "b.txt")
        res = run_doctor(repo)
        self.assertNotIn("foreign-staged-paths", issue_codes(res["data"]))
        self.assertEqual(res["data"]["staging"]["stagedCount"], 1)

    def test_merge_head_reports_in_progress(self):
        repo = init_repo(self.tmp / "merge")
        (repo / ".git" / "MERGE_HEAD").write_text("0" * 40 + "\n")
        res = run_doctor(repo)
        issue = issue_for(res["data"], "merge-in-progress")
        self.assertEqual(issue["nextOwner"], "foreign-session")

    def test_not_a_repo_exits_nonzero(self):
        plain = self.tmp / "plain"
        plain.mkdir()
        res = run_doctor(plain)
        self.assertEqual(res["code"], 3)
        self.assertIn("not-a-repo", issue_codes(res["data"]))

    def test_missing_worktree_path_flagged_never_pruned(self):
        repo = init_repo(self.tmp / "main-wt")
        linked = self.tmp / "linked-wt"
        add = git(repo, "worktree", "add", str(linked))
        self.assertEqual(add.returncode, 0, add.stderr)
        shutil.rmtree(linked)
        res = run_doctor(repo)
        codes = issue_codes(res["data"])
        self.assertTrue({"offline-worktrees", "prunable-worktrees"} & codes,
                        res["data"])
        self.assertTrue(any("prune" in a for i in res["data"]["issues"]
                            for a in i["blockedActions"]))
        admin = repo / ".git" / "worktrees"
        self.assertTrue(admin.exists() and list(admin.iterdir()),
                        "doctor must never prune worktree metadata")

    def test_probe_remote_failure_classified(self):
        repo = init_repo(self.tmp / "probe")
        git(repo, "remote", "add", "origin", "file:///nonexistent-repo-xyz")
        res = run_doctor(repo, "--probe-remote", "origin")
        probe = res["data"].get("probe", {})
        self.assertTrue(probe.get("attempted"))
        self.assertFalse(probe.get("ok"))
        self.assertIn(probe.get("errorKind"),
                      {"remote-not-found", "remote-error", "remote-timeout"})
        self.assertTrue(issue_codes(res["data"]))

    def test_probe_is_opt_in_no_network_by_default(self):
        repo = init_repo(self.tmp / "noprobe")
        git(repo, "remote", "add", "origin", "file:///nonexistent-repo-xyz")
        res = run_doctor(repo)
        self.assertNotIn("probe", res["data"])

    def test_url_rewrite_rule_flagged(self):
        repo = init_repo(self.tmp / "rewrite")
        git(repo, "remote", "add", "origin", "https://example.invalid/o/r.git")
        git(repo, "config", "url.https://mirror.invalid/.pushInsteadOf",
            "https://example.invalid/")
        res = run_doctor(repo)
        self.assertIn("url-rewrite-present", issue_codes(res["data"]))


if __name__ == "__main__":
    unittest.main()
