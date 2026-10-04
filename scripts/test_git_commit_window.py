#!/usr/bin/env python3
"""Unit tests for git_commit_window.py — fake inputs only, no real git."""

import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import git_commit_window as gcw  # noqa: E402

NOW = datetime(2026, 10, 3, 9, 0, 0, tzinfo=timezone.utc)


class TestPorcelainParse(unittest.TestCase):
    def test_porcelain_z_rows(self):
        rows = gcw.parse_porcelain_z("M  a.txt\0?? b.txt\0")
        self.assertEqual(rows, [{"xy": "M ", "path": "a.txt"},
                                {"xy": "??", "path": "b.txt"}])

    def test_porcelain_rename_consumes_extra_field(self):
        rows = gcw.parse_porcelain_z("R  new.txt\0old.txt\0M  c.txt\0")
        self.assertEqual([r["path"] for r in rows], ["new.txt", "c.txt"])

    def test_porcelain_empty(self):
        self.assertEqual(gcw.parse_porcelain_z(""), [])


class TestSensitive(unittest.TestCase):
    def test_sensitive_patterns(self):
        for p in (".env", ".env.local", "x/.secrets/k.txt", "my_credentials.json",
                  "data/lmsdb.mv.db", "cert.pem", "key.key"):
            self.assertTrue(gcw.is_sensitive(p), p)

    def test_normal_paths_not_sensitive(self):
        for p in ("scripts/git_doctor.py", "docs/readme.md", "env_notes.txt"):
            self.assertFalse(gcw.is_sensitive(p), p)


class TestBuckets(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "__patch_drop__/source-edit-locks/abc.lock").mkdir(
            parents=True)
        lease = {"ownerId": "other-agent",
                 "expiresAt": "2026-10-03T10:00:00+00:00",
                 "targetPaths": ["scripts/held.py"]}
        (self.root / "__patch_drop__/source-edit-locks/abc.lock/lease.json"
         ).write_text(json.dumps(lease), encoding="utf-8")
        self.leases = gcw.load_leases(self.root)

    def tearDown(self):
        self.tmp.cleanup()

    def _touch(self, rel, age_seconds=3600, size=10):
        p = self.root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(b"x" * size)
        mtime = NOW.timestamp() - age_seconds
        import os
        os.utime(p, (mtime, mtime))

    def test_held_by_lease(self):
        self._touch("scripts/held.py")
        info = gcw.classify_path("scripts/held.py", self.root, self.leases, 3, NOW)
        self.assertEqual(info["bucket"], "HELD_BY_LEASE")
        self.assertEqual(info["detail"]["lease"]["ownerId"], "other-agent")
        self.assertEqual(info["detail"]["lease"]["expiresAtKst"],
                         "2026-10-03 19:00:00 KST")

    def test_lease_path_casefold_match(self):
        self._touch("SCRIPTS/HELD.PY")
        info = gcw.classify_path("Scripts\\Held.py", self.root, self.leases, 3, NOW)
        self.assertEqual(info["bucket"], "HELD_BY_LEASE")

    def test_hot_recent(self):
        self._touch("hot.txt", age_seconds=60)
        info = gcw.classify_path("hot.txt", self.root, self.leases, 3, NOW)
        self.assertEqual(info["bucket"], "HOT_RECENT")

    def test_not_hot_after_quiet_window(self):
        self._touch("warm.txt", age_seconds=600)
        info = gcw.classify_path("warm.txt", self.root, self.leases, 3, NOW)
        self.assertEqual(info["bucket"], "SAFE")

    def test_large(self):
        old = gcw.LARGE_BYTES
        gcw.LARGE_BYTES = 100
        try:
            self._touch("big.bin", age_seconds=3600, size=200)
            info = gcw.classify_path("big.bin", self.root, self.leases, 3, NOW)
        finally:
            gcw.LARGE_BYTES = old
        self.assertEqual(info["bucket"], "LARGE")

    def test_precedence_held_over_sensitive(self):
        self._touch("scripts/held.py")
        info = gcw.classify_path("scripts/held.py", self.root, self.leases, 3, NOW)
        self.assertNotIn("SENSITIVE", info["tags"][:-1] or info["tags"])
        self.assertEqual(info["bucket"], "HELD_BY_LEASE")

    def test_bucket_rows_counts(self):
        self._touch("safe.txt", age_seconds=3600)
        self._touch("hot2.txt", age_seconds=10)
        rows = gcw.bucket_rows(self.root, gcw.parse_porcelain_z(
            "M  safe.txt\0?? hot2.txt\0M  scripts/held.py\0"), self.leases, 3, NOW)
        self.assertEqual(len(rows["SAFE"]), 1)
        self.assertEqual(len(rows["HOT_RECENT"]), 1)
        self.assertEqual(len(rows["HELD_BY_LEASE"]), 1)


