#!/usr/bin/env python3
"""Unit tests for scripts/baseline_sync_probe.py — fake git runner, no real git."""
from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts import baseline_sync_probe as probe_mod  # noqa: E402


EXPECT = "4150b2822f2c"
BRANCH = "codex/owned-runtime-browser-restart"


def make_runner(head="4150b2822f2c0000", branch=BRANCH, detached=False,
                toplevel="/fake/tree", expect_resolvable=True,
                ancestor_expect_of_head=False, ancestor_head_of_expect=False,
                git_ok=True, origin_main=None):
    def runner(root, args, timeout=30):
        joined = " ".join(args)
        if not git_ok:
            return 128, "", "fatal: not a git repository"
        if joined == "rev-parse --show-toplevel":
            return 0, toplevel + "\n", ""
        if joined == "rev-parse HEAD":
            return 0, head + "\n", ""
        if joined == "branch --show-current":
            return 0, ("" if detached else branch) + "\n", ""
        if joined.startswith("worktree list"):
            return 0, "worktree " + toplevel + "\nHEAD " + head + "\n\n", ""
        if joined.startswith("for-each-ref"):
            body = f"origin/main {origin_main}\n" if origin_main else ""
            return 0, body, ""
        if joined.startswith("rev-parse --verify"):
            return (0, head + "\n", "") if expect_resolvable else (128, "", "fatal: Needed a single revision")
        if joined == f"merge-base --is-ancestor {EXPECT} HEAD":
            return (0, "", "") if ancestor_expect_of_head else (1, "", "")
        if joined == f"merge-base --is-ancestor HEAD {EXPECT}":
            return (0, "", "") if ancestor_head_of_expect else (1, "", "")
        return 0, "", ""
    return runner


def make_tree(files):
    tmp = tempfile.TemporaryDirectory()
    root = Path(tmp.name)
    for rel in files:
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("x", encoding="utf-8")
    return tmp, root


class VerdictTests(unittest.TestCase):
    def test_match(self):
        tmp, root = make_tree(["main/java/x/ChatRunRegistry.java"])
        with tmp:
            r = probe_mod.probe(root, EXPECT, BRANCH, ["ChatRunRegistry"],
                                runner=make_runner())
            self.assertEqual("MATCH", r["verdict"])
            self.assertEqual("BASELINE_MATCH", r["summary"])
            self.assertTrue(r["git"]["originRefs"] == {})
            self.assertEqual("ACTIVE", r["symbols"]["ChatRunRegistry"]["status"])

    def test_ahead(self):
        tmp, root = make_tree([])
        with tmp:
            r = probe_mod.probe(root, EXPECT, BRANCH, [],
                                runner=make_runner(head="aaaabbbbcccc", ancestor_expect_of_head=True))
            self.assertEqual("AHEAD", r["verdict"])

    def test_behind(self):
        tmp, root = make_tree([])
        with tmp:
            r = probe_mod.probe(root, EXPECT, BRANCH, [],
                                runner=make_runner(head="111122223333", ancestor_head_of_expect=True))
            self.assertEqual("BEHIND", r["verdict"])

    def test_diverged(self):
        tmp, root = make_tree([])
        with tmp:
            r = probe_mod.probe(root, EXPECT, BRANCH, [],
                                runner=make_runner(head="999988887777"))
            self.assertEqual("DIVERGED", r["verdict"])

    def test_wrong_branch_is_diverged(self):
        tmp, root = make_tree([])
        with tmp:
            r = probe_mod.probe(root, EXPECT, BRANCH, [],
                                runner=make_runner(head="4150b2822f2c0000", branch="agent/other"))
            self.assertEqual("DIVERGED", r["verdict"])
            self.assertTrue(r["verdictDetail"]["branchMismatch"])

    def test_detached(self):
        tmp, root = make_tree([])
        with tmp:
            r = probe_mod.probe(root, EXPECT, BRANCH, [],
                                runner=make_runner(head="b6ec55d1aaaa", detached=True))
            self.assertEqual("DETACHED_OTHER", r["verdict"])
            self.assertFalse(r["verdictDetail"]["headMatchesExpected"])

    def test_detached_at_expected_still_not_match(self):
        tmp, root = make_tree([])
        with tmp:
            r = probe_mod.probe(root, EXPECT, BRANCH, [],
                                runner=make_runner(detached=True))
            self.assertEqual("DETACHED_OTHER", r["verdict"])
            self.assertTrue(r["verdictDetail"]["headMatchesExpected"])

    def test_no_git(self):
        tmp, root = make_tree([])
        with tmp:
            r = probe_mod.probe(root, EXPECT, BRANCH, [], runner=make_runner(git_ok=False))
            self.assertEqual("NO_GIT", r["verdict"])
            self.assertEqual("BASELINE_MISMATCH", r["summary"])

    def test_expectation_missing(self):
        tmp, root = make_tree([])
        with tmp:
            r = probe_mod.probe(root, None, BRANCH, [], runner=make_runner())
            self.assertEqual("EXPECTATION_MISSING", r["verdict"])

    def test_expected_sha_unresolvable_is_diverged(self):
        tmp, root = make_tree([])
        with tmp:
            r = probe_mod.probe(root, EXPECT, BRANCH, [],
                                runner=make_runner(head="dddd", expect_resolvable=False))
            self.assertEqual("DIVERGED", r["verdict"])
            self.assertFalse(r["verdictDetail"]["expectedShaResolvable"])

    def test_origin_main_reported(self):
        tmp, root = make_tree([])
        with tmp:
            r = probe_mod.probe(root, EXPECT, BRANCH, [],
                                runner=make_runner(origin_main="b2eaba46aa"))
            self.assertEqual("b2eaba46aa", r["git"]["originMainSha"])


