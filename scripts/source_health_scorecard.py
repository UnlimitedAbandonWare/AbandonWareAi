#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import math
import os
import sys
from pathlib import Path
from typing import Any
import xml.etree.ElementTree as ET

try:
    from dynamic_rag_quant_audit import (
        AuditContractError as ClosureAuditContractError,
        load_and_validate_current_bundle,
        load_closure_history,
        load_closure_registry,
    )
except ModuleNotFoundError:
    from scripts.dynamic_rag_quant_audit import (
        AuditContractError as ClosureAuditContractError,
        load_and_validate_current_bundle,
        load_closure_history,
        load_closure_registry,
    )


DEFAULT_QUANT_METRICS = Path("verification/dynamic-rag-quant-audit-metrics.json")
DEFAULT_STRUCTURAL_BASELINE = Path("verification/structural-design-baseline.json")
DEFAULT_STRUCTURAL_DEBT_LEDGER = Path("verification/structural-design-debt-ledger.jsonl")
DEFAULT_CLOSURE_WAVE_REGISTRY = Path("verification/structural-repair-waves/registry.json")
STRUCTURAL_AUDIT_SCHEMA = "awx.structural-design-audit.v2"
QUANT_METRICS_SCHEMA = "awx.dynamic-rag-quant-audit-metrics.v2"
STRUCTURAL_BASELINE_SCHEMA = "awx.structural-design-baseline.v1"
STRUCTURAL_LEDGER_ROW_SCHEMA = "awx.structural-design-debt-ledger-row.v1"
CLOSURE_HISTORY_SUMMARY_SCHEMA = "awx.structural-repair-history-summary.v2"
CLOSURE_HISTORY_SUMMARY_FIELDS = frozenset(
    {
        "schemaVersion",
        "waveRegistrySha256",
        "waveCount",
        "journalSetSha256",
        "proofSetSha256",
        "eventCount",
        "rejectedFalsePositiveRootCauseGroups",
        "verifiedClosedRootCauseGroups",
    }
)
QUANT_ARTIFACT_LINK_FIELDS = frozenset(
    {"baselinePayloadSha256", "ledgerPayloadSha256", "metricsPayloadSha256"}
)
QUANT_CONTRACT_STATUSES = frozenset(
    {
        "missing",
        "malformed",
        "stale",
        "future_generated_at",
        "cross-artifact-mismatch",
        "schema-invalid",
    }
)
DEFAULT_HARMONY_METRICS = Path("verification/dynamic-rag-harmony-pressure-metrics.json")
DEFAULT_TEST_TREE_METRICS = Path("verification/test-tree-contamination-metrics.json")
DEFAULT_WEBSOAK_PROVIDER_SMOKE = Path("verification/websoak-kpi-smoke/websoak-kpi-provider-disabled.json")
DEFAULT_DB_GAP_MATRIX = Path("data/db-gap-report/gap_matrix.json")
DEFAULT_COMPLETION_AUDIT_DIR = Path("var/codex-smoke")
COMPLETION_AUDIT_GLOB = "awx-mcp-completion-audit*.json"
COMPLETION_AUDIT_MAX_AGE_SECONDS = 24 * 60 * 60
DEFAULT_GOAL_NEXT_STATUS = Path("var/codex-smoke/goal-next-auto.status.json")
GOAL_NEXT_STATUS_MAX_AGE_SECONDS = 24 * 60 * 60
DB_GAP_MATRIX_MAX_AGE_SECONDS = 24 * 60 * 60
SCORE_INPUT_MAX_AGE_SECONDS = 24 * 60 * 60
DEFAULT_VERIFY_BOOT_LOG = Path("logs/verify_boot_source_runtime_current.log")
VERIFY_BOOT_LOG_MAX_AGE_SECONDS = 24 * 60 * 60
DEFAULT_OUTPUT = Path("verification/source-health-scorecard.json")
DEFAULT_FAILURE_PATTERN_DEBUG_EVENT_NDJSON = Path("verification/source-health-failure-pattern-events.ndjson")
DEFAULT_FAILURE_PATTERN_PATCHDROP_MANIFEST = Path("verification/source-health-patchdrop-manifest-contract.json")
SUPABASE_DATA_API_EVIDENCE_NAMES = {
    "data_api_role_grants",
    "exposed_tables_without_rls",
    "rls_and_table_flags",
}
SUPABASE_SHADOW_MEMORY_EVIDENCE_NAMES = {
    "shadow_memory_candidate_tables",
    "shadow_memory_candidate_columns",
    "shadow_memory_metadata_fingerprints",
}
EXTERNAL_ONLY_NEXT_ACTIONS = {
    "collect_supabase_data_api_grants_and_rls_result_sets",
    "provide_supabase_project_ref_and_authenticated_readonly_mcp_or_cli_for_schema_advisor_snapshot",
    "repair_computer_use_gui_proof_boundary",
    "repair_browser_ui_proof_boundary",
}
EXTERNAL_EVIDENCE_ONLY_COMPONENT_IDS = {
    "supabase_external_evidence",
    "computer_use_evidence_boundary",
    "browser_ui_evidence_boundary",
}
NO_LOCAL_SOURCE_ACTION = "no_local_source_action_external_evidence_needed"
DESKTOP_ONLY_NEXT_ACTION = "none_for_desktop_only"
SUPABASE_LIVE_PROOF_DOC_REFS = [
    "https://supabase.com/docs/guides/getting-started/mcp",
    "https://supabase.com/docs/guides/api/securing-your-api",
    "https://supabase.com/docs/guides/security/product-security",
    "https://supabase.com/changelog/45329-breaking-change-tables-not-exposed-to-data-and-graphql-api-automatically",
]
SUPABASE_LIVE_PROOF_CONTRACT_SIGNALS = [
    "mcp_project_scoped_read_only",
    "data_api_grants_required",
    "rls_policy_required",
    "secret_keys_backend_only",
    "advisors_required_before_schema_claim",
]
FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS = (
    "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
    "com.example.lms.orchestration.StrategyConflictResolverTest",
    "com.example.lms.orchestration.ExecutionPlanApplierTest",
    "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
)

TEST_RESULT_TASK_NAMES = ("test", "crossSubsystemContractTest")


def _stable_hash(value: Any) -> str:
    return hashlib.sha256(str(value).encode("utf-8")).hexdigest()[:12]


def _load_json_file(path: Path) -> dict[str, Any]:
    last_error: Exception | None = None
    for encoding in ("utf-8-sig", "utf-16", "utf-16-le", "utf-16-be"):
        try:
            return json.loads(path.read_text(encoding=encoding))
        except (UnicodeError, json.JSONDecodeError) as exc:
            last_error = exc
            continue
    if last_error is not None:
        raise last_error
    return {}


def _read_json(root: Path, relative: Path) -> dict[str, Any]:
    path = root / relative
    if not path.exists():
        return {}
    return _load_json_file(path)


def _safe_is_dir(path: Path) -> bool:
    try:
        return path.is_dir()
    except OSError:
        return False


def _read_latest_completion_audit(root: Path) -> tuple[dict[str, Any], str]:
    directory = root / DEFAULT_COMPLETION_AUDIT_DIR
    if not _safe_is_dir(directory):
        return {}, ""
    canonical = directory / "awx-mcp-completion-audit-current.json"
    if canonical.is_file():
        try:
            canonical_data = _load_json_file(canonical)
            if isinstance(canonical_data, dict):
                return canonical_data, canonical.relative_to(root).as_posix()
        except Exception:
            pass
    candidates = sorted(
        (
            path
            for path in directory.glob(COMPLETION_AUDIT_GLOB)
            if path.is_file() and path != canonical
        ),
        key=lambda path: (path.stat().st_mtime, path.name),
        reverse=True,
    )
    for path in candidates:
        try:
            candidate_data = _load_json_file(path)
            if isinstance(candidate_data, dict):
                return candidate_data, path.relative_to(root).as_posix()
        except Exception:
            continue
    return {}, ""


def _artifact_freshness(raw_generated_at: Any, max_age_seconds: int) -> tuple[bool, bool, int, str]:
    text = str(raw_generated_at or "").strip()
    if not text:
        return False, False, -1, "missing_generated_at"
    try:
        generated_at = _dt.datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return False, False, -1, "invalid_generated_at"
    now = _dt.datetime.now(_dt.timezone.utc)
    generated_at = generated_at.astimezone(_dt.timezone.utc)
    age_seconds = int((now - generated_at).total_seconds())
    if age_seconds < -300:
        return True, False, age_seconds, "future_generated_at"
    if age_seconds > max_age_seconds:
        return True, False, age_seconds, "stale"
    return True, True, max(0, age_seconds), "current"


def _file_mtime_freshness(path: Path, max_age_seconds: int) -> tuple[bool, bool, int, str]:
    try:
        modified_at = _dt.datetime.fromtimestamp(path.stat().st_mtime, tz=_dt.timezone.utc)
    except OSError:
        return False, False, -1, "missing"
    age_seconds = int((_dt.datetime.now(_dt.timezone.utc) - modified_at).total_seconds())
    if age_seconds < -300:
        return True, False, age_seconds, "future_mtime"
    if age_seconds > max_age_seconds:
        return True, False, age_seconds, "stale"
    return True, True, max(0, age_seconds), "current"


def _canonical_json_input(
    root: Path,
    relative: Path,
    max_age_seconds: int = SCORE_INPUT_MAX_AGE_SECONDS,
) -> tuple[dict[str, Any], dict[str, Any]]:
    path = root / relative
    projection: dict[str, Any] = {
        "path": relative.as_posix(),
        "pathHash": _stable_hash(relative.as_posix()),
        "present": False,
        "parsed": False,
        "generatedAt": "",
        "ageSeconds": -1,
        "fresh": False,
        "status": "missing",
        "reason": "missing",
    }
    try:
        if not path.is_file():
            return {}, projection
    except OSError:
        return {}, projection
    projection["present"] = True
    try:
        data = _load_json_file(path)
    except Exception:
        projection["status"] = "malformed"
        projection["reason"] = "malformed"
        return {}, projection
    if not isinstance(data, dict):
        projection["status"] = "malformed"
        projection["reason"] = "malformed"
        return {}, projection
    projection["parsed"] = True
    raw_generated_at = data.get("generatedAt")
    projection["generatedAt"] = str(raw_generated_at or "").strip()
    _, fresh, age_seconds, status = _artifact_freshness(
        raw_generated_at,
        max_age_seconds,
    )
    projection["ageSeconds"] = age_seconds
    projection["fresh"] = fresh and status == "current"
    projection["status"] = status
    projection["reason"] = status
    return data, projection


class QuantMetricsContractError(ValueError):
    def __init__(self, status: str) -> None:
        safe_status = status if status in QUANT_CONTRACT_STATUSES else "schema-invalid"
        super().__init__(safe_status)
        self.status = safe_status


def _quant_canonical_json_bytes(value: Any) -> bytes:
    try:
        rendered = json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
    except (TypeError, ValueError) as exc:
        raise QuantMetricsContractError("schema-invalid") from exc
    return (rendered + "\n").encode("utf-8")


def _quant_payload(value: dict[str, Any], *, metrics: bool = False) -> dict[str, Any]:
    payload = {
        key: item
        for key, item in value.items()
        if key not in {"generatedAt", "auditRunId", "artifactLinks"}
    }
    if metrics:
        payload.pop("semanticArtifactHash", None)
    return payload


def _quant_hash_is_valid(value: Any) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


def _load_required_bundle_json(path: Path) -> dict[str, Any]:
    try:
        if not path.is_file():
            raise QuantMetricsContractError("missing")
        value = _load_json_file(path)
    except QuantMetricsContractError:
        raise
    except Exception as exc:
        raise QuantMetricsContractError("malformed") from exc
    if not isinstance(value, dict):
        raise QuantMetricsContractError("malformed")
    return value


def _load_required_ledger(path: Path) -> list[dict[str, Any]]:
    try:
        if not path.is_file():
            raise QuantMetricsContractError("missing")
        rows: list[dict[str, Any]] = []
        with path.open("r", encoding="utf-8-sig", newline="") as handle:
            for raw_line in handle:
                if not raw_line.strip():
                    continue
                row = json.loads(raw_line)
                if not isinstance(row, dict):
                    raise QuantMetricsContractError("malformed")
                rows.append(row)
        return rows
    except QuantMetricsContractError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise QuantMetricsContractError("malformed") from exc


def validate_current_quant_bundle(root: Path) -> dict[str, Any]:
    root = Path(root).resolve()
    metrics, freshness = _canonical_json_input(root, DEFAULT_QUANT_METRICS)
    if not freshness.get("present"):
        raise QuantMetricsContractError("missing")
    if not freshness.get("parsed"):
        raise QuantMetricsContractError("malformed")
    freshness_status = str(freshness.get("status") or "")
    if freshness_status != "current":
        if freshness_status == "stale":
            raise QuantMetricsContractError("stale")
        if freshness_status == "future_generated_at":
            raise QuantMetricsContractError("future_generated_at")
        raise QuantMetricsContractError("schema-invalid")

    baseline = _load_required_bundle_json(root / DEFAULT_STRUCTURAL_BASELINE)
    ledger_rows = _load_required_ledger(root / DEFAULT_STRUCTURAL_DEBT_LEDGER)
    if (
        metrics.get("schemaVersion") != QUANT_METRICS_SCHEMA
        or baseline.get("schemaVersion") != STRUCTURAL_BASELINE_SCHEMA
        or any(
            row.get("schemaVersion") != STRUCTURAL_LEDGER_ROW_SCHEMA
            for row in ledger_rows
        )
    ):
        raise QuantMetricsContractError("schema-invalid")

    baseline_id = metrics.get("baselineId")
    audit_run_id = metrics.get("auditRunId")
    links = metrics.get("artifactLinks")
    if (
        not _quant_hash_is_valid(baseline_id)
        or not _quant_hash_is_valid(audit_run_id)
        or not isinstance(links, dict)
        or set(links) != QUANT_ARTIFACT_LINK_FIELDS
        or not all(_quant_hash_is_valid(value) for value in links.values())
        or not _quant_hash_is_valid(metrics.get("semanticArtifactHash"))
    ):
        raise QuantMetricsContractError("schema-invalid")

    envelopes = [baseline, *ledger_rows, metrics]
    generated_values = {value.get("generatedAt") for value in envelopes}
    run_ids = {value.get("auditRunId") for value in envelopes}
    if (
        baseline.get("baselineId") != baseline_id
        or any(row.get("baselineId") != baseline_id for row in ledger_rows)
        or any(value.get("artifactLinks") != links for value in envelopes)
        or len(run_ids) != 1
        or run_ids != {audit_run_id}
        or len(generated_values) != 1
        or None in generated_values
    ):
        raise QuantMetricsContractError("cross-artifact-mismatch")

    baseline_payload = _quant_payload(baseline)
    ledger_payloads = [_quant_payload(row) for row in ledger_rows]
    metrics_payload = _quant_payload(metrics, metrics=True)
    baseline_hash = hashlib.sha256(_quant_canonical_json_bytes(baseline_payload)).hexdigest()
    ledger_hash = hashlib.sha256(
        b"".join(_quant_canonical_json_bytes(row) for row in ledger_payloads)
    ).hexdigest()
    metrics_hash = hashlib.sha256(_quant_canonical_json_bytes(metrics_payload)).hexdigest()
    expected_links = {
        "baselinePayloadSha256": baseline_hash,
        "ledgerPayloadSha256": ledger_hash,
        "metricsPayloadSha256": metrics_hash,
    }
    if (
        links != expected_links
        or metrics.get("semanticArtifactHash") != metrics_hash
    ):
        raise QuantMetricsContractError("cross-artifact-mismatch")
    closure_summary = metrics_payload.get("closureHistorySummary")
    ledger_summary = metrics_payload.get("ledgerSummary")
    if (
        not isinstance(closure_summary, dict)
        or set(closure_summary) != CLOSURE_HISTORY_SUMMARY_FIELDS
        or closure_summary.get("schemaVersion") != CLOSURE_HISTORY_SUMMARY_SCHEMA
        or not isinstance(ledger_summary, dict)
    ):
        raise QuantMetricsContractError("schema-invalid")
    try:
        registry = load_closure_registry(
            root=root,
            registry_path=DEFAULT_CLOSURE_WAVE_REGISTRY,
        )
        history = load_closure_history(root=root, registry=registry)
    except ClosureAuditContractError as exc:
        raise QuantMetricsContractError("cross-artifact-mismatch") from exc
    if (
        closure_summary != history.summary
        or closure_summary.get("verifiedClosedRootCauseGroups")
        != ledger_summary.get("verifiedClosedRootCauseGroups")
    ):
        raise QuantMetricsContractError("cross-artifact-mismatch")
    expected_run_id = hashlib.sha256(
        (
            f"{STRUCTURAL_AUDIT_SCHEMA}|{baseline_id}|{baseline_hash}|"
            f"{ledger_hash}|{metrics_hash}|"
            f"{history.summary['waveRegistrySha256']}|"
            f"{history.summary['journalSetSha256']}|"
            f"{history.summary['proofSetSha256']}"
        ).encode("utf-8")
    ).hexdigest()
    if audit_run_id != expected_run_id:
        raise QuantMetricsContractError("cross-artifact-mismatch")
    try:
        validated_bundle = load_and_validate_current_bundle(root=root)
    except ClosureAuditContractError as exc:
        reason = getattr(exc, "reason_code", "semantic-nondeterminism")
        if reason == "required-input-missing":
            status = "missing"
        elif reason == "artifact-link-mismatch" or reason.startswith("closure-"):
            status = "cross-artifact-mismatch"
        elif reason == "required-input-malformed":
            status = "malformed"
        else:
            status = "schema-invalid"
        raise QuantMetricsContractError(status) from exc
    if (
        validated_bundle.baseline != baseline
        or validated_bundle.ledger_rows != tuple(ledger_rows)
        or validated_bundle.metrics != metrics
    ):
        raise QuantMetricsContractError("cross-artifact-mismatch")
    return validated_bundle.metrics


