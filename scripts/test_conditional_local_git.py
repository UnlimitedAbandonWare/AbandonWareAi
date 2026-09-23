"""Fixture tests for the conditional local Git gate. The live repo is not mutated."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "conditional_local_git.py"


def run_tool(*args: str, env: dict | None = None) -> subprocess.CompletedProcess[str]:
    merged = os.environ.copy()
    if env:
        merged.update(env)
    return subprocess.run(
        [sys.executable, "-B", str(TOOL), *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=merged,
        check=False,
    )


def git(repo: Path, *args: str) -> None:
    subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True)


class ConditionalLocalGitTests(unittest.TestCase):
    def test_policy_names_the_canonical_root_and_keeps_push_forbidden(self) -> None:
        proc = run_tool("policy")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn(r"C:\AbandonWare\demo-1\demo-1\src", proc.stdout)
        self.assertIn("push", proc.stdout)
        self.assertIn("AGENTS.override.md", proc.stdout)

    def test_command_matrix(self) -> None:
        expect = {
            ("status",): "allow-local",
            ("diff", "--cached"): "allow-local",
            ("add", "--", "scripts/conditional_local_git.py"): "needs-scan",
            ("add", "-A"): "forbid",
            ("add", "."): "forbid",
            ("commit", "-m", "x"): "needs-scan",
            ("commit", "--no-verify", "-m", "x"): "forbid",
            ("commit", "-a", "-m", "x"): "forbid",
            ("push",): "forbid",
            ("reset", "--hard"): "forbid",
            ("clean", "-fdx"): "forbid",
            ("pull",): "forbid",
        }
        for argv, verdict in expect.items():
            with self.subTest(argv=argv):
                proc = run_tool("check", "--", "git", *argv)
                payload = json.loads(proc.stdout)
                self.assertEqual(payload["verdict"], verdict, proc.stdout)
                if verdict == "forbid":
                    self.assertEqual(proc.returncode, 2)
                else:
                    self.assertEqual(proc.returncode, 0)

    def test_git_dash_c_does_not_hide_push(self) -> None:
        proc = run_tool("check", "--", "git", "-C", r"C:\elsewhere", "-c", "a.b=c", "push")
        payload = json.loads(proc.stdout)
        self.assertEqual(payload["verdict"], "forbid")

    def make_repo(self, root: Path) -> Path:
        repo = root / "repo"
        repo.mkdir()
        git(repo, "init", "-b", "main")
        git(repo, "config", "--local", "user.name", "Gate Test")
        git(repo, "config", "--local", "user.email", "gate-test@example.com")
        git(repo, "config", "--local", "demo1.gittest", "fixture")
        git(repo, "config", "--local", "commit.gpgsign", "false")
        return repo

    def test_commit_scans_the_staged_blob_and_rejects_a_secret(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo = self.make_repo(Path(tmp))
            target = repo / "note.txt"
            target.write_text("token AKIA"" ""IOSFODNN7EXAMPLE\n", encoding="utf-8")
            git(repo, "add", "--", "note.txt")
            proc = run_tool("scan", "--repo", str(repo), "--path", "note.txt")
            payload = json.loads(proc.stdout)
            self.assertEqual(payload["reason"], "secret-found")
            self.assertGreaterEqual(payload["secretCounts"]["aws-access-key"], 1)
            self.assertNotIn("AKIA"" ""IOSFODNN7EXAMPLE", proc.stdout)
            self.assertEqual(proc.returncode, 2)

    def test_commit_rejects_foreign_staging_and_accepts_an_owned_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo = self.make_repo(Path(tmp))
            (repo / "owned.txt").write_text("owned\n", encoding="utf-8")
            (repo / "other.txt").write_text("other\n", encoding="utf-8")
            git(repo, "add", "--", "owned.txt", "other.txt")
            blocked = run_tool("commit", "--repo", str(repo), "--message-file", str(repo / "missing"), "--path", "owned.txt")
            self.assertEqual(json.loads(blocked.stdout)["reason"], "foreign-or-mismatched-staging")
            self.assertIn("other.txt", json.loads(blocked.stdout)["foreignPaths"])
            git(repo, "rm", "--cached", "-q", "--", "other.txt")
            message = repo / "msg.txt"
            message.write_text("Reason: keep the owned note\nVerify: fixture commit\nConstraint: no push\n", encoding="utf-8")
            done = run_tool("commit", "--repo", str(repo), "--message-file", str(message), "--path", "owned.txt")
            payload = json.loads(done.stdout)
            self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
            self.assertEqual(payload["reason"], "committed")
            self.assertEqual(len(payload["commit"]), 40)

    def test_index_lock_blocks_the_commit_gate(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo = self.make_repo(Path(tmp))
            (repo / "owned.txt").write_text("owned\n", encoding="utf-8")
            git(repo, "add", "--", "owned.txt")
            git_dir = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "--absolute-git-dir"], text=True).strip()
            (Path(git_dir) / "index.lock").write_text("", encoding="utf-8")
            message = repo / "msg.txt"
            message.write_text("Reason: x\nVerify: y\nConstraint: z\n", encoding="utf-8")
            proc = run_tool("commit", "--repo", str(repo), "--message-file", str(message), "--path", "owned.txt")
            self.assertEqual(json.loads(proc.stdout)["reason"], "index-lock")
            self.assertEqual(proc.returncode, 4)

    def test_live_canonical_repo_is_not_a_fixture(self) -> None:
        proc = run_tool("check", "--", "git", "add", "--", ".secrets/providers.json")
        self.assertEqual(json.loads(proc.stdout)["verdict"], "forbid")


if __name__ == "__main__":
    unittest.main()
