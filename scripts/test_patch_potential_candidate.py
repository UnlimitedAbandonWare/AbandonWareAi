from __future__ import annotations

import importlib.util
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts" / "score_patch_potential_candidate.py"
SPEC = importlib.util.spec_from_file_location("score_patch_potential_candidate", MODULE_PATH)
grader = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(grader)

OFFICIAL_COMPONENTS = {
    "evidenceStrength": 0.8,
    "causalStrength": 0.6,
    "verificationFeasibility": 0.9,
    "userValue": 0.8,
    "reversibility": 0.9,
    "costEfficiency": 0.8,
    "timeFit": 0.8,
    "blastRadius": 0.1,
    "ambiguity": 0.2,
    "authorityOrSafetyExpansion": 0.0,
}

DELETION_REFERENCE_KEYS = (
    "javaImport",
    "springRegistration",
    "configFqcn",
    "reflectionString",
    "serviceLoader",
    "serializationName",
    "resourceReference",
    "publicApi",
    "dto",
    "configKey",
    "persistence",
)


def write_ready_packet(run_root: Path, name: str, packet: dict[str, object]) -> dict[str, str]:
    path = run_root / name
    data = grader.canonical_json_bytes(packet)
    path.write_bytes(data)
    digest = hashlib.sha256(data).hexdigest()
    Path(str(path) + ".sha256").write_text(digest + "\n", encoding="ascii")
    Path(str(path) + ".ready").write_text(digest + "\n", encoding="ascii")
    return {"relativePath": name, "sha256": digest}


def high_components() -> dict[str, float]:
    return {
        "evidenceStrength": 0.95, "causalStrength": 0.95, "verificationFeasibility": 0.95,
        "userValue": 0.95, "reversibility": 0.95, "costEfficiency": 0.95, "timeFit": 0.95,
        "blastRadius": 0.05, "ambiguity": 0.05, "authorityOrSafetyExpansion": 0.05,
    }


def candidate(candidate_id: str, blast_radius: float = 0.05) -> dict[str, object]:
    components = high_components()
    components["blastRadius"] = blast_radius
    evidence_ids = ["source-boundary", "call-path", "red-proof", "rollback-proof"]
    return {
        "candidateId": candidate_id,
        "targetFiles": ["main/java/com/example/lms/service/Example.java"],
        "candidateKind": "MODIFY",
        "problemEvidenceIds": evidence_ids,
        "scoreComponents": components,
        "scoreEvidenceBindings": {name: evidence_ids for name in grader.SCORE_COMPONENT_NAMES},
        "redCommand": ".\\gradlew.bat test --tests 'com.example.lms.service.ExampleTest.redCase'",
        "greenCommands": [".\\gradlew.bat test --tests 'com.example.lms.service.ExampleTest'"],
    }


def static_gate(row: dict[str, object]) -> dict[str, object]:
    evidence_ids = list(row["problemEvidenceIds"])
    return {
        "candidateId": row["candidateId"], "activeSourceSetProven": True, "callPathProven": True,
        "targetSetDeclared": True, "rollbackContractPresent": True, "publicApiChange": False,
        "dbMutation": False, "credentialMutation": False, "providerOrDeploymentMutation": False,
        "evidenceIds": evidence_ids,
    }


