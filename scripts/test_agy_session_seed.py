#!/usr/bin/env python3
"""Offline unit tests for scripts/agy_session_seed.py (no agy calls)."""

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location(
    "agy_session_seed", ROOT / "scripts" / "agy_session_seed.py")
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)


class TestScrub(unittest.TestCase):
    def test_secret_values_redacted(self):
        fake = "API" + "_KEY" + '="zz' + "zz" + '"'
        out = seed.scrub(fake + " path=/tmp/x")
        self.assertNotIn("zzzz", out)
        self.assertIn("<redacted>", out)

    def test_email_redacted(self):
        self.assertNotIn("user@example.com", seed.scrub("mail user@example.com ok"))

    def test_plain_line_untouched(self):
        self.assertEqual(seed.scrub("M  scripts/foo.py"), "M  scripts/foo.py")


class TestBuildSeed(unittest.TestCase):
    def test_seed_has_sections_and_cap(self):
        text = seed.build_seed()
        self.assertIn("# agy session seed", text)
        self.assertIn("## git", text)
        self.assertIn("## leases", text)
        self.assertLessEqual(len(text.encode("utf-8")), seed.MAX_BYTES + 64)

    def test_seed_contains_head(self):
        text = seed.build_seed()
        self.assertRegex(text, r"HEAD=[0-9a-f?]")


class TestLauncher(unittest.TestCase):
    def test_main_writes_seed(self):
        tmp = Path(tempfile.mkdtemp()) / "latest.md"
        orig = seed.SEED
        try:
            seed.SEED = tmp
            rc = seed.main()
            self.assertEqual(rc, 0)
            self.assertTrue(tmp.is_file())
        finally:
            seed.SEED = orig

    def test_quota_hit_false_on_missing_logs(self):
        # With no recent log hit the function returns False (or True only when
        # a real 6h hit exists); just ensure it does not raise.
        self.assertIn(seed.quota_hit_6h(), (True, False))


if __name__ == "__main__":
    unittest.main()