def _verify_boot_runtime_proof(root: Path) -> dict[str, Any]:
    path = root / DEFAULT_VERIFY_BOOT_LOG
    relative = DEFAULT_VERIFY_BOOT_LOG.as_posix()
    has_mtime, fresh, age_seconds, freshness_status = _file_mtime_freshness(
        path,
        VERIFY_BOOT_LOG_MAX_AGE_SECONDS,
    )
    proof: dict[str, Any] = {
        "passed": False,
        "path": relative,
        "pathHash": _stable_hash(relative),
        "fresh": fresh,
        "ageSeconds": age_seconds,
        "freshnessStatus": freshness_status,
        "intentionalTimeoutStop": False,
        "runtimePrecheckProven": False,
        "applicationReadyEventProven": False,
        "bootStartedProven": False,
        "bootSuccessProven": False,
        "loggedBootRunFailure": False,
        "probeMode": "missing",
    }
    if not has_mtime:
        return proof
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        proof["freshnessStatus"] = "unreadable"
        return proof

    def has_marker(marker: str) -> bool:
        return marker in text

    proof["intentionalTimeoutStop"] = has_marker("intentional-timeout-stop=true")
    proof["runtimePrecheckProven"] = has_marker("runtimePrecheckProven=True")
    proof["applicationReadyEventProven"] = has_marker("applicationReadyEventProven=True")
    proof["bootStartedProven"] = has_marker("bootStartedProven=True")
    proof["bootSuccessProven"] = has_marker("bootSuccessProven=True")
    raw_boot_failure = has_marker("Task :bootRun FAILED") or has_marker("bootRun logged failure")
    intentional_timeout_failure = proof["intentionalTimeoutStop"] and has_marker(
        "bootRun logged failure after intentional timeout stop"
    )
    proof["loggedBootRunFailure"] = raw_boot_failure and not intentional_timeout_failure
    for marker in (
        "probeMode=boot-success-proven",
        "probeMode=boot-started-proven",
        "probeMode=application-ready-event-proven",
        "probeMode=runtime-precheck-proven",
        "probeMode=blocker-scan-only",
    ):
        if marker in text:
            proof["probeMode"] = marker.split("=", 1)[1]
            break
    proof["passed"] = (
        fresh
        and proof["intentionalTimeoutStop"]
        and proof["runtimePrecheckProven"]
        and (proof["applicationReadyEventProven"] or proof["bootStartedProven"])
        and not proof["loggedBootRunFailure"]
    )
    return proof


def _aspect_order_contract_present(root: Path) -> bool:
    path = root / "src/test/java/ai/abandonware/nova/orch/aop/AspectOrderingContractTest.java"
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return False
    required_tokens = (
        "ExtremeZBurstAspect",
        "RagCompressionAspect",
        "LlmRouterAspect",
    )
    return all(token in text for token in required_tokens)


def _junit_xml_passed(path: Path) -> bool:
    if not _file_mtime_freshness(path, SCORE_INPUT_MAX_AGE_SECONDS)[1]:
        return False
    summary = _junit_xml_summary(path)
    if not summary:
        return False
    return (
        summary["tests"] > 0
        and summary["skipped"] == 0
        and summary["failures"] == 0
        and summary["errors"] == 0
    )


def _junit_xml_summary(path: Path) -> dict[str, int]:
    try:
        suite = ET.parse(path).getroot()
    except Exception:
        return {}
    try:
        tests = int(suite.attrib.get("tests", "0") or 0)
        skipped = int(suite.attrib.get("skipped", "0") or 0)
        failures = int(suite.attrib.get("failures", "0") or 0)
        errors = int(suite.attrib.get("errors", "0") or 0)
    except ValueError:
        return {}
    return {
        "tests": max(0, tests),
        "skipped": max(0, skipped),
        "failures": max(0, failures),
        "errors": max(0, errors),
    }


def _test_result_roots(root: Path) -> list[Path]:
    build_root = root / "build"
    host_id = os.environ.get("AWX_BUILD_HOST_ID", "").strip()
    result_roots: list[Path] = []

    def append_task_roots(base: Path) -> None:
        for task_name in TEST_RESULT_TASK_NAMES:
            result_roots.append(base / "test-results" / task_name)

    if host_id:
        append_task_roots(build_root / host_id)
    append_task_roots(build_root)
    if _safe_is_dir(build_root):
        for child in sorted(build_root.iterdir(), key=lambda item: item.name):
            if _safe_is_dir(child):
                append_task_roots(child)
    return result_roots


def _latest_test_result_roots(root: Path) -> list[Path]:
    latest: dict[str, tuple[float, list[Path]]] = {}
    seen: set[Path] = set()
    for result_root in _test_result_roots(root):
        if not _safe_is_dir(result_root) or result_root in seen:
            continue
        seen.add(result_root)
        try:
            timestamps = [path.stat().st_mtime for path in result_root.glob("TEST-*.xml") if path.is_file()]
        except OSError:
            continue
        if not timestamps:
            continue
        newest = max(timestamps)
        previous = latest.get(result_root.name)
        if previous is None or newest > previous[0]:
            latest[result_root.name] = (newest, [result_root])
        elif newest == previous[0]:
            previous[1].append(result_root)
    return [path for _, paths in latest.values() for path in paths]


def _focused_cross_subsystem_contract_proof(root: Path) -> dict[str, Any]:
    result_roots = _latest_test_result_roots(root)
    passed: set[str] = set()
    for class_name in FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS:
        candidates: list[tuple[float, Path]] = []
        try:
            for result_root in result_roots:
                candidate = result_root / f"TEST-{class_name}.xml"
                if candidate.is_file():
                    candidates.append((candidate.stat().st_mtime, candidate))
        except OSError:
            continue
        if not candidates:
            continue
        newest = max(timestamp for timestamp, _ in candidates)
        if all(_junit_xml_passed(path) for timestamp, path in candidates if timestamp == newest):
            passed.add(class_name)
    return {
        "passed": len(passed) == len(FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS),
        "passedCount": len(passed),
        "requiredCount": len(FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS),
    }


def _broad_runtime_test_proof(root: Path) -> dict[str, Any]:
    summaries: list[dict[str, Any]] = []
    blocking_summaries: list[dict[str, Any]] = []
    for result_root in _latest_test_result_roots(root):
        if not _safe_is_dir(result_root):
            continue
        suites = sorted(path for path in result_root.glob("TEST-*.xml") if path.is_file())
        if not suites:
            continue
        suite_count = 0
        test_count = 0
        executed_suite_count = 0
        all_suites_current = True
        failure_count = 0
        error_count = 0
        for suite in suites:
            summary = _junit_xml_summary(suite)
            if not summary:
                all_suites_current = False
                continue
            all_suites_current = all_suites_current and _file_mtime_freshness(suite, SCORE_INPUT_MAX_AGE_SECONDS)[1]
            suite_count += 1
            test_count += summary["tests"]
            if summary["tests"] > summary["skipped"]:
                executed_suite_count += 1
            failure_count += summary["failures"]
            error_count += summary["errors"]
        summaries.append({
            "passed": (
                executed_suite_count > len(FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS)
                and test_count > 0
                and all_suites_current
                and failure_count == 0
                and error_count == 0
            ),
            "suiteCount": suite_count,
            "testCount": test_count,
            "failureCount": failure_count,
            "errorCount": error_count,
        })
        if not all_suites_current or failure_count > 0 or error_count > 0:
            blocking_summaries.append(summaries[-1])
    if summaries:
        return max(blocking_summaries or summaries, key=_broad_runtime_test_proof_rank)
    return {
        "passed": False,
        "suiteCount": 0,
        "testCount": 0,
        "failureCount": 0,
        "errorCount": 0,
    }


def _broad_runtime_test_proof_rank(summary: dict[str, Any]) -> tuple[bool, bool, bool, int, int]:
    suite_count = int(summary.get("suiteCount", 0) or 0)
    test_count = int(summary.get("testCount", 0) or 0)
    failure_count = int(summary.get("failureCount", 0) or 0)
    error_count = int(summary.get("errorCount", 0) or 0)
    clean = failure_count == 0 and error_count == 0
    broad = suite_count > len(FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS) and test_count > 0
    return (
        bool(summary.get("passed")),
        broad,
        clean,
        suite_count,
        test_count,
    )


def _clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def _round4(value: float) -> float:
    return round(value, 4)


def _safe_names(value: Any) -> list[str]:
    if isinstance(value, list):
        raw = value
    elif isinstance(value, str):
        raw = value.split(",")
    else:
        raw = []
    out = []
    for item in raw:
        text = str(item).strip()
        if text and text.replace("_", "").replace("-", "").isalnum():
            out.append(text)
    return out


def _safe_tool_names(value: Any) -> list[str]:
    if isinstance(value, list):
        raw = value
    elif isinstance(value, str):
        raw = value.split(",")
    else:
        raw = []
    out = []
    for item in raw:
        text = str(item).strip()
        if text and text.replace("_", "").replace("-", "").replace(".", "").isalnum():
            out.append(text)
    return out


def _checked_evidence(report: dict[str, Any], check_id: str) -> tuple[bool, dict[str, str]]:
    checked = report.get("checked")
    if not isinstance(checked, list):
        return False, {}
    for row in checked:
        if not isinstance(row, dict) or row.get("id") != check_id:
            continue
        evidence: dict[str, str] = {}
        for part in str(row.get("evidence") or "").split(";"):
            if "=" not in part:
                continue
            key, value = part.split("=", 1)
            evidence[key.strip()] = value.strip()
        return row.get("ok") is True, evidence
    return False, {}


def _requirement_evidence(report: dict[str, Any], requirement_id: str) -> tuple[bool, dict[str, str]]:
    requirements = report.get("requirements")
    if not isinstance(requirements, list):
        return False, {}
    for row in requirements:
        if not isinstance(row, dict) or row.get("id") != requirement_id:
            continue
        evidence: dict[str, str] = {}
        for part in str(row.get("evidence") or "").split(";"):
            if "=" not in part:
                continue
            key, value = part.split("=", 1)
            evidence[key.strip()] = value.strip()
        return row.get("status") == "satisfied", evidence
    return False, {}


def _completion_audit_requirement_evidence_detail_summary(report: dict[str, Any]) -> dict[str, Any]:
    requirements = report.get("requirements")
    if not isinstance(requirements, list):
        requirements = []
    compact_rows = [
        row
        for row in requirements
        if isinstance(row, dict)
        and row.get("evidenceNeeded")
        and row.get("evidenceNeededDetailMode")
    ]
    modes = sorted({
        _safe_label(row.get("evidenceNeededDetailMode"))
        for row in compact_rows
        if _safe_label(row.get("evidenceNeededDetailMode"))
    })
    if not modes:
        mode = ""
    elif len(modes) == 1:
        mode = modes[0]
    else:
        mode = "mixed"
    omitted = 0
    max_length = 0
    hint = ""
    for row in compact_rows:
        try:
            omitted += int(row.get("evidenceNeededDetailsOmitted", 0) or 0)
        except Exception:
            pass
        if not hint and row.get("evidenceNeededDetailHint"):
            hint = str(row.get("evidenceNeededDetailHint") or "")
        evidence_needed = row.get("evidenceNeeded")
        if isinstance(evidence_needed, list):
            for item in evidence_needed:
                max_length = max(max_length, len(str(item)))
    return {
        "mode": mode,
        "rowCount": len(compact_rows),
        "detailsOmitted": omitted,
        "maxLength": max_length,
        "hint": hint,
    }


def _evidence_int(evidence: dict[str, str], key: str) -> int:
    try:
        return int(evidence.get(key, "0") or 0)
    except ValueError:
        return 0


def _evidence_bool(evidence: dict[str, str], key: str) -> bool:
    return evidence.get(key) == "True"


def _safe_relative_path(value: Any) -> str:
    text = str(value or "").strip().replace("\\", "/")
    if (
        not text
        or ":" in text
        or text.startswith("/")
        or text.startswith("//")
        or any(part == ".." for part in text.split("/"))
    ):
        return ""
    return text


def _safe_supabase_mcp_endpoint_template(value: Any) -> str:
    text = str(value or "").strip()
    if not text.startswith("https://mcp.supabase.com/mcp?"):
        return ""
    if any(token in text.lower() for token in ("authorization=", "bearer", "access_token=", "password=", "apikey=")):
        return ""
    allowed_chars = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?&=,$%{}")
    if any(ch not in allowed_chars for ch in text):
        return ""
    if "project_ref=${SUPABASE_PROJECT_REF}" not in text or "read_only=true" not in text:
        return ""
    return text


def _safe_label(value: Any) -> str:
    text = str(value or "").strip()
    if text.replace("_", "").replace("-", "").isalnum():
        return text[:80]
    return ""


def _local_interaction_proof_summary(
    *,
    computer_ready: bool,
    computer_evidence: dict[str, str],
    browser_ready: bool,
    browser_evidence: dict[str, str],
) -> dict[str, Any]:
    helper_secret_hits = _evidence_int(computer_evidence, "helperSecretPatternHits")
    computer_raw_secret_hits = _evidence_int(computer_evidence, "rawSecretPatternHits")
    computer_decision = _safe_label(computer_evidence.get("decision"))
    browser_decision = _safe_label(browser_evidence.get("decision"))
    return {
        "schema": "local_interaction_proof",
        "computerUse": {
            "observed": bool(computer_evidence),
            "boundaryReady": computer_ready,
            "liveReady": _evidence_bool(computer_evidence, "ready"),
            "safePendingProof": _evidence_bool(computer_evidence, "safePendingProof"),
            "decision": computer_decision or ("ok" if computer_ready else "evidence_needed"),
            "guiOnly": _evidence_bool(computer_evidence, "guiOnly"),
            "noTerminalAutomation": _evidence_bool(computer_evidence, "noTerminalAutomation"),
            "supportingOnly": _evidence_bool(computer_evidence, "supportingOnly"),
            "helperArtifact": _evidence_bool(computer_evidence, "helperArtifact"),
            "helperReachable": _evidence_bool(computer_evidence, "helperReachable"),
            "helperGeneratedAt": _evidence_bool(computer_evidence, "helperGeneratedAt"),
            "helperFresh": _evidence_bool(computer_evidence, "helperFresh"),
            "helperFreshnessStatus": _safe_label(computer_evidence.get("helperFreshnessStatus")),
            "helperAgeSeconds": _evidence_int(computer_evidence, "helperAgeSeconds"),
            "helperCountOnly": _evidence_bool(computer_evidence, "helperCountOnly"),
            "helperBoundary": _evidence_bool(computer_evidence, "helperBoundary"),
            "storesAppNames": _evidence_bool(computer_evidence, "storesAppNames"),
            "storesWindowTitles": _evidence_bool(computer_evidence, "storesWindowTitles"),
            "appCount": _evidence_int(computer_evidence, "appCount"),
            "targetableWindowCount": _evidence_int(computer_evidence, "targetableWindowCount"),
            "secretPatternHits": helper_secret_hits + computer_raw_secret_hits,
        },
        "browserUse": {
            "observed": bool(browser_evidence),
            "boundaryReady": browser_ready,
            "liveReady": _evidence_bool(browser_evidence, "ready"),
            "safePendingProof": _evidence_bool(browser_evidence, "safePendingProof"),
            "decision": browser_decision or ("ok" if browser_ready else "evidence_needed"),
            "artifactPath": _safe_relative_path(browser_evidence.get("artifactPath")),
            "artifactPresent": _evidence_bool(browser_evidence, "artifactPresent"),
            "artifactGeneratedAt": _evidence_bool(browser_evidence, "artifactGeneratedAt"),
            "artifactFresh": _evidence_bool(browser_evidence, "artifactFresh"),
            "artifactFreshnessStatus": _safe_label(browser_evidence.get("artifactFreshnessStatus")),
            "artifactAgeSeconds": _evidence_int(browser_evidence, "artifactAgeSeconds"),
            "reachable": _evidence_bool(browser_evidence, "reachable"),
            "localhost": _evidence_bool(browser_evidence, "localhost"),
            "publicDomain": _evidence_bool(browser_evidence, "publicDomain"),
            "targetAccepted": _evidence_bool(browser_evidence, "targetAccepted"),
            "screenshotCaptured": _evidence_bool(browser_evidence, "screenshotCaptured"),
            "targetContentVisible": _evidence_bool(browser_evidence, "targetContentVisible"),
            "browserSurface": _safe_label(browser_evidence.get("browserSurface")),
            "storesRawUrl": _evidence_bool(browser_evidence, "storesRawUrl"),
            "storesScreenshotPath": _evidence_bool(browser_evidence, "storesScreenshotPath"),
            "secretPatternHits": _evidence_int(browser_evidence, "secretPatternHits"),
        },
        "refreshCommand": {
            "scriptPath": "scripts/refresh_local_interaction_smokes.ps1",
            "outputPaths": [
                "var/codex-smoke/computer-use-smoke.json",
                "var/codex-smoke/browser-ui-smoke.json",
                "var/codex-smoke/local-interaction-smoke-refresh.summary.json",
            ],
            "mutationAllowed": False,
            "storesRawProbePayloads": False,
            "storesRawAppNames": False,
            "storesWindowTitles": False,
            "storesRawUrl": False,
            "storesScreenshotPath": False,
        },
    }


def _external_input_gate_proof_summary(
    *,
    requirement_ok: bool,
    evidence: dict[str, str],
) -> dict[str, Any]:
    evidence_needed = _safe_names(evidence.get("externalInputGateEvidenceNeeded"))
    required_evidence = {
        "SUPABASE_PROJECT_REF",
        "read_only_supabase_mcp_or_cli_auth",
        "execute_sql_results",
        "get_advisors_results",
    }
    status = _safe_label(evidence.get("externalInputGateStatus"))
    source = _safe_label(evidence.get("externalInputGateSource"))
    action = _safe_label(evidence.get("externalInputGateAction"))
    local_patch_justified = _evidence_bool(evidence, "externalInputGateLocalPatchJustified")
    mutation_allowed = _evidence_bool(evidence, "externalInputGateMutationAllowed")
    secret_hits = _evidence_int(evidence, "rawSecretPatternHits")
    supabase_external_boundary = (
        status == "external_input_needed"
        and source == "supabase_apply"
        and action == "set_SUPABASE_PROJECT_REF"
        and not local_patch_justified
        and not mutation_allowed
        and required_evidence.issubset(set(evidence_needed))
        and secret_hits == 0
    )
    desktop_local_boundary = (
        status in {"empty", "local_or_unknown"}
        and (
            (source in {"", "evidence_needed"} and action in {"", "evidence_needed"})
            or (source == "source_health_scorecard" and action not in {"", "evidence_needed"})
        )
        and local_patch_justified
        and not mutation_allowed
        and not evidence_needed
        and secret_hits == 0
    )
    boundary_ready = supabase_external_boundary or desktop_local_boundary
    return {
        "schema": "external_input_gate_proof",
        "observed": bool(evidence),
        "boundaryReady": boundary_ready,
        "status": status,
        "source": source,
        "action": action,
        "localPatchJustified": local_patch_justified,
        "mutationAllowed": mutation_allowed,
        "evidenceNeeded": evidence_needed,
        "secretPatternHits": secret_hits,
    }


