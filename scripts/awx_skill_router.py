#!/usr/bin/env python3
"""awx_skill_router.py — resumed 0915a0: keyword routing + index lint +
Korean routing regression set for .agents/skills-intent-index.yaml.

Routing itself is delegated to demo1_vibe_skill_router.py (the live SSOT
reader); this tool adds the quality gates the weekly directive asked for
(.devin/PROMPTS/codex-weekly-skill-directive-20260924.md P4):

  resolve "<text>"   one primary skill JSON (delegates to vibe router)
  lint               schema/duplicates/broken skill refs (errors) +
                     unindexed-on-disk skills (warnings)
  regression         built-in Korean/English routing regression cases;
                     asserts expected primary skill per case
  exit codes         0 clean/pass, 4 lint errors or regression failures,
                     2 usage/io error
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

SCRIPTS = Path(__file__).resolve().parent
ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS))
import demo1_vibe_skill_router as vibe  # noqa: E402

SCHEMA = "awx.skill-router.v1"
SKILLS_DIR = ".agents/skills"

# (name, user text, expected primary skill or None-for-fallback,
#  expected intent or None-any)
REGRESSION = [
    ("lens-caption-ko", "안경 렌즈 캡션 표시 시간을 바꿔줘",
     "demo1-meta-display-simple-caption", "meta-display"),
    ("nova-focus-ko", "노바 집중 대화 모드를 구현해줘",
     "demo1-nova-focus", "nova-focus"),
    ("db-export-ko", "대화 db export 해줘",
     "demo1-meta-display-db-export", "db-export"),
    ("safe-cleanup-ko", "디스크 잔여물 정리해줘",
     "demo1-safe-cleanup", "safe-cleanup"),
    ("source-write-ko", "이 코드 고쳐줘", "demo1-work-ledger", "source-write"),
    ("rag-search-ko", "rag 검색 품질을 개선해줘",
     "demo1-rag-platform", "rag-search"),
    ("llm-provider-ko", "ollama 모델 라우팅을 바꿔줘",
     "demo1-api-routing-inventory", "llm-provider"),
    ("debug-symptom-ko", "버그가 나요 에러 디버그해줘",
     "demo1-evidence-debugging", "debug-symptom"),
    ("patchdrop-explicit", "patchdrop 번들을 apply 해줘",
     "patchdrop-safe-patch-orchestrator", "patchdrop-apply"),
    ("brief-orchestrate", "devin brief 브리프를 orchestrate 해줘",
     "demo1-devin-source-orchestrator", "multi-seam-brief"),
    ("handoff-reply-ko", "이 보고서에 지시서 작성해줘",
     "demo1-devin-directive-loop", "handoff-reply"),
    ("change-plane", "병렬 세션 changeintent 등록",
     "demo1-agent-change-plane", "change-plane"),
    ("gated-default-off",
     "그냥 소스 패치해줘", "demo1-work-ledger", "source-write"),
]


def _load(args):
    return vibe.load_index(args.root, args.index)


def cmd_resolve(args) -> tuple[dict, int]:
    try:
        index = _load(args)
        result = vibe.resolve(index, args.text or "", args.root)
    except (OSError, ValueError, KeyError, TypeError) as error:
        return {"schemaVersion": SCHEMA, "status": "error",
                "reason": str(error) or "index-load-failed"}, 2
    return result, 0 if result.get("status") != "error" else 2


def _disk_skills(root: Path) -> set:
    base = Path(root) / SKILLS_DIR
    if not base.is_dir():
        return set()
    return {p.name for p in base.iterdir()
            if p.is_dir() and (p / "SKILL.md").is_file()}


def lint(index: dict, root: Path) -> dict:
    errors, warnings = [], []

    if not isinstance(index, dict):
        return {"ok": False, "errors": ["index-not-a-map"], "warnings": []}
    if "schemaVersion" not in index:
        errors.append("schemaVersion-missing")
    intents = index.get("intents")
    families = index.get("families") or {}
    defaults = index.get("default_forbid_families") or []
    if not isinstance(intents, list):
        errors.append("intents-not-a-list")
        intents = []
    if not isinstance(families, dict):
        errors.append("families-not-a-map")
        families = {}

    seen_intents, referenced = set(), set()
    for i, entry in enumerate(intents):
        if not isinstance(entry, dict):
            errors.append(f"intent[{i}]-not-a-map")
            continue
        name = entry.get("intent")
        if not name:
            errors.append(f"intent[{i}]-missing-id")
        elif name in seen_intents:
            errors.append(f"duplicate-intent:{name}")
        else:
            seen_intents.add(name)
        primary = entry.get("primary_skill")
        optional = entry.get("optional_skill")
        if not primary:
            errors.append(f"intent[{name}]-missing-primary")
        for skill in (primary, optional):
            if not skill:
                continue
            referenced.add(skill)
            final = vibe._follow_redirects(root, skill, {})
            if not vibe._skill_exists(root, final):
                errors.append(f"broken-skill-ref:{skill}")
        if optional and optional == primary:
            warnings.append(f"intent[{name}]-optional-equals-primary")
        for fam in entry.get("forbid_families") or []:
            if fam not in families:
                errors.append(f"intent[{name}]-unknown-family:{fam}")
        for pat in entry.get("match") or []:
            if isinstance(pat, str) and pat.startswith("re:"):
                try:
                    re.compile(pat[3:])
                except re.error as error:
                    errors.append(f"intent[{name}]-bad-regex:{pat}:{error}")

    for fam in defaults:
        if fam not in families:
            errors.append(f"default-forbid-unknown-family:{fam}")
    for fam, spec in families.items():
        for skill in (spec or {}).get("skills") or []:
            referenced.add(skill)
            if not vibe._skill_exists(root, skill):
                warnings.append(f"family[{fam}]-missing-skill:{skill}")

    fallback = index.get("fallback")
    if not isinstance(fallback, dict) or "primary_skill" not in fallback:
        warnings.append("fallback-missing-or-malformed")
    elif fallback.get("primary_skill"):
        referenced.add(fallback["primary_skill"])

    direct_only = {str(n).split("#")[0].split()[0]
                   for n in (index.get("direct_call_only") or [])
                   if isinstance(n, str)}
    unindexed = sorted(_disk_skills(root) - referenced - direct_only)
    if unindexed:
        warnings.append(f"unindexed-skills:{len(unindexed)}:" +
                        ",".join(unindexed))
    return {
        "ok": not errors,
        "errors": errors,
        "warnings": warnings,
        "counts": {"intents": len(intents), "families": len(families),
                   "referencedSkills": len(referenced),
                   "directCallOnly": len(direct_only & _disk_skills(root)),
                   "unindexed": len(unindexed)},
    }


def cmd_lint(args) -> tuple[dict, int]:
    try:
        index = _load(args)
    except (OSError, ValueError, KeyError, TypeError) as error:
        return {"schemaVersion": SCHEMA, "ok": False,
                "errors": [str(error) or "index-load-failed"],
                "warnings": []}, 4
    result = lint(index, Path(args.root))
    result["schemaVersion"] = SCHEMA
    return result, 0 if result["ok"] else 4


def cmd_regression(args) -> tuple[dict, int]:
    try:
        index = _load(args)
    except (OSError, ValueError, KeyError, TypeError) as error:
        return {"schemaVersion": SCHEMA, "ok": False,
                "failures": [str(error)]}, 4
    failures, passed = [], 0
    for name, text, want_skill, want_intent in REGRESSION:
        result = vibe.resolve(index, text, args.root)
        got_skill, got_intent = result.get("primary"), result.get("intent")
        if got_skill == want_skill and (want_intent is None
                                      or got_intent == want_intent):
            passed += 1
        else:
            failures.append({
                "case": name, "text": text,
                "want": {"primary": want_skill, "intent": want_intent},
                "got": {"primary": got_skill, "intent": got_intent,
                        "score": result.get("score")}})
    return {"schemaVersion": SCHEMA, "ok": not failures,
            "total": len(REGRESSION), "passed": passed,
            "failures": failures}, (0 if not failures else 4)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("resolve", "lint", "regression"))
    parser.add_argument("text", nargs="?", default="")
    parser.add_argument("--root", default=str(ROOT))
    parser.add_argument("--index", default=vibe.DEFAULT_INDEX)
    args = parser.parse_args(argv)
    if args.action == "resolve":
        result, code = cmd_resolve(args)
    elif args.action == "lint":
        result, code = cmd_lint(args)
    else:
        result, code = cmd_regression(args)
    print(json.dumps(result, ensure_ascii=True))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
