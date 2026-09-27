from __future__ import annotations

import argparse
from collections.abc import Callable
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
GOAL_SCORE_HELPER = ROOT / "scripts" / "awx_goal_score_contract.ps1"

SCHEMA_INPUT = "awx.patch-potential.candidates.v1"
SCHEMA_OUTPUT = "awx.patch-potential.eligibility.v1"
AUTOPATCH_THRESHOLD = 85.0
MAX_INPUT_BYTES = 1024 * 1024
MAX_OUTPUT_BYTES = 256 * 1024
MAX_CANDIDATES = 64
MAX_TARGET_FILES = 8

TOP_LEVEL_KEYS = frozenset({"schemaVersion", "evidenceSnapshotHash", "autopatchEnabled", "mutationMode", "sealedEvidence", "candidates", "positivePacketRef", "negativePacketRef", "neutralPacketRef"})
CANDIDATE_KEYS = frozenset({"candidateId", "targetFiles", "candidateKind", "problemEvidenceIds", "scoreComponents", "scoreEvidenceBindings", "redCommand", "greenCommands"})
SEALED_EVIDENCE_KEYS = frozenset({"evidenceIds", "staticGatesByCandidateId", "deletionGatesByCandidateId"})
STATIC_GATE_KEYS = frozenset({"candidateId", "activeSourceSetProven", "callPathProven", "targetSetDeclared", "rollbackContractPresent", "publicApiChange", "dbMutation", "credentialMutation", "providerOrDeploymentMutation", "evidenceIds"})
DELETION_GATE_KEYS = frozenset({"candidateId", "authorizedSurface", "inactiveOrArchive", "canonicalOwnerProven", "remainingReferenceCounts", "compatibilityAliasRequired", "deletionRedDefined", "rollbackArtifactSha256", "evidenceIds"})
PACKET_REF_KEYS = frozenset({"relativePath", "sha256"})
POSITIVE_PACKET_KEYS = frozenset({"schemaVersion", "packetType", "evidenceSnapshotHash", "mutationAllowed", "candidateScenarios"})
NEGATIVE_PACKET_KEYS = frozenset({"schemaVersion", "packetType", "evidenceSnapshotHash", "mutationAllowed", "candidateAttacks"})
NEUTRAL_PACKET_KEYS = frozenset({"schemaVersion", "packetType", "evidenceSnapshotHash", "mutationAllowed", "candidateAssessments"})
POSITIVE_ROW_KEYS = frozenset({"candidateId", "scenarioWorlds"})
NEGATIVE_ROW_KEYS = frozenset({"candidateId", "scenarioAttacks"})
NEUTRAL_ROW_KEYS = frozenset({"candidateId", "orderABVerdict", "orderBAVerdict", "orderStable", "verdict", "goalScoreComponents", "goalScoreEvidenceIds", "unresolvedFalsifierCount", "decisiveEvidenceIds"})
POSITIVE_SCENARIO_KEYS = frozenset({"scenarioId", "premise", "causalMechanism", "expectedObservation", "evidenceNeeded", "falsifier", "baseRateStatus"})
NEGATIVE_SCENARIO_KEYS = frozenset({"scenarioId", "counterexample", "alternativeCause", "boundaryOrAuthorityRisk", "costAndBlastRadius", "smallestDisconfirmingProbe", "evidenceIds"})

SCORE_COMPONENT_NAMES = (
    "evidenceStrength",
    "causalStrength",
    "verificationFeasibility",
    "userValue",
    "reversibility",
    "costEfficiency",
    "timeFit",
    "blastRadius",
    "ambiguity",
    "authorityOrSafetyExpansion",
)

DELETION_REFERENCE_KEYS = (
    "javaImport", "springRegistration", "configFqcn", "reflectionString", "serviceLoader",
    "serializationName", "resourceReference", "publicApi", "dto", "configKey", "persistence",
)
SECRET_PATTERNS = (
    re.compile(r"(?i)authorization\s*:\s*bearer\s+[A-Za-z0-9._~+/-]{8,}"),
    re.compile(r"(?i)(api[_-]?key|client[_-]?secret|owner[_-]?token)\s*[=:]\s*[A-Za-z0-9._~+/-]{12,}"),
    re.compile(r"sk-[A-Za-z0-9]{16,}"),
)
FORBIDDEN_PAYLOAD_KEYS = frozenset({"rawPrompt", "rawQuery", "Authorization", "Cookie", "apiKey", "clientSecret", "ownerToken"})
ABSOLUTE_PATH_PATTERN = re.compile(r"^(?:[A-Za-z]:[\\/]|[\\/])")


