#!/usr/bin/env python3
"""Evaluate sealed adaptive-verifier route fixtures without external effects."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from pathlib import Path
from typing import Any, Iterable


MANIFEST_LIMIT_BYTES = 131_072
ROWS_LIMIT_BYTES = 2_097_152
OUTPUT_LIMIT_BYTES = 1_048_576
MAX_ROWS = 10_000
MAX_DEPTH = 24
MAX_NODES = 100_000

SCHEMA_VERSION = "demo1.adaptive-verifier-evaluation.v1"
RESULT_SCHEMA_VERSION = "demo1.adaptive-verifier-evaluation-result.v1"
ROUTE_ACTIONS = (
    "CONTINUE",
    "DISPATCH_PAIR",
    "HOLD",
    "SKIP_OWNER_CONTROLLED",
)
EVALUATION_SPLITS = {
    "tuning",
    "calibration",
    "locked_evaluation",
    "production_shadow",
}

MANIFEST_REQUIRED_KEYS = {
    "schemaVersion",
    "evaluationMode",
    "fixtureId",
    "fixtureSha256",
    "evaluationSplit",
    "detectorPolicyVersion",
    "labelPolicyVersion",
    "positiveClassDefinition",
    "allowedFailureClasses",
    "allowedPostProbeActions",
    "costPolicyVersion",
    "costPolicyRef",
    "calibrationPolicyVersion",
    "calibrationFitRef",
    "calibrationFitFixtureSha256",
    "foldAssignmentSha256",
    "thresholdPolicyVersion",
    "thresholdSelectionRef",
    "thresholdSelectionFixtureSha256",
}
MANIFEST_OPTIONAL_KEYS = {"costPolicy", "foldFitRefs"}
ROW_REQUIRED_KEYS = {
    "scenarioId",
    "expectedFailureClass",
    "observedFailureClass",
    "expectedPostProbeAction",
    "observedPostProbeAction",
    "labelProvenance",
}
ROW_OPTIONAL_KEYS = {
    "detectorScore",
    "calibrationVersion",
    "latencyMs",
    "foldId",
    "probeCallCount",
    "verifierCallCount",
    "aggregatorCallCount",
    "observedCostUnits",
    "evaluationPopulation",
    "wasTriggered",
    "auditStratum",
    "selectionProbability",
}

_SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
_SECRET_KEY_RE = re.compile(
    r"(?:api[_-]?key|authorization|client[_-]?secret|password|passwd|"
    r"private[_-]?key|owner[_-]?token|access[_-]?token|refresh[_-]?token|cookie)",
    re.IGNORECASE,
)
_SECRET_VALUE_RES = (
    re.compile(r"(?:^|[^a-z0-9])sk-[a-z0-9]", re.IGNORECASE),
    re.compile(r"(?:^|\s)bearer\s+[a-z0-9._~+/=-]+", re.IGNORECASE),
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"(?:^|[^a-z0-9])(?:ghp_|github_pat_|xox[baprs]-|sbp_)", re.IGNORECASE),
)


class EvaluationInputError(ValueError):
    """Stable, non-reflective rejection for untrusted evaluation input."""

    def __init__(self, reason_code: str):
        self.reason_code = reason_code
        super().__init__(reason_code)


def _is_secret_like_string(value: str) -> bool:
    return any(pattern.search(value) for pattern in _SECRET_VALUE_RES)


def _validate_structure(
    values: Iterable[Any], *, check_secrets: bool, max_nodes: int = MAX_NODES
) -> None:
    stack = [(value, 0) for value in values]
    node_count = 0
    while stack:
        value, depth = stack.pop()
        if depth > MAX_DEPTH:
            raise EvaluationInputError("structure-depth-exceeded")
        node_count += 1
        if node_count > max_nodes:
            raise EvaluationInputError("structure-node-limit-exceeded")

        if value is None or isinstance(value, bool) or isinstance(value, int):
            continue
        if isinstance(value, float):
            if not math.isfinite(value):
                raise EvaluationInputError("non-finite-number")
            continue
        if isinstance(value, str):
            if check_secrets and _is_secret_like_string(value):
                raise EvaluationInputError("unsafe-secret-like-content")
            continue
        if isinstance(value, list):
            stack.extend((item, depth + 1) for item in value)
            continue
        if isinstance(value, dict):
            for key, item in value.items():
                if not isinstance(key, str):
                    raise EvaluationInputError("schema-invalid")
                if check_secrets and (
                    _SECRET_KEY_RE.search(key) or _is_secret_like_string(key)
                ):
                    raise EvaluationInputError("unsafe-secret-like-content")
                stack.append((item, depth + 1))
            continue
        raise EvaluationInputError("schema-invalid")


def canonical_json_bytes(value: Any) -> bytes:
    """Return finite, deterministic UTF-8 JSON without a trailing newline."""

    _validate_structure((value,), check_secrets=False)
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError, OverflowError) as failure:
        raise EvaluationInputError("schema-invalid") from failure


def _require_nonempty_string(value: Any) -> None:
    if not isinstance(value, str) or not value.strip():
        raise EvaluationInputError("schema-invalid")


def _require_nullable_string(value: Any) -> None:
    if value is not None:
        _require_nonempty_string(value)


def _validate_sha256(value: Any) -> str:
    if not isinstance(value, str) or not _SHA256_RE.fullmatch(value):
        raise EvaluationInputError("invalid-sha256")
    return value.lower()


def _validate_number(
    value: Any,
    *,
    minimum: float,
    maximum: float,
    integer: bool = False,
    exclusive_minimum: bool = False,
) -> float | int:
    if isinstance(value, bool):
        raise EvaluationInputError("bool-as-number")
    if not isinstance(value, (int, float)):
        raise EvaluationInputError("schema-invalid")
    if isinstance(value, float) and not math.isfinite(value):
        raise EvaluationInputError("non-finite-number")
    if integer and (not isinstance(value, int) or isinstance(value, bool)):
        raise EvaluationInputError("numeric-out-of-range")
    below = value <= minimum if exclusive_minimum else value < minimum
    if below or value > maximum:
        raise EvaluationInputError("numeric-out-of-range")
    return value


def _validate_identifier(value: Any) -> None:
    if isinstance(value, str):
        if not value:
            raise EvaluationInputError("schema-invalid")
        return
    _validate_number(value, minimum=0, maximum=1_000_000, integer=True)


def _validate_manifest(manifest: Any) -> tuple[list[str], str]:
    if not isinstance(manifest, dict):
        raise EvaluationInputError("schema-invalid")
    keys = set(manifest)
    if not MANIFEST_REQUIRED_KEYS.issubset(keys):
        raise EvaluationInputError("schema-invalid")
    if not keys.issubset(MANIFEST_REQUIRED_KEYS | MANIFEST_OPTIONAL_KEYS):
        raise EvaluationInputError("schema-invalid")

    if manifest["schemaVersion"] != SCHEMA_VERSION:
        raise EvaluationInputError("schema-invalid")
    mode = manifest["evaluationMode"]
    if mode not in {"ROUTE_EVAL", "PAIRED_EVAL"}:
        raise EvaluationInputError("schema-invalid")
    if manifest["evaluationSplit"] not in EVALUATION_SPLITS:
        raise EvaluationInputError("schema-invalid")
    if manifest["positiveClassDefinition"] != (
        "expectedPostProbeAction==DISPATCH_PAIR"
    ):
        raise EvaluationInputError("schema-invalid")

    for key in (
        "fixtureId",
        "detectorPolicyVersion",
        "labelPolicyVersion",
    ):
        _require_nonempty_string(manifest[key])
    for key in (
        "costPolicyVersion",
        "costPolicyRef",
        "calibrationPolicyVersion",
        "calibrationFitRef",
        "thresholdPolicyVersion",
        "thresholdSelectionRef",
    ):
        _require_nullable_string(manifest[key])

    fixture_sha = _validate_sha256(manifest["fixtureSha256"])
    for key in (
        "calibrationFitFixtureSha256",
        "foldAssignmentSha256",
        "thresholdSelectionFixtureSha256",
    ):
        if manifest[key] is not None:
            _validate_sha256(manifest[key])

    failure_classes = manifest["allowedFailureClasses"]
    if (
        not isinstance(failure_classes, list)
        or not failure_classes
        or any(not isinstance(item, str) or not item for item in failure_classes)
        or len(set(failure_classes)) != len(failure_classes)
    ):
        raise EvaluationInputError("schema-invalid")

    actions = manifest["allowedPostProbeActions"]
    if (
        not isinstance(actions, list)
        or len(actions) != len(ROUTE_ACTIONS)
        or set(actions) != set(ROUTE_ACTIONS)
    ):
        raise EvaluationInputError("schema-invalid")

    if manifest["evaluationSplit"] == "locked_evaluation":
        _require_nonempty_string(manifest["thresholdSelectionRef"])
        selection_sha = _validate_sha256(
            manifest["thresholdSelectionFixtureSha256"]
        )
        if selection_sha == fixture_sha:
            raise EvaluationInputError("schema-invalid")

    cost_policy = manifest.get("costPolicy")
    if cost_policy is not None and not isinstance(cost_policy, dict):
        raise EvaluationInputError("schema-invalid")
    fold_fit_refs = manifest.get("foldFitRefs")
    if fold_fit_refs is not None and not isinstance(fold_fit_refs, (dict, list)):
        raise EvaluationInputError("schema-invalid")

    return failure_classes, mode


def _validate_route_rows(
    manifest: dict[str, Any], rows: Any, failure_classes: list[str]
) -> None:
    if not isinstance(rows, list):
        raise EvaluationInputError("schema-invalid")
    if len(rows) > MAX_ROWS:
        raise EvaluationInputError("row-count-exceeded")

    seen_scenarios: set[str] = set()
    allowed_row_keys = ROW_REQUIRED_KEYS | ROW_OPTIONAL_KEYS
    failure_class_set = set(failure_classes)
    for row in rows:
        if not isinstance(row, dict):
            raise EvaluationInputError("schema-invalid")
        keys = set(row)
        if not ROW_REQUIRED_KEYS.issubset(keys) or not keys.issubset(allowed_row_keys):
            raise EvaluationInputError("schema-invalid")

        for key in ROW_REQUIRED_KEYS:
            _require_nonempty_string(row[key])
        scenario_id = row["scenarioId"]
        if scenario_id in seen_scenarios:
            raise EvaluationInputError("duplicate-scenario-id")
        seen_scenarios.add(scenario_id)

        for key in ("expectedFailureClass", "observedFailureClass"):
            if row[key] not in failure_class_set:
                raise EvaluationInputError("invalid-failure-class")
        for key in ("expectedPostProbeAction", "observedPostProbeAction"):
            if row[key] == "PROBE" or row[key] not in ROUTE_ACTIONS:
                raise EvaluationInputError("invalid-post-probe-action")

        numeric_specs = {
            "detectorScore": (0, 1, False, False),
            "latencyMs": (0, 32_400_000, False, False),
            "probeCallCount": (0, 1, True, False),
            "verifierCallCount": (0, 2, True, False),
            "aggregatorCallCount": (0, 1, True, False),
            "observedCostUnits": (0, 1_000_000_000, False, False),
            "evaluationPopulation": (1, 1_000_000_000, True, False),
            "selectionProbability": (0, 1, False, True),
        }
        for key, (minimum, maximum, integer, exclusive_minimum) in numeric_specs.items():
            if key in row:
                _validate_number(
                    row[key],
                    minimum=minimum,
                    maximum=maximum,
                    integer=integer,
                    exclusive_minimum=exclusive_minimum,
                )
        if "foldId" in row:
            _validate_identifier(row["foldId"])
        if "calibrationVersion" in row:
            _require_nonempty_string(row["calibrationVersion"])
        if "auditStratum" in row:
            _require_nonempty_string(row["auditStratum"])
        if "wasTriggered" in row and not isinstance(row["wasTriggered"], bool):
            raise EvaluationInputError("schema-invalid")

        if manifest["evaluationSplit"] == "production_shadow":
            required_shadow = {
                "evaluationPopulation",
                "wasTriggered",
                "auditStratum",
                "selectionProbability",
            }
            if not required_shadow.issubset(keys):
                raise EvaluationInputError("schema-invalid")


def _ratio(numerator: float, denominator: float) -> float | None:
    return numerator / denominator if denominator else None


def _binary_dispatch_metrics(rows: list[dict[str, Any]]) -> dict[str, Any]:
    tp = fp = fn = tn = 0
    for row in rows:
        expected = row["expectedPostProbeAction"]
        observed = row["observedPostProbeAction"]
        if expected == "DISPATCH_PAIR":
            if observed == "DISPATCH_PAIR":
                tp += 1
            else:
                fn += 1
        elif expected == "CONTINUE":
            if observed == "DISPATCH_PAIR":
                fp += 1
            else:
                tn += 1

    precision = _ratio(tp, tp + fp)
    recall = _ratio(tp, tp + fn)
    f1 = _ratio(2 * tp, 2 * tp + fp + fn)
    fpr = _ratio(fp, fp + tn)
    eligible = tp + fp + fn + tn
    prevalence = _ratio(tp + fn, eligible)
    reason_codes: dict[str, str] = {}
    if precision is None:
        reason_codes["precision"] = "zero-predicted-positive"
    if recall is None:
        reason_codes["recall"] = "zero-actual-positive"
    if f1 is None:
        reason_codes["f1"] = "zero-f1-denominator"
    if fpr is None:
        reason_codes["fpr"] = "zero-actual-negative"
    if prevalence is None:
        reason_codes["prevalence"] = "zero-eligible-truth"
    return {
        "counts": {"tp": tp, "fp": fp, "fn": fn, "tn": tn},
        "eligibleCount": eligible,
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "fpr": fpr,
        "prevalence": prevalence,
        "reasonCodes": reason_codes,
    }


def _abstention_metrics(rows: list[dict[str, Any]]) -> dict[str, Any]:
    eligible_rows = [
        row
        for row in rows
        if row["expectedPostProbeAction"] in {"CONTINUE", "DISPATCH_PAIR"}
    ]
    positive = [row for row in eligible_rows if row["expectedPostProbeAction"] == "DISPATCH_PAIR"]
    negative = [row for row in eligible_rows if row["expectedPostProbeAction"] == "CONTINUE"]

    def is_abstention(row: dict[str, Any]) -> bool:
        return row["observedPostProbeAction"] in {"HOLD", "SKIP_OWNER_CONTROLLED"}

    count = sum(is_abstention(row) for row in eligible_rows)
    positive_count = sum(is_abstention(row) for row in positive)
    negative_count = sum(is_abstention(row) for row in negative)
    eligible_count = len(eligible_rows)
    reason_codes: dict[str, str] = {"selectiveRisk": "accepted-labels-unavailable"}
    coverage = _ratio(eligible_count - count, eligible_count)
    abstention_rate = _ratio(count, eligible_count)
    positive_rate = _ratio(positive_count, len(positive))
    negative_rate = _ratio(negative_count, len(negative))
    if coverage is None:
        reason_codes["coverage"] = "zero-eligible-truth"
        reason_codes["abstentionRate"] = "zero-eligible-truth"
    if positive_rate is None:
        reason_codes["positiveAbstentionRate"] = "zero-actual-positive"
    if negative_rate is None:
        reason_codes["negativeAbstentionRate"] = "zero-actual-negative"
    return {
        "eligibleCount": eligible_count,
        "count": count,
        "coverage": coverage,
        "abstentionRate": abstention_rate,
        "positiveCount": positive_count,
        "positiveAbstentionRate": positive_rate,
        "negativeCount": negative_count,
        "negativeAbstentionRate": negative_rate,
        "unexpectedOwnerSkipCount": sum(
            row["observedPostProbeAction"] == "SKIP_OWNER_CONTROLLED"
            for row in eligible_rows
        ),
        "selectiveRisk": None,
        "reasonCodes": reason_codes,
    }


def _route_confusion(rows: list[dict[str, Any]]) -> dict[str, Any]:
    matrix = {
        expected: {observed: 0 for observed in ROUTE_ACTIONS}
        for expected in ROUTE_ACTIONS
    }
    for row in rows:
        matrix[row["expectedPostProbeAction"]][row["observedPostProbeAction"]] += 1
    return {"actions": list(ROUTE_ACTIONS), "matrix": matrix, "total": len(rows)}


def _mean_defined(values: Iterable[float | None]) -> float | None:
    defined = [value for value in values if value is not None]
    return sum(defined) / len(defined) if defined else None


def _weighted_defined(
    pairs: Iterable[tuple[float | None, int]], total_support: int
) -> float | None:
    pairs = list(pairs)
    if not total_support or any(value is None and support for value, support in pairs):
        return None
    return sum((value or 0.0) * support for value, support in pairs) / total_support


def _failure_class_confusion(
    rows: list[dict[str, Any]], failure_classes: list[str]
) -> dict[str, Any]:
    total = len(rows)
    correct = sum(
        row["expectedFailureClass"] == row["observedFailureClass"] for row in rows
    )
    matrix = {
        expected: {observed: 0 for observed in failure_classes}
        for expected in failure_classes
    }
    for row in rows:
        matrix[row["expectedFailureClass"]][row["observedFailureClass"]] += 1

    per_class: dict[str, dict[str, Any]] = {}
    for failure_class in failure_classes:
        tp = sum(
            row["expectedFailureClass"] == failure_class
            and row["observedFailureClass"] == failure_class
            for row in rows
        )
        fp = sum(
            row["expectedFailureClass"] != failure_class
            and row["observedFailureClass"] == failure_class
            for row in rows
        )
        fn = sum(
            row["expectedFailureClass"] == failure_class
            and row["observedFailureClass"] != failure_class
            for row in rows
        )
        support = tp + fn
        precision = _ratio(tp, tp + fp)
        recall = _ratio(tp, support)
        f1 = _ratio(2 * tp, 2 * tp + fp + fn)
        reasons: dict[str, str] = {}
        if precision is None:
            reasons["precision"] = "zero-predicted-class"
        if recall is None:
            reasons["recall"] = "zero-class-support"
        if f1 is None:
            reasons["f1"] = "zero-class-f1-denominator"
        per_class[failure_class] = {
            "tp": tp,
            "fp": fp,
            "fn": fn,
            "support": support,
            "precision": precision,
            "recall": recall,
            "f1": f1,
            "reasonCodes": reasons,
        }

    supported = [metrics for metrics in per_class.values() if metrics["support"]]
    excluded = len(per_class) - len(supported)
    total_support = sum(metrics["support"] for metrics in per_class.values())
    micro_tp = correct
    micro_fp = total - correct
    micro_fn = total - correct
    return {
        "accuracy": _ratio(correct, total),
        "matrix": matrix,
        "perClass": per_class,
        "macro": {
            "precision": _mean_defined(metrics["precision"] for metrics in supported),
            "recall": _mean_defined(metrics["recall"] for metrics in supported),
            "f1": _mean_defined(metrics["f1"] for metrics in supported),
            "excludedZeroSupportCount": excluded,
        },
        "weighted": {
            "precision": _weighted_defined(
                ((metrics["precision"], metrics["support"]) for metrics in per_class.values()),
                total_support,
            ),
            "recall": _weighted_defined(
                ((metrics["recall"], metrics["support"]) for metrics in per_class.values()),
                total_support,
            ),
            "f1": _weighted_defined(
                ((metrics["f1"], metrics["support"]) for metrics in per_class.values()),
                total_support,
            ),
        },
        "micro": {
            "precision": _ratio(micro_tp, micro_tp + micro_fp),
            "recall": _ratio(micro_tp, micro_tp + micro_fn),
            "f1": _ratio(2 * micro_tp, 2 * micro_tp + micro_fp + micro_fn),
        },
        "total": total,
    }


def evaluate(manifest: Any, rows: Any) -> dict[str, Any]:
    """Validate and evaluate one sealed fixture entirely in memory."""

    _validate_structure((manifest, rows), check_secrets=True)
    manifest_bytes = canonical_json_bytes(manifest)
    rows_bytes = canonical_json_bytes(rows)
    if len(manifest_bytes) > MANIFEST_LIMIT_BYTES:
        raise EvaluationInputError("manifest-too-large")
    if len(rows_bytes) > ROWS_LIMIT_BYTES:
        raise EvaluationInputError("rows-too-large")

    failure_classes, mode = _validate_manifest(manifest)
    fixture_sha = _validate_sha256(manifest["fixtureSha256"])
    if hashlib.sha256(rows_bytes).hexdigest() != fixture_sha:
        raise EvaluationInputError("fixture-sha256-mismatch")
    if mode == "PAIRED_EVAL":
        raise EvaluationInputError("paired-evaluation-deferred")

    _validate_route_rows(manifest, rows, failure_classes)
    return {
        "schemaVersion": RESULT_SCHEMA_VERSION,
        "status": "OK",
        "evaluationMode": mode,
        "fixtureId": manifest["fixtureId"],
        "fixtureSha256": fixture_sha,
        "decisionAuthority": "route_only",
        "productionRecallStatus": "unobservable",
        "binaryDispatchMetrics": _binary_dispatch_metrics(rows),
        "abstentionMetrics": _abstention_metrics(rows),
        "routeConfusion": _route_confusion(rows),
        "failureClassConfusion": _failure_class_confusion(rows, failure_classes),
    }


def _read_bounded(path: Path, limit: int, too_large_reason: str) -> bytes:
    try:
        with path.open("rb") as handle:
            payload = handle.read(limit + 1)
    except OSError as failure:
        raise EvaluationInputError("input-unavailable") from failure
    if len(payload) > limit:
        raise EvaluationInputError(too_large_reason)
    return payload


def _parse_json(payload: bytes) -> Any:
    try:
        return json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as failure:
        raise EvaluationInputError("invalid-json") from failure


def _load_inputs(manifest_path: Path, rows_path: Path) -> tuple[Any, list[Any]]:
    manifest = _parse_json(
        _read_bounded(manifest_path, MANIFEST_LIMIT_BYTES, "manifest-too-large")
    )
    rows_payload = _read_bounded(rows_path, ROWS_LIMIT_BYTES, "rows-too-large")
    try:
        text = rows_payload.decode("utf-8")
    except UnicodeDecodeError as failure:
        raise EvaluationInputError("invalid-json") from failure
    rows: list[Any] = []
    for line in text.splitlines():
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except (json.JSONDecodeError, ValueError) as failure:
            raise EvaluationInputError("invalid-json") from failure
        if len(rows) > MAX_ROWS:
            raise EvaluationInputError("row-count-exceeded")
    return manifest, rows


def _write_bytes_line(payload: bytes) -> None:
    binary_stdout = getattr(sys.stdout, "buffer", None)
    if binary_stdout is not None:
        binary_stdout.write(payload + b"\n")
        binary_stdout.flush()
    else:
        sys.stdout.write(payload.decode("utf-8") + "\n")


def _write_json_line(value: Any) -> None:
    _write_bytes_line(canonical_json_bytes(value))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--rows", required=True)
    try:
        args = parser.parse_args(argv)
        manifest, rows = _load_inputs(Path(args.manifest), Path(args.rows))
        result = evaluate(manifest, rows)
        output = canonical_json_bytes(result)
        if len(output) + 1 > OUTPUT_LIMIT_BYTES:
            raise EvaluationInputError("output-too-large")
        _write_bytes_line(output)
        return 0
    except EvaluationInputError as failure:
        _write_json_line({"reasonCode": failure.reason_code, "status": "HOLD"})
        return 2
    except BaseException:
        _write_json_line(
            {"reasonCode": "internal-evaluation-failure", "status": "HOLD"}
        )
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
