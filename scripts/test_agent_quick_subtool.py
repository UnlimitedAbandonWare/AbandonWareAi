"""Isolated unit tests for scripts/agent_quick_subtool.py (Sub-Tool.bat backend).

Runs the tool as a subprocess in temp dirs — no repo files touched, offline only.
  python -B scripts/test_agent_quick_subtool.py
"""
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "agent_quick_subtool.py"


def run_subtool(*args, timeout=60):
    return subprocess.run(
        [sys.executable, "-B", str(SCRIPT), *args],
        capture_output=True, text=True, cwd=str(ROOT), timeout=timeout)


class TestHashAction(unittest.TestCase):
    def test_hash_action(self):
        payload = b"line1\nline2\n"
        with tempfile.TemporaryDirectory() as td:
            f = Path(td) / "sample.txt"
            f.write_bytes(payload)
            proc = run_subtool("hash", str(f), "--json")
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            data = json.loads(proc.stdout)
            self.assertEqual(data["action"], "hash")
            self.assertEqual(len(data["files"]), 1)
            row = data["files"][0]
            self.assertTrue(row["exists"])
            self.assertEqual(row["bytes"], len(payload))
            self.assertEqual(row["lines"], 2)
            self.assertEqual(row["sha12"], hashlib.sha256(payload).hexdigest()[:12])
            self.assertEqual(data["missing"], [])

    def test_hash_missing_file(self):
        proc = run_subtool("hash", "does/not/exist.xyz", "--json")
        self.assertEqual(proc.returncode, 1)
        data = json.loads(proc.stdout)
        self.assertEqual(data["missing"], ["does/not/exist.xyz"])


class TestLint(unittest.TestCase):
    def test_lint_python_and_json(self):
        with tempfile.TemporaryDirectory() as td:
            good_py = Path(td) / "ok.py"
            good_py.write_text("x = 1\n", encoding="utf-8")
            good_json = Path(td) / "ok.json"
            good_json.write_text('{"a": 1}\n', encoding="utf-8")

            proc = run_subtool("lint", str(good_py), str(good_json), "--json")
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            data = json.loads(proc.stdout)
            self.assertEqual(data["fail"], 0)
            self.assertEqual(data["pass"], 2)

            bad_py = Path(td) / "bad.py"
            bad_py.write_text("def broken(:\n", encoding="utf-8")
            bad_json = Path(td) / "bad.json"
            bad_json.write_text("{invalid", encoding="utf-8")

            proc = run_subtool("lint", str(bad_py), str(bad_json), "--json")
            self.assertEqual(proc.returncode, 1)
            data = json.loads(proc.stdout)
            fails = [r for r in data["results"] if r["status"] == "fail"]
            self.assertEqual(len(fails), 2, data["results"])
            for r in fails:
                self.assertTrue(r["message"])
                self.assertIsNotNone(r["line"], r)


class TestPrecheck(unittest.TestCase):
    def test_precheck_metadata(self):
        payload = b"alpha\nbeta\ngamma\n"
        with tempfile.TemporaryDirectory() as td:
            f = Path(td) / "ctx.py"
            f.write_bytes(payload)
            proc = run_subtool("precheck", str(f), "--json")
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            data = json.loads(proc.stdout)
            row = data["files"][0]
            self.assertEqual(row["sha12"], hashlib.sha256(payload).hexdigest()[:12])
            self.assertEqual(row["bytes"], len(payload))
            self.assertEqual(row["lines"], 3)
            self.assertIn("mtimeUtc", row)
            self.assertEqual(row["firstLine"], "alpha")
            self.assertEqual(row["lastLine"], "gamma")


if __name__ == "__main__":
    unittest.main()
