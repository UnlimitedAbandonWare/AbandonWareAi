#!/usr/bin/env python3
"""Independently validate random-probe v1 ledgers or inspect legacy Markdown."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from collections import Counter
from pathlib import Path, PurePosixPath


ROW_SCHEMA = "awx.random-probe-candidate-row.v1"
MANIFEST_SCHEMA = "awx.random-probe-candidate-ledger.v1"
VALIDATION_SCHEMA = "awx.random-probe-candidate-validation.v1"
LEGACY_SCHEMA = "awx.random-probe-legacy-inspection.v1"
ACTIVE_ROOTS = (
    "main/java",
    "main/resources",
    "app/src/main/java_clean",
    "app/src/main/resources",
)
CANDIDATE_ROOTS = ("main/java", "app/src/main/java_clean")
TRACKING_STATES = {
    "clean_tracked",
    "modified_tracked",
    "untracked",
    "ignored_or_unknown",
}
PROBE_FAMILIES = {
    "broad_catch_without_local_breadcrumb",
    "locale_sensitive_case_normalization",
    "unbounded_collection_or_read",
    "unbounded_input_or_numeric_conversion",
    "optional_dependency_fail_soft_boundary",
    "no_static_pattern",
}
HEX64_RE = re.compile(r"[0-9a-f]{64}\Z")
SEED_RE = re.compile(r"[A-Za-z0-9._-]{1,64}\Z")

ROW_FIELDS = {
    "candidateId",
    "eligibility",
    "identityHash",
    "lineHints",
    "nextAction",
    "ordinal",
    "probeFamily",
    "rankKey",
    "repoPath",
    "schemaVersion",
    "sourceContentHash",
    "sourceRoot",
    "staticEvidence",
    "status",
    "trackingState",
}
STATIC_EVIDENCE_FIELDS = {
    "matchCount",
    "providerAttemptObserved",
    "rawContentStored",
    "runtimeBehaviorObserved",
}
MANIFEST_FIELDS = {
    "activeRoots",
    "applicationSourceWritten",
    "candidateRoots",
    "dedup",
    "externalCallsAllowed",
    "generatedAt",
    "maxRecords",
    "mutationAllowed",
    "ndjsonPath",
    "ndjsonSha256",
    "pathOrderAlgorithm",
    "pathOrderHash",
    "rankingAlgorithm",
    "recordCounts",
    "root",
    "rootInventory",
    "schemaVersion",
    "seed",
    "sourceSnapshot",
}
DEDUP_FIELDS = {
    "duplicateInputObservationCount",
    "identityAlgorithm",
    "uniqueIdentityCount",
}
RECORD_COUNT_FIELDS = {"byProbeFamily", "byRoot", "candidate", "emitted", "excludedDirty"}
SOURCE_SNAPSHOT_FIELDS = {
    "cleanTrackedJavaCount",
    "ignoredOrUnknownJavaCount",
    "modifiedTrackedJavaCount",
    "sourceContentSetHash",
    "statusSnapshotHash",
    "trackedPathCount",
    "untrackedJavaCount",
}
ROOT_INVENTORY_FIELDS = {"fileCount", "javaFileCount"}

SECRET_PATTERNS = (
    re.compile(r"sk-[A-Za-z0-9_-]{20,}"),
    re.compile(r"AIza[0-9A-Za-z_-]{20,}"),
    re.compile(r"gsk_[A-Za-z0-9_-]{20,}"),
    re.compile(r"pcsk_[A-Za-z0-9_-]{20,}"),
    re.compile(r"sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}"),
    re.compile(r"sbp_[A-Za-z0-9_-]{10,}"),
    re.compile(r"Authorization:"),
    re.compile(r"Cookie:"),
)


class LedgerValidationError(ValueError):
    def __init__(self, reason_code: str, *, secret_pattern_hits: int = 0) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code
        self.secret_pattern_hits = secret_pattern_hits


def sha256_hex(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def canonical_json_bytes(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def normalize_repo_path(path: str) -> str:
    if not isinstance(path, str) or not path.strip():
        raise LedgerValidationError("path-invalid")
    candidate = path.replace("\\", "/")
    if candidate.startswith("/") or re.match(r"^[A-Za-z]:", candidate) or "://" in candidate:
        raise LedgerValidationError("path-invalid")
    pure = PurePosixPath(candidate)
    if any(part in ("", ".", "..") for part in pure.parts):
        raise LedgerValidationError("path-invalid")
    return pure.as_posix()


def identity_path(path: str) -> str:
    return normalize_repo_path(path).lower()


def _is_hex64(value: object) -> bool:
    return isinstance(value, str) and HEX64_RE.fullmatch(value) is not None


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _require_exact_fields(value: object, fields: set[str]) -> dict:
    if not isinstance(value, dict) or set(value) != fields:
        raise LedgerValidationError("field-set-invalid")
    return value


def _secret_hits(payload: bytes) -> int:
    text = payload.decode("utf-8", errors="replace")
    return sum(len(pattern.findall(text)) for pattern in SECRET_PATTERNS)


def read_ndjson(path: Path) -> tuple[list[dict], bytes]:
    try:
        payload = Path(path).read_bytes()
        text = payload.decode("utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        raise LedgerValidationError("ndjson-read-invalid") from exc
    rows: list[dict] = []
    for raw_line in text.splitlines():
        if not raw_line.strip():
            raise LedgerValidationError("ndjson-blank-line")
        try:
            value = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            raise LedgerValidationError("ndjson-json-invalid") from exc
        if not isinstance(value, dict):
            raise LedgerValidationError("ndjson-row-invalid")
        rows.append(value)
    return rows, payload


def _read_manifest(path: Path) -> tuple[dict, bytes]:
    try:
        payload = Path(path).read_bytes()
        value = json.loads(payload.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise LedgerValidationError("manifest-read-invalid") from exc
    if not isinstance(value, dict):
        raise LedgerValidationError("manifest-read-invalid")
    return value, payload


def _validate_manifest_shape(manifest: dict, expected_max_records: int) -> None:
    _require_exact_fields(manifest, MANIFEST_FIELDS)
    if manifest["schemaVersion"] != MANIFEST_SCHEMA:
        raise LedgerValidationError("schema-invalid")
    if manifest["activeRoots"] != list(ACTIVE_ROOTS) or manifest["candidateRoots"] != list(CANDIDATE_ROOTS):
        raise LedgerValidationError("root-contract-invalid")
    if manifest["root"] != ".":
        raise LedgerValidationError("root-contract-invalid")
    if not _is_int(expected_max_records) or not 1 <= expected_max_records <= 1000:
        raise LedgerValidationError("count-out-of-range")
    if not _is_int(manifest["maxRecords"]) or not 1 <= manifest["maxRecords"] <= 1000:
        raise LedgerValidationError("count-out-of-range")
    if manifest["maxRecords"] != expected_max_records:
        raise LedgerValidationError("max-records-mismatch")
    if not isinstance(manifest["seed"], str) or SEED_RE.fullmatch(manifest["seed"]) is None:
        raise LedgerValidationError("seed-invalid")
    if manifest["mutationAllowed"] is not False or manifest["externalCallsAllowed"] is not False or manifest["applicationSourceWritten"] is not False:
        raise LedgerValidationError("mutation-contract-invalid")
    if not _is_hex64(manifest["pathOrderHash"]):
        raise LedgerValidationError("hash-invalid")
    if not _is_hex64(manifest["ndjsonSha256"]):
        raise LedgerValidationError("hash-invalid")
    normalize_repo_path(manifest["ndjsonPath"])

    dedup = _require_exact_fields(manifest["dedup"], DEDUP_FIELDS)
    if not _is_int(dedup["duplicateInputObservationCount"]) or dedup["duplicateInputObservationCount"] < 0:
        raise LedgerValidationError("count-out-of-range")
    if not _is_int(dedup["uniqueIdentityCount"]) or dedup["uniqueIdentityCount"] < 0:
        raise LedgerValidationError("count-out-of-range")

    counts = _require_exact_fields(manifest["recordCounts"], RECORD_COUNT_FIELDS)
    for field in ("candidate", "emitted", "excludedDirty"):
        if not _is_int(counts[field]) or counts[field] < 0:
            raise LedgerValidationError("count-out-of-range")
    if not isinstance(counts["byProbeFamily"], dict) or not isinstance(counts["byRoot"], dict):
        raise LedgerValidationError("field-set-invalid")

    snapshot = _require_exact_fields(manifest["sourceSnapshot"], SOURCE_SNAPSHOT_FIELDS)
    for field in (
        "cleanTrackedJavaCount",
        "ignoredOrUnknownJavaCount",
        "modifiedTrackedJavaCount",
        "trackedPathCount",
        "untrackedJavaCount",
    ):
        if not _is_int(snapshot[field]) or snapshot[field] < 0:
            raise LedgerValidationError("count-out-of-range")
    for field in ("sourceContentSetHash", "statusSnapshotHash"):
        if not _is_hex64(snapshot[field]):
            raise LedgerValidationError("hash-invalid")

    inventory = manifest["rootInventory"]
    if not isinstance(inventory, dict) or set(inventory) != set(ACTIVE_ROOTS):
        raise LedgerValidationError("root-contract-invalid")
    for item in inventory.values():
        item = _require_exact_fields(item, ROOT_INVENTORY_FIELDS)
        if any(not _is_int(item[field]) or item[field] < 0 for field in ROOT_INVENTORY_FIELDS):
            raise LedgerValidationError("count-out-of-range")


def _validate_row_fields(rows: list[dict]) -> None:
    for row in rows:
        _require_exact_fields(row, ROW_FIELDS)
        _require_exact_fields(row["staticEvidence"], STATIC_EVIDENCE_FIELDS)
        if row["schemaVersion"] != ROW_SCHEMA:
            raise LedgerValidationError("schema-invalid")


def _validate_uniqueness(rows: list[dict]) -> None:
    candidate_ids = [row["candidateId"] for row in rows]
    if len(candidate_ids) != len(set(candidate_ids)):
        raise LedgerValidationError("duplicate-id")
    identity_pairs = [(row["repoPath"], row["probeFamily"]) for row in rows]
    if len(identity_pairs) != len(set(identity_pairs)):
        raise LedgerValidationError("duplicate-identity")
    identity_hashes = [row["identityHash"] for row in rows]
    if len(identity_hashes) != len(set(identity_hashes)):
        raise LedgerValidationError("duplicate-identity")


def _validate_ordinals(rows: list[dict]) -> None:
    expected = list(range(1, len(rows) + 1))
    actual = [row["ordinal"] for row in rows]
    if any(not _is_int(value) for value in actual) or actual != expected:
        raise LedgerValidationError("ordinal-gap")


def _validate_path_and_claims(rows: list[dict]) -> None:
    for row in rows:
        repo_path = normalize_repo_path(row["repoPath"])
        source_root = row["sourceRoot"]
        if source_root not in CANDIDATE_ROOTS or not repo_path.startswith(f"{source_root}/") or not repo_path.lower().endswith(".java"):
            raise LedgerValidationError("path-invalid")
        if row["probeFamily"] not in PROBE_FAMILIES:
            raise LedgerValidationError("probe-family-invalid")
        if row["trackingState"] not in TRACKING_STATES:
            raise LedgerValidationError("tracking-state-invalid")
        if row["eligibility"] not in {"candidate", "excluded_dirty"}:
            raise LedgerValidationError("eligibility-invalid")
        if row["trackingState"] != "clean_tracked" and row["eligibility"] != "excluded_dirty":
            raise LedgerValidationError("dirty-eligible")
        if row["eligibility"] == "candidate" and row["trackingState"] != "clean_tracked":
            raise LedgerValidationError("dirty-eligible")
        if row["status"] != "UNTRIAGED":
            raise LedgerValidationError("status-claim-invalid")
        if row["nextAction"] != "require_active_call_path_and_smallest_disconfirming_probe":
            raise LedgerValidationError("next-action-invalid")


def _validate_hashes_and_evidence(rows: list[dict], seed: str) -> None:
    for row in rows:
        for field in ("identityHash", "rankKey", "sourceContentHash"):
            if not _is_hex64(row[field]):
                raise LedgerValidationError("hash-invalid")
        identity_material = f"{identity_path(row['repoPath'])}\0{row['probeFamily']}".encode("utf-8")
        expected_identity = sha256_hex(identity_material)
        if row["identityHash"] != expected_identity or row["candidateId"] != f"rpc1-{expected_identity[:16]}":
            raise LedgerValidationError("identity-hash-invalid")
        rank_material = f"{seed}\0{identity_path(row['repoPath'])}\0{row['probeFamily']}".encode("utf-8")
        if row["rankKey"] != sha256_hex(rank_material):
            raise LedgerValidationError("rank-hash-invalid")

        line_hints = row["lineHints"]
        if not isinstance(line_hints, list) or len(line_hints) > 8:
            raise LedgerValidationError("line-hints-invalid")
        if any(not _is_int(value) or value <= 0 for value in line_hints):
            raise LedgerValidationError("line-hints-invalid")
        if line_hints != sorted(set(line_hints)):
            raise LedgerValidationError("line-hints-invalid")
        evidence = row["staticEvidence"]
        if not _is_int(evidence["matchCount"]) or evidence["matchCount"] < 0:
            raise LedgerValidationError("evidence-invalid")
        if row["probeFamily"] == "no_static_pattern" and evidence["matchCount"] != 0:
            raise LedgerValidationError("evidence-invalid")
        if row["probeFamily"] != "no_static_pattern" and evidence["matchCount"] < 1:
            raise LedgerValidationError("evidence-invalid")
        if any(
            evidence[field] is not False
            for field in ("providerAttemptObserved", "rawContentStored", "runtimeBehaviorObserved")
        ):
            raise LedgerValidationError("evidence-claim-invalid")


def _validate_rank_order(rows: list[dict]) -> None:
    keys = [(row["rankKey"], identity_path(row["repoPath"]), row["probeFamily"]) for row in rows]
    if keys != sorted(keys):
        raise LedgerValidationError("rank-order-invalid")


def _validate_manifest_against_rows(manifest: dict, rows: list[dict], payload: bytes) -> None:
    if manifest["ndjsonSha256"] != sha256_hex(payload):
        raise LedgerValidationError("ndjson-hash-mismatch")
    counts = manifest["recordCounts"]
    by_family = dict(sorted(Counter(row["probeFamily"] for row in rows).items()))
    by_root = dict(sorted(Counter(row["sourceRoot"] for row in rows).items()))
    candidate = sum(row["eligibility"] == "candidate" for row in rows)
    excluded = sum(row["eligibility"] == "excluded_dirty" for row in rows)
    expected = {
        "byProbeFamily": by_family,
        "byRoot": by_root,
        "candidate": candidate,
        "emitted": len(rows),
        "excludedDirty": excluded,
    }
    if counts != expected:
        raise LedgerValidationError("manifest-count-mismatch")
    if manifest["dedup"]["uniqueIdentityCount"] != len(rows):
        raise LedgerValidationError("manifest-count-mismatch")
    if not 1 <= len(rows) <= manifest["maxRecords"]:
        raise LedgerValidationError("count-out-of-range")


def validate_v1(
    ndjson_path: Path,
    manifest_path: Path,
    expected_max_records: int,
) -> dict:
    rows, ndjson_payload = read_ndjson(Path(ndjson_path))
    manifest, manifest_payload = _read_manifest(Path(manifest_path))
    secret_pattern_hits = _secret_hits(ndjson_payload) + _secret_hits(manifest_payload)
    if secret_pattern_hits:
        raise LedgerValidationError(
            "secret-pattern-hit",
            secret_pattern_hits=secret_pattern_hits,
        )
    _validate_manifest_shape(manifest, expected_max_records)
    _validate_row_fields(rows)
    _validate_uniqueness(rows)
    _validate_ordinals(rows)
    _validate_path_and_claims(rows)
    _validate_hashes_and_evidence(rows, manifest["seed"])
    _validate_rank_order(rows)
    _validate_manifest_against_rows(manifest, rows, ndjson_payload)
    return {
        "rawContentStored": False,
        "recordCounts": dict(manifest["recordCounts"]),
        "schemaVersion": VALIDATION_SCHEMA,
        "secretPatternHits": 0,
        "verdict": "VALID",
    }


def inspect_legacy_markdown(path: Path) -> dict:
    try:
        text = Path(path).read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        raise LedgerValidationError("legacy-read-invalid") from exc
    lines = text.splitlines()
    in_candidate_section = False
    candidate_ids: list[str] = []
    for line in lines:
        if re.match(r"^#{1,6}\s+Candidate Rows\s*$", line, re.IGNORECASE):
            in_candidate_section = True
            continue
        if in_candidate_section and re.match(r"^#{1,6}\s+", line):
            break
        if not in_candidate_section or not line.lstrip().startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if not cells or cells[0].lower() in {"id", "candidate", "candidate id"}:
            continue
        if set(cells[0]) <= {"-", ":", " "}:
            continue
        if re.fullmatch(r"[A-Za-z][A-Za-z0-9._-]*", cells[0]):
            candidate_ids.append(cells[0])
    duplicate_count = len(candidate_ids) - len(set(candidate_ids))
    historical_ambiguity = bool(
        re.search(r"historical\s+closure|supersedes\s+earlier|post-closure|continuation", text, re.IGNORECASE)
    )
    return {
        "duplicatePrimaryIds": duplicate_count,
        "historicalSectionAmbiguity": historical_ambiguity,
        "primaryCandidateTableRowCount": len(candidate_ids),
        "rawContentStored": False,
        "schemaVersion": LEGACY_SCHEMA,
        "uniquePrimaryIds": len(set(candidate_ids)),
        "verdict": "EVIDENCE_NEEDED",
    }


def _atomic_publish(destination: Path, payload: bytes) -> None:
    destination = destination.resolve()
    relative = normalize_repo_path(destination.relative_to(Path.cwd().resolve()).as_posix())
    lowered = relative.lower()
    if any(
        lowered == prefix or lowered.startswith(f"{prefix}/")
        for prefix in (".git", "build", "main", "app/src/main", "src/test", "scripts")
    ):
        raise LedgerValidationError("output-path-forbidden")
    if destination.exists():
        raise LedgerValidationError("output-exists")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            dir=destination.parent,
            prefix=f".{destination.name}.",
            suffix=".tmp",
            delete=False,
        ) as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
            temp_name = handle.name
        os.link(temp_name, destination)
    except (FileExistsError, ValueError) as exc:
        raise LedgerValidationError("output-exists") from exc
    finally:
        if temp_name is not None:
            Path(temp_name).unlink(missing_ok=True)


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--input")
    mode.add_argument("--legacy-markdown")
    parser.add_argument("--manifest")
    parser.add_argument("--expected-max-records", type=int)
    parser.add_argument("--output")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    try:
        if args.input:
            if not args.manifest or args.expected_max_records is None:
                raise LedgerValidationError("v1-arguments-missing")
            result = validate_v1(
                Path(args.input),
                Path(args.manifest),
                args.expected_max_records,
            )
        else:
            if args.manifest or args.expected_max_records is not None:
                raise LedgerValidationError("legacy-arguments-invalid")
            result = inspect_legacy_markdown(Path(args.legacy_markdown))
        payload = canonical_json_bytes(result) + b"\n"
        if args.output:
            _atomic_publish(Path(args.output), payload)
        sys.stdout.buffer.write(payload)
        return 0
    except LedgerValidationError as exc:
        sys.stderr.write(
            canonical_json_bytes(
                {
                    "reasonCode": exc.reason_code,
                    "secretPatternHits": exc.secret_pattern_hits,
                    "verdict": "REJECT",
                }
            ).decode("utf-8")
            + "\n"
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