def _goal_next_collection_packet_proof_summary(
    *,
    requirement_ok: bool,
    check_ok: bool,
    evidence: dict[str, str],
) -> dict[str, Any]:
    required_env_names = _safe_names(evidence.get("supabaseRequiredEnvNames"))
    required_mcp_tools = _safe_tool_names(evidence.get("supabaseRequiredMcpTools"))
    external_roles = _safe_names(evidence.get("externalRoles"))
    secret_hits = _evidence_int(evidence, "rawSecretPatternHits")
    windows_abs_path_hits = _evidence_int(evidence, "windowsAbsPathHits")
    web_probe_boundary_ready = (
        evidence.get("webProbeRefreshBoundaryReady") == "True"
        or evidence.get("webProbeRefreshReady") == "True"
    )
    web_probe_refresh_requested = evidence.get("webProbeRefreshRequested") == "True"
    web_probe_process_executed = evidence.get("webProbeRefreshProcessExecuted") == "True"
    web_probe_proof_ready = evidence.get("webProbeRefreshProofReady") == "True"
    boundary_ready = (
        check_ok
        and evidence.get("collectionPacketPresent") == "True"
        and evidence.get("schemaVersion") == "awx.goal_next_auto.collection_packet.v1"
        and evidence.get("supabaseReadOnly") == "True"
        and evidence.get("supabaseMutationAllowed") == "False"
        and evidence.get("supabaseMcpConfigTokenStored") == "False"
        and {"SUPABASE_PROJECT_REF"}.issubset(set(required_env_names))
        and {"execute_sql", "get_advisors"}.issubset(set(required_mcp_tools))
        and {"macmini", "notebook"}.issubset(set(external_roles))
        and evidence.get("externalSourceIsolation") == "True"
        and web_probe_boundary_ready
        and (
            not web_probe_refresh_requested
            or (web_probe_process_executed and web_probe_proof_ready)
        )
        and evidence.get("localInteractionRefreshReady") == "True"
        and evidence.get("computerUseSafe") == "True"
        and evidence.get("browserUseSafe") == "True"
        and secret_hits == 0
        and windows_abs_path_hits == 0
    )
    return {
        "schema": "goal_next_collection_packet_proof",
        "observed": bool(evidence),
        "boundaryReady": boundary_ready,
        "requirementStatus": "satisfied" if (boundary_ready or requirement_ok) else "incomplete",
        "supabaseRequiredEnvNames": ",".join(required_env_names),
        "supabaseRequiredMcpTools": ",".join(required_mcp_tools),
        "supabaseReadOnly": _evidence_bool(evidence, "supabaseReadOnly"),
        "supabaseMutationAllowed": _evidence_bool(evidence, "supabaseMutationAllowed"),
        "supabaseMcpConfigTokenStored": _evidence_bool(evidence, "supabaseMcpConfigTokenStored"),
        "externalRoles": ",".join(external_roles),
        "externalSourceIsolation": _evidence_bool(evidence, "externalSourceIsolation"),
        "webProbeRefreshReady": _evidence_bool(evidence, "webProbeRefreshReady"),
        "webProbeRefreshBoundaryReady": web_probe_boundary_ready,
        "webProbeRefreshContractReady": _evidence_bool(evidence, "webProbeRefreshContractReady"),
        "webProbeRefreshProofReady": web_probe_proof_ready,
        "webProbeRefreshRequested": web_probe_refresh_requested,
        "webProbeRefreshProcessExecuted": web_probe_process_executed,
        "webProbeRefreshTargetCount": _evidence_int(evidence, "webProbeRefreshTargetCount"),
        "webProbeRefreshSourceCount": _evidence_int(evidence, "webProbeRefreshSourceCount"),
        "localInteractionRefreshReady": _evidence_bool(evidence, "localInteractionRefreshReady"),
        "computerUseSafe": _evidence_bool(evidence, "computerUseSafe"),
        "browserUseSafe": _evidence_bool(evidence, "browserUseSafe"),
        "secretPatternHits": secret_hits,
        "windowsAbsPathHits": windows_abs_path_hits,
    }


def _goal_next_status_proof_summary(root: Path) -> dict[str, Any]:
    status = _read_json(root, DEFAULT_GOAL_NEXT_STATUS)
    has_generated_at, fresh, age_seconds, freshness_status = _artifact_freshness(
        status.get("generatedAt") if status else "",
        GOAL_NEXT_STATUS_MAX_AGE_SECONDS,
    )
    gate = status.get("externalInputGate") if isinstance(status.get("externalInputGate"), dict) else {}
    evidence_needed = _safe_names(gate.get("evidenceNeeded"))
    required_evidence = {
        "SUPABASE_PROJECT_REF",
        "read_only_supabase_mcp_or_cli_auth",
        "execute_sql_results",
        "get_advisors_results",
    }
    status_decision = _safe_label(status.get("statusDecision"))
    latest_decision = _safe_label(status.get("latestDecision"))
    failure_classification = _safe_label(status.get("failureClassification"))
    first_action = _safe_label(status.get("firstAction"))
    first_action_source = _safe_label(status.get("firstActionSource"))
    gate_status = _safe_label(gate.get("status"))
    gate_source = _safe_label(gate.get("source"))
    gate_action = _safe_label(gate.get("action"))
    local_patch_justified = gate.get("localPatchJustified") is True
    mutation_allowed = gate.get("mutationAllowed") is True
    secret_hits = int(status.get("secretHits", 0) or 0) + int(gate.get("secretHits", 0) or 0)
    windows_abs_path_hits = int(gate.get("windowsAbsPathHits", 0) or 0)
    stale_latest = status.get("staleLatest") is True
    browser_stale_supporting = failure_classification == "browser-ui-smoke-stale" and stale_latest
    common_ready = (
        bool(status)
        and status.get("schemaVersion") == "awx.goal_next_auto.status.v1"
        and has_generated_at
        and fresh
        and status_decision == "evidence_needed"
        and latest_decision == "evidence_needed"
        and (failure_classification == "evidence_needed" or browser_stale_supporting)
        and (not stale_latest or browser_stale_supporting)
    )
    supabase_external_boundary = (
        first_action == "set_SUPABASE_PROJECT_REF"
        and first_action_source == "supabase_apply"
        and gate_status == "external_input_needed"
        and gate_source == "supabase_apply"
        and gate_action == "set_SUPABASE_PROJECT_REF"
        and not local_patch_justified
        and not mutation_allowed
        and required_evidence.issubset(set(evidence_needed))
        and secret_hits == 0
        and windows_abs_path_hits == 0
    )
    desktop_local_boundary = (
        (
            (first_action in {"", "evidence_needed"} and first_action_source in {"", "evidence_needed"})
            or (
                first_action not in {"", "evidence_needed"}
                and first_action_source == "source_health_scorecard"
                and gate_source == "source_health_scorecard"
                and gate_action == first_action
            )
        )
        and gate_status in {"empty", "local_or_unknown"}
        and (
            (gate_source in {"", "evidence_needed"} and gate_action in {"", "evidence_needed"})
            or (gate_source == "source_health_scorecard" and gate_action == first_action)
        )
        and local_patch_justified
        and not mutation_allowed
        and not evidence_needed
        and secret_hits == 0
        and windows_abs_path_hits == 0
    )
    boundary_ready = common_ready and (supabase_external_boundary or desktop_local_boundary)
    return {
        "schema": "goal_next_status_proof",
        "observed": bool(status),
        "path": DEFAULT_GOAL_NEXT_STATUS.as_posix(),
        "pathHash": _stable_hash(DEFAULT_GOAL_NEXT_STATUS.as_posix()),
        "generatedAt": has_generated_at,
        "fresh": fresh,
        "ageSeconds": age_seconds,
        "freshnessStatus": freshness_status,
        "boundaryReady": boundary_ready,
        "latestDecision": latest_decision,
        "statusDecision": status_decision,
        "failureClassification": failure_classification,
        "staleLatest": stale_latest,
        "firstAction": first_action,
        "firstActionSource": first_action_source,
        "externalInputGate": {
            "status": gate_status,
            "source": gate_source,
            "action": gate_action,
            "localPatchJustified": local_patch_justified,
            "mutationAllowed": mutation_allowed,
            "evidenceNeeded": evidence_needed,
        },
        "secretPatternHits": secret_hits,
        "windowsAbsPathHits": windows_abs_path_hits,
    }


def _goal_next_status_desktop_only_ready(proof: dict[str, Any]) -> bool:
    gate = proof.get("externalInputGate") if isinstance(proof.get("externalInputGate"), dict) else {}
    return (
        proof.get("boundaryReady") is True
        and str(proof.get("firstAction") or "") in {"", "evidence_needed"}
        and str(proof.get("firstActionSource") or "") in {"", "evidence_needed"}
        and str(gate.get("status") or "") in {"empty", "local_or_unknown"}
        and str(gate.get("source") or "") in {"", "evidence_needed"}
        and str(gate.get("action") or "") in {"", "evidence_needed"}
        and gate.get("localPatchJustified") is True
        and gate.get("mutationAllowed") is False
        and not gate.get("evidenceNeeded")
    )


def _safe_forbidden_peer_fields(value: Any) -> list[str]:
    allowed = {"rawCwd", "rawMessageText", "rawRepoRoot", "rawSummary"}
    out: list[str] = []
    for item in str(value or "").split(","):
        text = item.strip()
        if text in allowed and text not in out:
            out.append(text)
    return out


def _peer_evidence_bus_proof_summary(
    *,
    contract_ok: bool,
    contract_evidence: dict[str, str],
    artifact_ok: bool,
    artifact_evidence: dict[str, str],
) -> dict[str, Any]:
    contract_secret_hits = _evidence_int(contract_evidence, "rawSecretPatternHits")
    artifact_secret_hits = _evidence_int(artifact_evidence, "rawSecretPatternHits")
    target_metric = _safe_label(artifact_evidence.get("targetMetric"))
    node_role = _safe_label(artifact_evidence.get("nodeRole"))
    identity_resolution = _safe_label(artifact_evidence.get("identityResolution"))
    web_probe_ledger = _evidence_bool(artifact_evidence, "webProbeLedger")
    web_probe_source_count = _evidence_int(artifact_evidence, "webProbeSourceCount")
    web_probe_refresh_packet = _evidence_bool(artifact_evidence, "webProbeRefreshPacket")
    web_probe_refresh_target_count = _evidence_int(artifact_evidence, "webProbeRefreshTargetCount")
    web_probe_refresh_mutation_allowed = _evidence_bool(artifact_evidence, "webProbeRefreshMutationAllowed")
    web_probe_refresh_raw_content_stored = _evidence_bool(artifact_evidence, "webProbeRefreshRawContentStored")
    web_probe_raw_content_stored = _evidence_bool(artifact_evidence, "webProbeRawContentStored")
    web_probe_raw_query_stored = _evidence_bool(artifact_evidence, "webProbeRawQueryStored")
    lanes = {
        "supabase": _evidence_bool(contract_evidence, "supabaseLane"),
        "browser": _evidence_bool(contract_evidence, "browserLane"),
        "computer": _evidence_bool(contract_evidence, "computerLane"),
        "superpowers": _evidence_bool(contract_evidence, "superpowersLane"),
    }
    contract_ready = (
        contract_ok
        and _evidence_bool(contract_evidence, "manifestTool")
        and _evidence_bool(contract_evidence, "stdioHandler")
        and _evidence_bool(contract_evidence, "desktopControlLoop")
        and _evidence_bool(contract_evidence, "promptPackPresent")
        and _evidence_int(contract_evidence, "laneCount") >= 4
        and _evidence_bool(contract_evidence, "webProbeLedgerSchema")
        and _evidence_bool(contract_evidence, "webProbeRefreshPacketSchema")
        and all(lanes.values())
        and contract_secret_hits == 0
    )
    artifact_ready = (
        artifact_ok
        and _evidence_bool(artifact_evidence, "peerEvidenceBusArtifactPresent")
        and artifact_evidence.get("schemaVersion") == "awx.mcp.peer_evidence_bus.v1"
        and _evidence_bool(artifact_evidence, "ok")
        and artifact_evidence.get("decision") == "peer_evidence_bus"
        and target_metric == "harmony"
        and node_role == "desktop"
        and _evidence_int(artifact_evidence, "outputCount") >= 4
        and _evidence_bool(artifact_evidence, "promptPackPresent")
        and _evidence_bool(artifact_evidence, "claudePeersProtocol")
        and _evidence_bool(artifact_evidence, "requiresMutualClose")
        and _evidence_bool(artifact_evidence, "reopenRequiresExplicitFlag")
        and _evidence_bool(artifact_evidence, "safePeerIdentityContract")
        and web_probe_ledger
        and web_probe_source_count >= 4
        and web_probe_refresh_packet
        and web_probe_refresh_target_count >= 4
        and not web_probe_refresh_mutation_allowed
        and not web_probe_refresh_raw_content_stored
        and not web_probe_raw_content_stored
        and not web_probe_raw_query_stored
        and identity_resolution == "logical-name-preferred"
        and artifact_secret_hits == 0
    )
    return {
        "schema": "peer_evidence_bus_proof",
        "contractReady": contract_ready,
        "artifactReady": artifact_ready,
        "targetMetric": target_metric,
        "nodeRole": node_role,
        "laneCount": _evidence_int(contract_evidence, "laneCount"),
        "outputCount": _evidence_int(artifact_evidence, "outputCount"),
        "lanes": lanes,
        "manifestTool": _evidence_bool(contract_evidence, "manifestTool"),
        "stdioHandler": _evidence_bool(contract_evidence, "stdioHandler"),
        "desktopControlLoop": _evidence_bool(contract_evidence, "desktopControlLoop"),
        "promptPackPresent": (
            _evidence_bool(contract_evidence, "promptPackPresent")
            and _evidence_bool(artifact_evidence, "promptPackPresent")
        ),
        "claudePeersProtocol": _evidence_bool(artifact_evidence, "claudePeersProtocol"),
        "referenceToolCount": _evidence_int(artifact_evidence, "referenceTools"),
        "manualCheckTool": _safe_label(artifact_evidence.get("manualCheckTool")),
        "requiresMutualClose": _evidence_bool(artifact_evidence, "requiresMutualClose"),
        "reopenRequiresExplicitFlag": _evidence_bool(artifact_evidence, "reopenRequiresExplicitFlag"),
        "safePeerIdentityContract": _evidence_bool(artifact_evidence, "safePeerIdentityContract"),
        "webProbeLedger": web_probe_ledger,
        "webProbeSourceCount": web_probe_source_count,
        "webProbeRefreshPacket": web_probe_refresh_packet,
        "webProbeRefreshTargetCount": web_probe_refresh_target_count,
        "webProbeRefreshMutationAllowed": web_probe_refresh_mutation_allowed,
        "webProbeRefreshRawContentStored": web_probe_refresh_raw_content_stored,
        "webProbeRawContentStored": web_probe_raw_content_stored,
        "webProbeRawQueryStored": web_probe_raw_query_stored,
        "identityResolution": identity_resolution,
        "forbiddenRawFields": _safe_forbidden_peer_fields(artifact_evidence.get("forbiddenRawFields")),
        "secretPatternHits": contract_secret_hits + artifact_secret_hits,
    }


def _default_supabase_mcp_endpoint_template(safe: dict[str, Any]) -> str:
    env_names = {
        item.get("name")
        for item in safe.get("requiredEnv", [])
        if isinstance(item, dict)
    }
    if safe.get("readOnly") is True and "SUPABASE_PROJECT_REF" in env_names:
        return (
            "https://mcp.supabase.com/mcp?"
            "project_ref=${SUPABASE_PROJECT_REF}&read_only=true&features=database,debugging,docs"
        )
    return ""


def _safe_supabase_live_proof_details(report: dict[str, Any]) -> list[dict[str, Any]]:
    details: list[Any] = []
    primary_details = report.get("nextActionDetails")
    if isinstance(primary_details, list):
        details.extend(primary_details)
    supporting_details = report.get("supportingEvidenceNextActionDetails")
    if isinstance(supporting_details, list):
        details.extend(supporting_details)
    if not details:
        return []
    out: list[dict[str, Any]] = []
    for row in details:
        if not isinstance(row, dict) or row.get("action") != "collect-supabase-live-proof":
            continue
        safe: dict[str, Any] = {
            "action": "collect-supabase-live-proof",
            "nodeRole": str(row.get("nodeRole") or ""),
            "targetService": "supabase",
            "readOnly": row.get("readOnly") is True,
            "mutationAllowed": row.get("mutationAllowed") is True,
            "officialContractSignals": list(SUPABASE_LIVE_PROOF_CONTRACT_SIGNALS),
            "collectionGuards": {
                "mutationAllowed": False,
                "storeRawRows": False,
                "requireProjectScope": True,
                "requireAdvisors": True,
            },
            "applyCollectedEvidenceCommand": (
                "powershell -NoProfile -ExecutionPolicy Bypass "
                "-File scripts\\supabase_apply_collected_evidence.ps1"
            ),
            "requiredEnv": [
                {
                    "name": str(item.get("name") or ""),
                    "sensitive": item.get("sensitive") is True,
                }
                for item in row.get("requiredEnv", [])
                if isinstance(item, dict) and str(item.get("name") or "").replace("_", "").isalnum()
            ],
            "requiredMcpTools": _safe_names(row.get("requiredMcpTools")),
            "requiredResultNames": _safe_names(row.get("requiredResultNames")),
            "nextActions": _safe_names(row.get("nextActions")),
            "decision": str(row.get("decision") or ""),
        }
        supported_auth_modes = _safe_names(row.get("supportedAuthModes"))
        if supported_auth_modes:
            safe["supportedAuthModes"] = supported_auth_modes
        manual_auth_refs = _safe_names(row.get("manualAuthSensitiveEnvRefs"))
        if manual_auth_refs:
            safe["manualAuthSensitiveEnvRefs"] = manual_auth_refs
        if row.get("mcpOAuthSupported") is True:
            safe["mcpOAuthSupported"] = True
        endpoint_template = (
            _safe_supabase_mcp_endpoint_template(row.get("mcpEndpointTemplate"))
            or _default_supabase_mcp_endpoint_template(safe)
        )
        if endpoint_template:
            safe["mcpEndpointTemplate"] = endpoint_template
        for key in ("artifactPaths",):
            paths = [_safe_relative_path(item) for item in row.get(key, []) if _safe_relative_path(item)]
            if paths:
                safe[key] = paths
        for key in ("resultPathRecommendation", "advisorResultPathRecommendation"):
            path = _safe_relative_path(row.get(key))
            if path:
                safe[key] = path
        try:
            safe["queryCount"] = int(row.get("queryCount", 0) or 0)
        except Exception:
            safe["queryCount"] = 0
        docs_refs = [
            str(item)
            for item in row.get("docsRefs", [])
            if isinstance(item, str) and item.startswith("https://supabase.com/")
        ]
        safe["docsRefs"] = list(dict.fromkeys(docs_refs + SUPABASE_LIVE_PROOF_DOC_REFS))
        if str(row.get("importTool") or "").replace("_", "").isalnum():
            safe["importTool"] = str(row.get("importTool"))
        out.append(safe)
    return out


