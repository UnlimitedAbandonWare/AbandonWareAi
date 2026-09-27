#!/usr/bin/env python3
"""Build a deterministic, non-authoritative active-source probe inventory."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Iterable


ROW_SCHEMA = "awx.random-probe-candidate-row.v1"
MANIFEST_SCHEMA = "awx.random-probe-candidate-ledger.v1"
READY_SCHEMA = "awx.random-probe-candidate-ready.v1"
ACTIVE_ROOTS = (
    "main/java",
    "main/resources",
    "app/src/main/java_clean",
    "app/src/main/resources",
)
CANDIDATE_ROOTS = ("main/java", "app/src/main/java_clean")
MAX_LINE_HINTS = 8
SEED_RE = re.compile(r"[A-Za-z0-9._-]{1,64}\Z")
HEX64_RE = re.compile(r"[0-9a-f]{64}\Z")

PROBE_PATTERNS = {
    "locale_sensitive_case_normalization": re.compile(
        r"\.to(?:Lower|Upper)Case\s*\(\s*\)"
    ),
    "unbounded_collection_or_read": re.compile(
        r"(?:\.findAll\s*\(|Files\.readAll(?:Bytes|Lines)\s*\(|\.readAllBytes\s*\()"
    ),
    "unbounded_input_or_numeric_conversion": re.compile(
        r"(?:Integer|Long|Double|Float|Short|Byte)\.parse(?:Int|Long|Double|Float|Short|Byte)\s*\("
    ),
    "optional_dependency_fail_soft_boundary": re.compile(
        r"(?:@Autowired\s*\(\s*required\s*=\s*false\s*\)|Optional\s*<[^>]+>)"
    ),
}
BROAD_CATCH_RE = re.compile(r"catch\s*\(\s*(?:final\s+)?(?:Exception|RuntimeException|Throwable)\b")
BREADCRUMB_RE = re.compile(
    r"(?:\blog(?:ger)?\s*\.|TraceStore|DebugEventStore|breadcrumb|trace\s*\()",
    re.IGNORECASE,
)

FORBIDDEN_OUTPUT_PREFIXES = (
    ".git",
    "build",
    "main",
    "app/src/main",
    "src/test",
    "scripts",
)


class LedgerContractError(ValueError):
    def __init__(self, reason_code: str) -> None:
        super().__init__(reason_code)
        self.reason_code = reason_code


def sha256_hex(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def canonical_json_bytes(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def canonical_ndjson_bytes(rows: list[dict]) -> bytes:
    if not rows:
        return b""
    return b"\n".join(canonical_json_bytes(row) for row in rows) + b"\n"


def normalize_repo_path(path: str) -> str:
    if not isinstance(path, str) or not path.strip():
        raise LedgerContractError("path-invalid")
    candidate = path.replace("\\", "/")
    if candidate.startswith("/") or re.match(r"^[A-Za-z]:", candidate):
        raise LedgerContractError("path-invalid")
    if "://" in candidate:
        raise LedgerContractError("path-invalid")
    pure = PurePosixPath(candidate)
    if any(part in ("", ".", "..") for part in pure.parts):
        raise LedgerContractError("path-invalid")
    return pure.as_posix()


def identity_path(repo_path: str) -> str:
    return normalize_repo_path(repo_path).lower()


def _tracking_state(code: str) -> str:
    if code == "??":
        return "untracked"
    if code == "!!":
        return "ignored_or_unknown"
    return "modified_tracked"


def parse_porcelain(status_text: str) -> dict[str, str]:
    states: dict[str, str] = {}
    for raw_line in status_text.splitlines():
        if len(raw_line) < 4:
            continue
        code = raw_line[:2]
        raw_path = raw_line[3:]
        if " -> " in raw_path:
            raw_path = raw_path.rsplit(" -> ", 1)[1]
        if raw_path.startswith('"') and raw_path.endswith('"'):
            try:
                raw_path = json.loads(raw_path)
            except json.JSONDecodeError as exc:
                raise LedgerContractError("git-status-path-invalid") from exc
        normalized = normalize_repo_path(raw_path)
        states[identity_path(normalized)] = _tracking_state(code)
    return states


def _is_within(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _iter_files(root: Path, relative_root: str, suffix: str | None = None) -> Iterable[Path]:
    base = (root / relative_root).resolve()
    if not base.is_dir() or not _is_within(base, root.resolve()):
        return ()
    result: list[Path] = []
    pattern = f"*{suffix}" if suffix else "*"
    for path in base.rglob(pattern):
        if not path.is_file() or path.is_symlink():
            continue
        resolved = path.resolve()
        if not _is_within(resolved, base):
            continue
        result.append(path)
    return tuple(sorted(result, key=lambda value: value.relative_to(root).as_posix().lower()))


def discover_active_java(root: Path) -> list[tuple[str, str]]:
    resolved_root = root.resolve()
    found: list[tuple[str, str]] = []
    for source_root in CANDIDATE_ROOTS:
        for path in _iter_files(resolved_root, source_root, ".java"):
            repo_path = normalize_repo_path(path.relative_to(resolved_root).as_posix())
            found.append((repo_path, source_root))
    found.sort(key=lambda item: (identity_path(item[0]), item[1]))
    return found


def _line_numbers(pattern: re.Pattern[str], lines: list[str]) -> list[int]:
    return [index for index, line in enumerate(lines, start=1) if pattern.search(line)]


def _broad_catch_without_breadcrumb(lines: list[str]) -> list[int]:
    matches: list[int] = []
    for index, line in enumerate(lines):
        if not BROAD_CATCH_RE.search(line):
            continue
        window = "\n".join(lines[index : min(len(lines), index + 4)])
        if not BREADCRUMB_RE.search(window):
            matches.append(index + 1)
    return matches


def probe_java(text: str) -> list[tuple[str, list[int], int]]:
    lines = text.splitlines()
    observations: list[tuple[str, list[int], int]] = []
    broad = _broad_catch_without_breadcrumb(lines)
    if broad:
        observations.append(
            (
                "broad_catch_without_local_breadcrumb",
                broad[:MAX_LINE_HINTS],
                len(broad),
            )
        )
    for family, pattern in PROBE_PATTERNS.items():
        matches = _line_numbers(pattern, lines)
        if matches:
            observations.append((family, matches[:MAX_LINE_HINTS], len(matches)))
    if not observations:
        observations.append(("no_static_pattern", [], 0))
    return observations


def _validate_contract(seed: str, max_records: int) -> None:
    if not isinstance(seed, str) or not SEED_RE.fullmatch(seed):
        raise LedgerContractError("seed-invalid")
    if isinstance(max_records, bool) or not isinstance(max_records, int) or not 1 <= max_records <= 1000:
        raise LedgerContractError("max-records-invalid")


def _root_inventory(root: Path) -> dict[str, dict[str, int]]:
    inventory: dict[str, dict[str, int]] = {}
    for relative_root in ACTIVE_ROOTS:
        files = tuple(_iter_files(root, relative_root))
        inventory[relative_root] = {
            "fileCount": len(files),
            "javaFileCount": sum(1 for path in files if path.suffix.lower() == ".java"),
        }
    return inventory


def build_ledger(
    root: Path,
    seed: str,
    max_records: int,
    status_text: str,
) -> tuple[list[dict], dict]:
    _validate_contract(seed, max_records)
    resolved_root = Path(root).resolve()
    if not resolved_root.is_dir():
        raise LedgerContractError("root-invalid")

    status_by_path = parse_porcelain(status_text)
    java_paths = discover_active_java(resolved_root)
    path_order_material = "\n".join(identity_path(path) for path, _ in java_paths).encode("utf-8")

    source_set_parts: list[bytes] = []
    all_rows: list[dict] = []
    file_states: Counter[str] = Counter()
    duplicate_observations = 0
    for repo_path, source_root in java_paths:
        source_path = (resolved_root / Path(repo_path)).resolve()
        if not _is_within(source_path, resolved_root):
            raise LedgerContractError("path-invalid")
        content = source_path.read_bytes()
        content_hash = sha256_hex(content)
        source_set_parts.append(
            identity_path(repo_path).encode("utf-8") + b"\0" + content_hash.encode("ascii")
        )
        try:
            text = content.decode("utf-8")
        except UnicodeDecodeError:
            text = content.decode("utf-8", errors="replace")
        tracking_state = status_by_path.get(identity_path(repo_path), "clean_tracked")
        file_states[tracking_state] += 1
        eligibility = "candidate" if tracking_state == "clean_tracked" else "excluded_dirty"
        for probe_family, line_hints, match_count in probe_java(text):
            duplicate_observations += max(0, match_count - 1)
            identity_material = f"{identity_path(repo_path)}\0{probe_family}".encode("utf-8")
            identity_hash = sha256_hex(identity_material)
            rank_material = f"{seed}\0{identity_path(repo_path)}\0{probe_family}".encode("utf-8")
            all_rows.append(
                {
                    "candidateId": f"rpc1-{identity_hash[:16]}",
                    "eligibility": eligibility,
                    "identityHash": identity_hash,
                    "lineHints": line_hints,
                    "nextAction": "require_active_call_path_and_smallest_disconfirming_probe",
                    "probeFamily": probe_family,
                    "rankKey": sha256_hex(rank_material),
                    "repoPath": repo_path,
                    "schemaVersion": ROW_SCHEMA,
                    "sourceContentHash": content_hash,
                    "sourceRoot": source_root,
                    "staticEvidence": {
                        "matchCount": match_count,
                        "providerAttemptObserved": False,
                        "rawContentStored": False,
                        "runtimeBehaviorObserved": False,
                    },
                    "status": "UNTRIAGED",
                    "trackingState": tracking_state,
                }
            )

    all_rows.sort(key=lambda row: (row["rankKey"], identity_path(row["repoPath"]), row["probeFamily"]))
    rows = all_rows[:max_records]
    for ordinal, row in enumerate(rows, start=1):
        row["ordinal"] = ordinal

    family_counts = Counter(row["probeFamily"] for row in rows)
    root_counts = Counter(row["sourceRoot"] for row in rows)
    eligibility_counts = Counter(row["eligibility"] for row in rows)
    source_content_set_hash = sha256_hex(b"\n".join(source_set_parts))
    active_identity_paths = {identity_path(path) for path, _ in java_paths}
    normalized_status = "\n".join(
        f"{path}\0{state}"
        for path, state in sorted(status_by_path.items())
        if path in active_identity_paths
    )
    manifest = {
        "activeRoots": list(ACTIVE_ROOTS),
        "applicationSourceWritten": False,
        "candidateRoots": list(CANDIDATE_ROOTS),
        "dedup": {
            "duplicateInputObservationCount": duplicate_observations,
            "identityAlgorithm": "sha256:utf8:lower-path-nul-probe-family:v1",
            "uniqueIdentityCount": len({row["identityHash"] for row in rows}),
        },
        "externalCallsAllowed": False,
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "maxRecords": max_records,
        "mutationAllowed": False,
        "ndjsonPath": None,
        "ndjsonSha256": None,
        "pathOrderAlgorithm": "sha256:utf8:lowercase-repo-relative-path:v1",
        "pathOrderHash": sha256_hex(path_order_material),
        "rankingAlgorithm": "sha256:utf8:seed-nul-lower-path-nul-probe-family:v1",
        "recordCounts": {
            "byProbeFamily": dict(sorted(family_counts.items())),
            "byRoot": dict(sorted(root_counts.items())),
            "candidate": eligibility_counts["candidate"],
            "emitted": len(rows),
            "excludedDirty": eligibility_counts["excluded_dirty"],
        },
        "root": ".",
        "rootInventory": _root_inventory(resolved_root),
        "schemaVersion": MANIFEST_SCHEMA,
        "seed": seed,
        "sourceSnapshot": {
            "cleanTrackedJavaCount": file_states["clean_tracked"],
            "ignoredOrUnknownJavaCount": file_states["ignored_or_unknown"],
            "modifiedTrackedJavaCount": file_states["modified_tracked"],
            "sourceContentSetHash": source_content_set_hash,
            "statusSnapshotHash": sha256_hex(normalized_status.encode("utf-8")),
            "trackedPathCount": file_states["clean_tracked"] + file_states["modified_tracked"],
            "untrackedJavaCount": file_states["untracked"],
        },
    }
    return rows, manifest


def validate_output_path(root: Path, output: Path) -> Path:
    resolved_root = Path(root).resolve()
    resolved_output = Path(output).resolve()
    if not _is_within(resolved_output, resolved_root):
        raise LedgerContractError("output-path-outside-root")
    relative = normalize_repo_path(resolved_output.relative_to(resolved_root).as_posix())
    lowered = relative.lower()
    if any(lowered == prefix or lowered.startswith(f"{prefix}/") for prefix in FORBIDDEN_OUTPUT_PREFIXES):
        raise LedgerContractError("output-path-forbidden")
    return resolved_output


def _infer_root(output: Path) -> Path:
    for candidate in (output.parent, *output.parents):
        if any((candidate / source_root).exists() for source_root in CANDIDATE_ROOTS):
            return candidate.resolve()
    raise LedgerContractError("root-inference-failed")


def _atomic_publish_bytes(destination: Path, payload: bytes) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        raise LedgerContractError("output-exists")
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
    except FileExistsError as exc:
        raise LedgerContractError("output-exists") from exc
    finally:
        if temp_name is not None:
            Path(temp_name).unlink(missing_ok=True)


def publish_artifacts(
    output: Path,
    manifest_output: Path,
    rows: list[dict],
    manifest: dict,
    root: Path | None = None,
) -> None:
    resolved_output = Path(output).resolve()
    resolved_manifest = Path(manifest_output).resolve()
    resolved_root = Path(root).resolve() if root is not None else _infer_root(resolved_output)
    validate_output_path(resolved_root, resolved_output)
    validate_output_path(resolved_root, resolved_manifest)
    if resolved_output == resolved_manifest:
        raise LedgerContractError("output-path-collision")

    sha_path = Path(f"{resolved_output}.sha256")
    ready_path = Path(f"{resolved_output}.ready")
    for candidate in (resolved_output, resolved_manifest, sha_path, ready_path):
        if candidate.exists():
            raise LedgerContractError("output-exists")

    ndjson_bytes = canonical_ndjson_bytes(rows)
    ndjson_hash = sha256_hex(ndjson_bytes)
    published_manifest = json.loads(json.dumps(manifest))
    published_manifest["ndjsonPath"] = resolved_output.relative_to(resolved_root).as_posix()
    published_manifest["ndjsonSha256"] = ndjson_hash
    manifest_bytes = canonical_json_bytes(published_manifest) + b"\n"
    ready = {
        "applicationSourceWritten": False,
        "externalCallsAllowed": False,
        "manifestSha256": sha256_hex(manifest_bytes),
        "mutationAllowed": False,
        "ndjsonSha256": ndjson_hash,
        "schemaVersion": READY_SCHEMA,
    }

    _atomic_publish_bytes(resolved_output, ndjson_bytes)
    _atomic_publish_bytes(resolved_manifest, manifest_bytes)
    _atomic_publish_bytes(
        sha_path,
        f"{ndjson_hash}  {resolved_output.name}\n".encode("ascii"),
    )
    _atomic_publish_bytes(ready_path, canonical_json_bytes(ready) + b"\n")


def _git_status(root: Path) -> str:
    result = subprocess.run(
        ["git", "status", "--porcelain=v1", "--untracked-files=all"],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
    )
    if result.returncode != 0:
        raise LedgerContractError("git-status-failed")
    return result.stdout


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True)
    parser.add_argument("--seed", required=True)
    parser.add_argument("--max-records", required=True, type=int)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--output")
    mode.add_argument("--stdout", action="store_true")
    parser.add_argument("--manifest-output")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    try:
        if args.stdout and args.manifest_output:
            raise LedgerContractError("manifest-output-forbidden-with-stdout")
        if args.output and not args.manifest_output:
            raise LedgerContractError("manifest-output-required")
        root = Path(args.root).resolve()
        status_text = _git_status(root)
        rows, manifest = build_ledger(root, args.seed, args.max_records, status_text)
        if args.stdout:
            sys.stdout.buffer.write(canonical_ndjson_bytes(rows))
            sys.stderr.write(
                canonical_json_bytes(
                    {
                        "recordCounts": manifest["recordCounts"],
                        "schemaVersion": manifest["schemaVersion"],
                        "seed": manifest["seed"],
                    }
                ).decode("utf-8")
                + "\n"
            )
            return 0
        output = validate_output_path(root, root / args.output)
        manifest_output = validate_output_path(root, root / args.manifest_output)
        publish_artifacts(output, manifest_output, rows, manifest, root=root)
        sys.stdout.write(
            canonical_json_bytes(
                {
                    "manifest": manifest_output.relative_to(root).as_posix(),
                    "output": output.relative_to(root).as_posix(),
                    "recordCounts": manifest["recordCounts"],
                    "schemaVersion": manifest["schemaVersion"],
                }
            ).decode("utf-8")
            + "\n"
        )
        return 0
    except LedgerContractError as exc:
        sys.stderr.write(
            canonical_json_bytes(
                {"reasonCode": exc.reason_code, "status": "REJECT"}
            ).decode("utf-8")
            + "\n"
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
