#!/usr/bin/env python3
"""Validate sealed Notebook-to-Desktop goal directive packets."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from pathlib import Path
from typing import Any, Iterable


INPUT_LIMIT_BYTES = 1_048_576
MAX_DEPTH = 24
MAX_NODES = 100_000
MAX_EVIDENCE_ROWS = 20
POSITIVE_MAX_CHARS = 2_400
NEGATIVE_MAX_CHARS = 2_400
NEUTRAL_MAX_CHARS = 1_800

TOP_LEVEL_KEYS = {
    "schemaVersion",
    "userRequest",
    "evidenceSnapshot",
    "evidenceSnapshotHash",
    "requiresLiteralSubagents",
    "processMode",
    "actualAgentCount",
    "packets",
}
EVIDENCE_SNAPSHOT_KEYS = {"summary", "evidenceRows"}
EVIDENCE_ROW_KEYS = {
    "evidenceId",
    "owner",
    "observedAt",
    "observation",
    "verificationCommand",
}
POSITIVE_KEYS = {
    "packetType",
    "evidenceSnapshotHash",
    "candidateGoal",
    "scenarioWorlds",
    "validatedAssumptions",
    "reusableAssets",
    "expectedUserValue",
    "minimalVerification",
    "evidenceIds",
    "unknowns",
}
SCENARIO_KEYS = {
    "scenarioId",
    "premise",
    "causalMechanism",
    "expectedObservation",
    "evidenceNeeded",
    "falsifier",
}
NEGATIVE_KEYS = {
    "packetType",
    "evidenceSnapshotHash",
    "challengedGoal",
    "scenarioAttacks",
    "falsifiers",
    "counterExamples",
    "authorityRisks",
    "safetyRisks",
    "missingEvidence",
    "smallestDisconfirmingProbe",
    "evidenceIds",
}
ATTACK_KEYS = {
    "scenarioId",
    "counterExample",
    "alternativeCause",
    "boundaryOrAuthorityRisk",
    "costAndBlastRadius",
    "smallestDisconfirmingProbe",
    "evidenceIds",
}
NEUTRAL_KEYS = {
    "packetType",
    "evidenceSnapshotHash",
    "forwardOrder",
    "reverseOrder",
    "forwardVerdict",
    "reverseVerdict",
    "forwardDecisiveEvidenceIds",
    "reverseDecisiveEvidenceIds",
    "orderStable",
    "verdict",
    "selectedOrRewrittenGoal",
    "scoreInputs",
    "goalScore",
    "decisiveEvidence",
    "rejectedClaims",
    "nextSingleProof",
    "confidence",
}
SCORE_KEYS = (
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
SCORE_INPUT_KEYS = {"value", "evidenceIds"}

PACKET_NAMES = {"POSITIVE_QUERY", "NEGATIVE_QUERY", "NEUTRAL_QUERY"}
VERDICTS = {"APPLY", "HOLD", "REJECT"}
OBSERVATION_KEYS = {
    "reason",
    "status",
    "count",
    "hash",
    "present",
    "verified",
    "value",
}
_SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
_SECRET_KEY_RE = re.compile(
    r"(?:api[_-]?key|authorization|client[_-]?secret|password|passwd|"
    r"private[_-]?key|owner[_-]?token|access[_-]?token|refresh[_-]?token|cookie)",
    re.IGNORECASE,
)
_SECRET_VALUE_RES = (
    re.compile(r"(?:^|[^a-z0-9])sk-[a-z0-9_-]{10,}", re.IGNORECASE),
    re.compile(r"(?:^|\s)bearer\s+[a-z0-9._~+/=-]{10,}", re.IGNORECASE),
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"(?:^|[^a-z0-9])(?:ghp_|github_pat_|xox[baprs]-|sbp_)[a-z0-9_-]{8,}", re.IGNORECASE),
)


def canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    )


def _is_secret_like(value: str) -> bool:
    return any(pattern.search(value) for pattern in _SECRET_VALUE_RES)


def _structure_failure(value: Any) -> str | None:
    stack = [(value, 0)]
    nodes = 0
    while stack:
        current, depth = stack.pop()
        if depth > MAX_DEPTH:
            return "structure-depth-exceeded"
        nodes += 1
        if nodes > MAX_NODES:
            return "structure-node-limit-exceeded"
        if current is None or isinstance(current, bool) or isinstance(current, int):
            continue
        if isinstance(current, float):
            if not math.isfinite(current):
                return "non-finite-number"
            continue
        if isinstance(current, str):
            if _is_secret_like(current):
                return "redaction-failed"
            continue
        if isinstance(current, list):
            stack.extend((item, depth + 1) for item in current)
            continue
        if isinstance(current, dict):
            for key, item in current.items():
                if not isinstance(key, str):
                    return "schema-invalid"
                if _SECRET_KEY_RE.search(key) or _is_secret_like(key):
                    return "redaction-failed"
                stack.append((item, depth + 1))
            continue
        return "schema-invalid"
    return None


def _add(failures: list[str], reason: str) -> None:
    if reason not in failures:
        failures.append(reason)


def _exact_keys(value: Any, expected: set[str], failures: list[str]) -> bool:
    if not isinstance(value, dict) or set(value) != expected:
        _add(failures, "schema-invalid")
        return False
    return True


def _nonempty_string(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _string_list(value: Any) -> bool:
    return isinstance(value, list) and all(_nonempty_string(item) for item in value)


def _evidence_references(value: Any) -> set[str] | None:
    if not _string_list(value):
        return None
    return set(value)


def _validate_evidence_snapshot(
    snapshot: Any, failures: list[str]
) -> tuple[str | None, set[str]]:
    if not _exact_keys(snapshot, EVIDENCE_SNAPSHOT_KEYS, failures):
        return None, set()
    if not _nonempty_string(snapshot["summary"]):
        _add(failures, "schema-invalid")
    rows = snapshot["evidenceRows"]
    if not isinstance(rows, list) or not rows or len(rows) > MAX_EVIDENCE_ROWS:
        _add(failures, "schema-invalid")
        return None, set()

    evidence_ids: set[str] = set()
    for row in rows:
        if not _exact_keys(row, EVIDENCE_ROW_KEYS, failures):
            continue
        for key in ("evidenceId", "owner", "observedAt", "verificationCommand"):
            if not _nonempty_string(row[key]):
                _add(failures, "schema-invalid")
        evidence_id = row["evidenceId"]
        if isinstance(evidence_id, str):
            if evidence_id in evidence_ids:
                _add(failures, "duplicate-evidence-id")
            evidence_ids.add(evidence_id)
        observation = row["observation"]
        if (
            not isinstance(observation, dict)
            or len(observation) != 1
            or next(iter(observation), None) not in OBSERVATION_KEYS
            or isinstance(next(iter(observation.values()), None), (dict, list))
        ):
            _add(failures, "observation-shape-invalid")

    try:
        snapshot_hash = hashlib.sha256(
            canonical_json(snapshot).encode("utf-8")
        ).hexdigest()
    except (TypeError, ValueError, OverflowError):
        _add(failures, "schema-invalid")
        return None, evidence_ids
    return snapshot_hash, evidence_ids


def _validate_reference_set(
    value: Any, available: set[str], failures: list[str]
) -> set[str]:
    references = _evidence_references(value)
    if references is None:
        _add(failures, "schema-invalid")
        return set()
    if not references.issubset(available):
        _add(failures, "unsupported-evidence-id")
    return references


def _validate_positive(
    packet: Any,
    snapshot_hash: str,
    evidence_ids: set[str],
    failures: list[str],
) -> set[str]:
    if not _exact_keys(packet, POSITIVE_KEYS, failures):
        return set()
    if packet["packetType"] != "POSITIVE_QUERY" or packet["evidenceSnapshotHash"] != snapshot_hash:
        _add(failures, "packet-snapshot-mismatch")
    for key in ("candidateGoal", "expectedUserValue", "minimalVerification"):
        if not _nonempty_string(packet[key]):
            _add(failures, "schema-invalid")
    for key in ("validatedAssumptions", "reusableAssets", "evidenceIds", "unknowns"):
        if not _string_list(packet[key]) and packet[key] != []:
            _add(failures, "schema-invalid")
    _validate_reference_set(packet["evidenceIds"], evidence_ids, failures)

    worlds = packet["scenarioWorlds"]
    if not isinstance(worlds, list) or not 2 <= len(worlds) <= 4:
        _add(failures, "scenario-count-invalid")
        return set()
    scenario_ids: set[str] = set()
    for world in worlds:
        if not _exact_keys(world, SCENARIO_KEYS, failures):
            continue
        for key in (
            "scenarioId",
            "premise",
            "causalMechanism",
            "expectedObservation",
            "falsifier",
        ):
            if not _nonempty_string(world[key]):
                _add(failures, "schema-invalid")
        scenario_id = world["scenarioId"]
        if isinstance(scenario_id, str):
            if scenario_id in scenario_ids:
                _add(failures, "duplicate-scenario-id")
            scenario_ids.add(scenario_id)
        _validate_reference_set(world["evidenceNeeded"], evidence_ids, failures)
    return scenario_ids


def _validate_negative(
    packet: Any,
    snapshot_hash: str,
    evidence_ids: set[str],
    expected_scenarios: set[str],
    failures: list[str],
) -> None:
    if not _exact_keys(packet, NEGATIVE_KEYS, failures):
        return
    if packet["packetType"] != "NEGATIVE_QUERY" or packet["evidenceSnapshotHash"] != snapshot_hash:
        _add(failures, "packet-snapshot-mismatch")
    for key in ("challengedGoal", "smallestDisconfirmingProbe"):
        if not _nonempty_string(packet[key]):
            _add(failures, "schema-invalid")
    for key in (
        "falsifiers",
        "counterExamples",
        "authorityRisks",
        "safetyRisks",
        "missingEvidence",
        "evidenceIds",
    ):
        if not _string_list(packet[key]) and packet[key] != []:
            _add(failures, "schema-invalid")
    _validate_reference_set(packet["evidenceIds"], evidence_ids, failures)

    attacks = packet["scenarioAttacks"]
    if not isinstance(attacks, list):
        _add(failures, "schema-invalid")
        return
    observed_scenarios: list[str] = []
    for attack in attacks:
        if not _exact_keys(attack, ATTACK_KEYS, failures):
            continue
        for key in ATTACK_KEYS - {"evidenceIds"}:
            if not _nonempty_string(attack[key]):
                _add(failures, "schema-invalid")
        observed_scenarios.append(attack["scenarioId"])
        _validate_reference_set(attack["evidenceIds"], evidence_ids, failures)
    if (
        len(observed_scenarios) != len(set(observed_scenarios))
        or set(observed_scenarios) != expected_scenarios
    ):
        _add(failures, "scenario-coverage-mismatch")


def _score_value(value: Any, failures: list[str]) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        _add(failures, "score-input-invalid")
        return None
    numeric = float(value)
    if not math.isfinite(numeric) or numeric < 0.0 or numeric > 1.0:
        _add(failures, "score-input-invalid")
        return None
    return numeric


def _computed_goal_score(values: dict[str, float]) -> float:
    return 100.0 * (
        0.25 * values["evidenceStrength"]
        + 0.20 * values["causalStrength"]
        + 0.15 * values["verificationFeasibility"]
        + 0.15 * values["userValue"]
        + 0.10 * values["reversibility"]
        + 0.10 * values["costEfficiency"]
        + 0.05 * values["timeFit"]
        - 0.20 * values["blastRadius"]
        - 0.15 * values["ambiguity"]
        - 0.20 * values["authorityOrSafetyExpansion"]
    )


def _validate_neutral(
    packet: Any,
    snapshot_hash: str,
    evidence_ids: set[str],
    failures: list[str],
) -> None:
    if not _exact_keys(packet, NEUTRAL_KEYS, failures):
        return
    if packet["packetType"] != "NEUTRAL_QUERY" or packet["evidenceSnapshotHash"] != snapshot_hash:
        _add(failures, "packet-snapshot-mismatch")
    if packet["forwardOrder"] != ["POSITIVE_QUERY", "NEGATIVE_QUERY"]:
        _add(failures, "order-contract-invalid")
    if packet["reverseOrder"] != ["NEGATIVE_QUERY", "POSITIVE_QUERY"]:
        _add(failures, "order-contract-invalid")
    for key in ("forwardVerdict", "reverseVerdict", "verdict"):
        if packet[key] not in VERDICTS:
            _add(failures, "schema-invalid")
    if packet["confidence"] not in {"L", "M", "H"}:
        _add(failures, "schema-invalid")
    for key in ("selectedOrRewrittenGoal", "nextSingleProof"):
        if not _nonempty_string(packet[key]):
            _add(failures, "schema-invalid")
    if not isinstance(packet["orderStable"], bool):
        _add(failures, "schema-invalid")

    forward_evidence = _validate_reference_set(
        packet["forwardDecisiveEvidenceIds"], evidence_ids, failures
    )
    reverse_evidence = _validate_reference_set(
        packet["reverseDecisiveEvidenceIds"], evidence_ids, failures
    )
    _validate_reference_set(packet["decisiveEvidence"], evidence_ids, failures)
    if not _string_list(packet["rejectedClaims"]) and packet["rejectedClaims"] != []:
        _add(failures, "schema-invalid")

    evidence_agrees = forward_evidence == reverse_evidence
    verdict_agrees = packet["forwardVerdict"] == packet["reverseVerdict"]
    computed_stable = evidence_agrees and verdict_agrees
    if packet["orderStable"] != computed_stable:
        _add(failures, "order-stability-mismatch")
    if not computed_stable:
        if packet["orderStable"] or packet["verdict"] != "HOLD":
            _add(failures, "order-unstable")
    elif packet["verdict"] != packet["forwardVerdict"]:
        _add(failures, "neutral-verdict-mismatch")

    score_inputs = packet["scoreInputs"]
    score_values: dict[str, float] = {}
    if not isinstance(score_inputs, dict) or set(score_inputs) != set(SCORE_KEYS):
        _add(failures, "schema-invalid")
    else:
        for name in SCORE_KEYS:
            score_input = score_inputs[name]
            if not _exact_keys(score_input, SCORE_INPUT_KEYS, failures):
                continue
            numeric = _score_value(score_input["value"], failures)
            if numeric is not None:
                score_values[name] = numeric
            _validate_reference_set(score_input["evidenceIds"], evidence_ids, failures)

    goal_score = packet["goalScore"]
    if isinstance(goal_score, bool) or not isinstance(goal_score, (int, float)):
        _add(failures, "goal-score-invalid")
        goal_score_value = None
    else:
        goal_score_value = float(goal_score)
        if not math.isfinite(goal_score_value):
            _add(failures, "goal-score-invalid")
            goal_score_value = None
    if len(score_values) == len(SCORE_KEYS) and goal_score_value is not None:
        expected_score = max(0.0, min(100.0, _computed_goal_score(score_values)))
        if not math.isclose(goal_score_value, expected_score, rel_tol=0.0, abs_tol=1e-9):
            _add(failures, "goal-score-mismatch")
        if expected_score < 50.0 and packet["verdict"] == "APPLY":
            _add(failures, "goal-score-below-threshold")


def validate(document: Any) -> dict[str, Any]:
    failures: list[str] = []
    structure_failure = _structure_failure(document)
    if structure_failure is not None:
        return {"failureClasses": [structure_failure], "valid": False}
    if not _exact_keys(document, TOP_LEVEL_KEYS, failures):
        return {"failureClasses": failures, "valid": False}
    if document["schemaVersion"] != "1.0" or not _nonempty_string(document["userRequest"]):
        _add(failures, "schema-invalid")
    if not isinstance(document["requiresLiteralSubagents"], bool):
        _add(failures, "schema-invalid")
    if isinstance(document["actualAgentCount"], bool) or not isinstance(
        document["actualAgentCount"], int
    ):
        _add(failures, "schema-invalid")
    elif document["requiresLiteralSubagents"]:
        if document["processMode"] != "three-subagents" or document["actualAgentCount"] != 3:
            _add(failures, "literal-agent-contract-invalid")
    elif (
        document["processMode"] != "single-agent-logical-roles"
        or document["actualAgentCount"] != 1
    ):
        _add(failures, "process-mode-invalid")

    snapshot_hash, evidence_ids = _validate_evidence_snapshot(
        document["evidenceSnapshot"], failures
    )
    declared_hash = document["evidenceSnapshotHash"]
    if not isinstance(declared_hash, str) or not _SHA256_RE.fullmatch(declared_hash):
        _add(failures, "invalid-snapshot-hash")
        normalized_hash = ""
    else:
        normalized_hash = declared_hash.lower()
    if snapshot_hash is not None and normalized_hash != snapshot_hash:
        _add(failures, "evidence-snapshot-hash-mismatch")

    packets = document["packets"]
    if not isinstance(packets, dict) or set(packets) != PACKET_NAMES:
        _add(failures, "schema-invalid")
        return {"failureClasses": failures, "valid": False}
    positive = packets["POSITIVE_QUERY"]
    negative = packets["NEGATIVE_QUERY"]
    neutral = packets["NEUTRAL_QUERY"]
    scenario_ids = _validate_positive(
        positive, normalized_hash, evidence_ids, failures
    )
    _validate_negative(
        negative, normalized_hash, evidence_ids, scenario_ids, failures
    )
    _validate_neutral(neutral, normalized_hash, evidence_ids, failures)

    if (
        isinstance(positive, dict)
        and isinstance(negative, dict)
        and positive.get("candidateGoal") != negative.get("challengedGoal")
    ):
        _add(failures, "goal-contract-mismatch")
    for packet, limit in (
        (positive, POSITIVE_MAX_CHARS),
        (negative, NEGATIVE_MAX_CHARS),
        (neutral, NEUTRAL_MAX_CHARS),
    ):
        try:
            if len(canonical_json(packet)) > limit:
                _add(failures, "packet-bound-exceeded")
        except (TypeError, ValueError, OverflowError):
            _add(failures, "schema-invalid")

    return {"failureClasses": failures, "valid": not failures}


def _write_json_line(value: Any) -> None:
    payload = canonical_json(value).encode("utf-8") + b"\n"
    binary_stdout = getattr(sys.stdout, "buffer", None)
    if binary_stdout is not None:
        binary_stdout.write(payload)
        binary_stdout.flush()
    else:
        sys.stdout.write(payload.decode("utf-8"))


def _load_input(path: Path) -> Any:
    try:
        with path.open("rb") as handle:
            payload = handle.read(INPUT_LIMIT_BYTES + 1)
    except OSError as failure:
        raise ValueError("input-unavailable") from failure
    if len(payload) > INPUT_LIMIT_BYTES:
        raise ValueError("input-too-large")
    try:
        return json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as failure:
        raise ValueError("invalid-json") from failure


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("--input", required=True)
    try:
        args = parser.parse_args(argv)
        document = _load_input(Path(args.input))
        result = validate(document)
        _write_json_line(result)
        return 0 if result["valid"] else 2
    except ValueError as failure:
        reason = str(failure)
        if reason not in {"input-unavailable", "input-too-large", "invalid-json"}:
            reason = "invalid-input"
        _write_json_line({"failureClasses": [reason], "valid": False})
        return 2
    except BaseException:
        _write_json_line(
            {"failureClasses": ["internal-validator-failure"], "valid": False}
        )
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
