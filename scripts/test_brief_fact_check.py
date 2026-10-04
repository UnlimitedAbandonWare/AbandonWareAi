#!/usr/bin/env python3
"""Sandbox tests for brief_fact_check.py — temp trees only, no git required."""
from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import brief_fact_check as bfc  # noqa: E402


class BriefFactCheckTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="bfc-test-"))
        (self.root / "a").mkdir()
        (self.root / "a" / "foo.py").write_text(
            "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\n",
            encoding="utf-8")
        (self.root / "b").mkdir()
        (self.root / "b" / "readme.md").write_text("alpha\nbeta\n", encoding="utf-8")
        (self.root / "x").mkdir()
        (self.root / "x" / "dup.txt").write_text("d1\n", encoding="utf-8")
        (self.root / "y").mkdir()
        (self.root / "y" / "dup.txt").write_text("d2\n", encoding="utf-8")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def _check(self, text):
        brief = self.root / "brief.txt"
        brief.write_text(text, encoding="utf-8")
        return bfc.check_file(brief, self.root)["refs"]

    def test_b1_verdicts(self):
        refs = self._check(
            "F1 a/foo.py:3 plain ref\n"
            "F2 a/foo.py:2-4 range ref\n"
            'F3 a/foo.py:3 expects `three` here\n'
            'F4 a/foo.py:5 expects `nonexistent phrase` here\n'
            "F5 a/missing.py:5 gone\n"
            "F6 a/foo.py:99 beyond\n"
            "F7 dup.txt:1 which one\n"
            "F8 b/readme.md(2행) paren form\n"
            "F9 no refs in this line\n")
        by_ref = {r["ref"].split(":")[0]: r for r in refs}
        verdicts = {r["lines"][0]: r["verdict"] for r in refs}
        self.assertEqual(refs[0]["verdict"], "OK")
        self.assertEqual(refs[1]["verdict"], "OK")
        self.assertEqual(refs[2]["verdict"], "OK")
        self.assertEqual(refs[3]["verdict"], "DRIFT")
        self.assertEqual(refs[4]["verdict"], "MISSING")
        self.assertEqual(refs[5]["verdict"], "OUT_OF_RANGE")
        self.assertEqual(refs[6]["verdict"], "AMBIGUOUS")
        self.assertEqual(refs[7]["verdict"], "OK")
        self.assertEqual(len(refs), 8)

    def test_exit_mapping(self):
        refs_ok = self._check("a/foo.py:1 ok\n")
        self.assertTrue(all(r["verdict"] == "OK" for r in refs_ok))


if __name__ == "__main__":
    unittest.main()
