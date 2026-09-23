"""Bounded, offline structural grader for three-way long-tail query artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import unicodedata
from collections import deque
from pathlib import Path
from typing import Any, Sequence

INPUT_LIMIT_BYTES = 1_048_576
OUTPUT_LIMIT_BYTES = 65_536
MAX_CLAIMS = 256
MIN_WORLDS = 2
MAX_WORLDS = 4
WEIGHTS = {
    "evidenceCoverage": 20,
    "evidenceDirectnessAuthority": 15,
    "falsifiability": 15,
    "causalDiscrimination": 15,
    "longTailScenarioCoverage": 10,
    "branchIndependence": 10,
    "orderStability": 5,
    "boundednessAndRedaction": 5,
    "nextProofActionability": 5,
}
DIRECTNESS = {"direct": 1.0, "indirect": 0.5, "claim_only": 0.0}
AUTHORITY = {"authoritative": 1.0, "official_primary": 0.9, "supporting": 0.5, "unverified": 0.0}
FRESHNESS = {"current": 1.0, "dated": 0.75, "stale": 0.0}
UPLIFT_THRESHOLDS = {
    "minimumCaseCount": 12,
    "minimumCandidateScore": 80.0,
    "minimumTotalDelta": 10.0,
    "minimumEvidenceCoverageDelta": 0.10,
    "maximumSingleMetricRegression": 0.05,
}

_HASH = re.compile(r"^sha256:[0-9a-f]{64}$")
_SECRET_KEY = re.compile(r"(?:api[_-]?key|token|secret|authorization|password|credential)", re.I)
_SECRET_VALUE = re.compile(r"(?:sk-[a-z0-9_-]{8,}|bearer\s+\S+|-----begin\s+(?:rsa |ec )?private key-----)", re.I)
_SUPABASE_SCOPE_FIELDS = frozenset({
    "evidenceId", "directness", "authority", "freshness", "independenceGroup",
    "verificationAction", "projectRefHash", "readOnly", "observedAt", "toolTraceRef",
})
_SAFE_EVIDENCE_HASH_FIELDS = frozenset({"promptHash", "queryHash", "responseHash", "optionsHash", "bodyHash"})
_EVIDENCE_REQUIRED_FIELDS = frozenset({
    "evidenceId", "directness", "authority", "freshness", "independenceGroup", "verificationAction",
})
_EVIDENCE_FIELDS = _SUPABASE_SCOPE_FIELDS | _SAFE_EVIDENCE_HASH_FIELDS
_TOP_LEVEL_REQUIRED_FIELDS = frozenset({
    "schemaVersion", "rubricVersion", "fixtureDeckHash", "caseCount", "evidenceSnapshot",
    "positivePacket", "negativePacket", "neutralPacket",
})
_TOP_LEVEL_FIELDS = _TOP_LEVEL_REQUIRED_FIELDS | {"baseline"}
_POSITIVE_FIELDS = frozenset({
    "packetType", "evidenceSnapshotHash", "candidateGoal", "scenarioWorlds", "noneOrUnknown",
    "validatedAssumptions", "reusableAssets", "expectedUserValue", "minimalVerification",
    "evidenceIds", "unknowns", "claims",
})
_WORLD_FIELDS = frozenset({
    "scenarioId", "premise", "causalMechanism", "expectedObservation", "evidenceNeeded",
    "falsifier", "baseRateStatus",
})
_NEGATIVE_FIELDS = frozenset({
    "packetType", "evidenceSnapshotHash", "challengedGoal", "scenarioAttacks", "falsifiers",
    "missingEvidence", "safetyRisks", "claims",
})
_ATTACK_FIELDS = frozenset({
    "scenarioId", "counterexample", "alternativeCause", "boundaryOrAuthorityRisk",
    "costAndBlastRadius", "smallestDisconfirmingProbe", "evidenceIds",
})
_NEUTRAL_FIELDS = frozenset({
    "packetType", "evidenceSnapshotHash", "forwardOrder", "reverseOrder", "forwardVerdict",
    "reverseVerdict", "forwardDecisiveEvidenceIds", "reverseDecisiveEvidenceIds", "orderStable",
    "verdict", "selectedOrRewrittenGoal", "goalScore", "decisiveEvidence", "rejectedClaims",
    "nextSingleProof", "confidence", "artifactVerdict", "runtimeLineageVerdict", "claims",
})
_CLAIM_FIELDS = frozenset({"claimId", "text", "evidenceIds"})
_VERDICTS = frozenset({"APPLY", "HOLD", "REJECT"})
_PROHIBITED_RAW_KEYS = frozenset({
    "rawprompt", "rawquery", "rawresponse", "rawoptions", "response", "cookie", "cookies",
    "headers", "credentials", "databaseurl", "fullenvironmentdump",
})
_PROHIBITED_AUTHORITY_KEYS = frozenset({
    "authorization", "authorizedmutation", "authorizedmutationsurface", "mutationauthorization",
    "mutationauthorized", "sourceowner", "owner", "canmutate", "writeallowed", "permission", "permissions",
})
_SCAN_MAX_DEPTH = 32
_SCAN_MAX_NODES = 4096
_PACKETS = {
    "positivePacket": "POSITIVE_QUERY",
    "negativePacket": "NEGATIVE_QUERY",
    "neutralPacket": "NEUTRAL_QUERY",
}


class GradeInputError(ValueError):
    def __init__(self, reason_code: str):
        super().__init__(reason_code)
        self.reason_code = reason_code


def load_payload(path: Path) -> tuple[dict[str, Any], int]:
    try:
        raw = path.read_bytes()
        if len(raw) > INPUT_LIMIT_BYTES:
            raise GradeInputError("input-size-exceeded")
        payload = json.loads(raw.decode("utf-8"))
    except GradeInputError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise GradeInputError("malformed-json") from exc
    if not isinstance(payload, dict):
        raise GradeInputError("malformed-json")
    return payload, len(raw)


def _nonempty(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _add_once(reasons: list[str], reason: str) -> None:
    if reason not in reasons:
        reasons.append(reason)


def _normalized_key(value: Any) -> str:
    return "".join(character for character in str(value).casefold() if character.isalnum())


def _string_list(value: Any, *, nonempty: bool = False) -> bool:
    return (
        isinstance(value, list)
        and (not nonempty or bool(value))
        and all(_nonempty(item) for item in value)
    )


def _finite_score(value: Any) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(float(value))
        and 0.0 <= float(value) <= 100.0
    )


def _contains_normalized_key(value: Any, prohibited: frozenset[str]) -> bool:
    nodes = 0
    queue: deque[tuple[Any, int]] = deque([(value, 0)])
    while queue:
        current, depth = queue.popleft()
        nodes += 1
        if nodes > _SCAN_MAX_NODES or depth > _SCAN_MAX_DEPTH:
            return False
        if isinstance(current, dict):
            for key, item in current.items():
                if _normalized_key(key) in prohibited:
                    return True
                queue.append((item, depth + 1))
        elif isinstance(current, list):
            queue.extend((item, depth + 1) for item in current)
    return False


def _secret_scan_reason(value: Any) -> str | None:
    """Bounded iterative scan: never recurse through attacker-controlled JSON."""
    nodes = 0
    queue: deque[tuple[Any, str, int]] = deque([(value, "", 0)])
    while queue:
        current, key, depth = queue.popleft()
        nodes += 1
        if nodes > _SCAN_MAX_NODES or depth > _SCAN_MAX_DEPTH:
            return "structure-depth-exceeded"
        if _normalized_key(key) in _PROHIBITED_RAW_KEYS:
            return "secret-pattern-risk"
        if _SECRET_KEY.search(key) and key not in {"evidenceSnapshotHash", "fixtureDeckHash"}:
            return "secret-like-content"
        if isinstance(current, str) and _SECRET_VALUE.search(current):
            return "secret-like-content"
        if isinstance(current, dict):
            queue.extend((item, str(item_key), depth + 1) for item_key, item in current.items())
        elif isinstance(current, list):
            queue.extend((item, "", depth + 1) for item in current)
    return None


def _unsafe_secret_reason(payload: dict[str, Any]) -> str | None:
    """Check output-reflected identifiers before a bounded generic traversal."""
    for name in ("rubricVersion", "fixtureDeckHash"):
        value = payload.get(name)
        if isinstance(value, str) and _SECRET_VALUE.search(value):
            return "secret-like-content"
    return _secret_scan_reason(payload)


def _claim_has_unresolved_evidence(claim: Any, evidence_ids: set[str]) -> bool:
    if not isinstance(claim, dict) or not _nonempty(claim.get("claimId")) or not _nonempty(claim.get("text")):
        return True
    claim_ids = claim.get("evidenceIds")
    return not isinstance(claim_ids, list) or not claim_ids or any(
        not isinstance(evidence_id, str) or evidence_id not in evidence_ids
        for evidence_id in claim_ids
    )


def _claim_schema_valid(claim: Any) -> bool:
    return (
        isinstance(claim, dict)
        and set(claim) == _CLAIM_FIELDS
        and _nonempty(claim.get("claimId"))
        and _nonempty(claim.get("text"))
        and _string_list(claim.get("evidenceIds"), nonempty=True)
    )


def _packet_claims_schema_valid(packet: dict[str, Any]) -> bool:
    claims = packet.get("claims")
    return isinstance(claims, list) and all(_claim_schema_valid(claim) for claim in claims)


def _positive_schema_valid(packet: Any) -> bool:
    if not isinstance(packet, dict) or set(packet) != _POSITIVE_FIELDS:
        return False
    worlds = packet.get("scenarioWorlds")
    none_or_unknown = packet.get("noneOrUnknown")
    return (
        packet.get("packetType") == "POSITIVE_QUERY"
        and isinstance(packet.get("evidenceSnapshotHash"), str)
        and _nonempty(packet.get("candidateGoal"))
        and isinstance(worlds, list)
        and all(
            isinstance(world, dict)
            and set(world) == _WORLD_FIELDS
            and all(_nonempty(world.get(field)) for field in (
                "scenarioId", "premise", "causalMechanism", "expectedObservation", "falsifier", "baseRateStatus"
            ))
            and _string_list(world.get("evidenceNeeded"))
            for world in worlds
        )
        and isinstance(none_or_unknown, dict)
        and set(none_or_unknown) == {"present", "reason"}
        and none_or_unknown.get("present") is True
        and _nonempty(none_or_unknown.get("reason"))
        and _string_list(packet.get("validatedAssumptions"))
        and _string_list(packet.get("reusableAssets"))
        and _nonempty(packet.get("expectedUserValue"))
        and _nonempty(packet.get("minimalVerification"))
        and _string_list(packet.get("evidenceIds"))
        and _string_list(packet.get("unknowns"))
        and _packet_claims_schema_valid(packet)
    )


def _negative_schema_valid(packet: Any) -> bool:
    if not isinstance(packet, dict) or set(packet) != _NEGATIVE_FIELDS:
        return False
    attacks = packet.get("scenarioAttacks")
    return (
        packet.get("packetType") == "NEGATIVE_QUERY"
        and isinstance(packet.get("evidenceSnapshotHash"), str)
        and _nonempty(packet.get("challengedGoal"))
        and isinstance(attacks, list)
        and all(
            isinstance(attack, dict)
            and set(attack) == _ATTACK_FIELDS
            and all(_nonempty(attack.get(field)) for field in (
                "scenarioId", "counterexample", "alternativeCause", "boundaryOrAuthorityRisk",
                "costAndBlastRadius", "smallestDisconfirmingProbe",
            ))
            and _string_list(attack.get("evidenceIds"))
            for attack in attacks
        )
        and _string_list(packet.get("falsifiers"))
        and _string_list(packet.get("missingEvidence"))
        and _string_list(packet.get("safetyRisks"))
        and _packet_claims_schema_valid(packet)
    )


def _neutral_schema_valid(packet: Any) -> bool:
    if not isinstance(packet, dict) or set(packet) != _NEUTRAL_FIELDS:
        return False
    proof = packet.get("nextSingleProof")
    return (
        packet.get("packetType") == "NEUTRAL_QUERY"
        and isinstance(packet.get("evidenceSnapshotHash"), str)
        and packet.get("forwardOrder") == ["POSITIVE_QUERY", "NEGATIVE_QUERY"]
        and packet.get("reverseOrder") == ["NEGATIVE_QUERY", "POSITIVE_QUERY"]
        and all(packet.get(field) in _VERDICTS for field in (
            "forwardVerdict", "reverseVerdict", "verdict", "artifactVerdict", "runtimeLineageVerdict"
        ))
        and _string_list(packet.get("forwardDecisiveEvidenceIds"), nonempty=True)
        and _string_list(packet.get("reverseDecisiveEvidenceIds"), nonempty=True)
        and isinstance(packet.get("orderStable"), bool)
        and _nonempty(packet.get("selectedOrRewrittenGoal"))
        and _finite_score(packet.get("goalScore"))
        and _string_list(packet.get("decisiveEvidence"), nonempty=True)
        and _string_list(packet.get("rejectedClaims"))
        and isinstance(proof, dict)
        and set(proof) == {"action", "decisionChange"}
        and _nonempty(proof.get("action"))
        and _nonempty(proof.get("decisionChange"))
        and packet.get("confidence") in {"L", "M", "H"}
        and _packet_claims_schema_valid(packet)
    )


def _evidence_snapshot_schema_valid(snapshot: Any) -> bool:
    if not isinstance(snapshot, dict) or set(snapshot) != {"evidenceSnapshotHash", "decisionDependsOnSupabase", "evidenceRows"}:
        return False
    rows = snapshot.get("evidenceRows")
    if (
        not isinstance(snapshot.get("evidenceSnapshotHash"), str)
        or not _HASH.fullmatch(snapshot["evidenceSnapshotHash"])
        or not isinstance(snapshot.get("decisionDependsOnSupabase"), bool)
        or not isinstance(rows, list)
    ):
        return False
    seen_ids: set[str] = set()
    for row in rows:
        if not isinstance(row, dict) or not _EVIDENCE_REQUIRED_FIELDS.issubset(row) or set(row) - _EVIDENCE_FIELDS:
            return False
        if (
            not _nonempty(row.get("evidenceId"))
            or row["evidenceId"] in seen_ids
            or row.get("directness") not in DIRECTNESS
            or row.get("authority") not in AUTHORITY
            or row.get("freshness") not in FRESHNESS
            or not _nonempty(row.get("independenceGroup"))
            or not _nonempty(row.get("verificationAction"))
            or any(not isinstance(row.get(field), str) or not _HASH.fullmatch(row[field])
                   for field in _SAFE_EVIDENCE_HASH_FIELDS if field in row)
        ):
            return False
        seen_ids.add(row["evidenceId"])
    return True


def collect_hard_gates(payload: dict[str, Any], input_size: int) -> dict[str, list[str]]:
    """Return independent artifact and runtime gate failures without network access."""
    artifact: list[str] = []
    runtime: list[str] = []
    if input_size > INPUT_LIMIT_BYTES:
        _add_once(artifact, "input-size-exceeded")
    top_level_keys = set(payload)
    if not _TOP_LEVEL_REQUIRED_FIELDS.issubset(top_level_keys) or top_level_keys - _TOP_LEVEL_FIELDS:
        _add_once(artifact, "branch-count-invalid")
    if payload.get("schemaVersion") != "awx.three-way-long-tail.eval.v1":
        _add_once(artifact, "schema-version-invalid")
    if payload.get("rubricVersion") != "awx.three-way-long-tail.rubric.v1":
        _add_once(artifact, "rubric-version-invalid")
    if not isinstance(payload.get("fixtureDeckHash"), str) or not _HASH.fullmatch(payload["fixtureDeckHash"]):
        _add_once(artifact, "fixture-deck-hash-invalid")

    packet_keys = {key for key in payload if key.endswith("Packet")}
    if packet_keys != set(_PACKETS):
        _add_once(artifact, "packet-set-invalid")
    packets = {name: payload.get(name) for name in _PACKETS}
    for name, expected_type in _PACKETS.items():
        if not isinstance(packets[name], dict) or packets[name].get("packetType") != expected_type:
            _add_once(artifact, "packet-type-invalid")
            _add_once(artifact, "packet-role-mismatch")
    if not _positive_schema_valid(packets["positivePacket"]):
        _add_once(artifact, "packet-role-mismatch")
    if not _negative_schema_valid(packets["negativePacket"]):
        _add_once(artifact, "packet-role-mismatch")
    if not _neutral_schema_valid(packets["neutralPacket"]):
        _add_once(artifact, "packet-role-mismatch")
    if any(_contains_normalized_key(packet, _PROHIBITED_AUTHORITY_KEYS) for packet in packets.values()):
        _add_once(artifact, "authority-expansion")

    snapshot = payload.get("evidenceSnapshot")
    if not _evidence_snapshot_schema_valid(snapshot):
        _add_once(artifact, "evidence-snapshot-invalid")
    if not isinstance(snapshot, dict) or not isinstance(snapshot.get("evidenceSnapshotHash"), str):
        snapshot_hash = None
        rows: list[Any] = []
    else:
        snapshot_hash = snapshot["evidenceSnapshotHash"]
        rows = snapshot.get("evidenceRows") if isinstance(snapshot.get("evidenceRows"), list) else []
    if not _HASH.fullmatch(snapshot_hash or ""):
        _add_once(artifact, "evidence-snapshot-invalid")
    if any(isinstance(row, dict) and "authority" in row and row.get("authority") not in AUTHORITY for row in rows):
        _add_once(artifact, "authority-expansion")
    evidence_ids = {row.get("evidenceId") for row in rows if isinstance(row, dict) and _nonempty(row.get("evidenceId"))}
    for packet in packets.values():
        if not isinstance(packet, dict) or packet.get("evidenceSnapshotHash") != snapshot_hash:
            _add_once(artifact, "snapshot-hash-mismatch")

    positive = packets["positivePacket"] if isinstance(packets["positivePacket"], dict) else {}
    worlds = positive.get("scenarioWorlds") if isinstance(positive.get("scenarioWorlds"), list) else []
    none_or_unknown = positive.get("noneOrUnknown")
    if not MIN_WORLDS <= len(worlds) <= MAX_WORLDS:
        _add_once(artifact, "positive-worlds-invalid")
    if not isinstance(none_or_unknown, dict) or none_or_unknown.get("present") is not True or not _nonempty(none_or_unknown.get("reason")):
        _add_once(artifact, "none-or-unknown-invalid")
    world_ids: set[str] = set()
    for world in worlds:
        if not isinstance(world, dict) or not _nonempty(world.get("falsifier")):
            _add_once(artifact, "world-falsifier-missing")
            _add_once(artifact, "positive-world-missing-falsifier")
        scenario_id = world.get("scenarioId") if isinstance(world, dict) else None
        if not _nonempty(scenario_id):
            _add_once(artifact, "scenario-id-invalid")
        elif scenario_id in world_ids:
            _add_once(artifact, "scenario-id-duplicate")
        else:
            world_ids.add(scenario_id)

    negative = packets["negativePacket"] if isinstance(packets["negativePacket"], dict) else {}
    attacks = negative.get("scenarioAttacks") if isinstance(negative.get("scenarioAttacks"), list) else []
    attack_ids: set[str] = set()
    for attack in attacks:
        scenario_id = attack.get("scenarioId") if isinstance(attack, dict) else None
        if not _nonempty(scenario_id):
            _add_once(artifact, "attack-scenario-id-invalid")
        elif scenario_id in attack_ids:
            _add_once(artifact, "attack-scenario-id-duplicate")
        else:
            attack_ids.add(scenario_id)
    if len(attacks) != len(world_ids) or world_ids != attack_ids:
        _add_once(artifact, "scenario-coverage-mismatch")
        _add_once(artifact, "negative-coverage-gap")

    neutral = packets["neutralPacket"] if isinstance(packets["neutralPacket"], dict) else {}
    decisive_forward = neutral.get("forwardDecisiveEvidenceIds")
    decisive_reverse = neutral.get("reverseDecisiveEvidenceIds")
    decisive_valid = all(isinstance(values, list) and bool(values) and all(_nonempty(item) for item in values)
                         for values in (decisive_forward, decisive_reverse))
    if not decisive_valid:
        _add_once(artifact, "neutral-evidence-invalid")
    forward_set = set(decisive_forward) if decisive_valid and isinstance(decisive_forward, list) else set()
    reverse_set = set(decisive_reverse) if decisive_valid and isinstance(decisive_reverse, list) else set()
    if not forward_set.issubset(evidence_ids) or not reverse_set.issubset(evidence_ids):
        _add_once(artifact, "neutral-evidence-unresolved")
        _add_once(artifact, "neutral-evidence-invention")
        _add_once(artifact, "evidence-id-unresolved")
    verdicts_equal = neutral.get("forwardVerdict") == neutral.get("reverseVerdict")
    evidence_equal = forward_set == reverse_set
    stable_verdict_matches = neutral.get("verdict") == neutral.get("forwardVerdict") == neutral.get("reverseVerdict")
    if (
        not verdicts_equal
        or not evidence_equal
        or neutral.get("orderStable") is not (verdicts_equal and evidence_equal)
        or (neutral.get("orderStable") is True and not stable_verdict_matches)
    ):
        _add_once(artifact, "order-instability")
        _add_once(artifact, "order-unstable")

    packet_reference_lists: list[Any] = [positive.get("evidenceIds"), neutral.get("decisiveEvidence")]
    packet_reference_lists.extend(attack.get("evidenceIds") for attack in attacks if isinstance(attack, dict))
    if any(
        not isinstance(values, list)
        or any(not isinstance(evidence_id, str) or evidence_id not in evidence_ids for evidence_id in values)
        for values in packet_reference_lists
    ):
        _add_once(artifact, "evidence-id-unresolved")

    claims: list[Any] = []
    for packet in packets.values():
        packet_claims = packet.get("claims") if isinstance(packet, dict) else None
        if not isinstance(packet_claims, list):
            _add_once(artifact, "claims-invalid")
        else:
            claims.extend(packet_claims)
    if len(claims) > MAX_CLAIMS:
        _add_once(artifact, "claim-count-exceeded")
    if any(_claim_has_unresolved_evidence(claim, evidence_ids) for claim in claims):
        _add_once(artifact, "claims-invalid")
        _add_once(artifact, "claim-evidence-unresolved")
        _add_once(artifact, "evidence-id-unresolved")

    scan_reason = _unsafe_secret_reason(payload)
    if scan_reason:
        _add_once(artifact, scan_reason)

    if isinstance(snapshot, dict) and snapshot.get("decisionDependsOnSupabase") is True:
        scope_rows = [row for row in rows if isinstance(row, dict) and _HASH.fullmatch(str(row.get("projectRefHash", "")))]
        if not scope_rows:
            _add_once(artifact, "supabase-project-ref-missing")
        elif any(set(row) - _SUPABASE_SCOPE_FIELDS for row in scope_rows):
            _add_once(artifact, "supabase-raw-scope-prohibited")
        elif not any(row.get("readOnly") is True and _nonempty(row.get("evidenceId")) and _nonempty(row.get("observedAt")) and _nonempty(row.get("toolTraceRef")) for row in scope_rows):
            _add_once(artifact, "supabase-auth-missing")

    _add_once(runtime, "runtime-lineage-missing")
    return {"artifact": artifact, "runtime": runtime}


def _mean(values: Sequence[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def _packet_claims(packets: dict[str, Any]) -> list[Any]:
    claims: list[Any] = []
    for packet in packets.values():
        if isinstance(packet, dict) and isinstance(packet.get("claims"), list):
            claims.extend(packet["claims"])
    return claims


def _normalized_claim_fingerprint(claim: Any) -> str | None:
    if not isinstance(claim, dict) or not isinstance(claim.get("text"), str):
        return None
    normalized = " ".join(unicodedata.normalize("NFC", claim["text"]).split()).lower()
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def _score_metrics(
    payload: dict[str, Any], input_size: int, hard_gates: dict[str, list[str]]
) -> tuple[dict[str, dict[str, float]], dict[str, float], dict[str, float]]:
    """Compute bounded artifact-quality metrics; this never claims runtime improvement."""
    packets = {name: payload.get(name) for name in _PACKETS}
    snapshot = payload.get("evidenceSnapshot") if isinstance(payload.get("evidenceSnapshot"), dict) else {}
    rows = snapshot.get("evidenceRows") if isinstance(snapshot.get("evidenceRows"), list) else []
    evidence_by_id: dict[str, dict[str, Any]] = {}
    for row in rows:
        if isinstance(row, dict) and _nonempty(row.get("evidenceId")) and row["evidenceId"] not in evidence_by_id:
            evidence_by_id[row["evidenceId"]] = row

    claims = _packet_claims(packets)
    referenced_ids: set[str] = set()
    resolved_claim_count = 0
    for claim in claims:
        claim_ids = claim.get("evidenceIds") if isinstance(claim, dict) else None
        resolved = isinstance(claim_ids, list) and any(isinstance(item, str) and item in evidence_by_id for item in claim_ids)
        if resolved:
            resolved_claim_count += 1
            referenced_ids.update(item for item in claim_ids if isinstance(item, str) and item in evidence_by_id)
    referenced_rows = [evidence_by_id[evidence_id] for evidence_id in sorted(referenced_ids)]
    directness = _mean([DIRECTNESS.get(row.get("directness"), 0.0) for row in referenced_rows])
    freshness = _mean([FRESHNESS.get(row.get("freshness"), 0.0) for row in referenced_rows])
    authority_by_group: dict[str, float] = {}
    for row in referenced_rows:
        group = row.get("independenceGroup")
        group_key = group.strip() if isinstance(group, str) and group.strip() else f"evidence:{row['evidenceId']}"
        authority_by_group[group_key] = max(authority_by_group.get(group_key, 0.0), AUTHORITY.get(row.get("authority"), 0.0))
    authority = _mean(list(authority_by_group.values()))

    positive = packets["positivePacket"] if isinstance(packets["positivePacket"], dict) else {}
    negative = packets["negativePacket"] if isinstance(packets["negativePacket"], dict) else {}
    neutral = packets["neutralPacket"] if isinstance(packets["neutralPacket"], dict) else {}
    worlds = positive.get("scenarioWorlds") if isinstance(positive.get("scenarioWorlds"), list) else []
    attacks = negative.get("scenarioAttacks") if isinstance(negative.get("scenarioAttacks"), list) else []
    falsifier_checks = [
        _nonempty(world.get("falsifier")) if isinstance(world, dict) else False for world in worlds
    ] + [
        _nonempty(attack.get("smallestDisconfirmingProbe")) if isinstance(attack, dict) else False for attack in attacks
    ]
    alternative_causes = {
        attack.get("scenarioId") for attack in attacks
        if isinstance(attack, dict) and _nonempty(attack.get("scenarioId")) and _nonempty(attack.get("alternativeCause"))
    }
    causal_checks = [
        _nonempty(world.get("causalMechanism")) and world.get("scenarioId") in alternative_causes
        if isinstance(world, dict) else False for world in worlds
    ]
    world_ids = {
        world.get("scenarioId") for world in worlds
        if isinstance(world, dict) and _nonempty(world.get("scenarioId"))
    }
    attack_ids = {
        attack.get("scenarioId") for attack in attacks
        if isinstance(attack, dict) and _nonempty(attack.get("scenarioId"))
    }
    fingerprints = [fingerprint for claim in claims if (fingerprint := _normalized_claim_fingerprint(claim)) is not None]
    duplicate_fingerprints = len(fingerprints) - len(set(fingerprints))
    forward_ids = neutral.get("forwardDecisiveEvidenceIds")
    reverse_ids = neutral.get("reverseDecisiveEvidenceIds")
    proof = neutral.get("nextSingleProof")
    decisive_lists_valid = all(
        isinstance(values, list) and all(_nonempty(item) for item in values)
        for values in (forward_ids, reverse_ids)
    )
    order_stable = (
        decisive_lists_valid
        and neutral.get("forwardVerdict") == neutral.get("reverseVerdict")
        and set(forward_ids) == set(reverse_ids) and neutral.get("orderStable") is True
    )
    values = {
        "evidenceCoverage": resolved_claim_count / len(claims) if claims else 0.0,
        "evidenceDirectnessAuthority": _mean([directness, authority, freshness]),
        "falsifiability": _mean([1.0 if check else 0.0 for check in falsifier_checks]),
        "causalDiscrimination": _mean([1.0 if check else 0.0 for check in causal_checks]),
        "longTailScenarioCoverage": 1.0 if (
            MIN_WORLDS <= len(worlds) <= MAX_WORLDS
            and isinstance(positive.get("noneOrUnknown"), dict)
            and positive["noneOrUnknown"].get("present") is True
            and len(world_ids) == len(worlds)
            and len(attack_ids) == len(attacks)
            and len(attacks) == len(worlds)
            and world_ids == attack_ids
        ) else 0.0,
        "branchIndependence": 1.0 - duplicate_fingerprints / len(fingerprints) if fingerprints else 0.0,
        "orderStability": 1.0 if order_stable else 0.0,
        "boundednessAndRedaction": 1.0 if (
            input_size <= INPUT_LIMIT_BYTES and len(claims) <= MAX_CLAIMS and _unsafe_secret_reason(payload) is None
        ) else 0.0,
        "nextProofActionability": 1.0 if (
            isinstance(proof, dict) and set(proof) == {"action", "decisionChange"}
            and _nonempty(proof.get("action")) and _nonempty(proof.get("decisionChange"))
        ) else 0.0,
    }
    dimensions = {
        name: {"normalized": round(value, 6), "weight": weight, "points": round(value * weight, 2)}
        for name, weight in WEIGHTS.items() for value in [values[name]]
    }
    return dimensions, {
        "directness": round(directness, 6),
        "authority": round(authority, 6),
        "freshness": round(freshness, 6),
        "uniqueReferencedEvidenceRows": len(referenced_rows),
        "uniqueAuthorityGroups": len(authority_by_group),
    }, values


def _baseline_normalized(baseline: dict[str, Any], name: str) -> float | None:
    dimensions = baseline.get("dimensions")
    value = dimensions.get(name) if isinstance(dimensions, dict) else None
    normalized = value.get("normalized") if isinstance(value, dict) else None
    if not isinstance(normalized, (int, float)) or isinstance(normalized, bool):
        return None
    value = float(normalized)
    return value if math.isfinite(value) and 0.0 <= value <= 1.0 else None


def _not_evaluated_comparisons() -> dict[str, dict[str, Any]]:
    return {
        name: {"actual": None, "threshold": threshold, "passed": None, "status": "notEvaluated"}
        for name, threshold in UPLIFT_THRESHOLDS.items()
    }


def _quality_uplift(
    payload: dict[str, Any], raw_total_score: float, raw_dimensions: dict[str, float],
    hard_gates: dict[str, list[str]],
) -> tuple[bool | None, dict[str, Any], dict[str, Any] | None]:
    baseline = payload.get("baseline")
    definition = {
        "scope": "artifact-quality-candidate-not-runtime-improvement",
        "thresholds": UPLIFT_THRESHOLDS,
    }
    if baseline is None:
        return None, definition | {"reason": "baseline-absent", "comparisons": _not_evaluated_comparisons()}, None
    if not isinstance(baseline, dict) or baseline.get("rubricVersion") != payload.get("rubricVersion") or baseline.get("fixtureDeckHash") != payload.get("fixtureDeckHash"):
        return None, definition | {"reason": "baseline-incomparable", "comparisons": _not_evaluated_comparisons()}, None
    baseline_score = baseline.get("totalScore")
    baseline_coverage = _baseline_normalized(baseline, "evidenceCoverage")
    baseline_values = {name: _baseline_normalized(baseline, name) for name in WEIGHTS}
    if not isinstance(baseline_score, (int, float)) or isinstance(baseline_score, bool):
        baseline_score_value = None
    else:
        baseline_score_value = float(baseline_score)
    baseline_case_count = baseline.get("caseCount")
    baseline_case_count_value = baseline_case_count if isinstance(baseline_case_count, int) and not isinstance(baseline_case_count, bool) and baseline_case_count >= 0 else None
    if (
        baseline_score_value is None or not math.isfinite(baseline_score_value) or not 0.0 <= baseline_score_value <= 100.0
        or baseline_coverage is None or any(value is None for value in baseline_values.values())
        or baseline_case_count_value is None
    ):
        return None, definition | {"reason": "baseline-incomparable", "comparisons": _not_evaluated_comparisons()}, None

    case_count = payload.get("caseCount")
    case_count_value = int(case_count) if isinstance(case_count, int) and not isinstance(case_count, bool) else None
    total_delta = raw_total_score - baseline_score_value
    coverage_delta = raw_dimensions["evidenceCoverage"] - baseline_coverage
    regressions = {name: max(0.0, value - raw_dimensions[name]) for name, value in baseline_values.items()}
    maximum_regression = max(regressions.values()) if regressions else 0.0
    comparisons = {
        "minimumCaseCount": {"actual": case_count_value, "candidateActual": case_count_value, "baselineActual": baseline_case_count_value, "threshold": UPLIFT_THRESHOLDS["minimumCaseCount"], "passed": case_count_value is not None and case_count_value >= UPLIFT_THRESHOLDS["minimumCaseCount"] and baseline_case_count_value >= UPLIFT_THRESHOLDS["minimumCaseCount"], "status": "evaluated"},
        "minimumCandidateScore": {"actual": raw_total_score, "displayActual": round(raw_total_score, 2), "threshold": UPLIFT_THRESHOLDS["minimumCandidateScore"], "passed": raw_total_score >= UPLIFT_THRESHOLDS["minimumCandidateScore"], "status": "evaluated"},
        "minimumTotalDelta": {"actual": total_delta, "displayActual": round(total_delta, 6), "threshold": UPLIFT_THRESHOLDS["minimumTotalDelta"], "passed": total_delta >= UPLIFT_THRESHOLDS["minimumTotalDelta"], "status": "evaluated"},
        "minimumEvidenceCoverageDelta": {"actual": coverage_delta, "displayActual": round(coverage_delta, 6), "threshold": UPLIFT_THRESHOLDS["minimumEvidenceCoverageDelta"], "passed": coverage_delta >= UPLIFT_THRESHOLDS["minimumEvidenceCoverageDelta"], "status": "evaluated"},
        "maximumSingleMetricRegression": {"actual": maximum_regression, "displayActual": round(maximum_regression, 6), "threshold": UPLIFT_THRESHOLDS["maximumSingleMetricRegression"], "passed": maximum_regression <= UPLIFT_THRESHOLDS["maximumSingleMetricRegression"], "status": "evaluated"},
        "artifactHardGatesEmpty": {"actual": len(hard_gates["artifact"]), "threshold": 0, "passed": not hard_gates["artifact"], "status": "evaluated"},
    }
    candidate = all(comparison["passed"] for comparison in comparisons.values())
    return candidate, definition | {"reason": "thresholds-evaluated", "comparisons": comparisons}, {
        "totalScore": round(total_delta, 6),
        "dimensions": {name: round(raw_dimensions[name] - baseline_values[name], 6) for name in WEIGHTS},
    }


def grade_payload(payload: dict[str, Any], input_size: int) -> dict[str, Any]:
    hard_gates = collect_hard_gates(payload, input_size)
    if _unsafe_secret_reason(payload) == "secret-like-content":
        return {
            "schemaVersion": "awx.three-way-long-tail.grade.v1",
            "rubricVersion": "",
            "fixtureDeckHash": "",
            "hardGates": hard_gates,
            "artifactVerdict": "HOLD",
            "runtimeLineageVerdict": "HOLD",
        }
    neutral = payload.get("neutralPacket") if isinstance(payload.get("neutralPacket"), dict) else {}
    dimensions, evidence_submetrics, raw_dimensions = _score_metrics(payload, input_size, hard_gates)
    raw_total_score = sum(raw_dimensions[name] * weight for name, weight in WEIGHTS.items())
    total_score = round(raw_total_score, 2)
    quality_uplift_candidate, quality_uplift, baseline_delta = _quality_uplift(
        payload, raw_total_score, raw_dimensions, hard_gates
    )
    artifact_verdict = "HOLD" if hard_gates["artifact"] else neutral.get("artifactVerdict", "HOLD")
    runtime_verdict = "HOLD" if hard_gates["runtime"] else neutral.get("runtimeLineageVerdict", "HOLD")
    artifact_verdict = artifact_verdict if artifact_verdict in {"APPLY", "HOLD", "REJECT"} else "HOLD"
    runtime_verdict = runtime_verdict if runtime_verdict in {"APPLY", "HOLD", "REJECT"} else "HOLD"
    return {
        "schemaVersion": "awx.three-way-long-tail.grade.v1",
        "rubricVersion": payload.get("rubricVersion", ""),
        "fixtureDeckHash": payload.get("fixtureDeckHash", ""),
        "hardGates": hard_gates,
        "artifactVerdict": artifact_verdict,
        "runtimeLineageVerdict": runtime_verdict,
        "metricDefinition": {
            "bounded": True,
            "claimFingerprint": "sha256 of unicode-nfc, collapsed-whitespace, lowercase text; fingerprint text is not emitted",
            "authorityAggregation": "maximum authority value per independenceGroup",
        },
        "evidenceSubmetrics": evidence_submetrics,
        "dimensions": dimensions,
        "totalScore": total_score,
        "baselineDelta": baseline_delta,
        "qualityUpliftCandidate": quality_uplift_candidate,
        "qualityUplift": quality_uplift,
    }


def _serialize(result: dict[str, Any]) -> bytes:
    try:
        raw = json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise GradeInputError("output-nonfinite") from exc
    if len(raw) + 1 > OUTPUT_LIMIT_BYTES:
        raise GradeInputError("output-size-exceeded")
    return raw


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        payload, input_size = load_payload(args.input)
        unsafe_reason = _unsafe_secret_reason(payload)
        if unsafe_reason in {"secret-like-content", "secret-pattern-risk"}:
            print(unsafe_reason, file=sys.stderr)
            return 2
        result = grade_payload(payload, input_size)
        rendered = _serialize(result)
        if args.output:
            args.output.write_bytes(rendered + b"\n")
        else:
            sys.stdout.buffer.write(rendered + b"\n")
        return 0 if result["artifactVerdict"] == "APPLY" else 1
    except GradeInputError as exc:
        print(exc.reason_code, file=sys.stderr)
        return 2
    except Exception:
        print("internal-error", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