class SymbolTests(unittest.TestCase):
    def test_reference_only(self):
        tmp, root = make_tree(["app/src/main/java/x/RagControl.java"])
        with tmp:
            r = probe_mod.probe(root, EXPECT, BRANCH, ["RagControl"], runner=make_runner())
            self.assertEqual("REFERENCE_ONLY", r["symbols"]["RagControl"]["status"])

    def test_missing(self):
        tmp, root = make_tree(["main/java/x/ChatRunRegistry.java"])
        with tmp:
            r = probe_mod.probe(root, EXPECT, BRANCH, ["chat.js"], runner=make_runner())
            self.assertEqual("MISSING", r["symbols"]["chat.js"]["status"])

    def test_active_and_reference_mixed(self):
        tmp, root = make_tree([
            "main/java/x/BraveSearchService.java",
            "project/src/main/java/y/BraveSearchService.java",
        ])
        with tmp:
            r = probe_mod.probe(root, EXPECT, BRANCH, ["BraveSearchService"], runner=make_runner())
            self.assertEqual("ACTIVE", r["symbols"]["BraveSearchService"]["status"])
            self.assertEqual(2, len(r["symbols"]["BraveSearchService"]["hits"]))

    def test_test_root_counts_active(self):
        tmp, root = make_tree(["src/test/java/x/JevGatewayClient.java"])
        with tmp:
            r = probe_mod.probe(root, EXPECT, BRANCH, ["JevGatewayClient"], runner=make_runner())
            self.assertEqual("ACTIVE", r["symbols"]["JevGatewayClient"]["status"])


class CliTests(unittest.TestCase):
    def test_exit_codes(self):
        tmp, root = make_tree([])
        with tmp:
            code_match = probe_mod.main(["--root", str(root), "--expect-head", EXPECT,
                                         "--expect-branch", BRANCH, "--json"])
            # probe.main uses the real git runner -> root has no git -> exit 3.
            self.assertEqual(3, code_match)


if __name__ == "__main__":
    unittest.main()