class GradeInputError(ValueError):
    def __init__(self, reason_code: str):
        super().__init__(reason_code)
        self.reason_code = reason_code


def canonical_json_bytes(result: dict[str, object]) -> bytes:
    return (
        json.dumps(
            result,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def measure_goal_score(components: dict[str, float]) -> float:
    _validate_score_components(components)
    ordered_components = {name: float(components[name]) for name in SCORE_COMPONENT_NAMES}
    command = (
        "$ErrorActionPreference='Stop';"
        "$helper=[Environment]::GetEnvironmentVariable('AWX_GOAL_SCORE_HELPER');"
        ". ([IO.Path]::GetFullPath($helper));"
        "$components=[Console]::In.ReadToEnd()|ConvertFrom-Json;"
        "$result=Measure-AwxGoalScore -Components $components;"
        "if(-not $result.valid){[Console]::Error.Write($result.failureClassification);exit 2};"
        "[Console]::Out.Write($result.computedScore.ToString([Globalization.CultureInfo]::InvariantCulture))"
    )
    child_environment = os.environ.copy()
    child_environment["AWX_GOAL_SCORE_HELPER"] = str(GOAL_SCORE_HELPER)
    completed = subprocess.run(
        ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command],
        input=json.dumps(ordered_components, ensure_ascii=True, separators=(",", ":")),
        capture_output=True, text=True, check=False, env=child_environment, timeout=10,
    )
    if completed.returncode != 0:
        reason = completed.stderr.strip()
        raise GradeInputError(reason if reason else "goal-score-contract-failed")
    try:
        score = float(completed.stdout.strip())
    except ValueError as exc:
        raise GradeInputError("goal-score-contract-invalid-output") from exc
    if not math.isfinite(score) or not 0.0 <= score <= 100.0:
        raise GradeInputError("goal-score-contract-invalid-output")
    return round(score, 4)


def _validate_score_components(components: object) -> None:
    if not isinstance(components, dict):
        raise GradeInputError("goal-score-invalid")
    if len(components) != len(SCORE_COMPONENT_NAMES) or set(components) != set(SCORE_COMPONENT_NAMES):
        raise GradeInputError("goal-score-invalid")
    if any(type(value) not in (int, float) or not math.isfinite(float(value)) or not 0.0 <= float(value) <= 1.0 for value in components.values()):
        raise GradeInputError("goal-score-invalid")


def _safe_scan(value: object, *, stored_packet: bool = False) -> None:
    stack: list[tuple[object, int]] = [(value, 0)]
    node_count = 0
    while stack:
        current, depth = stack.pop()
        node_count += 1
        if depth > 32:
            raise GradeInputError("structure-depth-exceeded")
        if node_count > 4096:
            raise GradeInputError("structure-node-limit-exceeded")
        if isinstance(current, dict):
            for key, child in current.items():
                if not isinstance(key, str):
                    raise GradeInputError("invalid-type")
                if key in FORBIDDEN_PAYLOAD_KEYS:
                    raise GradeInputError("secret-like-content")
                stack.append((child, depth + 1))
        elif isinstance(current, list):
            for child in current:
                stack.append((child, depth + 1))
        elif isinstance(current, str):
            if len(current.encode("utf-8")) > 16384:
                raise GradeInputError("string-size-exceeded")
            if any(pattern.search(current) for pattern in SECRET_PATTERNS):
                raise GradeInputError("secret-like-content")
            if stored_packet and ABSOLUTE_PATH_PATTERN.search(current):
                raise GradeInputError("secret-leak-risk")
        elif current is not None and type(current) not in (bool, int, float):
            raise GradeInputError("invalid-type")


def _preflight_declared_resource_bounds(payload: object) -> None:
    """Reject oversized declared collections before bounded deep traversal."""
    if not isinstance(payload, dict):
        return
    candidates = payload.get("candidates")
    if isinstance(candidates, list):
        if len(candidates) > MAX_CANDIDATES:
            raise GradeInputError("resource-bound-exceeded")
        for row in candidates:
            if isinstance(row, dict) and isinstance(row.get("targetFiles"), list) and len(row["targetFiles"]) > MAX_TARGET_FILES:
                raise GradeInputError("resource-bound-exceeded")
    sealed = payload.get("sealedEvidence")
    if isinstance(sealed, dict):
        for key in ("staticGatesByCandidateId", "deletionGatesByCandidateId"):
            if isinstance(sealed.get(key), list) and len(sealed[key]) > MAX_CANDIDATES:
                raise GradeInputError("resource-bound-exceeded")


def _preflight_packet_resource_bounds(packet: object) -> None:
    if not isinstance(packet, dict):
        return
    for key in ("candidateScenarios", "candidateAttacks", "candidateAssessments"):
        rows = packet.get(key)
        if isinstance(rows, list) and len(rows) > MAX_CANDIDATES:
            raise GradeInputError("resource-bound-exceeded")


def _is_within(path: Path, root: Path) -> bool:
    try:
        return os.path.commonpath((str(path), str(root))) == str(root)
    except ValueError:
        return False


def _require_exact_keys(value: object, allowed: frozenset[str]) -> dict[str, object]:
    if not isinstance(value, dict):
        raise GradeInputError("invalid-type")
    keys = set(value)
    if not allowed <= keys:
        raise GradeInputError("missing-key")
    if keys - allowed:
        raise GradeInputError("unknown-key")
    return value


def _require_unique_ids(rows: object, key: str = "candidateId") -> set[str]:
    if not isinstance(rows, list):
        raise GradeInputError("invalid-type")
    result: set[str] = set()
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get(key), str) or not row[key]:
            raise GradeInputError("invalid-type")
        if row[key] in result:
            raise GradeInputError("duplicate-candidate-id")
        result.add(row[key])
    return result


