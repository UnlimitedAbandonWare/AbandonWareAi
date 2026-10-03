#!/usr/bin/env python3
"""brief_round_gate.py — 라운드 카드 진행 게이트. 이전 라운드의 Acceptance가 채워졌는지 보고 다음 카드를 연다.

재사용한 기존 스크립트: brief_lint.py 의 _write_utf8 (출력 인코딩)
새로 추가한 것: 라운드별 Acceptance 판정 파싱 + OPEN_NEXT/BLOCKED/PENDING 게이트

사용: python -B scripts/brief_round_gate.py <codex|devin> <R1|R2|R3|R4> [--root .] [--report FILE] [--manifest JSON]
출력(한 줄):
  PENDING: <기대하는 REPORT 경로>          (REPORT 없음, 종료코드 2)
  OPEN_NEXT: <다음 카드 경로> | COMPLETE   (해당 Acceptance 전부 PASS/사유 있는 NOT_RUN, 종료코드 0)
  BLOCKED: <빠지거나 FAIL인 항목>          (종료코드 1)
판정 규칙: 항목 줄에 PASS → ok. NOT_RUN은 같은 줄에 사유(키워드 뒤 5자 이상)가 있어야 ok.
나머지(FAIL/PENDING/누락)는 BLOCKED.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from brief_lint import _write_utf8  # noqa: E402

DEFAULT_MANIFEST = Path("data/agent-handoff/p6-rounds/rounds.json")
STATUS_RE = re.compile(r"\b(PASS|FAIL|NOT_RUN|PENDING|BLOCKED|HOLD|NOT_REPRODUCED)\b")
ID_IN_LINE = re.compile(r"\b([A-Z]\d{1,2})\b")


def load_manifest(path: Path) -> dict:
    return json.loads(path.read_bytes().decode("utf-8-sig"))


def parse_verdicts(text: str, wanted: list[str]) -> dict[str, dict]:
    """각 Acceptance id의 상태를 모은다. 같은 id가 여러 줄에 있으면 가장 나쁜 상태를 택한다."""
    wanted_set = set(wanted)
    rank = {"PASS": 0, "NOT_RUN": 0, "PENDING": 1, "NOT_REPRODUCED": 1,
            "HOLD": 1, "BLOCKED": 1, "FAIL": 2}
    found: dict[str, dict] = {}
    for line in text.splitlines():
        ids = [m.group(1) for m in ID_IN_LINE.finditer(line) if m.group(1) in wanted_set]
        if not ids:
            continue
        status_m = STATUS_RE.search(line)
        status = status_m.group(1) if status_m else None
        tail = line[status_m.end():].strip(" .:|-—\t") if status_m else ""
        for aid in ids:
            cur = found.get(aid)
            if cur is None or rank.get(status, 1) > rank.get(cur["status"], 1):
                found[aid] = {"status": status, "reason": tail, "line": line.strip()[:160]}
    return found


def gate(agent: str, round_id: str, *, root: Path, manifest_path: Path | None,
         report_override: Path | None) -> tuple[str, int]:
    manifest_path = manifest_path or root / DEFAULT_MANIFEST
    if not manifest_path.is_file():
        return f"PENDING: manifest 없음 {manifest_path}", 2
    manifest = load_manifest(manifest_path)
    agents = manifest.get("agents", {})
    if agent not in agents:
        return f"PENDING: manifest에 agent '{agent}' 없음", 2
    spec = agents[agent]
    rounds = spec.get("rounds", {})
    if round_id not in rounds:
        return f"PENDING: manifest에 round '{round_id}' 없음", 2
    rspec = rounds[round_id]

    if report_override is not None:
        report = report_override
    else:
        report_dir = root / spec["reportDir"]
        report = report_dir / f"REPORT-{round_id}.md"
        if not report.is_file():
            alt = report_dir / "REPORT.md"
            report = alt if alt.is_file() else report
    if not report.is_file():
        return f"PENDING: {report}", 2

    wanted = rspec["acceptance"]
    verdicts = parse_verdicts(report.read_bytes().decode("utf-8-sig", errors="replace"), wanted)
    blocked = []
    for aid in wanted:
        v = verdicts.get(aid)
        if v is None:
            blocked.append(f"{aid}(누락)")
        elif v["status"] == "PASS":
            continue
        elif v["status"] == "NOT_RUN" and len(v["reason"]) >= 5:
            continue
        else:
            blocked.append(f"{aid}({v['status'] or '상태없음'})")
    if blocked:
        return "BLOCKED: " + ", ".join(blocked), 1

    nxt = rspec.get("next")
    if nxt and nxt in rounds:
        card = rounds[nxt].get("card", "")
        return f"OPEN_NEXT: {card}", 0
    return "OPEN_NEXT: COMPLETE (마지막 라운드)", 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("agent", choices=["codex", "devin"])
    parser.add_argument("round", help="R1..R4")
    parser.add_argument("--root", default=".")
    parser.add_argument("--report", help="REPORT 파일 경로 override (테스트용)")
    parser.add_argument("--manifest", help="rounds.json 경로 override (테스트용)")
    args = parser.parse_args(argv)
    message, code = gate(
        args.agent, args.round.upper(), root=Path(args.root),
        manifest_path=Path(args.manifest) if args.manifest else None,
        report_override=Path(args.report) if args.report else None,
    )
    _write_utf8(message + "\n")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
