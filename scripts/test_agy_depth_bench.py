#!/usr/bin/env python3
"""Offline unit tests for scripts/agy_depth_bench.py (no agy calls)."""

import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location(
    "agy_depth_bench", ROOT / "scripts" / "agy_depth_bench.py")
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


class TestKeys(unittest.TestCase):
    def test_key_e_finds_default_lines(self):
        k = bench.key_e()
        self.assertTrue(k["lines"], "no AWX_AGY_EFFORT default line found")
        self.assertEqual(k["value"], "high")

    def test_key_m_finds_picker(self):
        k = bench.key_m()
        self.assertEqual(k["func"], "Get-BestSameTier")
        self.assertGreater(k["line"], 0)
        for c in ("family", "tier"):
            self.assertIn(c, k["criteria"])

    def test_key_h_five_titles_and_proto(self):
        k = bench.key_h()
        self.assertEqual(len(k["titles"]), 5)
        self.assertTrue(all(t["line"] > 0 for t in k["titles"]))
        self.assertTrue(k["protoDoc"].startswith("docs/"))


class TestGrading(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        bench.KEYS.update({"E": bench.key_e(), "M": bench.key_m(),
                           "H": bench.key_h()})

    def test_e_full(self):
        line = bench.KEYS["E"]["lines"][0]
        self.assertEqual(bench.grade("E", {"line": line, "value": "high"}), 1.0)

    def test_e_partial(self):
        self.assertEqual(bench.grade("E", {"line": 1, "value": "high"}), 0.5)

    def test_m_full(self):
        k = bench.KEYS["M"]
        self.assertEqual(bench.grade("M", {"func": k["func"], "line": k["line"],
                                           "criteria": k["criteria"]}), 1.0)

    def test_h_partial_and_proto(self):
        k = bench.KEYS["H"]
        got = {"titles": [t["title"] for t in k["titles"]],
               "protoDoc": k["protoDoc"]}
        self.assertAlmostEqual(bench.grade("H", got), 1.0)
        self.assertAlmostEqual(bench.grade("H", {"titles": [], "protoDoc": k["protoDoc"]}), 0.4)

    def test_last_json_picks_trailing(self):
        resp = "blah\nnot json\n{\"line\": 83, \"value\": \"high\"}\n"
        self.assertEqual(bench.last_json(resp)["line"], 83)
        self.assertIsNone(bench.last_json("no json here"))


if __name__ == "__main__":
    unittest.main()
