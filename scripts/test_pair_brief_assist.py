"""Fixture tests for pair_brief_assist. No product source and no Gradle."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts" / "pair_brief_assist.py"


def load_module():
    spec = importlib.util.spec_from_file_location("pair_brief_assist_under_test", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def write_spec(root: Path, body: dict) -> Path:
    path = root / "spec.json"
    path.write_text(json.dumps(body), encoding="utf-8")
    return path


class PairBriefAssistTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="awx-pair-brief-")
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def spec(self, **extra):
        body = {
            "schemaVersion": self.mod.SCHEMA,
            "contracts": {
                "DEMO": {
                    "sources": [{
                        "path": "Guard.java",
                        "expectSha12": extra.get("expect"),
                        "needles": [{"id": "reason", "regex": "chat_admission_exceeded"}],
                        "forbidden": [{"id": "busy503", "regex": "SERVICE_UNAVAILABLE"}],
                    }],
                    "coverage": [{
                        "path": "GuardTest.java",
                        "tokens": [{"id": "controller-link", "regex": "ChatApiController"}],
                    }],
                    "diffRules": [{
                        "id": "no-503",
                        "pathRegex": "Guard\\.java$",
                        "lineRegex": "SERVICE_UNAVAILABLE",
                        "allowPathRegex": "AllowedGuard\\.java$",
                    }],
                }
            },
        }
        return write_spec(self.root, body)

    def test_pin_fresh(self):
        source = self.root / "Guard.java"
        source.write_text("chat_admission_exceeded\n", encoding="utf-8")
        expect = self.mod.sha12_of(source.read_bytes())
        spec = self.spec(expect=expect)
        code = self.mod.main(["pin", "--root", str(self.root), "--spec", str(spec)])
        self.assertEqual(0, code)

    def test_pin_stale_anchor(self):
        (self.root / "Guard.java").write_text("chat_admission_exceeded\n", encoding="utf-8")
        spec = self.spec(expect="000000000000")
        code = self.mod.main(["pin", "--root", str(self.root), "--spec", str(spec)])
        self.assertEqual(4, code)

    def test_pin_missing_needle(self):
        (self.root / "Guard.java").write_text("other\n", encoding="utf-8")
        spec = self.spec(expect=None)
        code = self.mod.main(["pin", "--root", str(self.root), "--spec", str(spec)])
        self.assertEqual(3, code)

    def test_pin_forbidden_hit(self):
        text = "chat_admission_exceeded\nSERVICE_UNAVAILABLE\n"
        (self.root / "Guard.java").write_text(text, encoding="utf-8")
        spec = self.spec()
        code = self.mod.main(["pin", "--root", str(self.root), "--spec", str(spec)])
        self.assertEqual(3, code)

    def test_cover_gap(self):
        (self.root / "GuardTest.java").write_text("cancelExact only\n", encoding="utf-8")
        spec = self.spec()
        code = self.mod.main(["cover", "--root", str(self.root), "--spec", str(spec)])
        self.assertEqual(4, code)

    def test_diff_hit_and_allow(self):
        spec = self.spec()
        hit = self.root / "hit.diff"
        hit.write_text(
            "--- a/Guard.java\n+++ b/Guard.java\n+SERVICE_UNAVAILABLE\n",
            encoding="utf-8",
        )
        allowed = self.root / "ok.diff"
        allowed.write_text(
            "--- a/AllowedGuard.java\n+++ b/AllowedGuard.java\n+SERVICE_UNAVAILABLE\n",
            encoding="utf-8",
        )
        self.assertEqual(3, self.mod.main(
            ["diff-forbid", "--root", str(self.root), "--spec", str(spec), "--diff", str(hit)]))
        self.assertEqual(0, self.mod.main(
            ["diff-forbid", "--root", str(self.root), "--spec", str(spec), "--diff", str(allowed)]))

    def test_rejects_escape_and_secret_name(self):
        spec = write_spec(self.root, {
            "schemaVersion": self.mod.SCHEMA,
            "contracts": {"DEMO": {"sources": [{
                "path": "../Guard.java",
                "needles": [{"id": "reason", "regex": "x"}],
            }], "coverage": []}},
        })
        self.assertEqual(2, self.mod.main(
            ["pin", "--root", str(self.root), "--spec", str(spec)]))
        secret = self.root / "secret-notes.json"
        secret.write_text("{}", encoding="utf-8")
        self.assertEqual(2, self.mod.main(
            ["pin", "--root", str(self.root), "--spec", str(secret)]))

    def test_repo_spec_compiles(self):
        spec = ROOT / "var" / "codex-assist-pair-brief-20261005" / "spec.json"
        loaded = self.mod.load_spec(spec)
        self.assertIn("DEMO1-MULTIUSER-RESILIENCE-20261005", loaded["contracts"])
        self.assertIn("DEMO1-SESSION-DATA-CONSENT-20261005", loaded["contracts"])


if __name__ == "__main__":
    unittest.main()
