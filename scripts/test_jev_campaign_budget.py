"""Offline tests for jev_campaign_budget.py. Temporary journals only. No network."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

SCRIPT = Path(__file__).with_name("jev_campaign_budget.py")
SPEC = importlib.util.spec_from_file_location("jev_campaign_budget", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)

LEDGER = Path(__file__).resolve().parents[1] / "data" / "agent-handoff" / "jev-spend" / "ledger.jsonl"


def _policy(max_calls=20, hard="2.00", target="1.00", providers=None):
    return {
        "approval_ref": "ADD-20260930-SYNTHETIC",
        "campaign_id": "template",
        "policy_revision": "1",
        "startedAtUtc": "2026-09-30T00:00:00Z",
        "target_usd": target,
        "hard_cap_usd": hard,
        "allowed_providers": providers or ["jev", "gemini"],
        "allowed_models": ["typesafe-ai/jev", "synthetic-flash"],
        "max_calls": max_calls,
        "planHash": "SYNTHETIC",
    }


class BudgetTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = self._tmp.name
        self._prev = os.environ.get("AWX_JEV_CAMPAIGN_DIR")
        os.environ["AWX_JEV_CAMPAIGN_DIR"] = self.dir

    def tearDown(self) -> None:
        if self._prev is None:
            os.environ.pop("AWX_JEV_CAMPAIGN_DIR", None)
        else:
            os.environ["AWX_JEV_CAMPAIGN_DIR"] = self._prev
        self._tmp.cleanup()

    def boot(self, campaign="C", **kwargs):
        src = Path(self.dir) / (campaign + "-src.json")
        src.write_text(json.dumps(_policy(**kwargs)), encoding="utf-8")
        result = MOD.init_policy(campaign, src, directory=self.dir)
        self.assertEqual(result.get("exit"), 0, result)
        return result

    def events(self, campaign="C"):
        path = Path(self.dir) / (campaign + ".jsonl")
        if not path.is_file():
            return []
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]

    def test_two_processes_last_balance_only_one_wins(self) -> None:
        self.boot("race", max_calls=10)
        env = os.environ.copy()
        env["AWX_JEV_CAMPAIGN_DIR"] = self.dir
        cmd = [sys.executable, "-B", str(SCRIPT), "--lock-timeout", "5", "reserve", "--campaign", "race",
               "--provider", "jev", "--max-usd", "1.50"]
        first = subprocess.Popen(cmd + ["--call", "a"], cwd=str(SCRIPT.parent), env=env,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        second = subprocess.Popen(cmd + ["--call", "b"], cwd=str(SCRIPT.parent), env=env,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        out1, err1 = first.communicate(timeout=30)
        out2, err2 = second.communicate(timeout=30)
        codes = sorted([first.returncode, second.returncode])
        self.assertEqual(codes, [0, 5], (out1, err1, out2, err2))
        refused = err1 if first.returncode == 5 else err2
        self.assertIn("budget_refused:cap-exceeded", refused)

    def test_timeout_keeps_reservation(self) -> None:
        self.boot()
        self.assertEqual(MOD.reserve("C", "c1", "0.40", directory=self.dir)["exit"], 0)
        held = MOD.hold_unknown("C", "c1", "timeout", directory=self.dir)
        self.assertEqual(held["exit"], 0)
        self.assertEqual(held["unknownHeldUsd"], "0.40")
        self.assertEqual(held["settledUsd"], "0")
        self.assertEqual(held["calls"]["c1"]["state"], "UNKNOWN_HELD")

    def test_abandoned_prefetch_counted(self) -> None:
        self.boot()
        MOD.reserve("C", "c1", "0.25", directory=self.dir)
        MOD.mark_dispatched("C", "c1", directory=self.dir)
        held = MOD.hold_unknown("C", "c1", "abandoned", directory=self.dir)
        self.assertEqual(held["unknownHeldUsd"], "0.25")
        self.assertEqual(held["inflightUsd"], "0")

    def test_crash_between_reserve_and_dispatch_is_held(self) -> None:
        self.boot()
        MOD.reserve("C", "c1", "0.30", directory=self.dir)
        recovered = MOD.recover("C", directory=self.dir)
        self.assertEqual(recovered["calls"]["c1"]["state"], "UNKNOWN_HELD")
        self.assertNotIn("DISPATCHED", [row["event"] for row in self.events()])
        self.assertEqual(recovered["unknownHeldUsd"], "0.30")

    def test_late_usage_settles_once(self) -> None:
        self.boot()
        MOD.reserve("C", "c1", "0.40", directory=self.dir)
        MOD.hold_unknown("C", "c1", "timeout", directory=self.dir)
        first = MOD.settle("C", "c1", "0.01", directory=self.dir)
        second = MOD.settle("C", "c1", "0.01", directory=self.dir)
        self.assertEqual(first["exit"], 0)
        self.assertEqual(first["settledUsd"], "0.01")
        self.assertEqual(first["unknownHeldUsd"], "0")
        self.assertEqual(second["exit"], 0)
        self.assertTrue(second.get("duplicate_receipt"))
        self.assertEqual(second["settledUsd"], "0.01")

    def test_duplicate_receipt_noop(self) -> None:
        self.boot()
        MOD.reserve("C", "c1", "0.20", directory=self.dir)
        MOD.mark_dispatched("C", "c1", directory=self.dir)
        MOD.settle("C", "c1", "0.02", directory=self.dir)
        again = MOD.settle("C", "c1", "0.02", directory=self.dir)
        self.assertTrue(again.get("duplicate_receipt"))
        self.assertEqual(len([row for row in self.events() if row["event"] == "SETTLED"]), 1)

    def test_conflicting_receipt_blocks(self) -> None:
        self.boot()
        MOD.reserve("C", "c1", "0.20", directory=self.dir)
        MOD.settle("C", "c1", "0.02", directory=self.dir)
        conflict = MOD.settle("C", "c1", "0.03", directory=self.dir)
        self.assertEqual(conflict["exit"], 5)
        self.assertEqual(conflict["reason"], "conflict")
        blocked = MOD.reserve("C", "c2", "0.01", directory=self.dir)
        self.assertEqual(blocked["reason"], "conflict")

    def test_restart_does_not_refill(self) -> None:
        self.boot()
        MOD.reserve("C", "c1", "1.50", directory=self.dir)
        MOD.recover("C", directory=self.dir)
        again = MOD.reserve("C", "c2", "1.50", directory=self.dir)
        self.assertEqual(again["exit"], 5)
        self.assertEqual(again["reason"], "cap-exceeded")
        self.assertEqual(MOD.status("C", directory=self.dir)["unknownHeldUsd"], "1.50")

    def test_overrun_blocks_further_calls(self) -> None:
        self.boot()
        MOD.reserve("C", "c1", "0.10", directory=self.dir)
        settled = MOD.settle("C", "c1", "0.50", directory=self.dir)
        self.assertTrue(settled["overrun"])
        blocked = MOD.reserve("C", "c2", "0.01", directory=self.dir)
        self.assertEqual(blocked["reason"], "overrun-block")

    def test_uncomputable_cost_refuses(self) -> None:
        self.boot()
        missing = MOD.max_charge(None, 10, 10, "jev")
        self.assertIsNone(missing)
        refused = MOD.reserve("C", "c1", missing, directory=self.dir)
        self.assertEqual(refused["reason"], "uncomputable-max-charge")
        empty = {"label": "SYNTHETIC", "providers": {"jev": {"inputUsdPerMillion": "", "outputUsdPerMillion": "0.1"}}}
        self.assertIsNone(MOD.max_charge(empty, 10, 10, "jev"))
        self.assertEqual(MOD.status("C", directory=self.dir)["callCount"], 0)

    def test_decimal_no_float_drift(self) -> None:
        self.boot(max_calls=30)
        self.assertEqual(sum((Decimal("0.1") for _ in range(20)), Decimal("0")), Decimal("2.0"))
        for index in range(20):
            result = MOD.reserve("C", "c%d" % index, "0.10", directory=self.dir)
            self.assertEqual(result["exit"], 0, result)
        self.assertEqual(MOD.status("C", directory=self.dir)["inflightUsd"], "2.00")
        overflow = MOD.reserve("C", "overflow", "0.10", directory=self.dir)
        self.assertEqual(overflow["reason"], "cap-exceeded")
        self.assertEqual(MOD.reserve("C", "floaty", 0.1, directory=self.dir)["reason"], "float-not-allowed")

    def test_target_reached_logged_once(self) -> None:
        self.boot()
        MOD.reserve("C", "a", "0.40", directory=self.dir)
        self.assertEqual([row["event"] for row in self.events() if row["event"] == "TARGET_REACHED"], [])
        crossed = MOD.reserve("C", "b", "0.70", directory=self.dir)
        self.assertTrue(crossed["targetReached"])
        MOD.reserve("C", "c", "0.10", directory=self.dir)
        marks = [row for row in self.events() if row["event"] == "TARGET_REACHED"]
        self.assertEqual(len(marks), 1)
        self.assertIn("sumUsd", marks[0]["proof"])

    def test_provider_not_in_policy_refused(self) -> None:
        self.boot()
        refused = MOD.reserve("C", "c1", "0.01", provider="openai", directory=self.dir)
        self.assertEqual(refused["reason"], "provider-not-allowed")

    def test_max_calls_N_enforced(self) -> None:
        self.boot(max_calls=2)
        self.assertEqual(MOD.reserve("C", "a", "0.01", directory=self.dir)["exit"], 0)
        self.assertEqual(MOD.reserve("C", "b", "0.01", directory=self.dir)["exit"], 0)
        third = MOD.reserve("C", "c", "0.01", directory=self.dir)
        self.assertEqual(third["reason"], "max-calls")

    def test_lock_timeout_fails_closed(self) -> None:
        self.boot("lock")
        handle = MOD._acquire(Path(self.dir) / "lock.lock", 2)
        self.assertIsNotNone(handle)
        try:
            proc = subprocess.run(
                [sys.executable, "-B", str(SCRIPT), "--lock-timeout", "0.4",
                 "reserve", "--campaign", "lock", "--call", "c1", "--provider", "jev", "--max-usd", "0.01"],
                cwd=str(SCRIPT.parent), env=os.environ.copy(),
                capture_output=True, text=True, timeout=20)
        finally:
            MOD._release(handle)
        self.assertEqual(proc.returncode, 5, proc.stdout)
        self.assertIn("budget_refused:lock-timeout", proc.stderr)
        self.assertFalse((Path(self.dir) / "lock.jsonl").is_file())

    def test_release_requires_proof(self) -> None:
        self.boot()
        MOD.reserve("C", "c1", "0.40", directory=self.dir)
        missing = MOD.release_undispatched("C", "c1", "  ", directory=self.dir)
        self.assertEqual(missing["exit"], 2)
        self.assertEqual(MOD.status("C", directory=self.dir)["inflightUsd"], "0.40")
        released = MOD.release_undispatched("C", "c1", "gate-refused-before-send", directory=self.dir)
        self.assertEqual(released["exit"], 0)
        self.assertEqual(released["inflightUsd"], "0")
        self.assertEqual(released["calls"]["c1"]["state"], "RELEASED")
        self.assertEqual(MOD.reserve("C", "c2", "0.40", directory=self.dir)["exit"], 0)

    def test_gemini_and_jev_share_campaign(self) -> None:
        self.boot("shared")
        self.assertEqual(MOD.reserve("shared", "j", "1.20", provider="jev", directory=self.dir)["exit"], 0)
        self.assertEqual(MOD.reserve("shared", "g", "0.80", provider="gemini", directory=self.dir)["exit"], 0)
        self.assertEqual(MOD.reserve("shared", "x", "0.01", provider="jev", directory=self.dir)["reason"], "cap-exceeded")

    def test_other_campaign_unaffected(self) -> None:
        self.boot("one")
        self.boot("two")
        MOD.reserve("one", "a", "2.00", directory=self.dir)
        other = MOD.reserve("two", "a", "1.00", provider="gemini", directory=self.dir)
        self.assertEqual(other["exit"], 0)
        self.assertEqual(other["inflightUsd"], "1.00")
        self.assertEqual(MOD.reserve("one", "b", "0.01", directory=self.dir)["reason"], "cap-exceeded")

    def test_real_ledger_path_untouched(self) -> None:
        before = hashlib.sha256(LEDGER.read_bytes()).hexdigest()
        self.boot()
        MOD.reserve("C", "c1", "0.01", directory=self.dir)
        MOD.hold_unknown("C", "c1", "crash", directory=self.dir)
        after = hashlib.sha256(LEDGER.read_bytes()).hexdigest()
        self.assertEqual(before, after)

    def test_hard_cap_above_two_refused(self) -> None:
        src = Path(self.dir) / "wide.json"
        src.write_text(json.dumps(_policy(hard="2.01")), encoding="utf-8")
        result = MOD.init_policy("wide", src, directory=self.dir)
        self.assertEqual(result["reason"], "policy-invalid")
        self.assertFalse((Path(self.dir) / "wide.policy.json").is_file())


if __name__ == "__main__":
    unittest.main()
