#!/usr/bin/env python3
"""Synthetic tests for settings_defaults_budget_meter.py - fake ledger
dirs under a temp root; real Codex ledgers are never touched."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

SCRIPT = Path(__file__).resolve().parent / "settings_defaults_budget_meter.py"
spec = importlib.util.spec_from_file_location("sd_budget_meter", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class BudgetMeterTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.ledger = Path(self.tmp.name) / "codex-settings-defaults-aaaaaaaa"
        self.ledger.mkdir(parents=True)

    def tearDown(self):
        self.tmp.cleanup()

    def _audit(self):
        return mod.audit_ledger(self.ledger)

    def test_empty_ledger_dir_unknown_not_zero(self):
        out = self._audit()
        self.assertEqual("NO_LEDGER_FILES", out["status"])
        for m in out["metrics"].values():
            self.assertEqual("UNKNOWN", m["value"],
                             "missing evidence must stay UNKNOWN, not 0")

    def test_api_calls_json_counts_and_retries(self):
        doc = {"cap": 25, "records": [
            {"route": "llmrouter.api3", "http": 200, "result": "ok"},
            {"route": "llmrouter.api3", "http": 429, "result": "fail"},
            {"route": "llmrouter.api3", "http": 200, "result": "ok"},
            {"route": "https://abandonwareai.kro.kr", "http": 200,
             "result": "ok", "note": "public smoke"}]}
        (self.ledger / "API_CALLS.md.json").write_text(
            json.dumps(doc), encoding="utf-8")
        out = self._audit()
        m = out["metrics"]
        self.assertEqual(4, m["chatSends"]["value"])
        self.assertEqual(25, m["chatSends"]["cap"])
        self.assertEqual(1, m["publicSiteSends"]["value"])
        # both calls after the 429 row count as post-stop retries
        self.assertEqual(2, m["retriesAfterStopCodes"]["value"])

    def test_journal_restart_mentions(self):
        doc = {"events": [
            {"text": "presence check only"},
            {"text": "launcher restart 1/2 done"},
            {"text": "second 재시작 recorded"}]}
        (self.ledger / "journal.json").write_text(
            json.dumps(doc), encoding="utf-8")
        out = self._audit()
        self.assertEqual(2, out["metrics"]["restarts"]["value"])
        self.assertEqual("UNKNOWN",
                         out["metrics"]["chatSends"]["value"])

    def test_report_no_dirs_is_pending(self):
        args = SimpleNamespace(
            codex_glob=[str(Path(self.tmp.name) / "no-such-*")], out=None)
        import io, contextlib
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = mod.cmd_report(args)
        self.assertEqual(0, code)
        doc = json.loads(buf.getvalue())
        self.assertEqual("PENDING", doc["status"])

    def test_never_writes_into_ledger(self):
        before = sorted(p.name for p in self.ledger.iterdir())
        self._audit()
        after = sorted(p.name for p in self.ledger.iterdir())
        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main()
