"""Fixture tests for scripts/skill_frontmatter_lint.py and rule generators."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import os
import shutil
import tempfile
import unittest

from scripts import skill_frontmatter_lint as L
from scripts import grok_to_agy_memory_bridge as bridge
from scripts import grokbot_bot_import as botimp

GOOD = "---\nname: demo-x\ndescription: 'Use when something happens.'\n---\n\n# demo-x\n"
COLON = ("---\nname: demo-x\n"
         "description: Use when a demo-1 Codex decision needs axes (UAW self-ask): definer=contract.\n"
         "---\n\n# demo-x\n")
NO_FM = "# demo-x\n\nUse when the demo-1 root accumulates residue.\n"
NAME_BAD = "---\nname: other\ndescription: 'x'\n---\n\n# demo-x\n"
DESC_EMPTY = "---\nname: demo-x\ndescription: ''\n---\n\n# demo-x\n"


class LintFixtureTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="skills-fixture-")
        self.addCleanup(shutil.rmtree, self.dir)

    def _skill(self, folder, text):
        d = os.path.join(self.dir, folder)
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, "SKILL.md")
        with open(p, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        return p

    def test_good(self):
        p = self._skill("demo-x", GOOD)
        self.assertEqual(L.lint_file(p), [])

    def test_colon_description_flagged(self):
        p = self._skill("demo-x", COLON)
        codes = [c for c, _ in L.lint_file(p)]
        self.assertTrue(any(c in ("yaml-error", "unquoted-colon") for c in codes), codes)

    def test_missing_frontmatter(self):
        p = self._skill("demo-x", NO_FM)
        codes = [c for c, _ in L.lint_file(p)]
        self.assertEqual(codes, ["missing-frontmatter"])

    def test_name_mismatch(self):
        p = self._skill("demo-x", NAME_BAD)
        codes = [c for c, _ in L.lint_file(p)]
        self.assertIn("name-mismatch", codes)

    def test_empty_description(self):
        p = self._skill("demo-x", DESC_EMPTY)
        codes = [c for c, _ in L.lint_file(p)]
        self.assertIn("empty-description", codes)

    def test_fix_quote_repairs_colon(self):
        p = self._skill("demo-x", COLON)
        self.assertTrue(L.fix_quote(p))
        self.assertEqual(L.lint_file(p), [])
        with open(p, "r", encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn("(UAW self-ask): definer=contract.", text)
        self.assertIn("description: 'Use when", text)


class GeneratorFrontmatterRegressionTest(unittest.TestCase):
    def test_grok_bridge_rule_content_starts_with_frontmatter(self):
        text = bridge.build_rule_content([], [], {}, [])
        self.assertTrue(text.startswith("---\ntrigger: always_on\n---\n"), repr(text[:60]))

    def test_grokbot_bot_pointer_starts_with_frontmatter(self):
        self.assertTrue(botimp.POINTER_TEXT.startswith("---\ntrigger: always_on\n---\n"),
                        repr(botimp.POINTER_TEXT[:60]))


if __name__ == "__main__":
    unittest.main()