def _safe_patchdrop_sidecars(value: Any) -> list[str]:
    allowed = {
        ".patch",
        ".report.md",
        ".verify.log",
        ".sha256.txt",
        ".manifest.json",
        "pendingNotice",
    }
    raw = value if isinstance(value, list) else []
    out: list[str] = []
    for item in raw:
        text = str(item).strip()
        if text in allowed and text not in out:
            out.append(text)
    return out


def _producer_node_contracts(
    producer_roles: Any,
    trace_store_keys: Any,
    required_sidecars: Any,
) -> list[dict[str, Any]]:
    roles = [role for role in producer_roles or [] if role in {"macmini", "notebook"}]
    trace_keys = [str(key) for key in trace_store_keys or [] if str(key).strip()]
    sidecars = _safe_patchdrop_sidecars(required_sidecars)
    return [
        {
            "nodeRole": role,
            "sourceRootKind": "local-worktree",
            "directCanonicalSourceEdit": False,
            "evidenceOnly": True,
            "desktopFinalProof": "evidence_needed_until_desktop_verification",
            "requiredEvidenceSinks": _required_evidence_sinks(),
            "requiredTraceStoreKeys": trace_keys,
            "requiredDebugEventNdjsonPath": DEFAULT_FAILURE_PATTERN_DEBUG_EVENT_NDJSON.as_posix(),
            "requiredPatchDropManifestPath": DEFAULT_FAILURE_PATTERN_PATCHDROP_MANIFEST.as_posix(),
            "requiredManifestFields": [
                "sourceIsolation.guard",
                "sourceRootKind",
                "directCanonicalSourceEdit",
                "desktopFinalProof",
                "failurePatternKind",
                "patternId",
                "traceStoreKeys",
                "debugEventNdjsonPath",
                "requiredEvidenceSinks",
                "cfvmFailurePatternContract",
                "patchDropManifestHash",
                "autonomousValidationContract.maxDurationHours",
            ],
            "requiredSidecars": sidecars,
        }
        for role in roles
    ]


def _safe_external_apply_command(value: Any) -> str:
    text = str(value or "").strip()
    prefix = (
        "powershell -NoProfile -ExecutionPolicy Bypass "
        "-File scripts\\external_apply_collected_evidence.ps1 -Root . -Topic "
    )
    if not text.startswith(prefix):
        return ""
    topic = text[len(prefix):]
    if not topic or not topic.replace("_", "").replace("-", "").replace(".", "").replace(":", "").isalnum():
        return ""
    return text


def _safe_producer_command_templates(target_role: str, topic: str) -> list[str]:
    safe_topic = topic if topic else "<topic>"
    return [
        (
            "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> "
            f"--canonical-root <desktop-canonical-root> --node-role {target_role}"
        ),
        (
            "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> "
            "--canonical-root <desktop-canonical-root> --patchdrop-root <PatchDrop> "
            f"--producer-script <PatchDrop>\\producer_bundle.py --node-role {target_role} "
            f"--topic {safe_topic} --pathspec <relative/source/path>"
        ),
    ]


def _safe_external_evidence_details(report: dict[str, Any]) -> list[dict[str, Any]]:
    details: list[Any] = []
    primary_details = report.get("nextActionDetails")
    if isinstance(primary_details, list):
        details.extend(primary_details)
    supporting_details = report.get("supportingEvidenceNextActionDetails")
    if isinstance(supporting_details, list):
        details.extend(supporting_details)
    if not details:
        return []
    out: list[dict[str, Any]] = []
    for row in details:
        if not isinstance(row, dict) or row.get("action") != "collect-external-evidence-files":
            continue
        target_role = str(row.get("targetRole") or "").strip()
        if target_role not in {"macmini", "notebook"}:
            continue
        topic = str(row.get("topic") or "").strip()
        if not topic.replace("_", "").replace("-", "").replace(".", "").replace(":", "").isalnum():
            topic = ""
        sidecars = _safe_patchdrop_sidecars(row.get("requiredSidecars"))
        isolation = row.get("requiredSourceIsolation")
        if not isinstance(isolation, dict):
            isolation = {}
        guard = str(isolation.get("guard") or isolation.get("sourceIsolation.guard") or "").strip()
        source_root_kind = str(isolation.get("sourceRootKind") or "").strip()
        direct_canonical_source_edit = isolation.get("directCanonicalSourceEdit") is True
        evidence_only = isolation.get("evidenceOnly", True) is True
        desktop_final_proof = str(isolation.get("desktopFinalProof") or "").strip()
        try:
            raw_secret_pattern_hits = int(isolation.get("rawSecretPatternHits", 0) or 0)
        except Exception:
            raw_secret_pattern_hits = 0
        command = _safe_external_apply_command(row.get("applyCollectedEvidenceCommand"))
        if (
            not sidecars
            or guard != "PASS"
            or source_root_kind != "local-worktree"
            or direct_canonical_source_edit
            or not evidence_only
            or raw_secret_pattern_hits != 0
            or not command
        ):
            continue
        safe: dict[str, Any] = {
            "action": "collect-external-evidence-files",
            "nodeRole": "desktop",
            "targetRole": target_role,
            "requiredSidecars": sidecars,
            "requiredSourceIsolation": {
                "guard": guard,
                "sourceRootKind": source_root_kind,
                "directCanonicalSourceEdit": direct_canonical_source_edit,
                "evidenceOnly": evidence_only,
                "desktopFinalProof": desktop_final_proof or "evidence_needed",
                "rawSecretPatternHits": raw_secret_pattern_hits,
            },
            "applyCollectedEvidenceCommand": command,
            "producerCommandTemplates": _safe_producer_command_templates(target_role, topic),
            "nextActions": [
                f"run_{target_role}_external_node_smoke",
                f"collect_{target_role}_producer_handoff_json",
                f"submit_{target_role}_patchdrop_v3_bundle_sidecars",
            ],
            "decision": str(row.get("decision") or "evidence_needed"),
        }
        if topic:
            safe["topic"] = topic
        out.append(safe)
    return out


def _safe_archive_apply_command(value: Any) -> str:
    text = str(value or "").strip()
    expected = "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\awx_mcp_toolbox.ps1 -Tool archive.search"
    if text == expected:
        return text
    return ""


def _archive_action_names(value: Any) -> list[str]:
    allowed = {
        "create_or_point_archive_index",
        "run_archive_index_build",
        "set_ARCHIVE_INDEX_or_NAS_ARCHIVE_ROOT",
        "verify_archive_index_path",
        "rerun_archive_search_with_index_path",
        "rerun_archive_search",
    }
    return [item for item in _safe_names(value) if item in allowed]


def _default_archive_index_detail(report: dict[str, Any]) -> dict[str, Any]:
    next_actions = _archive_action_names(report.get("nextActions"))
    if not next_actions:
        next_actions = [
            "create_or_point_archive_index",
            "run_archive_index_build",
            "set_ARCHIVE_INDEX_or_NAS_ARCHIVE_ROOT",
            "verify_archive_index_path",
            "rerun_archive_search_with_index_path",
            "rerun_archive_search",
        ]
    return {
        "action": "collect-archive-index-proof",
        "nodeRole": "desktop",
        "targetService": "archive",
        "readOnly": True,
        "mutationAllowed": False,
        "requiredEnvNames": ["ARCHIVE_INDEX", "NAS_ARCHIVE_ROOT"],
        "requiredMcpTools": ["archive.search", "archive.index_build"],
        "indexPathRecommendation": "BackupsXS/index.jsonl",
        "archiveRootRecommendation": "BackupsXS",
        "applyCollectedEvidenceCommand": (
            "powershell -NoProfile -ExecutionPolicy Bypass "
            "-File scripts\\awx_mcp_toolbox.ps1 -Tool archive.search"
        ),
        "nextActions": next_actions,
        "decision": "evidence_needed",
    }


def _safe_archive_index_details(report: dict[str, Any]) -> list[dict[str, Any]]:
    details = report.get("nextActionDetails")
    rows = details if isinstance(details, list) else []
    out: list[dict[str, Any]] = []
    for row in rows:
        if not isinstance(row, dict) or row.get("action") != "collect-archive-index-proof":
            continue
        index_path = _safe_relative_path(row.get("indexPathRecommendation")) or "BackupsXS/index.jsonl"
        archive_root = _safe_relative_path(row.get("archiveRootRecommendation")) or "BackupsXS"
        command = _safe_archive_apply_command(row.get("applyCollectedEvidenceCommand")) or (
            "powershell -NoProfile -ExecutionPolicy Bypass "
            "-File scripts\\awx_mcp_toolbox.ps1 -Tool archive.search"
        )
        safe = {
            "action": "collect-archive-index-proof",
            "nodeRole": "desktop",
            "targetService": "archive",
            "readOnly": row.get("readOnly") is not False,
            "mutationAllowed": row.get("mutationAllowed") is True,
            "requiredEnvNames": _safe_names(row.get("requiredEnvNames")) or ["ARCHIVE_INDEX", "NAS_ARCHIVE_ROOT"],
            "requiredMcpTools": _safe_tool_names(row.get("requiredMcpTools")) or ["archive.search"],
            "indexPathRecommendation": index_path,
            "archiveRootRecommendation": archive_root,
            "applyCollectedEvidenceCommand": command,
            "nextActions": _archive_action_names(row.get("nextActions")),
            "decision": str(row.get("decision") or "evidence_needed"),
        }
        if not safe["nextActions"]:
            safe["nextActions"] = _default_archive_index_detail(report)["nextActions"]
        out.append(safe)
    if out:
        return out

    evidence_text = " ".join(str(item) for item in report.get("evidence_needed", [])).lower()
    next_actions = _archive_action_names(report.get("nextActions"))
    if next_actions or "backupsxs" in evidence_text or "archive" in evidence_text:
        return [_default_archive_index_detail(report)]
    return []


def _safe_source_action_details(next_source_action: str) -> list[dict[str, Any]]:
    if next_source_action == NO_LOCAL_SOURCE_ACTION:
        return [
            {
                "action": "no-local-source-action",
                "scope": "external_evidence",
                "nodeRole": "desktop",
                "readOnly": True,
                "mutationAllowed": False,
                "evidenceNeeded": [
                    "SUPABASE_PROJECT_REF",
                    "read_only_supabase_mcp_or_cli_auth",
                    "execute_sql_results",
                    "get_advisors_results",
                ],
                "commands": [
                    '\'{"root":".","skip_mcp_network_probe":true}\' | python scripts\\awx_mcp_toolbox.py --input-json - supabase_context_probe',
                    "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\smoke_supabase_readonly_snapshot.ps1 -Root .",
                    "python scripts\\supabase_schema_snapshot_import.py --root . --input data\\db-gap-report\\supabase-query-results.json --advisors data\\db-gap-report\\supabase-advisors.json",
                ],
                "decision": "external_evidence_needed_no_local_source_patch",
            }
        ]
    if next_source_action == "rerun_db_gap_scanner":
        return [
            {
                "action": "rerun-db-gap-scanner",
                "scope": "local_evidence",
                "nodeRole": "desktop",
                "readOnly": True,
                "mutationAllowed": False,
                "commands": [
                    "python scripts\\db_gap_scanner.py --root . --output data\\db-gap-report --format json",
                    "python scripts\\source_health_scorecard.py --root . --output verification\\source-health-scorecard.json",
                    "python scripts\\awx_mcp_completion_audit.py --root . --output var\\codex-smoke\\awx-mcp-completion-audit-current.json",
                ],
                "decision": "db_gap_matrix_stale_or_missing_generated_at",
            }
        ]
    if next_source_action == "rerun_completion_audit":
        return [
            {
                "action": "rerun-completion-audit",
                "scope": "local_evidence",
                "nodeRole": "desktop",
                "readOnly": True,
                "mutationAllowed": False,
                "commands": [
                    "python scripts\\awx_mcp_completion_audit.py --root . --output var\\codex-smoke\\awx-mcp-completion-audit-control-tower-current.json",
                    "python scripts\\source_health_scorecard.py --root . --output verification\\source-health-scorecard.json",
                ],
                "decision": "completion_audit_stale_or_missing_generated_at",
            }
        ]
    if next_source_action == "source_runtime_proof_current":
        return [
            {
                "action": "source-runtime-proof-current",
                "sourceContract": True,
                "scope": "active_source",
                "nodeRole": "desktop",
                "readOnly": True,
                "mutationAllowed": False,
                "proofState": "current",
                "focusedTests": list(FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS),
                "commands": [
                    "python scripts\\source_health_scorecard.py --root . --output verification\\source-health-scorecard.json",
                    "python scripts\\awx_mcp_completion_audit.py --root .",
                    ".\\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene -x test --no-daemon --project-cache-dir <desktop-cache>",
                    "powershell -NoProfile -ExecutionPolicy Bypass -File .\\verify_boot.ps1 -TimeoutSeconds 45 -ServerPort 18080 -ManagementPort 18081 -NettyPort 18082 -LogPath logs\\verify_boot_source_runtime_current.log",
                ],
                "requiredTraceKeys": [
                    "requestId",
                    "traceId",
                    "sessionId",
                    "applicationReadyEventProven",
                    "runtimePrecheckProven",
                ],
                "requiredMarkers": [
                    "sourceContractProofPassed=true",
                    "broadRuntimeTestProofPassed=true",
                    "applicationReadyEventProven=True with probeMode=application-ready-event-proven means ApplicationReadyEvent listener evidence was observed before intentional stop",
                    "bootStartedProven=True with probeMode=boot-started-proven means Spring startup marker was observed before intentional stop",
                    "runtimePrecheckProven=True with probeMode=runtime-precheck-proven means Tomcat and WiringPrecheck completed before intentional stop",
                    "bootSuccessProven=False means blocker-scan-only, not boot success",
                ],
                "decision": "local_runtime_proof_current",
            }
        ]
    if next_source_action == "audit_top_broad_catches_for_redacted_breadcrumbs":
        return [
            {
                "action": "audit-broad-catches-redacted-breadcrumbs",
                "sourceContract": True,
                "scope": "active_source",
                "nodeRole": "desktop",
                "readOnly": True,
                "mutationAllowed": False,
                "targetFiles": [
                    "main/java/com/example/lms/service",
                    "main/java/com/example/lms/trace",
                    "main/java/ai/abandonware/nova/orch/aop",
                    "main/java/ai/abandonware/nova/boot",
                ],
                "focusedTests": [
                    "com.example.lms.service.ChatWorkflowTraceRedactionContractTest",
                    "com.example.lms.guard.RemainingMediumEmptyCatchContractTest",
                    "com.example.lms.trace.TraceUtilityFallbackBreadcrumbContractTest",
                    "ai.abandonware.nova.orch.aop.WebFailSoftTraceSuppressionsTest",
                ],
                "commands": [
                    ".\\gradlew.bat test --tests \"com.example.lms.service.ChatWorkflowTraceRedactionContractTest\" --no-daemon --project-cache-dir <desktop-cache>",
                    ".\\gradlew.bat test --tests \"com.example.lms.guard.RemainingMediumEmptyCatchContractTest\" --no-daemon --project-cache-dir <desktop-cache>",
                    ".\\gradlew.bat test --tests \"com.example.lms.trace.TraceUtilityFallbackBreadcrumbContractTest\" --no-daemon --project-cache-dir <desktop-cache>",
                    ".\\gradlew.bat test --tests \"ai.abandonware.nova.orch.aop.WebFailSoftTraceSuppressionsTest\" --no-daemon --project-cache-dir <desktop-cache>",
                ],
                "requiredTraceKeys": [
                    "failSoft.suppressed.stage",
                    "failSoft.suppressed.errorType",
                    "ctx.debugPort.suppressed.count",
                    "ctx.propagation.missing.count",
                    "reactor.onErrorDropped.count",
                ],
                "decision": "local_contract_ready",
            }
        ]
    if next_source_action != "continue_small_contract_tests_on_cross_subsystem_runtime_seams":
        return []
    return [
        {
            "action": "run-cross-subsystem-contract-tests",
            "scope": "active_source",
            "readOnly": True,
            "mutationAllowed": False,
            "targetFiles": [
                "main/java/com/example/lms/orchestration/StrategyConflictResolver.java",
                "main/java/com/example/lms/orchestration/ExecutionPlanApplier.java",
                "main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java",
                "main/java/ai/abandonware/nova/orch/aop/RagCompressionAspect.java",
                "main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java",
            ],
            "affectedSubsystems": [
                "S01_Overdrive",
                "S05_ExtremeZ",
                "S06_Hypernova",
                "S07_CIH",
                "S08_Adapter",
            ],
            "focusedTests": [
                "ai.abandonware.nova.orch.aop.AspectOrderingContractTest",
                "com.example.lms.orchestration.StrategyConflictResolverTest",
                "com.example.lms.orchestration.ExecutionPlanApplierTest",
                "com.example.lms.service.rag.burst.ExtremeZTriggerTest",
            ],
            "commands": [
                ".\\gradlew.bat crossSubsystemContractTest --no-daemon --project-cache-dir <desktop-cache>",
            ],
            "requiredTraceKeys": [
                "boosterMode.active",
                "boosterMode.excludedModes",
                "specialMode.conflict.suppressed",
                "routing.executionPlan.applied.primaryMode",
                "extremeZ.bypassReason",
            ],
            "decision": "local_contract_ready",
        }
    ]


