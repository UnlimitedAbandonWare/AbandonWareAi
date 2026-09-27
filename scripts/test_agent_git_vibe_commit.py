"""Fixture tests for agent_git_vibe_commit.py. The live repo is not mutated."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from functools import partial

# Windows may hold a finished fixture directory briefly; functional assertions still fail normally.
FixtureDirectory = partial(tempfile.TemporaryDirectory, ignore_cleanup_errors=True)
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "agent_git_vibe_commit.py"
JOURNAL = ROOT / "scripts" / "work_journal.py"


def run_tool(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-B", str(TOOL), *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )


def git(repo: Path, *args: str) -> None:
    subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True)


def git_out(repo: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(repo), *args])


def git_dir(repo: Path) -> Path:
    return Path(git_out(repo, "rev-parse", "--absolute-git-dir").decode().strip())


def make_repo(root: Path) -> Path:
    repo = root / "repo"
    repo.mkdir()
    git(repo, "init", "-b", "main")
    git(repo, "config", "--local", "user.name", "Vibe Test")
    git(repo, "config", "--local", "user.email", "vibe-test@example.com")
    git(repo, "config", "--local", "demo1.gittest", "fixture")
    git(repo, "config", "--local", "commit.gpgsign", "false")
    git(repo, "config", "--local", "gc.auto", "0")
    git(repo, "config", "--local", "maintenance.auto", "false")
    git(repo, "config", "--local", "core.fsmonitor", "false")
    return repo


def message_file(root: Path) -> Path:
    path = root / "message.txt"
    path.write_text("Reason: vibe fixture\nVerify: synthetic tests\nConstraint: local only\n",
                    encoding="utf-8")
    return path


def staged_foreign_blob_missing(repo: Path, path: str) -> None:
    """Stage a foreign path, then remove its loose object so the index entry is corrupt."""
    (repo / path).write_text("foreign staged\n", encoding="utf-8")
    git(repo, "add", "--", path)
    oid = git_out(repo, "rev-parse", ":" + path).decode().strip()
    obj = git_dir(repo) / "objects" / oid[:2] / oid[2:]
    obj.chmod(0o644)  # git marks objects read-only on Windows
    obj.unlink()


class AgentGitVibeCommitTests(unittest.TestCase):
    def owned_fixture(self, root: Path):
        repo = make_repo(root)
        (repo / "owned.txt").write_text("base owned\n", encoding="utf-8")
        git(repo, "add", "--", "owned.txt")
        git(repo, "commit", "-m", "fixture baseline")
        (repo / "owned.txt").write_text("owned v2\n", encoding="utf-8")
        return repo, message_file(root)

    def test_dry_run_shows_plan_without_mutating(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            (repo / "foreign.txt").write_text("foreign\n", encoding="utf-8")
            git(repo, "add", "--", "foreign.txt")
            before_index = (git_dir(repo) / "index").read_bytes()
            before_head = git_out(repo, "rev-parse", "HEAD")
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message), "--dry-run")
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            self.assertEqual(payload["outcome"], "plan")
            self.assertIsNone(payload["committed"])
            self.assertIsNone(payload["deferred"])
            steps = [s["step"] for s in payload["plan"]["steps"]]
            self.assertEqual(steps, ["lock", "add", "scan", "commit"])
            self.assertIn("--preserve-foreign-staged",
                          payload["plan"]["steps"][-1]["argv"])
            self.assertEqual(payload["plan"]["foreignStagedPaths"], ["foreign.txt"])
            self.assertEqual(before_index, (git_dir(repo) / "index").read_bytes())
            self.assertEqual(before_head, git_out(repo, "rev-parse", "HEAD"))

    def test_preserve_commit_with_foreign_staged(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            (repo / "foreign.txt").write_text("foreign\n", encoding="utf-8")
            git(repo, "add", "--", "foreign.txt")
            foreign_entry = git_out(repo, "ls-files", "--stage", "-z", "--", "foreign.txt")
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message))
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            self.assertEqual(payload["outcome"], "committed")
            self.assertEqual(len(payload["committed"]), 40)
            self.assertEqual(payload["mode"], "preserve-foreign-staged")
            self.assertTrue(payload["foreignStagingPreserved"])
            self.assertEqual(foreign_entry,
                             git_out(repo, "ls-files", "--stage", "-z", "--", "foreign.txt"))
            self.assertEqual(b"owned v2\n", git_out(repo, "show", "HEAD:owned.txt"))

    def test_missing_blob_on_foreign_staging_does_not_block_preserve(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            staged_foreign_blob_missing(repo, "foreign.txt")
            foreign_entry = git_out(repo, "ls-files", "--stage", "-z", "--", "foreign.txt")
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message))
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            self.assertEqual(payload["outcome"], "committed")
            self.assertEqual(foreign_entry,
                             git_out(repo, "ls-files", "--stage", "-z", "--", "foreign.txt"))

    def test_strict_mode_defers_on_foreign_staging(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            (repo / "foreign.txt").write_text("foreign\n", encoding="utf-8")
            git(repo, "add", "--", "foreign.txt")
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message), "--strict-staging")
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 2, proc.stdout)
            self.assertEqual(payload["outcome"], "deferred")
            self.assertEqual(payload["deferred"], "foreign-or-mismatched-staging")
            self.assertEqual(b"owned v2\n",
                             git_out(repo, "show", ":owned.txt"))

    def test_strict_mode_commits_clean_set(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message), "--strict-staging")
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            self.assertEqual(payload["outcome"], "committed")
            self.assertEqual(payload["reason"], "committed")
            self.assertEqual(b"", git_out(repo, "diff", "--cached", "--name-only"))

    def test_stale_zero_byte_lock_is_soft_cleared_then_commit(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            lock = git_dir(repo) / "index.lock"
            lock.write_bytes(b"")
            import time
            past = time.time() - 2 * 86400
            os.utime(lock, (past, past))
            backup = Path(tmp) / "lockbak"
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message),
                            "--backup-dir", str(backup))
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            self.assertEqual(payload["outcome"], "committed")
            self.assertEqual(payload["lock"]["action"], "moved")
            self.assertFalse(lock.exists())
            self.assertEqual(1, len(list(backup.glob("index.lock.bak-*"))))

    def test_fresh_or_nonempty_lock_defers_and_is_preserved(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            lock = git_dir(repo) / "index.lock"
            lock.write_bytes(b"")
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message))
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 4, proc.stdout)
            self.assertEqual(payload["outcome"], "deferred")
            self.assertEqual(payload["deferred"], "index-lock")
            self.assertEqual(payload["lock"]["reason"], "lock-fresh")
            self.assertTrue(lock.exists())
            lock.write_bytes(b"foreign owner")
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message))
            payload = json.loads(proc.stdout)
            self.assertEqual(payload["lock"]["reason"], "lock-nonempty")
            self.assertEqual(b"foreign owner", lock.read_bytes())

    def test_journal_auto_line_when_task_id_given(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            opened = subprocess.run([sys.executable, "-B", str(JOURNAL), "open",
                                     "--root", str(repo), "--task", "vibe-task",
                                     "--agent", "fixture", "--purpose", "test",
                                     "--scope", "owned.txt"],
                                    check=True, capture_output=True, text=True)
            task_id = json.loads(opened.stdout)["taskId"]
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message), "--task-id", task_id)
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            self.assertEqual(payload["journalNote"], "ok")
            journal = json.loads(
                (repo / "data/agent-handoff/codex-autonomy" / task_id / "journal.json")
                .read_text(encoding="utf-8"))
            notes = [e["text"] for e in journal["events"] if e["kind"] == "info"]
            self.assertIn(f"AUTO:committed={payload['committed']}", notes)

    def test_discarded_or_mismatched_remote_defers_every_mode(self):
        for url, reason in (
            ("https://github.com/UnlimitedAbandonWare/AbandonWare3", "forbidden-remote"),
            ("https://example.com/other/repo.git", "origin-mismatch"),
        ):
            with self.subTest(url=url), FixtureDirectory() as tmp:
                repo, message = self.owned_fixture(Path(tmp))
                git(repo, "remote", "add", "origin", url)
                head_before = git_out(repo, "rev-parse", "HEAD")
                index_before = (git_dir(repo) / "index").read_bytes()
                for extra in ([], ["--dry-run"], ["--strict-staging"]):
                    proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                                    "--message-file", str(message), *extra)
                    payload = json.loads(proc.stdout)
                    self.assertEqual(proc.returncode, 2, proc.stdout)
                    self.assertEqual(payload["outcome"], "deferred")
                    self.assertEqual(payload["deferred"], reason)
                    self.assertIsNone(payload["committed"])
                    self.assertNotIn(url, proc.stdout)
                self.assertEqual(head_before, git_out(repo, "rev-parse", "HEAD"))
                self.assertEqual(index_before, (git_dir(repo) / "index").read_bytes())

    def test_deferred_reason_for_missing_message_labels(self):
        with FixtureDirectory() as tmp:
            repo = make_repo(Path(tmp))
            (repo / "owned.txt").write_text("owned\n", encoding="utf-8")
            bad = Path(tmp) / "bad.txt"
            bad.write_text("no labels\n", encoding="utf-8")
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(bad))
            payload = json.loads(proc.stdout)
            self.assertEqual(payload["outcome"], "deferred")
            self.assertEqual(payload["deferred"],
                             "message-missing-reason-verify-constraint")


if __name__ == "__main__":
    unittest.main()
