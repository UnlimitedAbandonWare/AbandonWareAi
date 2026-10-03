"""Self-check for fault_matrix_harness.py + fault_matrix_28.json.

1. `validate` accepts the real fixture (schema, 28 unique scenarios).
2. `run` on the real fixture passes every expectation (28/28).
3. The same expectations actually bite: importing the simulator and driving
   buggy postures (caller-runs, slot-release-on-cancel, adopt-late,
   token-cap-only promotion, split gates, stale-generation 401, double save)
   produces observation fields that would FAIL the declared contract.

Run: python -B scripts/test_fault_matrix_harness.py
Exit 0 = all cases behaved; 1 = a case disagreed.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HARNESS = ROOT / "scripts" / "fault_matrix_harness.py"
sys.path.insert(0, str(ROOT / "scripts"))
import fault_matrix_harness as h  # noqa: E402


def run_harness(*args: str) -> tuple[int, dict, str]:
    proc = subprocess.run(
        [sys.executable, "-B", str(HARNESS), *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        cwd=str(ROOT))
    try:
        payload = json.loads(proc.stdout.strip().splitlines()[0])
    except (json.JSONDecodeError, IndexError):
        payload = {"parse_error": proc.stdout[:200], "stderr": proc.stderr[:200]}
    return proc.returncode, payload, proc.stdout


def main() -> int:
    cases = []

    code, out, _ = run_harness("validate")
    cases.append(("fixture-schema-valid", code == 0
                  and out.get("ok") is True
                  and out.get("scenarioCount") == 28, out))

    code, out, _ = run_harness("run")
    cases.append(("all-28-pass-contract", code == 0
                  and out.get("total") == 28 and out.get("failed") == 0, out))

    # --- the contract must actually bite: buggy postures produce violations --
    buggy = h.simulate_exec({"workers": 1, "queueCapacity": 1,
                             "executeOnCaller": True,
                             "tasks": [{"id": "a", "duration": 5},
                                       {"id": "b", "duration": 5},
                                       {"id": "c", "duration": 5}]})
    cases.append(("bug-caller-runs-detectable", buggy["callerRuns"] >= 1,
                  {"callerRuns": buggy["callerRuns"]}))

    buggy = h.simulate_exec({"workers": 1, "queueCapacity": 4,
                             "parentDeadline": 200,
                             "releaseSlotOnCancel": True,
                             "tasks": [{"id": "a", "duration": 80,
                                        "cooperative": False}],
                             "cancels": [{"id": "a", "at": 10}]})
    cases.append(("bug-slot-release-early-detectable",
                  buggy["slotReleasedEarly"] is True,
                  {"slotReleasedEarly": buggy["slotReleasedEarly"]}))

    buggy = h.simulate_exec({"workers": 1, "queueCapacity": 4,
                             "parentDeadline": 20, "adoptLate": True,
                             "tasks": [{"id": "a", "duration": 90}]})
    cases.append(("bug-late-adopt-detectable", buggy["lateResultsAdopted"] >= 1,
                  {"lateResultsAdopted": buggy["lateResultsAdopted"]}))

    buggy = h.simulate_router({"sharedGate": False, "query": "두 방식 비교",
                               "classifierWired": True,
                               "modelFilePresent": False})
    cases.append(("bug-split-gate-divergence-detectable",
                  buggy["divergent"] is True, {"gate": buggy["gateVerdict"],
                                               "policy": buggy["policyVerdict"]}))

    buggy = h.simulate_router({"sharedGate": True, "query": "안녕",
                               "maxTokens": 4096, "complexity": 0.1,
                               "promoteOnTokenCap": True})
    cases.append(("bug-token-cap-promotion-detectable",
                  buggy["promoted"] is True
                  and buggy["promotionReason"] == "token_cap_only",
                  {"promoted": buggy["promoted"],
                   "reason": buggy["promotionReason"]}))

    buggy = h.simulate_jev({"staleGeneration401": True, "currentGeneration": 1,
                            "errorGeneration": 1})
    cases.append(("bug-same-generation-401-applies", 
                  buggy["staleAuthApplied"] is True
                  and buggy["authStateCorrupted"] is True,
                  {"staleAuthApplied": buggy["staleAuthApplied"]}))

    buggy = h.simulate_store({"saveAttempts": 2, "dedupOff": True})
    obs = dict(buggy)
    obs["storedCount"] = 2
    obs["duplicateStored"] = 1
    cases.append(("bug-double-store-detectable",
                  h.eval_expect(obs, [["storedCount", "eq", 1],
                                      ["duplicateStored", "eq", 0]]) != [],
                  {"storedCount": obs["storedCount"]}))

    # --- negative fixture validation ---------------------------------------
    bad = {"scenarios": [{"id": "x"}]}
    errs = h.validate_fixture(bad)
    cases.append(("schema-missing-keys-flagged",
                  any("missing" in e for e in errs)
                  and any("count" in e for e in errs), {"errors": errs}))

    dup = {"scenarios": [{"id": "x", "category": "exec", "title": "t",
                          "inject": {}, "expect": [["a", "eq", 1]]}] * 28}
    errs = h.validate_fixture(dup)
    cases.append(("schema-duplicate-id-flagged",
                  any("duplicate-id" in e for e in errs), {"errors": errs[:4]}))

    failed = [name for name, ok, _ in cases if not ok]
    for name, ok, out in cases:
        print(f"{'PASS' if ok else 'FAIL'} {name} :: "
              f"{json.dumps(out, ensure_ascii=False)[:160]}")
    print(f"{len(cases) - len(failed)}/{len(cases)} cases behaved")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