def _require_string_id_list(value: object, *, nonempty: bool = True, reason: str = "invalid-type") -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) or not item for item in value):
        raise GradeInputError(reason)
    if nonempty and not value:
        raise GradeInputError(reason)
    if len(set(value)) != len(value):
        raise GradeInputError("duplicate-evidence-id")
    return value


def _require_lower_sha256(value: object, reason: str) -> str:
    if not isinstance(value, str) or len(value) != 64 or any(char not in "0123456789abcdef" for char in value):
        raise GradeInputError(reason)
    return value


def _require_nonempty_string(value: object) -> str:
    if not isinstance(value, str) or not value:
        raise GradeInputError("packet-value-invalid")
    return value


def _read_ready_packet(
    run_root: Path,
    reference: dict[str, object],
    expected_schema: str,
    expected_packet_type: str,
) -> tuple[dict[str, object], str]:
    relative = reference["relativePath"]
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        raise GradeInputError("packet-ref-invalid")
    try:
        path = (run_root / relative).resolve(strict=True)
    except OSError as exc:
        raise GradeInputError("packet-ref-invalid") from exc
    if not _is_within(path, run_root):
        raise GradeInputError("packet-ref-outside-run-root")
    try:
        data = path.read_bytes()
    except OSError as exc:
        raise GradeInputError("packet-ref-invalid") from exc
    digest = hashlib.sha256(data).hexdigest()
    declared = reference["sha256"]
    if not isinstance(declared, str) or declared != digest:
        raise GradeInputError("artifact-hash-mismatch")
    sidecar = Path(str(path) + ".sha256")
    ready = Path(str(path) + ".ready")
    if not sidecar.is_file() or not ready.is_file():
        raise GradeInputError("artifact-not-ready")
    try:
        if sidecar.read_text(encoding="ascii").strip() != digest or ready.read_text(encoding="ascii").strip() != digest:
            raise GradeInputError("artifact-hash-mismatch")
    except UnicodeDecodeError as exc:
        raise GradeInputError("artifact-hash-mismatch") from exc
    try:
        packet = json.loads(data)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GradeInputError("malformed-json") from exc
    if not isinstance(packet, dict):
        raise GradeInputError("invalid-type")
    _preflight_packet_resource_bounds(packet)
    _safe_scan(packet, stored_packet=True)
    if packet.get("schemaVersion") != expected_schema or packet.get("packetType") != expected_packet_type:
        raise GradeInputError("packet-role-mismatch")
    return packet, digest


def _static_hard_gates(row: dict[str, object]) -> list[str]:
    gates: list[str] = []
    if row["activeSourceSetProven"] is not True:
        gates.append("wrong-sourceset")
    if row["callPathProven"] is not True:
        gates.append("call-path-unproven")
    if row["targetSetDeclared"] is not True:
        gates.append("target-set-invalid")
    if row["rollbackContractPresent"] is not True:
        gates.append("rollback-contract-missing")
    if row["publicApiChange"] is not False:
        gates.append("public-api-change-forbidden")
    if row["dbMutation"] is not False:
        gates.append("db-mutation-forbidden")
    if row["credentialMutation"] is not False:
        gates.append("credential-mutation-forbidden")
    if row["providerOrDeploymentMutation"] is not False:
        gates.append("provider-or-deployment-mutation-forbidden")
    return gates


