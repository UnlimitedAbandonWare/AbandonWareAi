"""Offline campaign-report checker and D3 loop oracle. No network.

Exit 0 PASS, 1 rule violation, 2 usage or schema. Runner vocabulary
(runState / stopReason) is not a product Jev reason.
"""
from __future__ import annotations

import argparse
import json
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SCHEMA = (
    ROOT / "data" / "agent-handoff" / "grok-jev-campaign-tooling-20260930"
    / "fixtures" / "campaign_result.schema.json"
)
HARD = Decimal("2.00")
RUN_STATES = {"RUNNING", "WAITING_FOR_REVIEW", "STOPPED", "DONE"}
STOP_REASONS = {
    "CONSECUTIVE_FAILURE_LIMIT", "BUDGET_CAP", "ROUND_LIMIT", "TIME_LIMIT",
    "AUTH_OR_PLAN_REJECT", "PII_OR_SECRET", "SCOPE_VIOLATION", "FOREIGN_LEASE",
    "UNCOMPUTABLE_COST", "GOAL_MET", "NO_NEW_EVIDENCE",
}
ROUND_CAP = 12
TIME_CAP_MS = 30 * 60 * 1000
IMMEDIATE = {
    "auth": ("STOPPED", "AUTH_OR_PLAN_REJECT"),
    "pii": ("STOPPED", "PII_OR_SECRET"),
    "foreign_lease": ("STOPPED", "FOREIGN_LEASE"),
    "uncomputable": ("STOPPED", "UNCOMPUTABLE_COST"),
    "budget": ("STOPPED", "BUDGET_CAP"),
}


class UsageError(Exception):
    pass


