"""Contract tests for scripts/codex_assist_guard.py (pre/post delegation tree diff).

Fixtures build a throwaway git repo in a tempdir; ledger artifacts live inside it.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
GUARD = SCRIPTS / "codex_assist_guard.py"

GIT = (os.environ.get("GIT_EXE") or shutil.which("git")
       or (r"F:\git\cmd\git.exe" if Path(r"F:\git\cmd\git.exe").exists() else "git"))


def run_guard(*args, env=None):
    merged = dict(os.environ, GIT_EXE=GIT)
    if env:
        merged.update(env)
    return subprocess.run([sys.executable, "-B", str(GUARD), *args],
                          capture_output=True, text=True, env=merged)


def git(repo, *args):
    return subprocess.run([GIT, *args], cwd=repo, capture_output=True, text=True)


class GuardRepoCase(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.repo = Path(self.td.name)
        git(self.repo, "init", "-q")
        git(self.repo, "config", "user.email", "t@example.invalid")
        git(self.repo, "config", "user.name", "t")
        (self.repo / "src").mkdir()
        (self.repo / "src" / "keep.py").write_text("print('keep')\n", encoding="utf-8")
        (self.repo / "other.txt").write_text("other\n", encoding="utf-8")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-qm", "init")
        self.ledger = self.repo / "data" / "agent-handoff" / "task-x"
        self.ledger.mkdir(parents=True)
        self.packet = self.ledger / "packet.json"
        self.packet.write_text(json.dumps({
            "role": "assist_browser_verifier",
            "allowedWritePaths": ["data/agent-handoff/task-x/browser"],
        }), encoding="utf-8")

    def tearDown(self):
        self.td.cleanup()

    def snapshot(self):
        return run_guard("snapshot", "--ledger", str(self.ledger), "--root", str(self.repo))

    def verify(self):
        return run_guard("verify", "--ledger", str(self.ledger),
                         "--packet", str(self.packet), "--root", str(self.repo))

    def test_no_change_pass(self):
        # DV2-8: snapshot -> verify with no tree change -> PASS.
        self.assertEqual(self.snapshot().returncode, 0)
        proc = self.verify()
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(json.loads(proc.stdout)["verdict"], "PASS")

    def test_new_artifact_inside_allowed_pass(self):
        # DV2-9: a new file only inside allowedWritePaths -> PASS.
        self.snapshot()
        out = self.ledger / "browser"
        out.mkdir()
        (out / "shot.png").write_bytes(b"\x89PNG")
        proc = self.verify()
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        body = json.loads(proc.stdout)
        self.assertEqual(body["verdict"], "PASS")
        # porcelain reports an untracked dir collapsed as "<dir>/" — the entry is
        # the browser dir, classified under allowedWritePaths.
        self.assertTrue(any("browser" in p for p in body["allowedChanged"]))

    def test_source_change_fail(self):
        # DV2-10: a tracked source file modified after snapshot -> FAIL naming the path.
        self.snapshot()
        (self.repo / "src" / "keep.py").write_text("print('changed')\n", encoding="utf-8")
        proc = self.verify()
        self.assertEqual(proc.returncode, 6)
        body = json.loads(proc.stdout)
        self.assertEqual(body["verdict"], "FAIL")
        self.assertTrue(any("keep.py" in v for v in body["violations"]))

    def test_predirty_excluded(self):
        # DV2-11: file already dirty at snapshot and unchanged since -> not a violation.
        (self.repo / "src" / "keep.py").write_text("print('dirty already')\n", encoding="utf-8")
        self.snapshot()
        proc = self.verify()
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(json.loads(proc.stdout)["verdict"], "PASS")

    def test_foreign_lease_marked_not_violation(self):
        # DV2-12: changed path covered by another session's lease -> FOREIGN, still PASS.
        lock = self.repo / "__patch_drop__" / "source-edit-locks" / "other-session.lock"
        lock.mkdir(parents=True)
        (lock / "lease.json").write_text(json.dumps({
            "topic": "other-session", "status": "active",
            "targetPaths": ["other.txt"],
            "expiresAtUtc": "2999-01-01T00:00:00+00:00",
        }), encoding="utf-8")
        self.snapshot()
        (self.repo / "other.txt").write_text("changed by other session\n", encoding="utf-8")
        proc = self.verify()
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        body = json.loads(proc.stdout)
        self.assertEqual(body["verdict"], "PASS")
        self.assertTrue(any("other.txt" in f["path"] for f in body["foreign"]))


if __name__ == "__main__":
    unittest.main()