def _deletion_hard_gates(
    candidate_row: dict[str, object],
    deletion_row: dict[str, object] | None,
    sealed_evidence_ids: set[str],
) -> tuple[bool, list[str]]:
    if candidate_row["candidateKind"] == "MODIFY":
        return False, [] if deletion_row is None else ["deletion-contract-unexpected"]
    if deletion_row is None:
        return False, ["deletion-compatibility-unproven"]
    gates: list[str] = []
    if deletion_row["authorizedSurface"] is not True or deletion_row["inactiveOrArchive"] is not False:
        gates.append("deletion-surface-forbidden")
    if deletion_row["canonicalOwnerProven"] is not True or deletion_row["compatibilityAliasRequired"] is not False:
        gates.append("deletion-compatibility-unproven")
    if deletion_row["deletionRedDefined"] is not True:
        gates.append("red-not-reproduced")
    rollback_hash = deletion_row["rollbackArtifactSha256"]
    if not isinstance(rollback_hash, str) or len(rollback_hash) != 64 or any(character not in "0123456789abcdef" for character in rollback_hash):
        gates.append("rollback-contract-missing")
    counts = deletion_row["remainingReferenceCounts"]
    if not isinstance(counts, dict) or set(counts) != set(DELETION_REFERENCE_KEYS) or any(type(counts[name]) is not int or counts[name] != 0 for name in DELETION_REFERENCE_KEYS):
        gates.append("deletion-reference-present")
    evidence_ids = deletion_row["evidenceIds"]
    if not isinstance(evidence_ids, list) or not evidence_ids or any(item not in sealed_evidence_ids for item in evidence_ids):
        gates.append("candidate-evidence-unresolved")
    unique_gates = sorted(set(gates))
    return not unique_gates, unique_gates


def _candidate_sort_key(row: dict[str, object]) -> tuple[float, float, float, str]:
    components = row["scoreComponents"]
    return (
        float(components["blastRadius"]),
        -float(components["verificationFeasibility"]),
        -float(components["reversibility"]),
        str(row["candidateId"]),
    )


def _failure(reason: str) -> dict[str, object]:
    return {"schemaVersion": SCHEMA_OUTPUT, "verdict": "HOLD", "failureClass": reason,
            "selectedCandidateId": None, "autopatchEligible": False,
            "deletionEligible": False,
            "nextSingleAction": "REPAIR_GRADE_INPUT", "hardGates": [reason],
            "hardGateCount": 1, "evaluatedCandidates": []}


def _validate_packet(packet: dict[str, object], keys: frozenset[str]) -> dict[str, object]:
    return _require_exact_keys(packet, keys)


def _scenario_ids(rows: list[dict[str, object]], scenario_key: str, allowed: frozenset[str]) -> dict[str, set[str]]:
    result: dict[str, set[str]] = {}
    for row in rows:
        container = row[scenario_key]
        if not isinstance(container, list) or len(container) != 2:
            raise GradeInputError("scenario-contract-invalid")
        ids: set[str] = set()
        for scenario in container:
            data = _require_exact_keys(scenario, allowed)
            scenario_id = data["scenarioId"]
            if not isinstance(scenario_id, str) or not scenario_id or scenario_id in ids:
                raise GradeInputError("scenario-contract-invalid")
            string_keys = ("premise", "causalMechanism", "expectedObservation", "falsifier", "baseRateStatus") if scenario_key == "scenarioWorlds" else ("counterexample", "alternativeCause", "boundaryOrAuthorityRisk", "costAndBlastRadius", "smallestDisconfirmingProbe")
            for string_key in string_keys:
                _require_nonempty_string(data[string_key])
            evidence_key = "evidenceNeeded" if scenario_key == "scenarioWorlds" else "evidenceIds"
            _require_string_id_list(data[evidence_key], reason="packet-value-invalid")
            ids.add(scenario_id)
        result[str(row["candidateId"])] = ids
    return result


