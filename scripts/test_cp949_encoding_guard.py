"""Unit tests for cp949_encoding_guard (Windows cp949 crash prevention)."""
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import cp949_encoding_guard as guard  # noqa: E402


class Cp949GuardTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def _write(self, name, text):
        path = self.dir / name
        path.write_text(text, encoding="utf-8")
        return path

    def test_detects_em_dash_in_docstring(self):
        path = self._write("bad.py", '"""Doc with em \u2014 dash."""\nprint("x")\n')
        result = guard.scan_file(path)
        self.assertEqual(result["nonCp949Count"], 1)
        self.assertIn("U+2014", result["uniqueCodepoints"])
        self.assertEqual(result["findings"][0]["kind"], "string")

    def test_classifies_comment_and_code(self):
        path = self._write("mixed.py", 'x = "\u2014"  # snow \u2603\ny = 1\n')
        result = guard.scan_file(path)
        self.assertEqual(result["nonCp949Count"], 2)
        self.assertEqual(result["kinds"]["string"], 1)
        self.assertEqual(result["kinds"]["comment"], 1)

    def test_ignores_cp949_encodable_korean(self):
        # Korean syllables are encodable in cp949 and must not flag.
        path = self._write("korean.py", '"""한글 docstring."""\nprint("한글")\n')
        result = guard.scan_file(path)
        self.assertEqual(result["nonCp949Count"], 0)

    def test_clean_ascii_file(self):
        path = self._write("ok.py", '"""Plain ASCII doc."""\nprint("ok")\n')
        self.assertEqual(guard.scan_file(path)["nonCp949Count"], 0)

    def test_fix_dry_run_leaves_file_untouched(self):
        path = self._write("fixme.py", '"""Doc \u2014 dash."""\n')
        result = guard.fix_file(path, dry_run=True)
        self.assertTrue(result["changed"])
        self.assertEqual(result["replacedTotal"], 1)
        self.assertIn("\u2014", path.read_text(encoding="utf-8"))

    def test_fix_writes_ascii_replacement(self):
        path = self._write("fixme.py", '"""Doc \u2014 dash."""\n')
        result = guard.fix_file(path, dry_run=False)
        self.assertTrue(result["changed"])
        self.assertEqual(result["remainingNonCp949"], 0)
        text = path.read_text(encoding="utf-8")
        self.assertNotIn("\u2014", text)
        self.assertIn("-- dash", text)

    def test_fix_reports_remaining_unmapped_chars(self):
        path = self._write("partial.py", '"""Dash \u2014 and snowman \u2603."""\n')
        result = guard.fix_file(path)
        self.assertEqual(result["replacedTotal"], 1)
        self.assertEqual(result["remainingNonCp949"], 1)

    def test_scan_dir_reports_flagged_only(self):
        self._write("bad.py", '"""Em \u2014 dash."""\n')
        self._write("good.py", '"""Clean."""\n')
        report = guard.scan_dir(self.dir)
        self.assertEqual(report["filesScanned"], 2)
        self.assertEqual(report["filesFlagged"], 1)
        self.assertEqual(report["files"][0]["path"].endswith("bad.py"), True)

    def test_cli_scan_json_output(self):
        self._write("bad.py", '"""Em \u2014 dash."""\n')
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = guard.main(["scan", "--dir", str(self.dir)])
        self.assertEqual(code, 0)
        report = json.loads(buf.getvalue())
        self.assertEqual(report["filesFlagged"], 1)

    def test_cli_scan_strict_exits_nonzero(self):
        self._write("bad.py", '"""Em \u2014 dash."""\n')
        with redirect_stdout(io.StringIO()):
            self.assertEqual(guard.main(["scan", "--dir", str(self.dir),
                                         "--strict"]), 1)

    def test_cli_fix_end_to_end(self):
        path = self._write("bad.py", '"""Em \u2014 dash."""\n')
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = guard.main(["fix", str(path)])
        self.assertEqual(code, 0)
        self.assertNotIn("\u2014", path.read_text(encoding="utf-8"))

    def test_ensure_utf8_streams(self):
        guard.ensure_utf8_streams()
        if hasattr(sys.stdout, "reconfigure"):
            self.assertIn("UTF-8", sys.stdout.encoding.upper().replace("UTF8", "UTF-8"))


if __name__ == "__main__":
    unittest.main()