def make_valid_run(run_root: Path, candidates: list[dict[str, object]]) -> tuple[Path, dict[str, object]]:
    evidence_snapshot_hash = "a" * 64
    positive_rows, negative_rows, neutral_rows = [], [], []
    for row in candidates:
        candidate_id = str(row["candidateId"])
        scenario_ids = [candidate_id + "-value", candidate_id + "-verification"]
        positive_rows.append({"candidateId": candidate_id, "scenarioWorlds": [
            {"scenarioId": scenario_ids[0], "premise": "the declared blocker is causal", "causalMechanism": "the narrow patch removes the blocker", "expectedObservation": "the focused RED becomes GREEN", "evidenceNeeded": ["red-proof"], "falsifier": "the same input still fails for the same reason", "baseRateStatus": "unknown"},
            {"scenarioId": scenario_ids[1], "premise": "the existing test boundary observes the behavior", "causalMechanism": "the focused assertion covers the call path", "expectedObservation": "the focused test passes without broader regressions", "evidenceNeeded": ["call-path"], "falsifier": "the test passes without executing the changed boundary", "baseRateStatus": "unknown"},
        ]})
        negative_rows.append({"candidateId": candidate_id, "scenarioAttacks": [
            {"scenarioId": scenario_ids[0], "counterexample": "the failure is caused by a different boundary", "alternativeCause": "stale or incomplete verification evidence", "boundaryOrAuthorityRisk": "the active call path may be unproven", "costAndBlastRadius": "one declared target with rollback", "smallestDisconfirmingProbe": "run the focused RED on the frozen input", "evidenceIds": ["red-proof", "call-path"]},
            {"scenarioId": scenario_ids[1], "counterexample": "the assertion may not execute the intended branch", "alternativeCause": "a permissive fixture can create a false GREEN", "boundaryOrAuthorityRisk": "verification feasibility may be overstated", "costAndBlastRadius": "focused test plus existing contract suite", "smallestDisconfirmingProbe": "prove the RED failure class before mutation", "evidenceIds": ["red-proof"]},
        ]})
        neutral_rows.append({"candidateId": candidate_id, "orderABVerdict": "APPLY", "orderBAVerdict": "APPLY", "orderStable": True, "verdict": "APPLY", "goalScoreComponents": row["scoreComponents"], "goalScoreEvidenceIds": row["scoreEvidenceBindings"], "unresolvedFalsifierCount": 0, "decisiveEvidenceIds": row["problemEvidenceIds"]})
    refs = {
        "positivePacketRef": write_ready_packet(run_root, "positive.packet.json", {"schemaVersion": "awx.patch-potential.positive.v1", "packetType": "POSITIVE_QUERY", "evidenceSnapshotHash": evidence_snapshot_hash, "mutationAllowed": False, "candidateScenarios": positive_rows}),
        "negativePacketRef": write_ready_packet(run_root, "negative.packet.json", {"schemaVersion": "awx.patch-potential.negative.v1", "packetType": "NEGATIVE_QUERY", "evidenceSnapshotHash": evidence_snapshot_hash, "mutationAllowed": False, "candidateAttacks": negative_rows}),
        "neutralPacketRef": write_ready_packet(run_root, "neutral.packet.json", {"schemaVersion": "awx.patch-potential.neutral.v1", "packetType": "NEUTRAL_QUERY", "evidenceSnapshotHash": evidence_snapshot_hash, "mutationAllowed": False, "candidateAssessments": neutral_rows}),
    }
    evidence_ids = sorted({item for row in candidates for item in row["problemEvidenceIds"]})
    payload = {"schemaVersion": "awx.patch-potential.candidates.v1", "evidenceSnapshotHash": evidence_snapshot_hash, "autopatchEnabled": True, "mutationMode": "AUTO_SINGLE_CANDIDATE", "sealedEvidence": {"evidenceIds": evidence_ids, "staticGatesByCandidateId": [static_gate(row) for row in candidates], "deletionGatesByCandidateId": []}, "candidates": candidates, **refs}
    return run_root, payload


def make_valid_delete_run(run_root: Path, candidate_id: str) -> tuple[Path, dict[str, object]]:
    row = candidate(candidate_id)
    row["candidateKind"] = "DELETE"
    row["targetFiles"] = ["main/java/com/example/lms/legacy/LegacyAlias.java"]
    prepared_root, payload = make_valid_run(run_root, [row])
    payload["sealedEvidence"]["deletionGatesByCandidateId"] = [{
        "candidateId": candidate_id,
        "authorizedSurface": True,
        "inactiveOrArchive": False,
        "canonicalOwnerProven": True,
        "remainingReferenceCounts": {name: 0 for name in DELETION_REFERENCE_KEYS},
        "compatibilityAliasRequired": False,
        "deletionRedDefined": True,
        "rollbackArtifactSha256": "b" * 64,
        "evidenceIds": list(row["problemEvidenceIds"]),
    }]
    return prepared_root, payload