def _prepare_grade_candidates(
    payload: dict[str, object], input_size: int, run_root: Path,
    score_resolver: Callable[[dict[str, float]], float], *,
    raise_input_errors: bool = False,
) -> dict[str, object]:
    try:
        _preflight_declared_resource_bounds(payload)
        _safe_scan(payload)
        if input_size < 0 or input_size > MAX_INPUT_BYTES:
            raise GradeInputError("resource-bound-exceeded")
        top = _require_exact_keys(payload, TOP_LEVEL_KEYS)
        if top["schemaVersion"] != SCHEMA_INPUT or top["mutationMode"] != "AUTO_SINGLE_CANDIDATE":
            raise GradeInputError("schema-version-invalid")
        if type(top["autopatchEnabled"]) is not bool:
            raise GradeInputError("invalid-type")
        _require_lower_sha256(top["evidenceSnapshotHash"], "snapshot-hash-invalid")
        sealed = _require_exact_keys(top["sealedEvidence"], SEALED_EVIDENCE_KEYS)
        evidence_ids = set(_require_string_id_list(sealed["evidenceIds"]))
        candidates = top["candidates"]
        candidate_ids = _require_unique_ids(candidates)
        if not candidate_ids:
            raise GradeInputError("candidate-set-invalid")
        if len(candidate_ids) > MAX_CANDIDATES:
            raise GradeInputError("resource-bound-exceeded")
        normalized_candidates: dict[str, dict[str, object]] = {}
        for raw in candidates:
            row = _require_exact_keys(raw, CANDIDATE_KEYS)
            if row["candidateKind"] not in ("MODIFY", "DELETE") or not isinstance(row["targetFiles"], list) or not row["targetFiles"]:
                raise GradeInputError("candidate-set-invalid")
            if len(row["targetFiles"]) > MAX_TARGET_FILES:
                raise GradeInputError("resource-bound-exceeded")
            if any(not isinstance(item, str) or not item for item in row["targetFiles"]):
                raise GradeInputError("invalid-type")
            problem_ids = _require_string_id_list(row["problemEvidenceIds"])
            if not set(problem_ids) <= evidence_ids:
                raise GradeInputError("evidence-not-sealed")
            components = row["scoreComponents"]
            _validate_score_components(components)
            bindings = row["scoreEvidenceBindings"]
            if not isinstance(bindings, dict) or set(bindings) != set(SCORE_COMPONENT_NAMES):
                raise GradeInputError("goal-score-invalid")
            for value in bindings.values():
                bound = _require_string_id_list(value, reason="evidence-binding-empty")
                if not set(bound) <= set(problem_ids) or not set(bound) <= evidence_ids:
                    raise GradeInputError("score-evidence-unsealed")
            if not isinstance(row["redCommand"], str) or not row["redCommand"] or not isinstance(row["greenCommands"], list) or not row["greenCommands"]:
                raise GradeInputError("invalid-type")
            normalized_candidates[str(row["candidateId"])] = row
        static_rows = sealed["staticGatesByCandidateId"]
        if not isinstance(static_rows, list) or len(static_rows) > MAX_CANDIDATES:
            raise GradeInputError("resource-bound-exceeded")
        static_ids = _require_unique_ids(static_rows)
        if static_ids != candidate_ids:
            raise GradeInputError("sealed-candidate-set-mismatch")
        static_by_id: dict[str, dict[str, object]] = {}
        for raw in static_rows:
            row = _require_exact_keys(raw, STATIC_GATE_KEYS)
            gate_ids = _require_string_id_list(row["evidenceIds"])
            if not set(gate_ids) <= evidence_ids:
                raise GradeInputError("evidence-not-sealed")
            static_by_id[str(row["candidateId"])] = row
        deletion_rows = sealed["deletionGatesByCandidateId"]
        if not isinstance(deletion_rows, list) or len(deletion_rows) > MAX_CANDIDATES:
            raise GradeInputError("resource-bound-exceeded")
        deletion_ids = _require_unique_ids(deletion_rows)
        delete_candidate_ids = {candidate_id for candidate_id, row in normalized_candidates.items() if row["candidateKind"] == "DELETE"}
        for raw in deletion_rows:
            row = _require_exact_keys(raw, DELETION_GATE_KEYS)
            if row["candidateId"] not in delete_candidate_ids:
                raise GradeInputError("deletion-gate-invalid")
            if not set(_require_string_id_list(row["evidenceIds"])) <= evidence_ids:
                raise GradeInputError("evidence-not-sealed")
        if deletion_ids != delete_candidate_ids:
            raise GradeInputError("sealed-candidate-set-mismatch")

        refs = (("positivePacketRef", "awx.patch-potential.positive.v1", "POSITIVE_QUERY", POSITIVE_PACKET_KEYS),
                ("negativePacketRef", "awx.patch-potential.negative.v1", "NEGATIVE_QUERY", NEGATIVE_PACKET_KEYS),
                ("neutralPacketRef", "awx.patch-potential.neutral.v1", "NEUTRAL_QUERY", NEUTRAL_PACKET_KEYS))
        packets: dict[str, dict[str, object]] = {}
        for name, schema, packet_type, keys in refs:
            reference = _require_exact_keys(top[name], PACKET_REF_KEYS)
            packet, _ = _read_ready_packet(run_root, reference, schema, packet_type)
            packets[name] = _validate_packet(packet, keys)
            _require_lower_sha256(packets[name]["evidenceSnapshotHash"], "snapshot-hash-invalid")
            if packets[name]["evidenceSnapshotHash"] != top["evidenceSnapshotHash"] or packets[name]["mutationAllowed"] is not False:
                raise GradeInputError("packet-snapshot-mismatch")
        positive_rows = packets["positivePacketRef"]["candidateScenarios"]
        negative_rows = packets["negativePacketRef"]["candidateAttacks"]
        neutral_rows = packets["neutralPacketRef"]["candidateAssessments"]
        for rows, keys in ((positive_rows, POSITIVE_ROW_KEYS), (negative_rows, NEGATIVE_ROW_KEYS), (neutral_rows, NEUTRAL_ROW_KEYS)):
            if not isinstance(rows, list) or len(rows) > MAX_CANDIDATES:
                raise GradeInputError("resource-bound-exceeded")
            ids = _require_unique_ids(rows)
            if ids != candidate_ids:
                raise GradeInputError("packet-candidate-set-mismatch")
            for row in rows:
                _require_exact_keys(row, keys)
        for row in neutral_rows:
            if (not isinstance(row["orderABVerdict"], str) or not isinstance(row["orderBAVerdict"], str)
                    or type(row["orderStable"]) is not bool or not isinstance(row["verdict"], str)
                    or type(row["unresolvedFalsifierCount"]) is not int or row["unresolvedFalsifierCount"] < 0):
                raise GradeInputError("packet-value-invalid")
            _validate_score_components(row["goalScoreComponents"])
            bindings = row["goalScoreEvidenceIds"]
            if not isinstance(bindings, dict) or set(bindings) != set(SCORE_COMPONENT_NAMES):
                raise GradeInputError("packet-value-invalid")
            for binding in bindings.values():
                _require_string_id_list(binding, reason="packet-value-invalid")
        positive_by_id = {str(row["candidateId"]): row for row in positive_rows}
        negative_by_id = {str(row["candidateId"]): row for row in negative_rows}
        neutral_by_id = {str(row["candidateId"]): row for row in neutral_rows}
        positive_scenarios = _scenario_ids(list(positive_by_id.values()), "scenarioWorlds", POSITIVE_SCENARIO_KEYS)
        negative_scenarios = _scenario_ids(list(negative_by_id.values()), "scenarioAttacks", NEGATIVE_SCENARIO_KEYS)
        evaluated_rows: list[dict[str, object]] = []
        all_gates: list[str] = []
        primary_failure: str | None = None
        deletion_by_id = {str(row["candidateId"]): row for row in deletion_rows}
        for candidate_id in sorted(candidate_ids):
            candidate_row = normalized_candidates[candidate_id]
            allowed_evidence = set(candidate_row["problemEvidenceIds"])
            for scenario in positive_by_id[candidate_id]["scenarioWorlds"]:
                if not set(_require_string_id_list(scenario["evidenceNeeded"])) <= allowed_evidence <= evidence_ids:
                    raise GradeInputError("evidence-not-sealed")
            for scenario in negative_by_id[candidate_id]["scenarioAttacks"]:
                if not set(_require_string_id_list(scenario["evidenceIds"])) <= allowed_evidence <= evidence_ids:
                    raise GradeInputError("evidence-not-sealed")
            neutral = neutral_by_id[candidate_id]
            if canonical_json_bytes(candidate_row["scoreComponents"]) != canonical_json_bytes(neutral["goalScoreComponents"]) or canonical_json_bytes(candidate_row["scoreEvidenceBindings"]) != canonical_json_bytes(neutral["goalScoreEvidenceIds"]):
                raise GradeInputError("neutral-assessment-mismatch")
            decisive = set(_require_string_id_list(neutral["decisiveEvidenceIds"]))
            if not decisive <= evidence_ids:
                raise GradeInputError("evidence-not-sealed")
            candidate_gates = _static_hard_gates(static_by_id[candidate_id])
            if positive_scenarios[candidate_id] != negative_scenarios[candidate_id]:
                candidate_gates.append("scenario-coverage-mismatch")
            if not (neutral["orderABVerdict"] == "APPLY" and neutral["orderBAVerdict"] == "APPLY" and neutral["orderStable"] is True and neutral["verdict"] == "APPLY" and neutral["unresolvedFalsifierCount"] == 0):
                candidate_gates.append("neutral-verdict-not-apply")
            deletion_row = deletion_by_id.get(candidate_id)
            if candidate_row["candidateKind"] == "DELETE" and deletion_row is not None and deletion_row["inactiveOrArchive"] is True:
                deletion_eligible = False
                candidate_gates.append("legacy-inactive-reported")
                primary_failure = "LEGACY_INACTIVE_REPORTED"
            else:
                deletion_eligible, deletion_gates = _deletion_hard_gates(candidate_row, deletion_row, evidence_ids)
                candidate_gates.extend(deletion_gates)
            evaluated = {"candidateId": candidate_row["candidateId"], "computedGoalScore": score_resolver(candidate_row["scoreComponents"]), "scoreComponents": candidate_row["scoreComponents"], "hardGates": sorted(set(candidate_gates)), "deletionEligible": deletion_eligible}
            evaluated_rows.append(evaluated)
            all_gates.extend(evaluated["hardGates"])
        return {"autopatchEnabled": top["autopatchEnabled"], "evaluatedCandidates": evaluated_rows, "hardGates": sorted(set(all_gates)), "primaryFailureClass": primary_failure}
    except GradeInputError as exc:
        if raise_input_errors:
            raise
        return {"failureReason": exc.reason_code}


