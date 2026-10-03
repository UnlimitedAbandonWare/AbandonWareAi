#!/usr/bin/env python3
"""brief_split_check.py — 지시서를 라운드 카드로 쪼갰을 때 작업 패키지·Acceptance 번호의 누락/중복 검사.

재사용한 기존 스크립트: brief_lint.py 의 _package_definitions/_acceptance_section/ACCEPTANCE_ITEM_RE
새로 추가한 것: 원본 대비 카드 집합의 패키지 id·acceptance id 정의 위치 1:1 대조

"정확히 한 번"의 정의: 각 id의 '정의(definition)'가 카드 집합 전체에서 정확히 1장에 존재.
  - 패키지 정의 = `### WP1`, `D1.` 같은 줄 시작부 정의 패턴 (brief_lint.PACKAGE_DEF_RE)
  - Acceptance 정의 = `- A1:` / `B1 ` 같은 항목 줄 (brief_lint.ACCEPTANCE_ITEM_RE)
본문 안의 단순 언급(예: 'D6의 코퍼스 참조')은 중복이 아니며 mentions로만 기록한다.

사용:
  python -B scripts/brief_split_check.py --source <원본.txt> --pkg WP --acc A --cards c1 c2 c3 ...
종료코드: 0=누락·중복 0, 1=누락·중복 있음, 2=입력 오류.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from brief_lint import (  # noqa: E402
    ACCEPTANCE_ITEM_RE,
    _acceptance_section,
    _package_definitions,
    _write_utf8,
)

DEF_SECTION_HEAD_RE = re.compile(r"^\s*#{1,6}\s")


def _collect_ids(path: Path, pkg_prefixes: tuple[str, ...], acc_prefixes: tuple[str, ...]) -> dict:
    text = path.read_bytes().decode("utf-8-sig", errors="replace")
    lines = text.splitlines()
    pkg_defs = {
        pid: locs
        for pid, locs in _package_definitions(lines).items()
        if any(pid.startswith(p) for p in pkg_prefixes)
    }
    acc_start, acc_lines = _acceptance_section(lines)
    scan_lines = acc_lines if acc_start >= 0 else lines
    acc_defs: dict[str, list[int]] = {}
    for off, line in enumerate(scan_lines):
        match = ACCEPTANCE_ITEM_RE.match(line)
        if match and any(match.group("id").startswith(p) for p in acc_prefixes):
            acc_defs.setdefault(match.group("id"), []).append(
                (acc_start + off + 1) if acc_start >= 0 else off + 1
            )
    pkg_any = re.compile(r"\b(" + "|".join(pkg_prefixes) + r")\d{1,2}\b")
    acc_any = re.compile(r"\b(" + "|".join(acc_prefixes) + r")\d{1,2}\b")
    mentions: dict[str, list[int]] = {}
    for idx, line in enumerate(lines, 1):
        for match in list(pkg_any.finditer(line)) + list(acc_any.finditer(line)):
            mentions.setdefault(match.group(0), []).append(idx)
    return {"pkg_defs": pkg_defs, "acc_defs": acc_defs, "mentions": mentions}


def check(source: Path, cards: list[Path], pkg_prefixes: tuple[str, ...], acc_prefixes: tuple[str, ...]) -> dict:
    src = _collect_ids(source, pkg_prefixes, acc_prefixes)
    expected_pkg = set(src["pkg_defs"])
    expected_acc = set(src["acc_defs"])

    per_card = []
    pkg_seen: dict[str, list[str]] = {}
    acc_seen: dict[str, list[str]] = {}
    mention_map: dict[str, list[str]] = {}
    for card in cards:
        got = _collect_ids(card, pkg_prefixes, acc_prefixes)
        card_name = card.name
        per_card.append({
            "card": str(card),
            "packages": sorted(got["pkg_defs"]),
            "acceptance": sorted(got["acc_defs"]),
        })
        for pid in got["pkg_defs"]:
            pkg_seen.setdefault(pid, []).append(card_name)
        for aid in got["acc_defs"]:
            acc_seen.setdefault(aid, []).append(card_name)
        for mid in got["mentions"]:
            if mid in expected_pkg or mid in expected_acc:
                if mid not in got["pkg_defs"] and mid not in got["acc_defs"]:
                    mention_map.setdefault(mid, []).append(card_name)

    missing_pkg = sorted(expected_pkg - set(pkg_seen))
    missing_acc = sorted(expected_acc - set(acc_seen))
    dup_pkg = sorted(pid for pid, homes in pkg_seen.items() if len(homes) > 1)
    dup_acc = sorted(aid for aid, homes in acc_seen.items() if len(homes) > 1)
    unexpected_pkg = sorted(set(pkg_seen) - expected_pkg)
    unexpected_acc = sorted(set(acc_seen) - expected_acc)

    ok = not (missing_pkg or missing_acc or dup_pkg or dup_acc or unexpected_pkg or unexpected_acc)
    return {
        "schema": "brief_split_check.v1",
        "source": str(source),
        "pass": ok,
        "packages": {
            "expected": sorted(expected_pkg),
            "missing": missing_pkg,
            "duplicates": {p: pkg_seen[p] for p in dup_pkg},
            "unexpected": unexpected_pkg,
        },
        "acceptance": {
            "expected": sorted(expected_acc),
            "missing": missing_acc,
            "duplicates": {a: acc_seen[a] for a in dup_acc},
            "unexpected": unexpected_acc,
        },
        "mentionsInOtherCards": {m: v for m, v in sorted(mention_map.items())},
        "cards": per_card,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--source", required=True)
    parser.add_argument("--pkg", nargs="+", default=["WP"], help="패키지 id 접두사(들)")
    parser.add_argument("--acc", nargs="+", default=["A"], help="Acceptance id 접두사(들)")
    parser.add_argument("--cards", nargs="+", required=True)
    args = parser.parse_args(argv)

    source = Path(args.source)
    cards = [Path(c) for c in args.cards]
    if not source.is_file() or any(not c.is_file() for c in cards):
        _write_utf8(json.dumps({"schema": "brief_split_check.v1", "pass": False,
                                "error": "input-missing"}, ensure_ascii=False) + "\n")
        return 2
    result = check(source, cards, tuple(args.pkg), tuple(args.acc))
    _write_utf8(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
