#!/usr/bin/env python3
"""Unit tests for scripts/bat_run_ledger.py (directive devin-bat-run-ledger-58a6f2c1 W8).
Run only this file:  python -B scripts/test_bat_run_ledger.py
"""
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location(
    "bat_run_ledger", os.path.join(HERE, "bat_run_ledger.py"))
brl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(brl)

KST = timezone(timedelta(hours=9))


def _load(mod_dir):
    rows = []
    for fn in sorted(os.listdir(mod_dir)):
        if fn.endswith(".jsonl"):
            with io.open(os.path.join(mod_dir, fn), encoding="utf-8") as fh:
                for line in fh:
                    if line.strip():
                        rows.append(json.loads(line))
    return rows


class BatRunLedgerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="batrun-test-")
        self.dir_p = mock.patch.object(brl, "LEDGER_DIR", self.tmp)
        self.dir_p.start()
        self.addCleanup(self.dir_p.stop)
        self.git_p = mock.patch.object(brl, "git_head", return_value="abc1234")
        self.git_p.start()
        self.addCleanup(self.git_p.stop)
        self.dirty_p = mock.patch.object(brl, "tree_dirty", return_value=False)
        self.dirty_p.start()
        self.addCleanup(self.dirty_p.stop)

    def test_append_and_masking(self):
        brl.main(["append", "--phase", "end", "--bat", "Verify-RAG.bat",
                  "--args", "--" + "token" + "=" + "abc123 -x", "--exit", "0"])
        rows = _load(self.tmp)
        self.assertEqual(len(rows), 1)
        r = rows[0]
        self.assertEqual(r["schemaVersion"], "awx.bat_run.v1")
        self.assertEqual(r["bat"], "Verify-RAG.bat")
        self.assertEqual(r["exit"], 0)
        self.assertNotIn("abc123", r["args"])
        self.assertIn("[REDACTED]", r["args"])
        self.assertEqual(r["head"], "abc1234")

    def test_caller_rules(self):
        self.assertEqual(brl.detect_caller({"AWX_CALLER": "qa"}), "qa")
        self.assertEqual(brl.detect_caller({"DEVIN": "1"}), "devin")
        self.assertEqual(brl.detect_caller({"DEVIN_SESSION_ID": "s"}), "devin")
        # Credential-like vars are machine-global, not a live session marker:
        # a persistent DEVIN_API_KEY/DEVIN_TOKEN alone must not read "devin".
        self.assertEqual(brl.detect_caller({"DEVIN_API_KEY": "x"}), "user")
        self.assertEqual(brl.detect_caller({"DEVIN_TOKEN": "x"}), "user")
        self.assertEqual(brl.detect_caller({"CODEX": "1"}), "codex")
        self.assertEqual(brl.detect_caller({"CODEX_SESSION": "s"}), "codex")
        self.assertEqual(brl.detect_caller({"CODEX_API_KEY": "x"}), "user")
        self.assertEqual(brl.detect_caller({"ANTIGRAVITY": "1"}), "agy")
        self.assertEqual(brl.detect_caller({"AGY_SESSION": "s"}), "agy")
        self.assertEqual(brl.detect_caller({"AGENT_SESSION": "1"}), "agent")
        self.assertEqual(brl.detect_caller({}), "user")

    def test_open_begins_orphan_marks(self):
        brl.append_record({"phase": "begin", "bat": "Start-RAG.bat",
                           "runKey": "k1", "caller": "test"})
        brl.append_record({"phase": "end", "bat": "Start-RAG.bat",
                           "runKey": "k1", "exit": 0, "caller": "test"})
        brl.append_record({"phase": "begin", "bat": "Verify-RAG.bat",
                           "runKey": "k2", "caller": "test"})
        opens = brl.open_begins(brl.iter_records(3))
        self.assertEqual(len(opens), 1)
        self.assertEqual(opens[0][0].get("runKey"), "k2")
        self.assertEqual(opens[0][1], "RUNNING/ORPHAN")
        buf = io.StringIO()
        with mock.patch("sys.stdout", buf):
            self.assertEqual(brl.main(["tail", "--days", "3"]), 0)
        marked = [json.loads(line) for line in buf.getvalue().splitlines()
                  if line.strip()]
        orphan = [r for r in marked if r.get("runKey") == "k2"]
        self.assertEqual(orphan[0].get("state"), "RUNNING/ORPHAN")
        buf = io.StringIO()
        with mock.patch("sys.stdout", buf):
            self.assertEqual(brl.main(["summary", "--days", "3"]), 0)
        self.assertIn("[RUNNING/ORPHAN]", buf.getvalue())
        self.assertIn("open=1", buf.getvalue())

    def test_summary_counts(self):
        for i, ex in enumerate([0, 1, 0]):
            brl.append_record({"phase": "end", "bat": "Debug-RAG.bat",
                               "exit": ex, "caller": "test"})
        brl.append_record({"phase": "begin", "bat": "Debug-RAG.bat",
                           "caller": "test"})
        buf = io.StringIO()
        with mock.patch("sys.stdout", buf):
            self.assertEqual(brl.main(["summary", "--days", "3"]), 0)
        out = buf.getvalue()
        self.assertIn("Debug-RAG.bat", out)
        self.assertIn("runs=3", out)
        self.assertIn("total=3", out)  # begin phase excluded from run count

    def test_should_run_no_history(self):
        buf = io.StringIO()
        with mock.patch("sys.stdout", buf):
            rc = brl.main(["should-run", "Never-Seen.bat"])
        self.assertEqual(rc, 0)
        self.assertIn("RUN", buf.getvalue())

    def test_should_run_skip_recent_ok(self):
        brl.append_record({"phase": "end", "bat": "Verify-RAG.bat", "exit": 0,
                           "head": "abc1234", "caller": "test"})
        buf = io.StringIO()
        with mock.patch("sys.stdout", buf):
            rc = brl.main(["should-run", "Verify-RAG.bat"])
        self.assertEqual(rc, 3)
        self.assertIn("SKIP_RECENT_OK", buf.getvalue())

    def test_should_run_fix_first(self):
        for _ in range(2):
            brl.append_record({"phase": "end", "bat": "start_rag_stack.ps1",
                               "exit": 1, "failurePoint": "port-conflict",
                               "status": "failed", "caller": "test",
                               "result_path": "var\\rag-launcher\\x"})
        buf = io.StringIO()
        with mock.patch("sys.stdout", buf):
            rc = brl.main(["should-run", "start_rag_stack.ps1"])
        self.assertEqual(rc, 4)
        self.assertIn("FIX_FIRST", buf.getvalue())
        self.assertIn("port-conflict", buf.getvalue())

    def test_should_run_after_dirty_tree(self):
        brl.append_record({"phase": "end", "bat": "Verify-RAG.bat", "exit": 0,
                           "head": "abc1234", "caller": "test"})
        with mock.patch.object(brl, "tree_dirty", return_value=True):
            buf = io.StringIO()
            with mock.patch("sys.stdout", buf):
                rc = brl.main(["should-run", "Verify-RAG.bat"])
        self.assertEqual(rc, 0)
        self.assertIn("tree dirty", buf.getvalue())

    def test_backfill(self):
        src = os.path.join(self.tmp, "rag-launcher")
        run = os.path.join(src, "20990101-120000-deadbeef")
        os.makedirs(run)
        with io.open(os.path.join(run, "result.json"), "w", encoding="utf-8") as f:
            json.dump({"ok": False, "status": "failed", "stage": "PREFLIGHT",
                       "failurePoint": "launcher"}, f)
        rc = brl.main(["backfill", "--from", src, "--days", "36500"])
        self.assertEqual(rc, 0)
        rows = _load(self.tmp)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["caller"], "unknown")
        self.assertEqual(rows[0]["runId"], "20990101-120000-deadbeef")
        self.assertEqual(rows[0]["failurePoint"], "launcher")
        # idempotent
        rc = brl.main(["backfill", "--from", src, "--days", "36500"])
        self.assertEqual(rc, 0)
        self.assertEqual(len(_load(self.tmp)), 1)

    def test_hook_preserves_exit_code(self):
        """bat_run_hook.cmd must not change the caller's exit code."""
        hook = os.path.join(HERE, "bat_run_hook.cmd")
        bat = os.path.join(self.tmp, "fake.bat")
        with io.open(bat, "w", newline="\r\n") as f:
            f.write("@echo off\r\n"
                    "call \"%s\" begin \"%%~nx0\" %%*\r\n" % hook +
                    "call \"%s\" end \"%%~nx0\" 7\r\n" % hook +
                    "exit /b 7\r\n")
        env = dict(os.environ, AWX_BATRUN_DIR=self.tmp, AWX_CALLER="test")
        cmdexe = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"),
                              "System32", "cmd.exe")
        p = subprocess.run([cmdexe, "/c", bat, "arg1", "arg2"],
                           capture_output=True, env=env)
        self.assertEqual(p.returncode, 7)
        rows = _load(self.tmp)
        phases = sorted(r["phase"] for r in rows)
        self.assertEqual(phases, ["begin", "end"])
        self.assertEqual(rows[-1]["exit"], 7)
        self.assertEqual(rows[-1]["caller"], "test")
        # args must not shift the bat name away (shift regression guard)
        self.assertEqual(rows[-1]["bat"], "fake.bat")
        begins = [r for r in rows if r["phase"] == "begin"]
        self.assertEqual(begins[0]["bat"], "fake.bat")
        self.assertIn("arg1", begins[0]["args"])

    def test_hook_noop_without_python_path(self):
        """Missing python -> hook still exits 0 (no interference)."""
        hook = os.path.join(HERE, "bat_run_hook.cmd")
        env = dict(os.environ, AWX_BATRUN_DIR=self.tmp, PATH="")
        cmdexe = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"),
                              "System32", "cmd.exe")
        p = subprocess.run([cmdexe, "/c", "call", hook, "end", "X.bat", "3"],
                           capture_output=True, env=env)
        self.assertEqual(p.returncode, 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
