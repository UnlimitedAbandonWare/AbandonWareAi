#!/usr/bin/env python3
"""f01b_evidence_pack.py — F01-B 증거팩 스캐폴드 (docs/diagnostics/f01b-narrow-jdbc-0929/).

Contract DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929 §4.4.

생성물:
  docs/diagnostics/f01b-narrow-jdbc-0929/
    README.md        — 팩 개요
    GATE0_PROBE.md   — schema_gate 결과 embed/link (probe JSON 주면 요약 삽입)
    FOR_CODEX.md     — Codex 핸드오프 스켈레톤 (명령·NEVER·stay-A)
    decision.json    — 결정 스텁 (B-narrow, task_ask/callbacks false)

이미 존재하는 비어 있지 않은 파일은 --force 없이 덮어쓰지 않는다.
모든 대상이 이미 존재하면 exit 3 (skipped_existing).

exit 0 created/updated / 1 error / 3 skipped existing without --force.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import sys

CONTRACT_ID = "DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929"
CONTRACT_ID_POST = "DEMO1-DEVIN-F01B-POST-TOOLS-20260929"
SCHEMA = "awx.f01b-evidence-pack.v1"
SCHEMA_SUMMARY = "awx.f01b-evidence-summary.v1"
DEFAULT_OUT_DIR = "docs/diagnostics/f01b-narrow-jdbc-0929"
DEFAULT_PROBE_JSON = ("data/diagnostics/f01b-trace-access-0929/"
                      "f01b_schema_gate.json")
DEFAULT_HANDOFF_DIR = ("data/agent-handoff/codex-autonomy/"
                       "f01b-jdbc-implementation-0929-db9db278")
DEFAULT_SUMMARY_MD = "docs/diagnostics/f01b-narrow-jdbc-0929/EVIDENCE_SUMMARY.md"

DECISION_STUB = {
    "scope": "B-narrow",
    "task_ask": False,
    "callbacks": False,
    "schema": None,
    "tm_proof": None,
    "status": "assist_scripts_ready",
    "devin_product_java_diff": 0,
}

FOR_CODEX_MD = """# FOR_CODEX — F01-B narrow JDBC UNDERSTANDING (evidence pack)

Contract: DEMO1-CODEX-F01B-NARROW-JDBC-UNDERSTANDING-20260929
Devin scripts contract: DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929

## GATE-0 명령 (제품 코드 없이)

```powershell
cd C:\\AbandonWare\\demo-1\\demo-1\\src
python -B scripts/f01b_schema_gate.py --mode both --json-out data/diagnostics/f01b-trace-access-0929/f01b_schema_gate.json
python -B scripts/f01b_tm_probe.py --json-out data/diagnostics/f01b-trace-access-0929/f01b_tm_probe.json
python -B scripts/f01b_admission_key_demo.py --demo
```

## 판정 규칙

- GATE0 exit 2/3 → stay F01-A + evidence_needed; enqueue/enable 금지.
- GATE0 exit 0 → schema 준비만 확인됨. 제품 활성화는 Codex RED→GREEN 이후.
- tm_probe verdict 는 정적 힌트일 뿐 — GATE-1 증명은 런타임 failure-injection.
- `runtimeProofRequired: true` 를 PASS 증거로 읽지 말 것.

## NEVER

- InMemoryJobQueue F01-B store 금지 / task_ask 활성화 금지 / n8n 콜백 금지
- Autograde B 재오픈 / F02 scanner 재전투 / commit·push / secrets 출력
- DDL apply (dry-run/read-only 만)
- `abandonware.understanding.deferred.enabled` 플립 금지

## FILL (Codex)

- [ ] GATE-0 verdict: ____ (exit __)
- [ ] GATE-1 runtime proof: ____ (test filter ____)
- [ ] admission/effect/fingerprint 정합: ____
- [ ] decision.json 실측값으로 갱신
"""

README_MD = """# f01b-narrow-jdbc-0929 — F01-B narrow JDBC UNDERSTANDING evidence pack

Track: **F01B** (TRACE 와 병합 금지).

이 팩은 Devin scripts 계약이 만든 스캐폴드다. Codex가 GATE-0/GATE-1 증거를
채운다. 제품 Java diff 는 Codex 계약 소관 — Devin 은 이 팩 + scripts 만 만든다.

