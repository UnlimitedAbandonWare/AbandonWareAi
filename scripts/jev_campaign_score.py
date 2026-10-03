"""Objective score and rubric devin-jev-evaluation/v1. No network, no eval.

Exit 0 PASS (or INCONCLUSIVE without --strict), 1 FAIL, 4 INCONCLUSIVE
with --strict, 2 usage or required-list drift. --bench p95 is reported
and is not a failure.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
from pathlib import Path

RUBRIC = "devin-jev-evaluation/v1"
LEVELS = {"L0": "0", "L1": "0.25", "L2": "0.5", "L3": "0.75", "L4": "1.0", "UNKNOWN": None}
RISKS = {"LOW", "HIGH", "UNKNOWN"}
TOKEN = re.compile(r"^[A-Z0-9]+$")
QUESTIONS = ("evidenceFit", "regressionRisk")


class UsageError(Exception):
    pass


def checks_digest(checks: list) -> str:
    payload = json.dumps(checks, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def load_required(path: Path) -> list:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise UsageError("required") from exc
    checks = data.get("checks") if isinstance(data, dict) else None
    if not isinstance(checks, list) or not checks or not all(isinstance(item, str) for item in checks):
        raise UsageError("required")
    expect = data.get("expectedSha256")
    actual = checks_digest(checks)
    if expect != actual:
        raise UsageError("drift")
    return checks


def _rows(data) -> list:
    if isinstance(data, dict) and isinstance(data.get("results"), list):
        data = data["results"]
    if not isinstance(data, list):
        raise UsageError("results")
    return data


def score_rows(rows: list, checks: list) -> dict:
    by_id = {}
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("id"), str):
            raise UsageError("results")
        by_id[row["id"]] = row
    missing = [item for item in checks if item not in by_id or by_id[item].get("executed") is not True]
    executed = sum(1 for item in checks if item in by_id and by_id[item].get("executed") is True)
    passed = sum(1 for item in checks if item in by_id and by_id[item].get("executed") is True
                 and by_id[item].get("passed") is True)
    required = len(checks)
    coverage = executed / required
    if missing:
        return {
            "rubric": RUBRIC,
            "coverage": coverage,
            "objectiveScore": None,
            "testResult": "INCONCLUSIVE",
            "missing": missing,
            "passed": passed,
            "required": required,
        }
    score = (100 * passed) / required
    return {
        "rubric": RUBRIC,
        "coverage": coverage,
        "objectiveScore": int(score) if float(score).is_integer() else score,
        "testResult": "PASS" if passed == required else "FAIL",
        "missing": [],
        "passed": passed,
        "required": required,
    }


def assess(doc: dict) -> dict:
    if not isinstance(doc, dict):
        raise UsageError("assess")
    if len(doc) > 2 or any(key not in QUESTIONS for key in doc):
        raise UsageError("assess-questions")
    model = {}
    if "evidenceFit" in doc:
        token = doc["evidenceFit"]
        if not isinstance(token, str) or TOKEN.fullmatch(token) is None or token not in LEVELS:
            model["evidenceFit"] = None
        else:
            model["evidenceFit"] = LEVELS[token]
    if "regressionRisk" in doc:
        token = doc["regressionRisk"]
        model["regressionRisk"] = token if token in RISKS else None
    return {"rubric": RUBRIC, "modelAssessment": model, "averaged": False}


def bench(samples: int = 10000) -> dict:
    checks = ["a", "b", "c", "d"]
    rows = [
        {"id": "a", "executed": True, "passed": True},
        {"id": "b", "executed": True, "passed": False},
        {"id": "c", "executed": True, "passed": True},
        {"id": "d", "executed": True, "passed": True},
    ]
    times = []
    for _ in range(samples):
        started = time.perf_counter()
        score_rows(rows, checks)
        times.append((time.perf_counter() - started) * 1000.0)
    times.sort()
    index = min(len(times) - 1, int(0.95 * (len(times) - 1)))
    p95 = times[index]
    return {"samples": samples, "p95Ms": p95, "targetMs": 50, "withinTarget": p95 < 50}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Score required checks. Model text is not executed.")
    parser.add_argument("--results")
    parser.add_argument("--required")
    parser.add_argument("--assess")
    parser.add_argument("--bench", action="store_true")
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args(argv)
    if not any((args.results, args.assess, args.bench)):
        print("usage results-or-assess", file=sys.stderr)
        return 2
    try:
        body = {}
        if args.bench:
            body["bench"] = bench()
        if args.required or args.results:
            if not args.required or not args.results:
                raise UsageError("results-and-required")
            checks = load_required(Path(args.required))
            rows = _rows(json.loads(Path(args.results).read_text(encoding="utf-8")))
            body.update(score_rows(rows, checks))
        if args.assess:
            assessed = assess(json.loads(Path(args.assess).read_text(encoding="utf-8")))
            body["modelAssessment"] = assessed["modelAssessment"]
            body["averaged"] = False
            body.setdefault("rubric", RUBRIC)
    except UsageError as exc:
        print("usage " + str(exc), file=sys.stderr)
        return 2
    except (OSError, json.JSONDecodeError):
        print("usage json", file=sys.stderr)
        return 2
    print(json.dumps(body, ensure_ascii=True))
    result = body.get("testResult")
    if result == "FAIL":
        return 1
    if result == "INCONCLUSIVE" and args.strict:
        return 4
    return 0


if __name__ == "__main__":
    sys.exit(main())
