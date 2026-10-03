#!/usr/bin/env python3
"""f01b_focused_verify.py — F01-B focused 테스트를 한 번에 수집·실행·집계.

Contract DEMO1-DEVIN-F01B-POST-TOOLS-20260929 항목 3 (G3).

SoT handoff 의 `*-suites.json`(이전 green 실행 증거)이나
`verification-plan.json` 의 filters 를 재사용해 테스트 대상을 모은 뒤,
한 번의 `gradlew.bat test --tests A --tests B ...` 호출로 실행하고
JUnit XML 결과를 합산한다. 출력 JSON 은 `verifyFragment` 필드로
awx.debug.verify.v2 의 tests 슬롯에 병합 가능한 형태를 갖는다.

- 기본은 `--collect-only`: 대상 수집만 보고하고 Gradle 은 실행하지 않는다
  (안전 기본값 — gradle 실행은 명시적 `--execute` 에서만).
- `--results-dir <dir>` 로 기존 TEST-*.xml 만 파싱해 재집계 가능
  (픽스처 테스트/오프라인 경로).
- 실행 실패는 테스트 실패와 구분해 기록한다 — unrun suite 는 pass 수에 넣지 않는다.

exit 0 실행+전부 pass / 1 usage·IO / 2 실행됐으나 failure/error>0 또는 gradle!=0
     / 3 collect-only (실행 안 함 — evidence_needed 성격).
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

CONTRACT_ID = "DEMO1-DEVIN-F01B-POST-TOOLS-20260929"
SCHEMA = "awx.f01b-focused-verify.v1"
DEFAULT_HANDOFF_DIR = ("data/agent-handoff/codex-autonomy/"
                       "f01b-jdbc-implementation-0929-db9db278")
DEFAULT_RESULTS_DIR = "build/test-results/test"
DEFAULT_OUT = "data/diagnostics/f01b-post-tools-0929/f01b_focused_verify.json"
DEFAULT_PLAN = DEFAULT_HANDOFF_DIR + "/verification-plan.json"

EXIT_OK = 0
EXIT_USAGE = 1
EXIT_FAILURES = 2
EXIT_COLLECTED = 3

RE_TEST_FILE = re.compile(r"^TEST-(.+)\.xml$")


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def _load(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def collect_from_suites_dir(suites_dir: Path) -> list[str]:
    """<dir>/*-suites.json → suite FQCN 집합 (file 또는 suite 필드)."""
    found = set()
    if not suites_dir.is_dir():
        return []
    for path in sorted(suites_dir.glob("*-suites.json")):
        data = _load(path)
        rows = data if isinstance(data, list) else \
            (data or {}).get("suites") or []
        for row in rows:
            name = row.get("suite") or ""
            if not name:
                fname = str(row.get("file") or "")
                m = RE_TEST_FILE.match(fname)
                name = m.group(1) if m else ""
            if name:
                found.add(name)
    return sorted(found)


def collect_from_plan(plan_path: Path) -> list[str]:
    data = _load(plan_path) or {}
    return [str(f) for f in (data.get("filters") or [])]


def gradle_command(root: Path, patterns: list[str],
                   gradlew: str = "gradlew.bat") -> list[str]:
    cmd = [str(root / gradlew), "test", "--console=plain"]
    for p in patterns:
        cmd += ["--tests", p]
    return cmd


def parse_results(results_dir: Path) -> list[dict]:
    """JUnit TEST-*.xml → [{suite, tests, failures, errors, skipped}]."""
    rows = []
    if not results_dir.is_dir():
        return rows
    for path in sorted(results_dir.glob("TEST-*.xml")):
        try:
            tree = ET.parse(path)
        except ET.ParseError:
            continue
        root_el = tree.getroot()
        suites = [root_el] if root_el.tag == "testsuite" else \
            list(root_el.iter("testsuite"))
        for el in suites:
            rows.append({
                "suite": el.get("name") or path.stem,
                "file": path.name,
                "tests": int(el.get("tests") or 0),
                "failures": int(el.get("failures") or 0),
                "errors": int(el.get("errors") or 0),
                "skipped": int(el.get("skipped") or 0),
            })
    return rows


def totals(rows: list[dict]) -> dict:
    return {"tests": sum(r["tests"] for r in rows),
            "failures": sum(r["failures"] for r in rows),
            "errors": sum(r["errors"] for r in rows),
            "skipped": sum(r["skipped"] for r in rows),
            "suites": len(rows)}


def verify_fragment(run: str, verdict: str, rows: list[dict],
                    gradle_exit: int | None, evidence: dict) -> dict:
    """awx.debug.verify.v2 의 tests 슬롯에 병합 가능한 조각.
    run 필드는 'executed' | 'skipped' — collect-only 는 절대 pass 로 세지 않는다."""
    return {"run": run, "verdict": verdict, "ok": verdict == "passed",
            "totals": totals(rows), "gradleExit": gradle_exit,
            "suites": rows, "evidence": evidence}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="F01-B focused test one-shot collect/run/aggregate "
                    "(JUnit XML → verify-mergeable JSON)")
    ap.add_argument("--root", default=".")
    ap.add_argument("--suites-dir", default=DEFAULT_HANDOFF_DIR,
                    help="*-suites.json 수집 디렉터리")
    ap.add_argument("--plan", default=None,
                    help="verification-plan.json filters 수집")
    ap.add_argument("--suite", action="append", default=[],
                    help="추가 --tests 패턴 (반복 가능)")
    ap.add_argument("--collect-only", action="store_true",
                    help="수집만 하고 Gradle 미실행 (기본 동작)")
    ap.add_argument("--execute", action="store_true",
                    help="gradlew.bat test 실제 실행")
    ap.add_argument("--gradlew", default="gradlew.bat")
    ap.add_argument("--results-dir", default=DEFAULT_RESULTS_DIR,
                    help="JUnit TEST-*.xml 디렉터리 (실행 후 또는 오프라인 파싱)")
    ap.add_argument("--parse-only", action="store_true",
                    help="Gradle 실행 없이 --results-dir 만 파싱")
    ap.add_argument("--timeout", type=int, default=1800)
    ap.add_argument("--json-out", default=DEFAULT_OUT)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    patterns = set(args.suite)
    if args.plan:
        patterns.update(collect_from_plan(root / args.plan))
    patterns.update(collect_from_suites_dir(root / args.suites_dir))
    patterns = sorted(p for p in patterns if p)

    cmd = gradle_command(root, patterns, args.gradlew)
    rows = []
    gradle_exit = None
    code = EXIT_COLLECTED
    run_state = "skipped"
    verdict = "collected-not-run"

    if args.parse_only or (args.execute and not args.collect_only):
        if args.execute:
            try:
                proc = subprocess.run(cmd, cwd=str(root), capture_output=True,
                                      text=True, timeout=args.timeout)
                gradle_exit = proc.returncode
            except (OSError, subprocess.TimeoutExpired) as exc:
                gradle_exit = f"spawn-{type(exc).__name__}"
        rows = parse_results(root / args.results_dir)
        t = totals(rows)
        run_state = "executed"
        if isinstance(gradle_exit, int) and gradle_exit != 0:
            verdict = "gradle-failed"
            code = EXIT_FAILURES
        elif t["failures"] or t["errors"]:
            verdict = "failed"
            code = EXIT_FAILURES
        elif not rows:
            verdict = "no-results"
            code = EXIT_FAILURES
        elif isinstance(gradle_exit, str):
            verdict = "spawn-failed"
            code = EXIT_FAILURES
        else:
            verdict = "passed"
            code = EXIT_OK

    fragment = verify_fragment(
        run_state, verdict, rows,
        gradle_exit if not isinstance(gradle_exit, str) else None,
        {"resultsDir": args.results_dir,
         "suitesDir": args.suites_dir,
         "plan": args.plan,
         "gradleSpawnError": gradle_exit if isinstance(gradle_exit, str)
                           else None})
    payload = {
        "schemaVersion": SCHEMA,
        "contractId": CONTRACT_ID,
        "generatedAtUtc": utcnow(),
        "root": str(root),
        "patterns": patterns,
        "patternCount": len(patterns),
        "command": " ".join(cmd),
        "run": run_state,
        "verdict": verdict,
        "verifyFragment": fragment,
        "exitCode": code,
        "never": ["full-suite-claim", "unrun-counted-as-pass"],
    }
    out_path = Path(args.json_out)
    if not out_path.is_absolute():
        out_path = root / out_path
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                        encoding="utf-8")
    if args.json:
        print(json.dumps(payload, ensure_ascii=False))
    else:
        print(f"focused_verify verdict={verdict} run={run_state} "
              f"patterns={len(patterns)} exit={code} json={out_path}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
