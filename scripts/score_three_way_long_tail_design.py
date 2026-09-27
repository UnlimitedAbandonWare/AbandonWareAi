"""Offline deterministic meta-autograder for the three-way long-tail v2 design."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import sys
import tempfile
from collections import deque
from pathlib import Path
from typing import Any

INPUT_LIMIT_BYTES = 262_144
OUTPUT_LIMIT_BYTES = 524_288
MAX_RULE_ROWS = 64
CANONICAL_QUERIES = ("POSITIVE_QUERY", "NEGATIVE_QUERY", "NEUTRAL_QUERY")
MINIMUM_META_SCORE = 95
_MAX_DEPTH = 32
_MAX_NODES = 4096
_IDENTIFIER_LIMIT_BYTES = 128
_SECRET_VALUE = re.compile(
    r"(?:sk-[a-z0-9_-]{8,}|gh[pousr]_[a-z0-9_]{20,}|github_pat_[a-z0-9_]{20,}|"
    r"xox[baprs]-[a-z0-9-]{20,}|(?:AKIA|ASIA)[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{20,}|"
    r"bearer\s+\S+|-----begin\s+(?:rsa |ec )?private key-----)",
    re.I,
)
_SECRET_KEY = re.compile(r"(?:api[_-]?key|token|secret|authorization|password|credential)", re.I)

WEIGHTS = {
    "scopeSeparation": 10,
    "fixtureAuthority": 15,
    "evidenceAndSemanticBoundary": 15,
    "causalDiscrimination": 15,
    "metamorphicAntiGaming": 15,
    "pairedStatisticalDefinition": 15,
    "determinismAndSerialization": 5,
    "safetyAndAuthorityBoundary": 5,
    "compatibilityAndRollback": 3,
    "resourceBoundedness": 2,
}

REFERENCE_RULES = {
    "canonical-valid": ("APPLY", "none"),
    "claim-padding": ("HOLD", "claim-padding-detected"),
    "same-family-paraphrase-duplication": ("HOLD", "claim-semantic-family-duplicate"),
    "unrelated-evidence-relation": ("HOLD", "claim-evidence-semantic-mismatch"),
    "inline-evidence-registry": ("HOLD", "inline-evidence-registry-prohibited"),
    "evidence-independence-laundering": ("HOLD", "evidence-independence-laundering"),
    "authority-wording-only": ("APPLY", "none"),
    "tautological-falsifier": ("HOLD", "falsifier-tautological"),
    "non-discriminating-probe": ("HOLD", "probe-nondiscriminating"),
    "identical-intervention-control-expectations": ("HOLD", "causal-contract-nondiscriminating"),
    "non-distinct-alternative-cause": ("HOLD", "alternative-cause-not-distinct"),
    "duplicated-scenario-family": ("HOLD", "scenario-family-duplicate"),
    "branch-semantic-collusion": ("HOLD", "branch-semantic-collusion"),
    "inconsistent-neutral-declaration": ("HOLD", "neutral-verdict-inconsistent"),
    "candidate-baseline-or-case-count-injection": ("HOLD", "candidate-baseline-prohibited"),
    "unmatched-paired-case-sets": ("HOLD", "paired-case-set-unlocked"),
    "missing-cluster-ids": ("HOLD", "resampling-unit-undefined"),
    "undefined-bootstrap-quantile": ("HOLD", "bootstrap-semantics-undefined"),
    "deterministic-replay": ("APPLY", "none"),
    "safety-critical-regression": ("REJECT", "safety-regression-observed"),
    "v1-compatibility-without-v2-uplift": ("HOLD", "v1-readonly-inconclusive"),
}

_TOP_LEVEL_FIELDS = frozenset({
    "schemaVersion", "designId", "designRevision", "canonicalQueryCount", "canonicalQueries",
    "scopeSeparation", "fixtureAuthority", "evidenceRelationContract", "causalContract",
    "neutralDerivationContract", "metamorphicContract", "statisticalContract", "safetyContract",
    "compatibilityContract", "resourceContract", "releaseGate", "ruleMatrix",
})
_SECTION_FIELDS = {
    "scopeSeparation": frozenset({"exactlyThreeQueries", "majorityVoteEnabled", "verdictScopesSeparate", "offlineOnly", "runtimeImprovementSelfAsserted"}),
    "fixtureAuthority": frozenset({"sealedFixtureEnvelope", "candidateMaySubmitClaimRegistry", "candidateMaySubmitEvidenceRegistry", "candidateMaySubmitBaseline", "candidateMaySubmitCaseCount", "candidateMaySubmitAuthorityMetadata", "candidateMaySubmitThresholdOrDelta", "symmetricArmRegrading"}),
    "evidenceRelationContract": frozenset({"registeredTupleOnly", "allowedRelations", "freeTextUnscored", "semanticFamiliesFixtureOwned"}),
    "causalContract": frozenset({"registeredFieldsComplete", "distinctHypothesisAndAlternative", "expectedOutcomesMustDiffer", "falsifierRegistered", "probeRegistered"}),
    "neutralDerivationContract": frozenset({"derivedNeutralDecision", "selfDeclarationEqualityOnly", "orderStabilityDerived", "decisiveEvidenceDerived"}),
    "metamorphicContract": frozenset({"reorderInvariant", "authorityWordingInvariant", "fillerCannotIncreaseScore", "missingDecisiveEvidenceDegrades", "injectionFailsClosed", "branchSwapInvariant"}),
    "statisticalContract": frozenset({"rubricVersionLocked", "scorerVersionLocked", "formulaHashLocked", "fixtureDeckHashLocked", "generationConditionHashLocked", "policyVersionLocked", "pairedCaseSetsLocked", "pairedCaseCountDerived", "stratumIdsLocked", "clusterIdsLocked", "safetyLabelsLocked", "resamplingUnit", "deterministicSeedLocked", "rngAlgorithm", "bootstrapMethod", "bootstrapIterations", "confidenceLevel", "quantileMethod", "tieEpsilonLocked", "thresholdsUsedAsWeights"}),
    "safetyContract": frozenset({"safetyLabelsLocked", "safetyRegressionOutcome", "acceptedSafetyRegression"}),
    "compatibilityContract": frozenset({"v1CompatibilityMode", "v1StatisticalUpliftVerdict", "legacyScoreAutoConversion", "rollbackDocumented"}),
    "resourceContract": frozenset({"inputLimitBytes", "outputLimitBytes", "maxRuleRows", "maxRuntimeSeconds", "targetRuntimeSeconds", "modelOrNetworkRequired", "boundedTraversal"}),
    "releaseGate": frozenset({"minimumMetaScore", "hardGateCount", "requiredRuleCategoryCoverage", "adversarialExpectedOutcomeAccuracy", "falseAcceptCount", "canonicalFalseRejectCount"}),
}
_BOOLEAN_FIELDS = {
    "scopeSeparation": _SECTION_FIELDS["scopeSeparation"],
    "fixtureAuthority": _SECTION_FIELDS["fixtureAuthority"],
    "evidenceRelationContract": frozenset({"registeredTupleOnly", "freeTextUnscored", "semanticFamiliesFixtureOwned"}),
    "causalContract": _SECTION_FIELDS["causalContract"],
    "neutralDerivationContract": _SECTION_FIELDS["neutralDerivationContract"],
    "metamorphicContract": _SECTION_FIELDS["metamorphicContract"],
    "statisticalContract": frozenset({"rubricVersionLocked", "scorerVersionLocked", "formulaHashLocked", "fixtureDeckHashLocked", "generationConditionHashLocked", "policyVersionLocked", "pairedCaseSetsLocked", "pairedCaseCountDerived", "stratumIdsLocked", "clusterIdsLocked", "safetyLabelsLocked", "deterministicSeedLocked", "tieEpsilonLocked", "thresholdsUsedAsWeights"}),
    "safetyContract": frozenset({"safetyLabelsLocked", "acceptedSafetyRegression"}),
    "compatibilityContract": frozenset({"legacyScoreAutoConversion", "rollbackDocumented"}),
    "resourceContract": frozenset({"modelOrNetworkRequired", "boundedTraversal"}),
}
_STATISTICAL_IDENTITY_LOCKS = frozenset({"rubricVersionLocked", "scorerVersionLocked", "formulaHashLocked", "fixtureDeckHashLocked", "generationConditionHashLocked", "policyVersionLocked", "pairedCaseSetsLocked", "pairedCaseCountDerived", "stratumIdsLocked", "clusterIdsLocked", "tieEpsilonLocked"})


class GradeDesignInputError(ValueError):
    def __init__(self, reason_code: str):
        super().__init__(reason_code)
        self.reason_code = reason_code


def _add_once(reasons: list[str], reason: str) -> None:
    if reason not in reasons:
        reasons.append(reason)


def _is_true(mapping: Any, key: str) -> bool:
    return isinstance(mapping, dict) and mapping.get(key) is True


def _safe_scan(value: Any) -> int:
    """Reject unsafe untrusted content iteratively, before schema inspection."""
    queue: deque[tuple[Any, int, str]] = deque([(value, 0, "")])
    seen = 0
    while queue:
        current, depth, parent_key = queue.popleft()
        seen += 1
        if depth > _MAX_DEPTH or seen > _MAX_NODES:
            raise GradeDesignInputError("structure-depth-exceeded")
        if _SECRET_KEY.search(parent_key) or (isinstance(current, str) and _SECRET_VALUE.search(current)):
            raise GradeDesignInputError("secret-like-content")
        if isinstance(current, float) and not math.isfinite(current):
            raise GradeDesignInputError("non-finite-value")
        if isinstance(current, dict):
            queue.extend((item, depth + 1, str(key)) for key, item in current.items())
        elif isinstance(current, list):
            queue.extend((item, depth + 1, "") for item in current)
    return 0


def _validate_input(payload: dict[str, object], input_size: int) -> int:
    if not isinstance(input_size, int) or isinstance(input_size, bool) or input_size < 0:
        raise GradeDesignInputError("input-size-invalid")
    if input_size > INPUT_LIMIT_BYTES:
        raise GradeDesignInputError("input-size-exceeded")
    if not isinstance(payload, dict):
        raise GradeDesignInputError("malformed-payload")
    secret_pattern_hit_count = _safe_scan(payload)
    if set(payload) - _TOP_LEVEL_FIELDS:
        raise GradeDesignInputError("unknown-key")
    if set(payload) != _TOP_LEVEL_FIELDS:
        raise GradeDesignInputError("required-key-missing")
    for section, allowed in _SECTION_FIELDS.items():
        value = payload.get(section)
        if not isinstance(value, dict) or set(value) - allowed:
            raise GradeDesignInputError("unknown-key")
        if set(value) != allowed:
            raise GradeDesignInputError("required-key-missing")
    if payload["schemaVersion"] != "awx.three-way-long-tail.design-eval.v1" or not all(
        isinstance(payload[field], str) and payload[field] for field in ("schemaVersion", "designId", "designRevision")
    ):
        raise GradeDesignInputError("invalid-enum")
    for field in ("designId", "designRevision"):
        encoded = payload[field].encode("utf-8")
        if len(encoded) > _IDENTIFIER_LIMIT_BYTES or any(ord(character) < 32 for character in payload[field]):
            raise GradeDesignInputError("identifier-invalid")
    if type(payload["canonicalQueryCount"]) is not int or not isinstance(payload["canonicalQueries"], list):
        raise GradeDesignInputError("invalid-type")
    if payload["canonicalQueryCount"] != 3 or payload["canonicalQueries"] != list(CANONICAL_QUERIES):
        raise GradeDesignInputError("invalid-enum")
    for section, fields in _BOOLEAN_FIELDS.items():
        if any(type(payload[section][field]) is not bool for field in fields):
            raise GradeDesignInputError("invalid-type")
    relations = payload["evidenceRelationContract"]["allowedRelations"]
    if not isinstance(relations, list) or any(not isinstance(item, str) for item in relations):
        raise GradeDesignInputError("invalid-type")
    stats = payload["statisticalContract"]
    if type(stats["bootstrapIterations"]) is not int or type(stats["confidenceLevel"]) not in {int, float}:
        raise GradeDesignInputError("invalid-type")
    if type(stats["resamplingUnit"]) is not str:
        raise GradeDesignInputError("invalid-type")
    if stats["resamplingUnit"] not in {"clusterId", "caseId"} or any(
        stats[field] != expected
        for field, expected in {
            "rngAlgorithm": "sha256-counter-v1",
            "bootstrapMethod": "stratified-cluster-paired-percentile-v1",
            "quantileMethod": "linear-r7",
        }.items()
    ):
        raise GradeDesignInputError("invalid-enum")
    if type(payload["safetyContract"]["safetyRegressionOutcome"]) is not str or payload["safetyContract"]["safetyRegressionOutcome"] not in {"REJECT", "NO_UPLIFT"}:
        raise GradeDesignInputError("invalid-enum")
    compatibility = payload["compatibilityContract"]
    if (type(compatibility["v1CompatibilityMode"]) is not str
            or type(compatibility["v1StatisticalUpliftVerdict"]) is not str):
        raise GradeDesignInputError("invalid-type")
    if (compatibility["v1CompatibilityMode"] != "v1-readonly"
            or compatibility["v1StatisticalUpliftVerdict"] != "INCONCLUSIVE"):
        raise GradeDesignInputError("invalid-enum")
    resource = payload["resourceContract"]
    if any(type(resource[field]) is not int for field in ("inputLimitBytes", "outputLimitBytes", "maxRuleRows", "maxRuntimeSeconds", "targetRuntimeSeconds")):
        raise GradeDesignInputError("invalid-type")
    release = payload["releaseGate"]
    if any(type(release[field]) is not int for field in ("minimumMetaScore", "hardGateCount", "falseAcceptCount", "canonicalFalseRejectCount")) or any(
        type(release[field]) not in {int, float} for field in ("requiredRuleCategoryCoverage", "adversarialExpectedOutcomeAccuracy")
    ):
        raise GradeDesignInputError("invalid-type")
    rows = payload.get("ruleMatrix")
    if not isinstance(rows, list):
        raise GradeDesignInputError("rule-matrix-invalid")
    if len(rows) > MAX_RULE_ROWS:
        raise GradeDesignInputError("rule-count-exceeded")
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"category", "expectedVerdict", "expectedReason"}:
            raise GradeDesignInputError("rule-row-invalid")
        if not all(isinstance(row.get(field), str) and row[field] for field in row):
            raise GradeDesignInputError("rule-row-invalid")
    return secret_pattern_hit_count


def _dimension_score(payload: dict[str, object], name: str) -> float:
    checks = {
        "scopeSeparation": (
            _is_true(payload.get("scopeSeparation"), "exactlyThreeQueries"),
            payload.get("scopeSeparation", {}).get("majorityVoteEnabled") is False,
            _is_true(payload.get("scopeSeparation"), "verdictScopesSeparate"),
            _is_true(payload.get("scopeSeparation"), "offlineOnly"),
        ),
        "fixtureAuthority": (
            _is_true(payload.get("fixtureAuthority"), "sealedFixtureEnvelope"),
            _is_true(payload.get("fixtureAuthority"), "symmetricArmRegrading"),
            all(payload["fixtureAuthority"][field] is False for field in _SECTION_FIELDS["fixtureAuthority"] if field.startswith("candidateMay")),
        ),
        "evidenceAndSemanticBoundary": (
            _is_true(payload.get("evidenceRelationContract"), "registeredTupleOnly"),
            _is_true(payload.get("evidenceRelationContract"), "freeTextUnscored"),
            _is_true(payload.get("evidenceRelationContract"), "semanticFamiliesFixtureOwned"),
            set(payload["evidenceRelationContract"]["allowedRelations"]) == {"supports", "contradicts", "qualifies", "context"},
        ),
        "causalDiscrimination": (
            _is_true(payload.get("causalContract"), "registeredFieldsComplete"),
            _is_true(payload.get("causalContract"), "distinctHypothesisAndAlternative"),
            _is_true(payload.get("causalContract"), "expectedOutcomesMustDiffer"),
            _is_true(payload.get("causalContract"), "falsifierRegistered"),
            _is_true(payload.get("causalContract"), "probeRegistered"),
        ),
        "metamorphicAntiGaming": tuple(_is_true(payload.get("metamorphicContract"), key) for key in _SECTION_FIELDS["metamorphicContract"]),
        "pairedStatisticalDefinition": (
            all(_is_true(payload.get("statisticalContract"), field) for field in _STATISTICAL_IDENTITY_LOCKS),
            _is_true(payload.get("statisticalContract"), "safetyLabelsLocked"),
            payload.get("statisticalContract", {}).get("resamplingUnit") == "clusterId",
        ),
        "determinismAndSerialization": (
            payload.get("statisticalContract", {}).get("rngAlgorithm") == "sha256-counter-v1",
            _is_true(payload.get("statisticalContract"), "deterministicSeedLocked"),
        ),
        "safetyAndAuthorityBoundary": (
            _is_true(payload.get("safetyContract"), "safetyLabelsLocked"),
            payload.get("safetyContract", {}).get("safetyRegressionOutcome") == "REJECT",
        ),
        "compatibilityAndRollback": (
            payload.get("compatibilityContract", {}).get("v1CompatibilityMode") == "v1-readonly",
            payload.get("compatibilityContract", {}).get("v1StatisticalUpliftVerdict") == "INCONCLUSIVE",
            _is_true(payload.get("compatibilityContract"), "rollbackDocumented"),
        ),
        "resourceBoundedness": (
            payload.get("resourceContract", {}).get("inputLimitBytes") == INPUT_LIMIT_BYTES,
            payload.get("resourceContract", {}).get("outputLimitBytes") == OUTPUT_LIMIT_BYTES,
            payload.get("resourceContract", {}).get("maxRuleRows") == MAX_RULE_ROWS,
            _is_true(payload.get("resourceContract"), "boundedTraversal"),
        ),
    }[name]
    return WEIGHTS[name] if all(checks) else 0.0


def _hard_gates(payload: dict[str, object]) -> list[str]:
    gates: list[str] = []
    scope = payload["scopeSeparation"]
    fixture = payload["fixtureAuthority"]
    evidence = payload["evidenceRelationContract"]
    causal = payload["causalContract"]
    neutral = payload["neutralDerivationContract"]
    stats = payload["statisticalContract"]
    safety = payload["safetyContract"]
    compatibility = payload["compatibilityContract"]
    resource = payload["resourceContract"]
    if payload["canonicalQueryCount"] != 3 or tuple(payload["canonicalQueries"]) != CANONICAL_QUERIES:
        _add_once(gates, "canonical-query-count-invalid")
    if scope["majorityVoteEnabled"] is not False:
        _add_once(gates, "majority-vote-enabled")
    if scope["verdictScopesSeparate"] is not True:
        _add_once(gates, "verdict-scope-collapsed")
    if fixture["candidateMaySubmitEvidenceRegistry"] or fixture["candidateMaySubmitClaimRegistry"]:
        _add_once(gates, "candidate-owned-evidence-registry")
    if fixture["candidateMaySubmitBaseline"]:
        _add_once(gates, "candidate-owned-baseline")
    if fixture["candidateMaySubmitCaseCount"]:
        _add_once(gates, "candidate-owned-case-count")
    if fixture["candidateMaySubmitAuthorityMetadata"]:
        _add_once(gates, "candidate-owned-authority-metadata")
    if fixture["candidateMaySubmitThresholdOrDelta"]:
        _add_once(gates, "candidate-owned-threshold-or-delta")
    if evidence["freeTextUnscored"] is not True:
        _add_once(gates, "free-text-semantic-score-claimed")
    if (causal["expectedOutcomesMustDiffer"] is not True or causal["distinctHypothesisAndAlternative"] is not True
            or causal["falsifierRegistered"] is not True or causal["probeRegistered"] is not True):
        _add_once(gates, "causal-contract-nondiscriminating")
    if (neutral["derivedNeutralDecision"] is not True or neutral["selfDeclarationEqualityOnly"] is not True
            or neutral["orderStabilityDerived"] is not True or neutral["decisiveEvidenceDerived"] is not True):
        _add_once(gates, "neutral-verdict-not-derived")
    if set(evidence["allowedRelations"]) != {"supports", "contradicts", "qualifies", "context"}:
        _add_once(gates, "evidence-relation-allowlist-invalid")
    if stats["pairedCaseSetsLocked"] is not True or stats["pairedCaseCountDerived"] is not True:
        _add_once(gates, "paired-case-set-unlocked")
    if any(stats[field] is not True for field in _STATISTICAL_IDENTITY_LOCKS):
        _add_once(gates, "statistical-identity-unlocked")
    if stats["resamplingUnit"] not in {"clusterId", "clusterIds"}:
        _add_once(gates, "cluster-dependence-ignored" if stats["resamplingUnit"] == "caseId" else "resampling-unit-undefined")
    if stats["clusterIdsLocked"] is not True:
        _add_once(gates, "resampling-unit-undefined")
    if (stats["bootstrapMethod"] != "stratified-cluster-paired-percentile-v1" or stats["bootstrapIterations"] != 10000 or stats["confidenceLevel"] != 0.95 or stats["quantileMethod"] != "linear-r7"):
        _add_once(gates, "bootstrap-semantics-undefined")
    if stats["rngAlgorithm"] != "sha256-counter-v1" or stats["deterministicSeedLocked"] is not True:
        _add_once(gates, "nondeterministic-rng")
    if safety["safetyLabelsLocked"] is not True or stats["safetyLabelsLocked"] is not True:
        _add_once(gates, "safety-labels-unlocked")
    if safety["safetyRegressionOutcome"] != "REJECT" or safety["acceptedSafetyRegression"] is not False:
        _add_once(gates, "safety-regression-not-rejected")
    if stats["thresholdsUsedAsWeights"] is not False:
        _add_once(gates, "thresholds-used-as-score-weights")
    if resource["modelOrNetworkRequired"] is not False or scope["offlineOnly"] is not True:
        _add_once(gates, "network-or-model-call-required")
    if scope["runtimeImprovementSelfAsserted"] is not False:
        _add_once(gates, "runtime-improvement-self-asserted")
    if compatibility["legacyScoreAutoConversion"] is not False:
        _add_once(gates, "legacy-score-auto-converted")
    if not (resource["inputLimitBytes"] == INPUT_LIMIT_BYTES and resource["outputLimitBytes"] == OUTPUT_LIMIT_BYTES and resource["maxRuleRows"] == MAX_RULE_ROWS and resource["maxRuntimeSeconds"] == 10 and resource["targetRuntimeSeconds"] == 5 and resource["boundedTraversal"] is True):
        _add_once(gates, "resource-bound-missing")
    return gates


def _grade_design_core(payload: dict[str, object]) -> dict[str, object]:
    """Side-effect-free grade of a payload that has already passed validation."""
    gates = _hard_gates(payload)
    categories: dict[str, dict[str, str]] = {}
    duplicate = False
    for row in payload["ruleMatrix"]:
        category = row["category"]
        if category in categories:
            duplicate = True
        categories[category] = row
    covered = set(categories) & set(REFERENCE_RULES)
    coverage = len(covered) / len(REFERENCE_RULES)
    if duplicate or len(categories) != len(REFERENCE_RULES) or covered != set(REFERENCE_RULES):
        _add_once(gates, "adversarial-matrix-incomplete")
    false_accepts = 0
    canonical_false_rejects = 0
    rule_results: dict[str, dict[str, object]] = {}
    for category, (verdict, reason) in REFERENCE_RULES.items():
        row = categories.get(category)
        submitted_verdict = row.get("expectedVerdict") if row else None
        submitted_reason = row.get("expectedReason") if row else None
        matches = submitted_verdict == verdict and submitted_reason == reason
        if not matches:
            if category == "canonical-valid":
                canonical_false_rejects += 1
            else:
                false_accepts += 1
        rule_results[category] = {
            "referenceVerdict": verdict,
            "referenceReason": reason,
            "submittedMatchesReference": matches,
        }
    accuracy = (len(REFERENCE_RULES) - false_accepts - canonical_false_rejects) / len(REFERENCE_RULES)
    if false_accepts:
        _add_once(gates, "false-accept-observed")
    if canonical_false_rejects:
        _add_once(gates, "canonical-false-reject-observed")
    dimensions = {name: _dimension_score(payload, name) for name in WEIGHTS}
    meta_score = float(sum(dimensions.values()))
    release = payload["releaseGate"]
    if release["minimumMetaScore"] != MINIMUM_META_SCORE:
        _add_once(gates, "release-threshold-invalid")
    if (release["hardGateCount"] != 0 or release["requiredRuleCategoryCoverage"] != 1.0 or release["adversarialExpectedOutcomeAccuracy"] != 1.0 or release["falseAcceptCount"] != 0 or release["canonicalFalseRejectCount"] != 0):
        _add_once(gates, "release-gate-invalid")
    safety_reject = "safety-regression-not-rejected" in gates
    verdict = "REJECT" if safety_reject else "APPLY"
    if gates or meta_score < MINIMUM_META_SCORE or coverage < 1.0 or accuracy < 1.0:
        verdict = "REJECT" if safety_reject else "HOLD"
    gates.sort()
    return {
        "schemaVersion": "awx.three-way-long-tail.design-grade.v1",
        "designIdHash": "sha256:" + hashlib.sha256(payload["designId"].encode("utf-8")).hexdigest(),
        "designRevisionHash": "sha256:" + hashlib.sha256(payload["designRevision"].encode("utf-8")).hexdigest(),
        "dimensions": dimensions,
        "metaScore": meta_score,
        "hardGates": gates,
        "hardGateCount": len(gates),
        "requiredRuleCategoryCoverage": coverage,
        "adversarialExpectedOutcomeAccuracy": accuracy,
        "falseAcceptCount": false_accepts,
        "canonicalFalseRejectCount": canonical_false_rejects,
        "ruleResults": rule_results,
        "designVerdict": verdict,
        "primaryReason": "none" if verdict == "APPLY" else gates[0],
        "nextSingleProof": "none" if verdict == "APPLY" else "Correct the first listed hard-gate field in the sealed contract.",
    }


def grade_design(payload: dict[str, object], input_size: int) -> dict[str, object]:
    """Validate, replay, and grade without reflecting untrusted identifiers."""
    secret_pattern_hit_count = _validate_input(payload, input_size)
    first = _grade_design_core(payload)
    second = _grade_design_core(payload)
    replay_identical = canonical_json_bytes(first) == canonical_json_bytes(second)
    result = dict(first)
    result["hardGates"] = list(first["hardGates"])
    if not replay_identical:
        _add_once(result["hardGates"], "deterministic-replay-failed")
        result["hardGates"].sort()
        result["hardGateCount"] = len(result["hardGates"])
        result["designVerdict"] = "HOLD"
        result["primaryReason"] = "deterministic-replay-failed"
        result["nextSingleProof"] = "Remove the nondeterministic core result and rerun the same validated payload."
    result["observedEvidence"] = {
        "deterministicReplayByteIdentical": replay_identical,
        "secretPatternHitCount": secret_pattern_hit_count,
    }
    return result


def canonical_json_bytes(result: dict[str, object]) -> bytes:
    return (json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")


def _load_payload(path: Path) -> tuple[dict[str, object], int]:
    try:
        raw = path.read_bytes()
        if len(raw) > INPUT_LIMIT_BYTES:
            raise GradeDesignInputError("input-size-exceeded")
        payload = json.loads(raw.decode("utf-8"))
    except GradeDesignInputError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise GradeDesignInputError("malformed-json") from exc
    if not isinstance(payload, dict):
        raise GradeDesignInputError("malformed-json")
    return payload, len(raw)


def _write_atomic(path: Path, data: bytes) -> None:
    if len(data) > OUTPUT_LIMIT_BYTES:
        raise RuntimeError("output-size-exceeded")
    handle, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except Exception:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _validated_cli_paths(input_value: str, output_value: str | None, run_root_value: str | None) -> tuple[Path, Path | None]:
    input_path = Path(input_value)
    if output_value is None:
        return input_path, None
    if run_root_value is None:
        raise GradeDesignInputError("run-root-required")
    try:
        run_root = Path(run_root_value).resolve(strict=True)
        temp_root = Path(tempfile.gettempdir()).resolve(strict=True)
    except OSError as exc:
        raise GradeDesignInputError("run-root-invalid") from exc
    if not run_root.is_dir():
        raise GradeDesignInputError("run-root-invalid")
    if run_root == temp_root or not _is_within(run_root, temp_root):
        raise GradeDesignInputError("run-root-outside-temp")
    output_path = Path(output_value)
    try:
        output_parent = output_path.parent.resolve(strict=True)
        output_resolved = output_path.resolve(strict=False)
        input_resolved = input_path.resolve(strict=False)
    except OSError as exc:
        raise GradeDesignInputError("output-parent-invalid") from exc
    if os.path.normcase(str(output_resolved)) == os.path.normcase(str(input_resolved)):
        raise GradeDesignInputError("output-input-collision")
    if not output_parent.is_dir() or not _is_within(output_resolved, run_root):
        raise GradeDesignInputError("output-outside-run-root")
    return input_path, output_resolved


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output")
    parser.add_argument("--run-root")
    args = parser.parse_args(argv)
    try:
        input_path, output_path = _validated_cli_paths(args.input, args.output, args.run_root)
        payload, input_size = _load_payload(input_path)
        result = grade_design(payload, input_size)
        data = canonical_json_bytes(result)
        if len(data) > OUTPUT_LIMIT_BYTES:
            raise RuntimeError("output-size-exceeded")
        if output_path is not None:
            _write_atomic(output_path, data)
        else:
            sys.stdout.buffer.write(data)
        return 0 if result["designVerdict"] == "APPLY" else 1
    except GradeDesignInputError as exc:
        print(exc.reason_code, file=sys.stderr)
        return 2
    except Exception:
        print("internal-error", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
