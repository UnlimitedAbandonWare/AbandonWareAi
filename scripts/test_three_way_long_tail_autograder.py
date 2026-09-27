"""Behavior tests for the bounded three-way long-tail structural grader."""

from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from score_three_way_long_tail_query import INPUT_LIMIT_BYTES, grade_payload


def base_payload():
    evidence_rows = [
        {
            "evidenceId": "E-REPO-1", "directness": "direct", "authority": "authoritative",
            "freshness": "current", "independenceGroup": "repo-filesystem",
            "verificationAction": "Get-FileHash declared prompt files",
        },
        {
            "evidenceId": "E-DOC-1", "directness": "direct", "authority": "authoritative",
            "freshness": "current", "independenceGroup": "official-docs",
            "verificationAction": "Open the cited official document",
        },
    ]
    worlds = [
        {
            "scenarioId": "W1", "premise": "The three branches use one frozen snapshot",
            "causalMechanism": "Shared evidence prevents branch-specific fact invention",
            "expectedObservation": "All packets carry one evidenceSnapshotHash",
            "evidenceNeeded": ["E-REPO-1"], "falsifier": "Any packet carries another snapshot hash",
            "baseRateStatus": "unknown",
        },
        {
            "scenarioId": "W2", "premise": "Negative coverage exposes optimistic omissions",
            "causalMechanism": "Every positive world receives a disconfirming probe",
            "expectedObservation": "scenarioAttacks covers W1 and W2",
            "evidenceNeeded": ["E-DOC-1"], "falsifier": "A positive scenario has no attack row",
            "baseRateStatus": "unknown",
        },
    ]
    return {
        "schemaVersion": "awx.three-way-long-tail.eval.v1",
        "rubricVersion": "awx.three-way-long-tail.rubric.v1",
        "fixtureDeckHash": "sha256:" + "a" * 64, "caseCount": 15,
        "evidenceSnapshot": {"evidenceSnapshotHash": "sha256:" + "b" * 64,
                             "decisionDependsOnSupabase": False, "evidenceRows": evidence_rows},
        "positivePacket": {"packetType": "POSITIVE_QUERY", "evidenceSnapshotHash": "sha256:" + "b" * 64,
                           "candidateGoal": "Adopt the bounded three-query artifact contract",
                           "scenarioWorlds": worlds,
                           "noneOrUnknown": {"present": True, "reason": "Insufficient runtime proof remains possible"},
                           "validatedAssumptions": ["The prompt files are available for hash verification"],
                           "reusableAssets": ["The existing offline grader"],
                           "expectedUserValue": "More auditable evidence coverage",
                           "minimalVerification": "Run the focused grader tests",
                           "evidenceIds": ["E-REPO-1", "E-DOC-1"],
                           "unknowns": ["Live provider lineage is not available offline"],
                           "claims": [{"claimId": "P1", "text": "All branches share one frozen snapshot", "evidenceIds": ["E-REPO-1"]}]},
        "negativePacket": {"packetType": "NEGATIVE_QUERY", "evidenceSnapshotHash": "sha256:" + "b" * 64,
                           "challengedGoal": "Adopt only if every scenario is attacked",
                           "scenarioAttacks": [
                               {"scenarioId": "W1", "counterexample": "A reused stale hash could look consistent",
                                "alternativeCause": "Prompt wording alone may explain the result",
                                "boundaryOrAuthorityRisk": "The offline artifact cannot authorize source mutation",
                                "costAndBlastRadius": "A false APPLY could widen the review surface",
                                "smallestDisconfirmingProbe": "Compare frozen packet hashes", "evidenceIds": ["E-REPO-1"]},
                               {"scenarioId": "W2", "counterexample": "Attack coverage can exist without live accuracy",
                                "alternativeCause": "More tokens rather than better evidence may explain the score",
                                "boundaryOrAuthorityRisk": "Runtime ownership remains external",
                                "costAndBlastRadius": "Unbounded follow-up would waste provider calls",
                                "smallestDisconfirmingProbe": "Compare evidence coverage at equal claim count", "evidenceIds": ["E-DOC-1"]},
                           ],
                           "falsifiers": ["Any scenario lacks a disconfirming probe"],
                           "missingEvidence": ["Trusted runtime lineage"],
                           "safetyRisks": ["Artifact evidence could be confused with runtime evidence"],
                           "claims": [{"claimId": "N1", "text": "Equal claim counts do not prove equal evidence quality", "evidenceIds": ["E-DOC-1"]}]},
        "neutralPacket": {"packetType": "NEUTRAL_QUERY", "evidenceSnapshotHash": "sha256:" + "b" * 64,
                           "forwardOrder": ["POSITIVE_QUERY", "NEGATIVE_QUERY"],
                           "reverseOrder": ["NEGATIVE_QUERY", "POSITIVE_QUERY"],
                           "forwardVerdict": "APPLY", "reverseVerdict": "APPLY",
                           "forwardDecisiveEvidenceIds": ["E-REPO-1", "E-DOC-1"],
                           "reverseDecisiveEvidenceIds": ["E-DOC-1", "E-REPO-1"], "orderStable": True,
                           "verdict": "APPLY",
                           "selectedOrRewrittenGoal": "Adopt the bounded artifact contract without a runtime claim",
                           "goalScore": 100.0,
                           "decisiveEvidence": ["E-REPO-1", "E-DOC-1"],
                           "rejectedClaims": ["An artifact score proves live runtime improvement"],
                           "nextSingleProof": {"action": "Run the focused prompt tests", "decisionChange": "A failure changes APPLY to HOLD"},
                           "confidence": "H",
                           "claims": [{"claimId": "J1", "text": "The artifact is acceptable while runtime lineage remains unproven", "evidenceIds": ["E-REPO-1", "E-DOC-1"]}],
                           "artifactVerdict": "APPLY", "runtimeLineageVerdict": "HOLD"},
    }


