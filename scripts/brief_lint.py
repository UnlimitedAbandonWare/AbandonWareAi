#!/usr/bin/env python3
"""brief_lint.py — 지시서(brief) 린터. 너무 큰/불완전한 지시서가 에이전트에게 나가는 것을 막는다.

재사용한 기존 스크립트: validate_goal_directive_packets.py 의 비밀값 정규식(_SECRET_VALUE_RES, _SECRET_KEY_RE)
새로 추가한 것: 지시서 구조 검사(크기/패키지 수/ANTI-STOP/Acceptance/NOT_RUN/공통규칙 블록/@스킬 줄/비밀 문자열)

사용: python -B scripts/brief_lint.py <지시서.txt> [<지시서2.txt> ...]
출력: JSON (항목별 PASS/WARN/FAIL + 줄 번호). 종료코드 0=FAIL 없음, 1=FAIL 있음, 2=입력 없음/오류.
비밀값은 절대 출력하지 않는다(줄 번호와 패턴 이름만).
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
# 키 '이름' 패턴(_SECRET_KEY_RE)은 지시서에서 apikey.ps1 같은 파일명 언급을 오탐하므로 쓰지 않고,
# 실제 토큰 '값' 형태(_SECRET_VALUE_RES)만 검사한다.
from validate_goal_directive_packets import _SECRET_VALUE_RES  # noqa: E402

SIZE_WARN_BYTES = 8 * 1024
PACKAGE_WARN_COUNT = 5
SKILL_LINE_WINDOW = 8

# 작업 패키지 "정의" 패턴: '### WP1', '## WP1.', 'D1.', '- WP2 [', '**WP3**' 등 줄 시작부에 번호가 오는 경우
PACKAGE_DEF_RE = re.compile(
    r"^\s*(?:#{1,6}\s*|\-\s*|\*\s*)?\**(?P<id>(?:WP|D|H|S)\d{1,2})\b[\s.\]:—\[-]",
)
PACKAGE_ANY_RE = re.compile(r"\b(?P<id>(?:WP|D|H|S)\d{1,2})\b")

ACCEPTANCE_HEAD_RE = re.compile(r"acceptance|완료\s*기준|수용\s*기준", re.IGNORECASE)
ACCEPTANCE_ITEM_RE = re.compile(r"^\s*(?:[-*]\s*)?(?:\*\*)?(?P<id>[A-Z]{1,3}\d{1,2})\b[\s.:)]")

COMMON_RULE_SIGNATURES = (
    re.compile(r"lease|리스", re.IGNORECASE),
    re.compile(r"git\s+(push|pull|add|commit|reset|clean)", re.IGNORECASE),
    re.compile(r"\.secrets|\.env\b|비밀|토큰", re.IGNORECASE),
    re.compile(r"NOT_RUN"),
    re.compile(r"gradle", re.IGNORECASE),
    re.compile(r"실제\s*(외부|호출)|실호출|유료", re.IGNORECASE),
    re.compile(r"(npm|pip)\s*패키지|패키지\s*설치", re.IGNORECASE),
)
COMMON_RULE_MIN_HITS = 4
COMMON_RULE_SECTION_RE = re.compile(
    r"^\s*#{1,6}\s*\d*\.?\s*(공통\s*규칙|진행\s*규칙|규칙|하지\s*않음|절대\s*금지|금지)", re.IGNORECASE
)

SKILL_LINE_RE = re.compile(r"^\s*@[\w-]+(\s+@[\w-]+)*\s*$")
SKILL_PATH_RE = re.compile(r"\.agents[\\/]skills[\\/]([\w-]+)[\\/]SKILL\.md")
DEVIN_BRIEF_RE = re.compile(r"devin", re.IGNORECASE)

SECRET_PATTERNS = tuple(
    (f"secret-value-{idx}", pattern) for idx, pattern in enumerate(_SECRET_VALUE_RES, 1)
)


def _finding(check_id: str, severity: str, message: str, lines: list[int] | None = None) -> dict:
    return {
        "id": check_id,
        "severity": severity,
        "lines": sorted(set(lines or [])),
        "message": message,
    }


def _package_definitions(lines: list[str]) -> dict[str, list[int]]:
    found: dict[str, list[int]] = {}
    for idx, line in enumerate(lines, 1):
        match = PACKAGE_DEF_RE.match(line)
        if match:
            found.setdefault(match.group("id"), []).append(idx)
    return found


def _acceptance_section(lines: list[str]) -> tuple[int, list[str]]:
    """Acceptance/완료기준 헤딩 이후의 줄들을 돌려준다. 없으면 (-1, [])."""
    for idx, line in enumerate(lines):
        if ACCEPTANCE_HEAD_RE.search(line) and line.lstrip().startswith(("#", "-", "완", "수")):
            return idx + 1, lines[idx + 1 :]
    for idx, line in enumerate(lines):
        if ACCEPTANCE_HEAD_RE.search(line):
            return idx + 1, lines[idx + 1 :]
    return -1, []


def lint_text(text: str, *, name: str = "<stdin>") -> dict:
    lines = text.splitlines()
    raw = text.encode("utf-8", errors="replace")
    findings: list[dict] = []

    # 1. 크기
    if len(raw) > SIZE_WARN_BYTES:
        findings.append(_finding(
            "size-over-8kb", "WARN",
            f"지시서가 {len(raw)}B(>{SIZE_WARN_BYTES}B)다. 라운드 카드로 나누는 것을 검토하라.",
        ))

    # 2. 작업 패키지 수
    packages = _package_definitions(lines)
    if not packages:
        # 정의형 패턴이 없으면 전체 언급으로 대체
        for idx, line in enumerate(lines, 1):
            for match in PACKAGE_ANY_RE.finditer(line):
                packages.setdefault(match.group("id"), []).append(idx)
    if len(packages) >= PACKAGE_WARN_COUNT:
        findings.append(_finding(
            "package-count", "WARN",
            f"작업 패키지가 {len(packages)}개(>={PACKAGE_WARN_COUNT}): {', '.join(sorted(packages))}. 라운드로 쪼갤 것.",
            sorted({ln for locs in packages.values() for ln in locs}),
        ))

    # 3. [ANTI-STOP]
    anti_stop_lines = [i for i, line in enumerate(lines, 1) if "[ANTI-STOP]" in line]
    if not anti_stop_lines:
        findings.append(_finding(
            "anti-stop-missing", "FAIL",
            "[ANTI-STOP] 블록이 없다. 에이전트가 읽기만 하고 멈출 수 있다. 끝에 [ANTI-STOP] 조건을 추가하라.",
        ))

    # 4. Acceptance 존재
    acc_start, acc_lines = _acceptance_section(lines)
    if acc_start < 0:
        findings.append(_finding(
            "acceptance-missing", "FAIL",
            "Acceptance/완료 기준 섹션이 없다. PASS/NOT_RUN 판정 기준을 적어라.",
        ))

    # 5. Acceptance가 없는 작업 패키지
    if acc_start >= 0 and packages:
        acc_text_lines = [ln for ln in acc_lines]
        acc_text = "\n".join(acc_text_lines)
        acc_items = [
            m.group("id")
            for ln in acc_text_lines
            for m in [ACCEPTANCE_ITEM_RE.match(ln)]
            if m
        ]
        uncovered = []
        untraceable = []
        for pkg_id, def_lines in sorted(packages.items()):
            if re.search(rf"\b{re.escape(pkg_id)}\b", acc_text):
                continue
            if acc_items and len(acc_items) >= len(packages):
                untraceable.append(pkg_id)  # 개수상 커버되지만 id 추적 불가
            else:
                uncovered.append(pkg_id)
        if uncovered:
            findings.append(_finding(
                "package-without-acceptance", "FAIL",
                f"Acceptance에 대응 항목이 없는 작업 패키지: {', '.join(uncovered)}",
                sorted({ln for p in uncovered for ln in packages[p]}),
            ))
        elif untraceable:
            findings.append(_finding(
                "package-acceptance-untraceable", "WARN",
                f"Acceptance 항목 수는 충분하나 패키지 id가 인용되지 않음: {', '.join(untraceable)}. 추적 가능하도록 id를 적을 것.",
            ))

    # 6. NOT_RUN 언급
    if not any("NOT_RUN" in line for line in lines):
        findings.append(_finding(
            "not-run-missing", "WARN",
            "NOT_RUN 규칙이 없다. '실행 못 한 항목은 NOT_RUN + 이유, PASS로 바꾸지 않는다'를 추가하라.",
        ))

    # 7. 공통 규칙 블록 통째 복사
    section_start = None
    for idx, line in enumerate(lines):
        if COMMON_RULE_SECTION_RE.match(line):
            section_start = idx
            break
    if section_start is not None:
        hits = []
        for idx, line in enumerate(lines[section_start:], section_start + 1):
            if line.lstrip().startswith("#") and idx != section_start + 1 and hits:
                break
            if any(sig.search(line) for sig in COMMON_RULE_SIGNATURES):
                hits.append(idx)
        if len(hits) >= COMMON_RULE_MIN_HITS:
            findings.append(_finding(
                "common-rules-inline", "WARN",
                f"공통 규칙 블록을 통째로 복사한 것으로 보임({len(hits)}줄). "
                "'COMMON_RULES: .agents/rules/demo1-common-brief-rules.md 적용' 한 줄로 바꿀 것.",
                hits,
            ))

    # 8. Devin 지시서인데 스킬 라우팅 표기 없음 — 첫 @스킬 줄 또는 본문의
    #    명시 `.agents/skills/<name>/SKILL.md` 경로 참조 둘 다 인정한다.
    is_devin = bool(DEVIN_BRIEF_RE.search(name)) or any(
        DEVIN_BRIEF_RE.search(line) for line in lines[:3]
    )
    if is_devin:
        head = lines[:SKILL_LINE_WINDOW]
        has_route = any(SKILL_LINE_RE.match(line) for line in head) or bool(
            SKILL_PATH_RE.search(text)
        )
        if not has_route:
            findings.append(_finding(
                "devin-skill-line-missing", "FAIL",
                f"Devin 지시서인데 스킬 라우팅 표기가 없다. 첫 {SKILL_LINE_WINDOW}줄 안의 @스킬 줄이나 "
                "본문의 `.agents/skills/<name>/SKILL.md` 경로 참조를 추가하라.",
                list(range(1, min(len(lines), SKILL_LINE_WINDOW) + 1)),
            ))

    # 9. 실제 키·토큰 형태 문자열 (값은 출력하지 않음)
    secret_hits = []
    for idx, line in enumerate(lines, 1):
        for pat_name, pattern in SECRET_PATTERNS:
            if pattern.search(line):
                secret_hits.append((idx, pat_name))
                break
    if secret_hits:
        names = sorted({n for _, n in secret_hits})
        findings.append(_finding(
            "secret-like-string", "FAIL",
            f"키·토큰처럼 보이는 문자열 {len(secret_hits)}건({', '.join(names)}). 값은 출력하지 않음. 지시서에서 제거하라.",
            [ln for ln, _ in secret_hits],
        ))

    severity_rank = {"PASS": 0, "WARN": 1, "FAIL": 2}
    worst = max((severity_rank[f["severity"]] for f in findings), default=0)
    verdict = {0: "PASS", 1: "WARN", 2: "FAIL"}[worst]
    return {
        "schema": "brief_lint.v1",
        "file": name,
        "bytes": len(raw),
        "verdict": verdict,
        "summary": {
            "fail": sum(1 for f in findings if f["severity"] == "FAIL"),
            "warn": sum(1 for f in findings if f["severity"] == "WARN"),
            "checks": 9,
        },
        "packages": sorted(packages),
        "findings": findings,
    }


def lint_file(path: Path) -> dict:
    raw = path.read_bytes()
    text = raw.decode("utf-8-sig", errors="replace")
    return lint_text(text, name=str(path))


def _write_utf8(text: str) -> None:
    """콘솔/파일 리다이렉트와 무관하게 UTF-8 바이트로 출력한다."""
    sys.stdout.buffer.write(text.encode("utf-8"))
    sys.stdout.buffer.flush()


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if not args or args[0] in ("-h", "--help"):
        _write_utf8(__doc__ + "\n")
        return 0 if args else 2
    results = []
    for name in args:
        path = Path(name)
        if not path.is_file():
            results.append({"file": name, "verdict": "FAIL",
                            "findings": [_finding("input-missing", "FAIL", "파일이 없다")]})
            continue
        try:
            results.append(lint_file(path))
        except OSError as exc:
            results.append({"file": name, "verdict": "FAIL",
                            "findings": [_finding("input-unreadable", "FAIL", f"읽기 실패: {exc}")]})
    payload = results[0] if len(results) == 1 else {"schema": "brief_lint.v1.batch", "results": results}
    _write_utf8(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    all_results = results
    if any(r.get("verdict") == "FAIL" for r in all_results):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