def _grade_candidates_core(prepared: dict[str, object]) -> dict[str, object]:
    """Pure selection over an already sealed, validated, and scored snapshot."""
    if "failureReason" in prepared:
        return _failure(str(prepared["failureReason"]))
    evaluated_rows = prepared["evaluatedCandidates"]
    hard_gates = prepared["hardGates"]
    eligible = [row for row in evaluated_rows if row["computedGoalScore"] >= AUTOPATCH_THRESHOLD and not row["hardGates"]]
    eligible.sort(key=_candidate_sort_key)
    if prepared["autopatchEnabled"] is not True:
        return {"schemaVersion": SCHEMA_OUTPUT, "verdict": "HOLD", "failureClass": "autopatch-disabled", "selectedCandidateId": None, "autopatchEligible": False, "deletionEligible": False, "nextSingleAction": "ENABLE_AUTOPATCH_WITH_EXPLICIT_AUTHORIZATION", "hardGates": hard_gates, "hardGateCount": len(hard_gates), "evaluatedCandidates": evaluated_rows}
    if not eligible:
        return {"schemaVersion": SCHEMA_OUTPUT, "verdict": "HOLD", "failureClass": prepared.get("primaryFailureClass") or "no-eligible-candidate", "selectedCandidateId": None, "autopatchEligible": False, "deletionEligible": False, "nextSingleAction": "REPAIR_HARD_GATES_OR_EVIDENCE", "hardGates": hard_gates, "hardGateCount": len(hard_gates), "evaluatedCandidates": evaluated_rows}
    selected = eligible[0]
    return {"schemaVersion": SCHEMA_OUTPUT, "verdict": "APPLY", "failureClass": None, "selectedCandidateId": selected["candidateId"], "autopatchEligible": True, "deletionEligible": selected["deletionEligible"], "nextSingleAction": "ENTER_EXISTING_MUTATION_GUARD", "hardGates": hard_gates, "hardGateCount": len(hard_gates), "evaluatedCandidates": evaluated_rows}


