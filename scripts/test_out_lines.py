#!/usr/bin/env python3
"""out_lines 샌드박스 테스트: python -B scripts/test_out_lines.py -v"""
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import out_lines  # noqa: E402


class Cap:
    def __enter__(self):
        self._old = sys.stdout
        self.buf = io.StringIO()
        sys.stdout = self.buf
        return self.buf

    def __exit__(self, *a):
        sys.stdout = self._old


def make_file(n=10, content=None):
    d = tempfile.mkdtemp(prefix="outlines-")
    p = Path(d) / "sample.txt"
    lines = content if content is not None else ["line%02d" % i for i in range(1, n + 1)]
    p.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return p


class TestOutLines(unittest.TestCase):
    def test_t1_window_numbered(self):
        p = make_file(10)
        with Cap() as buf:
            rc = out_lines.main([str(p), "--lines", "3:5"])
        self.assertEqual(rc, 0)
        t = buf.getvalue()
        self.assertIn("total_lines=10", t)
        self.assertIn("3| line03", t)
        self.assertIn("5| line05", t)
        self.assertNotIn("6| line06", t)
        self.assertIn("truncated=no", t)

    def test_t2_tail_and_json(self):
        p = make_file(10)
        with Cap() as buf:
            rc = out_lines.main([str(p), "--tail", "2", "--json"])
        self.assertEqual(rc, 0)
        d = json.loads(buf.getvalue())
        self.assertEqual(d["total_lines"], 10)
        self.assertEqual(d["windows"][0]["kind"], "tail")
        self.assertIn("9| line09", d["text"])
        self.assertIn("10| line10", d["text"])
        self.assertFalse(d["truncated"])

    def test_t3_max_chars_truncates(self):
        p = make_file(40, ["x" * 400 for _ in range(40)])
        with Cap() as buf:
            rc = out_lines.main([str(p), "--lines", "1:40", "--max-chars", "400"])
        self.assertEqual(rc, 0)
        self.assertIn("truncated=yes", buf.getvalue())

    def test_t4_multi_window(self):
        p = make_file(12)
        with Cap() as buf:
            rc = out_lines.main([str(p), "--lines", "2:3", "--lines", "8:9"])
        self.assertEqual(rc, 0)
        t = buf.getvalue()
        self.assertIn("2| line02", t)
        self.assertIn("8| line08", t)
        self.assertNotIn("5| line05", t)

    def test_t5_missing_file_exit2(self):
        with Cap():
            rc = out_lines.main(["C:\\no\\such\\file-9e8f7d.txt", "--lines", "1:3"])
        self.assertEqual(rc, 2)

    def test_t6_default_head(self):
        p = make_file(120)
        with Cap() as buf:
            rc = out_lines.main([str(p), "--max-chars", "900"])
        self.assertEqual(rc, 0)
        t = buf.getvalue()
        self.assertIn("1| line01", t)

    def test_t7_line_width_clip(self):
        p = make_file(3, ["a" * 900, "b", "c"])
        with Cap() as buf:
            rc = out_lines.main([str(p), "--lines", "1:1"])
        self.assertEqual(rc, 0)
        self.assertIn("line-truncated", buf.getvalue())


if __name__ == "__main__":
    unittest.main()