def _component(
    *,
    component_id: str,
    label: str,
    weight: float,
    normalized: float,
    confidence: float,
    evidence: str,
    formula: str,
) -> dict[str, Any]:
    normalized = _round4(_clamp(normalized))
    confidence = _round4(_clamp(confidence))
    weighted_points = _round4(weight * normalized * confidence * 100.0)
    return {
        "id": component_id,
        "label": label,
        "weight": weight,
        "normalized": normalized,
        "confidence": confidence,
        "weightedPoints": weighted_points,
        "evidence": evidence,
        "formula": formula,
    }


def _severity(risk_score: float) -> str:
    if risk_score >= 0.66:
        return "high"
    if risk_score >= 0.33:
        return "medium"
    return "low"


def _risk_status(scope: str, risk_score: float) -> tuple[str, str]:
    if risk_score <= 0.0:
        return (
            "monitored",
            "riskScore=0.0; current evidence keeps this risk in the monitor-only lane",
        )
    if scope != "active_source":
        return (
            "evidence_needed",
            "external or project-scoped proof is required before this risk can be cleared",
        )
    return (
        "action_required",
        "active-source risk remains above zero and needs a focused proof or patch",
    )


def _risk(
    *,
    risk_id: str,
    label: str,
    risk_score: float,
    evidence: str,
    next_action: str,
    scope: str = "active_source",
    status: str | None = None,
    status_reason: str | None = None,
) -> dict[str, Any]:
    risk_score = _round4(_clamp(risk_score))
    if status is None:
        status, status_reason = _risk_status(scope, risk_score)
    elif not status_reason:
        status_reason = "status was assigned by a focused proof gate"
    return {
        "id": risk_id,
        "label": label,
        "scope": scope,
        "riskScore": risk_score,
        "severity": _severity(risk_score),
        "status": status,
        "statusReason": status_reason,
        "evidence": evidence,
        "nextAction": next_action,
    }


FAILURE_PATTERN_CATALOG = {
    "aspect_order_hotspots": {
        "failurePatternKind": "aspect_order_hotspot",
        "patternId": "FP-AOP-ORDER-HOTSPOT",
        "traceStoreKeys": [
            "sourceHealth.failurePatternKind",
            "sourceHealth.patternId",
            "aspect.order.contract",
            "harmony.score.overall",
        ],
    },
    "cross_subsystem_concentration": {
        "failurePatternKind": "cross_subsystem_concentration",
        "patternId": "FP-S01S08-CROSS-CONCENTRATION",
        "traceStoreKeys": [
            "sourceHealth.failurePatternKind",
            "sourceHealth.patternId",
            "harmony.score.S01_S05",
            "harmony.score.S05_S06",
            "harmony.score.S03_CFVM",
            "harmony.score.overall",
        ],
    },
    "silent_swallow_pressure": {
        "failurePatternKind": "silent_swallow_pressure",
        "patternId": "FP-TRACE-SILENT-SWALLOW",
        "traceStoreKeys": [
            "sourceHealth.failurePatternKind",
            "sourceHealth.patternId",
            "failSoft.suppressed.stage",
            "failSoft.suppressed.errorType",
            "harmony.score.overall",
        ],
    },
    "test_tree_contamination": {
        "failurePatternKind": "test_tree_contamination",
        "patternId": "FP-TEST-TREE-CONTAMINATION",
        "traceStoreKeys": [
            "sourceHealth.failurePatternKind",
            "sourceHealth.patternId",
            "testTree.missingImportCount",
            "testTree.affectedFileCount",
            "harmony.score.overall",
        ],
    },
    "supabase_live_proof_missing": {
        "failurePatternKind": "external_supabase_evidence_gap",
        "patternId": "FP-EXT-SUPABASE-LIVE-PROOF",
        "traceStoreKeys": [
            "sourceHealth.failurePatternKind",
            "sourceHealth.patternId",
            "supabase.projectScopeStatus",
            "supabase.evidenceNeededCount",
            "harmony.score.overall",
        ],
    },
}

AMPLIFIER_TRACE_KEYS = [
    "hypernova.twpmP",
    "hypernova.cvarPhi",
    "hypernova.riskKAlloc",
    "hypernova.clampApplied",
    "sourceHealth.amplifiedSignalScore",
]

REQUIRED_EVIDENCE_SINKS = [
    "TraceStore",
    "DebugEventStore",
    "CFVM Failure Pattern",
]


def _ordered_unique(values: Any) -> list[str]:
    out: list[str] = []
    for item in values or []:
        text = str(item).strip()
        if text and text not in out:
            out.append(text)
    return out


def _trace_keys_with_amplifiers(trace_keys: Any) -> list[str]:
    return _ordered_unique(list(trace_keys or []) + list(AMPLIFIER_TRACE_KEYS))


def _required_evidence_sinks() -> list[str]:
    return list(REQUIRED_EVIDENCE_SINKS)


def _debug_event_store_contract() -> dict[str, Any]:
    return {
        "sink": "DebugEventStore",
        "eventType": "source_health.failure_pattern_prediction",
        "ndjsonPath": DEFAULT_FAILURE_PATTERN_DEBUG_EVENT_NDJSON.as_posix(),
        "rawPayloadStored": False,
    }


def _cfvm_failure_pattern_contract(prediction: dict[str, Any]) -> dict[str, Any]:
    return {
        "sink": "CFVM Failure Pattern",
        "failurePatternKind": str(prediction.get("failurePatternKind") or "unknown_source_health_risk"),
        "patternId": str(prediction.get("patternId") or "FP-SOURCE-HEALTH-UNKNOWN"),
        "source": "sourceHealthScorecard",
        "mutationAllowed": False,
        "rawPayloadStored": False,
    }


def _autonomous_validation_contract() -> dict[str, Any]:
    return {
        "schema": "source_health.autonomous_validation_contract.v1",
        "timeboxKind": "agent_safe_patch_budget",
        "maxDurationHours": 9,
        "runtimeProductBehavior": False,
        "desktopRole": "canonical_final_verifier",
        "producerNodeRoles": ["macmini", "notebook"],
        "requiredGates": [
            "sourceHealthScorecard",
            "harmonyPressureReport",
            "checkLangchain4jVersionPurity",
            "checkSourceSetHygiene",
            "compileJava",
            "desktop_final_verification",
        ],
        "stopConditions": [
            "all_p0_hard_gates_pass",
            "elapsed_hours_gte_9",
            "secret_leak_risk",
            "source_ownership_conflict",
            "patchdrop_manifest_missing",
        ],
        "cycleArtifacts": [
            "riskLedger",
            "componentScores",
            "TraceStore keys",
            "DebugEvent NDJSON",
            "PatchDrop manifest",
        ],
        "requiredEvidenceSinks": _required_evidence_sinks(),
    }


STATUS_AMPLIFICATION_WEIGHTS = {
    "action_required": 1.0,
    "evidence_needed": 0.82,
    "guarded": 0.35,
    "monitored": 0.12,
}

SEVERITY_AMPLIFICATION_WEIGHTS = {
    "high": 1.0,
    "medium": 0.7,
    "low": 0.4,
}


def _pattern_catalog_entry(risk_id: Any) -> dict[str, Any]:
    return FAILURE_PATTERN_CATALOG.get(
        str(risk_id or ""),
        {
            "failurePatternKind": "unknown_source_health_risk",
            "patternId": "FP-SOURCE-HEALTH-UNKNOWN",
            "traceStoreKeys": [
                "sourceHealth.failurePatternKind",
                "sourceHealth.patternId",
                "harmony.score.overall",
            ],
        },
    )


def _risk_float(value: Any) -> float:
    try:
        return float(value)
    except Exception:
        return 0.0


def _amplified_signal_score(risk: dict[str, Any]) -> float:
    risk_score = _clamp(_risk_float(risk.get("riskScore")))
    status_weight = STATUS_AMPLIFICATION_WEIGHTS.get(str(risk.get("status") or ""), 0.0)
    severity_weight = SEVERITY_AMPLIFICATION_WEIGHTS.get(str(risk.get("severity") or ""), 0.0)
    # Offline source-health analogue to TWPM/CVaR/Risk-K: emphasize high-tail
    # risk, then add bounded status and severity pressure. It is a gate score,
    # not a runtime HYPERNOVA answer score.
    return _round4(
        _clamp(
            (risk_score ** 0.75) * 0.58
            + status_weight * 0.27
            + severity_weight * 0.15
        )
    )


def _failure_pattern_candidate(risk: dict[str, Any]) -> dict[str, Any]:
    catalog = _pattern_catalog_entry(risk.get("id"))
    return {
        "failurePatternKind": catalog["failurePatternKind"],
        "patternId": catalog["patternId"],
        "sourceRiskId": str(risk.get("id") or ""),
        "sourceStatus": str(risk.get("status") or ""),
        "sourceScope": str(risk.get("scope") or ""),
        "sourceSeverity": str(risk.get("severity") or ""),
        "riskScore": _round4(_clamp(_risk_float(risk.get("riskScore")))),
        "amplifiedSignalScore": _amplified_signal_score(risk),
        "evidenceHash": _stable_hash(risk.get("evidence") or ""),
        "nextAction": str(risk.get("nextAction") or "")[:240],
    }


def _component_score_inputs(component_scores: list[dict[str, Any]]) -> list[dict[str, Any]]:
    inputs: list[dict[str, Any]] = []
    for component in component_scores:
        if not isinstance(component, dict):
            continue
        component_id = _safe_label(component.get("id"))
        if not component_id:
            continue
        normalized = _round4(_clamp(_risk_float(component.get("normalized"))))
        weighted_points = _round4(max(0.0, _risk_float(component.get("weightedPoints"))))
        inputs.append(
            {
                "componentId": component_id,
                "normalized": normalized,
                "confidence": _round4(_clamp(_risk_float(component.get("confidence")))),
                "weightedPoints": weighted_points,
                "pressureScore": _round4(_clamp(1.0 - normalized)),
            }
        )
    inputs.sort(
        key=lambda row: (
            row["pressureScore"],
            row["weightedPoints"],
            row["componentId"],
        ),
        reverse=True,
    )
    return inputs[:8]


def _producer_validation_queue(
    candidates: list[dict[str, Any]],
    component_scores: list[dict[str, Any]],
) -> dict[str, Any]:
    validation_contract = _autonomous_validation_contract()
    component_inputs = _component_score_inputs(component_scores)
    external_component_ids = [
        row["componentId"]
        for row in component_inputs
        if row["componentId"] in EXTERNAL_EVIDENCE_ONLY_COMPONENT_IDS
    ]
    component_refs = [
        row["componentId"]
        for row in component_inputs
        if row["componentId"] not in EXTERNAL_EVIDENCE_ONLY_COMPONENT_IDS
    ]
    roles = ["macmini", "notebook"]
    assignments: list[dict[str, Any]] = []
    for index, candidate in enumerate(candidates[:5]):
        trace_keys = _trace_keys_with_amplifiers(
            _pattern_catalog_entry(candidate.get("sourceRiskId"))["traceStoreKeys"]
        )
        risk_score = _round4(_clamp(_risk_float(candidate.get("riskScore"))))
        amplified_score = _round4(max(risk_score, _clamp(_risk_float(candidate.get("amplifiedSignalScore")))))
        assignments.append(
            {
                "queueRank": index + 1,
                "producerRole": roles[index % len(roles)],
                "sourceRootKind": "local-worktree",
                "directCanonicalSourceEdit": False,
                "evidenceOnly": True,
                "sourceRiskId": str(candidate.get("sourceRiskId") or ""),
                "failurePatternKind": str(candidate.get("failurePatternKind") or "unknown_source_health_risk"),
                "patternId": str(candidate.get("patternId") or "FP-SOURCE-HEALTH-UNKNOWN"),
                "riskScore": risk_score,
                "amplifiedSignalScore": amplified_score,
                "componentScoreRefs": component_refs,
                "requiredGates": validation_contract["requiredGates"],
                "requiredEvidenceArtifacts": validation_contract["cycleArtifacts"],
                "requiredEvidenceSinks": validation_contract["requiredEvidenceSinks"],
                "requiredTraceStoreKeys": trace_keys,
                "amplifierTraceKeys": list(AMPLIFIER_TRACE_KEYS),
                "debugEventNdjsonPath": DEFAULT_FAILURE_PATTERN_DEBUG_EVENT_NDJSON.as_posix(),
                "patchDropManifestPath": DEFAULT_FAILURE_PATTERN_PATCHDROP_MANIFEST.as_posix(),
                "stopConditions": validation_contract["stopConditions"],
            }
        )
    return {
        "schema": "producer_validation_queue.v1",
        "method": "riskLedger_componentScores_offline_twpm_cvar_riskk",
        "maxDurationHours": validation_contract["maxDurationHours"],
        "runtimeProductBehavior": False,
        "componentScoreInputs": component_inputs,
        "externalEvidenceOnlyComponentIds": external_component_ids,
        "assignments": assignments,
    }


def _failure_pattern_prediction(
    risk_ledger: list[dict[str, Any]],
    component_scores: list[dict[str, Any]],
) -> dict[str, Any]:
    candidates = [_failure_pattern_candidate(row) for row in risk_ledger if isinstance(row, dict)]
    candidates.sort(
        key=lambda row: (
            row["amplifiedSignalScore"],
            row["riskScore"],
            row["sourceRiskId"],
        ),
        reverse=True,
    )
    nearest = candidates[0] if candidates else _failure_pattern_candidate(
        {
            "id": "unknown",
            "status": "evidence_needed",
            "scope": "active_source",
            "severity": "low",
            "riskScore": 0.0,
            "evidence": "riskLedger empty",
            "nextAction": "rerun sourceHealthScorecard with current metrics",
        }
    )
    catalog = _pattern_catalog_entry(nearest["sourceRiskId"])
    trace_store_keys = _trace_keys_with_amplifiers(catalog["traceStoreKeys"])
    return {
        "schema": "failure_pattern_prediction.v1",
        "failurePatternKind": nearest["failurePatternKind"],
        "patternId": nearest["patternId"],
        "sourceRiskId": nearest["sourceRiskId"],
        "sourceStatus": nearest["sourceStatus"],
        "sourceScope": nearest["sourceScope"],
        "sourceSeverity": nearest["sourceSeverity"],
        "riskScore": nearest["riskScore"],
        "amplifiedSignalScore": nearest["amplifiedSignalScore"],
        "amplifier": {
            "method": "offline_source_health_twpm_cvar_riskk_gate",
            "runtimeScoreClaim": False,
            "description": "Amplifies repeated high-tail source-health risks for routing and PatchDrop evidence, without claiming runtime HYPERNOVA provider success.",
        },
        "autonomousValidationContract": _autonomous_validation_contract(),
        "requiredEvidenceSinks": _required_evidence_sinks(),
        "traceStoreKeys": trace_store_keys,
        "amplifierTraceKeys": list(AMPLIFIER_TRACE_KEYS),
        "debugEventNdjsonContract": {
            "sink": "DebugEventStore",
            "eventType": "source_health.failure_pattern_prediction",
            "requiredFields": [
                "generatedAt",
                "failurePatternKind",
                "patternId",
                "sourceRiskId",
                "amplifiedSignalScore",
                "traceStoreKeys",
                "patchDropManifestHash",
            ],
            "forbiddenFields": [
                "rawPrompt",
                "rawQuery",
                "apiKey",
                "Authorization",
                "Cookie",
                "absolutePath",
            ],
        },
        "cfvmFailurePatternContract": _cfvm_failure_pattern_contract(nearest),
        "patchDropManifestContract": {
            "schema": "patchdrop.producer_manifest.failure_pattern.v1",
            "producerRoles": ["macmini", "notebook"],
            "requiredSidecars": [
                ".patch",
                ".report.md",
                ".verify.log",
                ".sha256.txt",
                ".manifest.json",
                "pendingNotice",
            ],
            "requiredFields": [
                "sourceIsolation.guard",
                "sourceRootKind",
                "directCanonicalSourceEdit",
                "desktopFinalProof",
                "failurePatternKind",
                "patternId",
                "traceStoreKeys",
                "debugEventNdjsonPath",
                "requiredEvidenceSinks",
                "cfvmFailurePatternContract",
            ],
            "producerNodeContracts": _producer_node_contracts(
                ["macmini", "notebook"],
                trace_store_keys,
                [
                    ".patch",
                    ".report.md",
                    ".verify.log",
                    ".sha256.txt",
                    ".manifest.json",
                    "pendingNotice",
                ],
            ),
            "desktopFinalProof": "evidence_needed_until_desktop_verification",
            "directCanonicalSourceEdit": False,
            "mutationAllowed": False,
        },
        "candidatePatterns": candidates[:5],
        "producerValidationQueue": _producer_validation_queue(candidates, component_scores),
    }


