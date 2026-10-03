"""Contract tests for scripts/jeom_access_selftest.ps1.

Runs the script against a temporary fake project root + fake Downloads dir so
no real user files are touched. Verifies: output shape (Korean table / JSON
rows), self-created temp files are removed and pre-existing files are never
deleted, and .secrets content is never emitted.
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCRIPT = REPO / "scripts" / "jeom_access_selftest.ps1"
SENTINEL = "FAKE_SECRET_SENTINEL_DO_NOT_PRINT_9f3c"
STATUSES = {"PASS", "FAIL", "SKIP"}


def _make_tree(root: Path, dl: Path) -> None:
    (root / "main" / "java").mkdir(parents=True)
    (root / "agent-prompts").mkdir(parents=True)
    (root / "AGENTS.md").write_text("# fake root\n", encoding="utf-8")
    (root / "gradlew.bat").write_text("@rem fake\n", encoding="utf-8")
    (root / ".secrets").mkdir()
    (root / ".secrets" / "dummy.txt").write_text(SENTINEL, encoding="utf-8")
    dl.mkdir(parents=True)
    (dl / "PASTE_DEVIN_probe_20261002.txt").write_text("paste", encoding="utf-8")
    keep_dir = dl / "_jeom_selftest"
    keep_dir.mkdir()
    (keep_dir / "keep.txt").write_text("do not delete", encoding="utf-8")


def _run_ps(root: Path, dl: Path, as_json: bool) -> subprocess.CompletedProcess:
    args = ["-NoProfile", "-ExecutionPolicy", "Bypass"]
    script = str(SCRIPT).replace("'", "''")
    inner = f"& '{script}' -Root '{root}' -DownloadsDir '{dl}'"
    if as_json:
        inner += " -Json"
    cmd = ["powershell"] + args + [
        "-Command",
        "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; " + inner,
    ]
    return subprocess.run(cmd, capture_output=True, timeout=120)


class TestJeomAccessSelftest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory(prefix="jeom_selftest_")
        base = Path(cls._tmp.name)
        cls.root = base / "root"
        cls.dl = base / "dl"
        _make_tree(cls.root, cls.dl)

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def test_json_rows_shape(self):
        p = _run_ps(self.root, self.dl, as_json=True)
        out = p.stdout.decode("utf-8", errors="replace")
        start = out.find("[")
        self.assertGreaterEqual(start, 0, f"no JSON array in output: {out[:400]}")
        rows = json.loads(out[start:])
        self.assertIsInstance(rows, list)
        self.assertGreaterEqual(len(rows), 6)
        for r in rows:
            self.assertIn("item", r)
            self.assertIn("status", r)
            self.assertIn(r["status"], STATUSES, r)
            if r["status"] != "PASS":
                self.assertTrue(r.get("note"), f"missing note on {r}")

    def test_table_mode_and_fail_notes(self):
        p = _run_ps(self.root, self.dl, as_json=False)
        out = p.stdout.decode("utf-8", errors="replace")
        self.assertIn("항목", out)
        self.assertIn("결과", out)
        self.assertTrue(re.search(r"\| (PASS|FAIL|SKIP) \|", out), out[:400])
        # FAIL/SKIP rows must carry a note (distinguish denied vs intended)
        for line in out.splitlines():
            m = re.match(r".+\| (FAIL|SKIP) \|(.*)$", line)
            if m:
                self.assertTrue(m.group(2).strip(), f"empty note: {line}")

    def test_deletes_only_own_temp_files(self):
        _run_ps(self.root, self.dl, as_json=True)
        keep = self.dl / "_jeom_selftest" / "keep.txt"
        self.assertTrue(keep.exists(), "pre-existing file was deleted")
        leftovers = list(self.dl.rglob("jeom_selftest_*.tmp")) + list(
            self.root.rglob("jeom_selftest_*.tmp")
        )
        self.assertEqual(leftovers, [], f"temp leftovers: {leftovers}")

    def test_never_prints_secret_content(self):
        for as_json in (True, False):
            p = _run_ps(self.root, self.dl, as_json=as_json)
            blob = p.stdout + p.stderr
            text = blob.decode("utf-8", errors="replace")
            self.assertNotIn(SENTINEL, text)
            self.assertNotIn("dummy.txt", text)

    def test_root_markers_row_present(self):
        p = _run_ps(self.root, self.dl, as_json=True)
        out = p.stdout.decode("utf-8", errors="replace")
        self.assertIn("루트 읽기", out.replace("\\", ""))  # tolerate encoding quirks
        self.assertNotEqual(p.returncode, 127)


if __name__ == "__main__":
    unittest.main()
