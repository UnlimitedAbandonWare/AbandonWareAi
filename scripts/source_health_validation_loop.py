#!/usr/bin/env python3
"""Emit a repo-local source-health validation loop ledger.

This runner does not mutate source or claim product-runtime behavior. It records
one or more agent-budget validation cycles from the current sourceHealthScorecard
and its failure-pattern sidecars.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
from pathlib import Path
from typing import Any


DEFAULT_SCORECARD = Path("verification/source-health-scorecard.json")
DEFAULT_OUTPUT = Path("verification/source-health-validation-loop.json")
DEFAULT_CYCLES = Path("verification/source-health-validation-cycles.ndjson")

SECRET_PATTERN = re.compile(
    r"sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|"
    r"gsk_[A-Za-z0-9_-]{20,}|pcsk_[A-Za-z0-9_-]{20,}|"
    r"sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}"
)
WINDOWS_ABS_PATH = re.compile(r"(?<![A-Za-z])[A-Za-z]:[\\/][^\r\n\"']+")
REQUIRED_AMPLIFIER_TRACE_KEYS = [
    "hypernova.twpmP",
    "hypernova.cvarPhi",
    "hypernova.riskKAlloc",
    "hypernova.clampApplied",
    "sourceHealth.amplifiedSignalScore",
]
REQUIRED_BASE_TRACESTORE_KEYS = [
    "sourceHealth.failurePatternKind",
    "sourceHealth.patternId",
]
TRACE_KEY_FIELDS = {
    "traceStoreKeys",
    "requiredTraceStoreKeys",
    "amplifierTraceKeys",
}
REQUIRED_GATES = [
    "sourceHealthScorecard",
    "harmonyPressureReport",
    "checkLangchain4jVersionPurity",
    "checkSourceSetHygiene",
    "compileJava",
    "desktop_final_verification",
]
REQUIRED_EVIDENCE_SINKS = [
    "TraceStore",
    "DebugEventStore",
    "CFVM Failure Pattern",
]
REQUIRED_EVIDENCE_ARTIFACTS = [
    "riskLedger",
    "componentScores",
    "TraceStore keys",
    "DebugEvent NDJSON",
    "PatchDrop manifest",
]


def _utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def _read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8-sig", errors="ignore")
    except OSError:
        return ""


def _load_json(path: Path) -> dict[str, Any]:
    raw = _read_text(path)
    if not raw.strip():
        return {}
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return data if isinstance(data, dict) else {}


def _safe_rel_path(raw: Any, default: Path) -> Path:
    text = str(raw or "").strip()
    if not text or WINDOWS_ABS_PATH.search(text) or text.startswith(("/", "\\")) or ".." in Path(text).parts:
        return default
    return Path(text)


def _sha256_file(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError:
        return ""


def _safe_names(values: Any) -> list[str]:
    if isinstance(values, str):
        raw_values = values.split(",")
    elif isinstance(values, list):
        raw_values = values
    else:
        raw_values = []
    names: list[str] = []
    for value in raw_values:
        if not isinstance(value, str):
            continue
        text = value.strip()
        if text and not WINDOWS_ABS_PATH.search(text) and "/" not in text and "\\" not in text:
            names.append(text[:120])
    return names


def _trace_keys_from_json(value: Any) -> set[str]:
    keys: set[str] = set()
    if isinstance(value, dict):
        for key, child in value.items():
            if key in TRACE_KEY_FIELDS:
                keys.update(_safe_names(child))
            keys.update(_trace_keys_from_json(child))
    elif isinstance(value, list):
        for child in value:
            keys.update(_trace_keys_from_json(child))
    return keys


def _required_trace_store_keys(scorecard: dict[str, Any]) -> list[str]:
    keys = set(REQUIRED_BASE_TRACESTORE_KEYS)
    keys.update(REQUIRED_AMPLIFIER_TRACE_KEYS)
    queue = _producer_queue(scorecard)
    assignments = _assignments(queue)
    nearest_kind = str(scorecard.get("failurePatternKind") or "").strip()
    nearest_pattern = str(scorecard.get("patternId") or "").strip()
    matched = [
        assignment
        for assignment in assignments
        if (
            nearest_pattern
            and str(assignment.get("patternId") or "").strip() == nearest_pattern
        )
        or (
            nearest_kind
            and str(assignment.get("failurePatternKind") or "").strip() == nearest_kind
        )
    ]
    if not matched and assignments:
        matched = [assignments[0]]
    for assignment in matched:
        keys.update(_safe_names(assignment.get("requiredTraceStoreKeys")))
        keys.update(_safe_names(assignment.get("amplifierTraceKeys")))
    return sorted(keys)


def _producer_queue(scorecard: dict[str, Any]) -> dict[str, Any]:
    prediction = scorecard.get("failurePatternPrediction")
    if not isinstance(prediction, dict):
        return {}
    queue = prediction.get("producerValidationQueue")
    return queue if isinstance(queue, dict) else {}


def _assignments(queue: dict[str, Any]) -> list[dict[str, Any]]:
    return [row for row in queue.get("assignments", []) if isinstance(row, dict)]


def _producer_roles(assignments: list[dict[str, Any]]) -> list[str]:
    return sorted(
        {
            str(row.get("producerRole") or "").strip()
            for row in assignments
            if str(row.get("producerRole") or "").strip() in {"macmini", "notebook"}
        }
    )


def _safe_rel_string(raw: Any, default: Path) -> str:
    return _safe_rel_path(raw, default).as_posix()


def _safe_number(raw: Any) -> float:
    try:
        return float(raw or 0.0)
    except Exception:
        return 0.0


def _safe_int(raw: Any) -> int:
    try:
        return int(raw or 0)
    except Exception:
        return 0


def _producer_queue_summary(
    queue: dict[str, Any],
    assignments: list[dict[str, Any]],
    roles: list[str],
) -> dict[str, Any]:
    return {
        "schema": str(queue.get("schema") or ""),
        "method": str(queue.get("method") or ""),
        "maxDurationHours": _safe_int(queue.get("maxDurationHours")),
        "runtimeProductBehavior": queue.get("runtimeProductBehavior") is True,
        "assignmentCount": len(assignments),
        "producerRoles": roles,
        "externalEvidenceOnlyComponentIds": _safe_names(queue.get("externalEvidenceOnlyComponentIds")),
    }


def _producer_assignment_summaries(
    assignments: list[dict[str, Any]],
    required_evidence_sinks: list[str],
) -> list[dict[str, Any]]:
    summaries: list[dict[str, Any]] = []
    for index, assignment in enumerate(assignments, start=1):
        role = str(assignment.get("producerRole") or "").strip()
        if role not in {"macmini", "notebook"}:
            role = ""
        artifacts = _safe_names(assignment.get("requiredEvidenceArtifacts"))
        if not set(REQUIRED_EVIDENCE_ARTIFACTS).issubset(set(artifacts)):
            artifacts = list(REQUIRED_EVIDENCE_ARTIFACTS)
        sinks = _safe_names(assignment.get("requiredEvidenceSinks")) or required_evidence_sinks
        if not set(REQUIRED_EVIDENCE_SINKS).issubset(set(sinks)):
            sinks = list(REQUIRED_EVIDENCE_SINKS)
        summaries.append(
            {
                "queueRank": _safe_int(assignment.get("queueRank")) or index,
                "producerRole": role,
                "sourceRootKind": str(assignment.get("sourceRootKind") or "local-worktree"),
                "directCanonicalSourceEdit": assignment.get("directCanonicalSourceEdit") is True,
                "evidenceOnly": assignment.get("evidenceOnly", True) is True,
                "failurePatternKind": str(assignment.get("failurePatternKind") or ""),
                "patternId": str(assignment.get("patternId") or ""),
                "amplifiedSignalScore": round(_safe_number(assignment.get("amplifiedSignalScore")), 4),
                "componentScoreRefs": _safe_names(assignment.get("componentScoreRefs")),
                "requiredEvidenceArtifacts": artifacts,
                "requiredEvidenceSinks": sinks,
                "requiredTraceStoreKeys": _safe_names(assignment.get("requiredTraceStoreKeys")),
                "amplifierTraceKeys": _safe_names(assignment.get("amplifierTraceKeys")),
                "debugEventNdjsonPath": _safe_rel_string(
                    assignment.get("debugEventNdjsonPath"),
                    Path("verification/source-health-failure-pattern-events.ndjson"),
                ),
                "patchDropManifestPath": _safe_rel_string(
                    assignment.get("patchDropManifestPath"),
                    Path("verification/source-health-patchdrop-manifest-contract.json"),
                ),
                "stopConditions": _safe_names(assignment.get("stopConditions")),
            }
        )
    return summaries


def _sidecar_proof(root: Path, scorecard: dict[str, Any]) -> dict[str, Any]:
    artifacts = scorecard.get("failurePatternEvidenceArtifacts")
    if not isinstance(artifacts, dict):
        artifacts = {}
    debug_rel = _safe_rel_path(
        artifacts.get("debugEventNdjsonPath"),
        Path("verification/source-health-failure-pattern-events.ndjson"),
    )
    manifest_rel = _safe_rel_path(
        artifacts.get("patchDropManifestPath"),
        Path("verification/source-health-patchdrop-manifest-contract.json"),
    )
    debug_path = root / debug_rel
    manifest_path = root / manifest_rel
    manifest_hash = _sha256_file(manifest_path)
    debug_raw = _read_text(debug_path)
    manifest_raw = _read_text(manifest_path)
    combined = json.dumps(artifacts, ensure_ascii=False, sort_keys=True) + "\n" + debug_raw + "\n" + manifest_raw
    declared_hash = str(artifacts.get("patchDropManifestHash") or "").strip()
    debug_hash = ""
    debug_event_type = ""
    debug_store_ready = False
    cfvm_ready = False
    debug_trace_keys: set[str] = set()
    for line in debug_raw.splitlines():
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            event = {}
        if isinstance(event, dict):
            debug_hash = str(event.get("patchDropManifestHash") or "").strip()
            debug_event_type = str(event.get("eventType") or "").strip()
            debug_store_ready = "DebugEventStore" in json.dumps(event, ensure_ascii=False, sort_keys=True)
            cfvm_ready = "CFVM Failure Pattern" in json.dumps(event, ensure_ascii=False, sort_keys=True)
            debug_trace_keys = _trace_keys_from_json(event)
            break
    manifest_json = _load_json(manifest_path)
    manifest_trace_keys = _trace_keys_from_json(manifest_json)
    required_trace_keys = _required_trace_store_keys(scorecard)
    required_trace_key_set = set(required_trace_keys)
    manifest_ready = "DebugEventStore" in manifest_raw and "CFVM Failure Pattern" in manifest_raw
    artifact_ready = "DebugEventStore" in combined and "CFVM Failure Pattern" in combined
    trace_store_ready = (
        "TraceStore" in combined
        and required_trace_key_set.issubset(debug_trace_keys)
        and required_trace_key_set.issubset(manifest_trace_keys)
    )
    return {
        "debugEventNdjsonPresent": debug_path.is_file(),
        "patchDropManifestPresent": manifest_path.is_file(),
        "debugEventType": debug_event_type,
        "debugEventStoreReady": debug_store_ready and manifest_ready and artifact_ready,
        "cfvmFailurePatternReady": cfvm_ready and manifest_ready and artifact_ready,
        "traceStoreReady": trace_store_ready,
        "requiredTraceStoreKeys": required_trace_keys,
        "debugTraceStoreKeyCount": len(debug_trace_keys),
        "manifestTraceStoreKeyCount": len(manifest_trace_keys),
        "patchDropManifestHashMatches": bool(manifest_hash)
        and declared_hash == manifest_hash
        and debug_hash == manifest_hash,
        "secretPatternHits": len(SECRET_PATTERN.findall(combined)),
        "windowsAbsPathHits": len(WINDOWS_ABS_PATH.findall(combined)),
    }


def build_report(
    root: Path,
    max_duration_hours: int,
    max_cycles: int,
    code_gate_input: Path | None = None,
    code_gate_contract: Path | None = None,
    code_gate_contract_sha256: str | None = None,
    protected_outputs: tuple[Path, ...] = (),
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    scorecard_path = root / DEFAULT_SCORECARD
    scorecard = _load_json(scorecard_path)
    queue = _producer_queue(scorecard)
    assignments = _assignments(queue)
    roles = _producer_roles(assignments)
    first = assignments[0] if assignments else {}
    sidecar_proof = _sidecar_proof(root, scorecard)
    amplifier_trace_keys = [
        key
        for key in REQUIRED_AMPLIFIER_TRACE_KEYS
        if key in set(_safe_names(first.get("amplifierTraceKeys")))
        or any(key in set(_safe_names(row.get("amplifierTraceKeys"))) for row in assignments)
    ]
    if not amplifier_trace_keys:
        amplifier_trace_keys = list(REQUIRED_AMPLIFIER_TRACE_KEYS)
    prediction = scorecard.get("failurePatternPrediction")
    if not isinstance(prediction, dict):
        prediction = {}
    required_evidence_sinks = _safe_names(
        scorecard.get("requiredEvidenceSinks") or prediction.get("requiredEvidenceSinks")
    )
    if not set(REQUIRED_EVIDENCE_SINKS).issubset(set(required_evidence_sinks)):
        required_evidence_sinks = list(REQUIRED_EVIDENCE_SINKS)
    cycle_count = max(1, max_cycles)
    cycle_count = min(cycle_count, max(1, len(assignments) or 1))
    generated_at = _utc_now()
    nearest_kind = str(scorecard.get("failurePatternKind") or first.get("failurePatternKind") or "")
    nearest_pattern = str(scorecard.get("patternId") or first.get("patternId") or "")
    try:
        nearest_score = float(scorecard.get("amplifiedSignalScore", 0.0) or 0.0)
    except Exception:
        nearest_score = 0.0

    cycles: list[dict[str, Any]] = []
    for index in range(cycle_count):
        assignment = assignments[index % len(assignments)] if assignments else first
        cycles.append(
            {
                "schema": "source_health.validation_cycle.v1",
                "generatedAt": generated_at,
                "cycleIndex": index + 1,
                "mutatedSource": False,
                "producerRole": str(assignment.get("producerRole") or ""),
                "failurePatternKind": str(assignment.get("failurePatternKind") or nearest_kind),
                "patternId": str(assignment.get("patternId") or nearest_pattern),
                "amplifiedSignalScore": assignment.get("amplifiedSignalScore", nearest_score),
                "requiredEvidenceSinks": _safe_names(assignment.get("requiredEvidenceSinks"))
                or required_evidence_sinks,
                "requiredTraceStoreKeys": _safe_names(assignment.get("requiredTraceStoreKeys")),
                "amplifierTraceKeys": _safe_names(assignment.get("amplifierTraceKeys")),
                "debugEventNdjsonPath": "verification/source-health-failure-pattern-events.ndjson",
                "patchDropManifestPath": "verification/source-health-patchdrop-manifest-contract.json",
            }
        )

    combined_for_counts = json.dumps(scorecard, ensure_ascii=False, sort_keys=True)
    report = {
        "schema": "source_health.validation_loop.v1",
        "generatedAt": generated_at,
        "timeboxKind": "agent_safe_patch_budget",
        "maxDurationHours": max_duration_hours,
        "runtimeProductBehavior": False,
        "mutationAllowed": False,
        "cycleCount": len(cycles),
        "scorecardPath": DEFAULT_SCORECARD.as_posix(),
        "cyclesPath": DEFAULT_CYCLES.as_posix(),
        "requiredGates": list(REQUIRED_GATES),
        "stopConditions": [
            "all_p0_hard_gates_pass",
            "elapsed_hours_gte_9",
            "secret_leak_risk",
            "source_ownership_conflict",
            "patchdrop_manifest_missing",
        ],
        "nearestFailurePatternKind": nearest_kind,
        "nearestPatternId": nearest_pattern,
        "nearestAmplifiedSignalScore": round(nearest_score, 4),
        "producerRoles": roles,
        "producerValidationQueue": _producer_queue_summary(queue, assignments, roles),
        "producerAssignments": _producer_assignment_summaries(assignments, required_evidence_sinks),
        "requiredEvidenceSinks": required_evidence_sinks,
        "amplifierTraceKeys": amplifier_trace_keys,
        "sidecarProof": sidecar_proof,
        "secretPatternHits": len(SECRET_PATTERN.findall(combined_for_counts))
        + int(sidecar_proof.get("secretPatternHits", 0) or 0),
        "windowsAbsPathHits": len(WINDOWS_ABS_PATH.findall(combined_for_counts))
        + int(sidecar_proof.get("windowsAbsPathHits", 0) or 0),
    }
    if code_gate_input is not None:
        try:
            from scripts.agent_code_evidence_gate import evaluate_file
        except ModuleNotFoundError:
            from agent_code_evidence_gate import evaluate_file
        report["agentCodeEvidenceGate"] = evaluate_file(
            code_gate_input, contract_path=code_gate_contract, contract_sha256=code_gate_contract_sha256,
            protected_outputs=protected_outputs)
    return report, cycles


def _write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def _write_ndjson(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows)
    path.write_text(text, encoding="utf-8", newline="\n")


def _display_path(root: Path, path: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return hashlib.sha256(str(path).encode("utf-8")).hexdigest()[:16]


def _resolve_repo_output(root: Path, raw: str, failure_reason: str) -> Path:
    candidate = Path(raw)
    if not candidate.is_absolute():
        candidate = root / candidate
    try:
        resolved = candidate.resolve()
    except (OSError, RuntimeError):
        raise ValueError(failure_reason) from None
    if not resolved.is_relative_to(root):
        raise ValueError(failure_reason)
    return resolved


def _outputs_share_identity(first: Path, second: Path) -> bool:
    if first == second:
        return True
    try:
        return first.exists() and second.exists() and first.samefile(second)
    except (OSError, RuntimeError):
        raise ValueError("output_path_identity_unavailable") from None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Emit source-health validation loop ledger.")
    parser.add_argument("--root", default=".")
    parser.add_argument("--output", default=DEFAULT_OUTPUT.as_posix())
    parser.add_argument("--cycles-output", default=DEFAULT_CYCLES.as_posix())
    parser.add_argument("--max-duration-hours", type=int, default=9)
    parser.add_argument("--max-cycles", type=int, default=9)
    parser.add_argument("--skip-gradle", action="store_true")
    parser.add_argument("--code-gate-input", help="Optional independently collected gate envelope")
    parser.add_argument("--code-gate-contract", help="Separate immutable gate contract")
    parser.add_argument("--code-gate-contract-sha256", help="Independently supplied contract pin")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve()
    try:
        output = _resolve_repo_output(root, args.output, "output_path_outside_root")
        cycles_output = _resolve_repo_output(
            root,
            args.cycles_output,
            "cycles_output_path_outside_root",
        )
    except ValueError as exc:
        parser.error(str(exc))
    try:
        if _outputs_share_identity(output, cycles_output):
            parser.error("output_paths_conflict")
    except ValueError as exc:
        parser.error(str(exc))

    report, cycles = build_report(
        root,
        max(1, int(args.max_duration_hours or 9)),
        max(1, int(args.max_cycles or 1)),
        Path(args.code_gate_input) if args.code_gate_input else None,
        Path(args.code_gate_contract) if args.code_gate_contract else None,
        args.code_gate_contract_sha256,
        (output, cycles_output),
    )
    if "output_input_conflict" in report.get("agentCodeEvidenceGate", {}).get("reasonCodes", []):
        parser.error("output_input_conflict")
    report["cyclesPath"] = cycles_output.relative_to(root).as_posix() if cycles_output.is_relative_to(root) else DEFAULT_CYCLES.as_posix()
    _write_json(output, report)
    _write_ndjson(cycles_output, cycles)
    print(
        "[AWX][source-health-loop] "
        f"report={_display_path(root, output)} cycles={len(cycles)} "
        f"nearest={report['nearestFailurePatternKind']}/{report['nearestPatternId']} "
        f"secretHits={report['secretPatternHits']}"
    )
    if report["secretPatternHits"] or report["windowsAbsPathHits"]:
        return 1
    gate_result = report.get("agentCodeEvidenceGate")
    return {"PASS": 0, "HOLD": 3, "REJECT": 2}[gate_result["verdict"]] if gate_result else 0


if __name__ == "__main__":
    raise SystemExit(main())
