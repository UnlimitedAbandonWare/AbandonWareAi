#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
persist_build_error_patterns.py
- Scans build logs and analysis reports to persist aggregated build error patterns.
- Writes to .build/error_patterns_db.json and updates BUILD_ERROR_PATTERNS.json / BUILD_ERROR_PATTERN_SUMMARY.md.
Usage:
  python scripts/persist_build_error_patterns.py --ingest build-logs --ingest ../some/other/src111_merge15/build-logs
"""
import os, re, json, argparse, datetime, hashlib, tempfile
from pathlib import Path

LOG_PATTERNS = {
    "java.cannot_find_symbol": re.compile(r"cannot find symbol", re.I),
    "java.package_does_not_exist": re.compile(r"package\s+[a-zA-Z0-9_.]+\s+does not exist", re.I),
    "java.incompatible_types": re.compile(r"incompatible types", re.I),
    "java.method_arg_mismatch": re.compile(r"method\s+.*\s+in\s+class\s+.*\s+cannot\s+be\s+applied\s+to\s+given\s+types", re.I),
    "java.reference_ambiguous": re.compile(r"reference to\s+\w+\s+is\s+ambiguous", re.I),
    "java.inference_variable_bounds": re.compile(r"inference variable [A-Z]\w* has incompatible bounds", re.I),
    "java.class_interface_expected": re.compile(r"class,\s*interface,\s*enum,\s*or\s*record\s*expected", re.I),
    "java.illegal_escape_character": re.compile(r"illegal escape character", re.I),
    "gradle.build_failed": re.compile(r"FAILURE:\s*Build failed with an exception", re.I),
    "gradle.dependency_resolution": re.compile(r"Could not resolve [^:]+:[^:]+", re.I),
    "gradle.lms_core_not_found": re.compile(r":lms-core not found|project\s+:lms-core\s+not\s+found|Project with path ':lms-core' could not be found|Could not resolve project :lms-core", re.I),
    "maven.build_failure": re.compile(r"^\[INFO\] BUILD FAILURE", re.I | re.M),
    "lombok.missing": re.compile(r"Lombok processor not found|package lombok does not exist", re.I),
}

SAFE_SOURCE_REFERENCE = re.compile(r"source_sha256:[0-9a-f]{64}")
SAFE_EXAMPLE_REFERENCE = re.compile(
    r"(?:source_sha256:[0-9a-f]{64}:matched|legacy_example_sha256:[0-9a-f]{64})"
)

def read_text(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="ignore") if p.exists() else ""

def _atomic_write_text(path: Path, text: str) -> None:
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as handle:
            temp_path = Path(handle.name)
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp_path, path)
        temp_path = None
    finally:
        if temp_path is not None:
            try:
                temp_path.unlink(missing_ok=True)
            except OSError:
                # A non-None temp path means the write/replace path is already failing.
                # Preserve that authoritative error when best-effort cleanup also fails.
                pass

def _digest(value) -> str:
    return hashlib.sha256(str(value).encode("utf-8", errors="replace")).hexdigest()

def _safe_source_reference(value) -> str:
    text = str(value)
    if SAFE_SOURCE_REFERENCE.fullmatch(text):
        return text
    return f"source_sha256:{_digest(text)}"

def _safe_example_reference(value) -> str:
    text = str(value)
    if SAFE_EXAMPLE_REFERENCE.fullmatch(text):
        return text
    return f"legacy_example_sha256:{_digest(text)}"

def _source_reference(_root: Path, path: Path) -> str:
    return _safe_source_reference(path.resolve())

def _nonnegative_int_count(value) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return 0
    return value

def scan_logs(root: Path):
    candidates = []
    logs_dir = root / "build-logs"
    if logs_dir.exists():
        candidates.extend(list(logs_dir.glob("**/*.log")))
        candidates.extend(list(logs_dir.glob("**/*.txt")))
    latest = root / "BUILD_ERROR__latest.txt"
    if latest.exists():
        candidates.append(latest)
    analysis_json = root / "analysis" / "build_error_report.json"
    if analysis_json.exists():
        candidates.append(analysis_json)

    agg = {k: 0 for k in LOG_PATTERNS.keys()}
    examples = {k: [] for k in LOG_PATTERNS.keys()}
    for f in candidates:
        text = read_text(f)
        source_reference = _source_reference(root, f)
        structured_counts = None
        if f.suffix.lower() == ".json":
            try:
                data = json.loads(text)
                pats = data.get("patterns") if isinstance(data, dict) else None
                if isinstance(pats, dict):
                    parsed_counts = {}
                    recognized_pattern = False
                    for k in agg.keys():
                        alternate_key = k.replace(".", "_")
                        if k in pats:
                            v = pats[k]
                        elif alternate_key in pats:
                            v = pats[alternate_key]
                        else:
                            continue
                        recognized_pattern = True
                        if not isinstance(v, dict) or "count" not in v:
                            raise ValueError("invalid structured pattern record")
                        count = v["count"]
                        if isinstance(count, bool) or not isinstance(count, int) or count < 0:
                            raise ValueError("invalid structured pattern count")
                        parsed_counts[k] = count
                    if recognized_pattern:
                        structured_counts = parsed_counts
            except (json.JSONDecodeError, TypeError, ValueError):
                pass
        if structured_counts is not None:
            for k, count in structured_counts.items():
                agg[k] += count
            continue
        for k, rx in LOG_PATTERNS.items():
            matches = list(rx.finditer(text))
            agg[k] += len(matches)
            if matches and len(examples[k]) < 5:
                for _ in matches[:5-len(examples[k])]:
                    examples[k].append(f"{source_reference}:matched")
    return agg, examples, [_source_reference(root, p) for p in candidates]

def merge_db(existing: dict, delta_counts: dict, examples: dict, sources: list):
    existing = existing if isinstance(existing, dict) else {}
    out = {"generated_at": datetime.datetime.utcnow().isoformat()+"Z"}
    existing_sources = existing.get("sources_scanned", [])
    if not isinstance(existing_sources, list):
        existing_sources = []
    src = {_safe_source_reference(source) for source in existing_sources}
    src.update(_safe_source_reference(source) for source in sources)
    out["sources_scanned"] = sorted(src)
    existing_counts = existing.get("aggregated_counts") or {}
    if not isinstance(existing_counts, dict):
        existing_counts = {}
    agg = {}
    for k in LOG_PATTERNS:
        agg[k] = _nonnegative_int_count(existing_counts.get(k, 0))
    if not isinstance(delta_counts, dict):
        delta_counts = {}
    for k, v in delta_counts.items():
        if k not in LOG_PATTERNS:
            continue
        agg[k] = agg.get(k, 0) + _nonnegative_int_count(v)
    out["aggregated_counts"] = agg
    existing_examples = existing.get("examples") or {}
    if not isinstance(existing_examples, dict):
        existing_examples = {}
    ex = {k: [] for k in LOG_PATTERNS}
    for k in LOG_PATTERNS:
        arr = existing_examples.get(k, [])
        if isinstance(arr, list):
            for value in arr[:10]:
                safe_value = _safe_example_reference(value)
                if safe_value not in ex[k]:
                    ex[k].append(safe_value)
    for k, arr in examples.items():
        if k not in LOG_PATTERNS or not isinstance(arr, list):
            continue
        existing_set = set(ex[k])
        for e in arr:
            if len(ex[k]) >= 10:
                break
            safe_value = _safe_example_reference(e)
            if safe_value not in existing_set:
                ex[k].append(safe_value)
                existing_set.add(safe_value)
    out["examples"] = ex
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--ingest",
        action="append",
        default=[],
        help="Extra project roots or build-logs directories to scan",
    )
    args = ap.parse_args()

    ingest_paths = []
    for extra in args.ingest:
        try:
            ingest_path = Path(extra).resolve()
            ingest_path_exists = ingest_path.exists()
            ingest_path_is_dir = ingest_path.is_dir()
            ingest_path_is_file = ingest_path.is_file()
            ingest_stat = ingest_path.stat() if ingest_path_exists else None
        except (OSError, RuntimeError):
            ap.error("--ingest path could not be resolved")
        if not ingest_path_exists:
            ap.error("--ingest path does not exist")
        if not ingest_path_is_dir and not ingest_path_is_file:
            ap.error("--ingest path type is unsupported")
        ingest_paths.append((
            ingest_path,
            "directory" if ingest_path_is_dir else "file",
            (ingest_stat.st_dev, ingest_stat.st_ino),
        ))

    def require_unchanged_ingest_path(ingest_record):
        ingest_path, ingest_kind, ingest_identity = ingest_record
        try:
            if not ingest_path.exists():
                ap.error("--ingest path changed during validation")
            kind_matches = (
                ingest_path.is_dir()
                if ingest_kind == "directory"
                else ingest_path.is_file()
            )
            current_stat = ingest_path.stat()
        except (OSError, RuntimeError):
            ap.error("--ingest path could not be revalidated")
        if not kind_matches or (current_stat.st_dev, current_stat.st_ino) != ingest_identity:
            ap.error("--ingest path changed during validation")
        return ingest_path

    root = Path(".").resolve()
    db_path = root / ".build" / "error_patterns_db.json"
    existing = {}
    if db_path.exists():
        try:
            existing = json.loads(read_text(db_path))
        except Exception:
            existing = {}

    counts, examples, sources = scan_logs(root)
    seen_scan_roots = {root}
    for ingest_record in ingest_paths:
        ingest_path = require_unchanged_ingest_path(ingest_record)
        scan_root = ingest_path if ingest_record[1] == "directory" else ingest_path.parent
        if scan_root.name.casefold() == "build-logs" and not (scan_root / "build-logs").is_dir():
            scan_root = scan_root.parent
        if scan_root in seen_scan_roots:
            continue
        seen_scan_roots.add(scan_root)
        c, ex, srcs = scan_logs(scan_root)
        for k, v in c.items():
            counts[k] = counts.get(k, 0) + v
        for k, arr in ex.items():
            examples.setdefault(k, [])
            for e in arr:
                if e not in examples[k] and len(examples[k]) < 10:
                    examples[k].append(e)
        sources.extend(srcs)

    for ingest_record in ingest_paths:
        require_unchanged_ingest_path(ingest_record)

    merged = merge_db(existing, counts, examples, sources)
    root.joinpath(".build").mkdir(parents=True, exist_ok=True)
    _atomic_write_text(db_path, json.dumps(merged, ensure_ascii=False, indent=2))

    # Also expose top-level artifacts
    _atomic_write_text(root / "BUILD_ERROR_PATTERNS.json", json.dumps({
        "updated_at": datetime.datetime.utcnow().isoformat()+"Z",
        "aggregated_counts": merged.get("aggregated_counts", {}),
        "examples": merged.get("examples", {}),
        "sources": merged.get("sources_scanned", []),
    }, ensure_ascii=False, indent=2))

    top = sorted(merged.get("aggregated_counts", {}).items(), key=lambda kv: kv[1], reverse=True)[:12]
    summary = ["# BUILD Error Pattern Summary (persisted)","","- Top patterns:"]
    for k, v in top:
        summary.append(f"  - {k}: {v}")
    _atomic_write_text(root / "BUILD_ERROR_PATTERN_SUMMARY.md", "\n".join(summary)+"\n")

    print(json.dumps({"db": db_path.relative_to(root).as_posix(), "top": top}, ensure_ascii=False))

if __name__ == "__main__":
    main()
