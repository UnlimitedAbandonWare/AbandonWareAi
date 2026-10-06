#!/usr/bin/env python3
"""dot_brief_check.py - dot 지시서(PASTE_*.md) 유효성 프리플라이트 (읽기 전용).

Codex에 붙여 넣기 전, 지시서가 live repo와 맞는지 1초 검사한다.
쓰기 0, 네트워크 0, 지시서 본문은 검사 외 용도로 재출력하지 않는다.

사용:
  python -B scripts/dot_brief_check.py --brief <지시서파일> [--root <repo>]
      [--no-lease] [--json] [--verbose]

검사:
  1) TARGETS_EXIST  언급된 repo 경로·FQCN·*.java 파일명이 live tree에 존재하는지.
     같은 줄에 신규 생성 마커(신규|new file|create|생성|추가 예정)가 있으면
     미존재를 결함이 아니라 informational로 분류한다.
  2) SECTIONS       hard: 한 줄 목표/HOLD/수용(=Acceptance);
                    soft: 사실/공통 규칙/작업 항목/스킬(@$skill)/단계(W#·단계·WP#).
  3) LEASE          추출된 대상이 foreign live lease와 겹치는지
                    (scripts/lease_conflict_autoflow.py scan; --no-lease 시 skipped).
  4) ANTI_PATTERNS  git push/commit -a/add -A/reset --hard, 비밀값 노출 패턴,
                    무조건 전체 원복 유도.

판정: FAIL = 핵심 섹션(목표/HOLD/수용) 결손, 미존재 대상, 하드 안티패턴.
      WARN = 부속 섹션 결손, lease-blocked 대상, 추출 대상 0건, 소프트 안티패턴.
출력 3줄: VERDICT / checks 요약 / action 권고.
exit: 0 PASS / 1 WARN / 2 FAIL / 3 사용·IO 오류
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    from log_redact import redact_text
except ImportError:
    def redact_text(text):
        return text, {}

EXIT_PASS, EXIT_WARN, EXIT_FAIL, EXIT_IO = 0, 1, 2, 3

REQUIRED_HARD = {
    "goal": re.compile(r"한\s*줄\s*목표|##?\s*goal\b|목표\s*[:：]", re.I),
    "hold": re.compile(r"\bHOLD\b|중단\s*조건", re.I),
    "acceptance": re.compile(r"수용\s*조건|acceptance|완료\s*기준", re.I),
}
REQUIRED_SOFT = {
    "facts": re.compile(r"사실|##?\s*facts", re.I),
    "common_rules": re.compile(r"공통\s*규칙|COMMON_RULES", re.I),
    "work_items": re.compile(r"작업\s*항목|##?\s*work|WP\d|DV\d|task\s*item", re.I),
    # STAGED_METHOD soft nudges (2026-10-05): 스킬 resolve 흔적 + 단계 마커.
    "skill": re.compile(r"@[\w][\w-]*|\$demo1-[\w-]+|vibe_skill_router", re.I),
    "stages": re.compile(r"###\s*W\d+|작업\s*단계|단계\s*\d|\bWP\d+\b", re.I),
}
HARD_ANTI = [
    ("git-push", re.compile(r"\bgit\s+push\b", re.I)),
    ("git-commit", re.compile(r"\bgit\s+commit\b|\bcommit\s+-a\b", re.I)),
    ("git-add-all", re.compile(r"\bgit\s+add\s+(-A|\.)\b|\badd\s+-A\b", re.I)),
    ("git-reset-hard", re.compile(r"\bgit\s+(reset|clean|checkout|restore|rebase|merge|pull|fetch)\b", re.I)),
    ("secret-exposure", re.compile(r"\.secrets\b|api[_-]?key\s*[:=]\s*\S+|password\s*[:=]\s*\S+|BEGIN [A-Z ]*PRIVATE KEY", re.I)),
]
SOFT_ANTI = [
    ("revert-all", re.compile(r"전체\s*원복|전부\s*되돌|revert\s+all", re.I)),
]
CREATION_HINT = re.compile(
    r"신규|new\s*file|create|생성|추가|기록|작성|쓰기|write|output|new\b", re.I)
# 금지/부정 문맥에서 나온 금지 명령 언급은 위반이 아니다.
NEGATION = re.compile(
    r"금지|금단|never\b|do\s*not\b|don't\b|하지\s*마|하지마|절대|forbidden|"
    r"prohibit|금지한다|못\s*한다|불가|중단", re.I)
HEADING_RE = re.compile(r"^\s*#{1,6}\s|^\s*\d+\.\s*[A-Z가-힣]")
FQCN_RE = re.compile(r"\bcom\.example\.[\w.]+[A-Z]\w*\b")
PATH_RE = re.compile(
    r"\b((?:main/java|main/resources|src/test/java|src/test/resources|scripts|"
    r"docs|configs|static|\.agents|__patch_drop__|data|var)/[\w.\-/\\]+)")
JAVA_NAME_RE = re.compile(r"\b([A-Z]\w{2,})\.java\b")
TRAIL = ".,;:)`\"'`}]>"


def repo_root() -> Path:
    env = os.environ.get("DOT_BRIEF_ROOT", "").strip()
    return Path(env) if env else Path(__file__).resolve().parent.parent


def norm_rel(p: str) -> str:
    return p.replace("\\", "/").strip().rstrip(TRAIL)


def fqcn_candidates(fqcn: str):
    rel = fqcn.replace(".", "/") + ".java"
    return ["main/java/" + rel, "src/test/java/" + rel]


def extract_targets(text: str):
    """반환: [(kind, display, [candidate rel paths], creation_hint_bool, line_no)]"""
    targets = []
    lines = text.splitlines()
    seen = set()

    def add(kind, disp, cands, line):
        key = (kind, tuple(sorted(cands)))
        if key in seen:
            return
        seen.add(key)
        targets.append({"kind": kind, "display": disp, "candidates": cands,
                        "creationHint": bool(CREATION_HINT.search(line)),
                        "line": None})

    for i, line in enumerate(lines, 1):
        for m in FQCN_RE.finditer(line):
            fq = m.group(0)
            if fq.count(".") >= 2:
                t = {"kind": "fqcn", "display": fq,
                     "candidates": fqcn_candidates(fq),
                     "creationHint": bool(CREATION_HINT.search(line)),
                     "line": i}
                key = ("fqcn", fq)
                if key not in seen:
                    seen.add(key)
                    targets.append(t)
        for m in PATH_RE.finditer(line):
            rel = norm_rel(m.group(1))
            if len(rel) < 8:
                continue
            key = ("path", rel)
            if key not in seen:
                seen.add(key)
                targets.append({"kind": "path", "display": rel,
                                "candidates": [rel],
                                "creationHint": bool(CREATION_HINT.search(line)),
                                "line": i})
        for m in JAVA_NAME_RE.finditer(line):
            name = m.group(1) + ".java"
            key = ("java", name)
            if key not in seen:
                seen.add(key)
                targets.append({"kind": "java-name", "display": name,
                                "candidates": [],
                                "creationHint": bool(CREATION_HINT.search(line)),
                                "line": i})
    return targets


def resolve_java_names(root: Path, targets):
    """java-name 대상은 main/java·src/test/java 하위에서 basename 검색."""
    need = {t["display"] for t in targets if t["kind"] == "java-name"}
    if not need:
        return
    found = {}
    for base in ("main/java", "src/test/java"):
        bdir = root / base
        if not bdir.is_dir():
            continue
        for dp, _dn, fns in os.walk(bdir):
            for fn in fns:
                if fn in need:
                    found.setdefault(fn, []).append(
                        str(Path(dp, fn).relative_to(root)).replace("\\", "/"))
    for t in targets:
        if t["kind"] == "java-name":
            t["candidates"] = found.get(t["display"], [])


def lease_scan(root: Path, paths):
    if not paths:
        return {"status": "skipped", "reason": "no-targets", "blocked": []}
    tool = root / "scripts" / "lease_conflict_autoflow.py"
    if not tool.is_file():
        return {"status": "skipped", "reason": "tool-missing", "blocked": []}
    try:
        proc = subprocess.run(
            [sys.executable, "-B", str(tool), "scan", "--targets", *paths],
            capture_output=True, text=True, cwd=str(root), timeout=60)
        data = json.loads(proc.stdout or "{}")
    except (OSError, ValueError, subprocess.SubprocessError):
        return {"status": "skipped", "reason": "scan-error", "blocked": []}
    blocked = data.get("blockedTargets") or []
    if not blocked:
        blocked = sorted({t for r in data.get("overlappingLeases", [])
                          for t in r.get("overlappingTargets", [])})
    return {"status": "ok",
            "blocked": blocked,
            "leaseCount": len(data.get("leases", []))}


def check_brief(text: str, root: Path, do_lease: bool):
    hard_missing = [k for k, rx in REQUIRED_HARD.items() if not rx.search(text)]
    soft_missing = [k for k, rx in REQUIRED_SOFT.items() if not rx.search(text)]
    targets = extract_targets(text)
    resolve_java_names(root, targets)
    missing, existing, informational = [], [], []
    for t in targets:
        exists = any((root / c).is_file() for c in t["candidates"])
        rec = {"target": t["display"], "kind": t["kind"], "line": t["line"],
               "exists": exists, "creationHint": t["creationHint"]}
        if exists:
            existing.append(rec)
        elif t["creationHint"]:
            informational.append(rec)
        else:
            missing.append(rec)
    hard_anti, soft_anti = [], []
    forbidden_zone = False
    for ln in text.splitlines():
        if HEADING_RE.match(ln):
            forbidden_zone = bool(NEGATION.search(ln))
        if forbidden_zone or NEGATION.search(ln):
            continue
        for name, rx in HARD_ANTI:
            if rx.search(ln) and name not in hard_anti:
                hard_anti.append(name)
        for name, rx in SOFT_ANTI:
            if rx.search(ln) and name not in soft_anti:
                soft_anti.append(name)
    lease = {"status": "skipped", "reason": "disabled", "blocked": []}
    if do_lease:
        paths = sorted({c for t in targets for c in t["candidates"]
                        if (root / c).is_file()} |
                       {t["display"] for t in targets
                        if t["kind"] == "path" and (root / t["display"]).is_file()})
        lease = lease_scan(root, paths)
    verdict = "PASS"
    if hard_missing or missing or hard_anti:
        verdict = "FAIL"
    elif soft_missing or lease.get("blocked") or soft_anti or not targets:
        verdict = "WARN"
    actions = []
    if hard_missing:
        actions.append("필수 섹션 보강:" + ",".join(hard_missing))
    if missing:
        actions.append(f"미존재 대상 {len(missing)}건 경로/FQCN 확인")
    if hard_anti:
        actions.append("금지 명령 제거:" + ",".join(hard_anti))
    if lease.get("blocked"):
        actions.append(f"lease 충돌 {len(lease['blocked'])}건 — 소유 세션 종료 후 적용")
    if soft_missing:
        actions.append("권장 섹션 보강:" + ",".join(soft_missing))
    if not actions:
        actions.append("ready — Codex/실행 세션에 붙여넣기 가능")
    return {
        "verdict": verdict,
        "sections": {"hard_missing": hard_missing, "soft_missing": soft_missing},
        "targets": {"total": len(targets), "existing": len(existing),
                    "missing": missing, "informational": informational},
        "lease": lease,
        "antiPatterns": {"hard": hard_anti, "soft": soft_anti},
        "action": " | ".join(actions),
    }


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(description="dot brief preflight validity check (read-only)")
    ap.add_argument("--brief", required=True, help="지시서 파일 경로")
    ap.add_argument("--root", default=None, help="repo root (기본: 이 체크아웃)")
    ap.add_argument("--no-lease", action="store_true", help="lease 스캔 생략")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args(argv)

    brief = Path(args.brief)
    if not brief.is_file():
        print(f"brief not found: {brief}", file=sys.stderr)
        return EXIT_IO
    root = Path(args.root).resolve() if args.root else repo_root()
    text = brief.read_text(encoding="utf-8", errors="replace")
    result = check_brief(text, root, not args.no_lease)

    if args.json:
        print(json.dumps({"schemaVersion": "awx.dot-brief-check.v1",
                          "brief": str(brief), **result}, ensure_ascii=True))
    else:
        t = result["targets"]
        sec = result["sections"]
        lease = result["lease"]
        lease_s = ("skipped" if lease["status"] == "skipped"
                   else ("clear" if not lease["blocked"]
                         else f"blocked:{len(lease['blocked'])}"))
        hard_n = len(REQUIRED_HARD) - len(sec["hard_missing"])
        soft_n = len(REQUIRED_SOFT) - len(sec["soft_missing"])
        anti_n = len(result["antiPatterns"]["hard"]) + len(result["antiPatterns"]["soft"])
        line1 = f"VERDICT: {result['verdict']}"
        line2 = (f"checks: targets={t['existing']}/{t['total']} "
                 f"sections={hard_n + soft_n}/{len(REQUIRED_HARD) + len(REQUIRED_SOFT)} "
                 f"lease={lease_s} antiPatterns={anti_n}")
        line3 = f"action: {result['action']}"
        for ln in (line1, line2, line3):
            txt, _ = redact_text(ln)
            print(txt)
        if args.verbose:
            for m in t["missing"]:
                print(f"  missing: {m['kind']} {m['target']} (line {m['line']})")
            for b in lease.get("blocked", []):
                print(f"  lease-blocked: {b}")
    return {"PASS": EXIT_PASS, "WARN": EXIT_WARN, "FAIL": EXIT_FAIL}[result["verdict"]]


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"internal error: {exc}", file=sys.stderr)
        sys.exit(EXIT_IO)