- GATE0_PROBE.md — 스키마 존재 프로브 결과
- FOR_CODEX.md — 핸드오프 스켈레톤
- decision.json — 결정 스텁 (`schema`/`tm_proof` 는 Codex가 채움)
"""


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


# ---------- EVIDENCE_SUMMARY (--summary, POST-TOOLS 항목 4) ----------

def _load_json(path: Path) -> dict | list | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def _count(value) -> int:
    """suites 행의 카운트 필드: int | list(실패 상세) | None 모두 수용."""
    if isinstance(value, list):
        return len(value)
    if value is None:
        return 0
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def _suite_totals(rows: list) -> dict:
    return {"tests": sum(_count(r.get("tests")) for r in rows),
            "failures": sum(_count(r.get("failures")) for r in rows),
            "errors": sum(_count(r.get("errors")) for r in rows),
            "skipped": sum(_count(r.get("skipped")) for r in rows),
            "suites": len(rows)}


def scan_handoff(handoff: Path) -> dict:
    """SoT 디렉터리 스캔 → RED→GREEN / GATE / DDL / NOT_RUN 증거 테이블."""
    files = sorted(p.name for p in handoff.glob("*.json")) \
        if handoff.is_dir() else []

    # RED→GREEN: <slice>-red-suites.json ↔ <slice>-green-suites.json 페어링.
    # suites 파일은 raw list 또는 {"suites":[...],"totals":{}} 두 형태.
    def suite_rows(name: str):
        data = _load_json(handoff / name)
        if isinstance(data, list):
            return data
        if isinstance(data, dict):
            rows = data.get("suites")
            return rows if isinstance(rows, list) else None
        return None

    red, green, other_runs = {}, {}, {}
    for name in files:
        if not name.endswith("-suites.json"):
            continue
        stem = name[:-len("-suites.json")]
        rows = suite_rows(name)
        if rows is None:
            continue
        totals = _suite_totals(rows)
        if stem.endswith("-red"):
            red[stem[:-4]] = totals
        elif stem.endswith("-green"):
            green[stem[:-6]] = totals
        else:
            other_runs[stem] = totals
    pairs = []
    for slice_name in sorted(set(red) | set(green)):
        pairs.append({
            "slice": slice_name,
            "red": red.get(slice_name),
            "green": green.get(slice_name),
            "greenZeroFailures": bool(green.get(slice_name)) and
                green[slice_name]["failures"] == 0 and
                green[slice_name]["errors"] == 0,
        })
    unpaired_green = [s for s in green if s not in red]

    # GATE: gate-suites.json 총합 + gate0-*-*.json verdict/exit.
    gate_rows = []
    gate_suites = _load_json(handoff / "gate-suites.json")
    if isinstance(gate_suites, list):
        gate_rows.append({"evidence": "gate-suites.json",
                          **_suite_totals(gate_suites)})
    for name in files:
        if name.startswith("gate0-") and name.endswith(".json"):
            data = _load_json(handoff / name) or {}
            gate_rows.append({"evidence": name,
                              "verdict": data.get("verdict"),
                              "exitCode": data.get("exitCode"),
                              "via": (data.get("live") or {}).get("via")})

    # DDL: migration-*-applied.json.
    ddl_rows = []
    for name in files:
        if name.startswith("migration-") and name.endswith("-applied.json"):
            data = _load_json(handoff / name) or {}
            ddl_rows.append({
                "evidence": name,
                "file": data.get("file"),
                "statementCount": data.get("statementCount"),
                "approvedSha256": str(data.get("approvedMigrationSha256")
                                      or "")[:16],
                "applied": data.get("applied"),
                "jdbcExit": data.get("jdbcExit"),
            })

    # NOT_RUN: final/completion-*.json 최신본 limitations 에서 false/NOT 계열.
    not_run = []
    affected = None
    final_dir = handoff / "final"
    completions = sorted(final_dir.glob("completion-v*.json")) \
        if final_dir.is_dir() else []
    if completions:
        comp = _load_json(completions[-1]) or {}
        lim = comp.get("limitations") or {}
        for k, v in lim.items():
            if v is False:
                not_run.append({"item": k, "reason": "false in limitations"})
            elif isinstance(v, str) and ("NOT" in v or "not" == v.lower()):
                not_run.append({"item": k, "reason": v})
        affected = lim.get("affectedTests")
        runtime = lim.get("runtime") or {}
        if runtime.get("fullVerification") is False:
            not_run.append({"item": "fullVerification",
                            "reason": f"target={runtime.get('target')} "
                                      "(verify-RAG 범위 외)"})
    plan = _load_json(handoff / "verification-plan.json") or {}
    if plan.get("providerProof"):
        not_run.append({"item": "providerProof",
                        "reason": plan["providerProof"]})
    if plan.get("runtimeActivation"):
        not_run.append({"item": "runtimeActivation",
                        "reason": plan["runtimeActivation"]})

    return {
        "handoffDir": str(handoff),
        "jsonFileCount": len(files),
        "allFileCount": sum(1 for _ in handoff.rglob("*") if _.is_file())
            if handoff.is_dir() else 0,
        "redGreen": pairs,
        "unpairedGreen": unpaired_green,
        "otherRuns": other_runs,
        "gate": gate_rows,
        "ddl": ddl_rows,
        "notRun": not_run,
        "affectedTests": affected,
    }


def summary_md(scan: dict, generated: str) -> str:
    """한 장짜리 EVIDENCE_SUMMARY.md 본문."""
    lines = [
        "# EVIDENCE_SUMMARY — F01-B narrow JDBC UNDERSTANDING", "",
        f"- contract: `DEMO1-CODEX-F01B-NARROW-JDBC-UNDERSTANDING-20260929`",
        f"- post-tools contract: `{CONTRACT_ID_POST}`",
        f"- generatedAtUtc: {generated}",
        f"- SoT: `{scan['handoffDir']}` "
        f"(json {scan['jsonFileCount']} / files {scan['allFileCount']})",
        "", "## RED→GREEN", "",
        "| slice | red tests | green tests | green fail/err |", "|---|---|---|---|"]
    for row in scan["redGreen"]:
        r = row["red"] or {}
        g = row["green"] or {}
        lines.append(
            f"| {row['slice']} | {r.get('tests', '—')} | {g.get('tests', '—')} "
            f"| {g.get('failures', '—')}/{g.get('errors', '—')} |")
    if scan["unpairedGreen"]:
        lines.append(f"- green-only slices: {', '.join(scan['unpairedGreen'])}")
    if scan.get("otherRuns"):
        lines += ["", "추가 런 (페어 아님): " + ", ".join(
            f"{k}={v['tests']}t/{v['failures']}f"
            for k, v in sorted(scan["otherRuns"].items()))]
    lines += ["", "## GATE", "",
              "| evidence | verdict/tests | failures | exit |", "|---|---|---|---|"]
    for row in scan["gate"]:
        if "tests" in row:
            lines.append(f"| {row['evidence']} | {row['tests']} tests | "
                         f"{row['failures']} | — |")
        else:
            lines.append(f"| {row['evidence']} | {row.get('verdict')} | — "
                         f"| {row.get('exitCode')} |")
    lines += ["", "## DDL 적용 (3건)", "",
              "| file | statements | sha256 | applied | jdbcExit |",
              "|---|---|---|---|---|"]
    for row in scan["ddl"]:
        lines.append(f"| `{row.get('file')}` | {row.get('statementCount')} "
                     f"| {row.get('approvedSha256')}… | {row.get('applied')} "
                     f"| {row.get('jdbcExit')} |")
    lines += ["", "## NOT_RUN / 미검증", "",
              "| item | reason |", "|---|---|"]
    for row in scan["notRun"]:
        lines.append(f"| {row['item']} | {row['reason']} |")
    if scan.get("affectedTests"):
        at = scan["affectedTests"]
        lines += ["", f"- affectedTests: {at.get('tests')} total / "
                      f"{at.get('passed')} pass / {at.get('failed')} baseline "
                      f"fail (new={at.get('newFailures')})"]
    lines += ["", "NEVER: product java diff 0 유지 / DDL apply 없음 / "
                  "secrets 출력 없음 / commit·push 없음", ""]
    return "\n".join(lines)


def run_summary(root: Path, handoff_dir: str, md_out: str,
                json_out: str | None, force: bool, as_json: bool) -> int:
    handoff = root / handoff_dir
    scan = scan_handoff(handoff)
    md_path = root / md_out
    if md_path.exists() and md_path.stat().st_size > 0 and not force:
        payload = {"schemaVersion": SCHEMA_SUMMARY,
                   "contractId": CONTRACT_ID_POST,
                   "status": "skipped_existing", "mdOut": str(md_path)}
        if as_json:
            print(json.dumps(payload, ensure_ascii=False))
        else:
            print(f"summary skipped existing {md_path} (use --force)")
        return 3
    body = summary_md(scan, utcnow())
    md_path.parent.mkdir(parents=True, exist_ok=True)
    md_path.write_text(body, encoding="utf-8")
    payload = {"schemaVersion": SCHEMA_SUMMARY,
               "contractId": CONTRACT_ID_POST,
               "generatedAtUtc": utcnow(),
               "mdOut": str(md_path), "scan": scan, "exitCode": 0}
    if json_out:
        out = Path(json_out)
        if not out.is_absolute():
            out = root / out
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                       encoding="utf-8")
    if as_json:
        print(json.dumps(payload, ensure_ascii=False))
    else:
        print(f"summary written {md_path} "
              f"(slices={len(scan['redGreen'])} ddl={len(scan['ddl'])} "
              f"notRun={len(scan['notRun'])})")
    return 0


def gate0_probe_md(probe: dict | None, probe_path: str | None) -> str:
    lines = ["# GATE0_PROBE — schema gate evidence", ""]
    if probe:
        lines += [
            f"- source JSON: `{probe_path}`",
            f"- generatedAtUtc: {probe.get('generatedAtUtc')}",
            f"- verdict: **{probe.get('verdict')}** (exit {probe.get('exitCode')})",
            "",
            "## file checks", ""]
        for k, v in (probe.get("file", {}).get("checks") or {}).items():
            lines.append(f"- {k}: {v}")
        live = probe.get("live") or {}
        lines += ["", "## live", "",
                  f"- reachable: {live.get('reachable')} via={live.get('via')}",
                  f"- tables: {live.get('tables')}",
                  f"- columns: {live.get('columns')}",
                  f"- index: {live.get('index')}"]
    else:
        lines += ["- probe JSON 아직 없음 — Codex가 아래 명령으로 생성:",
                  "",
                  "```powershell",
                  "python -B scripts/f01b_schema_gate.py --mode both "
                  "--json-out data/diagnostics/f01b-trace-access-0929/"
                  "f01b_schema_gate.json",
                  "```"]
    lines += ["", "NEVER: DDL apply / secrets / deferred flag flip", ""]
    return "\n".join(lines)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="Scaffold F01-B evidence pack under docs/diagnostics/")
    ap.add_argument("--root", default=".")
    ap.add_argument("--out-dir", default=DEFAULT_OUT_DIR)
    ap.add_argument("--probe-json", default=DEFAULT_PROBE_JSON)
    ap.add_argument("--summary", action="store_true",
                    help="SoT handoff 스캔 → EVIDENCE_SUMMARY.md 한 장 생성 "
                         "(POST-TOOLS 항목 4)")
    ap.add_argument("--handoff-dir", default=DEFAULT_HANDOFF_DIR,
                    help="--summary 대상 SoT 디렉터리")
    ap.add_argument("--md-out", default=DEFAULT_SUMMARY_MD,
                    help="--summary 출력 MD 경로")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--json-out", default=None,
                    help="실행 결과 JSON 경로 (선택)")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    if args.summary:
        return run_summary(root, args.handoff_dir, args.md_out,
                           args.json_out, args.force, args.json)
    out_dir = root / args.out_dir

    probe = None
    probe_path = root / args.probe_json
    if probe_path.is_file():
        try:
            probe = json.loads(probe_path.read_text(encoding="utf-8"))
        except ValueError:
            probe = None

    targets = {
        "README.md": README_MD,
        "GATE0_PROBE.md": gate0_probe_md(probe, args.probe_json),
        "FOR_CODEX.md": FOR_CODEX_MD,
        "decision.json": json.dumps(DECISION_STUB, ensure_ascii=False,
                                    indent=2) + "\n",
    }

    written, skipped = [], []
    try:
        out_dir.mkdir(parents=True, exist_ok=True)
        for name, body in targets.items():
            path = out_dir / name
            if path.exists() and path.stat().st_size > 0 and not args.force:
                skipped.append(name)
                continue
            path.write_text(body, encoding="utf-8")
            written.append(name)
    except OSError as exc:
        print(f"write 실패: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1

    code = 3 if (skipped and not written) else 0
    payload = {
        "schemaVersion": SCHEMA,
        "contractId": CONTRACT_ID,
        "generatedAtUtc": utcnow(),
        "outDir": str(out_dir),
        "written": written,
        "skippedExisting": skipped,
        "probeEmbedded": probe is not None,
        "exitCode": code,
    }
    if args.json_out:
        out = Path(args.json_out)
        if not out.is_absolute():
            out = root / out
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                       encoding="utf-8")
        payload["jsonOut"] = str(out)
    if args.json:
        print(json.dumps(payload, ensure_ascii=False))
    else:
        print(f"evidence_pack written={written} skipped={skipped} exit={code}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
