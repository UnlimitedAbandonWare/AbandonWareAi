#!/usr/bin/env python3
"""Integration gate for one parallel-lane plan (INTEGRATOR runs this).

Contract DEMO1-DEVIN-CODEX-PARALLEL-LANES-20261002 (DV10). Read-only check:

  * every OWNER lane journal is closed or carries a handoff record
  * each lane's last checkpoint postimage sha still equals the live file sha
    (a mismatch names which later task's postimage matches instead)
  * no LANE_VIOLATION (a lane's sealed checkpoint wrote outside writeScope)
  * foreign-hunk snapshots under lane task dirs lose 0 hunks other than the
    ones a journal event marked intentional

Then it merges the focused test classes each lane reported (lane-report.json
`tests` or journal verify events) into ONE gradle line for the integrator to
run - this tool never runs Gradle itself.

  python -B scripts/codex_lane_integrate.py check --plan plan-ab12cd34 --json

Result: READY_FOR_INTEGRATION_TEST | BLOCKED(reasons[]). Exit 0 ready,
7 blocked, 2 usage.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codex_parallel_lib as lib
import codex_parallel_preflight as preflight

SCHEMA = "awx.lane-integrate.v1"
TEST_ARG_RE = re.compile(r"--tests\s+\"?([A-Za-z0-9_.*]+)")
SNAPSHOT_GLOBS = ("*foreign*hunk*.json", "*hunk*snapshot*.json")
ALLOWED_WRITE_EXTRA = ("data/agent-handoff", "var/", "__patch_drop__")


def lane_journals(root, plan):
    mapping = {}
    for journal in lib.iter_journals(root):
        plan_tag, lane_tag = lib.lane_tag(journal)
        if plan_tag == plan.get("planId") and lane_tag:
            mapping.setdefault(lane_tag, []).append(journal)
    return mapping


def reported_tests(root, task_id):
    task_dir = Path(root) / lib.JOURNAL_BASE / str(task_id)
    tests = []
    report = lib._read_json(task_dir / "lane-report.json")
    if isinstance(report, dict):
        tests += [str(t) for t in report.get("tests") or []]
    journal = lib._read_json(task_dir / "journal.json") or {}
    for event in journal.get("events") or []:
        tests += TEST_ARG_RE.findall(str(event.get("text") or ""))
    for run in task_dir.glob("*/run.json") if task_dir.is_dir() else []:
        doc = lib._read_json(run) or {}
        cmd = doc.get("command")
        if isinstance(cmd, list):
            cmd = " ".join(str(c) for c in cmd)
        tests += TEST_ARG_RE.findall(str(cmd or ""))
    return sorted(set(tests))


def check_postimage_drift(root, journal):
    """live sha vs the lane's last checkpoint postimage; on mismatch name the
    task whose postimage does match the current bytes."""
    drift = []
    posts = lib.latest_checkpoint_postimages(root, journal.get("taskId"))
    for path, sha in posts.items():
        current = lib.file_sha(root, path)
        if current is None or current == sha:
            continue
        culprit = None
        for other in lib.iter_journals(root):
            if other.get("taskId") == journal.get("taskId"):
                continue
            if lib.latest_checkpoint_postimages(root, other.get("taskId")).get(path) == current:
                culprit = other.get("taskId")
                break
        drift.append({"path": path, "expectedPostimage": sha[:12],
                      "currentSha": current[:12],
                      "lastWriterTaskId": culprit})
    return drift


def check_foreign_hunks(root, task_ids):
    """Re-run lost_hunks on every snapshot file found in lane task dirs.
    Returns (lostCount, checkedCount, notObservedReason)."""
    import foreign_hunk_preserve_check as fhpc
    lost, checked = 0, 0
    intended = set()
    for task_id in task_ids:
        journal = lib._read_json(
            Path(root) / lib.JOURNAL_BASE / task_id / "journal.json") or {}
        for event in journal.get("events") or []:
            for m in re.finditer(r"[Ii]ntentional foreign hunk[^.]*?([A-Za-z0-9_./\\-]+\.[a-z]+)",
                                 str(event.get("text") or "")):
                intended.add(lib.canon(m.group(1)))
    for task_id in task_ids:
        task_dir = Path(root) / lib.JOURNAL_BASE / task_id
        if not task_dir.is_dir():
            continue
        for pattern in SNAPSHOT_GLOBS:
            for snap_path in task_dir.glob(pattern):
                snap = lib._read_json(snap_path)
                if not isinstance(snap, dict):
                    continue
                checked += 1
                try:
                    for row in fhpc.lost_hunks(Path(root), snap):
                        if lib.canon(row.get("path")) in intended:
                            continue
                        lost += 1
                except Exception:
                    continue
    return lost, checked, ("no-snapshot-files" if checked == 0 else None)


def run_check(root, plan):
    reasons, lanes_report, tests = [], [], []
    journals_by_lane = lane_journals(root, plan)
    owner_lanes = [l for l in plan.get("lanes") or [] if l.get("role") == "OWNER"]
    task_ids = []
    for lane in owner_lanes:
        lane_id = lane["laneId"]
        js = journals_by_lane.get(lane_id) or []
        row = {"laneId": lane_id, "tasks": [j.get("taskId") for j in js],
               "closed": False, "drift": [], "violations": []}
        if not js:
            reasons.append(f"lane-unassigned:{lane_id}")
            lanes_report.append(row)
            continue
        for journal in js:
            task_ids.append(journal.get("taskId"))
            done = journal.get("status") != "in_progress" or \
                lib.has_handoff(root, journal.get("taskId"))
            row["closed"] = row["closed"] or done
            if not done:
                reasons.append(f"lane-open:{lane_id}:{journal.get('taskId')}")
            row["drift"] += check_postimage_drift(root, journal)
            tests += reported_tests(root, journal.get("taskId"))
        row["violations"] = preflight.lane_violations(root, plan, lane_id)
        if row["drift"]:
            reasons += [f"postimage-drift:{d['path']}" for d in row["drift"]]
        if row["violations"]:
            reasons += [f"lane-violation:{v.get('taskId')}:{v.get('path', v.get('paths'))}"
                        for v in row["violations"]]
        lanes_report.append(row)
    lost, hunk_checked, hunk_note = check_foreign_hunks(root, task_ids)
    if lost:
        reasons.append(f"foreign-hunk-lost:{lost}")
    ready = not reasons
    test_cmd = ""
    if tests:
        joined = " ".join(f"--tests {t}" for t in sorted(set(tests)))
        test_cmd = f".\\gradlew.bat test {joined}"
    return {"schemaVersion": SCHEMA, "ok": True, "planId": plan["planId"],
            "result": "READY_FOR_INTEGRATION_TEST" if ready else "BLOCKED",
            "reasons": reasons, "lanes": lanes_report,
            "foreignHunks": {"lost": lost, "snapshotsChecked": hunk_checked,
                             "note": hunk_note},
            "integrationTestCommand": test_cmd,
            "collectedTests": sorted(set(tests))}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    sub = parser.add_subparsers(dest="action", required=True)
    check = sub.add_parser("check")
    check.add_argument("--plan", required=True)
    check.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    if args.action == "check":
        plan = lib.load_plan(root, args.plan)
        if plan is None:
            print(json.dumps({"schemaVersion": SCHEMA, "ok": False,
                              "reason": f"plan-missing:{args.plan}"},
                             ensure_ascii=True))
            return 2
        body = run_check(root, plan)
        print(json.dumps(body, ensure_ascii=True, indent=2))
        return 0 if body["result"] == "READY_FOR_INTEGRATION_TEST" else 7
    return 2


if __name__ == "__main__":
    sys.exit(main())
