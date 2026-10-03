#!/usr/bin/env python3
"""Synthetic tests for api_call_budget.py - subprocess level, checks exit codes."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "api_call_budget.py"


def run(*argv) -> subprocess.CompletedProcess:
    proc = subprocess.run([sys.executable, str(SCRIPT), *argv],
                          capture_output=True, text=True)
    proc.stdout.close() if hasattr(proc.stdout, "close") else None
    return proc


class ApiCallBudgetTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.ledger = str(Path(self.tmp.name) / "API_CALLS.md")

    def tearDown(self):
        self.tmp.cleanup()

    def init(self, cap=25):
        proc = run("init", "--ledger", self.ledger, "--cap", str(cap))
        self.assertEqual(0, proc.returncode, proc.stderr)
        return proc

    def record(self, route="api3", http=200, result="ok"):
        return run("record", "--ledger", self.ledger, "--route", route,
                   "--http", str(http), "--result", result)

    def test_init_creates_md_and_json(self):
        self.init(cap=25)
        p = Path(self.ledger)
        sidecar = Path(self.ledger + ".json")
        self.assertTrue(p.is_file())
        self.assertTrue(sidecar.is_file())
        md = p.read_text(encoding="utf-8")
        self.assertIn("| time(UTC) | route | provider | http | result | note |", md)
        data = json.loads(sidecar.read_text(encoding="utf-8"))
        self.assertEqual(25, data["cap"])
        self.assertEqual([], data["records"])

    def test_record_ok_then_status_and_check(self):
        self.init(cap=3)
        proc = self.record(http=200)
        self.assertEqual(0, proc.returncode, proc.stderr)
        out = json.loads(proc.stdout)
        self.assertEqual(1, out["used"])
        self.assertEqual(2, out["remaining"])
        status = json.loads(run("status", "--ledger", self.ledger).stdout)
        self.assertEqual(3, status["cap"])
        self.assertEqual(1, status["used"])
        self.assertFalse(status["stop"])
        check = run("check", "--ledger", self.ledger)
        self.assertEqual(0, check.returncode, check.stderr)

    def test_cap_exceeded_exits_3_and_check_exits_3(self):
        self.init(cap=2)
        self.assertEqual(0, self.record().returncode)
        self.assertEqual(0, self.record().returncode)
        over = self.record()
        self.assertEqual(3, over.returncode)
        self.assertIn("BUDGET_EXCEEDED", over.stderr)
        check = run("check", "--ledger", self.ledger)
        self.assertEqual(3, check.returncode)
        self.assertIn("cap reached", check.stderr)
        md = Path(self.ledger).read_text(encoding="utf-8")
        self.assertEqual(3, md.count("| 200 |"))  # over-cap row still recorded

    def test_auth_and_throttle_codes_exit_4_and_set_stop(self):
        for code in (401, 403, 429):
            self.init(cap=5)
            proc = self.record(http=code, result="fail")
            self.assertEqual(4, proc.returncode, f"http {code}: {proc.stderr}")
            self.assertIn("STOP_AND_ROOT_CAUSE", proc.stderr)
            check = run("check", "--ledger", self.ledger)
            self.assertEqual(4, check.returncode)
            data = json.loads(Path(self.ledger + ".json").read_text(encoding="utf-8"))
            self.assertTrue(data["stop"])
            self.assertEqual(f"http_{code}_recorded", data["stopReason"])
            self.tmp.cleanup()
            self.tmp = tempfile.TemporaryDirectory()
            self.ledger = str(Path(self.tmp.name) / "API_CALLS.md")

    def test_non_stop_failure_codes_do_not_stop(self):
        self.init(cap=5)
        for code in (400, 500, 503):
            proc = self.record(http=code, result="fail")
            self.assertEqual(0, proc.returncode, f"http {code}: {proc.stderr}")
        check = run("check", "--ledger", self.ledger)
        self.assertEqual(0, check.returncode, check.stderr)

    def test_catalog_provider_lookup_never_uses_name(self):
        self.init(cap=5)
        catalog = Path(self.tmp.name) / "models.json"
        catalog.write_text(json.dumps({"models": [
            {"id": "llmrouter.api3", "provider": "groq"},
            {"id": "llmrouter.gemma", "provider": "local"},
        ]}), encoding="utf-8")
        # name that LOOKS local but catalog says groq -> provider must be groq
        run("record", "--ledger", self.ledger, "--route", "llmrouter.api3",
            "--http", "200", "--result", "ok",
            "--catalog", str(catalog))
        status = json.loads(run("status", "--ledger", self.ledger,
                                "--catalog", str(catalog)).stdout)
        self.assertEqual({"groq": 1}, status["byProvider"])

    def test_record_before_init_exits_2(self):
        proc = run("record", "--ledger", self.ledger, "--route", "x",
                   "--http", "200", "--result", "ok")
        self.assertEqual(2, proc.returncode)


if __name__ == "__main__":
    unittest.main()
