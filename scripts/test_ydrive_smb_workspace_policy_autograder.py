"""Contract tests for the Y-drive SMB workspace policy autograder."""

from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import ydrive_smb_workspace_policy_autograder as grader


SCRIPT = Path(__file__).with_name("ydrive_smb_workspace_policy_autograder.py")


def direct_facts(*, identity: bool | None = True) -> dict[str, object]:
    return {
        "intent": "DIRECT_MUTATION",
        "mappingStatus": "OK",
        "mappingIdentityMatched": identity,
        "guardProven": True,
        "targetBoundaryProven": True,
        "indexLockPresent": False,
    }


def direct_decision() -> dict[str, object]:
    return {
        "canonicalWorkspace": "Y:\\",
        "backingShareIdentityVerified": True,
        "backingShareIdentityReason": "match",
        "sourceWriteRoot": "Y:\\",
        "authorizedMutation": True,
        "mode": "YDRIVE_SMB_GUARDED_DIRECT",
        "verdict": "APPLY",
        "rewriteCanonicalToUnc": False,
        "fallbackWorkspace": None,
    }


def packet(
    decision: dict[str, object] | None = None,
    facts: dict[str, object] | None = None,
) -> dict[str, object]:
    return {
        "scenarioId": "literal-contract-scenario",
        "facts": direct_facts() if facts is None else facts,
        "decision": direct_decision() if decision is None else decision,
    }


class GradePacketTests(unittest.TestCase):
    def test_v2_direct_apply_requires_explicit_write_authorization(self) -> None:
        result = grader.grade_packet(packet())
        self.assertEqual("ydrive-smb-workspace-policy-autograder/v2", result["schemaVersion"])
        self.assertEqual("PASS", result["verdict"])

        unsafe = direct_decision()
        unsafe["authorizedMutation"] = False
        rejected = grader.grade_packet(packet(unsafe))
        self.assertEqual("FAIL", rejected["verdict"])
        self.assertIn("direct-apply-not-authorized", rejected["hardGateFailures"])

    def test_read_only_requires_null_write_root_and_false_authorization(self) -> None:
        facts = {
            "intent": "READ_AUDIT_BUILD_TOOL",
            "mappingStatus": "OK",
            "mappingIdentityMatched": True,
        }
        decision = {
            "canonicalWorkspace": "Y:\\",
            "backingShareIdentityVerified": True,
            "backingShareIdentityReason": "match",
            "sourceWriteRoot": None,
            "authorizedMutation": False,
            "mode": "SMB_ACCESS",
            "verdict": "APPLY",
            "rewriteCanonicalToUnc": False,
            "fallbackWorkspace": None,
        }
        self.assertEqual("PASS", grader.grade_packet(packet(decision, facts))["verdict"])

        unsafe = copy.deepcopy(decision)
        unsafe["sourceWriteRoot"] = "Y:\\"
        unsafe["authorizedMutation"] = True
        rejected = grader.grade_packet(packet(unsafe, facts))
        self.assertEqual("FAIL", rejected["verdict"])
        self.assertIn("read-only-write-root-not-null", rejected["hardGateFailures"])
        self.assertIn("read-only-authorized-mutation", rejected["hardGateFailures"])

    def test_hold_forbids_every_write_authorization(self) -> None:
        facts = direct_facts(identity=False)
        decision = direct_decision()
        decision.update(
            {
                "backingShareIdentityVerified": False,
                "backingShareIdentityReason": "mismatch",
                "sourceWriteRoot": None,
                "authorizedMutation": False,
                "mode": "HOLD",
                "verdict": "HOLD",
            }
        )
        self.assertEqual("PASS", grader.grade_packet(packet(decision, facts))["verdict"])

        unsafe = copy.deepcopy(decision)
        unsafe["sourceWriteRoot"] = "Y:\\"
        unsafe["authorizedMutation"] = True
        rejected = grader.grade_packet(packet(unsafe, facts))
        self.assertEqual("FAIL", rejected["verdict"])
        self.assertIn("hold-write-root-not-null", rejected["hardGateFailures"])
        self.assertIn("hold-authorized-mutation", rejected["hardGateFailures"])

    def test_identity_boolean_and_reason_must_match_supplied_facts(self) -> None:
        contradictory = direct_decision()
        contradictory["backingShareIdentityVerified"] = False
        contradictory["backingShareIdentityReason"] = "evidence-needed"
        rejected = grader.grade_packet(packet(contradictory, direct_facts(identity=True)))
        self.assertEqual("FAIL", rejected["verdict"])
        self.assertIn("contradictory-backing-identity-evidence", rejected["hardGateFailures"])

        evidence_needed = direct_decision()
        evidence_needed.update(
            {
                "backingShareIdentityVerified": False,
                "backingShareIdentityReason": "evidence-needed",
                "sourceWriteRoot": None,
                "authorizedMutation": False,
                "mode": "HOLD",
                "verdict": "HOLD",
            }
        )
        self.assertEqual(
            "PASS",
            grader.grade_packet(packet(evidence_needed, direct_facts(identity=None)))["verdict"],
        )

    def test_omitted_required_decision_fields_are_rejected(self) -> None:
        for field in ("authorizedMutation", "backingShareIdentityVerified", "backingShareIdentityReason"):
            with self.subTest(field=field):
                decision = direct_decision()
                del decision[field]
                result = grader.grade_packet(packet(decision))
                self.assertEqual("FAIL", result["verdict"])
                self.assertIn(f"missing-decision-field:{field}", result["hardGateFailures"])


class CliTests(unittest.TestCase):
    def run_cli(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), *arguments],
            cwd=SCRIPT.parent.parent,
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=15,
            check=False,
        )

    def test_input_path_grades_one_packet(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "packet.json"
            input_path.write_text(json.dumps(packet()), encoding="utf-8")
            completed = self.run_cli("--input", str(input_path))
        self.assertEqual(0, completed.returncode, completed.stderr)
        result = json.loads(completed.stdout)
        self.assertEqual("PASS", result["verdict"])

    def test_cli_requires_exactly_one_public_mode(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "packet.json"
            input_path.write_text(json.dumps(packet()), encoding="utf-8")
            neither = self.run_cli()
            both = self.run_cli("--input", str(input_path), "--self-test")
        self.assertEqual(2, neither.returncode)
        self.assertEqual(2, both.returncode)

    def test_self_test_is_a_public_mode(self) -> None:
        completed = self.run_cli("--self-test")
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("failures=0", completed.stdout)


if __name__ == "__main__":
    unittest.main()
