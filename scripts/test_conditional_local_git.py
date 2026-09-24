"""Fixture tests for the conditional local Git gate. The live repo is not mutated."""
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
        git(repo, "config", "--local", "gc.auto", "0")
        git(repo, "config", "--local", "maintenance.auto", "false")
        git(repo, "config", "--local", "core.fsmonitor", "false")
        return repo

    def test_commit_scans_the_staged_blob_and_rejects_a_secret(self) -> None:
        with FixtureDirectory() as tmp:
            # Only synthetic cleanup errors are ignored, never scan/commit assertions.
            repo = self.make_repo(Path(tmp))
            target = repo / "note.txt"
            fake = "AK" + "IA" + "IOSFODNN7EXAMPLE"
            target.write_text("token " + fake + "\n", encoding="utf-8")
            git(repo, "add", "--", "note.txt")
            proc = run_tool("scan", "--repo", str(repo), "--path", "note.txt")
            payload = json.loads(proc.stdout)
            self.assertEqual(payload["reason"], "secret-found")
            self.assertGreaterEqual(payload["secretCounts"]["aws-access-key"], 1)
            self.assertNotIn(fake, proc.stdout)
            self.assertEqual(proc.returncode, 2)

    def test_commit_rejects_foreign_staging_and_accepts_an_owned_file(self) -> None:
        with FixtureDirectory() as tmp:
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
        with FixtureDirectory() as tmp:
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

    def test_scan_rejects_oversized_candidate_set(self) -> None:
        with FixtureDirectory() as tmp:
            repo = self.make_repo(Path(tmp))
            names = [f"f{i:02d}.txt" for i in range(41)]
            for name in names:
                (repo / name).write_text("x\n", encoding="utf-8")
            git(repo, "add", "--", *names)
            proc = run_tool("scan", "--repo", str(repo))
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 2)
            self.assertEqual(payload["reason"], "blast-radius-paths")
            self.assertEqual(payload["candidatePathCount"], 41)
            self.assertEqual(payload["candidateDeletionCount"], 0)
            self.assertEqual(payload["intendedRemote"], "AbandonWareAi")
            self.assertFalse(payload["originMismatch"])

    def test_scan_rejects_mass_staged_deletions(self) -> None:
        with FixtureDirectory() as tmp:
            repo = self.make_repo(Path(tmp))
            names = [f"d{i:02d}.txt" for i in range(16)]
            for name in names:
                (repo / name).write_text("x\n", encoding="utf-8")
            git(repo, "add", "--", *names)
            git(repo, "commit", "-m", "baseline")
            git(repo, "rm", "-q", "--", *names)
            proc = run_tool("scan", "--repo", str(repo))
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 2)
            self.assertEqual(payload["reason"], "blast-radius-deletions")
            self.assertEqual(payload["candidateDeletionCount"], 16)

    def test_small_candidate_set_passes_despite_mass_worktree_deletions(self) -> None:
        with FixtureDirectory() as tmp:
            repo = self.make_repo(Path(tmp))
            names = [f"w{i:02d}.txt" for i in range(20)]
            for name in names:
                (repo / name).write_text("x\n", encoding="utf-8")
            (repo / "owned.txt").write_text("base\n", encoding="utf-8")
            git(repo, "add", "--", "owned.txt", *names)
            git(repo, "commit", "-m", "baseline")
            for name in names:
                (repo / name).unlink()
            (repo / "owned.txt").write_text("owned v2\n", encoding="utf-8")
            git(repo, "add", "--", "owned.txt")
            message = repo / "msg.txt"
            message.write_text("Reason: x\nVerify: y\nConstraint: z\n", encoding="utf-8")
            done = run_tool("commit", "--repo", str(repo), "--message-file", str(message), "--path", "owned.txt")
            payload = json.loads(done.stdout)
            self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
            self.assertEqual(payload["reason"], "committed")
            self.assertEqual(payload["candidatePathCount"], 1)
            self.assertEqual(payload["candidateDeletionCount"], 0)
            self.assertEqual(payload["worktreeCounts"]["deleted"], 20)

    def test_scan_reports_missing_blob_separately_from_blocked_paths(self) -> None:
        with FixtureDirectory() as tmp:
            repo = self.make_repo(Path(tmp))
            (repo / "staged.txt").write_text("staged\n", encoding="utf-8")
            git(repo, "add", "--", "staged.txt")
            oid = subprocess.check_output(
                ["git", "-C", str(repo), "rev-parse", ":staged.txt"], text=True).strip()
            git_dir = Path(subprocess.check_output(
                ["git", "-C", str(repo), "rev-parse", "--absolute-git-dir"],
                text=True).strip())
            obj = git_dir / "objects" / oid[:2] / oid[2:]
            obj.chmod(0o644)  # git marks objects read-only on Windows
            obj.unlink()
            proc = run_tool("scan", "--repo", str(repo), "--path", "staged.txt")
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 3)
            self.assertEqual(payload["reason"], "missing-blob")
            self.assertEqual(payload["missingBlobPaths"], ["staged.txt"])
            self.assertEqual(payload["blockedPaths"], [])

    def test_scan_flags_non_intended_origin_without_hard_stop(self) -> None:
        with FixtureDirectory() as tmp:
            repo = self.make_repo(Path(tmp))
            (repo / "a.txt").write_text("a\n", encoding="utf-8")
            git(repo, "add", "--", "a.txt")
            git(repo, "remote", "add", "origin",
                "https://github.com/UnlimitedAbandonWare/AbandonWare3")
            proc = run_tool("scan", "--repo", str(repo))
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 0, proc.stdout)
            self.assertEqual(payload["reason"], "ok")
            self.assertEqual(payload["intendedRemote"], "AbandonWareAi")
            self.assertTrue(payload["originMismatch"])

    def selected_fixture(self, root):
        repo = self.make_repo(root)
        (repo / "owned.txt").write_text("base owned\n", encoding="utf-8")
        (repo / "SelfAskPlannerOwnershipContractTest.java").write_text("base foreign\n", encoding="utf-8")
        git(repo, "add", "--", "owned.txt", "SelfAskPlannerOwnershipContractTest.java")
        git(repo, "commit", "-m", "fixture baseline")
        (repo / "SelfAskPlannerOwnershipContractTest.java").write_text("staged foreign\n", encoding="utf-8")
        git(repo, "add", "--", "SelfAskPlannerOwnershipContractTest.java")
        (repo / "SelfAskPlannerOwnershipContractTest.java").write_text("unstaged foreign\n", encoding="utf-8")
        (repo / "owned.txt").write_text("selected owned\n", encoding="utf-8")
        message = root / "message.txt"
        message.write_text("Reason: selected fixture\nVerify: synthetic tests\nConstraint: local only\n", encoding="utf-8")
        return repo, message

    def capture(self, repo, *args):
        return subprocess.check_output(["git", "-C", str(repo), *args])

    def selected(self, repo, message, *paths):
        args = ["commit", "--repo", str(repo), "--message-file", str(message), "--preserve-foreign-staged"]
        for path in paths:
            args += ["--path", path]
        return run_tool(*args)

    def test_selected_commit_preserves_foreign_partial_staging_and_updates_only_owned_index(self):
        with FixtureDirectory() as tmp:
            repo, message = self.selected_fixture(Path(tmp))
            foreign = "SelfAskPlannerOwnershipContractTest.java"
            before_entry = self.capture(repo, "ls-files", "--stage", "-z", "--", foreign)
            before_diff = self.capture(repo, "diff", "--cached", "--", foreign)
            done = self.selected(repo, message, "owned.txt")
            self.assertEqual(0, done.returncode, done.stdout + done.stderr)
            self.assertTrue(json.loads(done.stdout)["foreignStagingPreserved"])
            self.assertEqual(before_entry, self.capture(repo, "ls-files", "--stage", "-z", "--", foreign))
            self.assertEqual(before_diff, self.capture(repo, "diff", "--cached", "--", foreign))
            self.assertEqual(b"base foreign\n", self.capture(repo, "show", "HEAD:" + foreign))
            self.assertEqual("unstaged foreign\n", (repo / foreign).read_text(encoding="utf-8"))
            self.assertEqual(b"owned.txt\n", self.capture(repo, "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD"))
            self.assertEqual(b"", self.capture(repo, "diff", "--cached", "--", "owned.txt"))
            self.assertEqual(b"selected owned\n", self.capture(repo, "show", "HEAD:owned.txt"))
            self.assertFalse((repo / ".git/index.lock").exists())

    def test_selected_commit_disables_automatic_gc_and_keeps_foreign_blob_readable(self):
        with FixtureDirectory() as tmp:
            repo, message = self.selected_fixture(Path(tmp))
            foreign = "SelfAskPlannerOwnershipContractTest.java"
            oid = self.capture(repo, "rev-parse", ":" + foreign).decode().strip()
            before = self.capture(repo, "cat-file", "blob", oid)
            git(repo, "config", "gc.auto", "1")
            git(repo, "config", "maintenance.auto", "true")
            git(repo, "config", "gc.pruneExpire", "now")
            hook = repo / ".git/hooks/pre-commit"
            hook.write_text('#!/bin/sh\n[ "$(git config --get gc.auto)" = "0" ] || exit 41\n[ "$(git config --get maintenance.auto)" = "false" ] || exit 42\ngit gc --auto\n', encoding="utf-8")
            hook.chmod(0o755)
            done = self.selected(repo, message, "owned.txt")
            self.assertEqual(0, done.returncode, done.stdout + done.stderr)
            self.assertEqual(before, self.capture(repo, "cat-file", "blob", oid))
            self.assertEqual(oid, self.capture(repo, "rev-parse", ":" + foreign).decode().strip())
            self.assertEqual(b"1\n", self.capture(repo, "config", "--local", "--get", "gc.auto"))

    def test_selected_delete_new_unicode_and_literal_pathspec(self):
        with FixtureDirectory() as tmp:
            repo, message = self.selected_fixture(Path(tmp))
            (repo / "owned.txt").unlink()
            name = "notes/한글 [a].txt"
            (repo / "notes").mkdir()
            (repo / name).write_text("selected unicode", encoding="utf-8")
            done = self.selected(repo, message, "owned.txt", name)
            self.assertEqual(0, done.returncode, done.stdout + done.stderr)
            self.assertEqual("selected unicode".encode(), self.capture(repo, "show", "HEAD:" + name))
            self.assertEqual(b"", self.capture(repo, "ls-files", "--stage", "--", "owned.txt"))
            self.assertEqual(b"", self.capture(repo, "diff", "--cached", "--", name))

    def test_selected_scan_failure_preserves_real_index_and_head(self):
        for name, content in (("owned.txt", "sk-" + "Q" * 30),
                              ("build/generated.txt", "generated"),
                              ("owned.txt", "x" * (2 * 1024 * 1024 + 1))):
            with self.subTest(name=name, size=len(content)), FixtureDirectory() as tmp:
                repo, message = self.selected_fixture(Path(tmp))
                target = repo / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding="utf-8")
                before = (repo / ".git/index").read_bytes()
                head = self.capture(repo, "rev-parse", "HEAD")
                rejected = self.selected(repo, message, name)
                self.assertNotEqual(0, rejected.returncode)
                self.assertEqual("selected-staged-scan-failed", json.loads(rejected.stdout)["reason"])
                self.assertEqual(before, (repo / ".git/index").read_bytes())
                self.assertEqual(head, self.capture(repo, "rev-parse", "HEAD"))

    def test_selected_commit_runs_existing_hook_and_keeps_foreign_index_on_failure(self):
        with FixtureDirectory() as tmp:
            repo, message = self.selected_fixture(Path(tmp))
            hook = repo / ".git/hooks/pre-commit"
            hook.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
            hook.chmod(0o755)
            before = (repo / ".git/index").read_bytes()
            head = self.capture(repo, "rev-parse", "HEAD")
            rejected = self.selected(repo, message, "owned.txt")
            self.assertNotEqual(0, rejected.returncode)
            self.assertEqual(before, (repo / ".git/index").read_bytes())
            self.assertEqual(head, self.capture(repo, "rev-parse", "HEAD"))
            self.assertFalse((repo / ".git/index.lock").exists())

    def test_selected_preexisting_lock_and_ambient_index_are_never_touched(self):
        with FixtureDirectory() as tmp:
            repo, message = self.selected_fixture(Path(tmp))
            lock = repo / ".git/index.lock"
            lock.write_bytes(b"foreign owner")
            before = (repo / ".git/index").read_bytes()
            rejected = self.selected(repo, message, "owned.txt")
            self.assertEqual("index-lock", json.loads(rejected.stdout)["reason"])
            self.assertEqual(b"foreign owner", lock.read_bytes())
            self.assertEqual(before, (repo / ".git/index").read_bytes())
            alternate = run_tool("commit", "--repo", str(repo), "--message-file", str(message),
                                 "--preserve-foreign-staged", "--path", "owned.txt",
                                 env={"GIT_INDEX_FILE": str(Path(tmp) / "foreign-index")})
            self.assertEqual("ambient-git-routing", json.loads(alternate.stdout)["reason"])

    def test_selected_newborn_commit_leaves_foreign_addition_staged(self):
        with FixtureDirectory() as tmp:
            repo = self.make_repo(Path(tmp))
            (repo / "owned.txt").write_text("owned", encoding="utf-8")
            (repo / "foreign.txt").write_text("foreign", encoding="utf-8")
            git(repo, "add", "--", "foreign.txt")
            message = Path(tmp) / "message.txt"
            message.write_text("Reason: x\nVerify: y\nConstraint: z\n", encoding="utf-8")
            done = self.selected(repo, message, "owned.txt")
            self.assertEqual(0, done.returncode, done.stdout + done.stderr)
            self.assertEqual(b"foreign.txt\n", self.capture(repo, "diff", "--cached", "--name-only"))

    def test_selected_commit_ignores_foreign_staged_deletions_for_blast_radius(self):
        with FixtureDirectory() as tmp:
            repo, message = self.selected_fixture(Path(tmp))
            names = [f"gone{i:02d}.txt" for i in range(16)]
            for name in names:
                (repo / name).write_text("x\n", encoding="utf-8")
            git(repo, "add", "--", *names)
            git(repo, "commit", "-m", "baseline2")
            git(repo, "rm", "-q", "--", *names)
            done = self.selected(repo, message, "owned.txt")
            self.assertEqual(0, done.returncode, done.stdout + done.stderr)
            payload = json.loads(done.stdout)
            self.assertEqual(payload["candidatePathCount"], 1)
            self.assertEqual(payload["candidateDeletionCount"], 0)
            staged_deletions = self.capture(repo, "diff", "--cached", "--name-only", "--diff-filter=D")
            self.assertEqual(16, len(staged_deletions.split()))

    def test_selected_commit_rejects_oversized_wanted_set(self):
        with FixtureDirectory() as tmp:
            repo, message = self.selected_fixture(Path(tmp))
            names = [f"s{i:02d}.txt" for i in range(41)]
            for name in names:
                (repo / name).write_text("x\n", encoding="utf-8")
            before = (repo / ".git/index").read_bytes()
            rejected = self.selected(repo, message, *names)
            self.assertEqual(2, rejected.returncode)
            payload = json.loads(rejected.stdout)
            self.assertEqual(payload["reason"], "blast-radius-paths")
            self.assertEqual(payload["candidatePathCount"], 41)
            self.assertEqual(before, (repo / ".git/index").read_bytes())
            self.assertFalse((repo / ".git/index.lock").exists())

    def test_selected_commit_rejects_mass_selected_deletions(self):
        with FixtureDirectory() as tmp:
            repo, message = self.selected_fixture(Path(tmp))
            names = [f"del{i:02d}.txt" for i in range(16)]
            for name in names:
                (repo / name).write_text("x\n", encoding="utf-8")
            git(repo, "add", "--", *names)
            git(repo, "commit", "-m", "baseline2")
            for name in names:
                (repo / name).unlink()
            before = (repo / ".git/index").read_bytes()
            rejected = self.selected(repo, message, *names)
            self.assertEqual(2, rejected.returncode)
            self.assertEqual(json.loads(rejected.stdout)["reason"], "blast-radius-deletions")
            self.assertEqual(before, (repo / ".git/index").read_bytes())


    def lock_repo(self, root: Path) -> tuple[Path, Path]:
        repo = self.make_repo(root)
        (repo / "owned.txt").write_text("owned\n", encoding="utf-8")
        git(repo, "add", "--", "owned.txt")
        git(repo, "commit", "-m", "fixture baseline")
        git_dir = subprocess.check_output(
            ["git", "-C", str(repo), "rev-parse", "--absolute-git-dir"], text=True).strip()
        return repo, Path(git_dir)

    def test_lock_subcommand_moves_proven_stale_empty_lock(self) -> None:
        with FixtureDirectory() as tmp:
            repo, git_dir = self.lock_repo(Path(tmp))
            lock = git_dir / "index.lock"
            lock.write_bytes(b"")
            import time
            past = time.time() - 2 * 86400
            os.utime(lock, (past, past))
            index_before = (git_dir / "index").read_bytes()
            backup_dir = Path(tmp) / "bak"
            proc = run_tool("lock", "--repo", str(repo), "--backup-dir", str(backup_dir))
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            self.assertEqual(payload["action"], "moved")
            self.assertTrue(payload["indexUnchanged"])
            self.assertFalse(lock.exists())
            backups = list(backup_dir.glob("index.lock.bak-*"))
            self.assertEqual(len(backups), 1)
            self.assertEqual(index_before, (git_dir / "index").read_bytes())

    def test_lock_subcommand_preserves_fresh_or_nonempty_lock(self) -> None:
        with FixtureDirectory() as tmp:
            repo, git_dir = self.lock_repo(Path(tmp))
            backup_dir = Path(tmp) / "bak"
            fresh = git_dir / "index.lock"
            fresh.write_bytes(b"")
            proc = run_tool("lock", "--repo", str(repo), "--backup-dir", str(backup_dir))
            self.assertEqual(json.loads(proc.stdout)["reason"], "lock-fresh")
            self.assertEqual(proc.returncode, 4)
            self.assertTrue(fresh.exists())
            fresh.write_bytes(b"foreign owner")
            proc = run_tool("lock", "--repo", str(repo), "--backup-dir", str(backup_dir))
            self.assertEqual(json.loads(proc.stdout)["reason"], "lock-nonempty")
            self.assertEqual(b"foreign owner", fresh.read_bytes())

    def test_lock_subcommand_reports_absent_lock(self) -> None:
        with FixtureDirectory() as tmp:
            repo, git_dir = self.lock_repo(Path(tmp))
            proc = run_tool("lock", "--repo", str(repo), "--backup-dir", str(Path(tmp) / "bak"))
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            self.assertEqual(payload["action"], "absent")
            self.assertEqual(payload["reason"], "no-lock")

    def test_live_canonical_repo_is_not_a_fixture(self) -> None:
        proc = run_tool("check", "--", "git", "add", "--", ".secrets/providers.json")
        self.assertEqual(json.loads(proc.stdout)["verdict"], "forbid")


if __name__ == "__main__":
    unittest.main()