def baseline_for(payload, *, rubric_version=None, fixture_deck_hash=None, case_count=12,
                 total_score=89.0, evidence_coverage=0.9, branch_independence=1.0):
    """Hand-authored comparable baseline; a wrong threshold branch must fail these tests."""
    normalized = {
        "evidenceCoverage": evidence_coverage,
        "evidenceDirectnessAuthority": 1.0,
        "falsifiability": 1.0,
        "causalDiscrimination": 1.0,
        "longTailScenarioCoverage": 1.0,
        "branchIndependence": branch_independence,
        "orderStability": 1.0,
        "boundednessAndRedaction": 1.0,
        "nextProofActionability": 1.0,
    }
    return {
        "rubricVersion": rubric_version or payload["rubricVersion"],
        "fixtureDeckHash": fixture_deck_hash or payload["fixtureDeckHash"],
        "caseCount": case_count,
        "totalScore": total_score,
        "dimensions": {name: {"normalized": value} for name, value in normalized.items()},
    }


class GradePayloadTests(unittest.TestCase):
    def grade(self, mutate=None, size=1000):
        payload = base_payload()
        if mutate:
            mutate(payload)
        return grade_payload(payload, size)

    def test_accepts_complete_structural_artifact_despite_runtime_hold(self):
        result = self.grade()
        self.assertEqual([], result["hardGates"]["artifact"])
        self.assertEqual(["runtime-lineage-missing"], result["hardGates"]["runtime"])
        self.assertEqual("APPLY", result["artifactVerdict"])
        self.assertEqual("HOLD", result["runtimeLineageVerdict"])

    def test_runtime_apply_is_not_self_assertable_by_the_untrusted_packet(self):
        result = self.grade(lambda p: p["neutralPacket"].__setitem__("runtimeLineageVerdict", "APPLY"))
        self.assertEqual(["runtime-lineage-missing"], result["hardGates"]["runtime"])
        self.assertEqual("HOLD", result["runtimeLineageVerdict"])
        self.assertEqual("APPLY", result["artifactVerdict"])

    def test_invalid_verdict_enums_fail_closed_in_every_neutral_verdict_field(self):
        for field in ("forwardVerdict", "reverseVerdict", "verdict", "artifactVerdict", "runtimeLineageVerdict"):
            with self.subTest(field=field):
                result = self.grade(lambda p, field=field: p["neutralPacket"].__setitem__(field, "BANANA"))
                self.assertIn("packet-role-mismatch", result["hardGates"]["artifact"])
                self.assertEqual("HOLD", result["artifactVerdict"])

    def test_top_level_allowlist_rejects_undeclared_branch_or_key(self):
        for field in ("optimisticBranch", "fourthQuery", "debugMetadata"):
            with self.subTest(field=field):
                result = self.grade(lambda p, field=field: p.__setitem__(field, {"claims": []}))
                self.assertIn("branch-count-invalid", result["hardGates"]["artifact"])
                self.assertEqual("HOLD", result["artifactVerdict"])

    def test_exact_raw_keys_are_prohibited_without_echo_but_hash_keys_are_allowed(self):
        cases = (
            ("rawPrompt", "positivePacket"),
            ("raw_query", "negativePacket"),
            ("Cookie", "neutralPacket"),
            ("full-environment-dump", "positivePacket"),
        )
        for key, packet_name in cases:
            with self.subTest(key=key):
                marker = f"private-{key}-marker"
                result = self.grade(lambda p, key=key, packet_name=packet_name, marker=marker:
                                    p[packet_name].__setitem__(key, marker))
                self.assertIn("secret-pattern-risk", result["hardGates"]["artifact"])
                self.assertNotIn(marker, repr(result))

        result = self.grade(lambda p: p["evidenceSnapshot"]["evidenceRows"][0].__setitem__(
            "responseHash", "sha256:" + "d" * 64))
        self.assertEqual([], result["hardGates"]["artifact"])

    def test_packet_and_nested_schemas_require_exact_fields_and_types(self):
        missing_cases = (
            ("positivePacket", "candidateGoal"),
            ("positivePacket", "validatedAssumptions"),
            ("negativePacket", "challengedGoal"),
            ("negativePacket", "missingEvidence"),
            ("neutralPacket", "forwardOrder"),
            ("neutralPacket", "selectedOrRewrittenGoal"),
            ("neutralPacket", "confidence"),
        )
        for packet_name, field in missing_cases:
            with self.subTest(packet=packet_name, missing=field):
                result = self.grade(lambda p, packet_name=packet_name, field=field: p[packet_name].pop(field))
                self.assertIn("packet-role-mismatch", result["hardGates"]["artifact"])

        for packet_name in ("positivePacket", "negativePacket", "neutralPacket"):
            with self.subTest(packet=packet_name, extra="unexpectedField"):
                result = self.grade(lambda p, packet_name=packet_name:
                                    p[packet_name].__setitem__("unexpectedField", "benign"))
                self.assertIn("packet-role-mismatch", result["hardGates"]["artifact"])

        nested_cases = (
            ("positivePacket", "scenarioWorlds", "premise"),
            ("positivePacket", "scenarioWorlds", "evidenceNeeded"),
            ("negativePacket", "scenarioAttacks", "counterexample"),
            ("negativePacket", "scenarioAttacks", "boundaryOrAuthorityRisk"),
            ("negativePacket", "scenarioAttacks", "costAndBlastRadius"),
        )
        for packet_name, collection, field in nested_cases:
            with self.subTest(collection=collection, missing=field):
                result = self.grade(lambda p, packet_name=packet_name, collection=collection, field=field:
                                    p[packet_name][collection][0].pop(field))
                self.assertIn("packet-role-mismatch", result["hardGates"]["artifact"])

        result = self.grade(lambda p: p["positivePacket"]["scenarioWorlds"][0].__setitem__(
            "unexpectedField", "benign"))
        self.assertIn("packet-role-mismatch", result["hardGates"]["artifact"])

    def test_claim_snapshot_and_hash_contracts_are_exact(self):
        result = self.grade(lambda p: p["positivePacket"]["claims"][0].__setitem__("extra", True))
        self.assertIn("packet-role-mismatch", result["hardGates"]["artifact"])

        result = self.grade(lambda p: p["evidenceSnapshot"].pop("evidenceRows"))
        self.assertIn("evidence-snapshot-invalid", result["hardGates"]["artifact"])

        result = self.grade(lambda p: p["positivePacket"].__setitem__("evidenceSnapshotHash", 7))
        self.assertIn("snapshot-hash-mismatch", result["hardGates"]["artifact"])

    def test_neutral_orders_verdict_goal_score_confidence_and_next_proof_are_validated(self):
        mutations = (
            (lambda p: p["neutralPacket"].__setitem__("forwardOrder", ["NEGATIVE_QUERY", "POSITIVE_QUERY"]),
             "packet-role-mismatch"),
            (lambda p: p["neutralPacket"].__setitem__("verdict", "HOLD"), "order-unstable"),
            (lambda p: p["neutralPacket"].__setitem__("goalScore", float("nan")), "packet-role-mismatch"),
            (lambda p: p["neutralPacket"].__setitem__("goalScore", 100.01), "packet-role-mismatch"),
            (lambda p: p["neutralPacket"].__setitem__("confidence", "HIGH"), "packet-role-mismatch"),
            (lambda p: p["neutralPacket"].__setitem__("nextSingleProof", {"action": "Run tests"}),
             "packet-role-mismatch"),
        )
        for mutate, reason in mutations:
            with self.subTest(mutate=mutate):
                result = self.grade(mutate)
                self.assertIn(reason, result["hardGates"]["artifact"])
                self.assertEqual("HOLD", result["artifactVerdict"])
                json.dumps(result, allow_nan=False)

    def test_authority_expansion_and_invalid_evidence_authority_fail_closed(self):
        for key in ("authorizedMutationSurface", "source_owner", "writeAllowed"):
            with self.subTest(key=key):
                result = self.grade(lambda p, key=key: p["positivePacket"].__setitem__(key, "application-source"))
                self.assertIn("authority-expansion", result["hardGates"]["artifact"])
                self.assertEqual("HOLD", result["artifactVerdict"])

        result = self.grade(lambda p: p["evidenceSnapshot"]["evidenceRows"][0].__setitem__("authority", "root"))
        self.assertIn("authority-expansion", result["hardGates"]["artifact"])
        self.assertEqual("HOLD", result["artifactVerdict"])

    def test_complete_artifact_emits_a_100_point_auditable_rubric(self):
        result = self.grade()
        dimensions = result["dimensions"]
        self.assertEqual(100.0, result["totalScore"])
        self.assertEqual(
            {
                "evidenceCoverage": 20,
                "evidenceDirectnessAuthority": 15,
                "falsifiability": 15,
                "causalDiscrimination": 15,
                "longTailScenarioCoverage": 10,
                "branchIndependence": 10,
                "orderStability": 5,
                "boundednessAndRedaction": 5,
                "nextProofActionability": 5,
            },
            {name: dimension["weight"] for name, dimension in dimensions.items()},
        )
        self.assertTrue(result["metricDefinition"]["bounded"])
        self.assertEqual(1.0, dimensions["evidenceCoverage"]["normalized"])
        self.assertEqual(1.0, dimensions["evidenceDirectnessAuthority"]["normalized"])
        self.assertEqual(1.0, dimensions["branchIndependence"]["normalized"])
        self.assertNotIn("normalizedClaimText", json.dumps(result, sort_keys=True))

    def test_unresolved_claim_evidence_lowers_coverage_metric(self):
        result = self.grade(lambda p: p["positivePacket"]["claims"][0].__setitem__("evidenceIds", ["E-UNKNOWN"]))
        self.assertLess(result["dimensions"]["evidenceCoverage"]["normalized"], 1.0)

    def test_missing_alternative_cause_lowers_causal_discrimination(self):
        result = self.grade(lambda p: p["negativePacket"]["scenarioAttacks"][0].__setitem__("alternativeCause", ""))
        self.assertEqual(0.5, result["dimensions"]["causalDiscrimination"]["normalized"])

    def test_duplicate_normalized_claim_text_lowers_branch_independence_without_emitting_it(self):
        def mutate(payload):
            payload["negativePacket"]["claims"][0]["text"] = "  ALL branches share one frozen snapshot  "
        result = self.grade(mutate)
        self.assertAlmostEqual(2 / 3, result["dimensions"]["branchIndependence"]["normalized"], places=6)
        self.assertNotIn("normalizedClaimText", json.dumps(result, sort_keys=True))

    def test_order_instability_sets_metric_to_zero_and_holds_artifact(self):
        result = self.grade(lambda p: p["neutralPacket"].__setitem__("reverseVerdict", "HOLD"))
        self.assertEqual(0.0, result["dimensions"]["orderStability"]["normalized"])
        self.assertEqual("HOLD", result["artifactVerdict"])

    def test_uplift_is_none_without_a_comparable_baseline(self):
        result = self.grade()
        self.assertIsNone(result["qualityUpliftCandidate"])
        self.assertEqual("baseline-absent", result["qualityUplift"]["reason"])

    def test_uplift_requires_matching_baseline_version_and_fixture(self):
        def mutate(payload):
            payload["baseline"] = baseline_for(payload, rubric_version="awx.three-way-long-tail.rubric.v0")
        result = self.grade(mutate)
        self.assertIsNone(result["qualityUpliftCandidate"])
        self.assertEqual("baseline-incomparable", result["qualityUplift"]["reason"])

    def test_absent_or_incomparable_baseline_emits_all_not_evaluated_thresholds(self):
        for mutate in (
            None,
            lambda payload: payload.__setitem__("baseline", baseline_for(payload, fixture_deck_hash="sha256:" + "c" * 64)),
        ):
            result = self.grade(mutate)
            comparisons = result["qualityUplift"]["comparisons"]
            self.assertEqual(
                {"minimumCaseCount", "minimumCandidateScore", "minimumTotalDelta",
                 "minimumEvidenceCoverageDelta", "maximumSingleMetricRegression"},
                set(comparisons),
            )
            for comparison in comparisons.values():
                self.assertIsNone(comparison["actual"])
                self.assertIsNone(comparison["passed"])
                self.assertEqual("notEvaluated", comparison["status"])

    def test_invalid_baseline_numeric_values_are_incomparable_and_cannot_emit_nonstandard_json(self):
        cases = [
            ("totalScore", float("nan")), ("totalScore", float("inf")), ("totalScore", -0.01),
            ("totalScore", 100.01), ("dimensions.evidenceCoverage.normalized", float("nan")),
            ("dimensions.evidenceCoverage.normalized", float("-inf")),
            ("dimensions.evidenceCoverage.normalized", -0.01),
            ("dimensions.evidenceCoverage.normalized", 1.01),
            ("dimensions.evidenceCoverage.normalized", "not-a-number"),
        ]
        for field, value in cases:
            with self.subTest(field=field, value=repr(value)):
                def mutate(payload, field=field, value=value):
                    payload["baseline"] = baseline_for(payload)
                    if field == "totalScore":
                        payload["baseline"][field] = value
                    else:
                        payload["baseline"]["dimensions"]["evidenceCoverage"]["normalized"] = value
                result = self.grade(mutate)
                self.assertIsNone(result["qualityUpliftCandidate"])
                self.assertEqual("baseline-incomparable", result["qualityUplift"]["reason"])
                json.dumps(result, allow_nan=False)

    def test_uplift_requires_at_least_twelve_cases_in_both_candidate_and_baseline(self):
        def mutate(payload):
            payload["baseline"] = baseline_for(payload, case_count=11, total_score=90.0, evidence_coverage=0.9)
        result = self.grade(mutate)
        comparison = result["qualityUplift"]["comparisons"]["minimumCaseCount"]
        self.assertFalse(result["qualityUpliftCandidate"])
        self.assertEqual(15, comparison["candidateActual"])
        self.assertEqual(11, comparison["baselineActual"])
        self.assertFalse(comparison["passed"])

    def test_uplift_uses_raw_total_delta_before_rounding_display(self):
        def mutate(payload):
            payload["baseline"] = baseline_for(payload, total_score=90.0000004, evidence_coverage=0.9)
        result = self.grade(mutate)
        comparison = result["qualityUplift"]["comparisons"]["minimumTotalDelta"]
        self.assertFalse(result["qualityUpliftCandidate"])
        self.assertFalse(comparison["passed"])
        self.assertEqual(10.0, comparison["displayActual"])

    def test_uplift_uses_raw_coverage_delta_before_rounding_display(self):
        def mutate(payload):
            payload["baseline"] = baseline_for(payload, total_score=90.0, evidence_coverage=0.9000004)
        result = self.grade(mutate)
        comparison = result["qualityUplift"]["comparisons"]["minimumEvidenceCoverageDelta"]
        self.assertFalse(result["qualityUpliftCandidate"])
        self.assertFalse(comparison["passed"])
        self.assertEqual(0.1, comparison["displayActual"])

    def test_uplift_uses_raw_candidate_total_when_display_total_rounds_up_to_threshold(self):
        def mutate(payload):
            payload["evidenceSnapshot"]["evidenceRows"][1]["authority"] = "official_primary"
            for index in range(2, 128):
                payload["evidenceSnapshot"]["evidenceRows"].append({
                    "evidenceId": f"E-ROW-{index}",
                    "directness": "direct",
                    "authority": "authoritative",
                    "freshness": "current",
                    "independenceGroup": f"group-{index}",
                    "verificationAction": f"Verify row {index}",
                })
            payload["positivePacket"]["claims"][0]["evidenceIds"] = [
                row["evidenceId"] for row in payload["evidenceSnapshot"]["evidenceRows"]
            ]
            payload["baseline"] = baseline_for(payload, total_score=90.0, evidence_coverage=0.9)

        result = self.grade(mutate)
        comparison = result["qualityUplift"]["comparisons"]["minimumTotalDelta"]
        self.assertEqual(100.0, result["totalScore"])
        self.assertAlmostEqual(9.99609375, comparison["actual"], places=10)
        self.assertEqual(9.996094, comparison["displayActual"])
        self.assertFalse(comparison["passed"])
        self.assertFalse(result["qualityUpliftCandidate"])

    def test_uplift_uses_raw_dimension_for_max_regression_boundary(self):
        def mutate(payload):
            payload["negativePacket"]["claims"][0]["text"] = " all branches share one frozen snapshot "
            payload["baseline"] = baseline_for(
                payload,
                total_score=85.0,
                evidence_coverage=0.9,
                branch_independence=(2 / 3) + 0.0500002,
            )

        result = self.grade(mutate)
        comparison = result["qualityUplift"]["comparisons"]["maximumSingleMetricRegression"]
        self.assertGreater(comparison["actual"], 0.05)
        self.assertEqual(0.05, comparison["displayActual"])
        self.assertFalse(comparison["passed"])
        self.assertFalse(result["qualityUpliftCandidate"])

    def test_uplift_threshold_records_preserve_raw_deltas_while_rounding_only_display(self):
        cases = [
            ("minimumTotalDelta", {"total_score": 90.0000004, "evidence_coverage": 0.9}, 9.9999996, 10.0),
            ("minimumEvidenceCoverageDelta", {"total_score": 90.0, "evidence_coverage": 0.9000004}, 0.0999996, 0.1),
        ]
        for name, baseline_args, expected_raw, expected_display in cases:
            with self.subTest(name=name):
                result = self.grade(lambda payload, baseline_args=baseline_args: payload.__setitem__(
                    "baseline", baseline_for(payload, **baseline_args)))
                comparison = result["qualityUplift"]["comparisons"][name]
                self.assertAlmostEqual(expected_raw, comparison["actual"], places=13)
                self.assertEqual(expected_display, comparison["displayActual"])
                self.assertFalse(comparison["passed"])

    def test_uplift_rejects_fewer_than_twelve_cases(self):
        def mutate(payload):
            payload["caseCount"] = 11
            payload["baseline"] = baseline_for(payload)
        result = self.grade(mutate)
        self.assertFalse(result["qualityUpliftCandidate"])
        self.assertFalse(result["qualityUplift"]["comparisons"]["minimumCaseCount"]["passed"])

    def test_uplift_rejects_total_delta_below_ten_points(self):
        def mutate(payload):
            payload["baseline"] = baseline_for(payload, total_score=90.01)
        result = self.grade(mutate)
        self.assertFalse(result["qualityUpliftCandidate"])
        self.assertFalse(result["qualityUplift"]["comparisons"]["minimumTotalDelta"]["passed"])

    def test_uplift_rejects_evidence_coverage_delta_below_ten_percent(self):
        def mutate(payload):
            payload["baseline"] = baseline_for(payload, evidence_coverage=0.91)
        result = self.grade(mutate)
        self.assertFalse(result["qualityUpliftCandidate"])
        self.assertFalse(result["qualityUplift"]["comparisons"]["minimumEvidenceCoverageDelta"]["passed"])

    def test_uplift_rejects_a_single_metric_regression_above_five_percent(self):
        def mutate(payload):
            payload["negativePacket"]["claims"][0]["text"] = " all branches share one frozen snapshot "
            payload["baseline"] = baseline_for(payload, total_score=85.0, evidence_coverage=0.9,
                                               branch_independence=0.75)
        result = self.grade(mutate)
        self.assertFalse(result["qualityUpliftCandidate"])
        self.assertFalse(result["qualityUplift"]["comparisons"]["maximumSingleMetricRegression"]["passed"])

    def test_uplift_is_true_only_for_a_comparable_threshold_passing_artifact(self):
        def mutate(payload):
            payload["baseline"] = baseline_for(payload, total_score=90.0, evidence_coverage=0.8999)
        result = self.grade(mutate)
        self.assertTrue(result["qualityUpliftCandidate"])
        self.assertEqual("artifact-quality-candidate-not-runtime-improvement", result["qualityUplift"]["scope"])

    def test_repeated_grading_is_byte_identical(self):
        first = json.dumps(self.grade(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        second = json.dumps(self.grade(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        self.assertEqual(first, second)

    def test_rejects_a_fourth_packet_object(self):
        result = self.grade(lambda p: p.__setitem__("fourthPacket", {}))
        self.assertIn("packet-set-invalid", result["hardGates"]["artifact"])

    def test_rejects_wrong_packet_type(self):
        result = self.grade(lambda p: p["positivePacket"].__setitem__("packetType", "NEUTRAL_QUERY"))
        self.assertIn("packet-type-invalid", result["hardGates"]["artifact"])

    def test_rejects_snapshot_mismatch(self):
        result = self.grade(lambda p: p["negativePacket"].__setitem__("evidenceSnapshotHash", "sha256:" + "c" * 64))
        self.assertIn("snapshot-hash-mismatch", result["hardGates"]["artifact"])

    def test_rejects_world_without_falsifier(self):
        result = self.grade(lambda p: p["positivePacket"]["scenarioWorlds"][0].__setitem__("falsifier", ""))
        self.assertIn("world-falsifier-missing", result["hardGates"]["artifact"])

    def test_rejects_negative_coverage_gap(self):
        result = self.grade(lambda p: p["negativePacket"].__setitem__("scenarioAttacks", p["negativePacket"]["scenarioAttacks"][:1]))
        self.assertIn("scenario-coverage-mismatch", result["hardGates"]["artifact"])

    def test_rejects_neutral_only_evidence_id(self):
        result = self.grade(lambda p: p["neutralPacket"].__setitem__("forwardDecisiveEvidenceIds", ["E-UNKNOWN"]))
        self.assertIn("neutral-evidence-unresolved", result["hardGates"]["artifact"])

    def test_rejects_order_instability(self):
        result = self.grade(lambda p: p["neutralPacket"].__setitem__("reverseVerdict", "HOLD"))
        self.assertIn("order-instability", result["hardGates"]["artifact"])

    def test_rejects_unresolved_claim_evidence(self):
        result = self.grade(lambda p: p["positivePacket"]["claims"][0].__setitem__("evidenceIds", ["E-UNKNOWN"]))
        self.assertIn("claim-evidence-unresolved", result["hardGates"]["artifact"])

    def test_rejects_input_over_one_mib(self):
        result = self.grade(size=INPUT_LIMIT_BYTES + 1)
        self.assertIn("input-size-exceeded", result["hardGates"]["artifact"])

    def test_rejects_more_than_256_claims(self):
        result = self.grade(lambda p: p["positivePacket"].__setitem__("claims", p["positivePacket"]["claims"] * 257))
        self.assertIn("claim-count-exceeded", result["hardGates"]["artifact"])

    def test_rejects_secret_like_values_without_echoing_them(self):
        result = self.grade(lambda p: p["positivePacket"].__setitem__("token", "sk-test-secret-value"))
        self.assertIn("secret-like-content", result["hardGates"]["artifact"])
        self.assertNotIn("sk-test-secret-value", repr(result))

    def test_requires_static_supabase_scope_when_decision_depends_on_it(self):
        result = self.grade(lambda p: p["evidenceSnapshot"].__setitem__("decisionDependsOnSupabase", True))
        self.assertIn("supabase-project-ref-missing", result["hardGates"]["artifact"])

    def test_rejects_invalid_none_or_unknown_contract(self):
        result = self.grade(lambda p: p["positivePacket"].__setitem__("noneOrUnknown", {"present": False, "reason": ""}))
        self.assertIn("none-or-unknown-invalid", result["hardGates"]["artifact"])

    def test_rejects_duplicate_or_blank_world_and_attack_ids(self):
        def mutate(payload):
            payload["positivePacket"]["scenarioWorlds"][1]["scenarioId"] = "W1"
            payload["negativePacket"]["scenarioAttacks"][1]["scenarioId"] = ""
        result = self.grade(mutate)
        self.assertIn("scenario-id-duplicate", result["hardGates"]["artifact"])
        self.assertIn("attack-scenario-id-invalid", result["hardGates"]["artifact"])

    def test_rejects_duplicate_attack_ids_even_when_the_attack_id_set_matches_worlds(self):
        def mutate(payload):
            payload["negativePacket"]["scenarioAttacks"].append({
                "scenarioId": "W2", "alternativeCause": "A duplicated attack could mask a missing branch",
                "smallestDisconfirmingProbe": "Count attack IDs", "evidenceIds": ["E-DOC-1"],
            })
        result = self.grade(mutate)
        self.assertIn("attack-scenario-id-duplicate", result["hardGates"]["artifact"])
        self.assertIn("scenario-coverage-mismatch", result["hardGates"]["artifact"])
        self.assertEqual(0.0, result["dimensions"]["longTailScenarioCoverage"]["normalized"])

    def test_rejects_non_list_or_invalid_claim_shapes(self):
        result = self.grade(lambda p: p["positivePacket"].__setitem__("claims", {"claimId": "P1"}))
        self.assertIn("claims-invalid", result["hardGates"]["artifact"])

    def test_rejects_malformed_decisive_evidence_without_crashing(self):
        result = self.grade(lambda p: p["neutralPacket"].__setitem__("forwardDecisiveEvidenceIds", [{"bad": "shape"}]))
        self.assertIn("neutral-evidence-invalid", result["hardGates"]["artifact"])

    def test_rejects_raw_supabase_scope_fields(self):
        def mutate(payload):
            payload["evidenceSnapshot"]["decisionDependsOnSupabase"] = True
            payload["evidenceSnapshot"]["evidenceRows"].append({
                "evidenceId": "E-SB-1", "projectRefHash": "sha256:" + "c" * 64,
                "readOnly": True, "observedAt": "2026-07-30T00:00:00Z", "toolTraceRef": "trace-redacted",
                "projectRef": "raw-project-reference",
            })
        result = self.grade(mutate)
        self.assertIn("supabase-raw-scope-prohibited", result["hardGates"]["artifact"])

    def test_deep_payload_is_bounded_not_internal_failure(self):
        payload = base_payload()
        nested = payload
        for _ in range(80):
            child = {}
            nested["nested"] = child
            nested = child
        result = grade_payload(payload, 1000)
        self.assertIn("structure-depth-exceeded", result["hardGates"]["artifact"])

    def test_deep_structure_cannot_mask_secret_in_reflected_grade_fields(self):
        for field in ("rubricVersion", "fixtureDeckHash"):
            payload = base_payload()
            marker = "sk-"" ""test-deep-reflection-marker"
            payload[field] = marker
            nested = payload
            for _ in range(80):
                nested["nested"] = {}
                nested = nested["nested"]
            result = grade_payload(payload, 1000)
            self.assertIn("secret-like-content", result["hardGates"]["artifact"])
            self.assertNotIn(marker, repr(result))

    def test_accepts_allowlisted_qualifying_supabase_scope_with_ordinary_rows(self):
        def mutate(payload):
            payload["evidenceSnapshot"]["decisionDependsOnSupabase"] = True
            payload["evidenceSnapshot"]["evidenceRows"].append({
                "evidenceId": "E-SB-OK", "directness": "direct", "authority": "authoritative",
                "freshness": "current", "independenceGroup": "supabase-static",
                "verificationAction": "Read static metadata", "projectRefHash": "sha256:" + "d" * 64,
                "readOnly": True, "observedAt": "2026-07-30T00:00:00Z", "toolTraceRef": "trace-redacted",
            })
        result = self.grade(mutate)
        self.assertEqual([], result["hardGates"]["artifact"])

    def test_rejects_any_extra_field_on_qualifying_supabase_scope_row(self):
        def mutate(payload):
            payload["evidenceSnapshot"]["decisionDependsOnSupabase"] = True
            payload["evidenceSnapshot"]["evidenceRows"].append({
                "evidenceId": "E-SB-EXTRA", "directness": "direct", "authority": "authoritative",
                "freshness": "current", "independenceGroup": "supabase-static",
                "verificationAction": "Read static metadata", "projectRefHash": "sha256:" + "e" * 64,
                "readOnly": True, "observedAt": "2026-07-30T00:00:00Z", "toolTraceRef": "trace-redacted",
                "endpoint": "https://example.invalid/raw-project",
            })
        result = self.grade(mutate)
        self.assertIn("supabase-raw-scope-prohibited", result["hardGates"]["artifact"])


class CliContractTests(unittest.TestCase):
    script = Path(__file__).with_name("score_three_way_long_tail_query.py")

    def invoke(self, payload, output=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "input.json"
            input_path.write_text(json.dumps(payload), encoding="utf-8")
            command = [sys.executable, str(self.script), "--input", str(input_path)]
            output_path = root / "grade.json"
            if output:
                command.extend(["--output", str(output_path)])
            completed = subprocess.run(command, capture_output=True, text=True, check=False)
            return completed, output_path.read_text(encoding="utf-8") if output_path.exists() else ""

    def test_cli_exits_zero_and_emits_bounded_valid_grade(self):
        completed, written = self.invoke(base_payload(), output=True)
        self.assertEqual(0, completed.returncode)
        self.assertEqual("", completed.stdout)
        self.assertLessEqual(len(written.encode("utf-8")), 65_536)
        self.assertEqual("APPLY", json.loads(written)["artifactVerdict"])

    def test_cli_exits_one_for_structural_hold(self):
        payload = base_payload()
        payload["positivePacket"]["scenarioWorlds"][0]["falsifier"] = ""
        completed, _ = self.invoke(payload)
        self.assertEqual(1, completed.returncode)
        self.assertIn("world-falsifier-missing", completed.stdout)

    def test_cli_exits_two_and_never_reflects_secret_in_reflected_fields(self):
        for field in ("rubricVersion", "fixtureDeckHash"):
            payload = base_payload()
            marker = "sk-"" ""test-reflection-marker"
            payload[field] = marker
            completed, written = self.invoke(payload, output=True)
            self.assertEqual(2, completed.returncode)
            self.assertEqual("secret-like-content", completed.stderr.strip())
            self.assertNotIn(marker, completed.stdout + completed.stderr + written)

    def test_cli_exits_two_for_prohibited_raw_field_without_writing_or_echoing_it(self):
        payload = base_payload()
        marker = "private-raw-prompt-marker"
        payload["positivePacket"]["rawPrompt"] = marker
        completed, written = self.invoke(payload, output=True)
        self.assertEqual(2, completed.returncode)
        self.assertEqual("secret-pattern-risk", completed.stderr.strip())
        self.assertEqual("", written)
        self.assertNotIn(marker, completed.stdout + completed.stderr + written)

    def test_cli_deep_structure_cannot_mask_reflected_secret(self):
        for field in ("rubricVersion", "fixtureDeckHash"):
            payload = base_payload()
            marker = "sk-"" ""test-cli-deep-reflection-marker"
            payload[field] = marker
            nested = payload
            for _ in range(80):
                nested["nested"] = {}
                nested = nested["nested"]
            completed, written = self.invoke(payload, output=True)
            self.assertEqual(2, completed.returncode)
            self.assertEqual("secret-like-content", completed.stderr.strip())
            self.assertNotIn(marker, completed.stdout + completed.stderr + written)

    def test_cli_exits_two_for_unsafe_input(self):
        with tempfile.TemporaryDirectory() as directory:
            completed = subprocess.run([sys.executable, str(self.script), "--input", directory], capture_output=True, text=True, check=False)
        self.assertEqual(2, completed.returncode)
        self.assertEqual("malformed-json", completed.stderr.strip())

    def test_cli_exits_three_for_unexpected_output_write_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path = root / "input.json"
            input_path.write_text(json.dumps(base_payload()), encoding="utf-8")
            completed = subprocess.run(
                [sys.executable, str(self.script), "--input", str(input_path), "--output", directory],
                capture_output=True, text=True, check=False,
            )
        self.assertEqual(3, completed.returncode)
        self.assertEqual("internal-error", completed.stderr.strip())


if __name__ == "__main__":
    unittest.main(verbosity=2)
