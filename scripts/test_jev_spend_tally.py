"""Read-only tally. Temp ledgers only; the real ledger hash must not change."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path(__file__).with_name("jev_spend_tally.py")
LEDGER = ROOT / "data" / "agent-handoff" / "jev-spend" / "ledger.jsonl"
BUDGET = Path(__file__).with_name("jev_campaign_budget.py")


def _load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _run(args: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, "-B", str(SCRIPT), *args, "--json"],
                          capture_output=True, text=True, cwd=str(ROOT))


class TallyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.before = hashlib.sha256(LEDGER.read_bytes()).hexdigest() if LEDGER.is_file() else None

    def tearDown(self) -> None:
        if self.before is not None:
            self.assertEqual(hashlib.sha256(LEDGER.read_bytes()).hexdigest(), self.before)

    def _ledger(self, rows: list[dict]) -> Path:
        path = Path(tempfile.mkdtemp()) / "ledger.jsonl"
        path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
        return path

    def test_under_cap_groups_agents_as_decimal(self) -> None:
        path = self._ledger([
            {"agent": "grok", "dayKst": "2026-09-30", "session": "s1", "costUsd": 0.1},
            {"agent": "devin", "dayKst": "2026-09-30", "session": "s2", "costUsd": 0.2},
        ])
        result = _run(["--ledger", str(path)])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        body = json.loads(result.stdout)
        self.assertEqual(body["amountSource"], "float-ledger")
        self.assertIn("floats", body["note"])
        self.assertEqual(body["totals"]["sumUsd"], "0.3")
        agents = {row["agent"]: row["sumUsd"] for row in body["byAgentDay"]}
        self.assertEqual(agents["grok"], "0.1")
        self.assertEqual(agents["devin"], "0.2")

    def test_warn_and_cap_exits(self) -> None:
        warn = self._ledger([{"agent": "devin", "dayKst": "2026-09-30", "session": "s", "costUsd": 0.41}])
        cap = self._ledger([{"agent": "devin", "dayKst": "2026-09-30", "session": "s", "costUsd": 0.5}])
        self.assertEqual(_run(["--ledger", str(warn)]).returncode, 3)
        self.assertEqual(_run(["--ledger", str(cap)]).returncode, 5)

    def test_spend_line_fallback_and_campaign_fold(self) -> None:
        missing = Path(tempfile.mkdtemp()) / "absent.jsonl"
        spend = Path(tempfile.mkdtemp()) / "spend.log"
        spend.write_text(
            '[AWX][api-spend] {"agent":"devin","dayKst":"2026-09-30","session":"s","costUsd":0.01}\n',
            encoding="utf-8")
        result = _run(["--ledger", str(missing), "--spend-log", str(spend)])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        body = json.loads(result.stdout)
        self.assertEqual(body["amountSource"], "spend-line")
        self.assertEqual(body["totals"]["sumUsd"], "0.01")

        budget = _load(BUDGET, "jev_campaign_budget")
        campaign = Path(tempfile.mkdtemp())
        previous = os.environ.get("AWX_JEV_CAMPAIGN_DIR")
        os.environ["AWX_JEV_CAMPAIGN_DIR"] = str(campaign)
        try:
            policy = ROOT / "data" / "agent-handoff" / "grok-jev-campaign-tooling-20260930" / "fixtures" / "policy.SYNTHETIC.json"
            opened = budget.init_policy("tally1", policy, directory=campaign)
            self.assertTrue(opened.get("ok"), opened)
            reserved = budget.reserve("tally1", "c1", "0.25", provider="jev", directory=campaign)
            self.assertTrue(reserved.get("ok"), reserved)
            held = budget.hold_unknown("tally1", "c1", "timeout", directory=campaign)
            self.assertTrue(held.get("ok"), held)
        finally:
            if previous is None:
                os.environ.pop("AWX_JEV_CAMPAIGN_DIR", None)
            else:
                os.environ["AWX_JEV_CAMPAIGN_DIR"] = previous
        result = _run(["--ledger", str(missing), "--campaign-dir", str(campaign)])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        campaigns = json.loads(result.stdout)["campaigns"]
        self.assertEqual(campaigns[0]["campaignId"], "tally1")
        self.assertEqual(campaigns[0]["unknownHeldUsd"], "0.25")
        self.assertEqual(campaigns[0]["inflightUsd"], "0")

    def test_does_not_create_real_campaign_dir(self) -> None:
        before = set(os.listdir(ROOT / "data" / "agent-handoff" / "jev-spend"))
        _run(["--ledger", str(self._ledger([]))])
        after = set(os.listdir(ROOT / "data" / "agent-handoff" / "jev-spend"))
        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main()
