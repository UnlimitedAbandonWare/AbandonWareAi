#!/usr/bin/env python3
"""test_agy_skill_pack_check.py - agy_skill_pack_check 회로 합성 단위 테스트.

모든 케이스는 임시 폴더(--root)에 합성 skill/intent-index/brief를 만들어
판정만 본다. live tree·네트워크·비밀 경로는 일절 건드리지 않는다.
"""
from __future__ import annotations

import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "agy_skill_pack_check",
    str(Path(__file__).resolve().parent / "agy_skill_pack_check.py"))
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

GOOD_FM = "---\nname: {sid}\ndescription: >-\n  {desc}\n---\n\n"
DESC = "Use when testing pack checks for the demo synthetic skill folder."
# >=1500B 본문 + 절차/검증/금지 키워드
GOOD_BODY = (
    "# Synth skill\n\n## Steps\n\n1. one\n2. two\n3. three\n\n"
    "## Verify\nrun the check and confirm PASS exit.\n\n"
    "## Do not\nnever skip verification; hold on doubt.\n\n"
    + ("filler line to reach body bytes.\n" * 40))


def make_root() -> Path:
    root = Path(tempfile.mkdtemp(prefix="agypack-"))
    (root / ".agents" / "skills").mkdir(parents=True)
    (root / "scripts").mkdir(parents=True)
    (root / "docs" / "agents-rules").mkdir(parents=True)
    return root


def write_skill(root: Path, sid: str, body: str = GOOD_BODY,
                desc: str = DESC, extra_files=None):
    d = root / ".agents" / "skills" / sid
    d.mkdir(parents=True, exist_ok=True)
    (d / "SKILL.md").write_text(
        GOOD_FM.format(sid=sid, desc=desc) + body, encoding="utf-8")
    for rel in (extra_files or []):
        p = d / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("# companion\n", encoding="utf-8")
    return d


def write_index(root: Path, sid: str | None):
    idx = root / ".agents" / "skills-intent-index.yaml"
    body = "schemaVersion: 1\nintents:\n"
    if sid:
        body += (f"  - intent: test-{sid}\n    match: [x]\n"
                 f"    primary_skill: {sid}\n    optional_skill: null\n")
    body += "fallback:\n  primary_skill: demo1-vibe-max-agency\n"
    idx.write_text(body, encoding="utf-8")


def write_brief(root: Path, text: str) -> Path:
    p = root / "PASTE_TEST.txt"
    p.write_text(text, encoding="utf-8")
    return p


class SkillGradeTests(unittest.TestCase):
    def setUp(self):
        self.root = make_root()

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_s_pass(self):
        write_skill(self.root, "demo1-synth")
        write_index(self.root, None)
        res = mod.check_skill(self.root, ".agents/skills/demo1-synth", "S")
        self.assertEqual(mod.verdict_of(res), "PASS", res)

    def test_m_missing_intent(self):
        write_skill(self.root, "demo1-synth",
                    extra_files=["references/r.md"])
        write_index(self.root, None)  # intent 없음
        res = mod.check_skill(self.root, ".agents/skills/demo1-synth", "M")
        self.assertEqual(mod.verdict_of(res), "FAIL", res)
        self.assertIn("intent-missing", res["fails"])

    def test_m_missing_companion(self):
        write_skill(self.root, "demo1-synth")  # SKILL.md 단독
        write_index(self.root, "demo1-synth")
        res = mod.check_skill(self.root, ".agents/skills/demo1-synth", "M")
        self.assertEqual(mod.verdict_of(res), "FAIL", res)
        self.assertIn("companion-missing", res["fails"])

    def test_l_missing_rule_doc(self):
        write_skill(self.root, "demo1-synth",
                    extra_files=["references/r.md"])
        write_index(self.root, "demo1-synth")
        res = mod.check_skill(self.root, ".agents/skills/demo1-synth", "L")
        self.assertEqual(mod.verdict_of(res), "FAIL", res)
        self.assertTrue(any("rule-doc-missing" in f for f in res["fails"]))

    def test_m_full_pass(self):
        (self.root / "scripts" / "probe_x.py").write_text("x=1\n")
        (self.root / "scripts" / "test_probe_x.py").write_text("x=1\n")
        write_skill(self.root, "demo1-synth",
                    body=GOOD_BODY + "see `scripts/probe_x.py` probe.\n")
        write_index(self.root, "demo1-synth")
        res = mod.check_skill(self.root, ".agents/skills/demo1-synth", "M")
        self.assertEqual(mod.verdict_of(res), "PASS", res)
        self.assertTrue(res["companions"])

    def test_s_name_folder_mismatch_fails(self):
        d = self.root / ".agents" / "skills" / "demo1-synth"
        d.mkdir(parents=True)
        (d / "SKILL.md").write_text(
            GOOD_FM.format(sid="demo1-other", desc=DESC) + "body\n",
            encoding="utf-8")
        res = mod.check_skill(self.root, ".agents/skills/demo1-synth", "S")
        self.assertEqual(mod.verdict_of(res), "FAIL", res)


class BriefTests(unittest.TestCase):
    def setUp(self):
        self.root = make_root()

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def test_brief_scatter_fails(self):
        brief = write_brief(self.root, "".join(
            f"use @demo1-skill-{i} now\n" for i in range(6)))
        res = mod.check_brief(self.root, brief)
        self.assertEqual(mod.verdict_of(res), "FAIL", res)
        self.assertTrue(any("SKILL_SCATTER" in f for f in res["fails"]))

    def test_brief_full_pack_pass(self):
        brief = write_brief(self.root, (
            "## 4-1. SKILL_PACK\n"
            "| WP | 등급 | 스킬 경로 | companion 경로 | intent | 검사 명령 |\n"
            "| WP1 | M | .agents/skills/demo1-x/SKILL.md | "
            "references/x.md | skills-intent-index intent | "
            "python -B scripts/agy_skill_pack_check.py --skill x |\n"))
        res = mod.check_brief(self.root, brief)
        self.assertEqual(mod.verdict_of(res), "PASS", res)

    def test_brief_skill_no_pack_fails(self):
        brief = write_brief(self.root, (
            "WP1 create .agents/skills/demo1-x/SKILL.md only.\n"))
        res = mod.check_brief(self.root, brief)
        self.assertEqual(mod.verdict_of(res), "FAIL", res)
        self.assertTrue(any("NO_SKILL_PACK" in f for f in res["fails"]))

    def test_brief_devin_standard_preset_passes(self):
        preset = (
            "@objective-executor @demo1-devin-source-orchestrator @demo1-vibe-max-agency "
            "@demo1-core-request-router @meta-rayban-display @demo1-meta-display-simple-caption "
            "@demo1-meta-display-resume @frontend-display-debug @demo1-conversate-hint-context "
            "@demo1-evidence-debugging @demo1-repairing-from-live-evidence @rag-search-diagnosis "
            "@search-zero-result-recovery @safe-source-edit @compile-verify-smoke @start-rag-reload "
            "@positive-negative-neutral-judge @SKILL.md\n"
        )
        brief = write_brief(self.root, preset)
        res = mod.check_brief(self.root, brief)
        self.assertFalse(any("SKILL_SCATTER" in f for f in res["fails"]), res)


if __name__ == "__main__":
    unittest.main()
