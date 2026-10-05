#!/usr/bin/env python3
"""test_agent_git_snapshot.py — black-box tests for agent_git_snapshot.py.

Every case builds a throwaway git repo in a temp dir marked
`git config --local demo1.gittest fixture` (the only non-canonical root the
conditional-local-git gate allows). The real checkout's index/refs are never
touched: the fixture repo owns its own .git.

    python -B scripts/test_agent_git_snapshot.py
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
TOOL = HERE / "agent_git_snapshot.py"
sys.path.insert(0, str(HERE))
import conditional_local_git as gate  # noqa: E402


def git(repo: Path, *args, env_extra=None) -> subprocess.CompletedProcess:
    env = dict(os.environ)
    if env_extra:
        env.update(env_extra)
    return subprocess.run([gate.git_exe(), "-C", str(repo), *args],
                          capture_output=True, check=False, env=env)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class Fixture:
    """Temp git repo with one committed text file, fixture-flagged."""

    def __init__(self):
        self.dir = Path(tempfile.mkdtemp(prefix="awx-snap-fixture-"))
        repo = self.dir
        assert git(repo, "init", "-q", "-b", "main").returncode == 0
        assert git(repo, "config", "--local", "demo1.gittest",
                   "fixture").returncode == 0
        (repo / "file.txt").write_text("alpha\nbeta\ngamma\n",
                                       encoding="utf-8")
        (repo / ".gitignore").write_text("ignored.txt\n", encoding="utf-8")
        sub = repo / "sub"
        sub.mkdir()
        (sub / "other.txt").write_text("one\ntwo\n", encoding="utf-8")
        env = {"GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t",
               "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}
        assert git(repo, "add", "file.txt", "sub/other.txt",
                   ".gitignore").returncode == 0
        assert git(repo, "commit", "-qm", "init",
                   env_extra=env).returncode == 0

    def cleanup(self):
        shutil.rmtree(self.dir, ignore_errors=True)


class SnapshotTests(unittest.TestCase):
    maxDiff = None

    def setUp(self):
        self.fx = Fixture()
        self.repo = self.fx.dir
        self.task = "t" + hashlib.sha256(
            str(self.id()).encode()).hexdigest()[:12]

    def tearDown(self):
        self.fx.cleanup()

    def run_tool(self, *argv) -> tuple[dict, int]:
        proc = subprocess.run(
            [sys.executable, "-B", str(TOOL), "--repo", str(self.repo), *argv],
            capture_output=True, check=False, timeout=120)
        try:
            return json.loads(proc.stdout.decode("utf-8", "replace")), \
                proc.returncode
        except ValueError:
            self.fail("non-JSON tool output: %r stderr=%r"
                      % (proc.stdout[:400], proc.stderr[:400]))

    def index_sha(self) -> str | None:
        return gate.index_sha256(self.repo / ".git")

    def take(self, stage="pre", *paths) -> dict:
        data, code = self.run_tool(
            "take", "--task-id", self.task, "--agent", "tester",
            "--stage", stage, *sum((["--path", p] for p in paths), []))
        self.assertEqual(0, code, data)
        self.assertEqual("snapshotted", data["outcome"], data)
        return data

    # DV1 + Acceptance 2: take leaves .git/index and the worktree untouched
    def test_take_isolated_index(self):
        before = self.index_sha()
        data = self.take("pre", "file.txt", "sub/other.txt")
        self.assertTrue(data["mainIndexUnchanged"])
        self.assertEqual(before, self.index_sha())
        self.assertEqual(["file.txt", "sub/other.txt"], data["files"])
        ref = git(self.repo, "rev-parse", "--verify", "-q", data["ref"])
        self.assertEqual(0, ref.returncode)
        self.assertEqual(data["commit"], ref.stdout.decode().strip())
        # sparse tree = exactly the declared paths
        tree = git(self.repo, "ls-tree", "-r", "--name-only",
                   data["commit"]).stdout.decode().split()
        self.assertEqual(["file.txt", "sub/other.txt"], sorted(tree))
        # HEAD itself never moved
        head = git(self.repo, "rev-parse", "HEAD").stdout.decode().strip()
        self.assertEqual(head, data["head"])

    # DV2: diff reports the exact change set against the snapshot
    def test_diff_reports_changes(self):
        snap = self.take("pre", "file.txt")
        (self.repo / "file.txt").write_text("alpha\nbeta\ngamma\ndelta\n",
                                           encoding="utf-8")
        data, code = self.run_tool("diff", "--task-id", self.task,
                                   "--agent", "tester", "--stat")
        self.assertEqual(0, code, data)
        self.assertEqual("diffed", data["outcome"])
        self.assertEqual(snap["commit"], data["commit"])
        self.assertEqual(1, data["filesChanged"])
        self.assertEqual(1, data["insertions"])
        self.assertEqual(0, data["deletions"])
        self.assertIn("file.txt", data["statText"])
        with_patch, code = self.run_tool("diff", "--task-id", self.task,
                                         "--agent", "tester", "--patch")
        self.assertEqual(0, code, with_patch)
        self.assertIn("+delta", with_patch["diffText"])

    # gitignored worktree files must diff as modified, never as deleted:
    # the snapshot temp index keeps the recorded paths tracked
    def test_diff_gitignored_file(self):
        (self.repo / "ignored.txt").write_text("v1\n", encoding="utf-8")
        self.take("pre", "ignored.txt")
        (self.repo / "ignored.txt").write_text("v1\nv2\n", encoding="utf-8")
        data, code = self.run_tool("diff", "--task-id", self.task,
                                   "--agent", "tester", "--patch")
        self.assertEqual(0, code, data)
        self.assertEqual(1, data["filesChanged"])
        self.assertEqual(1, data["insertions"])
        self.assertIn("+v2", data["diffText"])
        self.assertNotIn("deleted file mode", data["diffText"])

    # DV3 + Acceptance 3: corrupt -> restore refuses dirty -> --force byte-exact
    def test_restore_force_and_unchanged(self):
        self.take("pre", "file.txt")
        original = (self.repo / "file.txt").read_bytes()
        (self.repo / "file.txt").write_text("tampered\n", encoding="utf-8")
        data, code = self.run_tool("restore", "--task-id", self.task,
                                   "--agent", "tester")
        self.assertEqual(2, code)
        self.assertEqual("uncommitted-changes-detected", data["deferred"])
        self.assertEqual(["file.txt"], data["blockedPaths"])
        data, code = self.run_tool("restore", "--task-id", self.task,
                                   "--agent", "tester", "--force")
        self.assertEqual(0, code, data)
        self.assertEqual("restored", data["outcome"])
        self.assertEqual(original, (self.repo / "file.txt").read_bytes())
        self.assertEqual(sha256(self.repo / "file.txt"),
                         data["files"][0]["restoredSha256"])

    # restore without --force when the worktree is at another recorded stage
    def test_restore_known_stage_no_force(self):
        self.take("pre", "file.txt")
        original = (self.repo / "file.txt").read_bytes()
        (self.repo / "file.txt").write_text("alpha\nbeta\ngamma\npost\n",
                                           encoding="utf-8")
        self.take("post", "file.txt")
        data, code = self.run_tool("restore", "--task-id", self.task,
                                   "--agent", "tester", "--stage", "pre")
        self.assertEqual(0, code, data)
        self.assertEqual("restored", data["outcome"])
        self.assertEqual(original, (self.repo / "file.txt").read_bytes())

    # restore recreates a deleted snapshot path
    def test_restore_recreates_deleted(self):
        self.take("pre", "file.txt")
        original = (self.repo / "file.txt").read_bytes()
        (self.repo / "file.txt").unlink()
        data, code = self.run_tool("restore", "--task-id", self.task,
                                   "--agent", "tester")
        self.assertEqual(0, code, data)
        self.assertEqual("restored", data["outcome"])
        self.assertEqual(original, (self.repo / "file.txt").read_bytes())

    # DV4 + Acceptance 4: make-patch emits a headered diff that passes
    # patch_preflight both vs the snapshot pre-image and the live tree
    # (append-only change keeps context valid in both directions)
    def test_make_patch_preflight(self):
        self.take("pre", "file.txt")
        (self.repo / "file.txt").write_text(
            "alpha\nbeta\ngamma\ndelta appended\n", encoding="utf-8")
        patch = self.repo / "out" / "change.patch"
        data, code = self.run_tool("make-patch", "--task-id", self.task,
                                   "--agent", "tester", "--out",
                                   str(patch))
        self.assertEqual(0, code, data)
        self.assertEqual("patch-created", data["outcome"])
        text = patch.read_text(encoding="utf-8")
        self.assertTrue(text.startswith("# AWX-PATCH: task_id="))
        self.assertIn("base_commit=", text.splitlines()[0])
        self.assertIn("diff --git", text)
        self.assertIn("+delta appended", text)
        self.assertTrue(data["preflightVsPreimage"]["ok"],
                        data["preflightVsPreimage"])
        self.assertEqual(0, data["preflightVsPreimage"]["exit"])
        self.assertTrue(data["preflightVsWorktree"]["ok"],
                        data["preflightVsWorktree"])
        self.assertEqual(0, data["preflightVsWorktree"]["exit"])
        # diff --verify-patch exercises the same pre-image check for an
        # externally supplied patch file
        data, code = self.run_tool("diff", "--task-id", self.task,
                                   "--agent", "tester",
                                   "--verify-patch", str(patch))
        self.assertEqual(0, code, data)
        self.assertTrue(data["preflight"]["ok"], data["preflight"])

    # prune: fresh refs survive --days 14, --days 0 prunes everything,
    # --dry-run reports without deleting
    def test_prune(self):
        snap = self.take("pre", "file.txt")
        data, code = self.run_tool("prune", "--days", "14")
        self.assertEqual(0, code, data)
        self.assertEqual(0, data["prunedCount"])
        self.assertEqual(1, data["keptCount"])
        data, code = self.run_tool("prune", "--days", "0", "--dry-run")
        self.assertEqual(0, code, data)
        self.assertEqual(1, data["prunedCount"])
        ref = git(self.repo, "rev-parse", "--verify", "-q", snap["ref"])
        self.assertEqual(0, ref.returncode)  # dry-run kept it
        data, code = self.run_tool("prune", "--days", "0")
        self.assertEqual(0, code, data)
        self.assertEqual(1, data["prunedCount"])
        ref = git(self.repo, "rev-parse", "--verify", "-q", snap["ref"])
        self.assertNotEqual(0, ref.returncode)

    # list reflects the published refs
    def test_list(self):
        self.take("pre", "file.txt")
        self.take("post", "file.txt")
        data, code = self.run_tool("list", "--task-id", self.task)
        self.assertEqual(0, code, data)
        self.assertEqual(2, data["count"])
        self.assertEqual({"pre", "post"},
                         {row["stage"] for row in data["snapshots"]})

    # negative paths
    def test_missing_path_deferred(self):
        data, code = self.run_tool("take", "--task-id", self.task,
                                   "--agent", "tester", "--path",
                                   "nope.txt")
        self.assertEqual(2, code)
        self.assertEqual("path-missing", data["deferred"])

    def test_snapshot_not_found(self):
        data, code = self.run_tool("diff", "--task-id", "absent-task",
                                   "--agent", "tester")
        self.assertEqual(2, code)
        self.assertEqual("snapshot-not-found", data["deferred"])

    def test_path_not_in_snapshot(self):
        self.take("pre", "file.txt")
        data, code = self.run_tool("restore", "--task-id", self.task,
                                   "--agent", "tester", "--path",
                                   "sub/other.txt")
        self.assertEqual(2, code)
        self.assertEqual("path-not-in-snapshot", data["deferred"])

    def test_secret_path_refused(self):
        (self.repo / ".env").write_text("k=v\n", encoding="utf-8")
        data, code = self.run_tool("take", "--task-id", self.task,
                                   "--agent", "tester", "--path", ".env")
        self.assertEqual(2, code)
        self.assertEqual("forbidden-path", data["deferred"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