def load_schema(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise UsageError("schema") from exc
    if not isinstance(data, dict) or not isinstance(data.get("required"), list):
        raise UsageError("schema")
    return data


def load_report(path: Path) -> list:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise UsageError("report") from exc
    stripped = text.strip()
    if not stripped:
        raise UsageError("report-empty")
    if path.suffix == ".jsonl" or "\n" in stripped and stripped[0] != "[" and stripped[0] != "{":
        rows = []
        for line in text.splitlines():
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise UsageError("report-json") from exc
            rows.append(row)
        return rows
    try:
        data = json.loads(stripped)
    except json.JSONDecodeError as exc:
        raise UsageError("report-json") from exc
    if isinstance(data, dict) and isinstance(data.get("rows"), list):
        return data["rows"]
    if isinstance(data, dict):
        return [data]
    if isinstance(data, list):
        return data
    raise UsageError("report-shape")


def _schema_problems(rows: list, schema: dict) -> list[str]:
    required = schema["required"]
    enums = schema.get("enums") or {}
    problems = []
    if not isinstance(rows, list) or not rows:
        return ["empty"]
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            problems.append("row-%d-type" % index)
            continue
        for key in required:
            if key not in row:
                problems.append("row-%d-missing-%s" % (index, key))
        for key, allowed in enums.items():
            if key in row and row[key] not in allowed:
                problems.append("row-%d-enum-%s" % (index, key))
    return problems


def _money(value) -> Decimal:
    if isinstance(value, bool) or value is None or value == "":
        return Decimal("0")
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise UsageError("money") from exc


def _incomplete(row: dict) -> bool:
    coverage = row.get("coverage")
    if isinstance(coverage, bool) or not isinstance(coverage, (int, float)) or coverage < 1:
        return True
    if not row.get("sourceHashes") or not row.get("fixtureHash"):
        return True
    unresolved = row.get("unresolvedEvidence")
    return isinstance(unresolved, list) and len(unresolved) > 0


def _failures_present(row: dict) -> bool:
    failures = row.get("failures")
    if failures in (None, [], 0):
        return False
    if isinstance(failures, int):
        return failures > 0
    if isinstance(failures, list):
        return len(failures) > 0
    return True


def rule_ids(rows: list) -> list[str]:
    found: list[str] = []

    def add(rule: str) -> None:
        if rule not in found:
            found.append(rule)

    for index, row in enumerate(rows):
        if _incomplete(row):
            if not (row.get("objectiveScore") is None and row.get("testResult") == "INCONCLUSIVE"):
                add("R1")
        if row.get("testResult") == "PASS":
            critical = row.get("critical")
            hashes = row.get("sourceHashes")
            if (_failures_present(row) or critical not in (0, None)
                    or not isinstance(hashes, dict) or not hashes
                    or row.get("sourceHashesMatch") is False):
                add("R2")
        assessment = row.get("modelAssessment")
        outcome = assessment.get("outcome") if isinstance(assessment, dict) else None
        if outcome == "FAIL" and row.get("testResult") == "PASS":
            add("R3")
        fallback = row.get("fallbackResult")
        if (fallback in ("PASS", "SUCCESS", "OK") and row.get("targetResult") != "PASS"
                and row.get("testResult") == "PASS"):
            add("R4")
        omitted = row.get("omittedFields") or []
        if isinstance(omitted, list):
            for key in ("score01", "selectedProbability", "confidence"):
                if key in omitted and row.get(key) is not None:
                    add("R5")
        try:
            settled = _money(row.get("settledUsd"))
            reserved = _money(row.get("reservedUsd"))
            held = _money(row.get("unknownHeldUsd"))
        except UsageError:
            raise
        if held < 0 or settled + reserved + held > HARD:
            add("R6")
        state = row.get("runState")
        reason = row.get("stopReason")
        if state not in RUN_STATES:
            add("R7")
        elif reason is not None and reason not in STOP_REASONS:
            add("R7")
        elif state != "RUNNING" and reason not in STOP_REASONS:
            add("R7")
        if row.get("verificationScope") == "MOCK" and (
                row.get("providerConnectionClaimed") is True
                or row.get("providerConnection") == "success"):
            add("R8")
        rounds = row.get("rounds")
        elapsed = row.get("elapsedMs")
        if isinstance(rounds, int) and not isinstance(rounds, bool) and rounds > ROUND_CAP:
            add("R9")
        if isinstance(elapsed, int) and not isinstance(elapsed, bool) and elapsed > TIME_CAP_MS:
            add("R9")
        if row.get("consecutiveFailures") == 3:
            if row.get("runState") != "WAITING_FOR_REVIEW":
                add("R10")
            for later in rows[index + 1:]:
                if isinstance(later, dict) and later.get("callAttempt") is True:
                    add("R10")
    return found


def simulate(steps: list, round_cap: int = ROUND_CAP) -> dict:
    consecutive = 0
    calls = 0
    rounds = 0
    state = "RUNNING"
    reason = None
    test_result = None
    target = False
    for step in steps:
        if state != "RUNNING":
            break
        if rounds >= round_cap:
            state, reason = "STOPPED", "ROUND_LIMIT"
            break
        if step == "time":
            state, reason = "STOPPED", "TIME_LIMIT"
            break
        if step == "target_1usd":
            target = True
            continue
        if step in IMMEDIATE:
            calls += 1
            rounds += 1
            state, reason = IMMEDIATE[step]
            break
        if step == "failure":
            calls += 1
            rounds += 1
            consecutive += 1
            test_result = "FAIL"
            if consecutive >= 3:
                state, reason = "WAITING_FOR_REVIEW", "CONSECUTIVE_FAILURE_LIMIT"
                break
            continue
        if step == "unrelated_success":
            calls += 1
            rounds += 1
            continue
        if step == "related_success":
            calls += 1
            rounds += 1
            consecutive = 0
            test_result = "PASS"
            continue
        if step == "expected_fault":
            calls += 1
            rounds += 1
            consecutive = 0
            test_result = "PASS"
            state, reason = "DONE", "GOAL_MET"
            break
        raise UsageError("trace-step")
    if state == "RUNNING" and rounds >= round_cap:
        state, reason = "STOPPED", "ROUND_LIMIT"
    return {
        "runState": state,
        "stopReason": reason,
        "calls": calls,
        "rounds": rounds,
        "testResult": test_result,
        "targetReached": target,
    }


def load_trace(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise UsageError("trace") from exc
    if not isinstance(data, dict) or not isinstance(data.get("steps"), list):
        raise UsageError("trace")
    if not isinstance(data.get("expect"), dict):
        raise UsageError("trace")
    return data


def compare_expect(actual: dict, expect: dict) -> list[str]:
    mismatches = []
    for key, want in expect.items():
        if actual.get(key) != want:
            mismatches.append(key)
    return mismatches


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check a campaign result or a loop-trace oracle.")
    parser.add_argument("--report")
    parser.add_argument("--schema", default=str(DEFAULT_SCHEMA))
    parser.add_argument("--trace")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.trace:
            trace = load_trace(Path(args.trace))
            actual = simulate(trace["steps"])
            if args.self_test or not args.report:
                mismatches = compare_expect(actual, trace["expect"])
                if mismatches:
                    print("FAIL " + ",".join(mismatches))
                    print(json.dumps(actual, ensure_ascii=True))
                    return 1
                print("PASS")
                return 0
            report = json.loads(Path(args.report).read_text(encoding="utf-8"))
            if isinstance(report, dict) and isinstance(report.get("oracle"), dict):
                report = report["oracle"]
            if not isinstance(report, dict):
                raise UsageError("report-shape")
            mismatches = compare_expect(report, trace["expect"])
            if mismatches:
                print("FAIL " + ",".join(mismatches))
                return 1
            print("PASS")
            return 0
        if not args.report:
            raise UsageError("report-required")
        schema = load_schema(Path(args.schema))
        rows = load_report(Path(args.report))
        problems = _schema_problems(rows, schema)
        if problems:
            print("schema " + ",".join(problems[:12]), file=sys.stderr)
            return 2
        violations = rule_ids(rows)
    except UsageError as exc:
        print("usage " + str(exc), file=sys.stderr)
        return 2
    if violations:
        print("FAIL " + ",".join(violations))
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