def _json_content_hash(data: Any) -> str:
    return hashlib.sha256(
        json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def _json_artifact_text(data: Any) -> str:
    return json.dumps(data, ensure_ascii=False, indent=2) + "\n"


def _json_artifact_hash(data: Any) -> str:
    return hashlib.sha256(_json_artifact_text(data).encode("utf-8")).hexdigest()


def _failure_pattern_patchdrop_manifest(generated_at: str, prediction: dict[str, Any]) -> dict[str, Any]:
    contract = prediction.get("patchDropManifestContract")
    if not isinstance(contract, dict):
        contract = {}
    producer_roles = [role for role in contract.get("producerRoles", []) if role in {"macmini", "notebook"}]
    trace_store_keys = _ordered_unique(
        [str(key) for key in prediction.get("traceStoreKeys", [])]
        + [str(key) for key in prediction.get("amplifierTraceKeys", [])]
    )
    required_sidecars = _safe_patchdrop_sidecars(contract.get("requiredSidecars"))
    return {
        "schema": str(contract.get("schema") or "patchdrop.producer_manifest.failure_pattern.v1"),
        "generatedAt": generated_at,
        "decision": "source_health_failure_pattern_manifest_contract",
        "producerRoles": producer_roles,
        "producerNodeContracts": _producer_node_contracts(
            producer_roles,
            trace_store_keys,
            required_sidecars,
        ),
        "sourceIsolation": {
            "guard": "PASS_REQUIRED",
            "sourceRootKind": "local-worktree",
            "directCanonicalSourceEdit": False,
            "desktopFinalProof": "evidence_needed_until_desktop_verification",
        },
        "mutationAllowed": False,
        "producerExecutionObserved": False,
        "desktopFinalProof": "evidence_needed_until_desktop_verification",
        "failurePatternKind": str(prediction.get("failurePatternKind") or "unknown_source_health_risk"),
        "patternId": str(prediction.get("patternId") or "FP-SOURCE-HEALTH-UNKNOWN"),
        "sourceRiskId": str(prediction.get("sourceRiskId") or "unknown"),
        "riskScore": _round4(_clamp(_risk_float(prediction.get("riskScore")))),
        "amplifiedSignalScore": _round4(_clamp(_risk_float(prediction.get("amplifiedSignalScore")))),
        "requiredEvidenceSinks": _required_evidence_sinks(),
        "traceStoreKeys": trace_store_keys,
        "amplifierTraceKeys": [str(key) for key in prediction.get("amplifierTraceKeys", [])],
        "debugEventStoreContract": _debug_event_store_contract(),
        "cfvmFailurePatternContract": _cfvm_failure_pattern_contract(prediction),
        "autonomousValidationContract": _autonomous_validation_contract(),
        "producerValidationQueue": (
            prediction.get("producerValidationQueue")
            if isinstance(prediction.get("producerValidationQueue"), dict)
            else _producer_validation_queue([], [])
        ),
        "debugEventNdjsonPath": DEFAULT_FAILURE_PATTERN_DEBUG_EVENT_NDJSON.as_posix(),
        "requiredSidecars": required_sidecars,
        "requiredFields": [
            field for field in contract.get("requiredFields", [])
            if isinstance(field, str) and field.replace(".", "").replace("_", "").isalnum()
        ],
        "runtimeScoreClaim": False,
        "rawSecretPatternHits": 0,
    }


def _failure_pattern_debug_event(generated_at: str, prediction: dict[str, Any], manifest_hash: str) -> dict[str, Any]:
    return {
        "schema": "debug_event.ndjson.source_health.v1",
        "eventType": "source_health.failure_pattern_prediction",
        "generatedAt": generated_at,
        "failurePatternKind": str(prediction.get("failurePatternKind") or "unknown_source_health_risk"),
        "patternId": str(prediction.get("patternId") or "FP-SOURCE-HEALTH-UNKNOWN"),
        "sourceRiskId": str(prediction.get("sourceRiskId") or "unknown"),
        "riskScore": _round4(_clamp(_risk_float(prediction.get("riskScore")))),
        "amplifiedSignalScore": _round4(_clamp(_risk_float(prediction.get("amplifiedSignalScore")))),
        "requiredEvidenceSinks": _required_evidence_sinks(),
        "traceStoreKeys": _ordered_unique(
            [str(key) for key in prediction.get("traceStoreKeys", [])]
            + [str(key) for key in prediction.get("amplifierTraceKeys", [])]
        ),
        "amplifierTraceKeys": [str(key) for key in prediction.get("amplifierTraceKeys", [])],
        "debugEventStoreContract": _debug_event_store_contract(),
        "cfvmFailurePatternContract": _cfvm_failure_pattern_contract(prediction),
        "autonomousValidationContract": _autonomous_validation_contract(),
        "patchDropManifestHash": manifest_hash,
        "patchDropManifestPath": DEFAULT_FAILURE_PATTERN_PATCHDROP_MANIFEST.as_posix(),
        "runtimeScoreClaim": False,
        "producerExecutionObserved": False,
        "desktopFinalProof": "evidence_needed_until_desktop_verification",
    }


def _failure_pattern_evidence_artifacts(generated_at: str, prediction: dict[str, Any]) -> dict[str, Any]:
    manifest = _failure_pattern_patchdrop_manifest(generated_at, prediction)
    manifest_hash = _json_artifact_hash(manifest)
    return {
        "schema": "failure_pattern_evidence_artifacts.v1",
        "debugEventNdjsonPath": DEFAULT_FAILURE_PATTERN_DEBUG_EVENT_NDJSON.as_posix(),
        "patchDropManifestPath": DEFAULT_FAILURE_PATTERN_PATCHDROP_MANIFEST.as_posix(),
        "patchDropManifestHash": manifest_hash,
        "eventType": "source_health.failure_pattern_prediction",
        "requiredEvidenceSinks": _required_evidence_sinks(),
        "debugEventStoreContract": _debug_event_store_contract(),
        "cfvmFailurePatternContract": _cfvm_failure_pattern_contract(prediction),
        "producerExecutionObserved": False,
        "runtimeScoreClaim": False,
        "autonomousValidationContract": _autonomous_validation_contract(),
    }


def _write_failure_pattern_evidence_artifacts(root: Path, scorecard: dict[str, Any]) -> None:
    generated_at = str(scorecard.get("generatedAt") or "")
    prediction = scorecard.get("failurePatternPrediction")
    if not isinstance(prediction, dict):
        return
    manifest = _failure_pattern_patchdrop_manifest(generated_at, prediction)
    manifest_hash = _json_artifact_hash(manifest)
    event = _failure_pattern_debug_event(generated_at, prediction, manifest_hash)
    manifest_path = root / DEFAULT_FAILURE_PATTERN_PATCHDROP_MANIFEST
    event_path = root / DEFAULT_FAILURE_PATTERN_DEBUG_EVENT_NDJSON
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    event_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(_json_artifact_text(manifest), encoding="utf-8", newline="\n")
    event_path.write_text(
        json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def build_scorecard(root: Path) -> dict[str, Any]:
    root = root.resolve()
    quant, quant_input_freshness = _canonical_json_input(root, DEFAULT_QUANT_METRICS)
    harmony, harmony_input_freshness = _canonical_json_input(root, DEFAULT_HARMONY_METRICS)
    test_tree, test_tree_input_freshness = _canonical_json_input(root, DEFAULT_TEST_TREE_METRICS)
    websoak_provider, websoak_input_freshness = _canonical_json_input(
        root,
        DEFAULT_WEBSOAK_PROVIDER_SMOKE,
    )
    input_freshness = {
        "quantMetrics": quant_input_freshness,
        "harmonyPressure": harmony_input_freshness,
        "testTreeContamination": test_tree_input_freshness,
        "websoakProviderSmoke": websoak_input_freshness,
    }
    quant_input_current = bool(quant_input_freshness["fresh"])
    harmony_input_current = bool(harmony_input_freshness["fresh"])
    test_tree_input_current = bool(test_tree_input_freshness["fresh"])
    websoak_input_current = bool(websoak_input_freshness["fresh"])
    db_gap_matrix = _read_json(root, DEFAULT_DB_GAP_MATRIX)
    (
        db_gap_matrix_has_generated_at,
        db_gap_matrix_fresh,
        db_gap_matrix_age_seconds,
        db_gap_matrix_freshness_status,
    ) = _artifact_freshness(
        db_gap_matrix.get("generatedAt") if db_gap_matrix else "",
        DB_GAP_MATRIX_MAX_AGE_SECONDS,
    )
    db_gap_matrix_current = (
        bool(db_gap_matrix)
        and db_gap_matrix_has_generated_at
        and db_gap_matrix_fresh
        and db_gap_matrix_freshness_status == "current"
    )
    completion_audit, completion_audit_path = _read_latest_completion_audit(root)
    (
        completion_audit_has_generated_at,
        completion_audit_fresh,
        completion_audit_age_seconds,
        completion_audit_freshness_status,
    ) = _artifact_freshness(
        completion_audit.get("generatedAt") if completion_audit_path else "",
        COMPLETION_AUDIT_MAX_AGE_SECONDS,
    )
    completion_audit_current = (
        bool(completion_audit_path)
        and completion_audit_has_generated_at
        and completion_audit_fresh
        and completion_audit_freshness_status == "current"
    )
    completion_audit_hard_failures = completion_audit.get("hardFailures")
    completion_audit_local_ready = (
        completion_audit_current
        and completion_audit.get("ok") is True
        and _safe_label(completion_audit.get("status")) == "local_control_tower_ready"
        and isinstance(completion_audit_hard_failures, list)
        and not completion_audit_hard_failures
    )
    completion_audit_supabase_live_proof_required = completion_audit.get(
        "supabaseLiveProofRequired"
    )
    if not isinstance(completion_audit_supabase_live_proof_required, bool):
        completion_audit_supabase_live_proof_required = None
    completion_audit_desktop_only_ready = (
        completion_audit_local_ready
        and completion_audit_supabase_live_proof_required is False
    )
    completion_audit_supporting_action_mode = _safe_label(
        completion_audit.get("supportingEvidenceActionMode")
    )
    try:
        completion_audit_supporting_actions_omitted = int(
            completion_audit.get("supportingEvidenceNextActionsOmitted", 0) or 0
        )
    except Exception:
        completion_audit_supporting_actions_omitted = 0
    completion_audit_supporting_action_hint = str(
        completion_audit.get("supportingEvidenceActionHint") or ""
    )
    completion_audit_supporting_needed_detail_mode = _safe_label(
        completion_audit.get("supportingEvidenceNeededDetailMode")
    )
    try:
        completion_audit_supporting_needed_details_omitted = int(
            completion_audit.get("supportingEvidenceNeededDetailsOmitted", 0) or 0
        )
    except Exception:
        completion_audit_supporting_needed_details_omitted = 0
    completion_audit_supporting_needed_detail_hint = str(
        completion_audit.get("supportingEvidenceNeededDetailHint") or ""
    )
    completion_audit_requirement_needed_detail = (
        _completion_audit_requirement_evidence_detail_summary(completion_audit)
    )
    source_runtime_boot_proof = _verify_boot_runtime_proof(root)
    source_runtime_boot_passed = bool(source_runtime_boot_proof.get("passed"))
    goal_next_status_proof = _goal_next_status_proof_summary(root)
    next_action_details = (
        _safe_supabase_live_proof_details(completion_audit)
        + _safe_external_evidence_details(completion_audit)
        + _safe_archive_index_details(completion_audit)
    )

    harmony_summary = quant.get("harmonyPressureSummary", {})
    if harmony:
        harmony_summary = {**harmony_summary, **harmony}
    test_summary = quant.get("testTreeContamination", {})
    if test_tree:
        test_summary = {**test_summary, **test_tree}
    supabase = quant.get("supabaseReadonlySmoke", {})
    provider = quant.get("runtimeProviderDisabledSmoke", {})
    websoak_summary = websoak_provider.get("summary", {}) if isinstance(websoak_provider, dict) else {}
    if isinstance(websoak_summary, dict) and websoak_summary:
        provider = {**provider, **websoak_summary}

    duplicate_fqcn = int(quant.get("duplicateFqcnActiveCount", 0) or 0)
    secret_hits = int(quant.get("secretPatternHitCount", 0) or 0)
    source_integrity = 1.0 if duplicate_fqcn == 0 and secret_hits == 0 else 0.0
    source_integrity_evidence = (
        f"duplicateFqcnActiveCount={duplicate_fqcn}; secretPatternHitCount={secret_hits}"
    )
    if not quant_input_current:
        source_integrity = 0.0
        source_integrity_evidence = (
            "reason=metric-input-invalid; input=quantMetrics; "
            f"status={quant_input_freshness['status']}"
        )

    provider_ok = (
        int(provider.get("status", 0) or 0) == 200
        and bool(provider.get("providerDisabledOrSkipped", False))
        and int(provider.get("secretPatternHits", 0) or 0) == 0
        and int(provider.get("rawQueryHits", 0) or 0) == 0
    )
    provider_health = 0.93 if provider_ok else 0.45
    provider_inputs_current = quant_input_current and websoak_input_current
    provider_evidence = (
        f"status={provider.get('status')}; "
        f"providerDisabledOrSkipped={provider.get('providerDisabledOrSkipped')}; "
        f"rawQueryHits={provider.get('rawQueryHits')}"
    )
    if not provider_inputs_current:
        provider_health = 0.0
        provider_evidence = (
            "reason=metric-input-invalid; inputs=quantMetrics,websoakProviderSmoke; "
            f"quantStatus={quant_input_freshness['status']}; "
            f"websoakStatus={websoak_input_freshness['status']}"
        )

    aspect_files = int(harmony_summary.get("aspectFiles", 0) or 0)
    critical_unordered = int(harmony_summary.get("criticalUnorderedAspectCount", 0) or 0)
    cross_large_total = int(harmony_summary.get("crossSubsystemLargeFilesOver1000", 0) or 0)
    cross_large = int(
        harmony_summary.get("runtimeCrossSubsystemLargeFilesOver1000", cross_large_total)
        or 0
    )
    broad_catch_ratio = float(harmony_summary.get("broadCatchWithoutLocalBreadcrumbRatio", 0.0) or 0.0)
    aspect_order_coverage = float(harmony_summary.get("aspectOrderCoverageApprox", 0.0) or 0.0)
    critical_aspect_ratio = critical_unordered / aspect_files if aspect_files else 0.0
    harmony_pressure_risk = _clamp(
        (min(1.0, cross_large / 50.0) * 0.30)
        + (broad_catch_ratio * 0.25)
        + ((1.0 - aspect_order_coverage) * 0.25)
        + (critical_aspect_ratio * 0.20)
    )
    harmony_health = 1.0 - (harmony_pressure_risk * 0.50)
    harmony_evidence = (
        f"runtimeCrossSubsystemLargeFilesOver1000={cross_large}; "
        f"crossSubsystemLargeFilesOver1000={cross_large_total}; "
        f"broadCatchWithoutLocalBreadcrumbRatio={broad_catch_ratio}; "
        f"aspectOrderCoverageApprox={aspect_order_coverage}; "
        f"criticalUnorderedAspectCount={critical_unordered}"
    )
    if not (quant_input_current and harmony_input_current):
        harmony_health = 0.0
        harmony_evidence = (
            "reason=metric-input-invalid; inputs=quantMetrics,harmonyPressure; "
            f"quantStatus={quant_input_freshness['status']}; "
            f"harmonyStatus={harmony_input_freshness['status']}"
        )

    test_risk = float(test_summary.get("riskScore", 0.0) or 0.0)
    test_health = 1.0 - _clamp(test_risk)
    test_evidence = (
        f"missingImportCount={test_summary.get('missingImportCount')}; "
        f"affectedTestFileCount={test_summary.get('affectedTestFileCount')}; "
        f"riskScore={test_summary.get('riskScore')}"
    )
    if not (quant_input_current and test_tree_input_current):
        test_health = 0.0
        test_evidence = (
            "reason=metric-input-invalid; inputs=quantMetrics,testTreeContamination; "
            f"quantStatus={quant_input_freshness['status']}; "
            f"testTreeStatus={test_tree_input_freshness['status']}"
        )

    supabase_read_only_safe = (
        bool(supabase.get("readOnlyMode", False))
        and not bool(supabase.get("mutationAllowed", True))
        and int(supabase.get("highConfidenceSecretHits", 0) or 0) == 0
    )
    supabase_project_status = str(supabase.get("projectScopeStatus", "missing"))
    if (
        supabase_project_status == "missing"
        and supabase.get("status") == "evidence_needed"
        and supabase.get("reason") == "project-scoped-readonly-evidence-not-declared"
    ):
        supabase_project_status = "project_ref_missing"
    supabase_snapshot_import = (
        db_gap_matrix.get("external_supabase_snapshot", {}).get("snapshotImport", {})
        if isinstance(db_gap_matrix, dict)
        else {}
    )
    supabase_missing_names = _safe_names(supabase.get("missingResultNames"))
    if not supabase_missing_names:
        supabase_missing_names = _safe_names(supabase_snapshot_import.get("missingResultNames"))
    supabase_data_api_missing_names = [
        name for name in supabase_missing_names
        if name in SUPABASE_DATA_API_EVIDENCE_NAMES
    ]
    supabase_shadow_memory_missing_names = [
        name for name in supabase_missing_names
        if name in SUPABASE_SHADOW_MEMORY_EVIDENCE_NAMES
    ]
    supabase_external_required_missing_names = (
        supabase_data_api_missing_names + supabase_shadow_memory_missing_names
    )
    supabase_data_api_result_sets_missing = bool(supabase_external_required_missing_names)
    supabase_smoke_ok, supabase_smoke_evidence = _checked_evidence(
        completion_audit,
        "supabase.readonly-snapshot-smoke",
    )
    supabase_local_smoke_contract_ready = (
        completion_audit_current
        and supabase_smoke_ok
        and supabase_smoke_evidence.get("reportsMissingResultNames") == "True"
        and supabase_smoke_evidence.get("reportsDataApiEvidenceMissing") == "True"
        and supabase_smoke_evidence.get("reportsEnvPreflight") == "True"
        and supabase_smoke_evidence.get("summaryArtifact") == "True"
        and supabase_smoke_evidence.get("summaryGeneratedAt") == "True"
        and supabase_smoke_evidence.get("summaryFresh") == "True"
        and supabase_smoke_evidence.get("summaryFreshnessStatus") == "current"
        and _evidence_int(supabase_smoke_evidence, "docsRefCount") >= 2
        and _evidence_int(supabase_smoke_evidence, "securityContractCount") >= 3
        and supabase_smoke_evidence.get("cliQueryMinVersion") == ">=2.79.0"
        and supabase_smoke_evidence.get("cliAdvisorsMinVersion") == ">=2.81.3"
        and supabase_smoke_evidence.get("mcpFallbackContract") == "True"
        and supabase_smoke_evidence.get("dataApiGrantProofRequired") == "True"
        and supabase_smoke_evidence.get("rlsPolicyProofRequired") == "True"
        and supabase_smoke_evidence.get("secretKeysBackendOnly") == "True"
        and supabase_smoke_evidence.get("rawSecretPatternHits") == "0"
        and supabase_smoke_evidence.get("summarySecretHits") == "0"
        and supabase_smoke_evidence.get("summaryRawSecretPatternHits") == "0"
        and supabase_smoke_evidence.get("summaryHighConfidenceSecretHits") == "0"
        and supabase_smoke_evidence.get("summaryRawJdbcUrlHits") == "0"
        and "envPresentCount" in supabase_smoke_evidence
        and "projectRefEnvPresent" in supabase_smoke_evidence
        and "accessTokenEnvPresent" in supabase_smoke_evidence
        and "cliPresent" in supabase_smoke_evidence
        and "contextEvidenceNeededCount" in supabase_smoke_evidence
    )
    supabase_smoke_contract_action_required = (
        not supabase_local_smoke_contract_ready
        and not completion_audit_desktop_only_ready
    )
    computer_use_ok, computer_use_evidence = _checked_evidence(
        completion_audit,
        "computer-use.gui-proof-boundary",
    )
    computer_use_live_ready = (
        completion_audit_current
        and computer_use_ok
        and computer_use_evidence.get("ready", "True") == "True"
        and computer_use_evidence.get("promptPack") == "True"
        and computer_use_evidence.get("guiOnly") == "True"
        and computer_use_evidence.get("noTerminalAutomation") == "True"
        and computer_use_evidence.get("supportingOnly") == "True"
        and computer_use_evidence.get("helperArtifact") == "True"
        and computer_use_evidence.get("helperReachable") == "True"
        and computer_use_evidence.get("helperGeneratedAt") == "True"
        and computer_use_evidence.get("helperFresh") == "True"
        and computer_use_evidence.get("helperFreshnessStatus") == "current"
        and computer_use_evidence.get("helperCountOnly") == "True"
        and computer_use_evidence.get("helperBoundary") == "True"
        and computer_use_evidence.get("storesAppNames") == "False"
        and computer_use_evidence.get("storesWindowTitles") == "False"
        and computer_use_evidence.get("helperSecretPatternHits") == "0"
        and computer_use_evidence.get("rawSecretPatternHits") == "0"
    )
    computer_use_safe_pending = (
        completion_audit_current
        and computer_use_ok
        and computer_use_evidence.get("safePendingProof") == "True"
        and computer_use_evidence.get("decision") == "evidence_needed"
        and computer_use_evidence.get("promptPack") == "True"
        and computer_use_evidence.get("guiOnly") == "True"
        and computer_use_evidence.get("noTerminalAutomation") == "True"
        and computer_use_evidence.get("supportingOnly") == "True"
        and computer_use_evidence.get("helperArtifact") == "True"
        and computer_use_evidence.get("helperGeneratedAt") == "True"
        and computer_use_evidence.get("helperFresh") == "True"
        and computer_use_evidence.get("helperFreshnessStatus") == "current"
        and computer_use_evidence.get("helperCountOnly") == "True"
        and computer_use_evidence.get("helperBoundary") == "True"
        and computer_use_evidence.get("storesAppNames") == "False"
        and computer_use_evidence.get("storesWindowTitles") == "False"
        and computer_use_evidence.get("helperSecretPatternHits") == "0"
        and computer_use_evidence.get("rawSecretPatternHits") == "0"
    )
    computer_use_boundary_ready = computer_use_live_ready or computer_use_safe_pending
    computer_use_health = 1.0 if computer_use_boundary_ready else 0.45
    browser_ui_ok, browser_ui_evidence = _checked_evidence(
        completion_audit,
        "browser-use.ui-proof-boundary",
    )
    browser_ui_boundary_observed = bool(browser_ui_evidence)
    browser_ui_live_ready = (
        completion_audit_current
        and browser_ui_ok
        and browser_ui_evidence.get("ready", "True") == "True"
        and browser_ui_evidence.get("artifactPresent") == "True"
        and browser_ui_evidence.get("artifactGeneratedAt") == "True"
        and browser_ui_evidence.get("artifactFresh") == "True"
        and browser_ui_evidence.get("artifactFreshnessStatus") == "current"
        and browser_ui_evidence.get("reachable") == "True"
        and browser_ui_evidence.get("targetAccepted") == "True"
        and browser_ui_evidence.get("screenshotCaptured") == "True"
        and browser_ui_evidence.get("targetContentVisible") == "True"
        and browser_ui_evidence.get("storesRawUrl") == "False"
        and browser_ui_evidence.get("storesScreenshotPath") == "False"
        and browser_ui_evidence.get("secretPatternHits") == "0"
    )
    browser_ui_safe_pending = (
        completion_audit_current
        and browser_ui_ok
        and browser_ui_evidence.get("safePendingProof") == "True"
        and browser_ui_evidence.get("decision") == "evidence_needed"
        and browser_ui_evidence.get("artifactPresent") == "True"
        and browser_ui_evidence.get("artifactGeneratedAt") == "True"
        and browser_ui_evidence.get("artifactFresh") == "True"
        and browser_ui_evidence.get("artifactFreshnessStatus") == "current"
        and browser_ui_evidence.get("storesRawUrl") == "False"
        and browser_ui_evidence.get("storesScreenshotPath") == "False"
        and browser_ui_evidence.get("secretPatternHits") == "0"
    )
    browser_ui_stale_supporting = (
        completion_audit_current
        and browser_ui_ok
        and browser_ui_evidence.get("safePendingProof") == "True"
        and browser_ui_evidence.get("decision") == "ok"
        and browser_ui_evidence.get("artifactPresent") == "True"
        and browser_ui_evidence.get("artifactGeneratedAt") == "True"
        and browser_ui_evidence.get("artifactFresh") == "False"
        and browser_ui_evidence.get("artifactFreshnessStatus") == "stale"
        and browser_ui_evidence.get("storesRawUrl") == "False"
        and browser_ui_evidence.get("storesScreenshotPath") == "False"
        and browser_ui_evidence.get("secretPatternHits") == "0"
    )
    browser_ui_boundary_ready = (
        browser_ui_live_ready or browser_ui_safe_pending or browser_ui_stale_supporting
    )
    browser_ui_health = 1.0 if browser_ui_boundary_ready else 0.45
    local_interaction_proof = _local_interaction_proof_summary(
        computer_ready=computer_use_boundary_ready,
        computer_evidence=computer_use_evidence,
        browser_ready=browser_ui_boundary_ready,
        browser_evidence=browser_ui_evidence,
    )
    peer_evidence_bus_ok, peer_evidence_bus_evidence = _checked_evidence(
        completion_audit,
        "peer.evidence-bus",
    )
    peer_evidence_bus_artifact_ok, peer_evidence_bus_artifact_evidence = _requirement_evidence(
        completion_audit,
        "peer-evidence-bus-artifact",
    )
    peer_evidence_bus_proof = _peer_evidence_bus_proof_summary(
        contract_ok=completion_audit_current and peer_evidence_bus_ok,
        contract_evidence=peer_evidence_bus_evidence,
        artifact_ok=completion_audit_current and peer_evidence_bus_artifact_ok,
        artifact_evidence=peer_evidence_bus_artifact_evidence,
    )
    external_input_gate_ok, external_input_gate_evidence = _requirement_evidence(
        completion_audit,
        "goal-next-auto-command-packet",
    )
    external_input_gate_proof = _external_input_gate_proof_summary(
        requirement_ok=completion_audit_current and external_input_gate_ok,
        evidence=external_input_gate_evidence,
    )
    external_input_gate_observed = bool(external_input_gate_evidence)
    external_input_gate_ready = (
        not external_input_gate_observed
        or bool(external_input_gate_proof.get("boundaryReady"))
    )
    collection_packet_check_ok, collection_packet_check_evidence = _checked_evidence(
        completion_audit,
        "goal-next.collection-packet",
    )
    collection_packet_requirement_ok, collection_packet_requirement_evidence = _requirement_evidence(
        completion_audit,
        "goal-next-auto-collection-packet",
    )
    collection_packet_evidence = {
        **collection_packet_requirement_evidence,
        **collection_packet_check_evidence,
    }
    goal_next_collection_packet_proof = _goal_next_collection_packet_proof_summary(
        requirement_ok=completion_audit_current and collection_packet_requirement_ok,
        check_ok=completion_audit_current and collection_packet_check_ok,
        evidence=collection_packet_evidence,
    )
    collection_packet_observed = bool(collection_packet_evidence)
    collection_packet_ready = (
        not collection_packet_observed
        or bool(goal_next_collection_packet_proof.get("boundaryReady"))
    )
    supabase_health = 0.60 if supabase_read_only_safe else 0.25
    if supabase_project_status not in ("project_ref_missing", "missing"):
        supabase_health = (
            0.65 if supabase_read_only_safe and supabase_data_api_result_sets_missing
            else 0.85 if supabase_read_only_safe
            else supabase_health
        )
    supabase_metric_evidence_valid = quant_input_current
    if not supabase_metric_evidence_valid:
        supabase_health = 0.0

    metric_inputs_current = (
        quant_input_current
        and harmony_input_current
        and test_tree_input_current
        and websoak_input_current
    )
    metric_integrity = (
        1.0
        if metric_inputs_current and quant and harmony_summary and test_summary and websoak_provider
        else 0.0
    )
    metric_integrity_evidence = (
        "quant, harmony, test-tree, and websoak JSON artifacts are present, parsed, and current"
        if metric_inputs_current
        else "reason=metric-input-invalid; one or more canonical score inputs are not current"
    )

    large_files_raw = quant.get("largeActiveFilesOver2000")
    p95_loc_raw = quant.get("activeJavaLocP95")
    maintainability_inputs_valid = (
        quant_input_current
        and isinstance(large_files_raw, int)
        and not isinstance(large_files_raw, bool)
        and large_files_raw >= 0
        and isinstance(p95_loc_raw, (int, float))
        and not isinstance(p95_loc_raw, bool)
        and math.isfinite(float(p95_loc_raw))
        and float(p95_loc_raw) >= 0.0
    )
    if maintainability_inputs_valid:
        large_files = large_files_raw
        p95_loc = float(p95_loc_raw)
        maintainability_risk = _clamp(
            (min(1.0, large_files / 24.0) * 0.55)
            + (min(1.0, p95_loc / 1000.0) * 0.45)
        )
        maintainability_health = 1.0 - (maintainability_risk * 0.55)
        maintainability_evidence = (
            "reason=metric-input-valid; "
            f"largeActiveFilesOver2000={large_files}; activeJavaLocP95={p95_loc}"
        )
        maintainability_formula = "1 - 0.55 * weighted large-file/p95 pressure"
    else:
        large_files = 0
        p95_loc = 0.0
        maintainability_health = 0.0
        maintainability_evidence = (
            "reason=metric-input-invalid; "
            f"quantStatus={quant_input_freshness['status']}; "
            "largeActiveFilesOver2000=missing-or-invalid; "
            "activeJavaLocP95=missing-or-invalid"
        )
        maintainability_formula = (
            "0 when required quantitative inputs are missing or invalid; "
            "otherwise 1 - 0.55 * weighted large-file/p95 pressure"
        )

    components = [
        _component(
            component_id="source_integrity",
            label="SourceSet, duplicate FQCN, and secret-scan integrity",
            weight=0.20,
            normalized=source_integrity,
            confidence=0.98,
            evidence=source_integrity_evidence,
            formula="0 when quant input is invalid; otherwise PASS when duplicateFqcnActiveCount=0 and secretPatternHitCount=0",
        ),
        _component(
            component_id="runtime_provider_safety",
            label="Provider-disabled runtime smoke and redaction",
            weight=0.20,
            normalized=provider_health,
            confidence=0.90,
            evidence=provider_evidence,
            formula="0 when quant/websoak input is invalid; otherwise 0.93 for a redacted provider-disabled 200 smoke",
        ),
        _component(
            component_id="harmony_pressure",
            label="Cross-subsystem, catch, and AOP-order pressure",
            weight=0.15,
            normalized=harmony_health,
            confidence=0.90,
            evidence=harmony_evidence,
            formula="0 when quant/harmony input is invalid; otherwise 1 - 0.5 * weighted pressure risk",
        ),
        _component(
            component_id="test_tree_reliability",
            label="Broad test-tree active-source alignment",
            weight=0.10,
            normalized=test_health,
            confidence=0.92,
            evidence=test_evidence,
            formula="0 when quant/test-tree input is invalid; otherwise 1 - testTreeContamination.riskScore",
        ),
        _component(
            component_id="supabase_external_evidence",
            label="Supabase read-only project evidence",
            weight=0.10,
            normalized=supabase_health,
            confidence=0.72,
            evidence=(
                (
                    "reason=metric-input-invalid; input=quantMetrics; "
                    f"status={quant_input_freshness['status']}; "
                    if not supabase_metric_evidence_valid
                    else ""
                )
                +
                f"readOnlyMode={supabase.get('readOnlyMode')}; "
                f"mutationAllowed={supabase.get('mutationAllowed')}; "
                f"projectScopeStatus={supabase_project_status}; "
                f"missingResultNames={','.join(supabase_missing_names)}; "
                f"docsRefCount={supabase_smoke_evidence.get('docsRefCount')}; "
                f"securityContractCount={supabase_smoke_evidence.get('securityContractCount')}; "
                f"summaryGeneratedAt={supabase_smoke_evidence.get('summaryGeneratedAt')}; "
                f"summaryFresh={supabase_smoke_evidence.get('summaryFresh')}; "
                f"summaryFreshnessStatus={supabase_smoke_evidence.get('summaryFreshnessStatus')}; "
                f"summaryAgeSeconds={supabase_smoke_evidence.get('summaryAgeSeconds')}; "
                f"summarySecretHits={supabase_smoke_evidence.get('summarySecretHits')}; "
                f"summaryRawSecretPatternHits={supabase_smoke_evidence.get('summaryRawSecretPatternHits')}; "
                f"rawSecretPatternHits={supabase_smoke_evidence.get('rawSecretPatternHits')}; "
                f"cliQueryMinVersion={supabase_smoke_evidence.get('cliQueryMinVersion')}; "
                f"cliAdvisorsMinVersion={supabase_smoke_evidence.get('cliAdvisorsMinVersion')}; "
                f"mcpFallbackContract={supabase_smoke_evidence.get('mcpFallbackContract')}; "
                f"dataApiGrantProofRequired={supabase_smoke_evidence.get('dataApiGrantProofRequired')}; "
                f"rlsPolicyProofRequired={supabase_smoke_evidence.get('rlsPolicyProofRequired')}; "
                f"localSmokeContractReady={supabase_local_smoke_contract_ready}"
            ),
            formula="0.60 for read-only safe but project-scoped proof missing; 0.85 when project-scoped proof is available",
        ),
        _component(
            component_id="metric_integrity",
            label="Metric artifact completeness and parseability",
            weight=0.10,
            normalized=metric_integrity,
            confidence=0.94,
            evidence=metric_integrity_evidence,
            formula="PASS only when all canonical score inputs are present, parseable, dated, and current",
        ),
        _component(
            component_id="computer_use_evidence_boundary",
            label="Computer Use GUI-only supporting evidence boundary",
            weight=0.05,
            normalized=computer_use_health,
            confidence=0.88,
            evidence=(
                f"promptPack={computer_use_evidence.get('promptPack')}; "
                f"guiOnly={computer_use_evidence.get('guiOnly')}; "
                f"noTerminalAutomation={computer_use_evidence.get('noTerminalAutomation')}; "
                f"supportingOnly={computer_use_evidence.get('supportingOnly')}; "
                f"helperArtifact={computer_use_evidence.get('helperArtifact')}; "
                f"helperReachable={computer_use_evidence.get('helperReachable')}; "
                f"helperGeneratedAt={computer_use_evidence.get('helperGeneratedAt')}; "
                f"helperFresh={computer_use_evidence.get('helperFresh')}; "
                f"helperFreshnessStatus={computer_use_evidence.get('helperFreshnessStatus')}; "
                f"helperAgeSeconds={computer_use_evidence.get('helperAgeSeconds')}; "
                f"helperCountOnly={computer_use_evidence.get('helperCountOnly')}; "
                f"helperBoundary={computer_use_evidence.get('helperBoundary')}; "
                f"storesAppNames={computer_use_evidence.get('storesAppNames')}; "
                f"storesWindowTitles={computer_use_evidence.get('storesWindowTitles')}; "
                f"appCount={computer_use_evidence.get('appCount')}; "
                f"targetableWindowCount={computer_use_evidence.get('targetableWindowCount')}; "
                f"helperSecretPatternHits={computer_use_evidence.get('helperSecretPatternHits')}; "
                f"rawSecretPatternHits={computer_use_evidence.get('rawSecretPatternHits')}; "
                f"safePendingProof={computer_use_evidence.get('safePendingProof')}; "
                f"decision={computer_use_evidence.get('decision')}; "
                f"boundaryReady={computer_use_boundary_ready}"
            ),
            formula="PASS when Computer Use is limited to GUI/browser proof, not source or terminal automation",
        ),
        _component(
            component_id="browser_ui_evidence_boundary",
            label="Browser UI target-accepted proof boundary",
            weight=0.05,
            normalized=browser_ui_health,
            confidence=0.88,
            evidence=(
                f"artifactPresent={browser_ui_evidence.get('artifactPresent')}; "
                f"artifactGeneratedAt={browser_ui_evidence.get('artifactGeneratedAt')}; "
                f"artifactFresh={browser_ui_evidence.get('artifactFresh')}; "
                f"artifactFreshnessStatus={browser_ui_evidence.get('artifactFreshnessStatus')}; "
                f"reachable={browser_ui_evidence.get('reachable')}; "
                f"targetAccepted={browser_ui_evidence.get('targetAccepted')}; "
                f"localhost={browser_ui_evidence.get('localhost')}; "
                f"publicDomain={browser_ui_evidence.get('publicDomain')}; "
                f"screenshotCaptured={browser_ui_evidence.get('screenshotCaptured')}; "
                f"targetContentVisible={browser_ui_evidence.get('targetContentVisible')}; "
                f"storesRawUrl={browser_ui_evidence.get('storesRawUrl')}; "
                f"storesScreenshotPath={browser_ui_evidence.get('storesScreenshotPath')}; "
                f"secretPatternHits={browser_ui_evidence.get('secretPatternHits')}; "
                f"safePendingProof={browser_ui_evidence.get('safePendingProof')}; "
                f"decision={browser_ui_evidence.get('decision')}; "
                f"boundaryObserved={browser_ui_boundary_observed}; "
                f"boundaryReady={browser_ui_boundary_ready}"
            ),
            formula="PASS when Browser proof is fresh, target-accepted, visible, and stores no raw URL or screenshot path",
        ),
        _component(
            component_id="scope_maintainability",
            label="Large-file and active LOC maintainability pressure",
            weight=0.05,
            normalized=maintainability_health,
            confidence=0.95,
            evidence=maintainability_evidence,
            formula=maintainability_formula,
        ),
    ]

    score = _round4(sum(component["weightedPoints"] for component in components))
    aspect_order_risk = _clamp(max(1.0 - aspect_order_coverage, critical_aspect_ratio)) if aspect_files else 0.0
    aspect_order_contract_present = _aspect_order_contract_present(root)
    silent_swallow_risk = _clamp(broad_catch_ratio)
    cross_subsystem_risk = _clamp(cross_large / 50.0)
    cross_subsystem_contract_proof = _focused_cross_subsystem_contract_proof(root)
    cross_subsystem_contract_passed = bool(cross_subsystem_contract_proof.get("passed"))
    broad_runtime_test_proof = _broad_runtime_test_proof(root)
    broad_runtime_test_passed = bool(broad_runtime_test_proof.get("passed"))
    risk_ledger = [
        _risk(
            risk_id="aspect_order_hotspots",
            label="Implicit AOP ordering on critical cross-subsystem aspects",
            risk_score=aspect_order_risk,
            evidence=(
                f"unorderedAspectCount={harmony_summary.get('unorderedAspectCount')}; "
                f"criticalUnorderedAspectCount={critical_unordered}; "
                f"contractPresent={aspect_order_contract_present}; "
                f"top={_top_file(harmony_summary.get('topUnorderedAspectHotspots', []))}"
            ),
            next_action=(
                "Aspect ordering proof is current; keep the existing order contract green before search/LLM routing patches."
                if aspect_order_contract_present and aspect_order_risk == 0.0 and broad_runtime_test_passed
                else "Run focused AspectOrderingContractTest before search/LLM routing patches and keep the existing order contract green."
                if aspect_order_contract_present and aspect_order_risk == 0.0
                else "Add explicit order/call-path contracts for ExtremeZBurstAspect, LlmRouterAspect, and RagCompressionAspect before search/LLM routing patches."
            ),
        ),
        _risk(
            risk_id="cross_subsystem_concentration",
            label="Large files holding multiple S01-S08 subsystem families",
            risk_score=cross_subsystem_risk,
            evidence=(
                f"runtimeCrossSubsystemLargeFilesOver1000={cross_large}; "
                f"crossSubsystemLargeFilesOver1000={cross_large_total}; "
                f"focusedContractTests={'passed' if cross_subsystem_contract_passed else 'missing'}; "
                f"focusedContractTestCount={cross_subsystem_contract_proof.get('passedCount', 0)}/"
                f"{cross_subsystem_contract_proof.get('requiredCount', len(FOCUSED_CROSS_SUBSYSTEM_CONTRACT_TESTS))}"
            ),
            next_action=(
                "Broad runtime proof is current; keep safe patches focused and preserve the broad proof gate."
                if cross_subsystem_contract_passed and broad_runtime_test_passed
                else "Focused cross-subsystem contract tests passed; keep safe patches focused and move the next gate to broad runtime proof."
                if cross_subsystem_contract_passed
                else "Keep safe patches focused; split only with contract tests around the exact runtime seam."
            ),
            status="guarded" if cross_subsystem_contract_passed and broad_runtime_test_passed else None,
            status_reason=(
                "focused cross-subsystem contracts and broad runtime proof are current; monitor pressure without refactoring"
                if cross_subsystem_contract_passed and broad_runtime_test_passed
                else None
            ),
        ),
        _risk(
            risk_id="silent_swallow_pressure",
            label="Broad catches without nearby trace/log/breadcrumb",
            risk_score=silent_swallow_risk,
            evidence=f"broadCatchWithoutLocalBreadcrumbRatio={broad_catch_ratio}",
            next_action="Audit broad catches in top cross-subsystem files and add redacted breadcrumbs, not blanket catch rewrites.",
        ),
        _risk(
            risk_id="test_tree_contamination",
            label="Broad test tree imports no longer aligned with active source FQCNs",
            risk_score=_clamp(test_risk),
            evidence=f"missingImportCount={test_summary.get('missingImportCount')}; affectedTestFileCount={test_summary.get('affectedTestFileCount')}",
            next_action=(
                "Broad runtime proof is current; keep compileTestJava in the proof lane and preserve the broad test gate."
                if test_risk == 0 and broad_runtime_test_passed
                else "Keep compileTestJava in the proof lane and move the next gate to full test runtime proof."
                if test_risk == 0
                else "Realign or quarantine affected test files before treating broad test as final proof."
            ),
        ),
        _risk(
            risk_id="supabase_live_proof_missing",
            label="Supabase schema/advisor proof not project-scoped",
            risk_score=(
                0.40 if supabase_project_status == "project_ref_missing"
                else 0.25 if supabase_data_api_result_sets_missing
                else 0.15
            ),
            evidence=f"projectScopeStatus={supabase_project_status}; readOnlyMode={supabase.get('readOnlyMode')}; missingResultNames={','.join(supabase_missing_names)}",
            next_action=(
                "Collect Supabase data_api_role_grants, rls_and_table_flags, and exposed_tables_without_rls before claiming Data API/RLS health."
                if supabase_data_api_result_sets_missing and supabase_project_status != "project_ref_missing"
                else "Provide project_ref/authenticated read-only MCP or CLI path before claiming live Supabase health."
            ),
            scope="external_evidence",
        ),
    ]
    active_risk_count = sum(
        1 for row in risk_ledger
        if row.get("status") == "action_required" and row.get("scope") == "active_source"
    )

    evidence_needed = []
    for input_name, freshness in input_freshness.items():
        if not freshness["fresh"]:
            evidence_needed.append(
                {
                    "classification": "evidence_needed",
                    "item": f"input_freshness:{input_name}",
                    "reason": (
                        "reason=metric-input-invalid; canonical score input must be "
                        "present, parseable, dated, and current: "
                        f"status={freshness['status']}; pathHash={freshness['pathHash']}"
                    ),
                }
            )
    if db_gap_matrix and not db_gap_matrix_current:
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "db_gap_matrix_freshness",
                "reason": (
                    "DB gap matrix must include generatedAt and be current before reusing Supabase gap evidence: "
                    f"status={db_gap_matrix_freshness_status}"
                ),
            }
        )
    if completion_audit_path and not completion_audit_current:
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "completion_audit_freshness",
                "reason": (
                    "latest completion audit must include generatedAt and be current: "
                    f"status={completion_audit_freshness_status}"
                ),
            }
        )
    if test_risk > 0:
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "broad_test_tree_compile",
                "reason": "test-tree contamination remains nonzero",
            }
        )
    if supabase_project_status == "project_ref_missing":
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "supabase_project_scoped_schema_and_advisor_snapshot",
                "reason": "project_ref/authenticated read-only path missing",
            }
        )
    if supabase_data_api_result_sets_missing:
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "supabase_data_api_grants_and_rls_result_sets",
                "reason": (
                    "missing result sets required after Supabase Data API explicit grant "
                    "change and trace-memory shadow-memory fingerprint probe expansion: "
                    + ",".join(supabase_external_required_missing_names)
                ),
            }
        )
    if completion_audit_path and not supabase_local_smoke_contract_ready:
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "supabase_readonly_snapshot_smoke_contract",
                "reason": (
                    "completion audit lacks missingResultNames/dataApiEvidenceMissing/docsRefCount/"
                    "securityContractCount/freshSummary/cliVersionRequirements/mcpFallbackContract/"
                    "dataApiGrantProofRequired/rlsPolicyProofRequired redacted summarySecretHits/"
                    "summaryRawSecretPatternHits readiness flags"
                ),
            }
        )
    if completion_audit_path and not computer_use_boundary_ready:
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "computer_use_gui_proof_boundary",
                "reason": (
                    "completion audit lacks promptPack/guiOnly/noTerminalAutomation/"
                    "supportingOnly/helperArtifact/helperReachable/fresh helper/count-only/"
                    "rawSecretPatternHits Computer Use boundary flags"
                ),
            }
        )
    if completion_audit_path and browser_ui_boundary_observed and not browser_ui_boundary_ready:
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "browser_ui_proof_boundary",
                "reason": (
                    "completion audit lacks fresh/reachable/targetAccepted/screenshotCaptured/"
                    "targetContentVisible/noRawUrl/noScreenshotPath/secretPatternHits Browser UI boundary flags"
                ),
            }
        )
    if completion_audit_path and external_input_gate_observed and not external_input_gate_ready:
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "goal_next_external_input_gate_contract",
                "reason": (
                    "completion audit goal-next command packet does not preserve the "
                    "external input gate status/source/action/localPatchJustified/"
                    "mutationAllowed/evidenceNeeded contract"
                ),
            }
        )
    if completion_audit_path and collection_packet_observed and not collection_packet_ready:
        evidence_needed.append(
            {
                "classification": "evidence_needed",
                "item": "goal_next_collection_packet_contract",
                "reason": (
                    "completion audit goal-next collection packet does not preserve the "
                    "Supabase read-only MCP, external source-isolation, web-probe, "
                    "browser, and computer-use proof boundary"
                ),
            }
        )

    if db_gap_matrix and not db_gap_matrix_current:
        next_single_action = "rerun_db_gap_scanner"
    elif completion_audit_path and not completion_audit_current:
        next_single_action = "rerun_completion_audit"
    elif completion_audit_path and supabase_smoke_contract_action_required:
        next_single_action = "repair_supabase_readonly_snapshot_smoke_contract"
    elif completion_audit_path and not computer_use_boundary_ready:
        next_single_action = "repair_computer_use_gui_proof_boundary"
    elif completion_audit_path and browser_ui_boundary_observed and not browser_ui_boundary_ready:
        next_single_action = "repair_browser_ui_proof_boundary"
    elif completion_audit_path and external_input_gate_observed and not external_input_gate_ready:
        next_single_action = "repair_goal_next_external_input_gate_contract"
    elif completion_audit_path and collection_packet_observed and not collection_packet_ready:
        next_single_action = "repair_goal_next_collection_packet_contract"
    elif supabase_project_status == "project_ref_missing":
        next_single_action = "provide_supabase_project_ref_and_authenticated_readonly_mcp_or_cli_for_schema_advisor_snapshot"
    elif supabase_data_api_result_sets_missing:
        next_single_action = "collect_supabase_data_api_grants_and_rls_result_sets"
    elif test_risk > 0:
        next_single_action = "realign_or_quarantine_test_tree_contamination_before_broad_test_gate"
    else:
        next_single_action = "run_broad_test_runtime_proof_or_triage_top_aspect_order_hotspots"

    if db_gap_matrix and not db_gap_matrix_current:
        next_source_action = "rerun_db_gap_scanner"
    elif completion_audit_path and not completion_audit_current:
        next_source_action = "rerun_completion_audit"
    elif completion_audit_path and supabase_smoke_contract_action_required:
        next_source_action = "repair_supabase_readonly_snapshot_smoke_contract"
    elif test_risk > 0:
        next_source_action = "realign_or_quarantine_test_tree_contamination_before_broad_test_gate"
    elif aspect_order_risk > 0:
        next_source_action = "triage_top_aspect_order_hotspots"
    elif silent_swallow_risk > 0:
        next_source_action = "audit_top_broad_catches_for_redacted_breadcrumbs"
    elif cross_subsystem_risk >= 0.33 and not cross_subsystem_contract_passed:
        next_source_action = "continue_small_contract_tests_on_cross_subsystem_runtime_seams"
    elif not broad_runtime_test_passed:
        next_source_action = "run_broad_test_runtime_proof"
    elif not source_runtime_boot_passed:
        next_source_action = "source_runtime_proof_current"
    elif next_single_action in EXTERNAL_ONLY_NEXT_ACTIONS:
        next_source_action = NO_LOCAL_SOURCE_ACTION
    else:
        next_source_action = next_single_action

    external_evidence_next_action = (
        next_single_action if next_single_action in EXTERNAL_ONLY_NEXT_ACTIONS else ""
    )
    if external_evidence_next_action and (
        _goal_next_status_desktop_only_ready(goal_next_status_proof)
        or completion_audit_desktop_only_ready
    ):
        next_single_action = (
            DESKTOP_ONLY_NEXT_ACTION
            if next_source_action == NO_LOCAL_SOURCE_ACTION
            else next_source_action
        )

    failure_pattern_prediction = _failure_pattern_prediction(risk_ledger, components)
    generated_at = _dt.datetime.now(_dt.timezone.utc).isoformat(timespec="seconds")
    failure_pattern_evidence_artifacts = _failure_pattern_evidence_artifacts(
        generated_at,
        failure_pattern_prediction,
    )

    return {
        "generatedAt": generated_at,
        "decision": "source_health_scorecard",
        "scoreKind": "strict_evidence_adjusted_source_health",
        "strictEvidenceAdjustedScore": score,
        "componentScores": components,
        "riskLedger": risk_ledger,
        "riskCount": len(risk_ledger),
        "activeRiskCount": active_risk_count,
        "failurePatternKind": failure_pattern_prediction["failurePatternKind"],
        "patternId": failure_pattern_prediction["patternId"],
        "amplifiedSignalScore": failure_pattern_prediction["amplifiedSignalScore"],
        "requiredEvidenceSinks": _required_evidence_sinks(),
        "failurePatternPrediction": failure_pattern_prediction,
        "failurePatternEvidenceArtifacts": failure_pattern_evidence_artifacts,
        "topUnorderedAspectHotspots": harmony_summary.get("topUnorderedAspectHotspots", [])[:10],
        "completionAuditFreshness": {
            "generatedAt": completion_audit_has_generated_at,
            "fresh": completion_audit_fresh,
            "ageSeconds": completion_audit_age_seconds,
            "status": completion_audit_freshness_status,
            "path": completion_audit_path
            or (DEFAULT_COMPLETION_AUDIT_DIR / COMPLETION_AUDIT_GLOB).as_posix(),
            "pathHash": _stable_hash(
                completion_audit_path
                or (DEFAULT_COMPLETION_AUDIT_DIR / COMPLETION_AUDIT_GLOB).as_posix()
            ),
        },
        "completionAuditSupportingEvidenceActionMode": completion_audit_supporting_action_mode,
        "completionAuditLocalReady": completion_audit_local_ready,
        "completionAuditSupabaseLiveProofRequired": completion_audit_supabase_live_proof_required,
        "completionAuditSupportingEvidenceNextActionsOmitted": completion_audit_supporting_actions_omitted,
        "completionAuditSupportingEvidenceActionHint": completion_audit_supporting_action_hint,
        "completionAuditSupportingEvidenceNeededDetailMode": completion_audit_supporting_needed_detail_mode,
        "completionAuditSupportingEvidenceNeededDetailsOmitted": completion_audit_supporting_needed_details_omitted,
        "completionAuditSupportingEvidenceNeededDetailHint": completion_audit_supporting_needed_detail_hint,
        "completionAuditRequirementEvidenceNeededDetailMode": completion_audit_requirement_needed_detail["mode"],
        "completionAuditRequirementEvidenceNeededCompactRowCount": completion_audit_requirement_needed_detail["rowCount"],
        "completionAuditRequirementEvidenceNeededDetailsOmitted": completion_audit_requirement_needed_detail["detailsOmitted"],
        "completionAuditRequirementEvidenceNeededMaxLength": completion_audit_requirement_needed_detail["maxLength"],
        "completionAuditRequirementEvidenceNeededDetailHint": completion_audit_requirement_needed_detail["hint"],
        "dbGapMatrixFreshness": {
            "generatedAt": db_gap_matrix_has_generated_at,
            "fresh": db_gap_matrix_fresh,
            "ageSeconds": db_gap_matrix_age_seconds,
            "status": db_gap_matrix_freshness_status,
        },
        "inputFreshness": input_freshness,
        "evidenceNeeded": evidence_needed,
        "evidenceNeededCount": len(evidence_needed),
        "inputArtifacts": {
            "quantMetrics": DEFAULT_QUANT_METRICS.as_posix(),
            "harmonyPressure": DEFAULT_HARMONY_METRICS.as_posix(),
            "testTreeContamination": DEFAULT_TEST_TREE_METRICS.as_posix(),
            "websoakProviderSmoke": DEFAULT_WEBSOAK_PROVIDER_SMOKE.as_posix(),
            "dbGapMatrix": DEFAULT_DB_GAP_MATRIX.as_posix(),
            "completionAudit": completion_audit_path
            or (DEFAULT_COMPLETION_AUDIT_DIR / COMPLETION_AUDIT_GLOB).as_posix(),
            "goalNextStatus": DEFAULT_GOAL_NEXT_STATUS.as_posix(),
        },
        "focusedCrossSubsystemContractProof": cross_subsystem_contract_proof,
        "broadRuntimeTestProof": broad_runtime_test_proof,
        "sourceRuntimeBootProof": source_runtime_boot_proof,
        "localInteractionProof": local_interaction_proof,
        "peerEvidenceBusProof": peer_evidence_bus_proof,
        "externalInputGateProof": external_input_gate_proof,
        "goalNextCollectionPacketProof": goal_next_collection_packet_proof,
        "goalNextStatusProof": goal_next_status_proof,
        "nextActionDetails": next_action_details,
        "nextSingleAction": next_single_action,
        "externalEvidenceNextAction": external_evidence_next_action,
        "nextSourceAction": next_source_action,
        "nextSourceActionDetails": _safe_source_action_details(next_source_action),
    }


