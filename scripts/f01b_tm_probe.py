#!/usr/bin/env python3
"""f01b_tm_probe.py — JdbcJobService/JobConfig 트랜잭션 매니저 정적 스캔 (GATE-1 힌트).

Contract DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929 §4.2.

정적 휴리스틱만 제공한다 — 어노테이션 존재는 증명이 아니므로 JSON에
"runtimeProofRequired": true 를 고정한다. 실제 GATE-1 증명은 Codex 런타임
failure-injection(JPA USUM insert 후 실패 → 양쪽 rollback)이 담당.

휴리스틱:
  - JdbcJobService 에 `new DataSourceTransactionManager` → PRIVATE_TM_SUSPECT
  - JobConfig/JdbcJobService 가 shared PlatformTransactionManager 주입/빈
    사용 → SHARED_HINT
  - 둘 다 → MIXED (수동 검토)

exit 0 = 스캔 완료·보고서 기록 (결과와 무관) / 2 = 필수 소스 파일 없음 /
3 = 애매/부분 파싱.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import re
import sys

CONTRACT_ID = "DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929"
SCHEMA = "awx.f01b-tm-probe.v1"
DEFAULT_OUT_DIR = "data/diagnostics/f01b-trace-access-0929"
DEFAULT_JDBC_SERVICE = "main/java/com/example/lms/jobs/JdbcJobService.java"
DEFAULT_JOB_CONFIG = "main/java/com/example/lms/config/JobConfig.java"

PRIVATE_TM = re.compile(r"new\s+DataSourceTransactionManager\s*\(")
SHARED_TM_HINT = re.compile(
    r"PlatformTransactionManager|@Autowired[^\n]*TransactionManager|"
    r"DataSourceTransactionManager\s+\w+\s*[,)]|transactionManager\s*\(\s*DataSource")
BEAN_TM = re.compile(
    r"@Bean[^\n]*\n[^}]*?TransactionManager|TransactionManager\s+\w+\s*\(")


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def scan_source(path: Path, rel: str) -> dict:
    """파일 하나를 스캔해 발견 지점(file:line) 목록을 반환."""
    entry = {"path": rel, "exists": path.is_file(), "findings": []}
    if not path.is_file():
        return entry
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as exc:
        entry["error"] = type(exc).__name__
        return entry
    for i, line in enumerate(lines, 1):
        text = line.strip()
        if PRIVATE_TM.search(line):
            entry["findings"].append({
                "kind": "PRIVATE_TM_SUSPECT", "line": i,
                "snippet": text[:160]})
        elif SHARED_TM_HINT.search(line):
            entry["findings"].append({
                "kind": "SHARED_HINT", "line": i, "snippet": text[:160]})
        elif BEAN_TM.search(line):
            entry["findings"].append({
                "kind": "TM_BEAN_HINT", "line": i, "snippet": text[:160]})
    return entry


def classify(findings: list[dict]) -> str:
    kinds = {f["kind"] for f in findings}
    private = "PRIVATE_TM_SUSPECT" in kinds
    shared = "SHARED_HINT" in kinds or "TM_BEAN_HINT" in kinds
    if private and shared:
        return "MIXED"
    if private:
        return "PRIVATE_TM_SUSPECT"
    if shared:
        return "SHARED_HINT"
    return "AMBIGUOUS"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="F01-B GATE-1 static TM hint scan (runtime proof still required)")
    ap.add_argument("--root", default=".")
    ap.add_argument("--jdbc-service", default=DEFAULT_JDBC_SERVICE)
    ap.add_argument("--job-config", default=DEFAULT_JOB_CONFIG)
    ap.add_argument("--json-out",
                    default=f"{DEFAULT_OUT_DIR}/f01b_tm_probe.json")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    targets = [(root / args.jdbc_service, args.jdbc_service),
               (root / args.job_config, args.job_config)]
    files = [scan_source(path, rel) for path, rel in targets]
    missing = [f["path"] for f in files if not f["exists"]]
    errored = [f["path"] for f in files if f.get("error")]

    if missing:
        code = 2
        verdict = "SOURCE_MISSING"
    elif errored:
        code = 2
        verdict = "SOURCE_UNREADABLE"
    else:
        findings = [f for entry in files for f in entry["findings"]]
        verdict = classify(findings)
        code = 0 if verdict != "AMBIGUOUS" else 3

    payload = {
        "schemaVersion": SCHEMA,
        "contractId": CONTRACT_ID,
        "gate": "GATE1_STATIC_HINT",
        "generatedAtUtc": utcnow(),
        "root": str(root),
        "verdict": verdict,
        "exitCode": code,
        "runtimeProofRequired": True,
        "note": "annotation/static presence is NOT proof; Codex runtime "
                "failure-injection is the GATE-1 verdict",
        "missing": missing,
        "files": files,
    }
    out_path = root / args.json_out
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                        encoding="utf-8")
    if args.json:
        print(json.dumps(payload, ensure_ascii=False))
    else:
        print(f"TM probe verdict={verdict} exit={code} json={out_path}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
