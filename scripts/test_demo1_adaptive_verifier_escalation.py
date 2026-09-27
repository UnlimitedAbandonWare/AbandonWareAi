from __future__ import annotations

import contextlib
import copy
import importlib.util
import hashlib
import io
import json
import math
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
SKILL_DIR = ROOT / ".agents" / "skills" / "demo1-adaptive-verifier-escalation"
SKILL_PATH = SKILL_DIR / "SKILL.md"
OPENAI_PATH = SKILL_DIR / "agents" / "openai.yaml"
CONTRACT_PATH = SKILL_DIR / "references" / "evaluation-contract.md"
EVALUATOR_PATH = SKILL_DIR / "scripts" / "evaluate_failure_routes.py"


def load_evaluator():
    spec = importlib.util.spec_from_file_location("evaluate_failure_routes", EVALUATOR_PATH)
    if spec is None or spec.loader is None:
        raise AssertionError("evaluator-import-unavailable")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def route_rows():
    return [
        {"scenarioId":"tp","expectedFailureClass":"stall","observedFailureClass":"stall","expectedPostProbeAction":"DISPATCH_PAIR","observedPostProbeAction":"DISPATCH_PAIR","labelProvenance":"sealed"},
        {"scenarioId":"fn-continue","expectedFailureClass":"stall","observedFailureClass":"none","expectedPostProbeAction":"DISPATCH_PAIR","observedPostProbeAction":"CONTINUE","labelProvenance":"sealed"},
        {"scenarioId":"fp","expectedFailureClass":"none","observedFailureClass":"semantic","expectedPostProbeAction":"CONTINUE","observedPostProbeAction":"DISPATCH_PAIR","labelProvenance":"sealed"},
        {"scenarioId":"tn","expectedFailureClass":"none","observedFailureClass":"none","expectedPostProbeAction":"CONTINUE","observedPostProbeAction":"CONTINUE","labelProvenance":"sealed"},
        {"scenarioId":"fn-hold","expectedFailureClass":"semantic","observedFailureClass":"semantic","expectedPostProbeAction":"DISPATCH_PAIR","observedPostProbeAction":"HOLD","labelProvenance":"sealed"},
        {"scenarioId":"tn-skip","expectedFailureClass":"none","observedFailureClass":"none","expectedPostProbeAction":"CONTINUE","observedPostProbeAction":"SKIP_OWNER_CONTROLLED","labelProvenance":"sealed"},
    ]


def canonical_fixture_bytes(rows):
    return json.dumps(
        rows, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def valid_route_manifest(rows):
    return {
        "schemaVersion":"demo1.adaptive-verifier-evaluation.v1",
        "evaluationMode":"ROUTE_EVAL",
        "fixtureId":"route-fixture-v1",
        "fixtureSha256":hashlib.sha256(canonical_fixture_bytes(rows)).hexdigest(),
        "evaluationSplit":"locked_evaluation",
        "detectorPolicyVersion":"detector-v1",
        "labelPolicyVersion":"labels-v1",
        "positiveClassDefinition":"expectedPostProbeAction==DISPATCH_PAIR",
        "allowedFailureClasses":["none","semantic","stall"],
        "allowedPostProbeActions":["CONTINUE","DISPATCH_PAIR","HOLD","SKIP_OWNER_CONTROLLED"],
        "costPolicyVersion":None,
        "costPolicyRef":None,
        "costPolicy":None,
        "calibrationPolicyVersion":None,
        "calibrationFitRef":None,
        "calibrationFitFixtureSha256":None,
        "foldAssignmentSha256":None,
        "foldFitRefs":None,
        "thresholdPolicyVersion":"threshold-v1",
        "thresholdSelectionRef":"sha256:" + "1" * 64,
        "thresholdSelectionFixtureSha256":"2" * 64,
    }


def write_cli_fixture(directory, manifest, rows):
    manifest_path = directory / "manifest.json"
    rows_path = directory / "rows.jsonl"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False), encoding="utf-8"
    )
    rows_path.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
        encoding="utf-8",
    )
    return manifest_path, rows_path


