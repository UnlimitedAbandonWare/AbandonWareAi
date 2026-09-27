"""Fixture-repo tests for __patch_drop__/producer_bundle.py.

Every case builds a disposable repo + patchdrop dir under tempdir; the
operational repository is never touched.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PRODUCER = ROOT / "__patch_drop__" / "producer_bundle.py"
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
    (path / "a.txt").write_text("seed\n", encoding="utf-8")
    git(path, "add", "a.txt")
    assert git(path, "commit", "-m", "init").returncode == 0
    return path


def run_producer(repo: Path, drop: Path, *extra: str) -> dict:
    proc = subprocess.run(
        [sys.executable, "-B", str(PRODUCER),
         "--topic", "fixture-topic", "--node", "desktop",
         "--source-root", str(repo), "--patchdrop-root", str(drop),
         *extra],
        capture_output=True, text=True, timeout=120)
    manifests = list(drop.glob("desktop/*.manifest.json"))
    return {"code": proc.returncode, "stdout": proc.stdout,
            "stderr": proc.stderr, "manifests": manifests}


def load_manifest(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


@unittest.skipUnless(shutil.which("git"), "git not on PATH")
class ProducerBundleTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="producer-bundle-test-"))
        self.repo = init_repo(self.tmp / "repo")
        self.drop = self.tmp / "drop"

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_worktree_mode_emits_patch_and_evidence(self):
        (self.repo / "a.txt").write_text("changed\n", encoding="utf-8")
        res = run_producer(self.repo, self.drop, "--pathspec", "a.txt")
        self.assertEqual(res["code"], 0, res["stdout"] + res["stderr"])
        manifest = load_manifest(res["manifests"][0])
        self.assertEqual(manifest["snapshotMode"], "worktree")
        self.assertFalse(manifest["emptyPatch"])
        entry = next(f for f in manifest["files"] if f["path"] == "a.txt")
        self.assertTrue(entry["headOid"])
        self.assertTrue(entry["worktreeSha256"])
        patch = res["manifests"][0].with_suffix("").parent / manifest["activePatch"]
        self.assertTrue(patch.exists())
        self.assertIn("changed", patch.read_text(encoding="utf-8"))

    def test_staged_mode_excludes_later_unstaged_edit(self):
        (self.repo / "a.txt").write_text("staged-version\n", encoding="utf-8")
        git(self.repo, "add", "a.txt")
        (self.repo / "a.txt").write_text("newer-unstaged\n", encoding="utf-8")
        res = run_producer(self.repo, self.drop, "--pathspec", "a.txt",
                           "--mode", "staged")
        self.assertEqual(res["code"], 0, res["stdout"] + res["stderr"])
        manifest = load_manifest(res["manifests"][0])
        self.assertEqual(manifest["snapshotMode"], "staged")
        self.assertIn("a.txt", manifest["partiallyStagedPaths"])
        patch = res["manifests"][0].parent / manifest["activePatch"]
        text = patch.read_text(encoding="utf-8")
        self.assertIn("staged-version", text)
        self.assertNotIn("newer-unstaged", text)

    def test_secret_dir_pathspec_blocked(self):
        secret_dir = self.repo / ".secrets"
        secret_dir.mkdir()
        (secret_dir / "k.txt").write_text("x\n", encoding="utf-8")
        res = run_producer(self.repo, self.drop, "--pathspec", ".secrets/k.txt")
        self.assertEqual(res["code"], 1)
        self.assertIn("forbidden-path", res["stdout"])
        self.assertEqual(res["manifests"], [], "no bundle must be written")

    def test_new_plain_file_allowed_not_filemode_blocked(self):
        (self.repo / "SKILL.md").write_text("# new\n", encoding="utf-8")
        res = run_producer(self.repo, self.drop, "--pathspec", "SKILL.md")
        self.assertEqual(res["code"], 0, res["stdout"] + res["stderr"])
        manifest = load_manifest(res["manifests"][0])
        verify = res["manifests"][0].parent / (
            manifest["activePatch"].replace(".patch", ".verify.log"))
        body = verify.read_text(encoding="utf-8")
        self.assertIn("allowedNewFileCount=1", body)
        self.assertIn("filemodeViolationCount=0", body)

    def test_second_run_same_topic_does_not_overwrite(self):
        (self.repo / "a.txt").write_text("v2\n", encoding="utf-8")
        first = run_producer(self.repo, self.drop, "--pathspec", "a.txt")
        self.assertEqual(first["code"], 0)
        (self.repo / "a.txt").write_text("v3\n", encoding="utf-8")
        second = run_producer(self.repo, self.drop, "--pathspec", "a.txt")
        self.assertEqual(second["code"], 0, second["stdout"])
        names = sorted(p.name for p in self.drop.glob("desktop/*.patch"))
        self.assertEqual(len(names), 2)
        self.assertTrue(any("-r2" in n for n in names))

    def test_preimage_mode_records_hashes_without_patch(self):
        (self.repo / "a.txt").write_text("v2\n", encoding="utf-8")
        res = run_producer(self.repo, self.drop, "--pathspec", "a.txt",
                           "--mode", "preimage")
        self.assertEqual(res["code"], 0, res["stdout"] + res["stderr"])
        manifest = load_manifest(res["manifests"][0])
        self.assertEqual(manifest["snapshotMode"], "preimage")
        self.assertTrue(manifest["emptyPatch"])
        entry = next(f for f in manifest["files"] if f["path"] == "a.txt")
        self.assertTrue(entry["headOid"])
        self.assertTrue(entry["worktreeSha256"])
        patch = res["manifests"][0].parent / manifest["activePatch"]
        self.assertEqual(patch.read_bytes(), b"")

    def test_unborn_head_reports_git_failure_not_empty(self):
        empty_repo = self.tmp / "unborn"
        empty_repo.mkdir()
        subprocess.run(["git", "init", str(empty_repo)], check=True,
                       capture_output=True)
        res = run_producer(empty_repo, self.tmp / "drop2",
                           "--pathspec", "a.txt")
        self.assertEqual(res["code"], 1)
        self.assertIn("producer-git-command-failed", res["stdout"])
        self.assertIn("exit=", res["stdout"],
                      "exit code must be preserved in the failure detail")

    def test_non_git_root_fails_isolation(self):
        plain = self.tmp / "plain"
        plain.mkdir()
        res = run_producer(plain, self.tmp / "drop3", "--pathspec", "a.txt")
        self.assertEqual(res["code"], 1)
        self.assertIn("source-isolation-violation", res["stdout"])

    def test_secret_pattern_in_patch_content_blocked(self):
        (self.repo / "leak.txt").write_text(f"k={FAKE_AWS_KEY}\n",
                                            encoding="utf-8")
        res = run_producer(self.repo, self.drop, "--pathspec", "leak.txt")
        self.assertEqual(res["code"], 1)
        self.assertNotIn(FAKE_AWS_KEY, res["stdout"] + res["stderr"])


if __name__ == "__main__":
    unittest.main()