def _top_file(rows: Any) -> str:
    if isinstance(rows, list) and rows:
        first = rows[0]
        if isinstance(first, dict):
            return str(first.get("file", ""))
    return ""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate strict source-health scorecard from repo-owned metrics")
    parser.add_argument("--root", default=".", help="Repository root")
    parser.add_argument("--output", default=DEFAULT_OUTPUT.as_posix(), help="JSON output path")
    parser.add_argument(
        "--write-canonical-output",
        action="store_true",
        help="Also write the canonical scorecard and its evidence sidecars",
    )
    args = parser.parse_args(argv)

    root = Path(args.root).resolve()
    output = Path(args.output)
    if not output.is_absolute():
        output = root / output
    try:
        validate_current_quant_bundle(root)
    except QuantMetricsContractError as exc:
        print(
            "[AWX][source-health] reason=quant-metrics-missing-or-stale "
            f"status={exc.status}",
            file=sys.stderr,
        )
        return 2
    scorecard = build_scorecard(root)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(scorecard, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    default_output = (root / DEFAULT_OUTPUT).resolve()
    canonical_output_requested = output.resolve() == default_output or args.write_canonical_output
    if canonical_output_requested:
        _write_failure_pattern_evidence_artifacts(root, scorecard)
    if args.write_canonical_output and output.resolve() != default_output:
        default_output.parent.mkdir(parents=True, exist_ok=True)
        default_output.write_text(json.dumps(scorecard, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "[AWX][source-health] "
        f"report={output} strictEvidenceAdjustedScore={scorecard['strictEvidenceAdjustedScore']} "
        f"riskCount={scorecard['riskCount']} activeRiskCount={scorecard['activeRiskCount']} "
        f"evidenceNeeded={len(scorecard['evidenceNeeded'])}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
