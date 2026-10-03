#!/usr/bin/env python3
"""Tests for scripts/source_nul_scan.py — synthetic byte fixtures only."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCAN = ROOT / "scripts" / "source_nul_scan.py"

sys.path.insert(0, str(ROOT))
from scripts import source_nul_scan  # noqa: E402


def make_root(files: dict[str, bytes]) -> Path:
    td = Path(tempfile.mkdtemp(prefix="nulscan_"))
    for rel, content in files.items():
        fp = td / rel
        fp.parent.mkdir(parents=True, exist_ok=True)
        fp.write_bytes(content)
    return td


def run_cli(root: Path, *extra: str) -> tuple[int, dict | str]:
    proc = subprocess.run(
        [sys.executable, "-B", str(SCAN), "--root", str(root), "--json",
         *extra],
        capture_output=True, timeout=120)
    out = proc.stdout.decode("utf-8", "replace").strip()
    try:
        return proc.returncode, json.loads(out.splitlines()[-1])
    except (ValueError, IndexError):
        return proc.returncode, out + proc.stderr.decode("utf-8", "replace")


class NulScanTests(unittest.TestCase):

    def test_clean_tree_exit0(self):
        r = make_root({"main/java/A.java": b"class A {\n\tint x;\r\n}\n"})
        code, res = run_cli(r)
        self.assertEqual(code, 0, res)
        self.assertEqual(res["verdict"], "CLEAN")
        self.assertEqual(res["findingCount"], 0)

    def test_nul_found_exit4_position(self):
        r = make_root({"main/java/A.java": b"class A {\n    int x;\x00\n}\n"})
        code, res = run_cli(r)
        self.assertEqual(code, 4, res)
        self.assertEqual(res["verdict"], "FOUND")
        f = res["findings"][0]
        self.assertEqual(f["path"], "main/java/A.java")
        self.assertEqual(f["line"], 2)
        self.assertEqual(f["col"], 11)
        self.assertEqual(f["codepoint"], "U+0000")

    def test_c0_vertical_tab_flagged(self):
        r = make_root({"main/java/A.java": b"line1\nli\x0bne2\n"})
        code, res = run_cli(r)
        self.assertEqual(code, 4)
        self.assertEqual(res["findings"][0]["codepoint"], "U+000B")
        self.assertEqual(res["findings"][0]["line"], 2)

    def test_c0_bell_and_esc_flagged(self):
        r = make_root({"scripts/x.py": b"a\x07b\x1bc\n"})
        code, res = run_cli(r)
        self.assertEqual(code, 4)
        codes = {f["codepoint"] for f in res["findings"]}
        self.assertEqual(codes, {"U+0007", "U+001B"})

    def test_allowed_controls_not_flagged(self):
        r = make_root({"main/java/A.java": b"a\tb\r\nc\n"})
        code, res = run_cli(r)
        self.assertEqual(code, 0, res)

    def test_binary_extension_skipped(self):
        r = make_root({
            "main/java/A.java": b"class A {}\n",
            "main/resources/x.png": b"\x89PNG\x00\x00\x0d\x0a",
            "scripts/b.jar": b"PK\x00\x00",
        })
        code, res = run_cli(r)
        self.assertEqual(code, 0, res)
        self.assertEqual(res["skippedByExtension"], 2)
        self.assertEqual(res["filesScanned"], 1)

    def test_utf8_multibyte_not_flagged(self):
        r = make_root({"main/java/A.java":
                       "// \ud55c\uae00 \uc8fc\uc11d\nclass A {}\n"
                       .encode("utf-8")})
        code, res = run_cli(r)
        self.assertEqual(code, 0, res)

    def test_multiple_files_reported(self):
        r = make_root({
            "main/java/A.java": b"a\x00b\n",
            "scripts/b.py": b"c\x01d\n",
            "main/resources/c.yml": b"e:f\n",  # clean
        })
        code, res = run_cli(r)
        self.assertEqual(code, 4)
        self.assertEqual(res["findingCount"], 2)
        self.assertEqual({f["path"] for f in res["findings"]},
                         {"main/java/A.java", "scripts/b.py"})

    def test_missing_scan_dir_ok(self):
        r = make_root({"main/java/A.java": b"class A {}\n"})
        code, res = run_cli(r)
        self.assertEqual(code, 0, res)
        self.assertEqual(res["filesScanned"], 1)

    def test_custom_dir_option(self):
        r = make_root({"custom/d.txt": b"x\x00y\n"})
        code, res = run_cli(r, "--dir", "custom")
        self.assertEqual(code, 4, res)
        self.assertEqual(res["findings"][0]["path"], "custom/d.txt")

    def test_per_file_cap(self):
        data = b"\x00" * 100
        r = make_root({"main/java/A.java": data})
        code, res = run_cli(r)
        self.assertEqual(code, 4)
        self.assertEqual(len(res["findings"]),
                         source_nul_scan.MAX_FINDINGS_PER_FILE)

    def test_bad_root_exit2(self):
        code, res = run_cli(Path("no/such/dir-zzz"))
        self.assertEqual(code, 2)

    def test_no_content_echoed(self):
        secretish = b"SECRET_BODY_XYZZY\x00\n"
        r = make_root({"main/java/A.java": secretish})
        code, res = run_cli(r)
        self.assertEqual(code, 4)
        self.assertNotIn("SECRET_BODY_XYZZY", json.dumps(res))

    def test_mvdb_skipped(self):
        r = make_root({"main/resources/db/lmsdb.mv.db": b"\x00\x01\x02"})
        code, res = run_cli(r)
        self.assertEqual(code, 0, res)
        self.assertEqual(res["skippedByExtension"], 1)


if __name__ == "__main__":
    unittest.main()
