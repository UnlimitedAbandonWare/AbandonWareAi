"""Behavior tests for the offline three-way long-tail v2 design autograder."""

from __future__ import annotations

import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path

import score_three_way_long_tail_design as design_grader
from score_three_way_long_tail_design import (
    GradeDesignInputError,
    INPUT_LIMIT_BYTES,
    canonical_json_bytes,
    grade_design,
)


CONTRACT_PATH = (
    Path(__file__).resolve().parents[1]
    / "agent-prompts"
    / "agents"
    / "demo1_three_perspective_chat_postprocess"
    / "v2_design_contract.json"
)
SCRIPT_PATH = Path(__file__).with_name("score_three_way_long_tail_design.py")


def valid_contract() -> dict[str, object]:
    return json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))


class GradeDesignTests(unittest.TestCase):
    def grade(self, mutate=None, *, input_size=4096):
        payload = valid_contract()
        if mutate:
            mutate(payload)
        return grade_design(payload, input_size)

    def test_canonical_contract_is_a_complete_apply(self):
        result = self.grade()
        self.assertEqual("APPLY", result["designVerdict"])
        self.assertEqual(100.0, result["metaScore"])
        self.assertEqual([], result["hardGates"])
        self.assertEqual(1.0, result["requiredRuleCategoryCoverage"])
        self.assertEqual(1.0, result["adversarialExpectedOutcomeAccuracy"])
        self.assertEqual(0, result["falseAcceptCount"])
        self.assertEqual(0, result["canonicalFalseRejectCount"])

    def test_candidate_owned_baseline_is_a_hard_gate(self):
        result = self.grade(
            lambda contract: contract["fixtureAuthority"].__setitem__(
                "candidateMaySubmitBaseline", True
            )
        )
        self.assertEqual("HOLD", result["designVerdict"])
        self.assertIn("candidate-owned-baseline", result["hardGates"])

    def test_case_resampling_is_not_an_independent_unit(self):
        result = self.grade(
            lambda contract: contract["statisticalContract"].__setitem__(
                "resamplingUnit", "caseId"
            )
        )
        self.assertEqual("HOLD", result["designVerdict"])
        self.assertIn("cluster-dependence-ignored", result["hardGates"])

    def test_non_discriminating_causal_contract_is_a_hard_gate(self):
        result = self.grade(
            lambda contract: contract["causalContract"].__setitem__(
                "expectedOutcomesMustDiffer", False
            )
        )
        self.assertEqual("HOLD", result["designVerdict"])
        self.assertIn("causal-contract-nondiscriminating", result["hardGates"])

    def test_unregistered_causal_falsifier_or_probe_holds_the_design(self):
        for field in ("falsifierRegistered", "probeRegistered"):
            with self.subTest(field=field):
                result = self.grade(
                    lambda contract, field=field: contract["causalContract"].__setitem__(field, False)
                )
                self.assertEqual("HOLD", result["designVerdict"])
                self.assertIn("causal-contract-nondiscriminating", result["hardGates"])

    def test_all_statistical_identity_locks_are_non_compensable(self):
        fields = (
            "rubricVersionLocked", "scorerVersionLocked", "formulaHashLocked", "fixtureDeckHashLocked",
            "generationConditionHashLocked", "policyVersionLocked", "pairedCaseSetsLocked",
            "pairedCaseCountDerived", "stratumIdsLocked", "clusterIdsLocked", "tieEpsilonLocked",
        )
        for field in fields:
            with self.subTest(field=field):
                result = self.grade(
                    lambda contract, field=field: contract["statisticalContract"].__setitem__(field, False)
                )
                self.assertEqual("HOLD", result["designVerdict"])
                self.assertTrue(result["hardGates"])

    def test_evidence_relations_must_equal_the_grader_allowlist(self):
        result = self.grade(
            lambda contract: contract["evidenceRelationContract"].__setitem__(
                "allowedRelations", ["supports"]
            )
        )
        self.assertEqual("HOLD", result["designVerdict"])
        self.assertIn("evidence-relation-allowlist-invalid", result["hardGates"])

    def test_neutral_sub_results_must_be_derived(self):
        for field in ("orderStabilityDerived", "decisiveEvidenceDerived"):
            with self.subTest(field=field):
                result = self.grade(
                    lambda contract, field=field: contract["neutralDerivationContract"].__setitem__(field, False)
                )
                self.assertEqual("HOLD", result["designVerdict"])
                self.assertIn("neutral-verdict-not-derived", result["hardGates"])

    def test_candidate_owned_authority_metadata_is_a_hard_gate(self):
        result = self.grade(
            lambda contract: contract["fixtureAuthority"].__setitem__(
                "candidateMaySubmitAuthorityMetadata", True
            )
        )
        self.assertEqual("HOLD", result["designVerdict"])
        self.assertIn("candidate-owned-authority-metadata", result["hardGates"])

    def test_safety_regression_acceptance_rejects_the_design(self):
        result = self.grade(
            lambda contract: contract["safetyContract"].__setitem__(
                "safetyRegressionOutcome", "NO_UPLIFT"
            )
        )
        self.assertEqual("REJECT", result["designVerdict"])
        self.assertIn("safety-regression-not-rejected", result["hardGates"])

    def test_submitted_release_threshold_cannot_lower_fixed_gate(self):
        result = self.grade(
            lambda contract: contract["releaseGate"].__setitem__(
                "minimumMetaScore", 90
            )
        )
        self.assertEqual("HOLD", result["designVerdict"])
        self.assertIn("release-threshold-invalid", result["hardGates"])

    def test_submitted_rule_answer_does_not_change_grader_owned_reference(self):
        baseline = self.grade()
        result = self.grade(
            lambda contract: contract["ruleMatrix"][0].__setitem__(
                "expectedReason", "designer-chosen-answer"
            )
        )
        self.assertEqual("APPLY", baseline["ruleResults"]["canonical-valid"]["referenceVerdict"])
        self.assertEqual("none", baseline["ruleResults"]["canonical-valid"]["referenceReason"])
        self.assertEqual("HOLD", result["designVerdict"])
        self.assertGreater(result["falseAcceptCount"] + result["canonicalFalseRejectCount"], 0)
        self.assertEqual("APPLY", result["ruleResults"]["canonical-valid"]["referenceVerdict"])
        self.assertEqual("none", result["ruleResults"]["canonical-valid"]["referenceReason"])

    def test_unknown_keys_fail_closed(self):
        payload = valid_contract()
        payload["unknown"] = True
        with self.assertRaisesRegex(GradeDesignInputError, "unknown-key") as raised:
            grade_design(payload, 4096)
        self.assertEqual("unknown-key", raised.exception.reason_code)

    def test_duplicate_or_missing_rule_categories_hold_the_design(self):
        missing = self.grade(lambda contract: contract["ruleMatrix"].pop())
        self.assertEqual("HOLD", missing["designVerdict"])
        self.assertIn("adversarial-matrix-incomplete", missing["hardGates"])

        duplicate = self.grade(
            lambda contract: contract["ruleMatrix"].__setitem__(
                1, copy.deepcopy(contract["ruleMatrix"][0])
            )
        )
        self.assertEqual("HOLD", duplicate["designVerdict"])
        self.assertIn("adversarial-matrix-incomplete", duplicate["hardGates"])

    def test_resource_and_invalid_json_values_fail_closed(self):
        cases = (
            (lambda payload: payload["ruleMatrix"].extend(copy.deepcopy(payload["ruleMatrix"] * 3)), 4096, "rule-count-exceeded"),
            (lambda payload: None, INPUT_LIMIT_BYTES + 1, "input-size-exceeded"),
            (lambda payload: payload["releaseGate"].__setitem__("minimumMetaScore", float("nan")), 4096, "non-finite-value"),
        )
        for mutate, input_size, reason in cases:
            with self.subTest(reason=reason):
                payload = valid_contract()
                mutate(payload)
                with self.assertRaisesRegex(GradeDesignInputError, reason) as raised:
                    grade_design(payload, input_size)
                self.assertEqual(reason, raised.exception.reason_code)

    def test_secret_like_content_and_excess_nesting_fail_closed(self):
        payload = valid_contract()
        payload["designId"] = "sk-"" ""this-must-not-be-accepted"
        with self.assertRaisesRegex(GradeDesignInputError, "secret-like-content") as raised:
            grade_design(payload, 4096)
        self.assertEqual("secret-like-content", raised.exception.reason_code)

        payload = valid_contract()
        nested = payload["scopeSeparation"]
        for _ in range(33):
            child: dict[str, object] = {}
            nested["nested"] = child
            nested = child
        with self.assertRaisesRegex(GradeDesignInputError, "structure-depth-exceeded") as raised:
            grade_design(payload, 4096)
        self.assertEqual("structure-depth-exceeded", raised.exception.reason_code)

    def test_malformed_scalar_list_boolean_and_enum_inputs_raise_input_errors(self):
        cases = (
            (lambda contract: contract.__setitem__("canonicalQueries", 7), "invalid-type"),
            (lambda contract: contract["scopeSeparation"].__setitem__("offlineOnly", "true"), "invalid-type"),
            (lambda contract: contract["statisticalContract"].__setitem__("resamplingUnit", ["clusterId"]), "invalid-type"),
            (lambda contract: contract["statisticalContract"].__setitem__("bootstrapIterations", True), "invalid-type"),
            (lambda contract: contract["safetyContract"].__setitem__("safetyRegressionOutcome", "ALLOW"), "invalid-enum"),
            (lambda contract: contract["releaseGate"].__setitem__("minimumMetaScore", "95"), "invalid-type"),
        )
        for mutate, reason in cases:
            with self.subTest(reason=reason):
                payload = valid_contract()
                mutate(payload)
                with self.assertRaisesRegex(GradeDesignInputError, reason) as raised:
                    grade_design(payload, 4096)
                self.assertEqual(reason, raised.exception.reason_code)

    def test_replay_and_secret_evidence_is_observed_not_submitted(self):
        contract = valid_contract()
        self.assertNotIn("deterministicReplayByteIdentical", contract["releaseGate"])
        self.assertNotIn("secretPatternHitCount", contract["releaseGate"])
        result = self.grade()
        self.assertEqual(
            {"deterministicReplayByteIdentical": True, "secretPatternHitCount": 0},
            result["observedEvidence"],
        )

    def test_failed_internal_replay_is_a_hold_hard_gate(self):
        baseline = self.grade()
        changed = copy.deepcopy(baseline)
        changed["metaScore"] = 99.0
        with mock.patch.object(
            design_grader, "_grade_design_core", side_effect=[baseline, changed], create=True
        ):
            result = grade_design(valid_contract(), 4096)
        self.assertEqual("HOLD", result["designVerdict"])
        self.assertIn("deterministic-replay-failed", result["hardGates"])
        self.assertFalse(result["observedEvidence"]["deterministicReplayByteIdentical"])

    def test_identifiers_are_hashed_and_never_reflected(self):
        payload = valid_contract()
        payload["designId"] = "unclassified-private-identifier-4172"
        payload["designRevision"] = "release-candidate-private-9918"
        result = grade_design(payload, 4096)
        serialized = canonical_json_bytes(result).decode("utf-8")
        self.assertNotIn(payload["designId"], serialized)
        self.assertNotIn(payload["designRevision"], serialized)
        self.assertNotIn("designId", result)
        self.assertNotIn("designRevision", result)
        self.assertEqual(
            "sha256:" + hashlib.sha256(payload["designId"].encode("utf-8")).hexdigest(),
            result["designIdHash"],
        )
        self.assertEqual(
            "sha256:" + hashlib.sha256(payload["designRevision"].encode("utf-8")).hexdigest(),
            result["designRevisionHash"],
        )

    def test_common_secret_identifier_families_fail_without_reflection(self):
        for marker in (
            "ghp_"" ""abcdefghijklmnopqrstuvwxyz1234567890",
            "gith"" ""ub_pat_11AAabcdefghijklmnopqrstuvwxyz123456",
            "xoxb-" "1234567890-1234567890-"" ""abcdefghijklmnopqrstuvwxyz",
            "AKIA"" ""IOSFODNN7EXAMPLE",
        ):
            with self.subTest(family=marker[:4]):
                payload = valid_contract()
                payload["designId"] = marker
                with self.assertRaisesRegex(GradeDesignInputError, "secret-like-content") as raised:
                    grade_design(payload, 4096)
                self.assertNotIn(marker, str(raised.exception))

    def test_identifiers_are_bounded_without_echoing_rejected_values(self):
        marker = "private-identifier-" + "x" * 200
        payload = valid_contract()
        payload["designRevision"] = marker
        with self.assertRaisesRegex(GradeDesignInputError, "identifier-invalid") as raised:
            grade_design(payload, 4096)
        self.assertNotIn(marker, str(raised.exception))

    def test_replay_serialization_is_byte_identical(self):
        first = canonical_json_bytes(self.grade())
        second = canonical_json_bytes(self.grade())
        self.assertEqual(first, second)
        self.assertTrue(first.endswith(b"\n"))