def grade_candidates(
    payload: dict[str, object], input_size: int, run_root: Path,
    score_resolver: Callable[[dict[str, float]], float] = measure_goal_score, *,
    raise_input_errors: bool = False,
) -> dict[str, object]:
    prepared = _prepare_grade_candidates(payload, input_size, run_root, score_resolver, raise_input_errors=raise_input_errors)
    first = _grade_candidates_core(prepared)
    second = _grade_candidates_core(prepared)
    first_bytes = canonical_json_bytes(first)
    if first_bytes != canonical_json_bytes(second):
        failed = dict(first)
        failed["hardGates"] = sorted(set(list(first["hardGates"]) + ["deterministic-replay-failed"]))
        failed["hardGateCount"] = len(failed["hardGates"])
        failed["autopatchEligible"] = False
        failed["verdict"] = "HOLD"
        failed["failureClass"] = "deterministic-replay-failed"
        failed["nextSingleAction"] = "REPAIR_DETERMINISTIC_GRADER"
        failed["deterministicReplayHash"] = None
        return failed
    result = dict(first)
    result["deterministicReplayHash"] = hashlib.sha256(first_bytes).hexdigest()
    return result


def _validated_cli_paths(
    input_value: str,
    output_value: str | None,
    run_root_value: str | None,
) -> tuple[Path, Path | None, Path]:
    if run_root_value is None:
        raise GradeInputError("run-root-required")
    try:
        run_root = Path(run_root_value).resolve(strict=True)
        input_path = Path(input_value).resolve(strict=True)
    except OSError as exc:
        raise GradeInputError("input-path-invalid") from exc
    if not run_root.is_dir() or not _is_within(input_path, run_root):
        raise GradeInputError("input-outside-run-root")
    if output_value is None:
        return input_path, None, run_root
    output_path = Path(output_value).resolve(strict=False)
    if not _is_within(output_path, run_root):
        raise GradeInputError("output-outside-run-root")
    if os.path.normcase(str(output_path)) == os.path.normcase(str(input_path)):
        raise GradeInputError("output-input-collision")
    try:
        if output_path.exists() and os.path.samefile(output_path, input_path):
            raise GradeInputError("output-input-collision")
        parent = output_path.parent.resolve(strict=True)
    except OSError as exc:
        raise GradeInputError("output-parent-invalid") from exc
    if not parent.is_dir() or parent != output_path.parent:
        raise GradeInputError("output-parent-invalid")
    return input_path, output_path, run_root


