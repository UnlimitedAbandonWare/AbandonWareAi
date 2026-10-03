"""Tests for scripts/demo1_skill_quality_audit.py.

Covered: folded-YAML description parsing, missing-trigger penalty,
listing-line exclusion in the usage scan, and index coverage counting.
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

import demo1_skill_quality_audit as audit  # noqa: E402

FOLDED_SKILL = """---
name: folded-skill
description: >-
  Use when the operator needs folded descriptions
  parsed across multiple YAML lines correctly.
---

## Steps
1. do the thing
2. verify the thing
"""

TRIGGERLESS_SKILL = """---
name: triggerless-skill
description: Local-first ledger journal and preimage tracking utility only.
---

## Steps
1. open journal
2. checkpoint
3. verify pass/fail results in a report output format.
"""


def _write_skill(root: Path, name: str, text: str):
    d = root / ".agents" / "skills" / name
    d.mkdir(parents=True, exist_ok=True)
    (d / "SKILL.md").write_text(text, encoding="utf-8")


class FrontmatterParseTest(unittest.TestCase):
    def test_folded_description(self):
        fm, _ = audit.split_frontmatter(FOLDED_SKILL)
        desc = audit.extract_field(fm, "description")
        self.assertIn("folded descriptions", desc)
        self.assertIn("YAML lines correctly", desc)
        self.assertNotIn(">-", desc)

    def test_literal_and_quoted(self):
        fm = 'name: x\ndescription: "Use when quoted desc"\n'
        self.assertEqual(audit.extract_field(fm, "description"),
                         "Use when quoted desc")
        fm2 = 'name: x\ndescription: |\n  line one\n  line two\n'
        self.assertEqual(audit.extract_field(fm2, "description"),
                         "line one\nline two")


class ScoringTest(unittest.TestCase):
    def test_missing_trigger_penalty(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_skill(root, "triggerless-skill", TRIGGERLESS_SKILL)
            skills = audit.scan_skills(str(root), str(root / "nohome"))
            row = skills["triggerless-skill"]
            self.assertIn("desc-no-trigger", row["why"])
            self.assertLessEqual(row["score"], 92)

    def test_triggered_description_no_penalty(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_skill(root, "folded-skill", FOLDED_SKILL)
            row = audit.scan_skills(str(root), str(root / "nohome"))["folded-skill"]
            self.assertNotIn("desc-no-trigger", row["why"])


class UsageScanTest(unittest.TestCase):
    def _skills(self, tmp: Path):
        for name in ("skill-alpha", "skill-beta", "skill-gamma",
                     "skill-delta", "skill-epsilon", "skill-zeta"):
            _write_skill(tmp, name, TRIGGERLESS_SKILL.replace(
                "triggerless-skill", name))
        return audit.scan_skills(str(tmp), str(tmp / "nohome"))

    def test_listing_line_excluded(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            skills = self._skills(tmp)
            sess = tmp / "fakehome" / ".codex" / "sessions" / "s1"
            sess.mkdir(parents=True)
            # a "skills list" injection line that mentions 6 skills -> excluded
            listing = ("Available skills: $skill-alpha $skill-beta "
                       "$skill-gamma $skill-delta $skill-epsilon $skill-zeta")
            real = '"user_message": "please run $skill-alpha now"'
            (sess / "a.jsonl").write_text(listing + "\n" + real + "\n",
                                          encoding="utf-8")
            use = audit.count_usage(skills, str(tmp), str(tmp / "fakehome"),
                                    30, agent_filter={"codex"})
            self.assertEqual(use["codex"]["skill-alpha"][0], 1)
            self.assertEqual(use["codex"]["skill-beta"][0], 0)
            self.assertEqual(use["codex"]["skill-epsilon"][0], 0)

    def test_over_four_names_excluded(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            skills = self._skills(tmp)
            sess = tmp / "fakehome" / ".codex" / "sessions" / "s2"
            sess.mkdir(parents=True)
            line = '"user_message": "$skill-alpha $skill-beta $skill-gamma ' \
                   '$skill-delta $skill-epsilon"'
            (sess / "b.jsonl").write_text(line + "\n", encoding="utf-8")
            use = audit.count_usage(skills, str(tmp), str(tmp / "fakehome"),
                                    30, agent_filter={"codex"})
            self.assertEqual(use["codex"]["skill-alpha"][0], 0)


class IndexCoverageTest(unittest.TestCase):
    def test_coverage_counts_unindexed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_skill(root, "indexed-one", FOLDED_SKILL)
            _write_skill(root, "orphan-two", TRIGGERLESS_SKILL.replace(
                "name: triggerless-skill", "name: orphan-two"))
            idx = root / ".agents" / "skills-intent-index.yaml"
            idx.write_text(
                "schemaVersion: 1\n"
                "intents:\n"
                "  - intent: t\n"
                "    match: ['x']\n"
                "    primary_skill: indexed-one\n",
                encoding="utf-8")
            skills = audit.scan_skills(str(root), str(root / "nohome"))
            index = audit.load_index(str(root),
                                     ".agents/skills-intent-index.yaml")
            cov = audit.index_coverage(skills, index)
            self.assertEqual(cov["repo_skills"], 2)
            self.assertEqual(cov["indexed"], 1)
            self.assertEqual(cov["unindexed"], ["orphan-two"])

    def test_secret_masking(self):
        self.assertEqual(
            audit.mask("key: sk-abcdefghijklmnop"),
            "key: <masked>")
        out = audit.mask("token = 'abcdef'")
        self.assertIn("<masked>", out)
        self.assertNotIn("abcdef", out)


if __name__ == "__main__":
    unittest.main()