class CliContractTests(unittest.TestCase):
    def invoke(self, payload: dict[str, object], *, output: bool = False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "contract.json"
            input_path.write_text(json.dumps(payload), encoding="utf-8")
            output_path = root / "result.json"
            command = [sys.executable, str(SCRIPT_PATH), "--input", str(input_path)]
            if output:
                command.extend(["--output", str(output_path), "--run-root", str(root)])
            completed = subprocess.run(command, capture_output=True, text=True, check=False)
            written = output_path.read_bytes() if output_path.exists() else b""
            return completed, written

    def test_cli_apply_and_canonical_output(self):
        completed, written = self.invoke(valid_contract(), output=True)
        self.assertEqual(0, completed.returncode)
        self.assertEqual(b"", completed.stdout.encode("utf-8"))
        self.assertEqual("APPLY", json.loads(written)["designVerdict"])
        self.assertEqual(written, canonical_json_bytes(json.loads(written)))

    def test_cli_hold_reject_and_malformed_exit_codes(self):
        hold = valid_contract()
        hold["fixtureAuthority"]["candidateMaySubmitBaseline"] = True
        completed, _ = self.invoke(hold)
        self.assertEqual(1, completed.returncode)
        self.assertIn("candidate-owned-baseline", completed.stdout)

        rejected = valid_contract()
        rejected["safetyContract"]["safetyRegressionOutcome"] = "NO_UPLIFT"
        completed, _ = self.invoke(rejected)
        self.assertEqual(1, completed.returncode)
        self.assertIn("safety-regression-not-rejected", completed.stdout)

        invalid = valid_contract()
        invalid["unknown"] = True
        completed, written = self.invoke(invalid, output=True)
        self.assertEqual(2, completed.returncode)
        self.assertEqual("unknown-key", completed.stderr.strip())
        self.assertEqual(b"", written)

    def test_cli_malformed_scalar_exits_two_not_internal_error(self):
        invalid = valid_contract()
        invalid["canonicalQueries"] = 7
        completed, written = self.invoke(invalid, output=True)
        self.assertEqual(2, completed.returncode)
        self.assertEqual("invalid-type", completed.stderr.strip())
        self.assertEqual(b"", written)

    def test_cli_rejects_input_output_collision_without_modifying_input(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "contract.json"
            original = json.dumps(valid_contract()).encode("utf-8")
            input_path.write_bytes(original)
            completed = subprocess.run(
                [sys.executable, str(SCRIPT_PATH), "--input", str(input_path), "--output", str(input_path), "--run-root", str(root)],
                capture_output=True, text=True, check=False,
            )
            observed = input_path.read_bytes()
        self.assertEqual(2, completed.returncode)
        self.assertEqual("output-input-collision", completed.stderr.strip())
        self.assertEqual(original, observed)

    def test_cli_rejects_output_outside_caller_temp_run_root(self):
        with tempfile.TemporaryDirectory() as directory:
            outer = Path(directory)
            run_root = outer / "run"
            outside = outer / "outside"
            run_root.mkdir()
            outside.mkdir()
            input_path = run_root / "contract.json"
            output_path = outside / "result.json"
            input_path.write_text(json.dumps(valid_contract()), encoding="utf-8")
            completed = subprocess.run(
                [sys.executable, str(SCRIPT_PATH), "--input", str(input_path), "--output", str(output_path), "--run-root", str(run_root)],
                capture_output=True, text=True, check=False,
            )
            output_exists = output_path.exists()
        self.assertEqual(2, completed.returncode)
        self.assertEqual("output-outside-run-root", completed.stderr.strip())
        self.assertFalse(output_exists)

    def test_cli_requires_run_root_for_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "contract.json"
            output_path = root / "result.json"
            input_path.write_text(json.dumps(valid_contract()), encoding="utf-8")
            completed = subprocess.run(
                [sys.executable, str(SCRIPT_PATH), "--input", str(input_path), "--output", str(output_path)],
                capture_output=True, text=True, check=False,
            )
            output_exists = output_path.exists()
        self.assertEqual(2, completed.returncode)
        self.assertEqual("run-root-required", completed.stderr.strip())
        self.assertFalse(output_exists)


if __name__ == "__main__":
    unittest.main(verbosity=2)
