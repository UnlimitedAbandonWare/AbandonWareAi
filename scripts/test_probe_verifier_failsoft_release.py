#!/usr/bin/env python3
"""Offline unit test for probe_verifier_failsoft_release.py (stdlib only).

Builds synthetic ChatWorkflow fixtures in a temp dir:
  RED  = old contract (!outcomeKnown -> HOLD / verification_outcome_unknown /
         body replaced) must exit 3.
  GREEN = new contract (verification_unknown_release, body kept) exits 0.
  EXTRA = missing gate -> exit 2; stale test claim -> warning, and exit 3
         under --strict-tests.

Exits 0 with 'ALL PASS' on success, 1 otherwise. Never touches the live tree.
"""

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROBE = ROOT / "scripts" / "probe_verifier_failsoft_release.py"

RESULTS = []


def check(name, cond, detail=""):
    RESULTS.append((name, bool(cond), detail))
    print(("PASS" if cond else "FAIL"), name, detail)


OLD_GATE = """package com.example.lms.service;

public class ChatWorkflow {
    static FinalVerificationReleaseDecision applyFinalVerificationReleaseGate(
            String candidate,
            boolean verificationRequired,
            String verificationStatus,
            boolean outcomeKnown,
            boolean acceptedForMemory) {
        String safeCandidate = candidate == null ? "" : candidate;
        if (!outcomeKnown) {
            return new FinalVerificationReleaseDecision(
                    "evidence_needed: final verification outcome unknown / retry",
                    "HOLD",
                    "verification_outcome_unknown",
                    false,
                    false,
                    false);
        }
        return new FinalVerificationReleaseDecision(
                safeCandidate, "APPROVE", "verification_accepted", true, false, true);
    }
}
"""

NEW_GATE = """package com.example.lms.service;

public class ChatWorkflow {
    static FinalVerificationReleaseDecision applyFinalVerificationReleaseGate(
            String candidate,
            boolean verificationRequired,
            String verificationStatus,
            boolean outcomeKnown,
            boolean acceptedForMemory) {
        String safeCandidate = candidate == null ? "" : candidate;
        if (!outcomeKnown) {
            return new FinalVerificationReleaseDecision(
                    safeCandidate,
                    "RELEASE",
                    "verification_unknown_release",
                    true,
                    false,
                    false);
        }
        return new FinalVerificationReleaseDecision(
                safeCandidate, "APPROVE", "verification_accepted", true, false, true);
    }
}
"""

NO_GATE = "package com.example.lms.service;\n\npublic class ChatWorkflow {\n}\n"

STALE_TEST = """package com.example.lms.service;

class ChatWorkflowFinalVerificationReleaseGateTest {
    void unknownOutcomeCannotReleaseOriginalDraft() {
        ChatWorkflow.FinalVerificationReleaseDecision decision = decide(
                "unsupported draft", true, "unknown", false, false);
        assertEquals("HOLD", decision.releaseStatus());
        assertEquals("verification_outcome_unknown", decision.reasonCode());
    }
}
"""

FRESH_TEST = """package com.example.lms.service;

class ChatWorkflowFinalVerificationReleaseGateTest {
    void unknownOutcomeReleasesDraftUnverified() {
        ChatWorkflow.FinalVerificationReleaseDecision decision = decide(
                "draft body kept", true, "unknown", false, false);
        assertTrue(decision.releaseAllowed());
        assertEquals("verification_unknown_release", decision.reasonCode());
    }
}
"""


def make_root(tmp, gate_text, test_text=None):
    root = Path(tmp)
    wf = root / "main" / "java" / "com" / "example" / "lms" / "service"
    wf.mkdir(parents=True)
    (wf / "ChatWorkflow.java").write_text(gate_text, encoding="utf-8")
    if test_text is not None:
        td = (root / "src" / "test" / "java" / "com" / "example" / "lms"
              / "service")
        td.mkdir(parents=True)
        (td / "ChatWorkflowFinalVerificationReleaseGateTest.java").write_text(
            test_text, encoding="utf-8")
    return root


def run_probe(*args):
    return subprocess.run(
        [sys.executable, "-B", str(PROBE), *args],
        capture_output=True, text=True, cwd=ROOT)


def main():
    with tempfile.TemporaryDirectory() as tmp:
        # RED fixture: old contract must be detected as exit 3.
        old_root = make_root(Path(tmp) / "old", OLD_GATE, STALE_TEST)
        r = run_probe("--root", str(old_root), "--json")
        data = json.loads(r.stdout)
        check("old-contract exit=3", r.returncode == 3,
              "exit=%s" % r.returncode)
        check("old-contract flagged", data.get("contract") == "old" and
              data.get("oldContractPresent") is True,
              "contract=%s" % data.get("contract"))
        check("old-gate branch detected",
              data["gate"]["oldBranch"]["hold"] is True and
              data["gate"]["oldBranch"]["oldReasonCode"] is True and
              data["gate"]["oldBranch"]["bodyReplaced"] is True,
              "oldBranch=%s" % data["gate"].get("oldBranch"))
        check("stale test claim warned",
              any(w["marker"] == "verification_outcome_unknown"
                  for w in data["testWarnings"]),
              "warnings=%d" % len(data["testWarnings"]))

        # GREEN fixture: new contract must exit 0 with no stale warnings.
        new_root = make_root(Path(tmp) / "new", NEW_GATE, FRESH_TEST)
        r = run_probe("--root", str(new_root), "--json")
        data = json.loads(r.stdout)
        check("new-contract exit=0", r.returncode == 0,
              "exit=%s stderr=%s" % (r.returncode, r.stderr.strip()[:200]))
        check("new-contract flagged",
              data.get("contract") == "new" and
              data.get("newContractPresent") is True and
              data.get("oldContractPresent") is False,
              "contract=%s" % data.get("contract"))
        check("fresh tests no warnings", data["testWarnings"] == [])

        # strict-tests: stale claim under the NEW contract escalates to exit 3.
        mixed_root = make_root(Path(tmp) / "mixed", NEW_GATE, STALE_TEST)
        r = run_probe("--root", str(mixed_root), "--strict-tests")
        check("strict-tests stale -> exit=3", r.returncode == 3,
              "exit=%s" % r.returncode)
        r = run_probe("--root", str(mixed_root))
        check("non-strict stale -> warn only (exit 0)",
              r.returncode == 0, "exit=%s" % r.returncode)

        # Indeterminate: file exists but gate method absent.
        ng_root = make_root(Path(tmp) / "nogate", NO_GATE)
        r = run_probe("--root", str(ng_root))
        check("missing gate exit=2", r.returncode == 2,
              "exit=%s" % r.returncode)

        # Indeterminate: file missing entirely.
        empty_root = Path(tmp) / "empty"
        empty_root.mkdir()
        r = run_probe("--root", str(empty_root))
        check("missing file exit=2", r.returncode == 2,
              "exit=%s" % r.returncode)

    failed = [n for n, ok, _ in RESULTS if not ok]
    print("ALL PASS" if not failed else "FAILED: %s" % ", ".join(failed))
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
