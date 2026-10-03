#!/usr/bin/env python3
"""Summarize named JUnit classes from existing XML.

Counts tests, failures, skipped, and failed method names for the named
classes only. Other classes in the same file are listed separately and are
never copied into the named class result. A missing class is MISSING, not a pass.
"""
from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def summarize(xml_dir: Path, classes: list[str],
              required_cases: list[str] | None = None) -> dict:
    wanted = list(dict.fromkeys(classes))
    required = list(dict.fromkeys(required_cases or []))
    found = {
        name: {"className": name, "status": "MISSING", "tests": 0,
               "failures": 0, "skipped": 0, "failedMethods": []}
        for name in wanted
    }
    # required case outcomes: FAILED > SKIPPED > PASSED dominates, MISSING default
    req_rank = {"PASSED": 0, "SKIPPED": 1, "FAILED": 2}
    req_seen: dict[str, str] = {}
    other = []
    files = sorted(xml_dir.glob("**/*.xml")) if xml_dir.is_dir() else []
    for path in files:
        try:
            root = ET.fromstring(path.read_bytes())
        except ET.ParseError:
            continue
        suites = [root] if local(root.tag) == "testsuite" else [
            node for node in root.iter() if local(node.tag) == "testsuite"]
        for suite in suites:
            cases = [node for node in list(suite) if local(node.tag) == "testcase"]
            for case in cases:
                if required:
                    cid = "{}#{}".format(case.attrib.get("classname") or "",
                                         case.attrib.get("name") or "")
                    if cid in required:
                        children = {local(c.tag) for c in list(case)}
                        st = ("FAILED" if children & {"failure", "error"}
                              else "SKIPPED" if "skipped" in children
                              else "PASSED")
                        prev = req_seen.get(cid)
                        if prev is None or req_rank[st] > req_rank[prev]:
                            req_seen[cid] = st
            by_class: dict[str, list] = {}
            for case in cases:
                by_class.setdefault(case.attrib.get("classname") or suite.attrib.get("name") or "", []).append(case)
            if not by_class and suite.attrib.get("name"):
                by_class[suite.attrib["name"]] = []
            for class_name, class_cases in by_class.items():
                failures = []
                skipped = 0
                for case in class_cases:
                    if any(local(child.tag) == "skipped" for child in list(case)):
                        skipped += 1
                    if any(local(child.tag) in ("failure", "error") for child in list(case)):
                        failures.append(case.attrib.get("name") or "")
                row = {
                    "className": class_name,
                    "tests": len(class_cases),
                    "failures": len(failures),
                    "skipped": skipped,
                    "failedMethods": failures,
                }
                if class_name in found:
                    current = found[class_name]
                    if current["status"] == "MISSING":
                        current.update(status="FOUND", tests=0, failures=0, skipped=0, failedMethods=[])
                    current["tests"] += row["tests"]
                    current["failures"] += row["failures"]
                    current["skipped"] += row["skipped"]
                    current["failedMethods"].extend(row["failedMethods"])
                else:
                    other.append(row)
    named = []
    for name in wanted:
        row = found[name]
        if row["status"] == "FOUND":
            row["status"] = "FAIL" if row["failures"] else "PASS"
        named.append(row)
    payload = {
        "schemaVersion": "awx.junit-owned-summary.v1",
        "xmlDir": str(xml_dir),
        "named": named,
        "otherClassCount": len(other),
        "note": "other classes are counted only as otherClassCount and do not make a named class PASS",
    }
    if required:
        payload["requiredCases"] = [
            {"id": cid, "status": req_seen.get(cid, "MISSING")}
            for cid in required]
    return payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Named-class JUnit XML summary. Does not run Gradle.")
    parser.add_argument("--xml-dir", required=True)
    parser.add_argument("--class", dest="classes", action="append", required=True)
    parser.add_argument("--require-case", dest="required", action="append",
                        default=[], metavar="FQCN#METHOD",
                        help="exact classname#method that must appear PASSED "
                             "(repeatable, additive; default off)")
    parser.add_argument("--out")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    bad = [c for c in args.required if "#" not in c or
           not c.split("#", 1)[0] or not c.split("#", 1)[1]]
    if bad:
        print("INPUT_ERROR bad --require-case (need FQCN#method): "
              + ",".join(bad), file=sys.stderr)
        return 2
    payload = summarize(Path(args.xml_dir), args.classes, args.required)
    text = json.dumps(payload, ensure_ascii=True, indent=2)
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(text + "\n", encoding="utf-8")
    if args.json or not args.out:
        print(text)
    missing = any(row["status"] == "MISSING" for row in payload["named"])
    failed = any(row["status"] == "FAIL" for row in payload["named"])
    for req in payload.get("requiredCases", []):
        if req["status"] == "FAILED":
            failed = True
        elif req["status"] in ("MISSING", "SKIPPED"):
            missing = True
    if failed:
        return 4
    if missing:
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
