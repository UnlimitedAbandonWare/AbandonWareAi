#!/usr/bin/env python3
"""JUnit XML + Gradle log evidence -> verdict classifier (WP3 engine).

This is a *tool* module despite the test_ prefix: it carries no TestCase
classes, so unittest discovery is a no-op on it. The ps1 runner calls this;
scripts/test_gradle_truth_gate.py is its unittest.

Verdicts:
  BASELINE_BLOCKED   config/compile/dependency failure -- not a valid RED
  RED                compile OK + test task failed (XML failures/errors, or
                     `:test FAILED` in log without XML evidence)
  GREEN              executed tests > 0, failures == 0, errors == 0, exit 0
  NO_TESTS           zero executed, NO-SOURCE, or all-skipped
  WRAPPER_EXIT_LIE   wrapper exit 0 while log shows failure markers
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

try:
    from scripts.analyze_build_output import ANSI_SGR_PATTERN, LOG_PATTERNS
except Exception:  # pragma: no cover - direct file exec fallback
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    from scripts.analyze_build_output import ANSI_SGR_PATTERN, LOG_PATTERNS

SCHEMA = "awx.gradle-truth.v1"

CONFIG_FAIL_PATTERNS = [
    re.compile(r"Could not compile build file", re.I),
    re.compile(r"Could not determine the dependencies", re.I),
    re.compile(r"Script compilation error", re.I),
    re.compile(r"Could not resolve all artifacts for configuration", re.I),
    re.compile(r"What went wrong:.*(?:plugin|repositories|settings)", re.I | re.S),
]
COMPILE_FAIL_PATTERNS = [
    re.compile(r"Execution failed for task ':?(?:[\w-]+:)?compile(?:Java|Kotlin|TestJava|TestKotlin)'", re.I),
    re.compile(r"Compilation failed", re.I),
    re.compile(r"\.java:\d+: error:"),
    re.compile(r"error: cannot find symbol"),
    re.compile(r"package [\w.]+ does not exist"),
]
TEST_TASK_FAIL_PATTERNS = [
    re.compile(r"Execution failed for task ':?(?:[\w-]+:)?test'"),
    re.compile(r"There were failing tests", re.I),
    re.compile(r">\s*Task :?[\w:-]*test.* FAILED"),
]
NO_SOURCE_PATTERN = re.compile(
    r">\s*Task :?[\w:-]*test.*\bNO-SOURCE\b", re.I)


def _hit(patterns, text: str) -> list[str]:
    return [p.pattern for p in patterns if p.search(text)]


def strip_ansi(text: str) -> str:
    return ANSI_SGR_PATTERN.sub("", text)


def summarize_xml(xml_dir: Path, since_epoch: float | None = None) -> dict:
    """Aggregate JUnit XML under xml_dir. `since_epoch` drops stale files from
    the *executed* evidence (reported as staleXmlCount)."""
    files = sorted(xml_dir.rglob("*.xml")) if xml_dir.is_dir() else []
    suites = []
    stale = 0
    for f in files:
        try:
            if since_epoch is not None and f.stat().st_mtime < since_epoch:
                stale += 1
                continue
            root = ET.parse(f).getroot()
            if root.tag != "testsuite":
                continue
            cases = list(root.iter("testcase"))
            skipped_cases = sum(1 for c in cases if c.find("skipped") is not None)
            jdk = None
            for prop in root.iter("property"):
                if prop.attrib.get("name") in ("java.version", "jdk"):
                    jdk = prop.attrib.get("value")
                    break
            suites.append({
                "file": str(f),
                "name": root.attrib.get("name", f.stem),
                "tests": int(root.attrib.get("tests", len(cases))),
                "failures": int(root.attrib.get("failures", 0)),
                "errors": int(root.attrib.get("errors", 0)),
                "skipped": int(root.attrib.get("skipped", skipped_cases)),
                "timeSec": float(root.attrib.get("time", 0.0)),
                "timestamp": root.attrib.get("timestamp", ""),
                "jdk": jdk,
            })
        except (ET.ParseError, OSError, ValueError):
            continue
    executed = sum(s["tests"] for s in suites)
    all_skipped = bool(suites) and all(s["skipped"] >= s["tests"] for s in suites)
    jdk = next((s["jdk"] for s in suites if s.get("jdk")), None)
    return {
        "files": len(files),
        "staleXmlCount": stale,
        "suites": [s["name"] for s in suites],
        "suiteDetail": suites,
        "tests": sum(s["tests"] for s in suites),
        "executed": executed,
        "failures": sum(s["failures"] for s in suites),
        "errors": sum(s["errors"] for s in suites),
        "skipped": sum(s["skipped"] for s in suites),
        "allSkipped": all_skipped,
        "totalTimeSec": round(sum(s["timeSec"] for s in suites), 3),
        "jdk": jdk,
    }


def classify_verdict(log_text: str, xml: dict, gradle_exit: int | None) -> dict:
    text = strip_ansi(log_text)
    failure_markers = _hit([LOG_PATTERNS["gradle_build_failed"]], text)
    config_hits = _hit(CONFIG_FAIL_PATTERNS, text)
    compile_hits = _hit(COMPILE_FAIL_PATTERNS, text)
    test_task_hits = _hit(TEST_TASK_FAIL_PATTERNS, text)
    no_source = bool(NO_SOURCE_PATTERN.search(text))
    failed = bool(failure_markers) or bool(config_hits) or bool(compile_hits) or bool(test_task_hits)
    xml_failures = xml["failures"] + xml["errors"]

    if gradle_exit == 0 and failed:
        verdict, note = "WRAPPER_EXIT_LIE", "exit 0 but log shows failure markers"
    elif config_hits or compile_hits:
        verdict, note = "BASELINE_BLOCKED", "config/compile failure -- not a valid RED"
    elif xml_failures > 0 or test_task_hits:
        verdict, note = "RED", "test task failed"
    elif no_source or xml["executed"] == 0:
        if gradle_exit not in (0, None):
            verdict, note = "BASELINE_BLOCKED", "nonzero exit, no test evidence"
        elif not no_source and not test_task_hits:
            verdict, note = "NO_TESTS", "no test task in this invocation (compile-only/up-to-date probe)"
        else:
            verdict, note = "NO_TESTS", "zero executed tests or NO-SOURCE"
    elif xml["allSkipped"]:
        verdict, note = "NO_TESTS", "every suite skipped"
    elif gradle_exit in (0, None):
        verdict, note = "GREEN", "executed>0, failures=0, errors=0"
    else:
        verdict, note = "BASELINE_BLOCKED", "unclassified nonzero exit"
    return {
        "verdict": verdict,
        "note": note,
        "markers": {
            "buildFailed": failure_markers,
            "config": config_hits,
            "compile": compile_hits,
            "testTask": test_task_hits,
            "noSource": no_source,
        },
    }


def _parse_epoch(text: str) -> float | None:
    if not text:
        return None
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="test_xml_evidence")
    ap.add_argument("--log", required=True, help="Gradle output log")
    ap.add_argument("--xml-dir", action="append", default=[], help="test-results dir (repeatable)")
    ap.add_argument("--gradle-exit", type=int, default=None)
    ap.add_argument("--since", default=None, help="ISO ts; XML older is stale")
    args = ap.parse_args(argv)
    log_text = Path(args.log).read_text(encoding="utf-8", errors="replace")
    since = _parse_epoch(args.since)
    xml_dirs = [Path(d) for d in args.xml_dir] or []
    xml = {"files": 0, "staleXmlCount": 0, "suites": [], "suiteDetail": [],
           "tests": 0, "executed": 0, "failures": 0, "errors": 0,
           "skipped": 0, "allSkipped": False, "totalTimeSec": 0.0, "jdk": None}
    for d in xml_dirs:
        part = summarize_xml(d, since)
        for k in ("files", "staleXmlCount", "tests", "executed",
                  "failures", "errors", "skipped"):
            xml[k] += part[k]
        xml["suites"] += part["suites"]
        xml["suiteDetail"] += part["suiteDetail"]
        xml["allSkipped"] = xml["allSkipped"] and part["allSkipped"] if xml["suiteDetail"] else False
        xml["totalTimeSec"] = round(xml["totalTimeSec"] + part["totalTimeSec"], 3)
        xml["jdk"] = xml["jdk"] or part["jdk"]
    if xml["suiteDetail"]:
        xml["allSkipped"] = all(s["skipped"] >= s["tests"] for s in xml["suiteDetail"])
    out = {"schemaVersion": SCHEMA, "log": args.log, "xmlDirs": args.xml_dir,
           "gradleExit": args.gradle_exit, "xml": xml,
           **classify_verdict(log_text, xml, args.gradle_exit)}
    print(json.dumps(out, ensure_ascii=False, indent=2))
    print(f"VERDICT: {out['verdict']} -- {out['note']}")
    return {"GREEN": 0, "RED": 1, "NO_TESTS": 5, "BASELINE_BLOCKED": 3,
            "WRAPPER_EXIT_LIE": 4}.get(out["verdict"], 2)


if __name__ == "__main__":
    raise SystemExit(main())
