from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import os
import re
import shutil
import stat
import sys
import tempfile
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Any, Iterable, Mapping, Sequence

try:
    from .harmony_catch_contract import (
        strip_java_comments_and_strings_preserve_lines,
    )
except ImportError:
    from harmony_catch_contract import (
        strip_java_comments_and_strings_preserve_lines,
    )


AUDIT_SCHEMA = "awx.structural-design-audit.v2"
METRICS_SCHEMA = "awx.dynamic-rag-quant-audit-metrics.v2"
BASELINE_SCHEMA = "awx.structural-design-baseline.v1"
LEDGER_SCHEMA = "awx.structural-design-debt-ledger-row.v1"
DUP_FQCN_SCHEMA = "awx.dup-fqcn-evidence.v1"
GIT_SKIP_WORKTREE_SCHEMA = "awx.structural-audit-git-skip-worktree.v1"
CLOSURE_EVENT_SCHEMA = "awx.structural-repair-closure-event.v1"
CLOSURE_PROGRESS_SCHEMA = "awx.structural-repair-progress.v1"
CLOSURE_INTAKE_SCHEMA = "awx.structural-repair-intake.v1"
CLOSURE_EVENT_V2_SCHEMA = "awx.structural-repair-closure-event.v2"
CLOSURE_PROGRESS_V2_SCHEMA = "awx.structural-repair-progress.v2"
CLOSURE_INTAKE_V2_SCHEMA = "awx.structural-repair-intake.v2"
CLOSURE_INTAKE_V3_SCHEMA = "awx.structural-repair-intake.v3"
CLOSURE_ADMISSION_DECISION_SCHEMA = "awx.structural-repair-admission-decision.v1"
CLOSURE_DUPLICATE_EVIDENCE_SCHEMA = "awx.structural-repair-duplicate-evidence.v1"
CLOSURE_ADMISSION_DECISION_V2_SCHEMA = "awx.structural-repair-admission-decision.v2"
CLOSURE_DUPLICATE_EVIDENCE_V2_SCHEMA = "awx.structural-repair-duplicate-evidence.v2"
CLOSURE_TARGET_ADMISSION_SCHEMA = "awx.structural-repair-target-admission.v1"
CLOSURE_TARGET_ADMISSION_PROOF_SCHEMA = "awx.structural-repair-target-admission-proof.v1"
WAVE_THREE_APPROVED_DESIGN_SHA256 = (
    "e7aad30226cde2c1a6a2360c65ccacf718f6f4ef1ea3f13f0ccff7f9017ebb55"
)
WAVE_THREE_APPROVED_SOURCE_DESIGN_SHA256: str | None = (
    "9a74ebdc8ad8fecee4965c4af27f399cab38178ec46a396b293fd8211d6454af"
)
WAVE_FOUR_APPROVED_DESIGN_SHA256 = (
    "1f41dfbfd570bec6c1babb94aa1273746a1ee596cf0460de0accfe64b5ef0ded"
)
CLOSURE_TARGET_PREIMAGES_SCHEMA = "awx.structural-repair-target-preimages.v1"
CLOSURE_PROOF_SCHEMA = "awx.structural-repair-proof-summary.v1"
CLOSURE_EVENT_BASELINE_SCHEMA = "awx.structural-repair-event-baseline.v1"
CLOSURE_WAVE_REGISTRY_SCHEMA = "awx.structural-repair-wave-registry.v1"
CLOSURE_HISTORY_SCHEMA = "awx.structural-repair-history-summary.v2"
DEFAULT_CANDIDATE_CAP = 1100
TARGET_FLOOR = 900
TARGET_CEILING = 1100
FILE_SIZE_THRESHOLD = 2000
CROSS_SUBSYSTEM_THRESHOLD = 1000
ACTIVE_JAVA_ROOTS = ("main/java", "app/src/main/java_clean")
ACTIVE_SOURCE_SET_BY_ROOT = {
    "main/java": "rootMain",
    "app/src/main/java_clean": "appMainClean",
}
STATUSES = frozenset(
    {
        "OPEN",
        "HOLD",
        "REJECTED_FALSE_POSITIVE",
        "SUPERSEDED",
        "PATCHED_UNVERIFIED",
        "VERIFIED_CLOSED",
    }
)
ELIGIBILITIES = frozenset(
    {"ELIGIBLE", "REVIEW_REQUIRED", "EXTERNAL_EVIDENCE_REQUIRED", "REJECTED_BY_CONTRACT"}
)
CATEGORIES = frozenset(
    {
        "DUPLICATE_FQCN_PACKAGED_ACTIVE",
        "BROAD_CATCH_NO_BREADCRUMB",
        "DUPLICATE_FQCN_SOURCE_COLLISION",
        "CROSS_SUBSYSTEM_CONCENTRATION",
        "FILE_SIZE_CONCENTRATION",
    }
)
SEVERITY_RANK = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3}
ELIGIBILITY_RANK = {
    "ELIGIBLE": 0,
    "REVIEW_REQUIRED": 1,
    "EXTERNAL_EVIDENCE_REQUIRED": 2,
    "REJECTED_BY_CONTRACT": 3,
}
REASON_CODES = frozenset(
    {
        "active-root-missing",
        "path-invalid",
        "path-case-collision",
        "reparse-path-risk",
        "git-capture-malformed",
        "required-input-missing",
        "required-input-malformed",
        "required-input-stale",
        "duplicate-count-inconsistent",
        "issue-identity-conflict",
        "group-owner-conflict",
        "public-artifact-secret-hit",
        "artifact-link-mismatch",
        "output-replace-failed",
        "semantic-nondeterminism",
        "source-decode-failed",
        "closure-registry-missing",
        "closure-registry-malformed",
        "closure-registry-mismatch",
        "closure-journal-missing",
        "closure-journal-malformed",
        "closure-intake-mismatch",
        "closure-admission-invalid",
        "closure-proof-invalid",
        "closure-event-conflict",
        "closure-wave-conflict",
        "closure-fingerprint-active",
        "closure-progress-mismatch",
    }
)
GENERATED_ARTIFACT_POLICY_VERSION = "structural-audit-generated.v1"
GENERATED_PATH_PREFIXES = (
    "__patch_drop__/source-edit-locks/",
    "build/",
    "app/build/",
    "var/codex-smoke/",
    "logs/",
)
GENERATED_TOP_LEVEL_PREFIXES = (".gradle",)
GENERATED_TOP_LEVEL_NAMES = frozenset({".playwright-cli"})
GENERATED_VAR_NAME_RULES = ("starts-with:gradle", "contains:project-cache")
GENERATED_VERIFICATION_PATHS = frozenset(
    {
        "verification/dynamic-rag-quant-audit-metrics.json",
        "verification/structural-design-baseline.json",
        "verification/structural-design-debt-ledger.jsonl",
        "verification/dynamic-rag-harmony-pressure-metrics.json",
        "verification/test-tree-contamination-metrics.json",
        "verification/source-health-scorecard.json",
        "verification/source-health-failure-pattern-events.ndjson",
        "verification/source-health-patchdrop-manifest-contract.json",
    }
)
AUDIT_METRICS_OUTPUT_RELATIVE = "verification/dynamic-rag-quant-audit-metrics.json"
AUDIT_BASELINE_OUTPUT_RELATIVE = "verification/structural-design-baseline.json"
AUDIT_LEDGER_OUTPUT_RELATIVE = "verification/structural-design-debt-ledger.jsonl"
HARMONY_SUMMARY_FIELDS = (
    "aspectFiles",
    "aspectOrderCoverageApprox",
    "criticalUnorderedAspectCount",
    "unorderedAspectCount",
    "crossSubsystemLargeFilesOver1000",
    "runtimeCrossSubsystemLargeFilesOver1000",
    "broadCatchWithoutLocalBreadcrumbRatio",
)
TEST_TREE_SUMMARY_FIELDS = ("riskScore", "missingImportCount", "affectedTestFileCount")
ALLOWED_PROOF_COMMANDS = frozenset(
    {
        "python -X utf8 scripts\\test_dynamic_rag_quant_audit.py",
        "python -X utf8 scripts\\test_harmony_pressure_report.py",
        "gradlew.bat :app:generateDupFqcnExcludes",
        "gradlew.bat dynamicRagQuantAudit",
    }
)
LEDGER_CORE_FIELDS = frozenset(
    {
        "schemaVersion",
        "issueId",
        "rootCauseGroupId",
        "baselineId",
        "category",
        "severity",
        "activeSourceSet",
        "path",
        "symbol",
        "evidenceReason",
        "evidenceFingerprint",
        "numericEvidence",
        "suspectedBoundary",
        "fixEligibility",
        "proofCommand",
        "status",
        "supersedes",
    }
)
LEDGER_ENVELOPE_FIELDS = frozenset({"generatedAt", "auditRunId", "artifactLinks"})
CLOSURE_EVENT_FIELDS = frozenset(
    {
        "schemaVersion",
        "eventId",
        "eventType",
        "rootCauseGroupId",
        "sourceIssueId",
        "sourceBaselineId",
        "sourceEvidenceFingerprint",
        "sourcePath",
        "sourceSymbol",
        "sourceCategory",
        "sourceNumericEvidence",
        "eventBaselineId",
        "targetPreimageSha256",
        "targetPostimageSha256",
        "scopedDiffSha256",
        "redProofId",
        "redProofSha256",
        "greenProofId",
        "greenProofSha256",
        "eventBaselineProofId",
        "eventBaselineProofSha256",
        "patchStateProofId",
        "patchStateProofSha256",
        "fingerprintDisposition",
        "sourceSetGate",
        "dependencyGate",
        "compileGate",
        "duplicateOwnerGate",
        "secretNewHitCount",
        "desktopProof",
        "supersedesEventId",
    }
)
CLOSURE_PROGRESS_FIELDS = frozenset(
    {
        "schemaVersion",
        "patchStateId",
        "state",
        "rootCauseGroupId",
        "sourceIssueId",
        "sourceBaselineId",
        "eventBaselineId",
        "targetPreimageSha256",
        "targetPostimageSha256",
        "scopedDiffSha256",
        "redProofId",
        "redProofSha256",
        "greenProofId",
        "greenProofSha256",
    }
)
CLOSURE_PROGRESS_V2_FIELDS = CLOSURE_PROGRESS_FIELDS | {
    "admissionDecisionId",
    "admissionDecisionSha256",
    "targetAdmissionId",
    "targetAdmissionSha256",
    "repairTargetPath",
}
CLOSURE_EVENT_V2_FIELDS = CLOSURE_EVENT_FIELDS | {
    "admissionDecisionId",
    "admissionDecisionSha256",
    "targetAdmissionId",
    "targetAdmissionSha256",
    "repairTargetPath",
}
CLOSURE_TARGET_ADMISSION_FIELDS = frozenset(
    {
        "schemaVersion",
        "targetAdmissionId",
        "rootCauseGroupId",
        "sourceIssueId",
        "admissionDecisionId",
        "admissionDecisionSha256",
        "repairTargetPath",
        "transformationMode",
        "approvedSourceDesignSha256",
        "detectorRedProofId",
        "detectorRedProofSha256",
        "compileBaselineProofSha256",
        "ownerContractProofSha256",
        "secretPatternHitCount",
    }
)
CLOSURE_TARGET_ADMISSION_PROOF_FIELDS = frozenset(
    {
        "schemaVersion",
        "proofRole",
        "rootCauseGroupId",
        "sourceIssueId",
        "commandToken",
        "exitCode",
        "result",
        "assertionCount",
        "outputSha256",
        "secretPatternHitCount",
    }
)
TARGET_ADMISSION_SUFFIXES = {
    "admission": "repair-target-admission",
    "detector": "detector-red-summary",
    "compile": "compile-baseline",
    "owner": "owner-contract",
}
CLOSURE_PROOF_FIELDS = frozenset(
    {
        "schemaVersion",
        "proofKind",
        "rootCauseGroupId",
        "sourceIssueId",
        "commandToken",
        "exitCode",
        "result",
        "assertionCount",
        "outputSha256",
        "secretPatternHitCount",
    }
)
CLOSURE_EVENT_BASELINE_FIELDS = frozenset(
    {"schemaVersion", "baselineId", "branch", "head", "declaredTargets"}
)
CLOSURE_EVENT_BASELINE_TARGET_FIELDS = frozenset(
    {"path", "gitState", "sizeBytes", "sha256"}
)
CLOSURE_INTAKE_SUMMARY_FIELDS = frozenset(
    {
        "schemaVersion",
        "sourceBaselineId",
        "sourceLedgerPayloadSha256",
        "sourceMetricsSemanticHash",
        "sourceIssueIds",
        "rootCauseGroupIds",
        "evidenceFingerprints",
        "sourceBranch",
        "sourceHead",
    }
)
CLOSURE_INTAKE_V2_SUMMARY_FIELDS = CLOSURE_INTAKE_SUMMARY_FIELDS | {
    "admissionMode",
    "admissionDecisionSha256",
    "duplicateEvidenceSha256",
}
CLOSURE_INTAKE_V3_SUMMARY_FIELDS = CLOSURE_INTAKE_SUMMARY_FIELDS | {
    "admissionMode",
    "admissionDecisionSha256",
    "duplicateEvidenceSha256",
}
CLOSURE_ADMISSION_DECISION_FIELDS = frozenset(
    {
        "schemaVersion", "decisionId", "decisionType", "sourceBaselineId",
        "sourceIssueId", "rootCauseGroupId", "sourceEvidenceFingerprint",
        "sourceCategory", "sourceFixEligibility", "sourceStatus",
        "sourceNumericEvidence", "canonicalOwnerPath", "proposedRepairTargetPath",
        "repairTargetAdmissionRequired", "activeCallPath", "behaviorTestPath",
        "ownerContractTestPath", "duplicateEvidenceSha256", "approvedDesignSha256",
        "ragControlExcluded",
    }
)
CLOSURE_DUPLICATE_EVIDENCE_FIELDS = frozenset(
    {
        "schemaVersion", "sourceIssueId", "rootCauseGroupId", "fqcn",
        "canonicalOwnerPath", "compatibilityCopyPath", "sourceCollisionCount",
        "generatedExcludeCount", "hardExcludeCount", "packagedActiveCount",
        "generatedExcludePattern", "proofCommand", "rawReportSha256",
        "secretPatternHitCount",
    }
)
CLOSURE_ADMISSION_DECISION_V2_FIELDS = frozenset(
    {
        "schemaVersion", "decisionId", "decisionType", "sourceBaselineId",
        "sourceIssueId", "rootCauseGroupId", "sourceEvidenceFingerprint",
        "sourceCategory", "sourceFixEligibility", "sourceStatus",
        "sourceNumericEvidence", "candidateFqcn", "canonicalOwnerPath",
        "proposedRepairTargetPath", "repairTargetAdmissionRequired",
        "packagingConfigPath", "activeCallPath", "futureOwnershipTestPath",
        "duplicateEvidenceSha256", "approvedDesignSha256", "ragControlExcluded",
    }
)
CLOSURE_DUPLICATE_EVIDENCE_V2_FIELDS = frozenset(
    {
        "schemaVersion", "sourceIssueId", "rootCauseGroupId", "fqcn",
        "canonicalOwnerPath", "compatibilityCopyPath", "packagingState",
        "sourceCollisionCount", "generatedExcludeCount", "hardExcludeCount",
        "packagedActiveCount", "appActiveSourceRoot",
        "compatibilityCopyDirectCallerFileCount", "callerProbeToken",
        "generatedExcludePattern", "proofCommand", "rawReportSha256",
        "rawCollisionEvidenceFingerprint", "secretPatternHitCount",
    }
)
CLOSURE_TARGET_PREIMAGES_FIELDS = frozenset(
    {"schemaVersion", "sourceHead", "canonicalBranch", "isolatedBranch", "targets"}
)
CLOSURE_TARGET_PREIMAGE_ROW_FIELDS = frozenset(
    {"path", "existence", "gitState", "sizeBytes", "sha256"}
)
CLOSURE_SUMMARY_FIELDS = frozenset(
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
CLOSURE_WAVE_REGISTRY_FIELDS = frozenset({"schemaVersion", "waves"})
CLOSURE_WAVE_DESCRIPTOR_FIELDS = frozenset(
    {
        "waveId",
        "ordinal",
        "journalPath",
        "proofRoot",
        "sourceBaselineId",
        "sourceLedgerPayloadSha256",
        "sourceMetricsSemanticHash",
        "intakeSummarySha256",
        "eligibleGroupsSha256",
        "targetPreimagesSha256",
    }
)
CLOSURE_WAVE_DESCRIPTOR_HASH_FIELDS = (
    "sourceBaselineId",
    "sourceLedgerPayloadSha256",
    "sourceMetricsSemanticHash",
    "intakeSummarySha256",
    "eligibleGroupsSha256",
    "targetPreimagesSha256",
)
INTAKE_SUMMARY_RELATIVE = Path("intake/intake-summary.json")
INTAKE_ROWS_RELATIVE = Path("intake/eligible-groups.jsonl")
TARGET_PREIMAGES_RELATIVE = Path("intake/target-preimages.json")
ADMISSION_DECISION_RELATIVE = Path("intake/admission-decision.json")
DUPLICATE_EVIDENCE_RELATIVE = Path("intake/duplicate-evidence.json")
PROGRESS_RELATIVE = Path("repair-progress.jsonl")
CLOSURE_WAVE_REGISTRY_RELATIVE = Path(
    "verification/structural-repair-waves/registry.json"
)
FROZEN_CLOSURE_WAVE_REGISTRY_SHA256 = (
    "3a3a09c64e7be4d5d13bb8a298d01779fde2932cff86f4c1e19d0d5068ed81ed"
)
PROOF_ID_RE = re.compile(
    r"proofs/[0-9a-f]{64}/(?:red-summary|green-summary|event-baseline)\.json"
)
HISTORICAL_TERMINAL_CONTRACT = {
    "REJECTED_FALSE_POSITIVE": ("REJECTED_BY_CONTRACT", "REJECTED_FALSE_POSITIVE"),
    "VERIFIED_CLOSED": ("ELIGIBLE", "VERIFIED_CLOSED"),
}
SECRET_PATTERN = re.compile(
    r"(?i)(?:sk-(?:live-|proj-)?[A-Za-z0-9_-]{20,}|(?:api[_-]?key|client[_-]?secret|authorization)\s*[:=])"
)
REDACTED_PATH_PREFIX = "__redacted_path__/sha256-"
REDACTED_SYMBOL_PREFIX = "__redacted_symbol__sha256-"
WINDOWS_ABSOLUTE_PATTERN = re.compile(r"(?i)(?:^|[\"'])\s*[A-Z]:[\\/]")
POSIX_ABSOLUTE_PATTERN = re.compile(r"^/(?!/)")
UNC_ABSOLUTE_PATTERN = re.compile(r"^(?:\\\\|//)")
WINDOWS_ROOTED_PATTERN = re.compile(r"^\\(?!\\)")
URI_SCHEME_PATTERN = re.compile(r"(?i)(?:^|[\s\"'])[A-Za-z][A-Za-z0-9+.-]*:")


class AuditContractError(ValueError):
    def __init__(self, reason_code: str) -> None:
        safe_reason = reason_code if reason_code in REASON_CODES else "semantic-nondeterminism"
        super().__init__(safe_reason)
        self.reason_code = safe_reason


class _RedactedArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        del message
        raise AuditContractError("semantic-nondeterminism")


@dataclass(frozen=True)
class AuditInputs:
    root: Path
    active_java_roots: tuple[str, ...]
    git_head_input: Path
    git_branch_input: Path
    git_paths_input: Path
    git_status_input: Path
    harmony_input: Path
    test_tree_input: Path
    dup_fqcn_input: Path
    closure_wave_registry: Path
    git_skip_worktree_input: Path | None = None
    candidate_cap: int = DEFAULT_CANDIDATE_CAP


@dataclass(frozen=True)
class AuditOutputs:
    metrics_output: Path
    baseline_output: Path
    ledger_output: Path


@dataclass(frozen=True)
class ClosureWaveDescriptor:
    wave_id: str
    ordinal: int
    journal_path: Path
    proof_root: Path
    source_baseline_id: str
    source_ledger_payload_sha256: str
    source_metrics_semantic_hash: str
    intake_summary_sha256: str
    eligible_groups_sha256: str
    target_preimages_sha256: str


@dataclass(frozen=True)
class ClosureRegistry:
    path: Path
    payload_sha256: str
    waves: tuple[ClosureWaveDescriptor, ...]


@dataclass(frozen=True)
class ClosureWaveIntake:
    schema_version: str
    predecessor_rows: tuple[dict[str, Any], ...]
    targets: dict[str, dict[str, Any]]
    summary: dict[str, Any]
    admission_decision: dict[str, Any] | None
    admission_decision_sha256: str | None
    duplicate_evidence: dict[str, Any] | None
    duplicate_evidence_sha256: str | None


@dataclass(frozen=True)
class ClosureTargetAdmission:
    row: dict[str, Any]
    raw_sha256: str
    proof_pairs: tuple[tuple[str, str], ...]


@dataclass(frozen=True)
class ClosureProgressEntry:
    row: dict[str, Any]
    raw_sha256: str
    target_admission: ClosureTargetAdmission | None


@dataclass(frozen=True)
class ClosureWaveHistory:
    descriptor: ClosureWaveDescriptor
    predecessor_rows: tuple[dict[str, Any], ...]
    all_events: tuple[dict[str, Any], ...]
    active_events: tuple[dict[str, Any], ...]
    journal_payload_sha256: str
    proof_pairs: tuple[tuple[str, str], ...]
    target_admissions: tuple[dict[str, Any], ...] = ()


@dataclass(frozen=True)
class ClosureHistory:
    predecessor_rows: tuple[dict[str, Any], ...]
    all_events: tuple[dict[str, Any], ...]
    active_events: tuple[dict[str, Any], ...]
    wave_registry_sha256: str
    wave_count: int
    journal_set_sha256: str
    proof_set_sha256: str
    event_count: int
    rejected_false_positive_root_cause_groups: int
    verified_closed_root_cause_groups: int

    @property
    def summary(self) -> dict[str, Any]:
        return {
            "schemaVersion": CLOSURE_HISTORY_SCHEMA,
            "waveRegistrySha256": self.wave_registry_sha256,
            "waveCount": self.wave_count,
            "journalSetSha256": self.journal_set_sha256,
            "proofSetSha256": self.proof_set_sha256,
            "eventCount": self.event_count,
            "rejectedFalsePositiveRootCauseGroups": (
                self.rejected_false_positive_root_cause_groups
            ),
            "verifiedClosedRootCauseGroups": self.verified_closed_root_cause_groups,
        }


@dataclass(frozen=True)
class AuditBundle:
    baseline: dict[str, Any]
    metrics: dict[str, Any]
    ledger_rows: tuple[dict[str, Any], ...]
    root: Path
    closure_registry: ClosureRegistry


def sha256_hex(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def canonical_json_bytes(value: object) -> bytes:
    try:
        rendered = json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
    except (TypeError, ValueError) as exc:
        raise AuditContractError("semantic-nondeterminism") from exc
    return (rendered + "\n").encode("utf-8")


def canonical_ndjson_bytes(rows: Sequence[dict[str, Any]]) -> bytes:
    return b"".join(canonical_json_bytes(row) for row in rows)


def _reject_duplicate_object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, child in pairs:
        if not isinstance(key, str) or key in value:
            raise ValueError("duplicate-or-invalid-key")
        value[key] = child
    return value


def _reject_json_constant(value: str) -> None:
    del value
    raise ValueError("nonfinite-json-number")


def _parse_canonical_json_bytes(payload: bytes, reason: str) -> Any:
    try:
        decoded = payload.decode("utf-8")
        value = json.loads(
            decoded,
            object_pairs_hook=_reject_duplicate_object_pairs,
            parse_constant=_reject_json_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise AuditContractError(reason) from exc
    if canonical_json_bytes(value) != payload:
        raise AuditContractError(reason)
    return value


def _parse_canonical_ndjson_bytes(payload: bytes, reason: str) -> tuple[dict[str, Any], ...]:
    if not payload:
        return ()
    if not payload.endswith(b"\n"):
        raise AuditContractError(reason)
    raw_lines = payload.split(b"\n")
    if raw_lines[-1] != b"" or any(not line for line in raw_lines[:-1]):
        raise AuditContractError(reason)
    rows: list[dict[str, Any]] = []
    for line in raw_lines[:-1]:
        value = _parse_canonical_json_bytes(line + b"\n", reason)
        if not isinstance(value, dict):
            raise AuditContractError(reason)
        rows.append(value)
    return tuple(rows)


def _is_lower_hex64(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def _is_safe_branch_token(value: object) -> bool:
    return (
        isinstance(value, str)
        and re.fullmatch(r"[A-Za-z0-9._/-]+", value) is not None
        and not value.startswith("/")
        and not value.endswith("/")
        and ".." not in value.split("/")
    )


def _normalize_repo_path(value: object) -> str:
    if not isinstance(value, str) or not value or value.strip() != value:
        raise AuditContractError("path-invalid")
    if any(ord(character) < 32 for character in value) or "://" in value:
        raise AuditContractError("path-invalid")
    windows = PureWindowsPath(value)
    normalized = value.replace("\\", "/")
    if windows.is_absolute() or windows.drive or normalized.startswith("/"):
        raise AuditContractError("path-invalid")
    raw_parts = normalized.split("/")
    if not raw_parts or any(part in {"", ".", ".."} for part in raw_parts):
        raise AuditContractError("path-invalid")
    pure = PurePosixPath(normalized)
    if pure.is_absolute() or any(part in {"", ".", ".."} for part in pure.parts):
        raise AuditContractError("path-invalid")
    return pure.as_posix()


def _public_repo_path(value: object) -> str:
    normalized = _normalize_repo_path(value)
    if SECRET_PATTERN.search(normalized):
        token = REDACTED_PATH_PREFIX + sha256_hex(normalized.encode("utf-8"))
        for active_root in ACTIVE_JAVA_ROOTS:
            prefix = active_root + "/"
            if normalized.casefold().startswith(prefix.casefold()):
                return prefix + token
        return token
    return normalized


def _public_manifest_rows(
    rows: Sequence[dict[str, Any]],
) -> tuple[dict[str, Any], ...]:
    public_rows: list[dict[str, Any]] = []
    identities: set[str] = set()
    for row in rows:
        public_path = _public_repo_path(row["path"])
        identity = public_path.casefold()
        if identity in identities:
            raise AuditContractError("path-case-collision")
        identities.add(identity)
        public_row = dict(row)
        public_row["path"] = public_path
        public_rows.append(public_row)
    public_rows.sort(key=lambda row: (row["path"].casefold(), row["path"]))
    return tuple(public_rows)


def _public_symbol(value: object) -> str:
    if not isinstance(value, str) or not value or value.strip() != value:
        raise AuditContractError("public-artifact-secret-hit")
    if SECRET_PATTERN.search(value):
        return REDACTED_SYMBOL_PREFIX + sha256_hex(value.encode("utf-8"))
    return value


def _is_generated_path(path: str) -> bool:
    normalized = _normalize_repo_path(path).casefold()
    if any(normalized.startswith(prefix) for prefix in GENERATED_PATH_PREFIXES):
        return True
    segments = normalized.split("/")
    if "__pycache__" in segments or normalized.endswith(".pyc"):
        return True
    if any(segments[0].startswith(prefix) for prefix in GENERATED_TOP_LEVEL_PREFIXES):
        return True
    if segments[0] in GENERATED_TOP_LEVEL_NAMES:
        return True
    if len(segments) >= 2 and segments[0] == "var":
        if segments[1].startswith("gradle") or "project-cache" in segments[1]:
            return True
    for generated in GENERATED_VERIFICATION_PATHS:
        if (
            normalized == generated
            or normalized.startswith(generated + ".tmp")
            or normalized.startswith(generated + ".rollback")
        ):
            return True
    return False


def _has_reparse_attribute(path: Path) -> bool:
    try:
        stat_result = path.lstat()
    except OSError:
        return False
    attributes = getattr(stat_result, "st_file_attributes", 0)
    return bool(attributes & getattr(stat_result, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400))


def _is_stat_confirmed_empty_untracked_file(root: Path, candidate: Path) -> bool:
    if _has_reparse_component(root, candidate):
        return False
    try:
        link_stat = candidate.lstat()
        target_stat = candidate.stat()
    except OSError:
        return False
    return (
        stat.S_ISREG(link_stat.st_mode)
        and stat.S_ISREG(target_stat.st_mode)
        and link_stat.st_size == 0
        and target_stat.st_size == 0
    )


def _has_reparse_component(root: Path, candidate: Path) -> bool:
    try:
        root_absolute = root.absolute()
        candidate_absolute = candidate.absolute()
        relative = candidate_absolute.relative_to(root_absolute)
    except (OSError, ValueError):
        return True
    current = root_absolute
    if current.is_symlink() or _has_reparse_attribute(current):
        return True
    for part in relative.parts:
        current = current / part
        if current.exists() and (current.is_symlink() or _has_reparse_attribute(current)):
            return True
    return False


def _safe_resolved_child(root: Path, relative: str, *, require_exists: bool = True) -> Path:
    normalized = _normalize_repo_path(relative)
    root_resolved = root.resolve(strict=True)
    candidate = root_resolved.joinpath(*PurePosixPath(normalized).parts)
    if _has_reparse_component(root_resolved, candidate):
        raise AuditContractError("reparse-path-risk")
    try:
        resolved = candidate.resolve(strict=require_exists)
        resolved.relative_to(root_resolved)
    except (OSError, ValueError) as exc:
        reason = "active-root-missing" if require_exists else "path-invalid"
        raise AuditContractError(reason) from exc
    return resolved


def _count_selfask_compatibility_copy_direct_callers(
    *,
    root: Path,
    compatibility_copy_path: str,
) -> int:
    expected_path = "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java"
    try:
        if compatibility_copy_path != expected_path:
            raise AuditContractError("closure-admission-invalid")
        root_resolved = Path(root).resolve(strict=True)
        if not root_resolved.is_dir():
            raise AuditContractError("closure-admission-invalid")
        active_root = _safe_resolved_child(
            root_resolved,
            "app/src/main/java_clean",
        )
        if not active_root.is_dir():
            raise AuditContractError("closure-admission-invalid")
        declaration = _safe_resolved_child(root_resolved, expected_path)
        if not declaration.is_file() or _has_reparse_component(active_root, declaration):
            raise AuditContractError("closure-admission-invalid")

        java_files: list[tuple[str, Path]] = []
        for current, directories, filenames in os.walk(active_root, followlinks=False):
            current_path = Path(current)
            if _has_reparse_component(active_root, current_path):
                raise AuditContractError("closure-admission-invalid")
            retained_directories: list[str] = []
            for directory in directories:
                candidate = current_path / directory
                if _has_reparse_component(active_root, candidate):
                    raise AuditContractError("closure-admission-invalid")
                retained_directories.append(directory)
            directories[:] = retained_directories
            for filename in filenames:
                candidate = current_path / filename
                if candidate.suffix != ".java":
                    continue
                if _has_reparse_component(active_root, candidate) or not candidate.is_file():
                    raise AuditContractError("closure-admission-invalid")
                resolved = candidate.resolve(strict=True)
                resolved.relative_to(active_root)
                relative = _normalize_repo_path(
                    resolved.relative_to(root_resolved).as_posix()
                )
                java_files.append((relative, resolved))
        java_files.sort(key=lambda item: (item[0].casefold(), item[0]))

        count = 0
        declaration_identity = _closure_path_key(declaration)
        for _, candidate in java_files:
            if _closure_path_key(candidate) == declaration_identity:
                continue
            text = candidate.read_bytes().decode("utf-8")
            stripped = strip_java_comments_and_strings_preserve_lines(text)
            if re.search(r"\bSelfAskPlanner\b", stripped) is not None:
                count += 1
        return count
    except AuditContractError as exc:
        if exc.reason_code == "closure-admission-invalid":
            raise
        raise AuditContractError("closure-admission-invalid") from exc
    except (OSError, TypeError, UnicodeDecodeError, ValueError) as exc:
        raise AuditContractError("closure-admission-invalid") from exc


def _resolve_registry_descriptor_path(
    root: Path,
    value: object,
    *,
    expected_kind: str,
) -> Path:
    try:
        normalized = _normalize_repo_path(value)
    except AuditContractError as exc:
        raise AuditContractError("closure-registry-malformed") from exc
    if normalized != value or any(character in normalized for character in "*?[]"):
        raise AuditContractError("closure-registry-malformed")
    if expected_kind == "file" and not normalized.endswith(".jsonl"):
        raise AuditContractError("closure-registry-malformed")
    candidate = root.joinpath(*PurePosixPath(normalized).parts)
    if _has_reparse_component(root, candidate):
        raise AuditContractError("closure-registry-malformed")
    try:
        resolved = candidate.resolve(strict=False)
        resolved.relative_to(root)
    except (OSError, ValueError) as exc:
        raise AuditContractError("closure-registry-malformed") from exc
    if expected_kind == "file":
        valid_kind = not resolved.exists() or resolved.is_file()
    elif expected_kind == "directory":
        valid_kind = not resolved.exists() or resolved.is_dir()
    else:
        valid_kind = False
    if not valid_kind:
        raise AuditContractError("closure-registry-malformed")
    return resolved


def _registry_path_identity(path: Path) -> str:
    return str(path).replace("\\", "/").casefold()


def load_closure_registry(*, root: Path, registry_path: Path) -> ClosureRegistry:
    try:
        root_resolved = Path(root).resolve(strict=True)
    except (OSError, TypeError, ValueError) as exc:
        raise AuditContractError("closure-registry-malformed") from exc
    if not root_resolved.is_dir():
        raise AuditContractError("closure-registry-malformed")

    if not isinstance(registry_path, Path) or registry_path.is_absolute():
        raise AuditContractError("closure-registry-malformed")
    if registry_path.as_posix() != CLOSURE_WAVE_REGISTRY_RELATIVE.as_posix():
        raise AuditContractError("closure-registry-malformed")

    candidate = root_resolved.joinpath(*CLOSURE_WAVE_REGISTRY_RELATIVE.parts)
    if _has_reparse_component(root_resolved, candidate):
        raise AuditContractError("closure-registry-malformed")
    if not candidate.exists():
        raise AuditContractError("closure-registry-missing")
    if not candidate.is_file():
        raise AuditContractError("closure-registry-malformed")
    try:
        resolved_registry = candidate.resolve(strict=True)
        resolved_registry.relative_to(root_resolved)
        payload = resolved_registry.read_bytes()
    except (OSError, ValueError) as exc:
        raise AuditContractError("closure-registry-missing") from exc

    value = _parse_canonical_json_bytes(payload, "closure-registry-malformed")
    if (
        not isinstance(value, dict)
        or set(value) != CLOSURE_WAVE_REGISTRY_FIELDS
        or value.get("schemaVersion") != CLOSURE_WAVE_REGISTRY_SCHEMA
        or not isinstance(value.get("waves"), list)
    ):
        raise AuditContractError("closure-registry-malformed")

    payload_sha256 = sha256_hex(payload)
    if payload_sha256 != FROZEN_CLOSURE_WAVE_REGISTRY_SHA256:
        raise AuditContractError("closure-registry-mismatch")

    descriptors: list[ClosureWaveDescriptor] = []
    journal_identities: set[str] = set()
    proof_identities: set[str] = set()
    for expected_ordinal, row in enumerate(value["waves"], start=1):
        if not isinstance(row, dict) or set(row) != CLOSURE_WAVE_DESCRIPTOR_FIELDS:
            raise AuditContractError("closure-registry-malformed")
        ordinal = row.get("ordinal")
        wave_id = row.get("waveId")
        if (
            isinstance(ordinal, bool)
            or not isinstance(ordinal, int)
            or ordinal <= 0
            or ordinal != expected_ordinal
            or not isinstance(wave_id, str)
            or re.fullmatch(r"wave-[0-9]{4}", wave_id) is None
            or wave_id != f"wave-{ordinal:04d}"
        ):
            raise AuditContractError("closure-registry-malformed")
        if any(not _is_lower_hex64(row.get(field)) for field in CLOSURE_WAVE_DESCRIPTOR_HASH_FIELDS):
            raise AuditContractError("closure-registry-malformed")

        journal = _resolve_registry_descriptor_path(
            root_resolved,
            row.get("journalPath"),
            expected_kind="file",
        )
        proof_root = _resolve_registry_descriptor_path(
            root_resolved,
            row.get("proofRoot"),
            expected_kind="directory",
        )
        journal_identity = _registry_path_identity(journal)
        proof_identity = _registry_path_identity(proof_root)
        if (
            journal_identity in journal_identities
            or proof_identity in proof_identities
            or journal_identity in proof_identities
            or proof_identity in journal_identities
        ):
            raise AuditContractError("closure-registry-malformed")
        journal_identities.add(journal_identity)
        proof_identities.add(proof_identity)
        descriptors.append(
            ClosureWaveDescriptor(
                wave_id=wave_id,
                ordinal=ordinal,
                journal_path=journal,
                proof_root=proof_root,
                source_baseline_id=row["sourceBaselineId"],
                source_ledger_payload_sha256=row["sourceLedgerPayloadSha256"],
                source_metrics_semantic_hash=row["sourceMetricsSemanticHash"],
                intake_summary_sha256=row["intakeSummarySha256"],
                eligible_groups_sha256=row["eligibleGroupsSha256"],
                target_preimages_sha256=row["targetPreimagesSha256"],
            )
        )

    return ClosureRegistry(
        path=resolved_registry,
        payload_sha256=payload_sha256,
        waves=tuple(descriptors),
    )


def _validated_audit_registry(
    *,
    root: Path,
    supplied_path: Path,
) -> ClosureRegistry:
    expected = root.joinpath(*CLOSURE_WAVE_REGISTRY_RELATIVE.parts)
    if (
        not isinstance(supplied_path, Path)
        or not supplied_path.is_absolute()
        or _closure_path_key(supplied_path) != _closure_path_key(expected)
        or _has_reparse_component(root, expected)
    ):
        raise AuditContractError("closure-registry-malformed")
    registry = load_closure_registry(
        root=root,
        registry_path=CLOSURE_WAVE_REGISTRY_RELATIVE,
    )
    if _closure_path_key(registry.path) != _closure_path_key(expected):
        raise AuditContractError("closure-registry-mismatch")
    return registry


def _registry_manifest_exclusions(
    *,
    root: Path,
    registry: ClosureRegistry,
) -> frozenset[str]:
    root_resolved, descriptors = _validated_aggregate_wave_paths(
        root=root,
        registry=registry,
    )
    paths = [registry.path, *(descriptor.journal_path for descriptor in descriptors)]
    try:
        relatives = frozenset(
            path.relative_to(root_resolved).as_posix() for path in paths
        )
    except ValueError as exc:
        raise AuditContractError("closure-registry-mismatch") from exc
    if len(relatives) != len(paths):
        raise AuditContractError("closure-wave-conflict")
    return relatives


def _decode_nul_tokens(raw: bytes) -> list[str]:
    if not raw:
        return []
    if not raw.endswith(b"\0"):
        raise AuditContractError("git-capture-malformed")
    try:
        decoded = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise AuditContractError("git-capture-malformed") from exc
    tokens = decoded.split("\0")
    if tokens[-1] != "":
        raise AuditContractError("git-capture-malformed")
    tokens.pop()
    if any(token == "" for token in tokens):
        raise AuditContractError("git-capture-malformed")
    return tokens


def _state_from_xy(xy: str) -> str:
    if len(xy) != 2:
        raise AuditContractError("git-capture-malformed")
    if "D" in xy:
        return "deleted"
    return "modified"


def parse_porcelain_v2_z(raw: bytes) -> tuple[dict[str, Any], ...]:
    tokens = _decode_nul_tokens(raw)
    records: list[dict[str, Any]] = []
    identities: dict[str, str] = {}
    index = 0
    while index < len(tokens):
        token = tokens[index]
        index += 1
        record: dict[str, Any]
        if token.startswith("1 "):
            fields = token.split(" ", 8)
            if len(fields) != 9:
                raise AuditContractError("git-capture-malformed")
            record = {
                "path": _normalize_repo_path(fields[8]),
                "gitState": _state_from_xy(fields[1]),
            }
        elif token.startswith("2 "):
            fields = token.split(" ", 9)
            if len(fields) != 10 or index >= len(tokens):
                raise AuditContractError("git-capture-malformed")
            original = _normalize_repo_path(tokens[index])
            index += 1
            record = {
                "path": _normalize_repo_path(fields[9]),
                "gitState": "renamed",
                "originalPath": original,
            }
        elif token.startswith("u "):
            fields = token.split(" ", 10)
            if len(fields) != 11:
                raise AuditContractError("git-capture-malformed")
            record = {"path": _normalize_repo_path(fields[10]), "gitState": "unmerged"}
        elif token.startswith("? "):
            record = {"path": _normalize_repo_path(token[2:]), "gitState": "untracked"}
        elif token.startswith("! "):
            raise AuditContractError("git-capture-malformed")
        else:
            raise AuditContractError("git-capture-malformed")
        identity = record["path"].casefold()
        if identity in identities:
            raise AuditContractError("path-case-collision")
        identities[identity] = record["path"]
        records.append(record)
    records.sort(key=lambda row: (row["path"].casefold(), row["path"]))
    return tuple(records)


def _parse_git_paths(raw: bytes) -> tuple[str, ...]:
    paths = [_normalize_repo_path(token) for token in _decode_nul_tokens(raw)]
    identities: dict[str, str] = {}
    for path in paths:
        identity = path.casefold()
        if identity in identities:
            raise AuditContractError("path-case-collision")
        identities[identity] = path
    return tuple(paths)


def _validate_skip_worktree_fallbacks(
    value: Mapping[str, dict[str, Any]] | None,
) -> dict[str, dict[str, Any]]:
    if value is None:
        return {}
    if not isinstance(value, Mapping):
        raise AuditContractError("git-capture-malformed")
    normalized: dict[str, dict[str, Any]] = {}
    for raw_path, raw_row in value.items():
        path = _normalize_repo_path(raw_path)
        if raw_path != path or not isinstance(raw_row, dict):
            raise AuditContractError("git-capture-malformed")
        if set(raw_row) != {"sizeBytes", "contentSha256"}:
            raise AuditContractError("git-capture-malformed")
        size = raw_row["sizeBytes"]
        content_hash = raw_row["contentSha256"]
        if (
            not isinstance(size, int)
            or isinstance(size, bool)
            or size < 0
            or not isinstance(content_hash, str)
            or re.fullmatch(r"[0-9a-f]{64}", content_hash) is None
        ):
            raise AuditContractError("git-capture-malformed")
        identity = path.casefold()
        if identity in normalized:
            raise AuditContractError("path-case-collision")
        normalized[identity] = {
            "path": path,
            "sizeBytes": size,
            "contentSha256": content_hash,
        }
    return normalized


def load_skip_worktree_fallbacks(path: Path) -> dict[str, dict[str, Any]]:
    try:
        payload = json.loads(_read_required_bytes(path).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AuditContractError("git-capture-malformed") from exc
    if (
        not isinstance(payload, dict)
        or set(payload) != {"schemaVersion", "rows"}
        or payload.get("schemaVersion") != GIT_SKIP_WORKTREE_SCHEMA
        or not isinstance(payload.get("rows"), list)
    ):
        raise AuditContractError("git-capture-malformed")
    raw_fallbacks: dict[str, dict[str, Any]] = {}
    for row in payload["rows"]:
        if not isinstance(row, dict) or set(row) != {"path", "sizeBytes", "contentSha256"}:
            raise AuditContractError("git-capture-malformed")
        path_value = row["path"]
        if not isinstance(path_value, str) or path_value in raw_fallbacks:
            raise AuditContractError("git-capture-malformed")
        raw_fallbacks[path_value] = {
            "sizeBytes": row["sizeBytes"],
            "contentSha256": row["contentSha256"],
        }
    validated = _validate_skip_worktree_fallbacks(raw_fallbacks)
    return {
        row["path"]: {
            "sizeBytes": row["sizeBytes"],
            "contentSha256": row["contentSha256"],
        }
        for row in validated.values()
    }


def build_workspace_manifest(
    root: Path,
    paths_raw: bytes,
    status_raw: bytes,
    skip_worktree_fallbacks: Mapping[str, dict[str, Any]] | None = None,
    exact_generated_paths: frozenset[str] = frozenset(),
) -> tuple[dict[str, Any], ...]:
    root = Path(root).resolve(strict=True)
    try:
        exact_generated_identities = frozenset(
            _normalize_repo_path(path).casefold() for path in exact_generated_paths
        )
    except (TypeError, AuditContractError) as exc:
        raise AuditContractError("semantic-nondeterminism") from exc
    if len(exact_generated_identities) != len(exact_generated_paths):
        raise AuditContractError("path-case-collision")
    captured_paths = _parse_git_paths(paths_raw)
    status_records = parse_porcelain_v2_z(status_raw)
    fallback_by_identity = _validate_skip_worktree_fallbacks(skip_worktree_fallbacks)
    used_fallbacks: set[str] = set()
    all_paths: dict[str, str] = {}

    def register(path: str) -> None:
        identity = path.casefold()
        existing = all_paths.get(identity)
        if existing is not None and existing != path:
            raise AuditContractError("path-case-collision")
        all_paths[identity] = path

    for path in captured_paths:
        register(path)
    status_by_identity: dict[str, dict[str, Any]] = {}
    rename_originals: dict[str, str] = {}
    for record in status_records:
        register(record["path"])
        status_by_identity[record["path"].casefold()] = record
        original = record.get("originalPath")
        if original is not None:
            register(original)
            rename_originals[original.casefold()] = original

    rows: list[dict[str, Any]] = []
    empty_hash = sha256_hex(b"")
    for identity, path in sorted(all_paths.items(), key=lambda item: (item[1].casefold(), item[1])):
        if _is_generated_path(path) or identity in exact_generated_identities:
            continue
        record = status_by_identity.get(identity)
        state = record["gitState"] if record is not None else "clean"
        if identity in rename_originals:
            state = "deleted"
        candidate = root.joinpath(*PurePosixPath(path).parts)
        if state == "deleted":
            size = 0
            content_hash = empty_hash
        else:
            if _has_reparse_component(root, candidate):
                raise AuditContractError("reparse-path-risk")
            try:
                resolved = candidate.resolve(strict=True)
                resolved.relative_to(root)
            except (OSError, ValueError) as exc:
                fallback = fallback_by_identity.get(identity)
                if state == "clean" and fallback is not None:
                    size = fallback["sizeBytes"]
                    content_hash = fallback["contentSha256"]
                    used_fallbacks.add(identity)
                    resolved = None
                elif state == "untracked" and _is_stat_confirmed_empty_untracked_file(
                    root, candidate
                ):
                    size = 0
                    content_hash = empty_hash
                    resolved = None
                else:
                    raise AuditContractError("git-capture-malformed") from exc
            if resolved is None:
                pass
            elif not resolved.is_file():
                raise AuditContractError("git-capture-malformed")
            else:
                try:
                    payload = resolved.read_bytes()
                except OSError as exc:
                    fallback = fallback_by_identity.get(identity)
                    if state == "clean" and fallback is not None:
                        size = fallback["sizeBytes"]
                        content_hash = fallback["contentSha256"]
                        used_fallbacks.add(identity)
                    elif state == "untracked" and _is_stat_confirmed_empty_untracked_file(
                        root, candidate
                    ):
                        size = 0
                        content_hash = empty_hash
                    else:
                        raise AuditContractError("git-capture-malformed") from exc
                else:
                    size = len(payload)
                    content_hash = sha256_hex(payload)
        rows.append(
            {
                "path": path,
                "gitState": state,
                "sizeBytes": size,
                "contentSha256": content_hash,
            }
        )
    if used_fallbacks != set(fallback_by_identity):
        raise AuditContractError("git-capture-malformed")
    return tuple(rows)


def _read_required_bytes(path: Path) -> bytes:
    try:
        candidate = Path(path)
        if not candidate.is_file():
            raise AuditContractError("required-input-missing")
        return candidate.read_bytes()
    except AuditContractError:
        raise
    except OSError as exc:
        raise AuditContractError("required-input-missing") from exc


def _aware_utc(value: datetime) -> datetime:
    if not isinstance(value, datetime):
        raise AuditContractError("required-input-malformed")
    if value.tzinfo is None:
        local_zone = datetime.now().astimezone().tzinfo or timezone.utc
        value = value.replace(tzinfo=local_zone)
    return value.astimezone(timezone.utc)


def _parse_timestamp(value: object) -> datetime:
    if not isinstance(value, str) or not value or value.strip() != value:
        raise AuditContractError("required-input-malformed")
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise AuditContractError("required-input-malformed") from exc
    return _aware_utc(parsed)


def load_required_input(
    path: Path,
    now: datetime,
    *,
    require_freshness: bool = True,
) -> dict[str, Any]:
    payload = _read_required_bytes(Path(path))
    try:
        decoded = payload.decode("utf-8-sig")
        value = json.loads(decoded)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AuditContractError("required-input-malformed") from exc
    if not isinstance(value, dict):
        raise AuditContractError("required-input-malformed")
    if require_freshness:
        generated_at = _parse_timestamp(value.get("generatedAt"))
        current = _aware_utc(now)
        age = current - generated_at
        if age > timedelta(hours=24) or age < timedelta(minutes=-5):
            raise AuditContractError("required-input-stale")
    return value


def _is_nonnegative_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def _is_nonnegative_number(value: object) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(float(value))
        and float(value) >= 0.0
    )


def _require_nonnegative_int(value: object, reason: str = "required-input-malformed") -> int:
    if not _is_nonnegative_int(value):
        raise AuditContractError(reason)
    return int(value)


def _require_nonnegative_number(
    value: object,
    reason: str = "required-input-malformed",
) -> int | float:
    if not _is_nonnegative_number(value):
        raise AuditContractError(reason)
    return value  # Preserve integer-valued producer fields as integers.


def _validate_harmony_input(payload: dict[str, Any]) -> dict[str, Any]:
    summary: dict[str, int | float] = {}
    integer_summary_fields = {
        "aspectFiles",
        "criticalUnorderedAspectCount",
        "unorderedAspectCount",
        "crossSubsystemLargeFilesOver1000",
        "runtimeCrossSubsystemLargeFilesOver1000",
    }
    for field in HARMONY_SUMMARY_FIELDS:
        value = payload.get(field)
        if field in integer_summary_fields:
            summary[field] = _require_nonnegative_int(value)
        else:
            numeric = _require_nonnegative_number(value)
            if field.endswith("Ratio") or field.endswith("CoverageApprox"):
                if float(numeric) > 1.0:
                    raise AuditContractError("required-input-malformed")
            summary[field] = numeric

    secret_hits = _require_nonnegative_int(payload.get("secretPatternHits"))
    evidence = payload.get("ledgerEvidence")
    expected_evidence_fields = {
        "runtimeCrossSubsystemLargeFiles",
        "broadCatchWithoutLocalBreadcrumbFiles",
        "manualPromptCandidateFiles",
    }
    if not isinstance(evidence, dict) or set(evidence) != expected_evidence_fields:
        raise AuditContractError("required-input-malformed")

    runtime_rows: list[dict[str, Any]] = []
    runtime_seen: set[str] = set()
    raw_runtime = evidence["runtimeCrossSubsystemLargeFiles"]
    if not isinstance(raw_runtime, list):
        raise AuditContractError("required-input-malformed")
    for raw_row in raw_runtime:
        if not isinstance(raw_row, dict) or set(raw_row) != {
            "file",
            "lines",
            "subsystems",
            "hitScore",
        }:
            raise AuditContractError("required-input-malformed")
        path = _normalize_repo_path(raw_row["file"])
        if path.casefold() in runtime_seen:
            raise AuditContractError("required-input-malformed")
        runtime_seen.add(path.casefold())
        subsystems = raw_row["subsystems"]
        if (
            not isinstance(subsystems, list)
            or not all(isinstance(item, str) and item and item.strip() == item for item in subsystems)
            or len({item.casefold() for item in subsystems}) != len(subsystems)
        ):
            raise AuditContractError("required-input-malformed")
        runtime_rows.append(
            {
                "file": path,
                "lines": _require_nonnegative_int(raw_row["lines"]),
                "subsystems": sorted(subsystems, key=lambda item: (item.casefold(), item)),
                "hitScore": _require_nonnegative_int(raw_row["hitScore"]),
            }
        )

    broad_rows: list[dict[str, Any]] = []
    broad_seen: set[str] = set()
    raw_broad = evidence["broadCatchWithoutLocalBreadcrumbFiles"]
    if not isinstance(raw_broad, list):
        raise AuditContractError("required-input-malformed")
    for raw_row in raw_broad:
        if not isinstance(raw_row, dict) or set(raw_row) != {
            "file",
            "lines",
            "broadCatchBlocks",
            "broadCatchWithoutLocalBreadcrumbApprox",
        }:
            raise AuditContractError("required-input-malformed")
        path = _normalize_repo_path(raw_row["file"])
        if path.casefold() in broad_seen:
            raise AuditContractError("required-input-malformed")
        broad_seen.add(path.casefold())
        broad_blocks = _require_nonnegative_int(raw_row["broadCatchBlocks"])
        without_breadcrumb = _require_nonnegative_int(
            raw_row["broadCatchWithoutLocalBreadcrumbApprox"]
        )
        if without_breadcrumb > broad_blocks:
            raise AuditContractError("required-input-malformed")
        broad_rows.append(
            {
                "file": path,
                "lines": _require_nonnegative_int(raw_row["lines"]),
                "broadCatchBlocks": broad_blocks,
                "broadCatchWithoutLocalBreadcrumbApprox": without_breadcrumb,
            }
        )

    manual_rows: list[dict[str, Any]] = []
    manual_seen: set[str] = set()
    raw_manual = evidence["manualPromptCandidateFiles"]
    if not isinstance(raw_manual, list):
        raise AuditContractError("required-input-malformed")
    for raw_row in raw_manual:
        if not isinstance(raw_row, dict) or set(raw_row) != {"file", "lines"}:
            raise AuditContractError("required-input-malformed")
        path = _normalize_repo_path(raw_row["file"])
        if path.casefold() in manual_seen:
            raise AuditContractError("required-input-malformed")
        manual_seen.add(path.casefold())
        manual_rows.append(
            {"file": path, "lines": _require_nonnegative_int(raw_row["lines"])}
        )

    for rows in (runtime_rows, broad_rows, manual_rows):
        rows.sort(key=lambda row: (row["file"].casefold(), row["file"]))
    return {
        "summary": summary,
        "secretPatternHits": secret_hits,
        "runtimeRows": runtime_rows,
        "broadRows": broad_rows,
        "manualRows": manual_rows,
    }


def _validate_test_tree_input(payload: dict[str, Any]) -> dict[str, int | float]:
    summary: dict[str, int | float] = {}
    for field in TEST_TREE_SUMMARY_FIELDS:
        value = payload.get(field)
        if field in {"missingImportCount", "affectedTestFileCount"}:
            summary[field] = _require_nonnegative_int(value)
        else:
            summary[field] = _require_nonnegative_number(value)
    return summary


def _duplicate_semantic_hash(payload: dict[str, Any]) -> str:
    status = payload.get("status", "current")
    lines = [
        f"schemaVersion={payload['schemaVersion']}",
        f"status={status}",
        f"mode={payload['mode']}",
        f"filter={payload['filter']}",
        f"action={payload['action']}",
        f"duplicateFqcnSourceCollisionCount={payload['duplicateFqcnSourceCollisionCount']}",
        f"duplicateFqcnGeneratedExcludeCount={payload['duplicateFqcnGeneratedExcludeCount']}",
        f"duplicateFqcnHardExcludeCount={payload['duplicateFqcnHardExcludeCount']}",
        f"duplicateFqcnPackagedActiveCount={payload['duplicateFqcnPackagedActiveCount']}",
        f"duplicateFqcnActiveCount={payload['duplicateFqcnActiveCount']}",
    ]
    material = "\n".join(lines) + "\n"
    for row in payload["collisions"]:
        material += "collision=" + "\0".join(
            str(row[key])
            for key in (
                "fqcn",
                "rootPath",
                "appPath",
                "packagingState",
                "evidenceFingerprint",
            )
        ) + "\n"
    return sha256_hex(material.encode("utf-8"))


def _validate_duplicate_input(payload: dict[str, Any]) -> dict[str, Any]:
    required_fields = {
        "schemaVersion",
        "generatedAt",
        "mode",
        "filter",
        "action",
        "duplicateFqcnSourceCollisionCount",
        "duplicateFqcnGeneratedExcludeCount",
        "duplicateFqcnHardExcludeCount",
        "duplicateFqcnPackagedActiveCount",
        "duplicateFqcnActiveCount",
        "collisions",
        "semanticHash",
    }
    if set(payload) not in (required_fields, required_fields | {"status"}):
        raise AuditContractError("duplicate-count-inconsistent")
    if payload.get("schemaVersion") != DUP_FQCN_SCHEMA:
        raise AuditContractError("duplicate-count-inconsistent")
    if payload.get("status", "current") != "current":
        raise AuditContractError("duplicate-count-inconsistent")
    for field in ("generatedAt", "mode", "filter", "action"):
        if not isinstance(payload.get(field), str) or not payload[field]:
            raise AuditContractError("duplicate-count-inconsistent")
    _parse_timestamp(payload["generatedAt"])

    count_fields = (
        "duplicateFqcnSourceCollisionCount",
        "duplicateFqcnGeneratedExcludeCount",
        "duplicateFqcnHardExcludeCount",
        "duplicateFqcnPackagedActiveCount",
        "duplicateFqcnActiveCount",
    )
    counts = {
        field: _require_nonnegative_int(payload.get(field), "duplicate-count-inconsistent")
        for field in count_fields
    }
    collisions = payload.get("collisions")
    if not isinstance(collisions, list):
        raise AuditContractError("duplicate-count-inconsistent")
    expected_row_fields = {
        "fqcn",
        "rootPath",
        "appPath",
        "packagingState",
        "evidenceFingerprint",
    }
    normalized_rows: list[dict[str, str]] = []
    seen_fqcns: set[str] = set()
    for raw_row in collisions:
        if not isinstance(raw_row, dict) or set(raw_row) != expected_row_fields:
            raise AuditContractError("duplicate-count-inconsistent")
        fqcn = raw_row.get("fqcn")
        state = raw_row.get("packagingState")
        fingerprint = raw_row.get("evidenceFingerprint")
        if (
            not isinstance(fqcn, str)
            or not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*", fqcn)
            or fqcn in seen_fqcns
            or state not in {"GENERATED_EXCLUDE", "HARD_EXCLUDE", "PACKAGED_ACTIVE"}
            or not isinstance(fingerprint, str)
            or not re.fullmatch(r"[0-9a-f]{64}", fingerprint)
        ):
            raise AuditContractError("duplicate-count-inconsistent")
        seen_fqcns.add(fqcn)
        root_path = _normalize_repo_path(raw_row.get("rootPath"))
        app_path = _normalize_repo_path(raw_row.get("appPath"))
        fqcn_path = fqcn.replace(".", "/") + ".java"
        if root_path != f"main/java/{fqcn_path}" or app_path != f"app/src/main/java_clean/{fqcn_path}":
            raise AuditContractError("duplicate-count-inconsistent")
        material = "\0".join((fqcn, root_path, app_path, state))
        if fingerprint != sha256_hex(material.encode("utf-8")):
            raise AuditContractError("duplicate-count-inconsistent")
        normalized_rows.append(
            {
                "fqcn": fqcn,
                "rootPath": root_path,
                "appPath": app_path,
                "packagingState": state,
                "evidenceFingerprint": fingerprint,
            }
        )
    if normalized_rows != sorted(normalized_rows, key=lambda row: row["fqcn"]):
        raise AuditContractError("duplicate-count-inconsistent")

    generated = sum(row["packagingState"] == "GENERATED_EXCLUDE" for row in normalized_rows)
    hard = sum(row["packagingState"] == "HARD_EXCLUDE" for row in normalized_rows)
    packaged = sum(row["packagingState"] == "PACKAGED_ACTIVE" for row in normalized_rows)
    source = len(normalized_rows)
    if (
        counts["duplicateFqcnSourceCollisionCount"] != source
        or counts["duplicateFqcnGeneratedExcludeCount"] != generated
        or counts["duplicateFqcnHardExcludeCount"] != hard
        or counts["duplicateFqcnPackagedActiveCount"] != packaged
        or counts["duplicateFqcnActiveCount"] != packaged
        or source != generated + hard + packaged
    ):
        raise AuditContractError("duplicate-count-inconsistent")

    normalized_payload = copy.deepcopy(payload)
    normalized_payload["status"] = payload.get("status", "current")
    normalized_payload["collisions"] = normalized_rows
    semantic_hash = payload.get("semanticHash")
    if (
        not isinstance(semantic_hash, str)
        or not re.fullmatch(r"[0-9a-f]{64}", semantic_hash)
        or semantic_hash != _duplicate_semantic_hash(normalized_payload)
    ):
        raise AuditContractError("duplicate-count-inconsistent")
    normalized_payload.update(counts)
    return normalized_payload


def _scan_active_java(
    root: Path,
    active_roots: Sequence[str],
) -> tuple[dict[str, Any], ...]:
    normalized_inputs = tuple(_normalize_repo_path(value) for value in active_roots)
    if len(normalized_inputs) != len(set(value.casefold() for value in normalized_inputs)):
        raise AuditContractError("path-case-collision")
    if {value.casefold() for value in normalized_inputs} != {
        value.casefold() for value in ACTIVE_JAVA_ROOTS
    }:
        raise AuditContractError("active-root-missing")

    root_resolved = Path(root).resolve(strict=True)
    records: list[dict[str, Any]] = []
    seen_paths: dict[str, str] = {}
    for relative_root in ACTIVE_JAVA_ROOTS:
        source_set = ACTIVE_SOURCE_SET_BY_ROOT[relative_root]
        source_root = _safe_resolved_child(root_resolved, relative_root, require_exists=True)
        if not source_root.is_dir():
            raise AuditContractError("active-root-missing")
        try:
            candidates = list(source_root.rglob("*.java"))
        except OSError as exc:
            raise AuditContractError("active-root-missing") from exc
        for candidate in candidates:
            if _has_reparse_component(root_resolved, candidate):
                raise AuditContractError("reparse-path-risk")
            try:
                resolved = candidate.resolve(strict=True)
                resolved.relative_to(source_root)
                relative = resolved.relative_to(root_resolved).as_posix()
                payload = resolved.read_bytes()
            except (OSError, ValueError) as exc:
                raise AuditContractError("reparse-path-risk") from exc
            if not resolved.is_file():
                continue
            normalized = _normalize_repo_path(relative)
            identity = normalized.casefold()
            existing = seen_paths.get(identity)
            if existing is not None and existing != normalized:
                raise AuditContractError("path-case-collision")
            seen_paths[identity] = normalized
            try:
                text = payload.decode("utf-8-sig")
            except UnicodeDecodeError as exc:
                raise AuditContractError("source-decode-failed") from exc
            records.append(
                {
                    "path": normalized,
                    "activeSourceSet": source_set,
                    "symbol": resolved.stem,
                    "lineCount": len(text.splitlines()),
                    "sizeBytes": len(payload),
                }
            )
    records.sort(key=lambda row: (row["path"].casefold(), row["path"]))
    return tuple(records)


EVIDENCE_REASON_BY_CATEGORY = {
    "FILE_SIZE_CONCENTRATION": "active-java-lines-over-2000",
    "CROSS_SUBSYSTEM_CONCENTRATION": "runtime-cross-subsystem-lines-over-1000",
    "BROAD_CATCH_NO_BREADCRUMB": "broad-catch-without-local-breadcrumb",
    "DUPLICATE_FQCN_SOURCE_COLLISION": "duplicate-source-fqcn",
    "DUPLICATE_FQCN_PACKAGED_ACTIVE": "duplicate-packaged-active-fqcn",
}
BOUNDARY_BY_CATEGORY = {
    "FILE_SIZE_CONCENTRATION": "active-java-structural-owner",
    "CROSS_SUBSYSTEM_CONCENTRATION": "active-java-structural-owner",
    "BROAD_CATCH_NO_BREADCRUMB": "fail-soft-catch-owner",
    "DUPLICATE_FQCN_SOURCE_COLLISION": "app-duplicate-fqcn-owner",
    "DUPLICATE_FQCN_PACKAGED_ACTIVE": "app-duplicate-fqcn-owner",
}
PROOF_COMMAND_BY_CATEGORY = {
    "FILE_SIZE_CONCENTRATION": "python -X utf8 scripts\\test_dynamic_rag_quant_audit.py",
    "CROSS_SUBSYSTEM_CONCENTRATION": "python -X utf8 scripts\\test_harmony_pressure_report.py",
    "BROAD_CATCH_NO_BREADCRUMB": "python -X utf8 scripts\\test_harmony_pressure_report.py",
    "DUPLICATE_FQCN_SOURCE_COLLISION": "gradlew.bat :app:generateDupFqcnExcludes",
    "DUPLICATE_FQCN_PACKAGED_ACTIVE": "gradlew.bat :app:generateDupFqcnExcludes",
}
DUPLICATE_NUMERIC_FIELDS = frozenset(
    {
        "duplicateFqcnSourceCollisionCount",
        "duplicateFqcnGeneratedExcludeCount",
        "duplicateFqcnHardExcludeCount",
        "duplicateFqcnPackagedActiveCount",
    }
)
NUMERIC_FIELDS_BY_CATEGORY = {
    "FILE_SIZE_CONCENTRATION": frozenset({"lineCount"}),
    "CROSS_SUBSYSTEM_CONCENTRATION": frozenset(
        {"lineCount", "subsystemCount", "subsystemHitScore"}
    ),
    "BROAD_CATCH_NO_BREADCRUMB": frozenset({"lineCount", "broadCatchCount"}),
    "DUPLICATE_FQCN_SOURCE_COLLISION": DUPLICATE_NUMERIC_FIELDS,
    "DUPLICATE_FQCN_PACKAGED_ACTIVE": DUPLICATE_NUMERIC_FIELDS,
}


def _normalized_evidence_key(reason: str, numeric_evidence: dict[str, int]) -> str:
    numeric = canonical_json_bytes(numeric_evidence).decode("utf-8").rstrip("\n")
    return f"{reason}|{numeric}"


def _issue_id(
    category: str,
    path: str,
    symbol: str,
    reason: str,
    numeric_evidence: dict[str, int],
) -> str:
    material = " | ".join(
        (
            LEDGER_SCHEMA,
            category,
            path,
            symbol,
            _normalized_evidence_key(reason, numeric_evidence),
        )
    )
    return sha256_hex(material.encode("utf-8"))


def _owner_key(category: str, path: str, symbol: str) -> str:
    if category in {"FILE_SIZE_CONCENTRATION", "CROSS_SUBSYSTEM_CONCENTRATION"}:
        return f"structural-owner:{path}|{symbol}"
    if category == "BROAD_CATCH_NO_BREADCRUMB":
        return f"fail-soft-owner:{path}"
    return f"fqcn-owner:{symbol}"


def _group_id(category: str, path: str, symbol: str) -> str:
    material = " | ".join(
        (LEDGER_SCHEMA, BOUNDARY_BY_CATEGORY[category], _owner_key(category, path, symbol))
    )
    return sha256_hex(material.encode("utf-8"))


def _evidence_fingerprint(
    category: str,
    path: str,
    symbol: str,
    reason: str,
    numeric_evidence: dict[str, int],
) -> str:
    return sha256_hex(
        canonical_json_bytes(
            {
                "category": category,
                "path": path,
                "symbol": symbol,
                "evidenceReason": reason,
                "numericEvidence": numeric_evidence,
            }
        )
    )


def _make_ledger_row(
    *,
    baseline_id: str,
    category: str,
    severity: str,
    active_source_set: str,
    path: str,
    symbol: str,
    numeric_evidence: dict[str, int],
    fix_eligibility: str,
    status: str,
) -> dict[str, Any]:
    reason = EVIDENCE_REASON_BY_CATEGORY[category]
    public_path = _public_repo_path(path)
    public_symbol = _public_symbol(symbol)
    return {
        "schemaVersion": LEDGER_SCHEMA,
        "issueId": _issue_id(category, public_path, public_symbol, reason, numeric_evidence),
        "rootCauseGroupId": _group_id(category, public_path, public_symbol),
        "baselineId": baseline_id,
        "category": category,
        "severity": severity,
        "activeSourceSet": active_source_set,
        "path": public_path,
        "symbol": public_symbol,
        "evidenceReason": reason,
        "evidenceFingerprint": _evidence_fingerprint(
            category, public_path, public_symbol, reason, numeric_evidence
        ),
        "numericEvidence": numeric_evidence,
        "suspectedBoundary": BOUNDARY_BY_CATEGORY[category],
        "fixEligibility": fix_eligibility,
        "proofCommand": PROOF_COMMAND_BY_CATEGORY[category],
        "status": status,
        "supersedes": [],
    }


def _ledger_sort_key(row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        SEVERITY_RANK[row["severity"]],
        ELIGIBILITY_RANK[row["fixEligibility"]],
        row["category"],
        row["path"].casefold(),
        row["path"],
        row["symbol"],
        row["issueId"],
    )


def build_ledger_rows(
    java_records: Sequence[dict[str, Any]],
    harmony: dict[str, Any],
    duplicate_evidence: dict[str, Any],
    baseline_id: str,
) -> tuple[dict[str, Any], ...]:
    if not isinstance(baseline_id, str) or not re.fullmatch(r"[0-9a-f]{64}", baseline_id):
        raise AuditContractError("semantic-nondeterminism")
    by_path = {record["path"].casefold(): record for record in java_records}
    if len(by_path) != len(java_records):
        raise AuditContractError("path-case-collision")
    rows: list[dict[str, Any]] = []

    for record in java_records:
        line_count = record["lineCount"]
        if line_count > FILE_SIZE_THRESHOLD:
            rows.append(
                _make_ledger_row(
                    baseline_id=baseline_id,
                    category="FILE_SIZE_CONCENTRATION",
                    severity="HIGH" if line_count > 4000 else "MEDIUM",
                    active_source_set=record["activeSourceSet"],
                    path=record["path"],
                    symbol=record["symbol"],
                    numeric_evidence={"lineCount": line_count},
                    fix_eligibility="REVIEW_REQUIRED",
                    status="HOLD",
                )
            )

    for evidence in harmony["runtimeRows"]:
        record = by_path.get(evidence["file"].casefold())
        if record is None or record["path"] != evidence["file"]:
            raise AuditContractError("required-input-malformed")
        if record["lineCount"] != evidence["lines"]:
            raise AuditContractError("required-input-malformed")
        if evidence["lines"] <= CROSS_SUBSYSTEM_THRESHOLD:
            continue
        subsystem_count = len(evidence["subsystems"])
        numeric = {
            "lineCount": evidence["lines"],
            "subsystemCount": subsystem_count,
            "subsystemHitScore": evidence["hitScore"],
        }
        rows.append(
            _make_ledger_row(
                baseline_id=baseline_id,
                category="CROSS_SUBSYSTEM_CONCENTRATION",
                severity=(
                    "HIGH"
                    if evidence["lines"] > 2000 or subsystem_count >= 4
                    else "MEDIUM"
                ),
                active_source_set=record["activeSourceSet"],
                path=record["path"],
                symbol=record["symbol"],
                numeric_evidence=numeric,
                fix_eligibility="REVIEW_REQUIRED",
                status="HOLD",
            )
        )

    for evidence in harmony["broadRows"]:
        record = by_path.get(evidence["file"].casefold())
        if record is None or record["path"] != evidence["file"]:
            raise AuditContractError("required-input-malformed")
        if record["lineCount"] != evidence["lines"]:
            raise AuditContractError("required-input-malformed")
        broad_count = evidence["broadCatchWithoutLocalBreadcrumbApprox"]
        if broad_count <= 0:
            continue
        rows.append(
            _make_ledger_row(
                baseline_id=baseline_id,
                category="BROAD_CATCH_NO_BREADCRUMB",
                severity="HIGH" if broad_count >= 3 else "MEDIUM",
                active_source_set=record["activeSourceSet"],
                path=record["path"],
                symbol=record["symbol"],
                numeric_evidence={
                    "lineCount": evidence["lines"],
                    "broadCatchCount": broad_count,
                },
                fix_eligibility="ELIGIBLE",
                status="OPEN",
            )
        )

    counts = {
        field: duplicate_evidence[field]
        for field in (
            "duplicateFqcnSourceCollisionCount",
            "duplicateFqcnGeneratedExcludeCount",
            "duplicateFqcnHardExcludeCount",
            "duplicateFqcnPackagedActiveCount",
        )
    }
    for collision in duplicate_evidence["collisions"]:
        root_record = by_path.get(collision["rootPath"].casefold())
        app_record = by_path.get(collision["appPath"].casefold())
        if (
            root_record is None
            or app_record is None
            or root_record["path"] != collision["rootPath"]
            or app_record["path"] != collision["appPath"]
        ):
            raise AuditContractError("duplicate-count-inconsistent")
        row_numeric = {
            "duplicateFqcnSourceCollisionCount": 1,
            "duplicateFqcnGeneratedExcludeCount": int(
                collision["packagingState"] == "GENERATED_EXCLUDE"
            ),
            "duplicateFqcnHardExcludeCount": int(
                collision["packagingState"] == "HARD_EXCLUDE"
            ),
            "duplicateFqcnPackagedActiveCount": int(
                collision["packagingState"] == "PACKAGED_ACTIVE"
            ),
        }
        rows.append(
            _make_ledger_row(
                baseline_id=baseline_id,
                category="DUPLICATE_FQCN_SOURCE_COLLISION",
                severity="HIGH",
                active_source_set=root_record["activeSourceSet"],
                path=root_record["path"],
                symbol=collision["fqcn"],
                numeric_evidence=row_numeric,
                fix_eligibility="REVIEW_REQUIRED",
                status="HOLD",
            )
        )
        if collision["packagingState"] == "PACKAGED_ACTIVE":
            rows.append(
                _make_ledger_row(
                    baseline_id=baseline_id,
                    category="DUPLICATE_FQCN_PACKAGED_ACTIVE",
                    severity="CRITICAL",
                    active_source_set=app_record["activeSourceSet"],
                    path=app_record["path"],
                    symbol=collision["fqcn"],
                    numeric_evidence=row_numeric,
                    fix_eligibility="ELIGIBLE",
                    status="OPEN",
                )
            )

    # Keep a direct equation assertion close to the row projection.  The app task
    # owns discovery; this producer only verifies and projects its classifications.
    if counts["duplicateFqcnSourceCollisionCount"] != sum(
        row["numericEvidence"]["duplicateFqcnSourceCollisionCount"]
        for row in rows
        if row["category"] == "DUPLICATE_FQCN_SOURCE_COLLISION"
    ):
        raise AuditContractError("duplicate-count-inconsistent")

    rows.sort(key=_ledger_sort_key)
    _validate_ledger_core_rows(rows, expected_baseline_id=baseline_id)
    return tuple(rows)


def _expected_category_contract(row: dict[str, Any]) -> tuple[str, str, str]:
    category = row["category"]
    numeric = row["numericEvidence"]
    if category == "FILE_SIZE_CONCENTRATION":
        line_count = numeric["lineCount"]
        severity = "HIGH" if line_count > 4000 else "MEDIUM"
        return severity, "REVIEW_REQUIRED", "HOLD"
    if category == "CROSS_SUBSYSTEM_CONCENTRATION":
        severity = (
            "HIGH"
            if numeric["lineCount"] > 2000 or numeric["subsystemCount"] >= 4
            else "MEDIUM"
        )
        return severity, "REVIEW_REQUIRED", "HOLD"
    if category == "BROAD_CATCH_NO_BREADCRUMB":
        severity = "HIGH" if numeric["broadCatchCount"] >= 3 else "MEDIUM"
        return severity, "ELIGIBLE", "OPEN"
    if category == "DUPLICATE_FQCN_SOURCE_COLLISION":
        return "HIGH", "REVIEW_REQUIRED", "HOLD"
    return "CRITICAL", "ELIGIBLE", "OPEN"


def _validate_historical_overlay_row(
    row: dict[str, Any],
    *,
    predecessor: dict[str, Any],
    current_baseline_id: str,
) -> None:
    if not isinstance(row, dict) or not isinstance(predecessor, dict):
        raise AuditContractError("semantic-nondeterminism")
    accepted = None
    for event_type, terminal in HISTORICAL_TERMINAL_CONTRACT.items():
        if (row.get("fixEligibility"), row.get("status")) == terminal:
            accepted = event_type
            break
    if accepted is None:
        raise AuditContractError("semantic-nondeterminism")
    del accepted
    supersession_hash = sha256_hex(
        "|".join(
            (
                predecessor.get("baselineId", ""),
                predecessor.get("issueId", ""),
                predecessor.get("evidenceFingerprint", ""),
            )
        ).encode("utf-8")
    )
    expected = copy.deepcopy(predecessor)
    expected.update(
        {
            "baselineId": current_baseline_id,
            "fixEligibility": row["fixEligibility"],
            "status": row["status"],
            "supersedes": [supersession_hash],
        }
    )
    if row != expected:
        raise AuditContractError("semantic-nondeterminism")


def _validate_ledger_core_rows(
    rows: Sequence[dict[str, Any]],
    *,
    expected_baseline_id: str | None = None,
    historical_predecessors: Mapping[str, dict[str, Any]] | None = None,
) -> None:
    historical_predecessors = historical_predecessors or {}
    issue_ids: set[str] = set()
    semantic_keys: set[tuple[Any, ...]] = set()
    owners_by_group: dict[str, str] = {}

    # Identity conflicts take precedence over group ownership and link checks.
    for row in rows:
        if not isinstance(row, dict) or set(row) != LEDGER_CORE_FIELDS:
            raise AuditContractError("public-artifact-secret-hit")
        issue_id = row.get("issueId")
        category = row.get("category")
        numeric = row.get("numericEvidence")
        if (
            not isinstance(issue_id, str)
            or not re.fullmatch(r"[0-9a-f]{64}", issue_id)
            or issue_id in issue_ids
            or category not in CATEGORIES
            or not isinstance(numeric, dict)
        ):
            raise AuditContractError("issue-identity-conflict")
        issue_ids.add(issue_id)
        if set(numeric) != NUMERIC_FIELDS_BY_CATEGORY[category] or not all(
            _is_nonnegative_int(value) for value in numeric.values()
        ):
            raise AuditContractError("issue-identity-conflict")
        try:
            path = _normalize_repo_path(row.get("path"))
        except AuditContractError as exc:
            raise AuditContractError("issue-identity-conflict") from exc
        symbol = row.get("symbol")
        reason = row.get("evidenceReason")
        if (
            not isinstance(symbol, str)
            or not symbol
            or symbol.strip() != symbol
            or any(ord(character) < 32 for character in symbol)
            or reason != EVIDENCE_REASON_BY_CATEGORY[category]
        ):
            raise AuditContractError("issue-identity-conflict")
        semantic_key = (
            category,
            path,
            symbol,
            reason,
            canonical_json_bytes(numeric),
        )
        if semantic_key in semantic_keys:
            raise AuditContractError("issue-identity-conflict")
        semantic_keys.add(semantic_key)
        expected_issue = _issue_id(category, path, symbol, reason, numeric)
        expected_fingerprint = _evidence_fingerprint(category, path, symbol, reason, numeric)
        if issue_id != expected_issue or row.get("evidenceFingerprint") != expected_fingerprint:
            raise AuditContractError("issue-identity-conflict")

    for row in rows:
        category = row["category"]
        path = row["path"]
        symbol = row["symbol"]
        group_id = row.get("rootCauseGroupId")
        owner = _owner_key(category, path, symbol)
        expected_group = _group_id(category, path, symbol)
        if (
            not isinstance(group_id, str)
            or not re.fullmatch(r"[0-9a-f]{64}", group_id)
            or group_id != expected_group
        ):
            raise AuditContractError("group-owner-conflict")
        prior_owner = owners_by_group.get(group_id)
        if prior_owner is not None and prior_owner != owner:
            raise AuditContractError("group-owner-conflict")
        owners_by_group[group_id] = owner

        baseline_id = row.get("baselineId")
        if (
            row.get("schemaVersion") != LEDGER_SCHEMA
            or not isinstance(baseline_id, str)
            or not re.fullmatch(r"[0-9a-f]{64}", baseline_id)
            or (expected_baseline_id is not None and baseline_id != expected_baseline_id)
            or row.get("severity") not in SEVERITY_RANK
            or row.get("fixEligibility") not in ELIGIBILITIES
            or row.get("status") not in STATUSES
            or row.get("suspectedBoundary") != BOUNDARY_BY_CATEGORY[category]
            or row.get("proofCommand") not in ALLOWED_PROOF_COMMANDS
        ):
            raise AuditContractError("semantic-nondeterminism")
        predecessor = historical_predecessors.get(row["issueId"])
        if predecessor is None:
            expected_contract = _expected_category_contract(row)
            if (
                (
                    row["severity"],
                    row["fixEligibility"],
                    row["status"],
                )
                != expected_contract
                or row.get("supersedes") != []
            ):
                raise AuditContractError("semantic-nondeterminism")
        else:
            _validate_historical_overlay_row(
                row,
                predecessor=predecessor,
                current_baseline_id=baseline_id,
            )
        active_source_set = row.get("activeSourceSet")
        if active_source_set not in ACTIVE_SOURCE_SET_BY_ROOT.values():
            raise AuditContractError("semantic-nondeterminism")
        expected_prefix = (
            "main/java/" if active_source_set == "rootMain" else "app/src/main/java_clean/"
        )
        if not path.startswith(expected_prefix):
            raise AuditContractError("semantic-nondeterminism")

    if list(rows) != sorted(rows, key=_ledger_sort_key):
        raise AuditContractError("semantic-nondeterminism")


def apply_closure_overlay(
    current_rows: Sequence[dict[str, Any]],
    history: ClosureHistory,
    *,
    baseline_id: str,
) -> tuple[dict[str, Any], ...]:
    if not isinstance(history, ClosureHistory) or not _is_lower_hex64(baseline_id):
        raise AuditContractError("semantic-nondeterminism")
    _validate_ledger_core_rows(current_rows, expected_baseline_id=baseline_id)
    predecessor_by_issue = {row["issueId"]: row for row in history.predecessor_rows}
    historical_predecessors: dict[str, dict[str, Any]] = {}
    current_fingerprints = {row["evidenceFingerprint"] for row in current_rows}
    combined = [copy.deepcopy(row) for row in current_rows]
    for event in history.active_events:
        predecessor = predecessor_by_issue.get(event["sourceIssueId"])
        if (
            predecessor is None
            or predecessor["rootCauseGroupId"] != event["rootCauseGroupId"]
        ):
            raise AuditContractError("closure-intake-mismatch")
        if event["sourceEvidenceFingerprint"] in current_fingerprints:
            raise AuditContractError("closure-fingerprint-active")
        terminal = HISTORICAL_TERMINAL_CONTRACT[event["eventType"]]
        historical = copy.deepcopy(predecessor)
        historical.update(
            {
                "baselineId": baseline_id,
                "fixEligibility": terminal[0],
                "status": terminal[1],
                "supersedes": [_source_supersession_hash(event)],
            }
        )
        _validate_historical_overlay_row(
            historical,
            predecessor=predecessor,
            current_baseline_id=baseline_id,
        )
        historical_predecessors[historical["issueId"]] = predecessor
        combined.append(historical)
    combined.sort(key=_ledger_sort_key)
    _validate_ledger_core_rows(
        combined,
        expected_baseline_id=baseline_id,
        historical_predecessors=historical_predecessors,
    )
    return tuple(combined)


BASELINE_PAYLOAD_FIELDS = frozenset(
    {
        "schemaVersion",
        "canonicalRootHash",
        "canonicalRootPathLength",
        "head",
        "branch",
        "activeSourceSets",
        "activeSourceSetConfigurationHash",
        "trackedPathCount",
        "modifiedPathCount",
        "deletedPathCount",
        "untrackedPathCount",
        "activeJavaFileCount",
        "activeJavaBytesTotal",
        "pathStateContentRows",
        "baselineId",
        "secretPatternHitCount",
        "ignoredArtifactPolicyVersion",
    }
)
METRICS_PAYLOAD_FIELDS = frozenset(
    {
        "schemaVersion",
        "baselineId",
        "activeJavaFileCount",
        "activeJavaLocTotal",
        "activeJavaLocP95",
        "largeActiveFilesOver2000",
        "duplicateFqcnSourceCollisionCount",
        "duplicateFqcnGeneratedExcludeCount",
        "duplicateFqcnHardExcludeCount",
        "duplicateFqcnPackagedActiveCount",
        "duplicateFqcnActiveCount",
        "secretPatternHitCount",
        "harmonyPressureSummary",
        "testTreeContamination",
        "ledgerSummary",
        "closureHistorySummary",
        "runtimeProviderDisabledSmoke",
        "supabaseReadonlySmoke",
    }
)
ENVELOPE_FIELDS = frozenset({"generatedAt", "auditRunId", "artifactLinks"})
ARTIFACT_LINK_FIELDS = frozenset(
    {"baselinePayloadSha256", "ledgerPayloadSha256", "metricsPayloadSha256"}
)
INACTIVE_JAVA_PREFIXES = ("app/src/main/java/",)


def _read_capture_line(path: Path, *, branch: bool = False) -> str:
    payload = _read_required_bytes(path)
    try:
        value = payload.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise AuditContractError("git-capture-malformed") from exc
    lines = value.splitlines()
    if len(lines) != 1 or lines[0].strip() != lines[0] or not lines[0]:
        raise AuditContractError("git-capture-malformed")
    captured = lines[0]
    if branch:
        if (
            not re.fullmatch(r"[A-Za-z0-9._/-]+", captured)
            or captured.startswith("/")
            or captured.endswith("/")
            or ".." in captured.split("/")
        ):
            raise AuditContractError("git-capture-malformed")
    elif not re.fullmatch(r"[0-9a-fA-F]{40}", captured):
        raise AuditContractError("git-capture-malformed")
    return captured.lower() if not branch else captured


def _source_set_configuration_hash() -> str:
    material = "rootMain=main/java\nappMainClean=app/src/main/java_clean\n"
    return sha256_hex(material.encode("utf-8"))


def _compute_baseline_id(
    *,
    head: str,
    branch: str,
    source_set_hash: str,
    manifest_rows: Sequence[dict[str, Any]],
) -> str:
    prefix = "|".join(
        (
            BASELINE_SCHEMA,
            head,
            branch,
            source_set_hash,
            GENERATED_ARTIFACT_POLICY_VERSION,
        )
    ).encode("utf-8") + b"\n"
    return sha256_hex(prefix + canonical_json_bytes(list(manifest_rows)))


def _nearest_rank_p95(line_counts: Sequence[int]) -> float:
    if not line_counts:
        return 0.0
    ordered = sorted(line_counts)
    index = max(0, math.ceil(0.95 * len(ordered)) - 1)
    return float(ordered[index])


def _ledger_summary(
    full_rows: Sequence[dict[str, Any]],
    emitted_rows: Sequence[dict[str, Any]],
) -> dict[str, Any]:
    category_totals = {
        category: sum(row["category"] == category for row in full_rows)
        for category in sorted(CATEGORIES)
    }
    emitted_category_totals = {
        category: sum(row["category"] == category for row in emitted_rows)
        for category in sorted(CATEGORIES)
    }
    total_groups = {row["rootCauseGroupId"] for row in full_rows}
    emitted_groups = {row["rootCauseGroupId"] for row in emitted_rows}
    eligible_rows = [
        row
        for row in full_rows
        if row["fixEligibility"] == "ELIGIBLE" and row["status"] == "OPEN"
    ]
    eligible_groups = {row["rootCauseGroupId"] for row in eligible_rows}
    verified_groups = {
        row["rootCauseGroupId"]
        for row in full_rows
        if row["status"] == "VERIFIED_CLOSED"
    }
    return {
        "totalRows": len(full_rows),
        "totalRootCauseGroups": len(total_groups),
        "emittedRows": len(emitted_rows),
        "emittedRootCauseGroups": len(emitted_groups),
        "overflowRows": max(0, len(full_rows) - len(emitted_rows)),
        "truncated": len(emitted_rows) < len(full_rows),
        "eligibleRows": len(eligible_rows),
        "eligibleRootCauseGroups": len(eligible_groups),
        "reviewOnlyRows": sum(
            row["fixEligibility"] == "REVIEW_REQUIRED" for row in full_rows
        ),
        "verifiedClosedRootCauseGroups": len(verified_groups),
        "targetFloor": TARGET_FLOOR,
        "targetCeiling": TARGET_CEILING,
        "targetGap": max(0, TARGET_FLOOR - len(eligible_groups | verified_groups)),
        "perCategoryTotals": category_totals,
        "emittedPerCategoryTotals": emitted_category_totals,
    }


def _cap_ledger_rows_preserving_terminals(
    full_rows: Sequence[dict[str, Any]],
    candidate_cap: int,
) -> tuple[dict[str, Any], ...]:
    terminal_statuses = {"REJECTED_FALSE_POSITIVE", "VERIFIED_CLOSED"}
    terminal_rows = [row for row in full_rows if row.get("status") in terminal_statuses]
    if len(terminal_rows) > candidate_cap:
        raise AuditContractError("semantic-nondeterminism")
    remaining_rows = [row for row in full_rows if row.get("status") not in terminal_statuses]
    return tuple(
        sorted(
            terminal_rows
            + remaining_rows[: max(0, candidate_cap - len(terminal_rows))],
            key=_ledger_sort_key,
        )
    )


def _format_generated_at(now: datetime) -> str:
    return _aware_utc(now).isoformat().replace("+00:00", "Z")


def build_audit(inputs: AuditInputs, now: datetime) -> AuditBundle:
    if not isinstance(inputs, AuditInputs):
        raise AuditContractError("semantic-nondeterminism")
    if not _is_nonnegative_int(inputs.candidate_cap) or inputs.candidate_cap == 0:
        raise AuditContractError("semantic-nondeterminism")
    try:
        root = Path(inputs.root).resolve(strict=True)
    except OSError as exc:
        raise AuditContractError("active-root-missing") from exc
    if not root.is_dir():
        raise AuditContractError("active-root-missing")

    registry = _validated_audit_registry(
        root=root,
        supplied_path=Path(inputs.closure_wave_registry),
    )
    registry_manifest_exclusions = _registry_manifest_exclusions(
        root=root,
        registry=registry,
    )

    head = _read_capture_line(Path(inputs.git_head_input))
    branch = _read_capture_line(Path(inputs.git_branch_input), branch=True)
    skip_worktree_fallbacks = (
        load_skip_worktree_fallbacks(Path(inputs.git_skip_worktree_input))
        if inputs.git_skip_worktree_input is not None
        else {}
    )
    manifest = build_workspace_manifest(
        root,
        _read_required_bytes(Path(inputs.git_paths_input)),
        _read_required_bytes(Path(inputs.git_status_input)),
        skip_worktree_fallbacks,
        registry_manifest_exclusions,
    )
    # The Java ledger is intentionally active-source scoped.  Keep inactive
    # compatibility roots out of public baseline rows as well as issue scans.
    manifest = tuple(
        row
        for row in manifest
        if not any(row["path"].casefold().startswith(prefix) for prefix in INACTIVE_JAVA_PREFIXES)
    )
    manifest = _public_manifest_rows(manifest)
    java_records = _scan_active_java(root, inputs.active_java_roots)

    harmony_payload = load_required_input(Path(inputs.harmony_input), now)
    test_tree_payload = load_required_input(Path(inputs.test_tree_input), now)
    duplicate_payload = load_required_input(
        Path(inputs.dup_fqcn_input), now, require_freshness=False
    )
    harmony = _validate_harmony_input(harmony_payload)
    test_tree = _validate_test_tree_input(test_tree_payload)
    duplicates = _validate_duplicate_input(duplicate_payload)

    source_set_hash = _source_set_configuration_hash()
    baseline_id = _compute_baseline_id(
        head=head,
        branch=branch,
        source_set_hash=source_set_hash,
        manifest_rows=manifest,
    )
    untracked_count = sum(row["gitState"] == "untracked" for row in manifest)
    deleted_count = sum(row["gitState"] == "deleted" for row in manifest)
    modified_count = sum(
        row["gitState"] in {"modified", "renamed", "unmerged"} for row in manifest
    )
    root_material = str(root).replace("\\", "/").casefold().encode("utf-8")
    baseline_payload: dict[str, Any] = {
        "schemaVersion": BASELINE_SCHEMA,
        "canonicalRootHash": sha256_hex(root_material),
        "canonicalRootPathLength": len(str(root)),
        "head": head,
        "branch": branch,
        "activeSourceSets": ["rootMain", "appMainClean"],
        "activeSourceSetConfigurationHash": source_set_hash,
        "trackedPathCount": len(manifest) - untracked_count,
        "modifiedPathCount": modified_count,
        "deletedPathCount": deleted_count,
        "untrackedPathCount": untracked_count,
        "activeJavaFileCount": len(java_records),
        "activeJavaBytesTotal": sum(record["sizeBytes"] for record in java_records),
        "pathStateContentRows": list(manifest),
        "baselineId": baseline_id,
        "secretPatternHitCount": harmony["secretPatternHits"],
        "ignoredArtifactPolicyVersion": GENERATED_ARTIFACT_POLICY_VERSION,
    }

    current_rows = build_ledger_rows(java_records, harmony, duplicates, baseline_id)
    history = load_closure_history(root=root, registry=registry)
    full_rows = apply_closure_overlay(current_rows, history, baseline_id=baseline_id)
    emitted_rows = _cap_ledger_rows_preserving_terminals(
        full_rows,
        inputs.candidate_cap,
    )
    line_counts = [record["lineCount"] for record in java_records]
    metrics_payload: dict[str, Any] = {
        "schemaVersion": METRICS_SCHEMA,
        "baselineId": baseline_id,
        "activeJavaFileCount": len(java_records),
        "activeJavaLocTotal": sum(line_counts),
        "activeJavaLocP95": _nearest_rank_p95(line_counts),
        "largeActiveFilesOver2000": sum(
            line_count > FILE_SIZE_THRESHOLD for line_count in line_counts
        ),
        "duplicateFqcnSourceCollisionCount": duplicates[
            "duplicateFqcnSourceCollisionCount"
        ],
        "duplicateFqcnGeneratedExcludeCount": duplicates[
            "duplicateFqcnGeneratedExcludeCount"
        ],
        "duplicateFqcnHardExcludeCount": duplicates["duplicateFqcnHardExcludeCount"],
        "duplicateFqcnPackagedActiveCount": duplicates[
            "duplicateFqcnPackagedActiveCount"
        ],
        "duplicateFqcnActiveCount": duplicates["duplicateFqcnPackagedActiveCount"],
        "secretPatternHitCount": harmony["secretPatternHits"],
        "harmonyPressureSummary": harmony["summary"],
        "testTreeContamination": test_tree,
        "ledgerSummary": _ledger_summary(full_rows, emitted_rows),
        "closureHistorySummary": copy.deepcopy(history.summary),
        "runtimeProviderDisabledSmoke": {
            "status": "evidence_needed",
            "reason": "optional-provider-evidence-not-declared",
        },
        "supabaseReadonlySmoke": {
            "status": "evidence_needed",
            "reason": "project-scoped-readonly-evidence-not-declared",
            "readOnlyMode": True,
            "mutationAllowed": False,
        },
    }

    baseline_payload_hash = sha256_hex(canonical_json_bytes(baseline_payload))
    ledger_payload_hash = sha256_hex(canonical_ndjson_bytes(emitted_rows))
    metrics_payload_hash = sha256_hex(canonical_json_bytes(metrics_payload))
    audit_run_id = sha256_hex(
        (
            f"{AUDIT_SCHEMA}|{baseline_id}|{baseline_payload_hash}|"
            f"{ledger_payload_hash}|{metrics_payload_hash}|"
            f"{history.summary['waveRegistrySha256']}|"
            f"{history.summary['journalSetSha256']}|"
            f"{history.summary['proofSetSha256']}"
        ).encode("utf-8")
    )
    artifact_links = {
        "baselinePayloadSha256": baseline_payload_hash,
        "ledgerPayloadSha256": ledger_payload_hash,
        "metricsPayloadSha256": metrics_payload_hash,
    }
    generated_at = _format_generated_at(now)

    baseline = copy.deepcopy(baseline_payload)
    baseline.update(
        {
            "generatedAt": generated_at,
            "auditRunId": audit_run_id,
            "artifactLinks": copy.deepcopy(artifact_links),
        }
    )
    ledger_rows: list[dict[str, Any]] = []
    for core_row in emitted_rows:
        row = copy.deepcopy(core_row)
        row.update(
            {
                "generatedAt": generated_at,
                "auditRunId": audit_run_id,
                "artifactLinks": copy.deepcopy(artifact_links),
            }
        )
        ledger_rows.append(row)
    metrics = copy.deepcopy(metrics_payload)
    metrics.update(
        {
            "semanticArtifactHash": metrics_payload_hash,
            "generatedAt": generated_at,
            "auditRunId": audit_run_id,
            "artifactLinks": copy.deepcopy(artifact_links),
        }
    )
    bundle = AuditBundle(
        baseline=baseline,
        metrics=metrics,
        ledger_rows=tuple(ledger_rows),
        root=root,
        closure_registry=registry,
    )
    validate_bundle(bundle)
    return bundle


def _strip_envelope(value: dict[str, Any], *, metrics: bool = False) -> dict[str, Any]:
    stripped = copy.deepcopy(value)
    for field in ENVELOPE_FIELDS:
        stripped.pop(field, None)
    if metrics:
        stripped.pop("semanticArtifactHash", None)
    return stripped


def _validate_manifest_payload(baseline: dict[str, Any]) -> None:
    rows = baseline.get("pathStateContentRows")
    if not isinstance(rows, list):
        raise AuditContractError("semantic-nondeterminism")
    seen: set[str] = set()
    allowed_states = {"clean", "modified", "deleted", "untracked", "renamed", "unmerged"}
    for row in rows:
        if not isinstance(row, dict) or set(row) != {
            "path",
            "gitState",
            "sizeBytes",
            "contentSha256",
        }:
            raise AuditContractError("public-artifact-secret-hit")
        path = _normalize_repo_path(row.get("path"))
        if (
            path.casefold() in seen
            or _is_generated_path(path)
            or any(path.casefold().startswith(prefix) for prefix in INACTIVE_JAVA_PREFIXES)
        ):
            raise AuditContractError("semantic-nondeterminism")
        seen.add(path.casefold())
        if (
            row.get("gitState") not in allowed_states
            or not _is_nonnegative_int(row.get("sizeBytes"))
            or not isinstance(row.get("contentSha256"), str)
            or not re.fullmatch(r"[0-9a-f]{64}", row["contentSha256"])
        ):
            raise AuditContractError("semantic-nondeterminism")
    if rows != sorted(rows, key=lambda row: (row["path"].casefold(), row["path"])):
        raise AuditContractError("semantic-nondeterminism")


def _validate_public_strings(value: object) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if not isinstance(key, str):
                raise AuditContractError("public-artifact-secret-hit")
            _validate_public_strings(child)
    elif isinstance(value, list) or isinstance(value, tuple):
        for child in value:
            _validate_public_strings(child)
    elif isinstance(value, str):
        if (
            SECRET_PATTERN.search(value)
            or WINDOWS_ABSOLUTE_PATTERN.search(value)
            or POSIX_ABSOLUTE_PATTERN.search(value)
            or UNC_ABSOLUTE_PATTERN.search(value)
            or WINDOWS_ROOTED_PATTERN.search(value)
            or URI_SCHEME_PATTERN.search(value)
        ):
            raise AuditContractError("public-artifact-secret-hit")


def _closure_path_key(path: Path) -> str:
    return os.path.normcase(str(path.absolute()))


def _closure_child(proof_root: Path, relative: Path | str, reason: str) -> Path:
    try:
        normalized = _normalize_repo_path(str(relative).replace("\\", "/"))
        candidate = proof_root.joinpath(*PurePosixPath(normalized).parts)
        if _has_reparse_component(proof_root, candidate):
            raise AuditContractError(reason)
        resolved = candidate.resolve(strict=True)
        resolved.relative_to(proof_root)
        if not resolved.is_file():
            raise AuditContractError(reason)
        return resolved
    except AuditContractError:
        raise
    except (OSError, ValueError) as exc:
        raise AuditContractError(reason) from exc


def _read_closure_bytes(path: Path, reason: str) -> bytes:
    try:
        if not path.is_file():
            raise AuditContractError(reason)
        return path.read_bytes()
    except AuditContractError:
        raise
    except OSError as exc:
        raise AuditContractError(reason) from exc


def _load_closure_intake(
    proof_root: Path,
    descriptor: ClosureWaveDescriptor,
) -> ClosureWaveIntake:
    try:
        summary_bytes = _read_closure_bytes(
            _closure_child(
                proof_root,
                INTAKE_SUMMARY_RELATIVE,
                "closure-intake-mismatch",
            ),
            "closure-intake-mismatch",
        )
        predecessor_bytes = _read_closure_bytes(
            _closure_child(
                proof_root,
                INTAKE_ROWS_RELATIVE,
                "closure-intake-mismatch",
            ),
            "closure-intake-mismatch",
        )
        target_bytes = _read_closure_bytes(
            _closure_child(
                proof_root,
                TARGET_PREIMAGES_RELATIVE,
                "closure-intake-mismatch",
            ),
            "closure-intake-mismatch",
        )
        observed_hashes = (
            (INTAKE_SUMMARY_RELATIVE.as_posix(), sha256_hex(summary_bytes)),
            (INTAKE_ROWS_RELATIVE.as_posix(), sha256_hex(predecessor_bytes)),
            (TARGET_PREIMAGES_RELATIVE.as_posix(), sha256_hex(target_bytes)),
        )
        expected_hashes = (
            (INTAKE_SUMMARY_RELATIVE.as_posix(), descriptor.intake_summary_sha256),
            (INTAKE_ROWS_RELATIVE.as_posix(), descriptor.eligible_groups_sha256),
            (TARGET_PREIMAGES_RELATIVE.as_posix(), descriptor.target_preimages_sha256),
        )
        if observed_hashes != expected_hashes:
            raise AuditContractError("closure-intake-mismatch")
        summary_value = _parse_canonical_json_bytes(
            summary_bytes,
            "closure-intake-mismatch",
        )
        predecessor_rows = _parse_canonical_ndjson_bytes(
            predecessor_bytes,
            "closure-intake-mismatch",
        )
        target_value = _parse_canonical_json_bytes(
            target_bytes,
            "closure-intake-mismatch",
        )
        if (
            not isinstance(summary_value, dict)
            or not isinstance(target_value, dict)
            or set(target_value) != CLOSURE_TARGET_PREIMAGES_FIELDS
            or target_value.get("schemaVersion") != CLOSURE_TARGET_PREIMAGES_SCHEMA
        ):
            raise AuditContractError("closure-intake-mismatch")
        if summary_value.get("schemaVersion") == CLOSURE_INTAKE_V3_SCHEMA:
            if descriptor.wave_id != "wave-0004" or descriptor.ordinal != 4:
                raise AuditContractError("closure-intake-mismatch")
            return _validate_selfask_reviewed_admission_intake_v3(
                proof_root=proof_root,
                descriptor=descriptor,
                summary=summary_value,
                predecessor_rows=predecessor_rows,
                target_value=target_value,
            )
        if summary_value.get("schemaVersion") == CLOSURE_INTAKE_V2_SCHEMA:
            if descriptor.wave_id != "wave-0003" or descriptor.ordinal != 3:
                raise AuditContractError("closure-intake-mismatch")
            return _validate_reviewed_duplicate_intake_v2(
                proof_root=proof_root,
                descriptor=descriptor,
                summary=summary_value,
                predecessor_rows=predecessor_rows,
                target_value=target_value,
            )
        if (
            summary_value.get("schemaVersion") != CLOSURE_INTAKE_SCHEMA
            or set(summary_value) != CLOSURE_INTAKE_SUMMARY_FIELDS
        ):
            raise AuditContractError("closure-intake-mismatch")
        if (descriptor.wave_id, descriptor.ordinal) not in {
            ("wave-0001", 1),
            ("wave-0002", 2),
        }:
            raise AuditContractError("closure-intake-mismatch")
        source_baseline = summary_value.get("sourceBaselineId")
        expected_identity = (
            ("sourceBaselineId", descriptor.source_baseline_id),
            (
                "sourceLedgerPayloadSha256",
                descriptor.source_ledger_payload_sha256,
            ),
            (
                "sourceMetricsSemanticHash",
                descriptor.source_metrics_semantic_hash,
            ),
        )
        observed_identity = tuple(
            (field, summary_value.get(field))
            for field, _ in expected_identity
        )
        if not all(
            _is_lower_hex64(summary_value.get(field))
            for field in (
                "sourceBaselineId",
                "sourceLedgerPayloadSha256",
                "sourceMetricsSemanticHash",
            )
        ) or not _is_safe_branch_token(summary_value.get("sourceBranch")):
            raise AuditContractError("closure-intake-mismatch")
        if observed_identity != expected_identity:
            raise AuditContractError("closure-intake-mismatch")
        if (
            not isinstance(summary_value.get("sourceHead"), str)
            or re.fullmatch(r"[0-9a-f]{40}", summary_value["sourceHead"]) is None
            or target_value.get("sourceHead") != summary_value["sourceHead"]
            or target_value.get("canonicalBranch") != summary_value["sourceBranch"]
            or not _is_safe_branch_token(target_value.get("isolatedBranch"))
        ):
            raise AuditContractError("closure-intake-mismatch")
        try:
            _validate_ledger_core_rows(
                predecessor_rows,
                expected_baseline_id=source_baseline,
            )
        except AuditContractError as exc:
            raise AuditContractError("closure-intake-mismatch") from exc
        if not predecessor_rows or any(
            row["category"] != "BROAD_CATCH_NO_BREADCRUMB"
            or row["fixEligibility"] != "ELIGIBLE"
            or row["status"] != "OPEN"
            for row in predecessor_rows
        ):
            raise AuditContractError("closure-intake-mismatch")
        expected_lists = (
            ("sourceIssueIds", "issueId"),
            ("rootCauseGroupIds", "rootCauseGroupId"),
            ("evidenceFingerprints", "evidenceFingerprint"),
        )
        for summary_field, row_field in expected_lists:
            values = summary_value.get(summary_field)
            if (
                not isinstance(values, list)
                or values != [row[row_field] for row in predecessor_rows]
                or len(values) != len(set(values))
                or not all(_is_lower_hex64(value) for value in values)
            ):
                raise AuditContractError("closure-intake-mismatch")

        raw_targets = target_value.get("targets")
        if not isinstance(raw_targets, list) or not raw_targets:
            raise AuditContractError("closure-intake-mismatch")
        targets: dict[str, dict[str, Any]] = {}
        ordered_paths: list[str] = []
        allowed_states = {
            "clean",
            "modified",
            "deleted",
            "untracked",
            "renamed",
            "unmerged",
            "absent",
        }
        for raw_target in raw_targets:
            if (
                not isinstance(raw_target, dict)
                or set(raw_target) != CLOSURE_TARGET_PREIMAGE_ROW_FIELDS
            ):
                raise AuditContractError("closure-intake-mismatch")
            path = _normalize_repo_path(raw_target.get("path"))
            identity = path.casefold()
            if identity in targets:
                raise AuditContractError("closure-intake-mismatch")
            existence = raw_target.get("existence")
            size = raw_target.get("sizeBytes")
            content_hash = raw_target.get("sha256")
            if (
                existence not in {"FILE", "ABSENT"}
                or raw_target.get("gitState") not in allowed_states
                or not _is_nonnegative_int(size)
                or not _is_lower_hex64(content_hash)
                or (
                    existence == "ABSENT"
                    and (size != 0 or content_hash != "0" * 64 or raw_target["gitState"] != "absent")
                )
            ):
                raise AuditContractError("closure-intake-mismatch")
            row = dict(raw_target)
            row["path"] = path
            targets[identity] = row
            ordered_paths.append(path)
        if ordered_paths != sorted(ordered_paths, key=lambda value: (value.casefold(), value)):
            raise AuditContractError("closure-intake-mismatch")
        return ClosureWaveIntake(
            schema_version=CLOSURE_INTAKE_SCHEMA,
            predecessor_rows=predecessor_rows,
            targets=targets,
            summary=summary_value,
            admission_decision=None,
            admission_decision_sha256=None,
            duplicate_evidence=None,
            duplicate_evidence_sha256=None,
        )
    except AuditContractError as exc:
        if exc.reason_code in {"closure-intake-mismatch", "closure-admission-invalid"}:
            raise
        raise AuditContractError("closure-intake-mismatch") from exc


def _validate_reviewed_duplicate_intake_v2(
    *,
    proof_root: Path,
    descriptor: ClosureWaveDescriptor,
    summary: dict[str, Any],
    predecessor_rows: tuple[dict[str, Any], ...],
    target_value: dict[str, Any],
) -> ClosureWaveIntake:
    if (
        set(summary) != CLOSURE_INTAKE_V2_SUMMARY_FIELDS
        or len(predecessor_rows) != 1
        or summary.get("admissionMode") != "EXPLICIT_REVIEW_PROMOTION"
        or descriptor.wave_id != "wave-0003"
        or descriptor.ordinal != 3
    ):
        raise AuditContractError("closure-intake-mismatch")
    identity_fields = (
        ("sourceBaselineId", descriptor.source_baseline_id),
        ("sourceLedgerPayloadSha256", descriptor.source_ledger_payload_sha256),
        ("sourceMetricsSemanticHash", descriptor.source_metrics_semantic_hash),
    )
    if (
        not all(_is_lower_hex64(summary.get(field)) and summary.get(field) == expected for field, expected in identity_fields)
        or not _is_safe_branch_token(summary.get("sourceBranch"))
        or not isinstance(summary.get("sourceHead"), str)
        or re.fullmatch(r"[0-9a-f]{40}", summary["sourceHead"]) is None
        or target_value.get("sourceHead") != summary["sourceHead"]
        or target_value.get("canonicalBranch") != summary["sourceBranch"]
        or not _is_safe_branch_token(target_value.get("isolatedBranch"))
    ):
        raise AuditContractError("closure-intake-mismatch")
    try:
        _validate_ledger_core_rows(predecessor_rows, expected_baseline_id=summary["sourceBaselineId"])
    except AuditContractError as exc:
        raise AuditContractError("closure-intake-mismatch") from exc
    row = predecessor_rows[0]
    required_row = {
        "category": "DUPLICATE_FQCN_SOURCE_COLLISION",
        "severity": "HIGH",
        "fixEligibility": "REVIEW_REQUIRED",
        "status": "HOLD",
        "supersedes": [],
        "symbol": "com.example.lms.strategy.RetrievalOrderService",
        "path": "main/java/com/example/lms/strategy/RetrievalOrderService.java",
        "numericEvidence": {
            "duplicateFqcnGeneratedExcludeCount": 1,
            "duplicateFqcnHardExcludeCount": 0,
            "duplicateFqcnPackagedActiveCount": 0,
            "duplicateFqcnSourceCollisionCount": 1,
        },
    }
    if any(row.get(field) != expected for field, expected in required_row.items()):
        raise AuditContractError("closure-intake-mismatch")
    for summary_field, row_field in (
        ("sourceIssueIds", "issueId"),
        ("rootCauseGroupIds", "rootCauseGroupId"),
        ("evidenceFingerprints", "evidenceFingerprint"),
    ):
        if summary.get(summary_field) != [row[row_field]]:
            raise AuditContractError("closure-intake-mismatch")

    raw_targets = target_value.get("targets")
    expected_paths = (
        "app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java",
        "main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java",
        "main/java/com/example/lms/strategy/RetrievalOrderService.java",
        "src/test/java/com/abandonware/ai/agent/AgentApplicationScanContractTest.java",
        "src/test/java/com/example/lms/strategy/RetrievalOrderServiceTest.java",
    )
    if not isinstance(raw_targets, list) or len(raw_targets) != len(expected_paths):
        raise AuditContractError("closure-admission-invalid")
    targets: dict[str, dict[str, Any]] = {}
    ordered_paths: list[str] = []
    for raw_target in raw_targets:
        if not isinstance(raw_target, dict) or set(raw_target) != CLOSURE_TARGET_PREIMAGE_ROW_FIELDS:
            raise AuditContractError("closure-admission-invalid")
        try:
            path = _normalize_repo_path(raw_target.get("path"))
        except AuditContractError as exc:
            raise AuditContractError("closure-admission-invalid") from exc
        identity = path.casefold()
        if identity in targets or raw_target.get("existence") not in {"FILE", "ABSENT"}:
            raise AuditContractError("closure-admission-invalid")
        if (
            raw_target.get("gitState") not in {"clean", "modified", "deleted", "untracked", "renamed", "unmerged", "absent"}
            or not _is_nonnegative_int(raw_target.get("sizeBytes"))
            or not _is_lower_hex64(raw_target.get("sha256"))
            or (
                raw_target["existence"] == "ABSENT"
                and (
                    raw_target["sizeBytes"] != 0
                    or raw_target["sha256"] != "0" * 64
                    or raw_target["gitState"] != "absent"
                )
            )
        ):
            raise AuditContractError("closure-admission-invalid")
        targets[identity] = dict(raw_target, path=path)
        ordered_paths.append(path)
    if (
        ordered_paths != sorted(ordered_paths, key=lambda value: (value.casefold(), value))
        or tuple(ordered_paths) != expected_paths
    ):
        raise AuditContractError("closure-admission-invalid")

    decision_path = _closure_child(proof_root, ADMISSION_DECISION_RELATIVE, "closure-admission-invalid")
    evidence_path = _closure_child(proof_root, DUPLICATE_EVIDENCE_RELATIVE, "closure-admission-invalid")
    decision_bytes = _read_closure_bytes(decision_path, "closure-admission-invalid")
    evidence_bytes = _read_closure_bytes(evidence_path, "closure-admission-invalid")
    decision_sha256 = sha256_hex(decision_bytes)
    evidence_sha256 = sha256_hex(evidence_bytes)
    if summary.get("admissionDecisionSha256") != decision_sha256 or summary.get("duplicateEvidenceSha256") != evidence_sha256:
        raise AuditContractError("closure-admission-invalid")
    decision = _parse_canonical_json_bytes(decision_bytes, "closure-admission-invalid")
    evidence = _parse_canonical_json_bytes(evidence_bytes, "closure-admission-invalid")
    if not isinstance(decision, dict) or set(decision) != CLOSURE_ADMISSION_DECISION_FIELDS:
        raise AuditContractError("closure-admission-invalid")
    if not isinstance(evidence, dict) or set(evidence) != CLOSURE_DUPLICATE_EVIDENCE_FIELDS:
        raise AuditContractError("closure-admission-invalid")
    material = dict(decision)
    decision_id = material.pop("decisionId", None)
    if not _is_lower_hex64(decision_id) or decision_id != sha256_hex(canonical_json_bytes(material)):
        raise AuditContractError("closure-admission-invalid")
    decision_fields = {
        "schemaVersion": CLOSURE_ADMISSION_DECISION_SCHEMA,
        "decisionType": "REVIEW_REQUIRED_DUPLICATE_OWNER",
        "sourceBaselineId": row["baselineId"],
        "sourceIssueId": row["issueId"],
        "rootCauseGroupId": row["rootCauseGroupId"],
        "sourceEvidenceFingerprint": row["evidenceFingerprint"],
        "sourceCategory": row["category"],
        "sourceFixEligibility": row["fixEligibility"],
        "sourceStatus": row["status"],
        "sourceNumericEvidence": row["numericEvidence"],
        "canonicalOwnerPath": row["path"],
        "proposedRepairTargetPath": expected_paths[0],
        "repairTargetAdmissionRequired": True,
        "activeCallPath": expected_paths[1],
        "behaviorTestPath": expected_paths[4],
        "ownerContractTestPath": expected_paths[3],
        "duplicateEvidenceSha256": evidence_sha256,
        "approvedDesignSha256": WAVE_THREE_APPROVED_DESIGN_SHA256,
        "ragControlExcluded": True,
    }
    if any(decision.get(field) != expected for field, expected in decision_fields.items()):
        raise AuditContractError("closure-admission-invalid")
    evidence_fields = {
        "schemaVersion": CLOSURE_DUPLICATE_EVIDENCE_SCHEMA,
        "sourceIssueId": row["issueId"],
        "rootCauseGroupId": row["rootCauseGroupId"],
        "fqcn": row["symbol"],
        "canonicalOwnerPath": row["path"],
        "compatibilityCopyPath": expected_paths[0],
        "sourceCollisionCount": 1,
        "generatedExcludeCount": 1,
        "hardExcludeCount": 0,
        "packagedActiveCount": 0,
        "generatedExcludePattern": "com/example/lms/strategy/RetrievalOrderService*",
        "proofCommand": "gradlew.bat :app:generateDupFqcnExcludes",
        "secretPatternHitCount": 0,
    }
    if (
        any(evidence.get(field) != expected for field, expected in evidence_fields.items())
        or not _is_lower_hex64(evidence.get("rawReportSha256"))
    ):
        raise AuditContractError("closure-admission-invalid")
    return ClosureWaveIntake(
        schema_version=CLOSURE_INTAKE_V2_SCHEMA,
        predecessor_rows=predecessor_rows,
        targets=targets,
        summary=summary,
        admission_decision=decision,
        admission_decision_sha256=decision_sha256,
        duplicate_evidence=evidence,
        duplicate_evidence_sha256=evidence_sha256,
    )


def _validate_selfask_reviewed_admission_intake_v3(
    *,
    proof_root: Path,
    descriptor: ClosureWaveDescriptor,
    summary: dict[str, Any],
    predecessor_rows: tuple[dict[str, Any], ...],
    target_value: dict[str, Any],
) -> ClosureWaveIntake:
    if (
        set(summary) != CLOSURE_INTAKE_V3_SUMMARY_FIELDS
        or len(predecessor_rows) != 1
        or summary.get("admissionMode") != "EXACT_SELFASK_REVIEW_PROMOTION"
        or descriptor.wave_id != "wave-0004"
        or descriptor.ordinal != 4
    ):
        raise AuditContractError("closure-intake-mismatch")
    identity_fields = (
        ("sourceBaselineId", descriptor.source_baseline_id),
        ("sourceLedgerPayloadSha256", descriptor.source_ledger_payload_sha256),
        ("sourceMetricsSemanticHash", descriptor.source_metrics_semantic_hash),
    )
    if (
        not all(
            _is_lower_hex64(summary.get(field)) and summary.get(field) == expected
            for field, expected in identity_fields
        )
        or not _is_safe_branch_token(summary.get("sourceBranch"))
        or not isinstance(summary.get("sourceHead"), str)
        or re.fullmatch(r"[0-9a-f]{40}", summary["sourceHead"]) is None
        or target_value.get("sourceHead") != summary["sourceHead"]
        or target_value.get("canonicalBranch") != summary["sourceBranch"]
        or not _is_safe_branch_token(target_value.get("isolatedBranch"))
    ):
        raise AuditContractError("closure-intake-mismatch")
    try:
        _validate_ledger_core_rows(
            predecessor_rows,
            expected_baseline_id=summary["sourceBaselineId"],
        )
    except AuditContractError as exc:
        raise AuditContractError("closure-intake-mismatch") from exc
    row = predecessor_rows[0]
    required_row = {
        "category": "DUPLICATE_FQCN_SOURCE_COLLISION",
        "severity": "HIGH",
        "fixEligibility": "REVIEW_REQUIRED",
        "status": "HOLD",
        "supersedes": [],
        "symbol": "service.rag.planner.SelfAskPlanner",
        "path": "main/java/service/rag/planner/SelfAskPlanner.java",
        "numericEvidence": {
            "duplicateFqcnGeneratedExcludeCount": 1,
            "duplicateFqcnHardExcludeCount": 0,
            "duplicateFqcnPackagedActiveCount": 0,
            "duplicateFqcnSourceCollisionCount": 1,
        },
    }
    if any(row.get(field) != expected for field, expected in required_row.items()):
        raise AuditContractError("closure-intake-mismatch")
    for summary_field, row_field in (
        ("sourceIssueIds", "issueId"),
        ("rootCauseGroupIds", "rootCauseGroupId"),
        ("evidenceFingerprints", "evidenceFingerprint"),
    ):
        if summary.get(summary_field) != [row[row_field]]:
            raise AuditContractError("closure-intake-mismatch")

    expected_paths = (
        "app/build.gradle.kts",
        "app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java",
        "main/java/config/RagLightAdapters.java",
        "main/java/service/rag/planner/SelfAskPlanner.java",
        "src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java",
    )
    raw_targets = target_value.get("targets")
    if not isinstance(raw_targets, list) or len(raw_targets) != len(expected_paths):
        raise AuditContractError("closure-admission-invalid")
    targets: dict[str, dict[str, Any]] = {}
    ordered_paths: list[str] = []
    allowed_states = {"clean", "modified", "deleted", "untracked", "renamed", "unmerged", "absent"}
    for raw_target in raw_targets:
        if not isinstance(raw_target, dict) or set(raw_target) != CLOSURE_TARGET_PREIMAGE_ROW_FIELDS:
            raise AuditContractError("closure-admission-invalid")
        try:
            path = _normalize_repo_path(raw_target.get("path"))
        except AuditContractError as exc:
            raise AuditContractError("closure-admission-invalid") from exc
        identity = path.casefold()
        if (
            path != raw_target.get("path")
            or identity in targets
            or raw_target.get("existence") not in {"FILE", "ABSENT"}
            or raw_target.get("gitState") not in allowed_states
            or not _is_nonnegative_int(raw_target.get("sizeBytes"))
            or not _is_lower_hex64(raw_target.get("sha256"))
        ):
            raise AuditContractError("closure-admission-invalid")
        if raw_target["existence"] == "ABSENT":
            if (
                raw_target["gitState"] != "absent"
                or raw_target["sizeBytes"] != 0
                or raw_target["sha256"] != "0" * 64
            ):
                raise AuditContractError("closure-admission-invalid")
        elif raw_target["gitState"] == "absent":
            raise AuditContractError("closure-admission-invalid")
        targets[identity] = dict(raw_target, path=path)
        ordered_paths.append(path)
    if (
        tuple(ordered_paths) != expected_paths
        or ordered_paths != sorted(ordered_paths, key=lambda value: (value.casefold(), value))
        or targets[expected_paths[1].casefold()]["existence"] != "FILE"
        or targets[expected_paths[1].casefold()]["gitState"] != "clean"
        or any(targets[path.casefold()]["existence"] != "FILE" for path in expected_paths[:4])
        or targets[expected_paths[4].casefold()]["existence"] != "ABSENT"
    ):
        raise AuditContractError("closure-admission-invalid")

    decision_path = _closure_child(proof_root, ADMISSION_DECISION_RELATIVE, "closure-admission-invalid")
    evidence_path = _closure_child(proof_root, DUPLICATE_EVIDENCE_RELATIVE, "closure-admission-invalid")
    decision_bytes = _read_closure_bytes(decision_path, "closure-admission-invalid")
    evidence_bytes = _read_closure_bytes(evidence_path, "closure-admission-invalid")
    decision_sha256 = sha256_hex(decision_bytes)
    evidence_sha256 = sha256_hex(evidence_bytes)
    if (
        summary.get("admissionDecisionSha256") != decision_sha256
        or summary.get("duplicateEvidenceSha256") != evidence_sha256
    ):
        raise AuditContractError("closure-admission-invalid")
    decision = _parse_canonical_json_bytes(decision_bytes, "closure-admission-invalid")
    evidence = _parse_canonical_json_bytes(evidence_bytes, "closure-admission-invalid")
    if (
        not isinstance(decision, dict)
        or set(decision) != CLOSURE_ADMISSION_DECISION_V2_FIELDS
        or not isinstance(evidence, dict)
        or set(evidence) != CLOSURE_DUPLICATE_EVIDENCE_V2_FIELDS
    ):
        raise AuditContractError("closure-admission-invalid")
    material = dict(decision)
    decision_id = material.pop("decisionId", None)
    if not _is_lower_hex64(decision_id) or decision_id != sha256_hex(canonical_json_bytes(material)):
        raise AuditContractError("closure-admission-invalid")
    decision_fields = {
        "schemaVersion": CLOSURE_ADMISSION_DECISION_V2_SCHEMA,
        "decisionType": "EXACT_SELFASK_REVIEWED_DUPLICATE_OWNER",
        "sourceBaselineId": row["baselineId"],
        "sourceIssueId": row["issueId"],
        "rootCauseGroupId": row["rootCauseGroupId"],
        "sourceEvidenceFingerprint": row["evidenceFingerprint"],
        "sourceCategory": row["category"],
        "sourceFixEligibility": row["fixEligibility"],
        "sourceStatus": row["status"],
        "sourceNumericEvidence": row["numericEvidence"],
        "candidateFqcn": row["symbol"],
        "canonicalOwnerPath": row["path"],
        "proposedRepairTargetPath": expected_paths[1],
        "repairTargetAdmissionRequired": True,
        "packagingConfigPath": expected_paths[0],
        "activeCallPath": expected_paths[2],
        "futureOwnershipTestPath": expected_paths[4],
        "duplicateEvidenceSha256": evidence_sha256,
        "approvedDesignSha256": WAVE_FOUR_APPROVED_DESIGN_SHA256,
        "ragControlExcluded": True,
    }
    if any(decision.get(field) != expected for field, expected in decision_fields.items()):
        raise AuditContractError("closure-admission-invalid")
    evidence_fields = {
        "schemaVersion": CLOSURE_DUPLICATE_EVIDENCE_V2_SCHEMA,
        "sourceIssueId": row["issueId"],
        "rootCauseGroupId": row["rootCauseGroupId"],
        "fqcn": row["symbol"],
        "canonicalOwnerPath": row["path"],
        "compatibilityCopyPath": expected_paths[1],
        "packagingState": "GENERATED_EXCLUDE",
        "sourceCollisionCount": 1,
        "generatedExcludeCount": 1,
        "hardExcludeCount": 0,
        "packagedActiveCount": 0,
        "appActiveSourceRoot": "app/src/main/java_clean",
        "compatibilityCopyDirectCallerFileCount": 0,
        "callerProbeToken": "java-active-app-direct-caller-files-v1",
        "generatedExcludePattern": "service/rag/planner/SelfAskPlanner*",
        "proofCommand": "gradlew.bat :app:generateDupFqcnExcludes",
        "secretPatternHitCount": 0,
    }
    if (
        any(evidence.get(field) != expected for field, expected in evidence_fields.items())
        or not _is_lower_hex64(evidence.get("rawReportSha256"))
        or evidence.get("rawReportSha256") == "0" * 64
        or not _is_lower_hex64(evidence.get("rawCollisionEvidenceFingerprint"))
        or evidence.get("rawCollisionEvidenceFingerprint") == row["evidenceFingerprint"]
    ):
        raise AuditContractError("closure-admission-invalid")
    return ClosureWaveIntake(
        schema_version=CLOSURE_INTAKE_V3_SCHEMA,
        predecessor_rows=predecessor_rows,
        targets=targets,
        summary=summary,
        admission_decision=decision,
        admission_decision_sha256=decision_sha256,
        duplicate_evidence=evidence,
        duplicate_evidence_sha256=evidence_sha256,
    )


def _target_admission_proof_id(group_id: str, suffix: str) -> str:
    if not _is_lower_hex64(group_id) or suffix not in TARGET_ADMISSION_SUFFIXES.values():
        raise AuditContractError("closure-admission-invalid")
    return f"proofs/{group_id}/{suffix}.json"


def _load_target_admission(
    *,
    proof_root: Path,
    intake: ClosureWaveIntake,
    expected_id: object,
    expected_sha256: object,
) -> ClosureTargetAdmission:
    try:
        if (
            not isinstance(intake, ClosureWaveIntake)
            or intake.schema_version != CLOSURE_INTAKE_V2_SCHEMA
            or len(intake.predecessor_rows) != 1
            or not isinstance(intake.admission_decision, dict)
            or not _is_lower_hex64(intake.admission_decision_sha256)
            or not _is_lower_hex64(expected_id)
            or not _is_lower_hex64(expected_sha256)
        ):
            raise AuditContractError("closure-admission-invalid")
        predecessor = intake.predecessor_rows[0]
        group_id = predecessor["rootCauseGroupId"]
        issue_id = predecessor["issueId"]
        admission_id = _target_admission_proof_id(
            group_id,
            TARGET_ADMISSION_SUFFIXES["admission"],
        )
        admission_path = _closure_child(
            proof_root,
            admission_id,
            "closure-admission-invalid",
        )
        admission_bytes = _read_closure_bytes(admission_path, "closure-admission-invalid")
        admission_sha256 = sha256_hex(admission_bytes)
        if admission_sha256 != expected_sha256:
            raise AuditContractError("closure-admission-invalid")
        row = _parse_canonical_json_bytes(admission_bytes, "closure-admission-invalid")
        if not isinstance(row, dict) or set(row) != CLOSURE_TARGET_ADMISSION_FIELDS:
            raise AuditContractError("closure-admission-invalid")
        _validate_public_strings(row)
        material = dict(row)
        target_admission_id = material.pop("targetAdmissionId", None)
        try:
            repair_target_path = _normalize_repo_path(row.get("repairTargetPath"))
        except AuditContractError as exc:
            raise AuditContractError("closure-admission-invalid") from exc
        source_paths = (
            "app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java",
            "main/java/com/example/lms/strategy/RetrievalOrderService.java",
        )
        target = intake.targets.get(repair_target_path.casefold())
        source_design_sha256 = WAVE_THREE_APPROVED_SOURCE_DESIGN_SHA256
        if (
            not _is_lower_hex64(source_design_sha256)
            or source_design_sha256 == WAVE_THREE_APPROVED_DESIGN_SHA256
        ):
            raise AuditContractError("closure-admission-invalid")
        expected_fields = {
            "schemaVersion": CLOSURE_TARGET_ADMISSION_SCHEMA,
            "rootCauseGroupId": group_id,
            "sourceIssueId": issue_id,
            "admissionDecisionId": intake.admission_decision.get("decisionId"),
            "admissionDecisionSha256": intake.admission_decision_sha256,
            "repairTargetPath": repair_target_path,
            "transformationMode": "IN_PLACE_EXISTING_FILE",
            "approvedSourceDesignSha256": source_design_sha256,
            "detectorRedProofId": _target_admission_proof_id(
                group_id,
                TARGET_ADMISSION_SUFFIXES["detector"],
            ),
            "secretPatternHitCount": 0,
        }
        if (
            target_admission_id != expected_id
            or target_admission_id != sha256_hex(canonical_json_bytes(material))
            or repair_target_path not in source_paths
            or target is None
            or target.get("existence") != "FILE"
            or any(row.get(field) != expected for field, expected in expected_fields.items())
        ):
            raise AuditContractError("closure-admission-invalid")

        proof_specs = (
            (
                TARGET_ADMISSION_SUFFIXES["detector"],
                "DETECTOR_RED",
                "EXPECTED_FAIL",
                False,
                "detectorRedProofSha256",
            ),
            (
                TARGET_ADMISSION_SUFFIXES["compile"],
                "COMPILE_BASELINE",
                "PASS",
                True,
                "compileBaselineProofSha256",
            ),
            (
                TARGET_ADMISSION_SUFFIXES["owner"],
                "OWNER_CONTRACT",
                "PASS",
                True,
                "ownerContractProofSha256",
            ),
        )
        proof_pairs: dict[str, str] = {admission_id: admission_sha256}
        for suffix, role, result, zero_exit, hash_field in proof_specs:
            proof_id = _target_admission_proof_id(group_id, suffix)
            expected_hash = row.get(hash_field)
            if not _is_lower_hex64(expected_hash):
                raise AuditContractError("closure-admission-invalid")
            proof_path = _closure_child(
                proof_root,
                proof_id,
                "closure-admission-invalid",
            )
            proof_bytes = _read_closure_bytes(proof_path, "closure-admission-invalid")
            if sha256_hex(proof_bytes) != expected_hash:
                raise AuditContractError("closure-admission-invalid")
            proof = _parse_canonical_json_bytes(proof_bytes, "closure-admission-invalid")
            if not isinstance(proof, dict) or set(proof) != CLOSURE_TARGET_ADMISSION_PROOF_FIELDS:
                raise AuditContractError("closure-admission-invalid")
            _validate_public_strings(proof)
            command = proof.get("commandToken")
            if (
                proof.get("schemaVersion") != CLOSURE_TARGET_ADMISSION_PROOF_SCHEMA
                or proof.get("proofRole") != role
                or proof.get("rootCauseGroupId") != group_id
                or proof.get("sourceIssueId") != issue_id
                or not isinstance(command, str)
                or not command
                or command.strip() != command
                or any(ord(character) < 32 for character in command)
                or not _is_nonnegative_int(proof.get("exitCode"))
                or (zero_exit and proof["exitCode"] != 0)
                or (not zero_exit and proof["exitCode"] == 0)
                or proof.get("result") != result
                or not _is_nonnegative_int(proof.get("assertionCount"))
                or proof["assertionCount"] == 0
                or not _is_lower_hex64(proof.get("outputSha256"))
                or proof.get("secretPatternHitCount") != 0
            ):
                raise AuditContractError("closure-admission-invalid")
            proof_pairs[proof_id] = expected_hash
        return ClosureTargetAdmission(
            row=row,
            raw_sha256=admission_sha256,
            proof_pairs=tuple(sorted(proof_pairs.items(), key=lambda pair: (pair[0], pair[1]))),
        )
    except AuditContractError as exc:
        if exc.reason_code == "closure-admission-invalid":
            raise
        raise AuditContractError("closure-admission-invalid") from exc


def _validate_proof_id(proof_id: object, group_id: str, suffix: str) -> str:
    if (
        not isinstance(proof_id, str)
        or PROOF_ID_RE.fullmatch(proof_id) is None
        or proof_id != f"proofs/{group_id}/{suffix}.json"
    ):
        raise AuditContractError("closure-proof-invalid")
    return proof_id


def _load_verified_proof(
    proof_root: Path,
    proof_id: str,
    expected_hash: object,
) -> dict[str, Any]:
    if not _is_lower_hex64(expected_hash):
        raise AuditContractError("closure-proof-invalid")
    proof_path = _closure_child(proof_root, proof_id, "closure-proof-invalid")
    payload = _read_closure_bytes(proof_path, "closure-proof-invalid")
    if sha256_hex(payload) != expected_hash:
        raise AuditContractError("closure-proof-invalid")
    value = _parse_canonical_json_bytes(payload, "closure-proof-invalid")
    if not isinstance(value, dict):
        raise AuditContractError("closure-proof-invalid")
    try:
        _validate_public_strings(value)
    except AuditContractError as exc:
        raise AuditContractError("closure-proof-invalid") from exc
    return value


def _validate_red_green_proof(
    proof: dict[str, Any],
    *,
    kind: str,
    group_id: str,
    issue_id: str,
) -> None:
    expected_result = "EXPECTED_FAIL" if kind == "RED" else "PASS"
    expected_exit = 0 if kind == "GREEN" else None
    command = proof.get("commandToken")
    if (
        set(proof) != CLOSURE_PROOF_FIELDS
        or proof.get("schemaVersion") != CLOSURE_PROOF_SCHEMA
        or proof.get("proofKind") != kind
        or proof.get("rootCauseGroupId") != group_id
        or proof.get("sourceIssueId") != issue_id
        or not isinstance(command, str)
        or not command
        or command.strip() != command
        or any(ord(character) < 32 for character in command)
        or not _is_nonnegative_int(proof.get("exitCode"))
        or (expected_exit is not None and proof["exitCode"] != expected_exit)
        or (kind == "RED" and proof["exitCode"] == 0)
        or proof.get("result") != expected_result
        or not _is_nonnegative_int(proof.get("assertionCount"))
        or proof["assertionCount"] == 0
        or not _is_lower_hex64(proof.get("outputSha256"))
        or proof.get("secretPatternHitCount") != 0
    ):
        raise AuditContractError("closure-proof-invalid")


def _validate_event_baseline_proof(
    proof: dict[str, Any],
    *,
    event: dict[str, Any],
    targets: Mapping[str, dict[str, Any]],
    changed_target_path: str | None = None,
    current_target_hash: str | None = None,
    current_target_size: int | None = None,
) -> None:
    if (
        set(proof) != CLOSURE_EVENT_BASELINE_FIELDS
        or proof.get("schemaVersion") != CLOSURE_EVENT_BASELINE_SCHEMA
        or proof.get("baselineId") != event["eventBaselineId"]
        or not _is_safe_branch_token(proof.get("branch"))
        or not isinstance(proof.get("head"), str)
        or re.fullmatch(r"[0-9a-f]{40}", proof["head"]) is None
        or not isinstance(proof.get("declaredTargets"), list)
    ):
        raise AuditContractError("closure-proof-invalid")
    rows = proof["declaredTargets"]
    expected_paths = [target["path"] for target in targets.values()]
    actual_paths: list[str] = []
    source_seen = False
    strict_target_binding = changed_target_path is not None
    expected_changed_path = changed_target_path or event["sourcePath"]
    allowed_states = {"clean", "modified", "deleted", "untracked", "renamed", "unmerged", "absent"}
    for row in rows:
        if not isinstance(row, dict) or set(row) != CLOSURE_EVENT_BASELINE_TARGET_FIELDS:
            raise AuditContractError("closure-proof-invalid")
        path = _normalize_repo_path(row.get("path"))
        if (
            row.get("gitState") not in allowed_states
            or not _is_nonnegative_int(row.get("sizeBytes"))
            or not _is_lower_hex64(row.get("sha256"))
        ):
            raise AuditContractError("closure-proof-invalid")
        actual_paths.append(path)
        if path == expected_changed_path:
            source_seen = True
            if (
                row["sha256"] != event["targetPostimageSha256"]
                or (
                    strict_target_binding
                    and (
                        row["sha256"] != current_target_hash
                        or row["sizeBytes"] != current_target_size
                    )
                )
            ):
                raise AuditContractError("closure-proof-invalid")
        elif strict_target_binding:
            frozen = targets.get(path.casefold())
            if frozen is None or any(
                row[field] != frozen[field]
                for field in ("gitState", "sizeBytes", "sha256")
            ):
                raise AuditContractError("closure-proof-invalid")
    if actual_paths != expected_paths or not source_seen:
        raise AuditContractError("closure-proof-invalid")


def _load_progress_rows(
    *,
    proof_root: Path,
    intake: ClosureWaveIntake,
) -> dict[str, ClosureProgressEntry]:
    candidate = proof_root.joinpath(*PROGRESS_RELATIVE.parts)
    if not candidate.exists():
        raise AuditContractError("closure-progress-mismatch")
    try:
        path = _closure_child(proof_root, PROGRESS_RELATIVE, "closure-progress-mismatch")
        rows = _parse_canonical_ndjson_bytes(
            _read_closure_bytes(path, "closure-progress-mismatch"),
            "closure-progress-mismatch",
        )
        if not isinstance(intake, ClosureWaveIntake):
            raise AuditContractError("closure-progress-mismatch")
        if intake.schema_version == CLOSURE_INTAKE_V3_SCHEMA:
            if rows:
                raise AuditContractError("closure-progress-mismatch")
            _validate_empty_wave_four_proof_namespace(proof_root)
            return {}
        if not rows and intake.schema_version == CLOSURE_INTAKE_V2_SCHEMA:
            if len(intake.predecessor_rows) != 1:
                raise AuditContractError("closure-admission-invalid")
            group_id = intake.predecessor_rows[0].get("rootCauseGroupId")
            for suffix in TARGET_ADMISSION_SUFFIXES.values():
                relative = _target_admission_proof_id(group_id, suffix)
                reserved = proof_root.joinpath(*PurePosixPath(relative).parts)
                if os.path.lexists(reserved):
                    raise AuditContractError("closure-admission-invalid")
        by_id: dict[str, ClosureProgressEntry] = {}
        for row in rows:
            target_admission: ClosureTargetAdmission | None
            if row.get("schemaVersion") == CLOSURE_PROGRESS_SCHEMA:
                if (
                    intake.schema_version != CLOSURE_INTAKE_SCHEMA
                    or set(row) != CLOSURE_PROGRESS_FIELDS
                ):
                    raise AuditContractError("closure-progress-mismatch")
                target_admission = None
            elif row.get("schemaVersion") == CLOSURE_PROGRESS_V2_SCHEMA:
                if (
                    intake.schema_version != CLOSURE_INTAKE_V2_SCHEMA
                    or set(row) != CLOSURE_PROGRESS_V2_FIELDS
                ):
                    raise AuditContractError("closure-progress-mismatch")
                target_admission = _load_target_admission(
                    proof_root=proof_root,
                    intake=intake,
                    expected_id=row.get("targetAdmissionId"),
                    expected_sha256=row.get("targetAdmissionSha256"),
                )
            else:
                raise AuditContractError("closure-progress-mismatch")
            patch_state_id = row.get("patchStateId")
            material = dict(row)
            material.pop("patchStateId", None)
            if (
                row.get("state") != "PATCHED_UNVERIFIED"
                or not _is_lower_hex64(patch_state_id)
                or patch_state_id != sha256_hex(canonical_json_bytes(material))
                or patch_state_id in by_id
                or not all(
                    _is_lower_hex64(row.get(field))
                    for field in (
                        "rootCauseGroupId",
                        "sourceIssueId",
                        "sourceBaselineId",
                        "eventBaselineId",
                        "targetPreimageSha256",
                        "targetPostimageSha256",
                        "scopedDiffSha256",
                        "redProofSha256",
                        "greenProofSha256",
                    )
                )
                or row["targetPreimageSha256"] == row["targetPostimageSha256"]
                or row["scopedDiffSha256"] == "0" * 64
            ):
                raise AuditContractError("closure-progress-mismatch")
            group_id = row["rootCauseGroupId"]
            _validate_proof_id(row.get("redProofId"), group_id, "red-summary")
            _validate_proof_id(row.get("greenProofId"), group_id, "green-summary")
            if target_admission is not None:
                predecessor = intake.predecessor_rows[0]
                target_row = target_admission.row
                repair_target_path = target_row["repairTargetPath"]
                frozen_target = intake.targets.get(repair_target_path.casefold())
                expected_links = {
                    "rootCauseGroupId": predecessor["rootCauseGroupId"],
                    "sourceIssueId": predecessor["issueId"],
                    "sourceBaselineId": predecessor["baselineId"],
                    "admissionDecisionId": intake.admission_decision["decisionId"],
                    "admissionDecisionSha256": intake.admission_decision_sha256,
                    "targetAdmissionId": target_row["targetAdmissionId"],
                    "targetAdmissionSha256": target_admission.raw_sha256,
                    "repairTargetPath": repair_target_path,
                    "targetPreimageSha256": frozen_target["sha256"] if frozen_target else None,
                }
                if any(row.get(field) != expected for field, expected in expected_links.items()):
                    raise AuditContractError("closure-progress-mismatch")
            by_id[patch_state_id] = ClosureProgressEntry(
                row=row,
                raw_sha256=sha256_hex(canonical_json_bytes(row)),
                target_admission=target_admission,
            )
        return by_id
    except AuditContractError as exc:
        if exc.reason_code in {
            "closure-progress-mismatch",
            "closure-admission-invalid",
            "closure-proof-invalid",
        }:
            raise
        raise AuditContractError("closure-progress-mismatch") from exc


def _validate_empty_wave_four_proof_namespace(proof_root: Path) -> None:
    proof_namespace = proof_root / "proofs"
    try:
        if not os.path.lexists(proof_namespace):
            return
        if (
            _has_reparse_component(proof_root, proof_namespace)
            or proof_namespace.is_symlink()
            or _has_reparse_attribute(proof_namespace)
            or not proof_namespace.is_dir()
        ):
            raise AuditContractError("closure-proof-invalid")
        with os.scandir(proof_namespace) as entries:
            if next(entries, None) is not None:
                raise AuditContractError("closure-proof-invalid")
    except AuditContractError:
        raise
    except OSError as exc:
        raise AuditContractError("closure-proof-invalid") from exc


def _source_supersession_hash(event: dict[str, Any]) -> str:
    material = "|".join(
        (
            event["sourceBaselineId"],
            event["sourceIssueId"],
            event["sourceEvidenceFingerprint"],
        )
    ).encode("utf-8")
    return sha256_hex(material)


def _select_active_closure_events(
    events: Sequence[dict[str, Any]],
) -> tuple[dict[str, Any], ...]:
    if not events:
        return ()
    events_by_id: dict[str, dict[str, Any]] = {}
    for event in events:
        event_id = event.get("eventId") if isinstance(event, dict) else None
        if (
            not _is_lower_hex64(event_id)
            or not _is_lower_hex64(event.get("rootCauseGroupId"))
            or not _is_lower_hex64(event.get("sourceIssueId"))
            or event_id in events_by_id
        ):
            raise AuditContractError("closure-event-conflict")
        supersedes = event.get("supersedesEventId")
        if supersedes is not None and not _is_lower_hex64(supersedes):
            raise AuditContractError("closure-event-conflict")
        events_by_id[event_id] = event

    referenced_counts: dict[str, int] = {}
    for event in events:
        supersedes = event["supersedesEventId"]
        if supersedes is None:
            continue
        predecessor_event = events_by_id.get(supersedes)
        if (
            predecessor_event is None
            or predecessor_event["rootCauseGroupId"] != event["rootCauseGroupId"]
            or predecessor_event["sourceIssueId"] != event["sourceIssueId"]
        ):
            raise AuditContractError("closure-event-conflict")
        referenced_counts[supersedes] = referenced_counts.get(supersedes, 0) + 1
        if referenced_counts[supersedes] > 1:
            raise AuditContractError("closure-event-conflict")
        seen: set[str] = set()
        cursor: dict[str, Any] | None = event
        while cursor is not None and cursor["supersedesEventId"] is not None:
            cursor_id = cursor["eventId"]
            if cursor_id in seen:
                raise AuditContractError("closure-event-conflict")
            seen.add(cursor_id)
            cursor = events_by_id.get(cursor["supersedesEventId"])
            if cursor is None:
                raise AuditContractError("closure-event-conflict")

    referenced = set(referenced_counts)
    tips_by_group: dict[str, list[dict[str, Any]]] = {}
    all_groups = {event["rootCauseGroupId"] for event in events}
    for event in events:
        if event["eventId"] not in referenced:
            tips_by_group.setdefault(event["rootCauseGroupId"], []).append(event)
    if set(tips_by_group) != all_groups or any(
        len(tips) != 1 for tips in tips_by_group.values()
    ):
        raise AuditContractError("closure-event-conflict")
    active_ids = {tips[0]["eventId"] for tips in tips_by_group.values()}
    return tuple(event for event in events if event["eventId"] in active_ids)


def _resolve_closure_wave_paths(
    *,
    root: Path,
    descriptor: ClosureWaveDescriptor,
) -> tuple[Path, Path, Path]:
    if not isinstance(descriptor, ClosureWaveDescriptor):
        raise AuditContractError("closure-journal-malformed")
    try:
        root_resolved = Path(root).resolve(strict=True)
    except (OSError, TypeError, ValueError) as exc:
        raise AuditContractError("closure-journal-malformed") from exc
    if not root_resolved.is_dir():
        raise AuditContractError("closure-journal-malformed")
    if (
        isinstance(descriptor.ordinal, bool)
        or not isinstance(descriptor.ordinal, int)
        or descriptor.ordinal <= 0
        or descriptor.wave_id != f"wave-{descriptor.ordinal:04d}"
        or not all(
            _is_lower_hex64(value)
            for value in (
                descriptor.source_baseline_id,
                descriptor.source_ledger_payload_sha256,
                descriptor.source_metrics_semantic_hash,
                descriptor.intake_summary_sha256,
                descriptor.eligible_groups_sha256,
                descriptor.target_preimages_sha256,
            )
        )
        or not isinstance(descriptor.journal_path, Path)
        or not isinstance(descriptor.proof_root, Path)
        or not descriptor.journal_path.is_absolute()
        or not descriptor.proof_root.is_absolute()
    ):
        raise AuditContractError("closure-journal-malformed")

    journal_candidate = descriptor.journal_path
    if _has_reparse_component(root_resolved, journal_candidate):
        raise AuditContractError("closure-journal-malformed")
    try:
        journal = journal_candidate.resolve(strict=False)
        journal.relative_to(root_resolved)
    except (OSError, ValueError) as exc:
        raise AuditContractError("closure-journal-malformed") from exc
    if _closure_path_key(journal_candidate) != _closure_path_key(journal):
        raise AuditContractError("closure-journal-malformed")

    proof_candidate = descriptor.proof_root
    if _has_reparse_component(root_resolved, proof_candidate):
        raise AuditContractError("closure-proof-invalid")
    try:
        proof_root = proof_candidate.resolve(strict=True)
        proof_root.relative_to(root_resolved)
    except (OSError, ValueError) as exc:
        raise AuditContractError("closure-proof-invalid") from exc
    if (
        not proof_root.is_dir()
        or _closure_path_key(proof_candidate) != _closure_path_key(proof_root)
    ):
        raise AuditContractError("closure-proof-invalid")
    return root_resolved, journal, proof_root


def _load_closure_wave_from_journal_bytes(
    *,
    root: Path,
    descriptor: ClosureWaveDescriptor,
    journal_bytes: bytes | None,
) -> ClosureWaveHistory:
    root_resolved, journal, proof_root = _resolve_closure_wave_paths(
        root=root,
        descriptor=descriptor,
    )
    intake = _load_closure_intake(proof_root, descriptor)
    predecessor_rows = intake.predecessor_rows
    targets = intake.targets
    progress_by_id = _load_progress_rows(proof_root=proof_root, intake=intake)
    if _has_reparse_component(root_resolved, journal):
        raise AuditContractError("closure-journal-malformed")
    if not journal.exists():
        raise AuditContractError("closure-journal-missing")
    if not journal.is_file():
        raise AuditContractError("closure-journal-malformed")
    if journal_bytes is None:
        candidate_bytes = _read_closure_bytes(journal, "closure-journal-missing")
    elif isinstance(journal_bytes, bytes):
        candidate_bytes = journal_bytes
    else:
        raise AuditContractError("closure-journal-malformed")
    if intake.schema_version == CLOSURE_INTAKE_V3_SCHEMA:
        if candidate_bytes != b"":
            raise AuditContractError("closure-journal-malformed")
        events = ()
    else:
        events = _parse_canonical_ndjson_bytes(
            candidate_bytes,
            "closure-journal-malformed",
        )
    for event in events:
        if not isinstance(event, dict):
            raise AuditContractError("closure-journal-malformed")
        if event.get("schemaVersion") == CLOSURE_EVENT_SCHEMA:
            expected_fields = CLOSURE_EVENT_FIELDS
            expected_intake = CLOSURE_INTAKE_SCHEMA
        elif event.get("schemaVersion") == CLOSURE_EVENT_V2_SCHEMA:
            expected_fields = CLOSURE_EVENT_V2_FIELDS
            expected_intake = CLOSURE_INTAKE_V2_SCHEMA
        else:
            raise AuditContractError("closure-journal-malformed")
        if set(event) != expected_fields or intake.schema_version != expected_intake:
            raise AuditContractError("closure-journal-malformed")

    predecessor_by_issue = {row["issueId"]: row for row in predecessor_rows}
    events_by_id: dict[str, dict[str, Any]] = {}
    proof_pairs: dict[str, str] = {}
    target_admissions_by_id: dict[str, dict[str, Any]] = {}
    for event in events:
        is_v2 = event["schemaVersion"] == CLOSURE_EVENT_V2_SCHEMA
        changed_target_path = (
            event.get("repairTargetPath") if is_v2 else event.get("sourcePath")
        )
        event_id = event.get("eventId")
        material = dict(event)
        material.pop("eventId", None)
        if (
            event.get("eventType") not in HISTORICAL_TERMINAL_CONTRACT
            or not _is_lower_hex64(event_id)
            or event_id != sha256_hex(canonical_json_bytes(material))
            or event_id in events_by_id
        ):
            raise AuditContractError("closure-event-conflict")
        predecessor = predecessor_by_issue.get(event.get("sourceIssueId"))
        if predecessor is None or any(
            event.get(event_field) != predecessor[row_field]
            for event_field, row_field in (
                ("rootCauseGroupId", "rootCauseGroupId"),
                ("sourceBaselineId", "baselineId"),
                ("sourceEvidenceFingerprint", "evidenceFingerprint"),
                ("sourcePath", "path"),
                ("sourceSymbol", "symbol"),
                ("sourceCategory", "category"),
                ("sourceNumericEvidence", "numericEvidence"),
            )
        ):
            raise AuditContractError("closure-intake-mismatch")
        if not all(
            _is_lower_hex64(event.get(field))
            for field in (
                "rootCauseGroupId",
                "sourceIssueId",
                "sourceBaselineId",
                "sourceEvidenceFingerprint",
                "eventBaselineId",
                "targetPreimageSha256",
                "targetPostimageSha256",
                "scopedDiffSha256",
                "redProofSha256",
                "greenProofSha256",
                "eventBaselineProofSha256",
            )
        ):
            raise AuditContractError("closure-journal-malformed")
        target = targets.get(str(changed_target_path).casefold())
        if target is None or target["sha256"] != event["targetPreimageSha256"]:
            raise AuditContractError("closure-intake-mismatch")
        if is_v2:
            decision = intake.admission_decision
            if (
                event.get("eventType") != "VERIFIED_CLOSED"
                or event.get("fingerprintDisposition") != "DISAPPEARED"
                or not isinstance(decision, dict)
                or event.get("admissionDecisionId") != decision.get("decisionId")
                or event.get("admissionDecisionSha256") != intake.admission_decision_sha256
            ):
                raise AuditContractError("closure-proof-invalid")
        if (
            event.get("fingerprintDisposition")
            not in {"DISAPPEARED", "ACCEPTED_BOUNDED_TRANSFORMATION"}
            or any(
                event.get(field) != "PASS"
                for field in (
                    "sourceSetGate",
                    "dependencyGate",
                    "compileGate",
                    "duplicateOwnerGate",
                    "desktopProof",
                )
            )
            or event.get("secretNewHitCount") != 0
        ):
            raise AuditContractError("closure-proof-invalid")

        current_target_hash: str | None = None
        current_target_size: int | None = None
        if is_v2:
            try:
                current_target = _safe_resolved_child(root_resolved, str(changed_target_path))
                current_target_bytes = current_target.read_bytes()
                current_target_hash = sha256_hex(current_target_bytes)
                current_target_size = len(current_target_bytes)
            except (AuditContractError, OSError) as exc:
                raise AuditContractError("closure-proof-invalid") from exc
            if current_target_hash != event["targetPostimageSha256"]:
                raise AuditContractError("closure-proof-invalid")

        group_id = event["rootCauseGroupId"]
        for id_field, hash_field, suffix, kind in (
            ("redProofId", "redProofSha256", "red-summary", "RED"),
            ("greenProofId", "greenProofSha256", "green-summary", "GREEN"),
        ):
            proof_id = _validate_proof_id(event.get(id_field), group_id, suffix)
            proof = _load_verified_proof(proof_root, proof_id, event.get(hash_field))
            _validate_red_green_proof(
                proof,
                kind=kind,
                group_id=group_id,
                issue_id=event["sourceIssueId"],
            )
            prior_hash = proof_pairs.get(proof_id)
            if prior_hash is not None and prior_hash != event[hash_field]:
                raise AuditContractError("closure-proof-invalid")
            proof_pairs[proof_id] = event[hash_field]

        baseline_proof_id = _validate_proof_id(
            event.get("eventBaselineProofId"),
            group_id,
            "event-baseline",
        )
        baseline_proof = _load_verified_proof(
            proof_root,
            baseline_proof_id,
            event.get("eventBaselineProofSha256"),
        )
        _validate_event_baseline_proof(
            baseline_proof,
            event=event,
            targets=targets,
            changed_target_path=str(changed_target_path) if is_v2 else None,
            current_target_hash=current_target_hash,
            current_target_size=current_target_size,
        )
        prior_hash = proof_pairs.get(baseline_proof_id)
        if prior_hash is not None and prior_hash != event["eventBaselineProofSha256"]:
            raise AuditContractError("closure-proof-invalid")
        proof_pairs[baseline_proof_id] = event["eventBaselineProofSha256"]

        if event["eventType"] == "REJECTED_FALSE_POSITIVE":
            if (
                event["targetPreimageSha256"] != event["targetPostimageSha256"]
                or event["scopedDiffSha256"] != "0" * 64
                or event.get("patchStateProofId") is not None
                or event.get("patchStateProofSha256") is not None
            ):
                raise AuditContractError("closure-proof-invalid")
        else:
            patch_state_id = event.get("patchStateProofId")
            patch_state_hash = event.get("patchStateProofSha256")
            progress_entry = progress_by_id.get(patch_state_id)
            if (
                not _is_lower_hex64(patch_state_id)
                or not _is_lower_hex64(patch_state_hash)
                or event["targetPreimageSha256"] == event["targetPostimageSha256"]
                or event["scopedDiffSha256"] == "0" * 64
                or progress_entry is None
                or progress_entry.raw_sha256 != patch_state_hash
            ):
                raise AuditContractError("closure-progress-mismatch")
            progress = progress_entry.row
            shared_fields = [
                "rootCauseGroupId",
                "sourceIssueId",
                "sourceBaselineId",
                "eventBaselineId",
                "targetPreimageSha256",
                "targetPostimageSha256",
                "scopedDiffSha256",
                "redProofId",
                "redProofSha256",
                "greenProofId",
                "greenProofSha256",
            ]
            if is_v2:
                shared_fields.extend(
                    (
                        "admissionDecisionId",
                        "admissionDecisionSha256",
                        "targetAdmissionId",
                        "targetAdmissionSha256",
                        "repairTargetPath",
                    )
                )
            if any(progress[field] != event[field] for field in shared_fields):
                raise AuditContractError("closure-progress-mismatch")
            if is_v2:
                target_admission = progress_entry.target_admission
                if target_admission is None:
                    raise AuditContractError("closure-progress-mismatch")
                admission = target_admission.row
                admission_links = {
                    "admissionDecisionId": admission["admissionDecisionId"],
                    "admissionDecisionSha256": admission["admissionDecisionSha256"],
                    "targetAdmissionId": admission["targetAdmissionId"],
                    "targetAdmissionSha256": target_admission.raw_sha256,
                    "repairTargetPath": admission["repairTargetPath"],
                }
                if any(event.get(field) != expected for field, expected in admission_links.items()):
                    raise AuditContractError("closure-progress-mismatch")
                target_admission_id = admission["targetAdmissionId"]
                prior_admission = target_admissions_by_id.get(target_admission_id)
                if prior_admission is not None and prior_admission != admission:
                    raise AuditContractError("closure-admission-invalid")
                target_admissions_by_id[target_admission_id] = copy.deepcopy(admission)
                for proof_id, proof_hash in target_admission.proof_pairs:
                    prior_hash = proof_pairs.get(proof_id)
                    if prior_hash is not None and prior_hash != proof_hash:
                        raise AuditContractError("closure-admission-invalid")
                    proof_pairs[proof_id] = proof_hash
            prior_hash = proof_pairs.get(patch_state_id)
            if prior_hash is not None and prior_hash != patch_state_hash:
                raise AuditContractError("closure-progress-mismatch")
            proof_pairs[patch_state_id] = patch_state_hash

        if not is_v2:
            try:
                current_target = _safe_resolved_child(root_resolved, event["sourcePath"])
                current_hash = sha256_hex(current_target.read_bytes())
            except (AuditContractError, OSError) as exc:
                raise AuditContractError("closure-proof-invalid") from exc
            if current_hash != event["targetPostimageSha256"]:
                raise AuditContractError("closure-proof-invalid")

        supersedes = event.get("supersedesEventId")
        if supersedes is not None and not _is_lower_hex64(supersedes):
            raise AuditContractError("closure-event-conflict")
        events_by_id[event_id] = event

    active_events = _select_active_closure_events(events)
    proof_pair_rows = tuple(
        sorted(proof_pairs.items(), key=lambda pair: (pair[0], pair[1]))
    )
    return ClosureWaveHistory(
        descriptor=descriptor,
        predecessor_rows=predecessor_rows,
        all_events=events,
        active_events=active_events,
        journal_payload_sha256=sha256_hex(candidate_bytes),
        proof_pairs=proof_pair_rows,
        target_admissions=tuple(
            target_admissions_by_id[target_admission_id]
            for target_admission_id in sorted(target_admissions_by_id)
        ),
    )


def load_closure_wave(
    *,
    root: Path,
    descriptor: ClosureWaveDescriptor,
) -> ClosureWaveHistory:
    return _load_closure_wave_from_journal_bytes(
        root=root,
        descriptor=descriptor,
        journal_bytes=None,
    )


def _path_is_equal_or_below(candidate: Path, parent: Path) -> bool:
    candidate_parts = tuple(part.casefold() for part in candidate.parts)
    parent_parts = tuple(part.casefold() for part in parent.parts)
    return (
        len(candidate_parts) >= len(parent_parts)
        and candidate_parts[: len(parent_parts)] == parent_parts
    )


def _validated_aggregate_wave_paths(
    *,
    root: Path,
    registry: ClosureRegistry,
) -> tuple[Path, tuple[ClosureWaveDescriptor, ...]]:
    try:
        root_resolved = Path(root).resolve(strict=True)
    except (OSError, TypeError, ValueError) as exc:
        raise AuditContractError("closure-wave-conflict") from exc
    if (
        not root_resolved.is_dir()
        or not isinstance(registry, ClosureRegistry)
        or not isinstance(registry.path, Path)
        or not isinstance(registry.waves, tuple)
        or not registry.waves
        or not _is_lower_hex64(registry.payload_sha256)
    ):
        raise AuditContractError("closure-wave-conflict")

    expected_registry_path = root_resolved.joinpath(*CLOSURE_WAVE_REGISTRY_RELATIVE.parts)
    if _has_reparse_component(root_resolved, expected_registry_path):
        raise AuditContractError("closure-wave-conflict")
    try:
        resolved_registry = registry.path.resolve(strict=True)
        resolved_registry.relative_to(root_resolved)
    except (OSError, ValueError) as exc:
        raise AuditContractError("closure-wave-conflict") from exc
    if (
        not resolved_registry.is_file()
        or _registry_path_identity(resolved_registry)
        != _registry_path_identity(expected_registry_path)
        or _registry_path_identity(registry.path)
        != _registry_path_identity(resolved_registry)
    ):
        raise AuditContractError("closure-wave-conflict")

    journals: list[Path] = []
    proof_roots: list[Path] = []
    journal_identities: set[str] = set()
    proof_identities: set[str] = set()
    seen_wave_ids: set[str] = set()
    seen_ordinals: set[int] = set()
    for expected_ordinal, descriptor in enumerate(registry.waves, start=1):
        if (
            not isinstance(descriptor, ClosureWaveDescriptor)
            or isinstance(descriptor.ordinal, bool)
            or not isinstance(descriptor.ordinal, int)
            or descriptor.ordinal != expected_ordinal
            or descriptor.ordinal in seen_ordinals
            or descriptor.wave_id != f"wave-{descriptor.ordinal:04d}"
            or descriptor.wave_id in seen_wave_ids
            or not isinstance(descriptor.journal_path, Path)
            or not descriptor.journal_path.is_absolute()
            or not isinstance(descriptor.proof_root, Path)
            or not descriptor.proof_root.is_absolute()
            or not all(
                _is_lower_hex64(value)
                for value in (
                    descriptor.source_baseline_id,
                    descriptor.source_ledger_payload_sha256,
                    descriptor.source_metrics_semantic_hash,
                    descriptor.intake_summary_sha256,
                    descriptor.eligible_groups_sha256,
                    descriptor.target_preimages_sha256,
                )
            )
        ):
            raise AuditContractError("closure-wave-conflict")
        seen_wave_ids.add(descriptor.wave_id)
        seen_ordinals.add(descriptor.ordinal)

        if (
            _has_reparse_component(root_resolved, descriptor.journal_path)
            or _has_reparse_component(root_resolved, descriptor.proof_root)
        ):
            raise AuditContractError("closure-wave-conflict")
        try:
            journal = descriptor.journal_path.resolve(strict=False)
            proof_root = descriptor.proof_root.resolve(strict=False)
            journal.relative_to(root_resolved)
            proof_root.relative_to(root_resolved)
        except (OSError, ValueError) as exc:
            raise AuditContractError("closure-wave-conflict") from exc
        if (
            (journal.exists() and not journal.is_file())
            or (proof_root.exists() and not proof_root.is_dir())
            or _registry_path_identity(descriptor.journal_path)
            != _registry_path_identity(journal)
            or _registry_path_identity(descriptor.proof_root)
            != _registry_path_identity(proof_root)
        ):
            raise AuditContractError("closure-wave-conflict")
        journal_identity = _registry_path_identity(journal)
        proof_identity = _registry_path_identity(proof_root)
        if (
            journal_identity in journal_identities
            or proof_identity in proof_identities
        ):
            raise AuditContractError("closure-wave-conflict")
        journal_identities.add(journal_identity)
        proof_identities.add(proof_identity)
        journals.append(journal)
        proof_roots.append(proof_root)

    for index, proof_root in enumerate(proof_roots):
        for other in proof_roots[index + 1 :]:
            if _path_is_equal_or_below(proof_root, other) or _path_is_equal_or_below(
                other, proof_root
            ):
                raise AuditContractError("closure-wave-conflict")
    for journal in journals:
        if any(
            _path_is_equal_or_below(journal, proof_root)
            for proof_root in proof_roots
        ):
            raise AuditContractError("closure-wave-conflict")
    return root_resolved, registry.waves


def load_closure_history(
    *,
    root: Path,
    registry: ClosureRegistry,
) -> ClosureHistory:
    root_resolved, descriptors = _validated_aggregate_wave_paths(
        root=root,
        registry=registry,
    )
    wave_histories: list[ClosureWaveHistory] = []
    for descriptor in descriptors:
        wave = load_closure_wave(root=root_resolved, descriptor=descriptor)
        if (
            not isinstance(wave, ClosureWaveHistory)
            or wave.descriptor != descriptor
            or not isinstance(wave.predecessor_rows, tuple)
            or not isinstance(wave.all_events, tuple)
            or not isinstance(wave.active_events, tuple)
            or not isinstance(wave.proof_pairs, tuple)
            or not isinstance(wave.target_admissions, tuple)
            or not _is_lower_hex64(wave.journal_payload_sha256)
        ):
            raise AuditContractError("closure-wave-conflict")
        wave_histories.append(wave)

    issue_owners: dict[str, str] = {}
    group_owners: dict[str, str] = {}
    path_owners: dict[str, str] = {}
    wave_predecessors: dict[str, dict[str, dict[str, Any]]] = {}
    for wave in wave_histories:
        issue_rows: dict[str, dict[str, Any]] = {}
        local_groups: set[str] = set()
        for row in wave.predecessor_rows:
            if not isinstance(row, dict):
                raise AuditContractError("closure-wave-conflict")
            issue_id = row.get("issueId")
            group_id = row.get("rootCauseGroupId")
            path = row.get("path")
            path_key = path.casefold() if isinstance(path, str) else None
            if (
                not _is_lower_hex64(issue_id)
                or not _is_lower_hex64(group_id)
                or issue_id in issue_rows
                or group_id in local_groups
                or issue_id in issue_owners
                or group_id in group_owners
                or (path_key is not None and path_key in path_owners)
            ):
                raise AuditContractError("closure-wave-conflict")
            issue_rows[issue_id] = row
            local_groups.add(group_id)
            issue_owners[issue_id] = wave.descriptor.wave_id
            group_owners[group_id] = wave.descriptor.wave_id
            if path_key is not None:
                path_owners[path_key] = wave.descriptor.wave_id
        wave_predecessors[wave.descriptor.wave_id] = issue_rows

    event_owners: dict[str, str] = {}
    for wave in wave_histories:
        for event in wave.all_events:
            if not isinstance(event, dict):
                raise AuditContractError("closure-wave-conflict")
            event_id = event.get("eventId")
            if not _is_lower_hex64(event_id) or event_id in event_owners:
                raise AuditContractError("closure-wave-conflict")
            event_owners[event_id] = wave.descriptor.wave_id

    target_admission_owners: dict[str, str] = {}
    wave_target_admissions: dict[str, dict[str, dict[str, Any]]] = {}
    for wave in wave_histories:
        wave_id = wave.descriptor.wave_id
        admission_by_id: dict[str, dict[str, Any]] = {}
        predecessor_by_issue = wave_predecessors[wave_id]
        for admission in wave.target_admissions:
            if (
                not isinstance(admission, dict)
                or set(admission) != CLOSURE_TARGET_ADMISSION_FIELDS
            ):
                raise AuditContractError("closure-wave-conflict")
            target_admission_id = admission.get("targetAdmissionId")
            issue_id = admission.get("sourceIssueId")
            group_id = admission.get("rootCauseGroupId")
            predecessor = predecessor_by_issue.get(issue_id)
            if (
                not _is_lower_hex64(target_admission_id)
                or target_admission_id in admission_by_id
                or target_admission_id in target_admission_owners
                or predecessor is None
                or predecessor.get("rootCauseGroupId") != group_id
                or group_owners.get(group_id) != wave_id
            ):
                raise AuditContractError("closure-wave-conflict")
            admission_by_id[target_admission_id] = admission
            target_admission_owners[target_admission_id] = wave_id
        wave_target_admissions[wave_id] = admission_by_id

    aggregate_active_events: list[dict[str, Any]] = []
    for wave in wave_histories:
        wave_id = wave.descriptor.wave_id
        predecessor_by_issue = wave_predecessors[wave_id]
        target_admission_by_id = wave_target_admissions[wave_id]
        proof_by_id: dict[str, str] = {}
        for pair in wave.proof_pairs:
            if (
                not isinstance(pair, tuple)
                or len(pair) != 2
                or not isinstance(pair[0], str)
                or not _is_lower_hex64(pair[1])
                or pair[0] in proof_by_id
            ):
                raise AuditContractError("closure-wave-conflict")
            proof_by_id[pair[0]] = pair[1]

        referenced_proofs: dict[str, str] = {}
        referenced_target_admission_ids: set[str] = set()
        local_seen: dict[str, dict[str, Any]] = {}
        referenced_event_counts: dict[str, int] = {}
        for event in wave.all_events:
            event_id = event["eventId"]
            issue_id = event.get("sourceIssueId")
            group_id = event.get("rootCauseGroupId")
            predecessor = predecessor_by_issue.get(issue_id)
            if (
                predecessor is None
                or predecessor.get("rootCauseGroupId") != group_id
                or event.get("eventType") not in HISTORICAL_TERMINAL_CONTRACT
            ):
                raise AuditContractError("closure-wave-conflict")

            for id_field, hash_field, suffix in (
                ("redProofId", "redProofSha256", "red-summary"),
                ("greenProofId", "greenProofSha256", "green-summary"),
                (
                    "eventBaselineProofId",
                    "eventBaselineProofSha256",
                    "event-baseline",
                ),
            ):
                try:
                    proof_id = _validate_proof_id(
                        event.get(id_field),
                        group_id,
                        suffix,
                    )
                except AuditContractError as exc:
                    raise AuditContractError("closure-wave-conflict") from exc
                proof_hash = event.get(hash_field)
                if (
                    not _is_lower_hex64(proof_hash)
                    or proof_by_id.get(proof_id) != proof_hash
                ):
                    raise AuditContractError("closure-wave-conflict")
                prior_hash = referenced_proofs.get(proof_id)
                if prior_hash is not None and prior_hash != proof_hash:
                    raise AuditContractError("closure-wave-conflict")
                referenced_proofs[proof_id] = proof_hash

            patch_state_id = event.get("patchStateProofId")
            patch_state_hash = event.get("patchStateProofSha256")
            if event["eventType"] == "VERIFIED_CLOSED":
                if (
                    not _is_lower_hex64(patch_state_id)
                    or not _is_lower_hex64(patch_state_hash)
                    or proof_by_id.get(patch_state_id) != patch_state_hash
                ):
                    raise AuditContractError("closure-wave-conflict")
                prior_hash = referenced_proofs.get(patch_state_id)
                if prior_hash is not None and prior_hash != patch_state_hash:
                    raise AuditContractError("closure-wave-conflict")
                referenced_proofs[patch_state_id] = patch_state_hash
            elif patch_state_id is not None or patch_state_hash is not None:
                raise AuditContractError("closure-wave-conflict")

            if event.get("schemaVersion") == CLOSURE_EVENT_V2_SCHEMA:
                target_admission_id = event.get("targetAdmissionId")
                admission = target_admission_by_id.get(target_admission_id)
                if admission is None:
                    raise AuditContractError("closure-wave-conflict")
                admission_material = {
                    key: value
                    for key, value in admission.items()
                    if key != "targetAdmissionId"
                }
                computed_id = sha256_hex(canonical_json_bytes(admission_material))
                computed_sha256 = sha256_hex(canonical_json_bytes(admission))
                if (
                    computed_id != target_admission_id
                    or event.get("targetAdmissionSha256") != computed_sha256
                    or event.get("rootCauseGroupId") != admission.get("rootCauseGroupId")
                    or event.get("sourceIssueId") != admission.get("sourceIssueId")
                    or event.get("admissionDecisionId")
                    != admission.get("admissionDecisionId")
                    or event.get("admissionDecisionSha256")
                    != admission.get("admissionDecisionSha256")
                    or event.get("repairTargetPath") != admission.get("repairTargetPath")
                ):
                    raise AuditContractError("closure-wave-conflict")
                try:
                    admission_proof_id = _target_admission_proof_id(
                        group_id,
                        TARGET_ADMISSION_SUFFIXES["admission"],
                    )
                    detector_proof_id = _target_admission_proof_id(
                        group_id,
                        TARGET_ADMISSION_SUFFIXES["detector"],
                    )
                    compile_proof_id = _target_admission_proof_id(
                        group_id,
                        TARGET_ADMISSION_SUFFIXES["compile"],
                    )
                    owner_proof_id = _target_admission_proof_id(
                        group_id,
                        TARGET_ADMISSION_SUFFIXES["owner"],
                    )
                except AuditContractError as exc:
                    raise AuditContractError("closure-wave-conflict") from exc
                if admission.get("detectorRedProofId") != detector_proof_id:
                    raise AuditContractError("closure-wave-conflict")
                supporting_pairs = (
                    (admission_proof_id, computed_sha256),
                    (detector_proof_id, admission.get("detectorRedProofSha256")),
                    (compile_proof_id, admission.get("compileBaselineProofSha256")),
                    (owner_proof_id, admission.get("ownerContractProofSha256")),
                )
                for proof_id, proof_hash in supporting_pairs:
                    if (
                        not _is_lower_hex64(proof_hash)
                        or proof_by_id.get(proof_id) != proof_hash
                    ):
                        raise AuditContractError("closure-wave-conflict")
                    prior_hash = referenced_proofs.get(proof_id)
                    if prior_hash is not None and prior_hash != proof_hash:
                        raise AuditContractError("closure-wave-conflict")
                    referenced_proofs[proof_id] = proof_hash
                referenced_target_admission_ids.add(target_admission_id)

            supersedes = event.get("supersedesEventId")
            if supersedes is not None:
                if not _is_lower_hex64(supersedes):
                    raise AuditContractError("closure-wave-conflict")
                owner = event_owners.get(supersedes)
                if owner != wave_id:
                    raise AuditContractError("closure-wave-conflict")
                predecessor_event = local_seen.get(supersedes)
                if (
                    predecessor_event is None
                    or predecessor_event.get("sourceIssueId") != issue_id
                    or predecessor_event.get("rootCauseGroupId") != group_id
                ):
                    raise AuditContractError("closure-wave-conflict")
                referenced_event_counts[supersedes] = (
                    referenced_event_counts.get(supersedes, 0) + 1
                )
                if referenced_event_counts[supersedes] > 1:
                    raise AuditContractError("closure-wave-conflict")
            local_seen[event_id] = event

        if (
            proof_by_id != referenced_proofs
            or set(target_admission_by_id) != referenced_target_admission_ids
        ):
            raise AuditContractError("closure-wave-conflict")
        referenced_event_ids = set(referenced_event_counts)
        tips_by_group: dict[str, list[dict[str, Any]]] = {}
        all_event_groups = {
            event["rootCauseGroupId"] for event in wave.all_events
        }
        for event in wave.all_events:
            if event["eventId"] not in referenced_event_ids:
                tips_by_group.setdefault(event["rootCauseGroupId"], []).append(event)
        if set(tips_by_group) != all_event_groups or any(
            len(tips) != 1 for tips in tips_by_group.values()
        ):
            raise AuditContractError("closure-wave-conflict")
        active_ids = {tips[0]["eventId"] for tips in tips_by_group.values()}
        expected_active = tuple(
            event for event in wave.all_events if event["eventId"] in active_ids
        )
        if wave.active_events != expected_active:
            raise AuditContractError("closure-wave-conflict")
        aggregate_active_events.extend(expected_active)

    predecessor_rows = tuple(
        row
        for wave in wave_histories
        for row in wave.predecessor_rows
    )
    all_events = tuple(
        event
        for wave in wave_histories
        for event in wave.all_events
    )
    active_events = tuple(aggregate_active_events)
    journal_rows = [
        [
            wave.descriptor.wave_id,
            wave.descriptor.journal_path.relative_to(root_resolved).as_posix(),
            wave.journal_payload_sha256,
        ]
        for wave in wave_histories
    ]
    journal_set_sha256 = sha256_hex(canonical_json_bytes(journal_rows))
    proof_rows = sorted(
        [wave.descriptor.wave_id, proof_id, file_sha256]
        for wave in wave_histories
        for proof_id, file_sha256 in wave.proof_pairs
    )
    proof_set_sha256 = sha256_hex(canonical_json_bytes(proof_rows))
    rejected_count = len(
        {
            event["rootCauseGroupId"]
            for event in active_events
            if event["eventType"] == "REJECTED_FALSE_POSITIVE"
        }
    )
    verified_count = len(
        {
            event["rootCauseGroupId"]
            for event in active_events
            if event["eventType"] == "VERIFIED_CLOSED"
        }
    )
    return ClosureHistory(
        predecessor_rows=predecessor_rows,
        all_events=all_events,
        active_events=active_events,
        wave_registry_sha256=registry.payload_sha256,
        wave_count=len(wave_histories),
        journal_set_sha256=journal_set_sha256,
        proof_set_sha256=proof_set_sha256,
        event_count=len(all_events),
        rejected_false_positive_root_cause_groups=rejected_count,
        verified_closed_root_cause_groups=verified_count,
    )


def validate_bundle(bundle: AuditBundle) -> None:
    if not isinstance(bundle, AuditBundle):
        raise AuditContractError("semantic-nondeterminism")
    if not isinstance(bundle.baseline, dict) or not isinstance(bundle.metrics, dict):
        raise AuditContractError("semantic-nondeterminism")
    if not isinstance(bundle.root, Path) or not isinstance(
        bundle.closure_registry,
        ClosureRegistry,
    ):
        raise AuditContractError("semantic-nondeterminism")
    try:
        root = bundle.root.resolve(strict=True)
    except OSError as exc:
        raise AuditContractError("semantic-nondeterminism") from exc
    if not root.is_dir() or _closure_path_key(root) != _closure_path_key(bundle.root):
        raise AuditContractError("semantic-nondeterminism")
    registry = _validated_audit_registry(
        root=root,
        supplied_path=bundle.closure_registry.path,
    )
    if registry != bundle.closure_registry:
        raise AuditContractError("closure-registry-mismatch")
    history = load_closure_history(root=root, registry=registry)
    predecessor_by_issue = {row["issueId"]: row for row in history.predecessor_rows}
    active_by_issue = {event["sourceIssueId"]: event for event in history.active_events}
    historical_predecessors = {
        issue_id: predecessor_by_issue[issue_id]
        for issue_id in active_by_issue
        if issue_id in predecessor_by_issue
    }
    if len(historical_predecessors) != len(active_by_issue):
        raise AuditContractError("closure-intake-mismatch")

    core_rows: list[dict[str, Any]] = []
    for row in bundle.ledger_rows:
        if not isinstance(row, dict) or set(row) != LEDGER_CORE_FIELDS | LEDGER_ENVELOPE_FIELDS:
            raise AuditContractError("public-artifact-secret-hit")
        core_rows.append(_strip_envelope(row))
    baseline_id = bundle.baseline.get("baselineId")
    _validate_ledger_core_rows(
        core_rows,
        expected_baseline_id=baseline_id,
        historical_predecessors=historical_predecessors,
    )
    terminal_by_issue = {
        row["issueId"]: row
        for row in core_rows
        if row["status"] in {"REJECTED_FALSE_POSITIVE", "VERIFIED_CLOSED"}
    }
    if set(terminal_by_issue) != set(active_by_issue):
        raise AuditContractError("semantic-nondeterminism")
    for issue_id, event in active_by_issue.items():
        expected_terminal = HISTORICAL_TERMINAL_CONTRACT[event["eventType"]]
        row = terminal_by_issue[issue_id]
        if (row["fixEligibility"], row["status"]) != expected_terminal:
            raise AuditContractError("semantic-nondeterminism")

    if set(bundle.baseline) != BASELINE_PAYLOAD_FIELDS | ENVELOPE_FIELDS:
        raise AuditContractError("public-artifact-secret-hit")
    if set(bundle.metrics) != METRICS_PAYLOAD_FIELDS | ENVELOPE_FIELDS | {
        "semanticArtifactHash"
    }:
        raise AuditContractError("public-artifact-secret-hit")
    baseline_payload = _strip_envelope(bundle.baseline)
    metrics_payload = _strip_envelope(bundle.metrics, metrics=True)
    if set(baseline_payload) != BASELINE_PAYLOAD_FIELDS or set(metrics_payload) != METRICS_PAYLOAD_FIELDS:
        raise AuditContractError("public-artifact-secret-hit")

    _validate_manifest_payload(baseline_payload)
    if (
        baseline_payload.get("schemaVersion") != BASELINE_SCHEMA
        or metrics_payload.get("schemaVersion") != METRICS_SCHEMA
        or metrics_payload.get("baselineId") != baseline_id
        or not isinstance(baseline_id, str)
        or not re.fullmatch(r"[0-9a-f]{64}", baseline_id)
    ):
        raise AuditContractError("semantic-nondeterminism")
    manifest_rows = baseline_payload["pathStateContentRows"]
    untracked_count = sum(row["gitState"] == "untracked" for row in manifest_rows)
    deleted_count = sum(row["gitState"] == "deleted" for row in manifest_rows)
    modified_count = sum(
        row["gitState"] in {"modified", "renamed", "unmerged"} for row in manifest_rows
    )
    baseline_scalars_valid = (
        isinstance(baseline_payload.get("canonicalRootHash"), str)
        and re.fullmatch(r"[0-9a-f]{64}", baseline_payload["canonicalRootHash"])
        and _is_nonnegative_int(baseline_payload.get("canonicalRootPathLength"))
        and isinstance(baseline_payload.get("head"), str)
        and re.fullmatch(r"[0-9a-f]{40}", baseline_payload["head"])
        and isinstance(baseline_payload.get("branch"), str)
        and re.fullmatch(r"[A-Za-z0-9._/-]+", baseline_payload["branch"])
        and baseline_payload.get("activeSourceSets") == ["rootMain", "appMainClean"]
        and baseline_payload.get("activeSourceSetConfigurationHash")
        == _source_set_configuration_hash()
        and baseline_payload.get("ignoredArtifactPolicyVersion")
        == GENERATED_ARTIFACT_POLICY_VERSION
        and baseline_payload.get("trackedPathCount") == len(manifest_rows) - untracked_count
        and baseline_payload.get("modifiedPathCount") == modified_count
        and baseline_payload.get("deletedPathCount") == deleted_count
        and baseline_payload.get("untrackedPathCount") == untracked_count
        and _is_nonnegative_int(baseline_payload.get("activeJavaFileCount"))
        and _is_nonnegative_int(baseline_payload.get("activeJavaBytesTotal"))
        and _is_nonnegative_int(baseline_payload.get("secretPatternHitCount"))
    )
    if not baseline_scalars_valid:
        raise AuditContractError("semantic-nondeterminism")
    expected_baseline_id = _compute_baseline_id(
        head=baseline_payload.get("head"),
        branch=baseline_payload.get("branch"),
        source_set_hash=baseline_payload.get("activeSourceSetConfigurationHash"),
        manifest_rows=baseline_payload["pathStateContentRows"],
    )
    if expected_baseline_id != baseline_id:
        raise AuditContractError("semantic-nondeterminism")

    links = bundle.baseline.get("artifactLinks")
    if not isinstance(links, dict) or set(links) != ARTIFACT_LINK_FIELDS or not all(
        isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value)
        for value in links.values()
    ):
        raise AuditContractError("artifact-link-mismatch")
    baseline_hash = sha256_hex(canonical_json_bytes(baseline_payload))
    ledger_hash = sha256_hex(canonical_ndjson_bytes(core_rows))
    metrics_hash = sha256_hex(canonical_json_bytes(metrics_payload))
    expected_links = {
        "baselinePayloadSha256": baseline_hash,
        "ledgerPayloadSha256": ledger_hash,
        "metricsPayloadSha256": metrics_hash,
    }
    expected_run_id = sha256_hex(
        (
            f"{AUDIT_SCHEMA}|{baseline_id}|{baseline_hash}|{ledger_hash}|{metrics_hash}|"
            f"{history.summary['waveRegistrySha256']}|"
            f"{history.summary['journalSetSha256']}|"
            f"{history.summary['proofSetSha256']}"
        ).encode("utf-8")
    )
    envelopes = [bundle.baseline, *bundle.ledger_rows, bundle.metrics]
    if (
        links != expected_links
        or bundle.metrics.get("semanticArtifactHash") != metrics_hash
        or any(value.get("artifactLinks") != links for value in envelopes)
        or any(value.get("auditRunId") != expected_run_id for value in envelopes)
    ):
        raise AuditContractError("artifact-link-mismatch")
    generated_values = {value.get("generatedAt") for value in envelopes}
    if len(generated_values) != 1:
        raise AuditContractError("semantic-nondeterminism")
    _parse_timestamp(next(iter(generated_values)))

    _validate_public_strings(bundle.baseline)
    _validate_public_strings(bundle.metrics)
    _validate_public_strings(bundle.ledger_rows)

    duplicate_fields = (
        "duplicateFqcnSourceCollisionCount",
        "duplicateFqcnGeneratedExcludeCount",
        "duplicateFqcnHardExcludeCount",
        "duplicateFqcnPackagedActiveCount",
        "duplicateFqcnActiveCount",
    )
    if not all(_is_nonnegative_int(metrics_payload.get(field)) for field in duplicate_fields):
        raise AuditContractError("duplicate-count-inconsistent")
    if (
        metrics_payload["duplicateFqcnSourceCollisionCount"]
        != metrics_payload["duplicateFqcnGeneratedExcludeCount"]
        + metrics_payload["duplicateFqcnHardExcludeCount"]
        + metrics_payload["duplicateFqcnPackagedActiveCount"]
        or metrics_payload["duplicateFqcnActiveCount"]
        != metrics_payload["duplicateFqcnPackagedActiveCount"]
    ):
        raise AuditContractError("duplicate-count-inconsistent")
    if (
        metrics_payload.get("activeJavaFileCount")
        != baseline_payload["activeJavaFileCount"]
        or metrics_payload.get("secretPatternHitCount")
        != baseline_payload["secretPatternHitCount"]
        or not _is_nonnegative_int(metrics_payload.get("activeJavaLocTotal"))
        or not _is_nonnegative_number(metrics_payload.get("activeJavaLocP95"))
        or not _is_nonnegative_int(metrics_payload.get("largeActiveFilesOver2000"))
        or metrics_payload["activeJavaLocP95"] > metrics_payload["activeJavaLocTotal"]
        or metrics_payload["largeActiveFilesOver2000"]
        > metrics_payload["activeJavaFileCount"]
        or (
            metrics_payload["activeJavaFileCount"] == 0
            and (
                metrics_payload["activeJavaLocTotal"] != 0
                or metrics_payload["activeJavaLocP95"] != 0
                or metrics_payload["largeActiveFilesOver2000"] != 0
            )
        )
        or metrics_payload.get("runtimeProviderDisabledSmoke")
        != {
            "status": "evidence_needed",
            "reason": "optional-provider-evidence-not-declared",
        }
        or metrics_payload.get("supabaseReadonlySmoke")
        != {
            "status": "evidence_needed",
            "reason": "project-scoped-readonly-evidence-not-declared",
            "readOnlyMode": True,
            "mutationAllowed": False,
        }
    ):
        raise AuditContractError("semantic-nondeterminism")
    harmony_summary = metrics_payload.get("harmonyPressureSummary")
    test_tree_summary = metrics_payload.get("testTreeContamination")
    if (
        not isinstance(harmony_summary, dict)
        or set(harmony_summary) != set(HARMONY_SUMMARY_FIELDS)
        or not all(_is_nonnegative_number(value) for value in harmony_summary.values())
        or not isinstance(test_tree_summary, dict)
        or set(test_tree_summary) != set(TEST_TREE_SUMMARY_FIELDS)
        or not all(_is_nonnegative_number(value) for value in test_tree_summary.values())
    ):
        raise AuditContractError("semantic-nondeterminism")
    summary = metrics_payload.get("ledgerSummary")
    closure_summary = metrics_payload.get("closureHistorySummary")
    if (
        not isinstance(summary, dict)
        or not isinstance(closure_summary, dict)
        or set(closure_summary) != CLOSURE_SUMMARY_FIELDS
        or closure_summary != history.summary
        or closure_summary.get("schemaVersion") != CLOSURE_HISTORY_SCHEMA
        or not all(
            _is_lower_hex64(closure_summary.get(field))
            for field in (
                "waveRegistrySha256",
                "journalSetSha256",
                "proofSetSha256",
            )
        )
        or not all(
            _is_nonnegative_int(closure_summary.get(field))
            for field in (
                "waveCount",
                "eventCount",
                "rejectedFalsePositiveRootCauseGroups",
                "verifiedClosedRootCauseGroups",
            )
        )
    ):
        raise AuditContractError("semantic-nondeterminism")
    emitted_groups = {row["rootCauseGroupId"] for row in core_rows}
    emitted_category_totals = {
        category: sum(row["category"] == category for row in core_rows)
        for category in sorted(CATEGORIES)
    }
    if (
        summary.get("emittedRows") != len(core_rows)
        or summary.get("emittedRootCauseGroups") != len(emitted_groups)
        or summary.get("emittedPerCategoryTotals") != emitted_category_totals
        or summary.get("targetFloor") != TARGET_FLOOR
        or summary.get("targetCeiling") != TARGET_CEILING
        or summary.get("verifiedClosedRootCauseGroups")
        != closure_summary["verifiedClosedRootCauseGroups"]
        or summary.get("targetGap")
        != max(
            0,
            TARGET_FLOOR
            - (
                summary.get("eligibleRootCauseGroups", 0)
                + summary.get("verifiedClosedRootCauseGroups", 0)
            ),
        )
        or not _is_nonnegative_int(summary.get("overflowRows"))
        or summary.get("totalRows") != len(core_rows) + summary["overflowRows"]
        or summary.get("truncated") != (summary["overflowRows"] > 0)
    ):
        raise AuditContractError("semantic-nondeterminism")


def load_and_validate_current_bundle(*, root: Path) -> AuditBundle:
    try:
        root_resolved = Path(root).resolve(strict=True)
    except (OSError, TypeError, ValueError) as exc:
        raise AuditContractError("required-input-missing") from exc
    if not root_resolved.is_dir():
        raise AuditContractError("required-input-missing")

    payloads: list[bytes] = []
    for relative in (
        AUDIT_BASELINE_OUTPUT_RELATIVE,
        AUDIT_LEDGER_OUTPUT_RELATIVE,
        AUDIT_METRICS_OUTPUT_RELATIVE,
    ):
        try:
            path = _safe_resolved_child(root_resolved, relative)
        except AuditContractError as exc:
            if exc.reason_code == "active-root-missing":
                raise AuditContractError("required-input-missing") from exc
            raise
        payloads.append(_read_required_bytes(path))

    baseline = _parse_canonical_json_bytes(payloads[0], "required-input-malformed")
    ledger_rows = _parse_canonical_ndjson_bytes(payloads[1], "required-input-malformed")
    metrics = _parse_canonical_json_bytes(payloads[2], "required-input-malformed")
    registry = load_closure_registry(
        root=root_resolved,
        registry_path=CLOSURE_WAVE_REGISTRY_RELATIVE,
    )
    bundle = AuditBundle(
        baseline=baseline,
        metrics=metrics,
        ledger_rows=ledger_rows,
        root=root_resolved,
        closure_registry=registry,
    )
    validate_bundle(bundle)
    return bundle


def _write_closed_temp(destination: Path, payload: bytes) -> Path:
    if not destination.parent.is_dir():
        raise OSError("output parent unavailable")
    handle = tempfile.NamedTemporaryFile(
        mode="wb",
        prefix=destination.name + ".tmp-",
        dir=destination.parent,
        delete=False,
    )
    temp_path = Path(handle.name)
    try:
        with handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
    except Exception:
        try:
            temp_path.unlink(missing_ok=True)
        except OSError:
            pass
        raise
    return temp_path


def _bundle_from_serialized(
    baseline_bytes: bytes,
    ledger_bytes: bytes,
    metrics_bytes: bytes,
    *,
    root: Path,
    closure_registry: ClosureRegistry,
) -> AuditBundle:
    try:
        baseline = _parse_canonical_json_bytes(
            baseline_bytes,
            "output-replace-failed",
        )
        ledger_rows = _parse_canonical_ndjson_bytes(
            ledger_bytes,
            "output-replace-failed",
        )
        metrics = _parse_canonical_json_bytes(
            metrics_bytes,
            "output-replace-failed",
        )
    except AuditContractError as exc:
        raise AuditContractError("output-replace-failed") from exc
    return AuditBundle(
        baseline=baseline,
        metrics=metrics,
        ledger_rows=ledger_rows,
        root=root,
        closure_registry=closure_registry,
    )


def _validated_output_destinations(
    bundle: AuditBundle,
    outputs: AuditOutputs,
) -> tuple[Path, Path, Path]:
    try:
        root = Path(bundle.root).resolve(strict=True)
    except OSError as exc:
        raise AuditContractError("output-replace-failed") from exc
    if not root.is_dir():
        raise AuditContractError("output-replace-failed")
    supplied = (
        Path(outputs.baseline_output),
        Path(outputs.ledger_output),
        Path(outputs.metrics_output),
    )
    relatives = (
        AUDIT_BASELINE_OUTPUT_RELATIVE,
        AUDIT_LEDGER_OUTPUT_RELATIVE,
        AUDIT_METRICS_OUTPUT_RELATIVE,
    )
    validated: list[Path] = []
    for candidate, relative in zip(supplied, relatives):
        expected = root.joinpath(*PurePosixPath(relative).parts)
        if (
            not candidate.is_absolute()
            or _closure_path_key(candidate) != _closure_path_key(expected)
            or not expected.parent.is_dir()
            or _has_reparse_component(root, expected.parent)
            or _has_reparse_component(root, expected)
            or (expected.exists() and not expected.is_file())
        ):
            raise AuditContractError("output-replace-failed")
        try:
            expected.parent.resolve(strict=True).relative_to(root)
        except (OSError, ValueError) as exc:
            raise AuditContractError("output-replace-failed") from exc
        validated.append(expected)
    destination_keys = {_closure_path_key(path) for path in validated}
    if len(destination_keys) != len(validated):
        raise AuditContractError("output-replace-failed")
    return validated[0], validated[1], validated[2]


def publish_bundle(bundle: AuditBundle, outputs: AuditOutputs) -> None:
    validate_bundle(bundle)
    if not isinstance(outputs, AuditOutputs):
        raise AuditContractError("output-replace-failed")
    destinations = _validated_output_destinations(bundle, outputs)
    payloads = (
        canonical_json_bytes(bundle.baseline),
        canonical_ndjson_bytes(bundle.ledger_rows),
        canonical_json_bytes(bundle.metrics),
    )
    prior: dict[Path, bytes | None] = {}
    temp_paths: dict[Path, Path] = {}
    rollback_paths: dict[Path, Path] = {}
    committed = False
    restoration_complete = True
    try:
        for destination, payload in zip(destinations, payloads):
            try:
                prior[destination] = destination.read_bytes() if destination.exists() else None
                temp_paths[destination] = _write_closed_temp(destination, payload)
            except OSError as exc:
                raise AuditContractError("output-replace-failed") from exc

        staged_payloads = tuple(
            temp_paths[destination].read_bytes() for destination in destinations
        )
        if staged_payloads != payloads:
            raise AuditContractError("output-replace-failed")
        reread_bundle = _bundle_from_serialized(
            *staged_payloads,
            root=bundle.root,
            closure_registry=bundle.closure_registry,
        )
        validate_bundle(reread_bundle)

        for destination in destinations:
            prior_payload = prior[destination]
            if prior_payload is None:
                continue
            rollback = destination.with_name(destination.name + ".rollback")
            rollback_temp = _write_closed_temp(rollback, prior_payload)
            try:
                os.replace(rollback_temp, rollback)
            finally:
                rollback_temp.unlink(missing_ok=True)
            rollback_paths[destination] = rollback

        final_staged_payloads = tuple(
            temp_paths[destination].read_bytes() for destination in destinations
        )
        if final_staged_payloads != payloads:
            raise AuditContractError("output-replace-failed")
        final_staged_bundle = _bundle_from_serialized(
            *final_staged_payloads,
            root=bundle.root,
            closure_registry=bundle.closure_registry,
        )
        validate_bundle(final_staged_bundle)

        for destination in destinations:
            os.replace(temp_paths[destination], destination)
        validate_bundle(final_staged_bundle)
        committed = True
    except Exception as exc:
        for destination in reversed(destinations):
            try:
                prior_payload = prior.get(destination)
                rollback = rollback_paths.get(destination)
                if prior_payload is None:
                    destination.unlink(missing_ok=True)
                elif rollback is not None and rollback.exists():
                    os.replace(rollback, destination)
                elif not destination.exists() or destination.read_bytes() != prior_payload:
                    restore_temp = _write_closed_temp(destination, prior_payload)
                    try:
                        os.replace(restore_temp, destination)
                    finally:
                        restore_temp.unlink(missing_ok=True)
            except Exception:
                # Do not discard the recovery sidecar when the filesystem also
                # refuses restoration.  The public reason remains count-safe.
                restoration_complete = False
        raise AuditContractError("output-replace-failed") from exc
    finally:
        for temp_path in temp_paths.values():
            try:
                temp_path.unlink(missing_ok=True)
            except OSError:
                pass
        if committed or restoration_complete:
            for rollback in rollback_paths.values():
                try:
                    rollback.unlink(missing_ok=True)
                except OSError:
                    pass
    if not committed:
        raise AuditContractError("output-replace-failed")


def _argument_path(root: Path, value: str) -> Path:
    candidate = Path(value)
    return candidate if candidate.is_absolute() else root / candidate


def _fixed_output_argument_path(root: Path, value: str, expected_relative: str) -> Path:
    try:
        if Path(value).is_absolute():
            raise AuditContractError("output-replace-failed")
        normalized = _normalize_repo_path(value)
    except AuditContractError as exc:
        raise AuditContractError("output-replace-failed") from exc
    if normalized != expected_relative:
        raise AuditContractError("output-replace-failed")
    return root.joinpath(*PurePosixPath(expected_relative).parts)


def _fixed_registry_argument_path(root: Path, value: str) -> Path:
    expected_relative = CLOSURE_WAVE_REGISTRY_RELATIVE.as_posix()
    try:
        if Path(value).is_absolute():
            raise AuditContractError("closure-registry-malformed")
        normalized = _normalize_repo_path(value)
    except AuditContractError as exc:
        raise AuditContractError("closure-registry-malformed") from exc
    if normalized != expected_relative:
        raise AuditContractError("closure-registry-malformed")
    return root.joinpath(*CLOSURE_WAVE_REGISTRY_RELATIVE.parts)


def _build_parser() -> argparse.ArgumentParser:
    parser = _RedactedArgumentParser(description="Generate the structural design audit bundle")
    parser.add_argument("--root", required=True)
    parser.add_argument("--active-java-root", action="append", required=True)
    parser.add_argument("--git-head-input", required=True)
    parser.add_argument("--git-branch-input", required=True)
    parser.add_argument("--git-paths-input", required=True)
    parser.add_argument("--git-status-input", required=True)
    parser.add_argument("--git-skip-worktree-input", required=True)
    parser.add_argument("--harmony-input", required=True)
    parser.add_argument("--test-tree-input", required=True)
    parser.add_argument("--dup-fqcn-input", required=True)
    parser.add_argument("--closure-wave-registry", required=True)
    parser.add_argument("--metrics-output", required=True)
    parser.add_argument("--baseline-output", required=True)
    parser.add_argument("--ledger-output", required=True)
    parser.add_argument("--candidate-cap", type=int, default=DEFAULT_CANDIDATE_CAP)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    try:
        args = _build_parser().parse_args(argv)
        root = Path(args.root).resolve(strict=True)
        inputs = AuditInputs(
            root=root,
            active_java_roots=tuple(args.active_java_root),
            git_head_input=_argument_path(root, args.git_head_input),
            git_branch_input=_argument_path(root, args.git_branch_input),
            git_paths_input=_argument_path(root, args.git_paths_input),
            git_status_input=_argument_path(root, args.git_status_input),
            git_skip_worktree_input=_argument_path(root, args.git_skip_worktree_input),
            harmony_input=_argument_path(root, args.harmony_input),
            test_tree_input=_argument_path(root, args.test_tree_input),
            dup_fqcn_input=_argument_path(root, args.dup_fqcn_input),
            closure_wave_registry=_fixed_registry_argument_path(
                root,
                args.closure_wave_registry,
            ),
            candidate_cap=args.candidate_cap,
        )
        outputs = AuditOutputs(
            metrics_output=_fixed_output_argument_path(
                root,
                args.metrics_output,
                AUDIT_METRICS_OUTPUT_RELATIVE,
            ),
            baseline_output=_fixed_output_argument_path(
                root,
                args.baseline_output,
                AUDIT_BASELINE_OUTPUT_RELATIVE,
            ),
            ledger_output=_fixed_output_argument_path(
                root,
                args.ledger_output,
                AUDIT_LEDGER_OUTPUT_RELATIVE,
            ),
        )
        bundle = build_audit(inputs, datetime.now(timezone.utc))
        publish_bundle(bundle, outputs)
        summary = bundle.metrics["ledgerSummary"]
        print(
            "[AWX][structural-audit] status=ok "
            f"rows={summary['emittedRows']} groups={summary['emittedRootCauseGroups']} "
            f"overflow={summary['overflowRows']}"
        )
        return 0
    except AuditContractError as exc:
        print(f"[AWX][structural-audit] reason={exc.reason_code}", file=sys.stderr)
        return 2
    except Exception:
        print("[AWX][structural-audit] reason=semantic-nondeterminism", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