class SkillContractTests(unittest.TestCase):
    EXPECTED_FRONTMATTER = """---
name: demo1-adaptive-verifier-escalation
description: Use when a bounded worker stalls, repeats failures, produces no new evidence, conflicts with an authoritative artifact, or when labeled worker routes need precision/recall calibration; coordinates one read-only probe, two orthogonal verifier roles, and route-only abstaining aggregation without replacing owner-controlled triads or authorizing source mutation.
---
"""

    EXPECTED_OPENAI_YAML = """interface:
  display_name: \"Demo1 Adaptive Verifier Escalation\"
  short_description: \"Calibrate worker failures and bounded verifier escalation\"
  default_prompt: \"Use $demo1-adaptive-verifier-escalation to detect a stalled worker, run one bounded probe, and calibrate any verifier escalation.\"
"""

    def test_entrypoint_declares_route_only_owner_precedence(self):
        text = SKILL_PATH.read_text(encoding="utf-8")
        for clause in (
            "decisionAuthority=route_only",
            "routeGatePassed",
            "SKIP_OWNER_CONTROLLED",
            "maxAttempts=1",
            "independenceProven",
            "SUGGESTION_ONLY",
            "productionRecallStatus=unobservable",
        ):
            self.assertIn(clause, text)
        self.assertNotIn("verificationGatePassed=true", text)

    def test_metadata_mentions_the_skill(self):
        text = OPENAI_PATH.read_text(encoding="utf-8")
        self.assertEqual(text, self.EXPECTED_OPENAI_YAML)

    def test_entrypoint_frontmatter_workflow_and_limits_are_exact(self):
        text = SKILL_PATH.read_text(encoding="utf-8")
        self.assertTrue(text.startswith(self.EXPECTED_FRONTMATTER))
        self.assertLessEqual(len(text.splitlines()), 160)
        self.assertLessEqual(len(text.split()), 1200)

        steps = (
            "1. Select mode `ROUTE` for one bounded run or `EVALUATE` for sealed labeled fixtures.",
            "2. Honor `workflowOwner`; return `SKIP_OWNER_CONTROLLED` for protected triads.",
            "3. Freeze the redacted health envelope",
            "4. Separate checkable `operationalSignals` from claim-to-evidence `semanticSignals`.",
            "5. Run one local deterministic probe",
            "6. Delegate any semantic retrieval to the existing three-slot counter-evidence owner.",
            "7. Dispatch `blind_solver` and `falsifier` only after the post-probe gate.",
            "8. Collapse correlated provenance; never majority-vote.",
            "9. Select a candidate or `HOLD` with `decisionAuthority=route_only`.",
            "10. Require the existing coherence verifier before factual confirmation.",
            "11. Report `productionRecallStatus=unobservable` without a valid non-triggered audit.",
        )
        positions = [text.index(step) for step in steps]
        self.assertEqual(positions, sorted(positions))
        normalized = " ".join(text.split())
        for non_trigger in (
            "ordinary success",
            "generic three-way review",
            "raw verbal confidence",
            "source-mutation request",
            "future-window observations",
        ):
            self.assertIn(non_trigger, normalized)

    def test_probe_requires_positive_budget_and_fails_closed(self):
        text = SKILL_PATH.read_text(encoding="utf-8")
        self.assertIn("timeBudgetMs>0 or tokenBudget>0", text)
        self.assertIn("probe-budget-missing-or-nonpositive", text)

    def test_reference_has_arm_applicability_null_contract(self):
        text = CONTRACT_PATH.read_text(encoding="utf-8")
        for clause in (
            "## Arm applicability and null contract",
            "| Arm | blindSolverCorrect | falsifierDecisionCorrect | aggregatorDecision | orderStable | probeCallCount | verifierCallCount | aggregatorCallCount |",
            "A0 | `null` | `null` | `null` | `null` | `0` | `0` | `0`",
            "A1 | `null` | `null` | required | `null` | `0` | `2` | `1`",
            "A2 | required | required | required | required | `0` | `2` | `1`",
            "A3 / cleared | `null` | `null` | `null` | `null` | `1` | `0` | `0`",
            "A3 / dispatched | required | required | required | required | `1` | `2` | `1`",
            "null-vs-required mismatch",
        ):
            self.assertIn(clause, text)
        self.assertNotIn("| Arm | minimalProbe |", text)

    def test_reference_declares_numeric_types_bounds_and_rejections(self):
        text = CONTRACT_PATH.read_text(encoding="utf-8")
        for clause in (
            "## Numeric types and bounds",
            "`detectorScore` | number | `0 <= value <= 1`",
            "`selectionProbability` | number | `0 < value <= 1`",
            "`probeCallCount` | integer | `0 <= value <= 1`",
            "`verifierCallCount` | integer | `0 <= value <= 2`",
            "`aggregatorCallCount` | integer | `0 <= value <= 1`",
            "`latencyMs` | number | `0 <= value <= 32400000`",
            "`observedCostUnits` | number | `0 <= value <= 1000000000`",
            "`replicateId` and `foldId` may be strings; a numeric identifier is an integer with `0 <= value <= 1000000`.",
            "`fpPenalty`, `fnPenalty`, `probeUnitCost`, `verifierUnitCost`, `aggregatorUnitCost`, `holdPenalty`",
            "`latencyUnitCostPerMs`",
            "bool-as-number",
            "NaN",
            "infinity",
            "numeric-out-of-range",
        ):
            self.assertIn(clause, text)
        for stale_name in (
            "probeCost",
            "verifierCost",
            "aggregatorCost",
            "holdCost",
            "latencyCostPerMs",
        ):
            self.assertNotIn(stale_name, text)

    def test_reference_constrains_paired_decisions_and_order_coupling(self):
        text = CONTRACT_PATH.read_text(encoding="utf-8")
        for clause in (
            "### Decision domains and coupling",
            "| A0 | `null` | `null` |",
            "| A1 | `APPLY|HOLD|REJECT` | `null` |",
            "| A2 | `SELECT_WORKER_CANDIDATE|SELECT_CORRECTION_CANDIDATE|HOLD|HANDOFF_STRONG_JUDGE` | JSON boolean |",
            "| A3 / cleared | `null` | `null` |",
            "| A3 / dispatched | `SELECT_WORKER_CANDIDATE|SELECT_CORRECTION_CANDIDATE|HOLD|HANDOFF_STRONG_JUDGE` | JSON boolean |",
            "`orderStable=false` requires `aggregatorDecision=HOLD` and `acceptedCorrect=null`.",
            "`aggregatorDecision=HOLD` or `aggregatorDecision=HANDOFF_STRONG_JUDGE` requires `acceptedCorrect=null`.",
            "A1 `HOLD` also requires `acceptedCorrect=null`.",
            "A candidate-selection decision requires `orderStable=true` and remains `decisionAuthority=route_only`; it does not prove factual truth.",
            "invalid-aggregator-decision",
            "non-boolean-order-stable",
            "order-stability-hold-required",
            "abstention-accepted-coupling",
            "candidate-order-stability-required",
            "route-only-not-truth",
        ):
            self.assertIn(clause, text)

    def test_reference_owns_both_evaluation_modes(self):
        text = CONTRACT_PATH.read_text(encoding="utf-8")
        self.assertIn("ROUTE_EVAL", text)
        self.assertIn("PAIRED_EVAL", text)
        self.assertIn("expectedPostProbeAction", text)


