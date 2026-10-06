#!/usr/bin/env python3
"""agy_skill_pack_check.py - agy Skill Pack 등급(S/M/L) 산출 검사기 (읽기 전용).

agy가 스킬/지시서를 낼 때 SKILL.md 한 장만 내는 얇은 산출을 막는다.
SSOT: docs/agents-rules/DEMO1-AGY-SKILL-PACK.md. 쓰기 0, 네트워크 0,
비밀값·지시서 본문 재출력 0.

사용:
  python -B scripts/agy_skill_pack_check.py --skill .agents/skills/<id>
      [--grade S|M|L] [--root <repo>] [--json] [--verbose]
  python -B scripts/agy_skill_pack_check.py --brief <PASTE 파일> [--json]

판정 (--skill, 폴더 기준; 기본 grade=M):
  S  SKILL.md frontmatter name==folder + description 비어있지 않음
  M  S + intent-index가 스킬을 라우팅(primary/optional) + companion >=1
     (references/ 파일 · scripts/x.py+test_x.py · docs/agents-rules/*.md)
     + 품질 소프트(body>=600B 필수, >=1500B 권장, 절차·검증·금지 키워드)
  L  M + 본문이 존재하는 docs/agents-rules/DEMO1-*.md 참조
     + ratchet 언급(INV-* 또는 configs/*ratchet*.json)

판정 (--brief):
  본문에 `.agents/skills/<id>` 경로가 있으면 SKILL_PACK 표 칸(스킬·companion
  경로·intent·검사 명령)의 존재를 검사. @skill 토큰 >=5개인데 primary
  불명이면 SKILL_SCATTER FAIL.

exit: 0 PASS / 1 WARN / 2 FAIL / 3 사용·IO 오류
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    from log_redact import redact_text
except ImportError:
    def redact_text(text):
        return text, {}

EXIT_PASS, EXIT_WARN, EXIT_FAIL, EXIT_IO = 0, 1, 2, 3
SCHEMA = "awx.agy-skill-pack-check.v1"
GRADES = ("S", "M", "L")

FM_RE = re.compile(r"\A---\s*\r?\n(.*?)\r?\n---\s*\r?\n", re.S)
SKILL_PATH_RE = re.compile(r"\.agents[/\\]skills[/\\]([A-Za-z0-9._-]+)")
AT_SKILL_RE = re.compile(r"@([A-Za-z][\w-]*)")
PRIMARY_HINT_RE = re.compile(r"(?i)primary|프라이머리|주\s*스킬|objective-executor|올라운더|기본\s*프리셋")
STEPS_RE = re.compile(r"(?i)(step|workflow|procedure|절차|순서|^\s*\d+\.)", re.M)
VERIFY_RE = re.compile(r"(?i)(verif|acceptance|check|검증|done when|pass)")
GUARD_RE = re.compile(r"(?i)(forbid|never|do not|don't|stop|금지|hold|ask)")
RULE_DOC_RE = re.compile(r"docs[/\\]agents-rules[/\\][\w.-]+\.md", re.I)
SCRIPT_RE = re.compile(r"scripts[/\\]([\w.-]+\.py)", re.I)
RATCHET_RE = re.compile(
    r"(?i)INV-[A-Za-z0-9-]+|configs[/\\][\w.-]*ratchet[\w.-]*\.json|behavior[-_]ratchet")
SKILL_PACK_MARK_RE = re.compile(r"(?i)SKILL_PACK|skill[\s_-]*pack|스킬\s*팩")
INTENT_HINT_RE = re.compile(r"(?i)skills-intent-index|intent\b|인텐트")
CHECK_CMD_RE = re.compile(r"agy_skill_pack_check")
COMPANION_HINT_RE = re.compile(
    r"(?i)references[/\\]|scripts[/\\][\w.-]+\.py|test_[\w.-]+\.py|"
    r"docs[/\\]agents-rules[/\\]")


def repo_root() -> Path:
    env = os.environ.get("AGY_PACK_ROOT", "").strip()
    return Path(env) if env else Path(__file__).resolve().parent.parent


def split_frontmatter(txt: str):
    m = FM_RE.match(txt)
    if not m:
        return None, txt
    return m.group(1), txt[m.end():]


def fm_field(fm: str, field: str) -> str:
    """frontmatter 스칼라 추출 — `field: value`, 인용, `>-`/`|` 블록 스칼라."""
    lines = fm.splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"^(\s*)" + re.escape(field) + r"\s*:\s*(.*)$", line)
        if not m:
            continue
        rest = m.group(2).strip()
        if rest and rest not in (">", ">-", ">+", "|", "|-", "|+"):
            if len(rest) >= 2 and rest[0] == rest[-1] and rest[0] in ("'", '"'):
                return rest[1:-1].strip()
            return rest.lstrip("'\"").strip()
        extra = []
        for cont in lines[i + 1:]:
            if cont.strip() and (cont.startswith(" ") or cont.startswith("\t")):
                extra.append(cont.strip())
            elif cont.strip():
                break
        return " ".join(extra).strip()
    return ""


def skill_id_of(skill_dir: Path) -> str:
    return skill_dir.name.strip()


def companions_of(root: Path, skill_dir: Path, body: str):
    """companion 산출 목록: 폴더 내 SKILL.md 외 파일, 본문이 참조하는
    존재하는 scripts/x.py+test_x.py 쌍, 존재하는 docs/agents-rules/*.md."""
    found = []
    for f in sorted(skill_dir.iterdir()):
        if f.is_file() and f.name != "SKILL.md":
            found.append(str(f.relative_to(skill_dir)).replace("\\", "/"))
        elif f.is_dir():
            found.append(f.name + "/")
    for m in SCRIPT_RE.finditer(body):
        script = "scripts/" + m.group(1)
        test = "scripts/test_" + m.group(1)
        pair = script + "+" + test
        if pair not in found and (root / script).is_file() \
                and (root / test).is_file():
            found.append(pair)
    for m in RULE_DOC_RE.finditer(body):
        rel = m.group(0).replace("\\", "/")
        if (root / rel).is_file() and rel not in found:
            found.append(rel)
    return found


def intent_status(root: Path, skill_id: str):
    """intent-index 라우팅 참조: primary/optional 필드면 routed,
    본문 어디든 이름만 있으면 referenced, 없으면 missing."""
    idx = root / ".agents" / "skills-intent-index.yaml"
    if not idx.is_file():
        return "missing", "intent-index absent"
    txt = idx.read_text(encoding="utf-8", errors="replace")
    routed = re.search(
        r"(?:primary_skill|optional_skill)\s*:\s*" + re.escape(skill_id) + r"\b",
        txt)
    if routed:
        return "routed", ""
    if skill_id in txt:
        return "referenced", "name present but not a primary/optional field"
    return "missing", ""


def check_skill(root: Path, skill_arg: str, grade: str):
    skill_dir = Path(skill_arg)
    if not skill_dir.is_absolute():
        skill_dir = (root / skill_arg).resolve()
    res = {"skill": str(skill_arg), "grade": grade,
           "fails": [], "warns": [], "companions": []}
    skill_md = skill_dir / "SKILL.md"
    if not skill_dir.is_dir() or not skill_md.is_file():
        res["fails"].append(f"SKILL.md not found under {skill_arg}")
        return res
    txt = skill_md.read_text(encoding="utf-8", errors="replace")
    fm, body = split_frontmatter(txt)
    sid = skill_id_of(skill_dir)
    if fm is None:
        res["fails"].append("no-frontmatter")
    else:
        name = fm_field(fm, "name")
        if name != sid and name.replace("_", "-") != sid:
            res["fails"].append(f"name!=folder({name or 'empty'})")
        if not fm_field(fm, "description"):
            res["fails"].append("desc-empty")
    if grade == "S":
        return res

    # ---- M 품질 소프트(600B 미만은 FAIL) ----
    bl = len(body.encode("utf-8"))
    if bl < 600:
        res["fails"].append(f"body{bl}B<600")
    elif bl < 1500:
        res["warns"].append(f"body{bl}B<1500")
    if not STEPS_RE.search(body):
        res["warns"].append("no-steps")
    if not VERIFY_RE.search(body):
        res["warns"].append("no-verify")
    if not GUARD_RE.search(body):
        res["warns"].append("no-guardrail")

    ist, inote = intent_status(root, sid)
    res["intent"] = ist
    if ist == "missing":
        res["fails"].append("intent-missing")
    elif ist == "referenced":
        res["warns"].append("intent-referenced-not-routed")

    comps = companions_of(root, skill_dir, body)
    res["companions"] = comps
    if not comps:
        res["fails"].append("companion-missing")

    if grade == "L":
        rules = [m.group(0).replace("\\", "/")
                 for m in RULE_DOC_RE.finditer(body)]
        rules = [r for r in rules if (root / r).is_file()
                 and "DEMO1-" in os.path.basename(r)]
        if not rules:
            res["fails"].append("rule-doc-missing(docs/agents-rules/DEMO1-*)")
        res["ruleDocs"] = rules
        if not RATCHET_RE.search(body) and not RATCHET_RE.search(txt):
            res["fails"].append("ratchet-missing(INV-*|configs/*ratchet*)")
    return res


def check_brief(root: Path, brief: Path):
    txt = brief.read_text(encoding="utf-8", errors="replace")
    res = {"brief": str(brief), "fails": [], "warns": []}
    tags = sorted(set(AT_SKILL_RE.findall(txt)))
    res["skillTags"] = len(tags)
    if len(tags) >= 5 and not PRIMARY_HINT_RE.search(txt):
        res["fails"].append("SKILL_SCATTER(@skill>=5 primary-unclear)")
        return res
    skills = sorted(set(m.group(1) for m in SKILL_PATH_RE.finditer(txt)))
    res["skillsPromised"] = skills
    if not skills:
        res["warns"].append("no-skill-path-detected")
        return res
    marks = {
        "packMark": bool(SKILL_PACK_MARK_RE.search(txt)),
        "companionPaths": bool(COMPANION_HINT_RE.search(txt)),
        "intent": bool(INTENT_HINT_RE.search(txt)),
        "checkCmd": bool(CHECK_CMD_RE.search(txt)),
    }
    res["packFields"] = marks
    missing = [k for k, v in marks.items() if not v]
    if len(missing) == len(marks):
        res["fails"].append("NO_SKILL_PACK(skills promised, no pack fields)")
    elif missing:
        res["warns"].append("pack-fields-missing:" + ",".join(missing))
    return res


def verdict_of(res) -> str:
    if res.get("fails"):
        return "FAIL"
    if res.get("warns"):
        return "WARN"
    return "PASS"


def emit(res) -> int:
    verdict = verdict_of(res)
    issues = res.get("fails", []) + res.get("warns", [])
    line2_bits = []
    if "grade" in res:
        line2_bits.append(f"grade={res['grade']}")
        line2_bits.append(f"companions={len(res.get('companions', []))}")
        line2_bits.append(f"intent={res.get('intent', 'n/a')}")
    else:
        line2_bits.append(f"skills={len(res.get('skillsPromised', []))}")
        line2_bits.append(f"@tags={res.get('skillTags', 0)}")
    action = ("ready" if verdict == "PASS" else
              "fix: " + "; ".join(issues[:4]))
    for ln in (f"VERDICT: {verdict}",
               "checks: " + " ".join(line2_bits),
               f"action: {action}"):
        txt, _ = redact_text(ln)
        print(txt)
    return {"PASS": EXIT_PASS, "WARN": EXIT_WARN, "FAIL": EXIT_FAIL}[verdict]


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(description="agy Skill Pack 산출 검사 (read-only)")
    ap.add_argument("--skill", default=None, help=".agents/skills/<id> 폴더")
    ap.add_argument("--brief", default=None, help="PASTE 지시서 파일")
    ap.add_argument("--grade", default="M", choices=GRADES)
    ap.add_argument("--root", default=None)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args(argv)
    if not args.skill and not args.brief:
        print("need --skill or --brief", file=sys.stderr)
        return EXIT_IO
    root = Path(args.root).resolve() if args.root else repo_root()
    if args.skill:
        res = check_skill(root, args.skill, args.grade)
    else:
        brief = Path(args.brief)
        if not brief.is_file():
            cand = root / args.brief
            if cand.is_file():
                brief = cand
            else:
                print(f"brief not found: {args.brief}", file=sys.stderr)
                return EXIT_IO
        res = check_brief(root, brief)
    res["verdict"] = verdict_of(res)
    if args.json:
        print(json.dumps({"schemaVersion": SCHEMA, **res},
                         ensure_ascii=True))
        return {"PASS": EXIT_PASS, "WARN": EXIT_WARN,
                "FAIL": EXIT_FAIL}[res["verdict"]]
    code = emit(res)
    if args.verbose:
        for k in ("fails", "warns"):
            for it in res.get(k, []):
                print(f"  {k[:-1]}: {it}")
        for c in res.get("companions", []):
            print(f"  companion: {c}")
    return code


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"internal error: {exc}", file=sys.stderr)
        sys.exit(EXIT_IO)
