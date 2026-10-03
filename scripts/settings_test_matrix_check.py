#!/usr/bin/env python3
"""settings_test_matrix_check — track Codex test coverage for settings-routing v3.

Read-only. Scans src/test/java + src/test/js for the expected test classes,
counts @Test/test( occurrences, maps spec IDs (V3-xx, Txx) and test_name
strings mentioned in test bodies back to the v3 (32) / v2 (64) matrices, and
reads build/test-results/test/TEST-*.xml for last-run counts when present.
Never runs Gradle.

  python -B scripts/settings_test_matrix_check.py [--root .] [--json]

Output columns per class: file found, @Test count, spec IDs referenced,
last XML run tests/failures/skipped. Spec rows are reported as
covered / excluded / not-covered — coverage is a map, not a demand that all
96 specs have tests.
"""
from __future__ import annotations

import argparse
import glob
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

EXPECTED_JAVA_CLASSES = [
    "SettingsCapabilityProjectionTest",
    "SettingsPlanProjectionTest",
    "SettingsPageControllerTest",
    "RoutingSettingsControllerTest",
    "RoutingSettingsPersistenceTest",
    "RoutingRunSnapshotTest",
    "SettingsSnapshotCompatibilityTest",
    "RoleRoutingConsumptionTest",
    "RoutingFallbackBoundaryTest",
    "RoutingFactoryArgumentsTest",
    "SettingsRoutingIntegrationTest",
    "RoutingOutcomeProjectionTest",
    "RoutingOutcomeAccessTest",
    "RoutingRedactionTest",
    "SettingsOutcomeProjectionTest",
    "SettingsRoutingPageTest",
]
EXPECTED_NODE_TESTS = [
    "settings-core.test.cjs",
    "settings-routing.test.cjs",
    "settings-harmony.test.cjs",
]

SPEC_ID_RE = re.compile(r"\b(?:V3-\d{2}|V2-\d{2}|T\d{2})\b")
EXCLUDE_HINT_RE = re.compile(
    r"(?i)(excluded|out.of.scope|제외|제거|skip|disabled|not.?covered)")
TEST_ANN_RE = re.compile(r"@Test\b")
NODE_TEST_RE = re.compile(r"\b(?:test|it)\s*\(")

DEFAULT_V3 = ("agent-prompts/codex-settings-page-routing-v3-20261002/"
              "ref/contracts/v3-test-matrix.json")
DEFAULT_V2 = ("agent-prompts/codex-settings-page-routing-v3-20261002/"
              "ref/reference/v2-test-matrix.json")


def load_specs(path: Path, id_prefix: str):
    data = json.loads(path.read_text(encoding="utf-8"))
    out = []
    for row in data:
        sid = row.get("id", "")
        out.append({
            "id": sid,
            "test_name": row.get("test_name", ""),
            "test_class": row.get("test_class", ""),
            "work_package": row.get("work_package"),
            "matrix": path.name,
        })
    return out


def scan_test_files(root: Path):
    """Return {path: text} for every file under src/test (java + js)."""
    files = {}
    test_root = root / "src" / "test"
    if not test_root.is_dir():
        return files
    for p in sorted(test_root.rglob("*")):
        if p.is_file() and p.suffix in {".java", ".cjs", ".js", ".mjs", ".ts"}:
            try:
                files[str(p.relative_to(root)).replace("\\", "/")] = \
                    p.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
    return files


def ids_in_text(text: str):
    return set(SPEC_ID_RE.findall(text))


def excluded_ids_in_text(text: str):
    """IDs mentioned only on lines carrying an exclusion hint."""
    normal, excl = set(), set()
    for line in text.splitlines():
        ids = SPEC_ID_RE.findall(line)
        if not ids:
            continue
        (excl if EXCLUDE_HINT_RE.search(line) else normal).update(ids)
    return excl - normal


def class_row(class_name: str, files: dict):
    """Locate the class by filename stem, then confirm declaration in body."""
    found_path = None
    for rel, text in files.items():
        stem = Path(rel).stem
        if stem == class_name:
            found_path = rel
            break
    if found_path is None:
        for rel, text in files.items():
            if re.search(r"\bclass\s+" + re.escape(class_name) + r"\b", text):
                found_path = rel
                break
    if found_path is None:
        return {"exists": False}
    text = files[found_path]
    return {
        "exists": True,
        "file": found_path,
        "testCount": len(TEST_ANN_RE.findall(text)),
        "specIds": sorted(ids_in_text(text)),
        "excludedIds": sorted(excluded_ids_in_text(text)),
    }


def node_row(name: str, root: Path):
    rel = f"src/test/js/{name}"
    p = root / rel
    if not p.is_file():
        return {"exists": False}
    text = p.read_text(encoding="utf-8", errors="replace")
    return {
        "exists": True,
        "file": rel,
        "testCount": len(NODE_TEST_RE.findall(text)),
        "specIds": sorted(ids_in_text(text)),
        "excludedIds": sorted(excluded_ids_in_text(text)),
    }


