#!/usr/bin/env python3
"""Unit tests for scripts/git_branch_context.py — fake runner, no real git."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts import git_branch_context as ctx_mod  # noqa: E402

WORK = "codex/owned-runtime-browser-restart"


def make_runner(branch=WORK, upstream=f"origin/{WORK}", counts="0\t0",
                merge_base_rc=1, snapshot_present=True, detached=False):
    calls = []

    def runner(root, args, timeout=30):
        joined = " ".join(args)
        calls.append(joined)
        if joined == "rev-parse --show-toplevel":
            return 0, str(root) + "\n", ""
        if joined == "rev-parse HEAD":
            return 0, "433bd6f56bc6\n", ""
        if joined == "rev-parse --abbrev-ref HEAD":
            return 0, ("HEAD" if detached else branch) + "\n", ""
        if joined.startswith("rev-parse --abbrev-ref --symbolic-full-name"):
            return ((0, upstream + "\n", "") if upstream
                    else (128, "", "fatal: no upstream"))
        if joined.startswith("rev-list --left-right --count"):
            return 0, counts + "\n", ""
        if joined.startswith("merge-base HEAD"):
            return (merge_base_rc, "abc123\n" if merge_base_rc == 0 else "", "")
        if joined.startswith("rev-parse --verify"):
            return ((0, "b2eaba46\n", "") if snapshot_present
                    else (128, "", "fatal: Needed a single revision"))
        if joined.startswith("symbolic-ref"):
            return 0, "refs/remotes/origin/main\n", ""
        return 0, "", ""

    runner.calls = calls
    return runner


CFG = {"workBranch": WORK, "remote": "origin", "snapshotBranch": "main"}


class VerdictTests(unittest.TestCase):
    def probe(self, runner, config=CFG):
        return ctx_mod.probe(Path("/fake"), runner=runner, config=config)

    # t1: on the work branch with upstream -> OK
    def test_ok_work_branch(self):
        runner = make_runner()
        r = self.probe(runner)
        self.assertEqual("OK_WORK_BRANCH", r["verdict"])
        self.assertEqual(0, r["git"]["ahead"])
        self.assertEqual(0, r["git"]["behind"])
        self.assertTrue(r["workBranchMatch"])
        self.assertFalse(any(c.startswith(("checkout", "switch", "fetch"))
                             for c in runner.calls))

    # t2: another branch checked out -> WRONG_BRANCH + guidance, no checkout
    def test_wrong_branch(self):
        runner = make_runner(branch="main", upstream=None)
        r = self.probe(runner)
        self.assertEqual("WRONG_BRANCH", r["verdict"])
        self.assertIn(WORK, r["note"])
        self.assertIn("checkout", r["note"])
        self.assertFalse(any(c.startswith(("checkout", "switch"))
                             for c in runner.calls))

    # t3: no merge-base with main -> SNAPSHOT_UNRELATED is normal, not a fail
    def test_snapshot_unrelated_is_not_failure(self):
        r = self.probe(make_runner(merge_base_rc=1))
        self.assertEqual("OK_WORK_BRANCH", r["verdict"])
        self.assertEqual("SNAPSHOT_UNRELATED", r["git"]["mainRelation"])

    def test_related_history_is_reported(self):
        r = self.probe(make_runner(merge_base_rc=0))
        self.assertEqual("OK_WORK_BRANCH", r["verdict"])
        self.assertEqual("RELATED", r["git"]["mainRelation"])

    # t4: missing config -> built-in defaults + warning, still works
    def test_missing_config_uses_defaults_with_warning(self):
        r = self.probe(make_runner(), config={})
        self.assertEqual("OK_WORK_BRANCH", r["verdict"])
        self.assertEqual(WORK, r["context"]["workBranch"])
        self.assertEqual("config-missing-defaults-used", r["warning"])

    def test_no_upstream(self):
        r = self.probe(make_runner(upstream=None))
        self.assertEqual("NO_UPSTREAM", r["verdict"])
        self.assertIsNone(r["git"]["ahead"])

    def test_detached(self):
        r = self.probe(make_runner(detached=True))
        self.assertEqual("DETACHED", r["verdict"])

    def test_no_git(self):
        def bad_runner(root, args, timeout=30):
            return 128, "", "fatal: not a git repository"
        r = self.probe(bad_runner)
        self.assertEqual("NO_GIT", r["verdict"])


class ExitCodeTests(unittest.TestCase):
    def test_main_exit_codes(self):
        # main() runs the real runner; a non-repo root yields exit 1.
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(1, ctx_mod.main(["--root", tmp, "--quiet"]))


if __name__ == "__main__":
    unittest.main()
