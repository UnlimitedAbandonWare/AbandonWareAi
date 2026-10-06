#!/usr/bin/env python3
"""test_codex_quick_doc.py — codex_quick_doc.py 유닛테스트 (읽기 전용).

실행: python -B scripts/test_codex_quick_doc.py  (기대 exit 0)
검증: --list, --get <key>(전 키), --search, 잘못된 키 exit 1, --get 인프로세스 <50ms.
"""
from __future__ import annotations

import subprocess
import sys
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "codex_quick_doc.py"

sys.path.insert(0, str(ROOT / "scripts"))
import codex_quick_doc  # noqa: E402

REQUIRED_KEYS = ("sse", "h2", "ps-gpu", "langchain4j", "arch")


def run_cli(*argv):
    return subprocess.run(
        [sys.executable, "-B", str(SCRIPT), *argv],
        capture_output=True, encoding="utf-8", errors="replace",
        cwd=str(ROOT), timeout=60)


class QuickDocCase(unittest.TestCase):
    def test_list_ok(self):
        r = run_cli("--list")
        self.assertEqual(r.returncode, 0, r.stderr)
        for key in REQUIRED_KEYS:
            self.assertIn(key, r.stdout)

    def test_get_each_required_key(self):
        for key in REQUIRED_KEYS:
            r = run_cli("--get", key)
            self.assertEqual(r.returncode, 0, f"{key}: {r.stderr}")
            self.assertIn("doc_id", r.stdout, f"{key}: missing frontmatter")
            self.assertIn("title", r.stdout, f"{key}: missing title")

    def test_get_all_registered_keys(self):
        for key in codex_quick_doc.DOCS:
            r = run_cli("--get", key)
            self.assertEqual(r.returncode, 0, f"{key}: {r.stderr}")

    def test_get_unknown_key_exit1(self):
        r = run_cli("--get", "no-such-key")
        self.assertEqual(r.returncode, 1)
        self.assertIn("unknown key", r.stderr)

    def test_search_hit(self):
        r = run_cli("--search", "AUTO_SERVER")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("AUTO_SERVER", r.stdout)
        self.assertIn("[h2]", r.stdout)

    def test_search_miss_exit0(self):
        r = run_cli("--search", "zz-no-such-token-qqq")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("no matches", r.stdout)

    def test_get_in_process_under_50ms(self):
        codex_quick_doc.read_sheet("sse")  # warm any caches
        t0 = time.perf_counter()
        for key in REQUIRED_KEYS:
            self.assertTrue(codex_quick_doc.read_sheet(key))
        elapsed_ms = (time.perf_counter() - t0) * 1000
        self.assertLess(elapsed_ms, 50.0, f"read_sheet loop took {elapsed_ms:.1f}ms")

    def test_usage_error_exit2(self):
        r = run_cli()  # no flag -> argparse error
        self.assertEqual(r.returncode, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
