#!/usr/bin/env python3
"""Read-only zombie-code candidate audit for demo-1 Desktop safe patches."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


DEFAULT_ACTIVE_ROOTS = ("main/java", "app/src/main/java_clean")
DEFAULT_RESOURCE_ROOTS = ("main/resources", "app/src/main/resources")
RESOURCE_TEXT_SUFFIXES = (
    ".factories",
    ".imports",
    ".properties",
    ".yml",
    ".yaml",
    ".xml",
    ".json",
    ".conf",
    ".txt",
)
SPRING_MARKERS = (
    "@Component",
    "@Service",
    "@Repository",
    "@Controller",
    "@RestController",
    "@Configuration",
    "@AutoConfiguration",
    "@Aspect",
    "@Bean",
    "@SpringBootApplication",
)
RUNTIME_MARKERS = (
    "PromptBuilder",
    "TraceStore",
    "DebugEventStore",
    "NaverSearchService",
    "BraveSearchService",
    "SerpApiProvider",
    "HybridWebSearchProvider",
    "NightmareBreaker",
    "QueryTransformer",
)
TEXT_CACHE: dict[Path, str] = {}
PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*;", re.MULTILINE)
TYPE_RE = re.compile(
    r"^\s*(?:public\s+)?(?:final\s+|abstract\s+|sealed\s+|non-sealed\s+)?"
    r"(?:class|interface|enum|record|@interface)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)
JAVA_PATH_RE = re.compile(r"(?P<path>(?:[A-Za-z0-9_.-]+[\\/])+[A-Za-z0-9_$.-]+\.java)")


@dataclass
class SourceInfo:
    path: str
    active_root: str
    package: str | None
    type_name: str | None
    fqcn: str | None
    package_path_matches: bool | None
    deprecated_or_alias_signal: bool
    spring_marker_count: int
    runtime_marker_count: int


@dataclass
class CandidateResult:
    path: str
    exists: bool
    within_root: bool
    active_root: str | None
    fqcn: str | None
    package_path_matches: bool | None
    simple_name_duplicate_count: int
    fqcn_reference_count: int
    simple_reference_count: int
    external_reference_samples: list[str]
    resource_reference_count: int
    resource_reference_samples: list[str]
    deprecated_or_alias_signal: bool
    spring_marker_count: int
    runtime_marker_count: int
    classification: str
    reasons: list[str]


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Audit Java zombie-code deletion candidates without modifying files."
    )
    parser.add_argument("--root", default=".", help="Repository root to audit.")
    parser.add_argument(
        "--candidates",
        help="Markdown/text/JSON file listing candidate Java paths.",
    )
    parser.add_argument(
        "--active-root",
        action="append",
        dest="active_roots",
        help="Active Java source root, repeatable. Defaults to demo-1 roots.",
    )
    parser.add_argument(
        "--format",
        choices=("markdown", "json"),
        default="markdown",
        help="Output format.",
    )
    parser.add_argument(
        "--max-usages",
        type=int,
        default=20,
        help="Maximum external reference samples per candidate.",
    )
    parser.add_argument(
        "--discover",
        action="store_true",
        help="If no candidates are supplied, discover deprecated/alias-signaled Java files.",
    )
    parser.add_argument(
        "--discover-limit",
        type=int,
        default=50,
        help="Maximum discovered candidates to audit. Use explicit --candidates for deletion proof.",
    )
    return parser.parse_args(argv)


def normalize_rel(path_text: str) -> Path:
    text = path_text.strip().strip("`'\"")
    text = re.sub(r"^[*-]\s+", "", text).strip()
    return Path(text.replace("\\", "/"))


def read_candidate_paths(candidate_file: Path) -> list[Path]:
    raw = candidate_file.read_text(encoding="utf-8", errors="ignore")
    paths: list[Path] = []
    if candidate_file.suffix.lower() == ".json":
        data = json.loads(raw)
        if not isinstance(data, list):
            raise ValueError("JSON candidate file must contain a list.")
        for item in data:
            if isinstance(item, str):
                paths.append(normalize_rel(item))
            elif isinstance(item, dict) and isinstance(item.get("path"), str):
                paths.append(normalize_rel(item["path"]))
            else:
                raise ValueError("JSON candidates must be strings or objects with a path field.")
    else:
        for match in JAVA_PATH_RE.finditer(raw):
            paths.append(normalize_rel(match.group("path")))
    seen = set()
    unique: list[Path] = []
    for path in paths:
        key = path.as_posix()
        if key not in seen:
            unique.append(path)
            seen.add(key)
    return unique


def safe_relative(path: Path, root: Path) -> str | None:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return None


def read_text(path: Path) -> str:
    key = path.resolve()
    if key not in TEXT_CACHE:
        TEXT_CACHE[key] = path.read_text(encoding="utf-8", errors="ignore")
    return TEXT_CACHE[key]


def parse_source(path: Path, root: Path, active_roots: list[Path]) -> SourceInfo:
    rel = safe_relative(path, root) or path.as_posix()
    text = read_text(path)
    package_match = PACKAGE_RE.search(text)
    type_match = TYPE_RE.search(text)
    package = package_match.group(1) if package_match else None
    type_name = type_match.group(1) if type_match else None
    fqcn = f"{package}.{type_name}" if package and type_name else type_name
    active_root = ""
    package_path_matches: bool | None = None
    for active in active_roots:
        try:
            suffix = path.resolve().relative_to(active.resolve())
        except ValueError:
            continue
        active_root = safe_relative(active, root) or active.as_posix()
        if package:
            expected_parent = Path(*package.split("."))
            package_path_matches = suffix.parent.as_posix().endswith(expected_parent.as_posix())
        break
    lower = text.lower()
    deprecated_or_alias_signal = any(
        signal in lower
        for signal in ("@deprecated", "deprecated", "alias", "compatibility", "bridge", "legacy")
    )
    spring_marker_count = sum(text.count(marker) for marker in SPRING_MARKERS)
    runtime_marker_count = sum(text.count(marker) for marker in RUNTIME_MARKERS)
    return SourceInfo(
        path=rel,
        active_root=active_root,
        package=package,
        type_name=type_name,
        fqcn=fqcn,
        package_path_matches=package_path_matches,
        deprecated_or_alias_signal=deprecated_or_alias_signal,
        spring_marker_count=spring_marker_count,
        runtime_marker_count=runtime_marker_count,
    )


def iter_java_files(active_roots: Iterable[Path]) -> Iterable[Path]:
    for active in active_roots:
        if active.exists():
            yield from active.rglob("*.java")


def infer_resource_roots(root: Path, active_roots: Iterable[Path]) -> list[Path]:
    roots: list[Path] = []
    seen: set[Path] = set()

    def add(path: Path) -> None:
        resolved = path.resolve()
        if resolved not in seen:
            roots.append(path)
            seen.add(resolved)

    for rel in DEFAULT_RESOURCE_ROOTS:
        add(root / rel)

    for active in active_roots:
        name = active.name
        if name in {"java", "java_clean"}:
            add(active.parent / "resources")
    return roots


def iter_resource_files(resource_roots: Iterable[Path]) -> Iterable[Path]:
    for resource_root in resource_roots:
        if not resource_root.exists():
            continue
        for path in resource_root.rglob("*"):
            if path.is_file() and path.suffix.lower() in RESOURCE_TEXT_SUFFIXES:
                yield path


def build_index(root: Path, active_roots: list[Path]) -> tuple[list[SourceInfo], dict[str, int]]:
    sources: list[SourceInfo] = []
    simple_counts: dict[str, int] = {}
    for path in iter_java_files(active_roots):
        info = parse_source(path, root, active_roots)
        sources.append(info)
        if info.type_name:
            simple_counts[info.type_name] = simple_counts.get(info.type_name, 0) + 1
    return sources, simple_counts


def count_references(
    root: Path,
    active_roots: list[Path],
    candidate_path: Path,
    fqcn: str | None,
    type_name: str | None,
    max_samples: int,
) -> tuple[int, int, list[str]]:
    fqcn_count = 0
    simple_count = 0
    samples: list[str] = []
    simple_re = re.compile(rf"\b{re.escape(type_name)}\b") if type_name else None
    for path in iter_java_files(active_roots):
        if path.resolve() == candidate_path.resolve():
            continue
        text = read_text(path)
        path_fqcn_count = text.count(fqcn) if fqcn else 0
        path_simple_count = len(simple_re.findall(text)) if simple_re else 0
        fqcn_count += path_fqcn_count
        simple_count += path_simple_count
        if (path_fqcn_count or path_simple_count) and len(samples) < max_samples:
            rel = safe_relative(path, root) or path.as_posix()
            samples.append(f"{rel}: fqcn={path_fqcn_count} simple={path_simple_count}")
    return fqcn_count, simple_count, samples


def count_resource_references(
    root: Path,
    resource_roots: list[Path],
    fqcn: str | None,
    type_name: str | None,
    max_samples: int,
) -> tuple[int, list[str]]:
    if not fqcn and not type_name:
        return 0, []
    simple_re = re.compile(rf"\b{re.escape(type_name)}\b") if type_name else None
    count = 0
    samples: list[str] = []
    for path in iter_resource_files(resource_roots):
        text = read_text(path)
        fqcn_count = text.count(fqcn) if fqcn else 0
        simple_count = len(simple_re.findall(text)) if simple_re else 0
        path_count = fqcn_count + simple_count
        count += path_count
        if path_count and len(samples) < max_samples:
            rel = safe_relative(path, root) or path.as_posix()
            samples.append(f"{rel}: fqcn={fqcn_count} simple={simple_count}")
    return count, samples


def classify(info: SourceInfo | None, result: CandidateResult) -> None:
    reasons = result.reasons
    if not result.within_root:
        result.classification = "BLOCK_OUTSIDE_ROOT"
        reasons.append("candidate path is outside the audited root")
        return
    if not result.exists:
        result.classification = "BLOCK_MISSING"
        reasons.append("candidate file does not exist")
        return
    if not result.active_root:
        result.classification = "BLOCK_WRONG_SOURCESET"
        reasons.append("candidate is not under a configured active Java root")
        return
    if result.package_path_matches is False:
        result.classification = "BLOCK_PACKAGE_PATH_MISMATCH"
        reasons.append("package does not match source path")
        return
    if result.spring_marker_count:
        result.classification = "HOLD_SPRING_RUNTIME"
        reasons.append("Spring/runtime marker present; requires manual bean/config proof")
    if result.runtime_marker_count:
        result.classification = "HOLD_CANONICAL_RUNTIME"
        reasons.append("canonical runtime seam marker present")
    if result.fqcn_reference_count or result.simple_reference_count:
        result.classification = "HOLD_REFERENCED"
        reasons.append("external references found in active roots")
    if result.resource_reference_count:
        if not result.classification:
            result.classification = "HOLD_RESOURCE_METADATA"
        reasons.append("resource metadata references candidate")
    if result.simple_name_duplicate_count > 1:
        reasons.append("simple class name is duplicated; use FQCN-only reasoning")
    if result.classification:
        return
    if result.deprecated_or_alias_signal:
        result.classification = "REVIEW_DELETE_CANDIDATE"
        reasons.append("deprecated/alias signal found and no active references detected")
    else:
        result.classification = "REVIEW_NO_ALIAS_SIGNAL"
        reasons.append("no references detected, but no deprecated/alias signal was found")


def audit_candidate(
    root: Path,
    rel_candidate: Path,
    active_roots: list[Path],
    resource_roots: list[Path],
    simple_counts: dict[str, int],
    max_samples: int,
) -> CandidateResult:
    candidate = (root / rel_candidate).resolve()
    rel = safe_relative(candidate, root)
    within_root = rel is not None
    exists = candidate.exists()
    info: SourceInfo | None = None
    if within_root and exists:
        info = parse_source(candidate, root, active_roots)
    type_name = info.type_name if info else None
    fqcn = info.fqcn if info else None
    fqcn_refs = 0
    simple_refs = 0
    samples: list[str] = []
    resource_refs = 0
    resource_samples: list[str] = []
    if within_root and exists and info:
        fqcn_refs, simple_refs, samples = count_references(
            root, active_roots, candidate, fqcn, type_name, max_samples
        )
        resource_refs, resource_samples = count_resource_references(
            root, resource_roots, fqcn, type_name, max_samples
        )
    result = CandidateResult(
        path=rel_candidate.as_posix(),
        exists=exists,
        within_root=within_root,
        active_root=info.active_root if info else None,
        fqcn=fqcn,
        package_path_matches=info.package_path_matches if info else None,
        simple_name_duplicate_count=simple_counts.get(type_name or "", 0),
        fqcn_reference_count=fqcn_refs,
        simple_reference_count=simple_refs,
        external_reference_samples=samples,
        resource_reference_count=resource_refs,
        resource_reference_samples=resource_samples,
        deprecated_or_alias_signal=info.deprecated_or_alias_signal if info else False,
        spring_marker_count=info.spring_marker_count if info else 0,
        runtime_marker_count=info.runtime_marker_count if info else 0,
        classification="",
        reasons=[],
    )
    classify(info, result)
    return result


def discover_candidates(sources: Iterable[SourceInfo], limit: int) -> list[Path]:
    paths = []
    for info in sources:
        if info.deprecated_or_alias_signal:
            paths.append(Path(info.path))
        if len(paths) >= limit:
            break
    return paths


def render_markdown(results: list[CandidateResult]) -> str:
    counts: dict[str, int] = {}
    for item in results:
        counts[item.classification] = counts.get(item.classification, 0) + 1
    lines = ["# Zombie Candidate Audit", "", "## Summary", ""]
    for key in sorted(counts):
        lines.append(f"- {key}: {counts[key]}")
    if not counts:
        lines.append("- no candidates")
    lines.extend(["", "## Candidates", ""])
    for item in results:
        lines.append(f"### {item.path}")
        lines.append("")
        lines.append(f"- classification: `{item.classification}`")
        lines.append(f"- exists: `{str(item.exists).lower()}`")
        lines.append(f"- active_root: `{item.active_root or ''}`")
        lines.append(f"- fqcn: `{item.fqcn or ''}`")
        lines.append(f"- package_path_matches: `{item.package_path_matches}`")
        lines.append(f"- simple_name_duplicate_count: `{item.simple_name_duplicate_count}`")
        lines.append(f"- fqcn_reference_count: `{item.fqcn_reference_count}`")
        lines.append(f"- simple_reference_count: `{item.simple_reference_count}`")
        lines.append(f"- resource_reference_count: `{item.resource_reference_count}`")
        lines.append(f"- spring_marker_count: `{item.spring_marker_count}`")
        lines.append(f"- runtime_marker_count: `{item.runtime_marker_count}`")
        if item.reasons:
            lines.append("- reasons:")
            for reason in item.reasons:
                lines.append(f"  - {reason}")
        if item.external_reference_samples:
            lines.append("- reference_samples:")
            for sample in item.external_reference_samples:
                lines.append(f"  - {sample}")
        if item.resource_reference_samples:
            lines.append("- resource_reference_samples:")
            for sample in item.resource_reference_samples:
                lines.append(f"  - {sample}")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    root = Path(args.root).resolve()
    if not root.exists():
        print(f"error: root does not exist: {root}", file=sys.stderr)
        return 2
    active_roots = [root / Path(p) for p in (args.active_roots or DEFAULT_ACTIVE_ROOTS)]
    resource_roots = infer_resource_roots(root, active_roots)
    sources, simple_counts = build_index(root, active_roots)
    if args.candidates:
        candidate_paths = read_candidate_paths(Path(args.candidates))
    elif args.discover:
        candidate_paths = discover_candidates(sources, max(0, args.discover_limit))
    else:
        print("error: pass --candidates or --discover", file=sys.stderr)
        return 2
    results = [
        audit_candidate(root, candidate, active_roots, resource_roots, simple_counts, args.max_usages)
        for candidate in candidate_paths
    ]
    if args.format == "json":
        print(json.dumps([asdict(item) for item in results], indent=2, sort_keys=True))
    else:
        print(render_markdown(results), end="")
    blocking = {
        "BLOCK_OUTSIDE_ROOT",
        "BLOCK_MISSING",
        "BLOCK_WRONG_SOURCESET",
        "BLOCK_PACKAGE_PATH_MISMATCH",
    }
    return 1 if any(item.classification in blocking for item in results) else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
