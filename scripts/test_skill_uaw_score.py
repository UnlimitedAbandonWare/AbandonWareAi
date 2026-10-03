#!/usr/bin/env python3
"""test_skill_uaw_score.py — boundary tests for skill_uaw_score verdicts.

Builds a tiny fake skill set + signals in a temp dir and asserts the
deterministic verdict rules (no real repo reads).
"""
import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import skill_uaw_score as sus


def wskill(root, name, desc, body_lines=None):
    d = os.path.join(root, ".agents", "skills", name)
    os.makedirs(d, exist_ok=True)
    body = body_lines or [f"# {name}", "", "body text"]
    text = "---\n" + f"name: {name}\ndescription: {desc}\n" + "---\n" \
        + "\n".join(body) + "\n"
    p = os.path.join(d, "SKILL.md")
    with open(p, "w", encoding="utf-8") as fh:
        fh.write(text)
    return os.path.relpath(p, root).replace("\\", "/")


class UawScoreTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="uawscore-")
        self.rows = []

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def _signals(self, specs):
        # specs: list of (name, reads, near_fail, sessions)
        sig = os.path.join(self.root, "signals.jsonl")
        with open(sig, "w", encoding="utf-8") as fh:
            for name, reads, nf, sess in specs:
                fh.write(json.dumps({
                    "skill": name,
                    "path": f".agents/skills/{name}/SKILL.md",
                    "bytes": 1000, "reads": reads, "catalog_mentions": 0,
                    "mentions": 0, "unknown": 0, "near_fail": nf,
                    "distinct_sessions": sess, "co_reads": [],
                    "last_used": None, "by_source": {}}) + "\n")
        return sig

    def _run(self, specs, index_names=None):
        sig = self._signals(specs)
        idx = None
        if index_names:
            idx = os.path.join(self.root, "index.yaml")
            with open(idx, "w", encoding="utf-8") as fh:
                fh.write("intents:\n" + "".join(
                    f"  - skill: {n}\n" for n in index_names))
        return {r["skill"]: r
                for r in sus.compute(self.root, sig, idx)}, sig

    def test_missing_description_is_fix_frontmatter(self):
        wskill(self.root, "nodesc-skill", "")
        out, _ = self._run([("nodesc-skill", 9, 0, 5)],
                           index_names=["nodesc-skill"])
        self.assertEqual(out["nodesc-skill"]["verdict"], "FIX-FRONTMATTER")

    def test_healthy_skill_is_keep(self):
        wskill(self.root, "good-skill",
               "Use this when debugging flaky tests on demo-1 worktrees.")
        out, _ = self._run([("good-skill", 8, 0, 6)],
                           index_names=["good-skill"])
        self.assertEqual(out["good-skill"]["verdict"], "KEEP")
        self.assertGreaterEqual(out["good-skill"]["score"], sus.T_TIGHTEN)

    def test_zero_use_unindexed_no_when_is_cold(self):
        wskill(self.root, "cold-skill", "Some obscure niche helper.",
               body_lines=["# cold", "", "implementation details only"])
        out, _ = self._run([("cold-skill", 0, 0, 0)])
        self.assertEqual(out["cold-skill"]["verdict"], "COLD-PROPOSE")

    def test_similar_pair_weaker_merges(self):
        wskill(self.root, "lease-alpha",
               "Use when agents must claim a file lease before editing files.")
        wskill(self.root, "lease-beta",
               "Use when agents must claim a file lease before editing files.")
        out, _ = self._run([("lease-alpha", 9, 0, 4),
                            ("lease-beta", 0, 0, 0)],
                           index_names=["lease-alpha", "lease-beta"])
        self.assertEqual(out["lease-beta"]["verdict"], "MERGE-PROPOSE")
        self.assertEqual(out["lease-beta"]["sim_partner"], "lease-alpha")
        self.assertEqual(out["lease-alpha"]["verdict"], "KEEP")

    def test_moderate_zero_use_is_tighten_not_cold(self):
        # zero reads but indexed + good desc + sessions -> not cold
        wskill(self.root, "mid-skill",
               "Use this when repairing flaky session journals and leases.")
        out, _ = self._run([("mid-skill", 0, 0, 3)],
                           index_names=["mid-skill"])
        v = out["mid-skill"]["verdict"]
        self.assertIn(v, ("TIGHTEN", "KEEP"))
        self.assertNotEqual(v, "COLD-PROPOSE")

    def test_sigmoid_monotonic(self):
        self.assertLess(sus.sigmoid(0.0), sus.sigmoid(0.5))
        self.assertLess(sus.sigmoid(0.5), sus.sigmoid(1.0))
        self.assertAlmostEqual(sus.sigmoid(sus.SIGMOID_X0), 0.5)


if __name__ == "__main__":
    unittest.main(verbosity=1)
    print("testCount=6")
