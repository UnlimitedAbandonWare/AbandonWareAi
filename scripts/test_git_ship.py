#!/usr/bin/env python3
"""test_git_ship.py -- sandbox tests for scripts/git_ship.py.

Runs in temp dirs only: git init + a local bare origin, no network, no hooks
(sandbox repos get default hooksPath -- never the real repo's .githooks).
Run: python -B scripts/test_git_ship.py -v  (this file only; never a suite)
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
SHIP = SCRIPTS / "git_ship.py"
GIT = os.environ.get("AWX_GIT_EXE") or shutil.which("git") or r"F:\git\cmd\git.exe"

BASE_ENV = {
    "GIT_TERMINAL_PROMPT": "0",
    "AWX_GIT_EXE": GIT,
    "PYTHONDONTWRITEBYTECODE": "1",
}
BLOCKED_ENV = {"AWX_PUBLISH_APPROVED", "AWX_SHIP_SKIP_GUARD"}


def git(repo, *args, check=True):
    proc = subprocess.run(
        [GIT, "--no-optional-locks", "-C", str(repo)] + list(args),
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and proc.returncode != 0:
        raise AssertionError(f"git {' '.join(args)} -> {proc.returncode}: {proc.stderr}")
    return proc


class ShipCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="gitship-"))
        self.repo = self.tmp / "repo"
        self.origin = self.tmp / "origin.git"
        self.repo.mkdir()
        git(self.repo, "init", "-b", "main")
        git(self.repo, "config", "user.email", "t@example.invalid")
        git(self.repo, "config", "user.name", "sandbox")
        self.origin.mkdir()
        git(self.origin, "init", "--bare", "-b", "main")
        git(self.repo, "remote", "add", "origin", str(self.origin))
        (self.repo / "README.md").write_text("hello\n", encoding="utf-8")
        git(self.repo, "add", "README.md")
        git(self.repo, "commit", "-m", "init")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    # -- helpers ---------------------------------------------------------
    def ship(self, *args, env=None):
        e = dict(os.environ)
        e.update(BASE_ENV)
        for k in BLOCKED_ENV:
            e.pop(k, None)
        if env:
            e.update(env)
        return subprocess.run(
            [sys.executable, "-B", str(SHIP), "--root", str(self.repo)] + list(args),
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            env=e)

    def w(self, rel, text):
        p = self.repo / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
        return p

    def head(self):
        return git(self.repo, "rev-parse", "HEAD").stdout.strip()

    def staged(self):
        out = git(self.repo, "diff", "--cached", "--name-status").stdout
        return {c[1]: c[0] for ln in out.splitlines() if (c := ln.split("\t")) and len(c) >= 2}

    def write_tree(self):
        return git(self.repo, "write-tree").stdout.strip()

    def remote_sha(self, branch):
        proc = git(self.repo, "ls-remote", "origin", f"refs/heads/{branch}", check=False)
        for ln in proc.stdout.splitlines():
            cols = ln.split()
            if len(cols) == 2:
                return cols[0]
        return None

    def make_feature(self):
        git(self.repo, "checkout", "-b", "ship/test")
        self.w("feat.txt", "work\n")
        git(self.repo, "add", "feat.txt")
        git(self.repo, "commit", "-m", "feat")


# ----------------------------------------------------------------------
# T1 word-boundary: "task-..." must classify word, never real
# ----------------------------------------------------------------------

class T1WordBoundary(ShipCase):
    def test_task_token_is_word(self):
        self.w("notes.txt", "see task-query-rewrite-safe-longer-token-pad\n")
        git(self.repo, "add", "notes.txt")
        r = self.ship("scan", "--staged", "--json")
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertIn('"word": 1', r.stdout)
        self.assertIn('"real": 0', r.stdout)


# ----------------------------------------------------------------------
# T2 fake marker + test path -> fake
# ----------------------------------------------------------------------

class T2FakeClass(ShipCase):
    def test_fake_key_in_test_file(self):
        self.w("scripts/test_x.py",
               "KEY = '" + "sk-" + "FAKEKEY1234567890abcd'\n")
        git(self.repo, "add", "scripts/test_x.py")
        r = self.ship("scan", "--staged", "--json")
        self.assertEqual(r.returncode, 0)
        self.assertIn('"fake": 1', r.stdout)
        self.assertIn('"real": 0', r.stdout)


# ----------------------------------------------------------------------
# T3 real-looking key -> real, exit 2, commit/push refused
# ----------------------------------------------------------------------

class T3RealBlocks(ShipCase):
    KEY = "sk-" + "Qm7vX2pL9wK4tR8zN5bH3jF6"  # synthetic shape, no marker words

    def test_real_blocks_commit_and_push(self):
        self.w("src/app.py", f"KEY = '{self.KEY}'\n")
        git(self.repo, "add", "src/app.py")
        r = self.ship("scan", "--staged")
        self.assertEqual(r.returncode, 2, r.stdout)
        r = self.ship("commit", "--message", "x", "--apply")
        self.assertEqual(r.returncode, 2, r.stdout)
        before = self.head()
        git(self.repo, "commit", "-m", "bad")  # history now carries the key
        git(self.repo, "checkout", "-b", "ship/leak")
        r = self.ship("push", "--apply",
                      env={"AWX_PUBLISH_APPROVED": "1"})
        self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
        self.assertIsNone(self.remote_sha("ship/leak"))
        self.assertNotEqual(before, "")


# ----------------------------------------------------------------------
# T4 persistent index.lock -> 10 waits -> exit 3, lock untouched
# ----------------------------------------------------------------------

class T4LockTimeout(ShipCase):
    def test_lock_waits_then_exit3(self):
        self.w("a.txt", "a\n")
        git(self.repo, "add", "a.txt")
        lock = self.repo / ".git" / "index.lock"
        lock.write_text("held")
        r = self.ship("commit", "--message", "x", "--apply",
                      "--lock-wait", "0.05")
        self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
        self.assertTrue(lock.is_file(), "lock must never be deleted")


# ----------------------------------------------------------------------
# T5 lock released mid-wait -> next attempt commits
# ----------------------------------------------------------------------

class T5LockReleased(ShipCase):
    def test_unlock_allows_commit(self):
        self.w("b.txt", "b\n")
        git(self.repo, "add", "b.txt")
        lock = self.repo / ".git" / "index.lock"
        lock.write_text("held")

        def release():
            time.sleep(0.3)
            lock.unlink()

        t = threading.Thread(target=release, daemon=True)
        t.start()
        before = self.head()
        r = self.ship("commit", "--message", "x", "--apply",
                      "--lock-wait", "0.1")
        t.join(timeout=5)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertNotEqual(self.head(), before)
        rec = self.repo / "var" / "codex-assist-git-ship" / "last-commit.json"
        self.assertTrue(rec.is_file())


# ----------------------------------------------------------------------
# T6 junk --apply: unstages junk only, keeps work files + staged deletion
# ----------------------------------------------------------------------

class T6JunkApply(ShipCase):
    def test_junk_unstage_only(self):
        self.w(".gitignore", "*.log\n")
        self.w("__pycache__/a.pyc", "x")
        self.w("_tmp_out.txt", "x")
        self.w("app/quarantine/resources/application-prod.yml", "k: v\n")
        big = self.w("big.bin", "z" * 500)
        self.w("app.log", "x")
        git(self.repo, "add", "-f", "app.log")
        self.w("normal.txt", "n")
        git(self.repo, "add", ".")
        git(self.repo, "rm", "--cached", "-r", "__pycache__", check=False)
        git(self.repo, "add", "-f", "__pycache__/a.pyc", "big.bin")
        (self.repo / "README.md").unlink()
        git(self.repo, "add", "README.md")  # staged deletion
        r = self.ship("junk", "--apply", "--max-mb", "0.0001")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        staged = self.staged()
        for gone in ("__pycache__/a.pyc", "_tmp_out.txt",
                     "app/quarantine/resources/application-prod.yml",
                     "big.bin", "app.log"):
            self.assertNotIn(gone, staged, gone)
        self.assertIn("normal.txt", staged)
        self.assertEqual(staged.get("README.md"), "D",
                         "staged deletion must stay staged")
        for keep in ("__pycache__/a.pyc", "_tmp_out.txt", "app.log",
                     "big.bin",
                     "app/quarantine/resources/application-prod.yml"):
            self.assertTrue((self.repo / keep).exists(), keep)


# ----------------------------------------------------------------------
# T7 --skip-guard needs env + reason + clean scan, else exit 4
# ----------------------------------------------------------------------

class T7SkipGuardGate(ShipCase):
    def setUp(self):
        super().setUp()
        self.w("c.txt", "c\n")
        git(self.repo, "add", "c.txt")

    def test_missing_env_and_reason(self):
        r = self.ship("commit", "--message", "x", "--apply", "--skip-guard")
        self.assertEqual(r.returncode, 4, r.stdout + r.stderr)

    def test_env_without_reason(self):
        r = self.ship("commit", "--message", "x", "--apply", "--skip-guard",
                      env={"AWX_SHIP_SKIP_GUARD": "1"})
        self.assertEqual(r.returncode, 4, r.stdout + r.stderr)

    def test_reason_without_env(self):
        r = self.ship("commit", "--message", "x", "--apply", "--skip-guard",
                      "--reason", "user approved verbally")
        self.assertEqual(r.returncode, 4, r.stdout + r.stderr)


# ----------------------------------------------------------------------
# T8 push refuses main and force forms
# ----------------------------------------------------------------------

class T8PushRefusals(ShipCase):
    def test_main_refused(self):
        r = self.ship("push", "--apply", env={"AWX_PUBLISH_APPROVED": "1"})
        self.assertEqual(r.returncode, 4, r.stdout + r.stderr)
        self.assertIsNone(self.remote_sha("main"))

    def test_force_flag_refused(self):
        self.make_feature()
        r = self.ship("push", "--apply", "--force",
                      env={"AWX_PUBLISH_APPROVED": "1"})
        self.assertNotEqual(r.returncode, 0)


# ----------------------------------------------------------------------
# T9 push needs AWX_PUBLISH_APPROVED; with it -> pushed + verify match
# ----------------------------------------------------------------------

class T9PushApproval(ShipCase):
    def test_push_gate_and_success(self):
        self.make_feature()
        r = self.ship("push", "--apply")
        self.assertEqual(r.returncode, 4, r.stdout + r.stderr)
        self.assertIsNone(self.remote_sha("ship/test"))
        r = self.ship("push", "--apply", env={"AWX_PUBLISH_APPROVED": "1"})
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(self.remote_sha("ship/test"), self.head())
        r = self.ship("verify")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


# ----------------------------------------------------------------------
# T10 ship default is dry-run: HEAD, index, remote all unchanged
# ----------------------------------------------------------------------

class T10ShipDryRun(ShipCase):
    def test_ship_dry_run_changes_nothing(self):
        self.w("d.txt", "d\n")
        git(self.repo, "add", "d.txt")
        head0, tree0 = self.head(), self.write_tree()
        remote0 = self.remote_sha("main")
        r = self.ship("ship")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        first = r.stdout.splitlines()[0] if r.stdout.splitlines() else ""
        self.assertTrue(first.startswith("DRY-RUN"), first)
        self.assertEqual(self.head(), head0)
        self.assertEqual(self.write_tree(), tree0)
        self.assertEqual(self.remote_sha("main"), remote0)


# ----------------------------------------------------------------------
# T11 output never leaks a full key value
# ----------------------------------------------------------------------

class T11NoKeyLeak(ShipCase):
    KEY = "sk-" + "Qm7vX2pL9wK4tR8zN5bH3jF6"

    def test_output_masks_value(self):
        self.w("src/app.py", f"KEY = '{self.KEY}'\n")
        git(self.repo, "add", "src/app.py")
        for args in (("scan", "--staged"), ("scan", "--staged", "--json"),
                     ("commit", "--message", "x", "--apply")):
            r = self.ship(*args)
            blob = r.stdout + r.stderr
            self.assertNotIn(self.KEY, blob, args)
            self.assertNotIn(self.KEY[6:], blob, args)
        self.assertIn("sk-Qm7", self.ship("scan", "--staged").stdout)


# ----------------------------------------------------------------------
# T12 range base order: @{u} -> config remote/workBranch -> origin/HEAD only
# when it shares history with HEAD -> HEAD --not --remotes=<r>. An unrelated
# base (e.g. a detached-history snapshot branch) is never picked.
# ----------------------------------------------------------------------

class T12RangeResolution(ShipCase):
    def test_upstream_is_the_base(self):
        self.make_feature()
        git(self.repo, "push", "-u", "origin", "ship/test")
        self.w("more.txt", "more\n")
        git(self.repo, "add", "more.txt")
        git(self.repo, "commit", "-m", "more")
        r = self.ship("scan", "--json")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual("origin/ship/test", json.loads(r.stdout)["scope"])

    def test_config_work_branch_when_no_upstream(self):
        self.make_feature()
        git(self.repo, "push", "origin", "ship/test")  # no -u: no upstream
        cfg = self.repo / "configs" / "git-branch-context.json"
        cfg.parent.mkdir()
        cfg.write_text('{"workBranch":"ship/test","remote":"origin"}',
                       encoding="utf-8")
        r = self.ship("scan", "--json")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual("origin/ship/test", json.loads(r.stdout)["scope"])

    def test_unrelated_origin_head_is_skipped(self):
        self.make_feature()
        git(self.repo, "push", "origin", "ship/test")  # no upstream
        git(self.repo, "checkout", "--orphan", "snap")
        git(self.repo, "commit", "--allow-empty", "-m", "snapshot")
        git(self.repo, "push", "origin", "snap")
        git(self.repo, "remote", "set-head", "origin", "snap")  # sandbox repo
        git(self.repo, "checkout", "ship/test")
        r = self.ship("scan", "--json")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        scope = json.loads(r.stdout)["scope"]
        self.assertIn("HEAD-not-on-origin", scope)
        self.assertIn("unrelated", scope)
        self.assertNotIn("origin/snap..HEAD", scope)


# ----------------------------------------------------------------------
# T13 junk rules: $null / *.lnk / *.url / Thumbs.db / desktop.ini never reach
# candidates -- the guard's binary-scan-unavailable is dodged by exclusion
# ----------------------------------------------------------------------

class T13JunkNewRules(ShipCase):
    def test_devnull_and_windows_junk_flagged(self):
        for name in ("$null", "x.lnk", "x.url", "Thumbs.db", "desktop.ini"):
            self.w(name, "junk\n")
        self.w("keep.txt", "k\n")
        git(self.repo, "add", "-A")
        r = self.ship("junk", "--json")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        found = {f["path"]: f["rule"]
                 for f in json.loads(r.stdout)["findings"]}
        for name in ("$null", "x.lnk", "x.url", "Thumbs.db", "desktop.ini"):
            self.assertIn(name, found, name)
        self.assertNotIn("keep.txt", found)
        self.assertEqual(found["$null"], "dev-null-name")
        self.assertEqual(found["x.lnk"], "windows-shell-junk")


# ----------------------------------------------------------------------
# T14 preview runs the real staged guard: a fake key in a test-path file
# passes run_scan as fake but the hook guard still blocks -- preview must
# fail with exit 6 and never reach `git commit`
# ----------------------------------------------------------------------

class T14HookBlockedPreview(ShipCase):
    KEY = "sk-" + "FAKEKEY1234567890abcd"  # guard trips, run_scan calls fake

    def stage_fake(self):
        self.w("scripts/test_hook.py", f'KEY = "{self.KEY}"\n')
        git(self.repo, "add", "scripts/test_hook.py")

    def test_preview_and_apply_stop_before_commit(self):
        self.stage_fake()
        before = self.head()
        for args in (("commit", "--message", "x"),
                     ("commit", "--message", "x", "--apply"),
                     ("commit", "--message", "x", "--json")):
            r = self.ship(*args)
            self.assertEqual(r.returncode, 6, str(args) + r.stdout + r.stderr)
            self.assertEqual(self.head(), before, args)
        out = self.ship("commit", "--message", "x", "--json").stdout
        self.assertIn('"exit": 6', out)

    def test_clean_stage_still_commits(self):
        self.w("ok.txt", "ok\n")
        git(self.repo, "add", "ok.txt")
        r = self.ship("commit", "--message", "x", "--apply")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertNotEqual(self.head(), "")



class SelectedCommitCase(ShipCase):
    def test_selected_commit_scans_worktree_and_preserves_foreign_hook_index(self):
        from scripts import git_ship as gs, git_ship_easy as ge
        g=gs.Git(str(self.repo),GIT)
        self.w('gone.txt','old\n'); self.w('[literal] $ x.txt','old\n')
        git(self.repo,'add','gone.txt','[literal] $ x.txt')
        git(self.repo,'commit','-m','seed')
        self.w('foreign.txt','key = "'+'sk-'+'Qm7vX2pL9wK4tR8zN5bH3jF6'+'"\n')
        git(self.repo,'add','foreign.txt')
        foreign=git(self.repo,'ls-files','-s','foreign.txt').stdout
        self.w('[literal] $ x.txt','staged safe\n')
        git(self.repo,'add',':(literal)[literal] $ x.txt')
        self.w('[literal] $ x.txt','key = "'+'sk-'+'Qm7vX2pL9wK4tR8zN5bH3jF6'+'"\n')
        with self.assertRaises(gs.ShipError) as error:
            gs.cmd_commit(g,ge._commit_args('selected',True),paths=['[literal] $ x.txt'])
        self.assertEqual(error.exception.code,gs.EXIT_SECRET)
        self.w('[literal] $ x.txt','final safe\n'); (self.repo/'gone.txt').unlink()
        git(self.repo,'add',':(literal)[literal] $ x.txt','gone.txt')
        hook=self.repo/'.git/hooks/pre-commit'
        hook.write_text('#!/bin/sh\ngit diff --cached --name-only > hook-paths.txt\n"'+
                        Path(sys.executable).as_posix()+'" -B "'+
                        (SCRIPTS/'git_staged_guard.py').as_posix()+'" --root .\n',encoding='utf-8')
        hook.chmod(0o755)
        result=gs.cmd_commit(g,ge._commit_args('selected',True),paths=['[literal] $ x.txt','gone.txt'])
        self.assertTrue(result['ok'])
        self.assertEqual(git(self.repo,'ls-files','-s','foreign.txt').stdout,foreign)
        self.assertEqual(set((self.repo/'hook-paths.txt').read_text().splitlines()),{'[literal] $ x.txt','gone.txt'})
        self.assertEqual(git(self.repo,'show','HEAD:[literal] $ x.txt').stdout,'final safe\n')
        self.assertNotEqual(git(self.repo,'cat-file','-e','HEAD:gone.txt',check=False).returncode,0)
        self.assertNotIn('GIT_INDEX_FILE',os.environ)

    def test_empty_selection_refuses_whole_index_and_cleans_temp(self):
        from scripts import git_ship as gs, git_ship_easy as ge
        self.w('foreign.txt','safe\n');git(self.repo,'add','foreign.txt')
        before=self.head()
        with self.assertRaises(gs.ShipError):
            gs.cmd_commit(gs.Git(str(self.repo),GIT),ge._commit_args('selected',True),paths=[])
        self.assertEqual(self.head(),before)
        self.assertEqual(self.staged(),{'foreign.txt':'A'})

if __name__ == "__main__":
    unittest.main(verbosity=2)