class TestPlanOutputs(unittest.TestCase):
    def test_writes_md_json_uncheck(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "s.envx").write_text("x", encoding="utf-8")
            rows = gcw.bucket_rows(root, gcw.parse_porcelain_z("?? .envx\0"),
                                   {}, 3, NOW)
            out = gcw.write_plan_outputs(root, rows, 3, NOW,
                                         out_dir=root / "out")
            for key in ("json", "md", "uncheck"):
                self.assertTrue(Path(out[key]).exists(), key)
            doc = json.loads(Path(out["json"]).read_text(encoding="utf-8"))
            self.assertEqual(doc["bucketCounts"]["SENSITIVE"], 1)
            self.assertIn(".envx", Path(out["uncheck"]).read_text(
                encoding="utf-8"))


class TestFlag(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def _write_flag(self, opened_minutes_ago, ttl):
        path = gcw.flag_path(self.root)
        path.parent.mkdir(parents=True, exist_ok=True)
        opened = NOW - timedelta(minutes=opened_minutes_ago)
        path.write_text(json.dumps(
            {"openedAt": gcw._iso(opened), "ttlMinutes": ttl, "by": "t"}),
            encoding="utf-8")

    def test_closed_when_missing(self):
        self.assertEqual(gcw.flag_state(self.root, NOW)["state"], "closed")

    def test_open_within_ttl(self):
        self._write_flag(5, 10)
        st = gcw.flag_state(self.root, NOW)
        self.assertEqual(st["state"], "open")
        self.assertEqual(st["by"], "t")

    def test_expired_flag_kept_not_deleted(self):
        self._write_flag(30, 10)
        st = gcw.flag_state(self.root, NOW)
        self.assertEqual(st["state"], "expired")
        self.assertTrue(gcw.flag_path(self.root).exists())

    def test_open_close_commands(self):
        args = type("A", (), {"ttl": 10, "by": "me"})()
        st = gcw.cmd_open(self.root, args)
        self.assertEqual(st["state"], "open")
        st = gcw.cmd_close(self.root, None)
        self.assertTrue(st["removed"])
        self.assertEqual(gcw.flag_state(self.root, NOW)["state"], "closed")


class TestLockReport(unittest.TestCase):
    def test_lock_report_present(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / ".git").mkdir()
            (root / ".git/index.lock").write_bytes(b"")
            info = gcw.cmd_lock(root, None)
            lock = info["indexLock"]
            self.assertTrue(lock["exists"])
            self.assertEqual(lock["sizeBytes"], 0)
            self.assertIn("gitProcesses", info)

    def test_lock_report_absent(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / ".git").mkdir()
            info = gcw.cmd_lock(root, None)
            self.assertFalse(info["indexLock"]["exists"])


class TestNoGitWriteArgs(unittest.TestCase):
    """The tool must never emit a git write argument."""

    WRITE_ARGS = {"add", "commit", "stash", "reset", "checkout", "push",
                  "pull", "merge", "rebase", "clean", "restore", "gc",
                  "prune", "fetch", "rm", "mv", "tag", "apply"}

    def _argv_strings(self):
        import ast
        src = Path(gcw.__file__).read_text(encoding="utf-8")
        tree = ast.parse(src)
        strs = []
        for node in ast.walk(tree):
            if isinstance(node, ast.Call):
                for arg in ast.walk(node):
                    if isinstance(arg, ast.Constant) and isinstance(arg.value, str):
                        strs.append(arg.value)
        return strs

    def test_no_write_literal_in_calls(self):
        for s in self._argv_strings():
            self.assertNotIn(s.strip().lower(), self.WRITE_ARGS,
                             f"git write arg literal found: {s!r}")

    def test_no_git_write_invocation_pattern(self):
        import re
        src = Path(gcw.__file__).read_text(encoding="utf-8")
        pat = re.compile(r'["\']git["\'][^)\n]*?["\'](' +
                         "|".join(sorted(self.WRITE_ARGS)) + r')["\']')
        self.assertIsNone(pat.search(src),
                          "git write invocation found in source")


if __name__ == "__main__":
    unittest.main()
