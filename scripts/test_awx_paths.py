#!/usr/bin/env python3
"""Contract tests for scripts/awx_paths.py. Stdlib unittest only."""
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import awx_paths


class RegistryLoadTest(unittest.TestCase):
    def test_loads_expected_keys(self):
        reg = awx_paths.load_registry()
        for key in ("lease.locks", "journal.base", "git.exe", "user.home",
                    "handoff.root", "patch_drop.root"):
            self.assertIn(key, reg, key)
        self.assertEqual(reg["lease.locks"]["status"], "FROZEN")

    def test_consumers_is_list(self):
        reg = awx_paths.load_registry()
        self.assertIsInstance(reg["lease.locks"]["consumers"], list)


class ResolveOrderTest(unittest.TestCase):
    def setUp(self):
        self.reg = awx_paths.load_registry()
        self._env = dict(os.environ)

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self._env)

    def test_env_beats_registry(self):
        os.environ["AWX_PATH_LEASE_LOCKS"] = r"D:\somewhere\else"
        got = awx_paths.resolve("lease.locks", registry=self.reg)
        self.assertEqual(str(got), r"D:\somewhere\else")

    def test_registry_path_used(self):
        os.environ.pop("AWX_PATH_LEASE_LOCKS", None)
        got = awx_paths.resolve("lease.locks", registry=self.reg)
        self.assertEqual(got, awx_paths.ROOT / "__patch_drop__" / "source-edit-locks")

    def test_old_paths_fallback_with_alias_log(self):
        with tempfile.TemporaryDirectory() as td:
            old = Path(td) / "old-place"
            old.mkdir()
            reg = {"demo.key": {"path": "no-such-dir-zzz/nothing",
                                "env": None, "old_paths": [str(old)]}}
            err = io.StringIO()
            with redirect_stderr(err):
                got = awx_paths.resolve("demo.key", registry=reg)
            self.assertEqual(got, old)
            self.assertIn("[AWX][path-alias] key=demo.key", err.getvalue())

    def test_userprofile_expansion(self):
        got = awx_paths.resolve("user.home", registry=self.reg)
        self.assertEqual(str(got).lower(),
                         os.environ["USERPROFILE"].lower())

    def test_unknown_key(self):
        with self.assertRaises(KeyError):
            awx_paths.resolve("no.such.key", registry=self.reg)


class GitExeTest(unittest.TestCase):
    def setUp(self):
        self.reg = awx_paths.load_registry()
        self._env = dict(os.environ)

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self._env)

    def test_env_first(self):
        os.environ["AWX_GIT_EXE"] = r"E:\fake\git.exe"
        self.assertEqual(str(awx_paths.resolve("git.exe", registry=self.reg)),
                         r"E:\fake\git.exe")

    def test_registry_then_path(self):
        os.environ.pop("AWX_GIT_EXE", None)
        got = awx_paths.resolve("git.exe", registry=self.reg)
        self.assertTrue(str(got).lower().endswith("git.exe"))
        # registry path exists on this machine, else PATH fallback must hit
        self.assertTrue(got.exists())


class CheckTest(unittest.TestCase):
    def test_detects_new_hardcode(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            (base / "scripts").mkdir()
            bad = base / "scripts" / "x.py"
            bad.write_text('P = r"C:\\AbandonWare\\demo-1\\demo-1\\src"\n',
                           encoding="utf-8")
            hits = {}
            for p in (base / "scripts").rglob("*"):
                if p.is_file() and p.suffix == ".py":
                    n = len(awx_paths.ABS_LIT_RE.findall(
                        p.read_text(encoding="utf-8")))
                    if n:
                        hits[p.name] = n
            self.assertEqual(hits, {"x.py": 1})

    def test_check_command_runs(self):
        rc = awx_paths.check()
        self.assertEqual(rc, 0)


class MovedTest(unittest.TestCase):
    def test_moved_empty_ok(self):
        with tempfile.TemporaryDirectory() as td:
            f = Path(td) / "moved.jsonl"
            self.assertEqual(awx_paths.moved(moves_file=f), 0)
            f.write_text('{"old":"a","new":"b","alias":"junction",'
                         '"expires":"1999-01-01"}\n', encoding="utf-8")
            self.assertEqual(awx_paths.aliases(expired=True, plan=True,
                                               moves_file=f), 0)


if __name__ == "__main__":
    unittest.main()
