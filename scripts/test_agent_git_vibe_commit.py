"""Fixture tests for agent_git_vibe_commit.py. The live repo is not mutated."""
from __future__ import annotations

import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
from functools import partial
from unittest import mock

# Windows may hold a finished fixture directory briefly; functional assertions still fail normally.
FixtureDirectory = partial(tempfile.TemporaryDirectory, ignore_cleanup_errors=True)
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "agent_git_vibe_commit.py"
JOURNAL = ROOT / "scripts" / "work_journal.py"
sys.path.insert(0, str(ROOT / "scripts"))
import agent_git_vibe_commit as vibe  # noqa: E402
import conditional_local_git as gate  # noqa: E402


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

    def test_dry_run_marks_self_orphaned_marker_lock_would_clear(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            lock = git_dir(repo) / "index.lock"
            lock.write_bytes(b"conditional-local-git:" + b"a" * 32)
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message), "--dry-run")
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            self.assertEqual(payload["outcome"], "plan")
            self.assertIsNone(payload["deferred"])
            self.assertEqual(payload["plan"]["lock"]["action"], "would-clear")
            self.assertEqual(payload["plan"]["lock"]["reason"],
                             "self-orphaned-marker-lock")
            self.assertTrue(lock.exists())  # dry-run은 절대 이동하지 않는다

    def test_self_orphaned_marker_lock_reclaimed_then_commit(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            lock = git_dir(repo) / "index.lock"
            lock.write_bytes(b"conditional-local-git:" + b"b" * 32)
            backup = Path(tmp) / "lockbak"
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message),
                            "--backup-dir", str(backup))
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            self.assertEqual(payload["outcome"], "committed")
            self.assertEqual(payload["lock"]["action"], "moved")
            self.assertEqual(payload["lock"]["reason"],
                             "self-orphaned-marker-lock-archived")
            self.assertFalse(lock.exists())
            self.assertEqual(1, len(list(backup.glob("index.lock.bak-*"))))

    def test_foreign_nonempty_lock_still_preserved(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            lock = git_dir(repo) / "index.lock"
            lock.write_bytes(b"another tool owns this")
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message), "--dry-run")
            payload = json.loads(proc.stdout)
            self.assertEqual(payload["plan"]["lock"]["action"], "preserved")
            self.assertEqual(payload["plan"]["lock"]["reason"], "lock-nonempty")
            self.assertEqual(payload["deferred"], "index-lock")
            self.assertEqual(lock.read_bytes(), b"another tool owns this")

    def test_forbidden_junk_path_defers_before_any_git_mutation(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            bad = "scripts/__pycache__/x.cpython-311.pyc"
            proc = run_tool("--repo", str(repo), "--path", bad,
                            "--message-file", str(message))
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 2, proc.stdout)
            self.assertEqual(payload["outcome"], "deferred")
            self.assertEqual(payload["deferred"], "forbidden-path")
            self.assertEqual(payload["badPath"], bad)
            self.assertEqual(b"", git_out(repo, "diff", "--cached", "--name-only"))

    def test_dry_run_reports_blast_capacity_before_scan(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            args = ["--repo", str(repo), "--message-file", str(message), "--dry-run"]
            for i in range(41):
                args += ["--path", f"p{i:02d}.txt"]
            proc = run_tool(*args)
            payload = json.loads(proc.stdout)
            self.assertEqual(payload["outcome"], "plan")
            self.assertEqual(payload["deferred"], "blast-radius-paths")
            plan = payload["plan"]
            self.assertEqual(plan["candidatePathCount"], 41)
            self.assertEqual(plan["maxPaths"], 40)
            self.assertEqual(plan["remainingPathCapacity"], 0)

    def test_stale_lock_ttl_is_single_ssot(self):
        self.assertEqual(vibe.DEFAULT_STALE_LOCK_DAYS, gate.DEFAULT_STALE_LOCK_DAYS)
        low = gate.build_parser().parse_args(["lock", "--repo", "x"])
        self.assertEqual(low.days, gate.DEFAULT_STALE_LOCK_DAYS)
        high = vibe.build_parser().parse_args(
            ["--repo", "x", "--path", "a", "--message-file", "m"])
        self.assertEqual(high.stale_lock_days, gate.DEFAULT_STALE_LOCK_DAYS)

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

    def test_extra_or_mismatched_remote_defers_every_mode(self):
        for name, url, reason in (
            ("origin", "https://github.com/UnlimitedAbandonWare/OldRepo", "origin-mismatch"),
            ("backup", "https://github.com/UnlimitedAbandonWare/OldRepo", "forbidden-remote"),
            ("origin", "https://example.com/other/repo.git", "origin-mismatch"),
        ):
            with self.subTest(name=name, url=url), FixtureDirectory() as tmp:
                repo, message = self.owned_fixture(Path(tmp))
                if name != "origin":
                    git(repo, "remote", "add", "origin",
                        "https://github.com/UnlimitedAbandonWare/AbandonWareAi")
                git(repo, "remote", "add", name, url)
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

    def test_task_id_appends_task_id_trailer_to_commit(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message),
                            "--task-id", "vibe-task-0927")
            payload = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            self.assertEqual(payload["outcome"], "committed")
            self.assertEqual(payload["taskId"], "vibe-task-0927")
            body = git_out(repo, "log", "-1", "--pretty=%B").decode()
            self.assertIn("Reason: vibe fixture", body)
            self.assertIn("Task-Id: vibe-task-0927", body)

    def test_task_id_dry_run_shows_trailer_intent(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message),
                            "--task-id", "dry-run-task", "--dry-run")
            payload = json.loads(proc.stdout)
            self.assertEqual(payload["outcome"], "plan")
            self.assertEqual(payload["plan"]["taskTrailer"],
                             "Task-Id: dry-run-task")
            steps = [s["step"] for s in payload["plan"]["steps"]]
            self.assertIn("message-trailer", steps)
            self.assertNotIn("doctor", payload)  # dry-run은 doctor 미호출

    def test_commit_without_task_id_leaves_message_untouched(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message))
            payload = json.loads(proc.stdout)
            self.assertEqual(payload["outcome"], "committed")
            self.assertIsNone(payload["taskId"])
            body = git_out(repo, "log", "-1", "--pretty=%B").decode()
            self.assertNotIn("Task-Id:", body)

    def test_invalid_or_secret_like_task_id_defers_without_mutation(self):
        for bad in ("../escape", "white space", "sk-" + "A" * 40, "x" * 81):
            with self.subTest(kind=bad[:12]), FixtureDirectory() as tmp:
                repo, message = self.owned_fixture(Path(tmp))
                index_before = (git_dir(repo) / "index").read_bytes()
                proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                                "--message-file", str(message),
                                "--task-id", bad)
                payload = json.loads(proc.stdout)
                self.assertEqual(proc.returncode, 2, proc.stdout)
                self.assertEqual(payload["deferred"], "invalid-task-id")
                self.assertNotIn(bad, proc.stdout)
                self.assertEqual(index_before,
                                 (git_dir(repo) / "index").read_bytes())

    def test_deferred_index_lock_carries_doctor_summary(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            lock = git_dir(repo) / "index.lock"
            lock.write_bytes(b"another tool owns this")
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message))
            payload = json.loads(proc.stdout)
            self.assertEqual(payload["outcome"], "deferred")
            self.assertEqual(payload["deferred"], "index-lock")
            self.assertIn("doctor", payload)
            self.assertIn("ok", payload["doctor"])
            if payload["doctor"]["ok"]:
                self.assertIn("decision", payload["doctor"])
                self.assertIn("issueCount", payload["doctor"])
            self.assertNotIn("publishReview", payload)
            self.assertEqual(b"another tool owns this", lock.read_bytes())

    def test_committed_payload_has_no_doctor_attach(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            proc = run_tool("--repo", str(repo), "--path", "owned.txt",
                            "--message-file", str(message))
            payload = json.loads(proc.stdout)
            self.assertEqual(payload["outcome"], "committed")
            self.assertNotIn("doctor", payload)
            self.assertNotIn("publishReview", payload)

    def test_doctor_soft_fail_preserves_original_deferred(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            lock = git_dir(repo) / "index.lock"
            lock.write_bytes(b"foreign owner")
            buf = io.StringIO()
            with mock.patch.object(vibe, "_tool_json",
                                   return_value=(None, None)), \
                    contextlib.redirect_stdout(buf):
                code = vibe.main(["--repo", str(repo), "--path", "owned.txt",
                                  "--message-file", str(message)])
            payload = json.loads(buf.getvalue())
            self.assertEqual(code, 4)
            self.assertEqual(payload["outcome"], "deferred")
            self.assertEqual(payload["deferred"], "index-lock")
            self.assertFalse(payload["doctor"]["ok"])
            self.assertEqual(payload["doctor"]["reason"], "doctor-unavailable")
            self.assertEqual(b"foreign owner", lock.read_bytes())

    def test_attach_publish_review_only_when_flagged(self):
        with FixtureDirectory() as tmp:
            repo, message = self.owned_fixture(Path(tmp))
            lock = git_dir(repo) / "index.lock"
            lock.write_bytes(b"another tool owns this")
            buf = io.StringIO()
            with mock.patch.object(
                    vibe, "_tool_json",
                    return_value=({"verdict": "BLOCKED",
                                   "reasons": ["fixture-reason"]}, 1)) as tool, \
                    contextlib.redirect_stdout(buf):
                code = vibe.main(["--repo", str(repo), "--path", "owned.txt",
                                  "--message-file", str(message),
                                  "--attach-publish-review"])
            payload = json.loads(buf.getvalue())
            self.assertEqual(code, 4)
            self.assertEqual(payload["deferred"], "index-lock")
            self.assertEqual(payload["publishReview"]["verdict"], "BLOCKED")
            self.assertEqual(payload["publishReview"]["reasons"],
                             ["fixture-reason"])
            names = [call.args[0] for call in tool.call_args_list]
            self.assertEqual(names, ["git_doctor.py", "git_publish_review.py"])


if __name__ == "__main__":
    unittest.main()