def _load_payload(path: Path) -> tuple[dict[str, object], int]:
    data = path.read_bytes()
    if len(data) > MAX_INPUT_BYTES:
        raise GradeInputError("resource-bound-exceeded")
    try:
        payload = json.loads(data)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GradeInputError("malformed-json") from exc
    if not isinstance(payload, dict):
        raise GradeInputError("invalid-type")
    return payload, len(data)


def _write_atomic(path: Path, data: bytes) -> None:
    if len(data) > MAX_OUTPUT_BYTES:
        raise GradeInputError("resource-bound-exceeded")
    if path.exists():
        raise GradeInputError("output-already-exists")
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + ".", delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    try:
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def _write_ready_result(path: Path, data: bytes) -> None:
    digest = hashlib.sha256(data).hexdigest()
    sidecar_path = Path(str(path) + ".sha256")
    ready_path = Path(str(path) + ".ready")
    if path.exists() or sidecar_path.exists() or ready_path.exists():
        raise GradeInputError("output-already-exists")
    _write_atomic(path, data)
    _write_atomic(sidecar_path, (digest + "\n").encode("ascii"))
    if path.read_bytes() != data or sidecar_path.read_text(encoding="ascii").strip() != digest:
        raise GradeInputError("artifact-publication-nonatomic")
    _write_atomic(ready_path, (digest + "\n").encode("ascii"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output")
    parser.add_argument("--run-root")
    args = parser.parse_args(argv)
    try:
        input_path, output_path, run_root = _validated_cli_paths(args.input, args.output, args.run_root)
        payload, input_size = _load_payload(input_path)
        _safe_scan(payload)
        if payload.get("schemaVersion") != SCHEMA_INPUT:
            raise GradeInputError("schema-version-invalid")
        result = grade_candidates(payload, input_size, run_root, raise_input_errors=True)
        result_bytes = canonical_json_bytes(result)
        if len(result_bytes) > MAX_OUTPUT_BYTES:
            raise GradeInputError("resource-bound-exceeded")
        if output_path is None:
            sys.stdout.buffer.write(result_bytes)
        else:
            _write_ready_result(output_path, result_bytes)
        return 0 if result["verdict"] == "APPLY" else 1
    except GradeInputError as exc:
        sys.stderr.write(exc.reason_code + "\n")
        return 2
    except Exception:
        sys.stderr.write("internal-error\n")
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