class GoalScoreBridgeTests(unittest.TestCase):
    def test_existing_powershell_contract_computes_the_authoritative_score(self):
        self.assertEqual(73.5, grader.measure_goal_score(OFFICIAL_COMPONENTS))

    def test_canonical_json_is_sorted_finite_utf8_and_newline_terminated(self):
        first = grader.canonical_json_bytes({"z": 2, "a": 1.25})
        second = grader.canonical_json_bytes({"a": 1.25, "z": 2})
        self.assertEqual(first, second)
        self.assertEqual(b'{"a":1.25,"z":2}\n', first)

    def test_non_finite_json_fails_closed(self):
        with self.assertRaises(ValueError):
            grader.canonical_json_bytes({"score": float("nan")})


class CliPathTests(unittest.TestCase):
    def test_output_requires_run_root(self):
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "input.json"
            output_path = Path(directory) / "result.json"
            input_path.write_text("{}\n", encoding="utf-8")
            completed = subprocess.run(
                [sys.executable, str(MODULE_PATH), "--input", str(input_path), "--output", str(output_path)],
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertEqual(2, completed.returncode)
        self.assertEqual("run-root-required", completed.stderr.strip())

    def test_output_outside_run_root_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as outside:
            run_root = Path(directory)
            input_path = run_root / "input.json"
            output_path = Path(outside) / "result.json"
            input_path.write_text("{}\n", encoding="utf-8")
            completed = subprocess.run(
                [
                    sys.executable,
                    str(MODULE_PATH),
                    "--input",
                    str(input_path),
                    "--output",
                    str(output_path),
                    "--run-root",
                    str(run_root),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertEqual(2, completed.returncode)
        self.assertEqual("output-outside-run-root", completed.stderr.strip())


class CandidateGradingTests(unittest.TestCase):
    def _rewrite_packet(self, run_root: Path, payload: dict[str, object], ref_name: str, mutate) -> None:
        path = run_root / str(payload[ref_name]["relativePath"])
        packet = json.loads(path.read_text(encoding="utf-8"))
        mutate(packet)
        payload[ref_name] = write_ready_packet(run_root, path.name, packet)

    def test_score_above_85_with_all_static_gates_selects_one_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("APPLY", result["verdict"])
        self.assertEqual("candidate-a", result["selectedCandidateId"])
        self.assertTrue(result["autopatchEligible"])
        self.assertEqual("ENTER_EXISTING_MUTATION_GUARD", result["nextSingleAction"])

    def test_unproven_source_set_is_non_compensable(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            payload["sealedEvidence"]["staticGatesByCandidateId"][0]["activeSourceSetProven"] = False
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("HOLD", result["verdict"])
        self.assertIn("wrong-sourceset", result["hardGates"])
        self.assertFalse(result["autopatchEligible"])

    def test_input_order_does_not_change_the_selected_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, forward = make_valid_run(Path(directory), [candidate("candidate-b"), candidate("candidate-a")])
            reverse = json.loads(json.dumps(forward))
            reverse["candidates"].reverse()
            first = grader.grade_candidates(forward, len(grader.canonical_json_bytes(forward)), run_root)
            second = grader.grade_candidates(reverse, len(grader.canonical_json_bytes(reverse)), run_root)
        self.assertEqual("candidate-a", first["selectedCandidateId"])
        self.assertEqual(first["selectedCandidateId"], second["selectedCandidateId"])

    def test_autopatch_disabled_never_enters_the_mutation_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            payload["autopatchEnabled"] = False
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("HOLD", result["verdict"])
        self.assertEqual("autopatch-disabled", result["failureClass"])

    def test_invalid_sealed_packet_conditions_fail_closed(self):
        cases = ("snapshot", "hash", "ready", "extra-sealed", "missing-sealed", "binding", "neutral-hold", "unstable", "computed")
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory:
                run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
                if case == "snapshot":
                    packet = run_root / "positive.packet.json"
                    data = json.loads(packet.read_text(encoding="utf-8")); data["evidenceSnapshotHash"] = "b" * 64
                    write_ready_packet(run_root, packet.name, data); payload["positivePacketRef"]["sha256"] = hashlib.sha256(packet.read_bytes()).hexdigest()
                elif case == "hash":
                    payload["positivePacketRef"]["sha256"] = "b" * 64
                elif case == "ready":
                    Path(str(run_root / "negative.packet.json") + ".ready").unlink()
                elif case == "extra-sealed":
                    payload["sealedEvidence"]["staticGatesByCandidateId"].append(static_gate(candidate("candidate-b")))
                elif case == "missing-sealed":
                    payload["sealedEvidence"]["staticGatesByCandidateId"] = []
                elif case == "binding":
                    payload["candidates"][0]["scoreEvidenceBindings"]["userValue"] = ["outside"]
                elif case == "neutral-hold":
                    data = json.loads((run_root / "neutral.packet.json").read_text(encoding="utf-8")); data["candidateAssessments"][0]["verdict"] = "HOLD"
                    ref = write_ready_packet(run_root, "neutral.packet.json", data); payload["neutralPacketRef"] = ref
                elif case == "unstable":
                    data = json.loads((run_root / "neutral.packet.json").read_text(encoding="utf-8")); data["candidateAssessments"][0]["orderStable"] = False
                    payload["neutralPacketRef"] = write_ready_packet(run_root, "neutral.packet.json", data)
                else:
                    payload["candidates"][0]["computedGoalScore"] = 99.0
                result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
                self.assertEqual("HOLD", result["verdict"])

    def test_empty_score_evidence_binding_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            payload["candidates"][0]["scoreEvidenceBindings"]["userValue"] = []
            self._rewrite_packet(run_root, payload, "neutralPacketRef", lambda packet: packet["candidateAssessments"][0]["goalScoreEvidenceIds"].__setitem__("userValue", []))
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("HOLD", result["verdict"])
        self.assertEqual("evidence-binding-empty", result["failureClass"])
        self.assertIn("evidence-binding-empty", result["hardGates"])

    def test_snapshot_hash_must_be_lowercase_sha256(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            payload["evidenceSnapshotHash"] = "A" * 64
            for ref_name in ("positivePacketRef", "negativePacketRef", "neutralPacketRef"):
                self._rewrite_packet(run_root, payload, ref_name, lambda packet: packet.__setitem__("evidenceSnapshotHash", "A" * 64))
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("HOLD", result["verdict"])
        self.assertEqual("snapshot-hash-invalid", result["failureClass"])

    def test_malformed_guard_packet_values_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            self._rewrite_packet(run_root, payload, "positivePacketRef", lambda packet: packet["candidateScenarios"][0]["scenarioWorlds"][0].__setitem__("premise", ""))
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("HOLD", result["verdict"])
        self.assertEqual("packet-value-invalid", result["failureClass"])

    def test_empty_guard_packet_evidence_list_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            self._rewrite_packet(run_root, payload, "negativePacketRef", lambda packet: packet["candidateAttacks"][0]["scenarioAttacks"][0].__setitem__("evidenceIds", []))
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("HOLD", result["verdict"])
        self.assertEqual("packet-value-invalid", result["failureClass"])

    def test_boolean_unresolved_falsifier_count_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            self._rewrite_packet(run_root, payload, "neutralPacketRef", lambda packet: packet["candidateAssessments"][0].__setitem__("unresolvedFalsifierCount", False))
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("HOLD", result["verdict"])
        self.assertEqual("packet-value-invalid", result["failureClass"])

    def test_modify_candidate_cannot_have_deletion_gate_or_unsealed_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            payload["sealedEvidence"]["deletionGatesByCandidateId"] = [{
                "candidateId": "candidate-a", "authorizedSurface": True, "inactiveOrArchive": False,
                "canonicalOwnerProven": True, "remainingReferenceCounts": {name: 0 for name in DELETION_REFERENCE_KEYS},
                "compatibilityAliasRequired": False, "deletionRedDefined": True,
                "rollbackArtifactSha256": "b" * 64, "evidenceIds": ["outside"],
            }]
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("HOLD", result["verdict"])
        self.assertEqual("deletion-gate-invalid", result["failureClass"])

    def test_deletion_gate_evidence_must_be_sealed(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_delete_run(Path(directory), "candidate-a")
            payload["sealedEvidence"]["deletionGatesByCandidateId"][0]["evidenceIds"] = ["outside"]
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("HOLD", result["verdict"])
        self.assertEqual("evidence-not-sealed", result["failureClass"])

    def test_packets_and_score_are_prepared_once_before_pure_replay(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            packet_calls, score_calls = [], []
            original = grader._read_ready_packet
            grader._read_ready_packet = lambda *args: (packet_calls.append(args[1]["relativePath"]) or original(*args))
            try:
                result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root, lambda components: (score_calls.append(components) or 95.0))
            finally:
                grader._read_ready_packet = original
        self.assertEqual("APPLY", result["verdict"])
        self.assertEqual(3, len(packet_calls))
        self.assertEqual(1, len(score_calls))


class DeletionEligibilityAndBoundsTests(unittest.TestCase):
    def test_remaining_reflection_reference_blocks_deletion(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_delete_run(Path(directory), "legacy-a")
            row = payload["sealedEvidence"]["deletionGatesByCandidateId"][0]
            row["remainingReferenceCounts"]["reflectionString"] = 1
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("HOLD", result["verdict"])
        self.assertFalse(result["deletionEligible"])
        self.assertIn("deletion-reference-present", result["hardGates"])

    def test_complete_deletion_evidence_is_eligible(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_delete_run(Path(directory), "legacy-a")
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("APPLY", result["verdict"])
        self.assertTrue(result["deletionEligible"])

    def test_inactive_delete_is_reported_but_never_selected(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_delete_run(Path(directory), "legacy-a")
            payload["sealedEvidence"]["deletionGatesByCandidateId"][0]["inactiveOrArchive"] = True
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("HOLD", result["verdict"])
        self.assertFalse(result["deletionEligible"])
        self.assertEqual("LEGACY_INACTIVE_REPORTED", result["failureClass"])

    def test_incomplete_delete_contracts_fail_closed(self):
        cases = ("owner", "compatibility", "red", "rollback", "references")
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory:
                run_root, payload = make_valid_delete_run(Path(directory), "legacy-a")
                row = payload["sealedEvidence"]["deletionGatesByCandidateId"][0]
                if case == "owner":
                    row["canonicalOwnerProven"] = False
                elif case == "compatibility":
                    row["compatibilityAliasRequired"] = True
                elif case == "red":
                    row["deletionRedDefined"] = False
                elif case == "rollback":
                    row["rollbackArtifactSha256"] = "B" * 64
                else:
                    row["remainingReferenceCounts"].pop("dto")
                result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
                self.assertEqual("HOLD", result["verdict"])
                self.assertFalse(result["deletionEligible"])

    def test_resource_bound_violations_are_not_truncated(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate(f"candidate-{index}") for index in range(65)])
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("resource-bound-exceeded", result["failureClass"])
        self.assertEqual([], result["evaluatedCandidates"])
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            payload["candidates"][0]["targetFiles"] *= 9
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("resource-bound-exceeded", result["failureClass"])

    def test_secret_like_content_and_nonfinite_components_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            payload["candidates"][0]["redCommand"] = "Authorization: Bearer abcdefghijklmnop"
            result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
        self.assertEqual("secret-like-content", result["failureClass"])
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            payload["candidates"][0]["scoreComponents"]["userValue"] = float("nan")
            result = grader.grade_candidates(payload, 1, run_root)
        self.assertEqual("goal-score-invalid", result["failureClass"])


class CliContractTests(unittest.TestCase):
    def _write_input(self, run_root: Path, payload: dict[str, object]) -> Path:
        path = run_root / "input.json"
        path.write_bytes(grader.canonical_json_bytes(payload))
        return path

    def test_cli_apply_writes_ready_result_and_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            input_path = self._write_input(run_root, payload)
            output_path = run_root / "result.json"
            completed = subprocess.run([sys.executable, str(MODULE_PATH), "--input", str(input_path), "--output", str(output_path), "--run-root", str(run_root)], capture_output=True, text=True, check=False)
            data = output_path.read_bytes()
            digest = hashlib.sha256(data).hexdigest()
            self.assertEqual(0, completed.returncode)
            self.assertEqual(digest, Path(str(output_path) + ".sha256").read_text(encoding="ascii").strip())
            self.assertEqual(digest, Path(str(output_path) + ".ready").read_text(encoding="ascii").strip())

    def test_cli_hold_returns_one_and_malformed_writes_no_result(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            payload["autopatchEnabled"] = False
            input_path = self._write_input(run_root, payload)
            output_path = run_root / "hold.json"
            completed = subprocess.run([sys.executable, str(MODULE_PATH), "--input", str(input_path), "--output", str(output_path), "--run-root", str(run_root)], capture_output=True, text=True, check=False)
            self.assertEqual(1, completed.returncode)
            malformed = run_root / "malformed.json"; malformed.write_text("{}", encoding="utf-8")
            missing = run_root / "missing.json"
            completed = subprocess.run([sys.executable, str(MODULE_PATH), "--input", str(malformed), "--output", str(missing), "--run-root", str(run_root)], capture_output=True, text=True, check=False)
            self.assertEqual(2, completed.returncode)
            self.assertEqual("schema-version-invalid", completed.stderr.strip())
            self.assertFalse(missing.exists())

    def test_cli_replay_is_byte_identical(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            input_path = self._write_input(run_root, payload)
            first, second = run_root / "first.json", run_root / "second.json"
            for output_path in (first, second):
                completed = subprocess.run([sys.executable, str(MODULE_PATH), "--input", str(input_path), "--output", str(output_path), "--run-root", str(run_root)], capture_output=True, text=True, check=False)
                self.assertEqual(0, completed.returncode)
            self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_cli_rejects_output_alias_and_enforces_output_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            input_path = self._write_input(run_root, payload)
            completed = subprocess.run([sys.executable, str(MODULE_PATH), "--input", str(input_path), "--output", str(input_path), "--run-root", str(run_root)], capture_output=True, text=True, check=False)
            self.assertEqual(2, completed.returncode)
            self.assertEqual("output-input-collision", completed.stderr.strip())
            with mock.patch.object(grader, "MAX_OUTPUT_BYTES", 1), mock.patch("sys.stderr", new_callable=io.StringIO) as stderr:
                self.assertEqual(2, grader.main(["--input", str(input_path), "--output", str(run_root / "too-large.json"), "--run-root", str(run_root)]))
                self.assertEqual("resource-bound-exceeded\n", stderr.getvalue())

    def test_cli_post_schema_unsafe_input_writes_no_ready_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            payload["candidates"][0]["redCommand"] = "Authorization: Bearer abcdefghijklmnop"
            input_path = self._write_input(run_root, payload)
            output_path = run_root / "unsafe.json"
            completed = subprocess.run([sys.executable, str(MODULE_PATH), "--input", str(input_path), "--output", str(output_path), "--run-root", str(run_root)], capture_output=True, text=True, check=False)
            self.assertEqual(2, completed.returncode)
            self.assertEqual("secret-like-content", completed.stderr.strip())
            self.assertFalse(output_path.exists())
            self.assertFalse(Path(str(output_path) + ".sha256").exists())
            self.assertFalse(Path(str(output_path) + ".ready").exists())

    def test_cli_post_schema_resource_bound_writes_no_ready_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
            payload["candidates"][0]["targetFiles"] *= 9
            input_path = self._write_input(run_root, payload)
            output_path = run_root / "resource.json"
            completed = subprocess.run([sys.executable, str(MODULE_PATH), "--input", str(input_path), "--output", str(output_path), "--run-root", str(run_root)], capture_output=True, text=True, check=False)
            self.assertEqual(2, completed.returncode)
            self.assertEqual("resource-bound-exceeded", completed.stderr.strip())
            self.assertFalse(output_path.exists())
            self.assertFalse(Path(str(output_path) + ".sha256").exists())
            self.assertFalse(Path(str(output_path) + ".ready").exists())


class StoredPacketPathTests(unittest.TestCase):
    def test_unc_device_and_root_relative_windows_paths_are_rejected(self):
        for absolute_path in (r"\\server\share\file", r"\\?\C:\file", r"\file"):
            with self.subTest(absolute_path=absolute_path), tempfile.TemporaryDirectory() as directory:
                run_root, payload = make_valid_run(Path(directory), [candidate("candidate-a")])
                packet_path = run_root / "positive.packet.json"
                packet = json.loads(packet_path.read_text(encoding="utf-8"))
                packet["candidateScenarios"][0]["scenarioWorlds"][0]["premise"] = absolute_path
                payload["positivePacketRef"] = write_ready_packet(run_root, packet_path.name, packet)
                result = grader.grade_candidates(payload, len(grader.canonical_json_bytes(payload)), run_root)
                self.assertEqual("HOLD", result["verdict"])
                self.assertEqual("secret-leak-risk", result["failureClass"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
