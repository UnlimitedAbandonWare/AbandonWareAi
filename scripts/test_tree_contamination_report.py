#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from typing import Iterable


ACTIVE_MAIN_ROOTS = (
    Path("main/java"),
    Path("app/src/main/java_clean"),
)
TEST_ROOT_CANDIDATES = (
    Path("src/test/java"),
    Path("app/src/test/java"),
)
REPO_IMPORT_PREFIXES = (
    "ai.abandonware.",
    "com.abandonware.",
    "com.acme.",
    "com.example.",
    "com.nova.",
)
NAMESPACE_FAMILIES = (
    "ai.abandonware.nova",
    "ai.abandonware",
    "com.abandonware",
    "com.example",
    "com.acme",
    "com.nova",
)

PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*;", re.M)
TYPE_RE = re.compile(
    r"^\s*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\s+)*"
    r"(?:class|interface|enum|record|@interface)\s+([A-Za-z0-9_]+)",
    re.M,
)
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([A-Za-z0-9_.*]+)\s*;", re.M)


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="ignore")


def rel(root: Path, path: Path) -> str:
    return path.relative_to(root).as_posix()


def iter_java_files(root: Path, roots: Iterable[Path]) -> Iterable[Path]:
    for sub in roots:
        base = root / sub
        if not base.exists():
            continue
        yield from sorted(base.rglob("*.java"))


def java_fqcns(path: Path) -> list[str]:
    source = read_text(path)
    pkg = PACKAGE_RE.search(source)
    types = [match.group(1) for match in TYPE_RE.finditer(source)]
    if not types:
        return []
    package_prefix = (pkg.group(1) + ".") if pkg else ""
    outer = types[0]
    names = [package_prefix + outer]
    names.extend(package_prefix + outer + "." + nested for nested in types[1:])
    return names


def active_class_index(root: Path) -> set[str]:
    classes: set[str] = set()
    for path in iter_java_files(root, ACTIVE_MAIN_ROOTS):
        classes.update(java_fqcns(path))
    return classes


def app_legacy_tests_quarantined(root: Path) -> bool:
    app_build = root / "app" / "build.gradle.kts"
    if not app_build.is_file():
        return False
    text = read_text(app_build)
    return (
        "val test by getting" in text
        and "java.setSrcDirs(emptyList<String>())" in text
        and "resources.setSrcDirs(emptyList<String>())" in text
    )


def effective_test_roots(root: Path) -> tuple[list[Path], list[Path]]:
    active: list[Path] = []
    quarantined: list[Path] = []
    app_tests_quarantined = app_legacy_tests_quarantined(root)
    for test_root in TEST_ROOT_CANDIDATES:
        if test_root == Path("app/src/test/java") and app_tests_quarantined:
            quarantined.append(test_root)
        else:
            active.append(test_root)
    return active, quarantined


def test_class_index(root: Path, test_roots: Iterable[Path]) -> set[str]:
    classes: set[str] = set()
    for path in iter_java_files(root, test_roots):
        classes.update(java_fqcns(path))
    return classes


def imported_type(import_name: str) -> str | None:
    if import_name.endswith(".*"):
        return None
    parts = import_name.split(".")
    if len(parts) < 2:
        return None
    # For static member imports, trim trailing member names until the last token
    # looks like a Java type. This keeps com.x.LegacyUtil.run -> com.x.LegacyUtil.
    while len(parts) > 1 and not parts[-1][:1].isupper():
        parts.pop()
    if len(parts) < 2:
        return None
    return ".".join(parts)


def is_repo_import(import_name: str) -> bool:
    return any(import_name.startswith(prefix) for prefix in REPO_IMPORT_PREFIXES)


def namespace(import_name: str) -> str:
    for family in NAMESPACE_FAMILIES:
        if import_name == family or import_name.startswith(family + "."):
            return family
    parts = import_name.split(".")
    if len(parts) >= 3:
        return ".".join(parts[:3])
    return import_name


def build_report(root: Path) -> dict:
    root = root.resolve()
    test_roots, quarantined_test_roots = effective_test_roots(root)
    active_classes = active_class_index(root)
    test_classes = test_class_index(root, test_roots)
    missing_by_file: dict[str, list[str]] = defaultdict(list)
    missing_by_namespace: Counter[str] = Counter()
    checked_import_count = 0
    test_support_import_count = 0

    test_files = list(iter_java_files(root, test_roots))
    for path in test_files:
        source = read_text(path)
        for match in IMPORT_RE.finditer(source):
            raw_import = match.group(1)
            if not is_repo_import(raw_import):
                continue
            imported = imported_type(raw_import)
            if not imported:
                continue
            checked_import_count += 1
            if imported in active_classes:
                continue
            if imported in test_classes:
                test_support_import_count += 1
                continue
            if imported not in active_classes:
                missing_by_file[rel(root, path)].append(imported)
                missing_by_namespace[namespace(imported)] += 1

    affected = [
        {
            "file": file,
            "missingImportCount": len(sorted(set(imports))),
            "missingImports": sorted(set(imports))[:25],
        }
        for file, imports in sorted(
            missing_by_file.items(),
            key=lambda item: (-len(set(item[1])), item[0]),
        )
    ]
    missing_count = sum(row["missingImportCount"] for row in affected)
    risk_score = round(min(1.0, missing_count / 100.0), 4)

    return {
        "generatedAt": datetime.now().replace(microsecond=0).isoformat(),
        "decision": "test_tree_contamination_report",
        "activeRoots": [p.as_posix() for p in ACTIVE_MAIN_ROOTS],
        "testRoots": [p.as_posix() for p in test_roots],
        "quarantinedTestRoots": [p.as_posix() for p in quarantined_test_roots],
        "activeClassCount": len(active_classes),
        "testSupportClassCount": len(test_classes),
        "testJavaFileCount": len(test_files),
        "checkedRepoImportCount": checked_import_count,
        "testSupportImportCount": test_support_import_count,
        "missingImportCount": missing_count,
        "affectedTestFileCount": len(affected),
        "riskScore": risk_score,
        "missingByNamespace": dict(sorted(missing_by_namespace.items())),
        "topAffectedTestFiles": affected[:50],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--output", default="verification/test-tree-contamination-metrics.json")
    args = parser.parse_args()

    root = Path(args.root)
    output = root / args.output
    data = build_report(root)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(
        "[AWX][test-tree] "
        f"report={output} missingImportCount={data['missingImportCount']} "
        f"affectedTestFileCount={data['affectedTestFileCount']} riskScore={data['riskScore']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