def xml_results(root: Path):
    """build/test-results/test/TEST-*.xml -> {simpleClassName: counts}."""
    out = {}
    for xml_path in sorted(glob.glob(
            str(root / "build" / "test-results" / "test" / "TEST-*.xml"))):
        try:
            tree = ET.parse(xml_path)
            suite = tree.getroot()
            name = suite.get("name", "")
            simple = name.rsplit(".", 1)[-1]
            out[simple] = {
                "xml": str(Path(xml_path).name),
                "tests": int(suite.get("tests", 0)),
                "failures": int(suite.get("failures", 0)),
                "errors": int(suite.get("errors", 0)),
                "skipped": int(suite.get("skipped", 0)),
            }
        except (ET.ParseError, OSError, ValueError):
            continue
    return out


def build_report(root: Path, v3_path: Path | None, v2_path: Path | None):
    files = scan_test_files(root)
    # Precompute per-file id/exclusion sets once (1590-file tree x 96 specs).
    file_ids = {rel: ids_in_text(text) for rel, text in files.items()}
    file_excl = {rel: excluded_ids_in_text(text)
                 for rel, text in files.items()}
    xml = xml_results(root)

    classes = {}
    for name in EXPECTED_JAVA_CLASSES:
        row = class_row(name, files)
        if row["exists"] and name in xml:
            row["lastRun"] = xml[name]
        classes[name] = row
    nodes = {}
    for name in EXPECTED_NODE_TESTS:
        row = node_row(name, root)
        if row["exists"]:
            # merge with the java scan for id references too
            pass
        nodes[name] = row

    spec_rows = []
    specs = []
    if v3_path and v3_path.is_file():
        specs += load_specs(v3_path, "V3")
    if v2_path and v2_path.is_file():
        specs += load_specs(v2_path, "V2")
    for spec in specs:
        sid, tname = spec["id"], spec["test_name"]
        id_hits = {rel for rel in files if sid in file_ids[rel]}
        excl_hits = {rel for rel in files if sid in file_excl[rel]}
        name_hits = {rel for rel, text in files.items()
                     if tname and tname in text and re.search(
                         r"\b" + re.escape(tname) + r"\b", text)}
        hit_files = sorted((id_hits - excl_hits) | name_hits)
        excluded = sorted(excl_hits)
        if hit_files:
            state = "covered"
        elif excluded:
            state = "excluded"
        else:
            state = "not-covered"
        spec_rows.append({**spec, "state": state, "files": hit_files,
                          "excludedIn": excluded})

    covered = [s["id"] for s in spec_rows if s["state"] == "covered"]
    excluded = [s["id"] for s in spec_rows if s["state"] == "excluded"]
    uncovered = [s["id"] for s in spec_rows if s["state"] == "not-covered"]
    return {
        "root": str(root),
        "matrices": [str(p) for p in (v3_path, v2_path) if p and p.is_file()],
        "classes": classes,
        "nodeTests": nodes,
        "specs": spec_rows,
        "summary": {
            "specTotal": len(spec_rows),
            "covered": len(covered),
            "excluded": len(excluded),
            "notCovered": len(uncovered),
            "classesFound": sum(1 for c in classes.values() if c["exists"]),
            "classesTotal": len(classes),
            "nodeFound": sum(1 for c in nodes.values() if c["exists"]),
            "nodeTotal": len(nodes),
        },
        "coveredIds": covered,
        "excludedIds": excluded,
        "notCoveredIds": uncovered,
    }


def print_report(rep: dict):
    s = rep["summary"]
    print(f"test-matrix check - classes {s['classesFound']}/{s['classesTotal']}, "
          f"node tests {s['nodeFound']}/{s['nodeTotal']}, "
          f"specs covered {s['covered']}/{s['specTotal']} "
          f"(excluded {s['excluded']}, not-covered {s['notCovered']})")
    print("\n== Java classes ==")
    for name, row in rep["classes"].items():
        if not row["exists"]:
            print(f"  {name:42} MISSING")
            continue
        run = row.get("lastRun")
        run_s = (f"xml:{run['tests']}t/{run['failures']}f/{run['skipped']}s"
                 if run else "xml:-")
        print(f"  {name:42} {row['testCount']:3} tests  "
              f"ids:{len(row['specIds']):2}  {run_s}  {row['file']}")
    print("\n== Node tests ==")
    for name, row in rep["nodeTests"].items():
        if not row["exists"]:
            print(f"  {name:32} MISSING")
        else:
            print(f"  {name:32} {row['testCount']:3} tests  "
                  f"ids:{len(row['specIds'])}")
    print("\n== Spec coverage ==")
    for row in rep["specs"]:
        mark = {"covered": "COVERED", "excluded": "EXCLUDED",
                "not-covered": "-"}.get(row["state"], "?")
        loc = row["files"][0] if row["files"] else ""
        print(f"  {row['id']:6} {mark:11} {row['test_name']:42} {loc}")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--v3", default=DEFAULT_V3)
    ap.add_argument("--v2", default=DEFAULT_V2)
    ap.add_argument("--json", action="store_true", dest="as_json")
    ap.add_argument("--out", help="also write the JSON report to this path")
    args = ap.parse_args(argv)
    root = Path(args.root).resolve()
    v3 = root / args.v3 if args.v3 else None
    v2 = root / args.v2 if args.v2 else None
    rep = build_report(root, v3, v2)
    if args.as_json:
        print(json.dumps(rep, ensure_ascii=False, indent=1))
    else:
        print_report(rep)
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(json.dumps(rep, ensure_ascii=False, indent=1),
                                  encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