class RouteEvaluatorTests(unittest.TestCase):
    def assert_input_error(self, evaluator, reason_code, manifest, rows):
        with self.assertRaises(evaluator.EvaluationInputError) as caught:
            evaluator.evaluate(manifest, rows)
        self.assertEqual(reason_code, caught.exception.reason_code)
        self.assertEqual(reason_code, str(caught.exception))

    def test_binary_dispatch_and_abstention_mapping(self):
        evaluator = load_evaluator()
        result = evaluator.evaluate(valid_route_manifest(route_rows()), route_rows())
        self.assertEqual({"tp":1,"fp":1,"fn":2,"tn":2}, result["binaryDispatchMetrics"]["counts"])
        self.assertAlmostEqual(0.5, result["binaryDispatchMetrics"]["precision"])
        self.assertAlmostEqual(1 / 3, result["binaryDispatchMetrics"]["recall"])
        self.assertEqual(2, result["abstentionMetrics"]["count"])
        self.assertEqual(1, result["abstentionMetrics"]["unexpectedOwnerSkipCount"])

    def test_expected_hold_and_skip_are_route_only_truth(self):
        evaluator = load_evaluator()
        rows = route_rows() + [
            {"scenarioId":"expected-hold","expectedFailureClass":"none","observedFailureClass":"none","expectedPostProbeAction":"HOLD","observedPostProbeAction":"CONTINUE","labelProvenance":"sealed"},
            {"scenarioId":"expected-skip","expectedFailureClass":"none","observedFailureClass":"none","expectedPostProbeAction":"SKIP_OWNER_CONTROLLED","observedPostProbeAction":"DISPATCH_PAIR","labelProvenance":"sealed"},
        ]
        result = evaluator.evaluate(valid_route_manifest(rows), rows)
        self.assertEqual(6, result["binaryDispatchMetrics"]["eligibleCount"])
        self.assertEqual(8, result["routeConfusion"]["total"])
        self.assertEqual(1, result["routeConfusion"]["matrix"]["HOLD"]["CONTINUE"])
        self.assertEqual(1, result["routeConfusion"]["matrix"]["SKIP_OWNER_CONTROLLED"]["DISPATCH_PAIR"])

    def test_invalid_terminal_probe_is_rejected(self):
        evaluator = load_evaluator()
        rows = route_rows()
        rows[0]["observedPostProbeAction"] = "PROBE"
        self.assert_input_error(
            evaluator, "invalid-post-probe-action", valid_route_manifest(rows), rows
        )

    def test_duplicate_scenario_id_is_rejected(self):
        evaluator = load_evaluator()
        rows = route_rows()
        rows[1]["scenarioId"] = rows[0]["scenarioId"]
        self.assert_input_error(
            evaluator, "duplicate-scenario-id", valid_route_manifest(rows), rows
        )

    def test_exact_schema_rejects_unknown_manifest_and_row_keys(self):
        evaluator = load_evaluator()
        manifest = valid_route_manifest(route_rows())
        manifest["unexpected"] = "value"
        self.assert_input_error(evaluator, "schema-invalid", manifest, route_rows())

        rows = route_rows()
        rows[0]["unexpected"] = "value"
        self.assert_input_error(
            evaluator, "schema-invalid", valid_route_manifest(rows), rows
        )

    def test_non_finite_scores_and_bool_as_number_are_rejected(self):
        evaluator = load_evaluator()
        for value, reason in (
            (math.nan, "non-finite-number"),
            (math.inf, "non-finite-number"),
            (True, "bool-as-number"),
        ):
            with self.subTest(value=value, reason=reason):
                rows = route_rows()
                rows[0]["detectorScore"] = value
                self.assert_input_error(
                    evaluator, reason, valid_route_manifest(rows), rows
                )

    def test_invalid_sha_and_canonical_fixture_mismatch_are_distinct(self):
        evaluator = load_evaluator()
        manifest = valid_route_manifest(route_rows())
        manifest["fixtureSha256"] = "bad"
        self.assert_input_error(
            evaluator, "invalid-sha256", manifest, route_rows()
        )

        manifest = valid_route_manifest(route_rows())
        manifest["fixtureSha256"] = "0" * 64
        self.assert_input_error(
            evaluator, "fixture-sha256-mismatch", manifest, route_rows()
        )

    def test_secret_like_keys_and_values_are_rejected_without_reflection(self):
        evaluator = load_evaluator()
        marker = "sk-"" ""private-marker-never-reflect"
        for manifest_change, rows_change in (
            ({"apiKey": marker}, None),
            (None, {"labelProvenance": marker}),
        ):
            with self.subTest(manifest_change=manifest_change is not None):
                rows = route_rows()
                manifest = valid_route_manifest(rows)
                if manifest_change:
                    manifest.update(manifest_change)
                if rows_change:
                    rows[0].update(rows_change)
                    manifest = valid_route_manifest(rows)
                with self.assertRaises(evaluator.EvaluationInputError) as caught:
                    evaluator.evaluate(manifest, rows)
                self.assertEqual("unsafe-secret-like-content", caught.exception.reason_code)
                self.assertNotIn(marker, str(caught.exception))

    def test_zero_denominators_are_null_with_stable_reason_codes(self):
        evaluator = load_evaluator()
        rows = [{
            "scenarioId":"route-only",
            "expectedFailureClass":"none",
            "observedFailureClass":"none",
            "expectedPostProbeAction":"HOLD",
            "observedPostProbeAction":"HOLD",
            "labelProvenance":"sealed",
        }]
        result = evaluator.evaluate(valid_route_manifest(rows), rows)
        binary = result["binaryDispatchMetrics"]
        self.assertIsNone(binary["precision"])
        self.assertIsNone(binary["recall"])
        self.assertIsNone(binary["f1"])
        self.assertIsNone(binary["fpr"])
        self.assertEqual("zero-predicted-positive", binary["reasonCodes"]["precision"])
        self.assertEqual("zero-actual-positive", binary["reasonCodes"]["recall"])
        self.assertEqual("zero-eligible-truth", binary["reasonCodes"]["prevalence"])
        self.assertIsNone(result["abstentionMetrics"]["coverage"])

    def test_route_confusion_is_complete_for_allowed_actions(self):
        evaluator = load_evaluator()
        result = evaluator.evaluate(valid_route_manifest(route_rows()), route_rows())
        matrix = result["routeConfusion"]["matrix"]
        actions = ["CONTINUE", "DISPATCH_PAIR", "HOLD", "SKIP_OWNER_CONTROLLED"]
        self.assertEqual(actions, list(matrix))
        for expected in actions:
            self.assertEqual(actions, list(matrix[expected]))
        self.assertEqual(6, sum(sum(row.values()) for row in matrix.values()))

    def test_failure_class_metrics_cover_macro_weighted_micro_and_zero_support(self):
        evaluator = load_evaluator()
        manifest = valid_route_manifest(route_rows())
        manifest["allowedFailureClasses"].append("unseen")
        result = evaluator.evaluate(manifest, route_rows())
        metrics = result["failureClassConfusion"]
        self.assertAlmostEqual(2 / 3, metrics["accuracy"])
        self.assertEqual(0, metrics["perClass"]["unseen"]["support"])
        self.assertIsNone(metrics["perClass"]["unseen"]["recall"])
        self.assertEqual(1, metrics["macro"]["excludedZeroSupportCount"])
        self.assertAlmostEqual(13 / 18, metrics["macro"]["precision"])
        self.assertAlmostEqual(13 / 18, metrics["macro"]["recall"])
        self.assertAlmostEqual(2 / 3, metrics["macro"]["f1"])
        self.assertAlmostEqual(0.75, metrics["weighted"]["precision"])
        self.assertAlmostEqual(2 / 3, metrics["weighted"]["recall"])
        self.assertAlmostEqual(2 / 3, metrics["weighted"]["f1"])
        self.assertAlmostEqual(2 / 3, metrics["micro"]["precision"])
        self.assertAlmostEqual(2 / 3, metrics["micro"]["recall"])
        self.assertAlmostEqual(2 / 3, metrics["micro"]["f1"])
        self.assertNotIn("tn", metrics["perClass"]["none"])
        self.assertNotIn("fpr", metrics["perClass"]["none"])

    def test_canonical_bytes_are_deterministic_and_finite_json(self):
        evaluator = load_evaluator()
        result = evaluator.evaluate(valid_route_manifest(route_rows()), route_rows())
        first = evaluator.canonical_json_bytes(result)
        second = evaluator.canonical_json_bytes(copy.deepcopy(result))
        self.assertEqual(first, second)
        self.assertFalse(first.endswith(b"\n"))
        self.assertEqual(result, json.loads(first.decode("utf-8")))

    def test_paired_mode_is_recognized_but_deferred(self):
        evaluator = load_evaluator()
        manifest = valid_route_manifest([])
        manifest["evaluationMode"] = "PAIRED_EVAL"
        manifest["fixtureSha256"] = hashlib.sha256(b"[]").hexdigest()
        self.assert_input_error(evaluator, "paired-evaluation-deferred", manifest, [])

    def test_resource_limits_and_iterative_depth_guard_are_exact(self):
        evaluator = load_evaluator()
        self.assertEqual(131_072, evaluator.MANIFEST_LIMIT_BYTES)
        self.assertEqual(2_097_152, evaluator.ROWS_LIMIT_BYTES)
        self.assertEqual(1_048_576, evaluator.OUTPUT_LIMIT_BYTES)
        self.assertEqual(10_000, evaluator.MAX_ROWS)
        self.assertEqual(24, evaluator.MAX_DEPTH)
        self.assertEqual(100_000, evaluator.MAX_NODES)
        manifest = valid_route_manifest(route_rows())
        nested = "leaf"
        for _ in range(26):
            nested = [nested]
        manifest["costPolicy"] = nested
        self.assert_input_error(
            evaluator, "structure-depth-exceeded", manifest, route_rows()
        )

    def test_cli_success_is_bounded_canonical_and_single_line(self):
        evaluator = load_evaluator()
        with tempfile.TemporaryDirectory() as raw_dir:
            directory = Path(raw_dir)
            manifest_path, rows_path = write_cli_fixture(
                directory, valid_route_manifest(route_rows()), route_rows()
            )
            completed = subprocess.run(
                [sys.executable, "-X", "utf8", str(EVALUATOR_PATH),
                 "--manifest", str(manifest_path), "--rows", str(rows_path)],
                capture_output=True, check=False,
            )
        self.assertEqual(0, completed.returncode)
        self.assertEqual(b"", completed.stderr)
        self.assertEqual(1, completed.stdout.count(b"\n"))
        self.assertLessEqual(len(completed.stdout), evaluator.OUTPUT_LIMIT_BYTES)
        parsed = json.loads(completed.stdout.decode("utf-8"))
        self.assertEqual("OK", parsed["status"])
        self.assertEqual(
            evaluator.canonical_json_bytes(parsed) + b"\n", completed.stdout
        )

    def test_cli_malformed_unsafe_and_oversize_inputs_exit_two_without_reflection(self):
        evaluator = load_evaluator()
        marker = "sk-"" ""cli-private-marker-never-reflect"
        with tempfile.TemporaryDirectory() as raw_dir:
            directory = Path(raw_dir)
            manifest_path, rows_path = write_cli_fixture(
                directory, valid_route_manifest(route_rows()), route_rows()
            )
            cases = []
            malformed = directory / "malformed.json"
            malformed.write_text("{", encoding="utf-8")
            cases.append((malformed, rows_path, "invalid-json"))
            unsafe_rows = directory / "unsafe.jsonl"
            unsafe_row = route_rows()[0]
            unsafe_row["labelProvenance"] = marker
            unsafe_rows.write_text(json.dumps(unsafe_row) + "\n", encoding="utf-8")
            unsafe_manifest = valid_route_manifest([unsafe_row])
            unsafe_manifest_path = directory / "unsafe-manifest.json"
            unsafe_manifest_path.write_text(json.dumps(unsafe_manifest), encoding="utf-8")
            cases.append((unsafe_manifest_path, unsafe_rows, "unsafe-secret-like-content"))
            oversized = directory / "oversized.json"
            oversized.write_bytes(b" " * (evaluator.MANIFEST_LIMIT_BYTES + 1))
            cases.append((oversized, rows_path, "manifest-too-large"))

            for current_manifest, current_rows, reason in cases:
                with self.subTest(reason=reason):
                    completed = subprocess.run(
                        [sys.executable, "-X", "utf8", str(EVALUATOR_PATH),
                         "--manifest", str(current_manifest), "--rows", str(current_rows)],
                        capture_output=True, check=False,
                    )
                    self.assertEqual(2, completed.returncode)
                    self.assertEqual(b"", completed.stderr)
                    self.assertEqual(
                        {"reasonCode": reason, "status": "HOLD"},
                        json.loads(completed.stdout.decode("utf-8")),
                    )
                    self.assertNotIn(marker.encode("utf-8"), completed.stdout)
                    self.assertNotIn(str(current_manifest).encode("utf-8"), completed.stdout)

    def test_cli_internal_failure_exits_three_without_native_exception(self):
        evaluator = load_evaluator()
        marker = "internal-private-marker-never-reflect"
        with tempfile.TemporaryDirectory() as raw_dir:
            directory = Path(raw_dir)
            manifest_path, rows_path = write_cli_fixture(
                directory, valid_route_manifest(route_rows()), route_rows()
            )
            stdout = io.StringIO()
            stderr = io.StringIO()
            with mock.patch.object(evaluator, "evaluate", side_effect=RuntimeError(marker)):
                with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    exit_code = evaluator.main([
                        "--manifest", str(manifest_path), "--rows", str(rows_path)
                    ])
        self.assertEqual(3, exit_code)
        self.assertEqual("", stderr.getvalue())
        self.assertEqual(
            {"reasonCode": "internal-evaluation-failure", "status": "HOLD"},
            json.loads(stdout.getvalue()),
        )
        self.assertNotIn(marker, stdout.getvalue())
        self.assertNotIn("Traceback", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
