#!/usr/bin/env python3
"""staged_method_check.py - STAGED_METHOD 6-gate heuristic check (read-only).

지시서(PASTE_*.md)나 짧은 작업 계획 텍스트가 STAGED_METHOD 6게이트 순서를
갖추었는지 검사한다. 네트워크 0, 쓰기 0, 본문은 판정·누락 게이트명 외 재출력 금지.

사용:
  python -B scripts/staged_method_check.py --brief <파일>
  python -B scripts/staged_method_check.py --text "<텍스트>" | --text -   # stdin
      [--json] [--verbose]

게이트(SSOT: docs/agents-rules/DEMO1-STAGED-METHOD.md):
  G1 목표 1개   : PASTE_ / 한 줄 목표 마커 >=1
  G2 스킬       : @skill / $demo1-* / demo1_vibe_skill_router / primary skill >=1
  G3 사실       : file:line 형태 경로:줄번호 또는 `확인 필요` >=1
  G4 작은 단계  : ### W# / 단계 / WP# / G# 마커 >=2
  G5 검증       : Acceptance/수용 표기 + PASS/NOT_RUN/HOLD/FAIL 판정 어휘
  G6 닫기      : HOLD 섹션 + (새 세션|SERIAL_LANE|닫|goal.switch|session_close)

판정: FAIL = SKILL_SCATTER(@skill 나열 >=5 + resolve/primary 근거 없음) 또는
      누락 게이트 >=3. WARN = 누락 1~2개. PASS = 전부 충족.
      resolve 근거가 있는 @나열은 routing 증거이므로 notes로만 남긴다
      (지시서 헤더의 규칙 언급 행은 실행자의 스킬 선택이 아니다).
exit: 0 PASS / 1 WARN / 2 FAIL / 3 사용·IO 오류
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

EXIT_PASS, EXIT_WARN, EXIT_FAIL, EXIT_IO = 0, 1, 2, 3

G1_GOAL = re.compile(r"PASTE_|한\s*줄\s*목표", re.I)
G2_SKILL = re.compile(
    r"@[\w][\w-]*|\$demo1-[\w-]+|demo1_vibe_skill_router|primary[_\s]skill|"
    r"스킬\s*resolve|skill\s*resolve", re.I)
G2_RESOLVE = re.compile(
    r"demo1_vibe_skill_router|primary[_\s]skill|스킬\s*resolve|skill\s*resolve|"
    r"\bresolve\b", re.I)
AT_MENTION = re.compile(r"@[\w][\w-]*")
G3_FACT = re.compile(
    r"[\w./\\-]+\.(?:py|java|md|ya?ml|json|js|ts|html|kts|bat|ps1|txt)\s*[:：]\s*\d+|"
    r"\bfile\s*:\s*line\b|확인\s*필요", re.I)
G4_STAGE = re.compile(r"#{1,6}\s*W\d+|\bWP\d+\b|\bG\d+\b|단계\s*\d+|작업\s*단계|단계", re.I)
G5_ACCEPT = re.compile(r"acceptance|수용\s*(조건|기준)?|완료\s*기준", re.I)
G5_VERDICT = re.compile(r"\b(PASS|FAIL|NOT_RUN|HOLD)\b", re.I)
G6_HOLD = re.compile(r"\bHOLD\b|중단\s*조건", re.I)
G6_NEXT = re.compile(
    r"새\s*세션|새\s*PASTE|새\s*목표|SERIAL_LANE|닫|goal[._\s-]*switch|session_close|"
    r"다음\s*(지시서|목표|PASTE)", re.I)


def check_text(text: str) -> dict:
    gates = {
        "G1": bool(G1_GOAL.search(text)),
        "G2": bool(G2_SKILL.search(text)),
        "G3": bool(G3_FACT.search(text)),
        "G4": len(G4_STAGE.findall(text)) >= 2,
        "G5": bool(G5_ACCEPT.search(text)) and bool(G5_VERDICT.search(text)),
        "G6": bool(G6_HOLD.search(text)) and bool(G6_NEXT.search(text)),
    }
    missing = [g for g, ok in gates.items() if not ok]
    mentions = sorted(set(AT_MENTION.findall(text)))
    resolved = bool(G2_RESOLVE.search(text))
    scatter = len(mentions) >= 5 and not resolved
    notes = []
    if len(mentions) >= 5 and resolved:
        notes.append(f"skill-mentions={len(mentions)} suppressed: resolve/primary 근거 존재")
    if scatter:
        verdict = "FAIL"
    elif len(missing) >= 3:
        verdict = "FAIL"
    elif missing:
        verdict = "WARN"
    else:
        verdict = "PASS"
    return {"verdict": verdict, "missing": missing, "gates": gates,
            "skillMentions": len(mentions), "skillScatter": scatter,
            "notes": notes}


def main(argv=None) -> int:
    for s in (sys.stdout, sys.stdin):
        try:
            s.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError, OSError):
            pass
    ap = argparse.ArgumentParser(
        description="STAGED_METHOD 6-gate brief/plan check (read-only)")
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--brief", help="지시서/계획 파일 경로")
    src.add_argument("--text", help="검사할 텍스트 ('-' = stdin)")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args(argv)

    if args.brief:
        p = Path(args.brief)
        if not p.is_file():
            print(f"brief not found: {p}", file=sys.stderr)
            return EXIT_IO
        text = p.read_text(encoding="utf-8", errors="replace")
        label = str(p)
    elif args.text == "-":
        text = sys.stdin.read()
        label = "<stdin>"
    else:
        text = args.text
        label = "<text>"

    result = check_text(text)
    if args.json:
        print(json.dumps({"schemaVersion": "awx.staged-method-check.v1",
                          "source": label, **result}, ensure_ascii=False))
    else:
        print(f"VERDICT: {result['verdict']}")
        line2 = ("missing: " + ",".join(result["missing"])) if result["missing"] \
            else "gates: G1-G6 present"
        if result["skillScatter"]:
            line2 += " | SKILL_SCATTER(@skill>=5, resolve 근거 없음)"
        print(line2)
        for n in result["notes"]:
            print(f"note: {n}")
        if args.verbose:
            for g, ok in result["gates"].items():
                print(f"  {g}: {'ok' if ok else 'MISSING'}")
    return {"PASS": EXIT_PASS, "WARN": EXIT_WARN, "FAIL": EXIT_FAIL}[result["verdict"]]


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"internal error: {exc}", file=sys.stderr)
        sys.exit(EXIT_IO)
