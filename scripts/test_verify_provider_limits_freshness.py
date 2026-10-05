"""Unit tests for verify_provider_limits_freshness.py (stdlib unittest)."""
import datetime as _dt
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_provider_limits_freshness as v  # noqa: E402

DOC = """# X limits (SSOT)

```yaml
capturedAt: "2026-01-01"
timezone: "Asia/Seoul"
reviewedAt: "2026-01-10"
reviewAfterDays: 90
disclaimer: >
  folded text stays ignored
sourceUrls:
  - "https://example.com/a"
  - "https://example.com/b"
```
"""

TTL_DOC = """# Y

```yaml
capturedAt: "2026-10-05"
reviewedAt: "2026-10-05"
ttlDays: 90
expiresAt: "2027-01-03"
status: "ACTIVE"
expiryAction: "archive"
sourceUrls:
  - "https://example.com/c"
```
"""

NO_DATE_DOC = """# Z

```yaml
sourceType: "official_public_documentation"
```
"""


class ParseTests(unittest.TestCase):
    def test_scalars_and_urls(self):
        meta = v.parse_yaml_block(DOC)
        self.assertEqual(meta["capturedAt"], "2026-01-01")
        self.assertEqual(meta["reviewAfterDays"], "90")
        self.assertEqual(meta["sourceUrls"],
                         ["https://example.com/a", "https://example.com/b"])
        self.assertNotIn("disclaimer", meta)

    def test_no_block(self):
        self.assertIsNone(v.parse_yaml_block("# nothing\n"))


class EvaluateTests(unittest.TestCase):
    def test_reviewed_plus_days_expired(self):
        rec = v.evaluate(Path("x-limits.md"), v.parse_yaml_block(DOC),
                         _dt.date(2026, 6, 1))
        self.assertEqual(rec["status"], v.EXPIRED)
        self.assertEqual(rec["expiresAt"], "2026-04-10")
        self.assertLess(rec["daysLeft"], 0)

    def test_reviewed_plus_days_fresh(self):
        rec = v.evaluate(Path("x-limits.md"), v.parse_yaml_block(DOC),
                         _dt.date(2026, 2, 1))
        self.assertEqual(rec["status"], v.ACTIVE)
        self.assertGreaterEqual(rec["daysLeft"], 0)

    def test_expires_at_wins(self):
        rec = v.evaluate(Path("y-limits.md"), v.parse_yaml_block(TTL_DOC),
                         _dt.date(2027, 1, 4))
        self.assertEqual(rec["status"], v.EXPIRED)
        rec = v.evaluate(Path("y-limits.md"), v.parse_yaml_block(TTL_DOC),
                         _dt.date(2027, 1, 3))
        self.assertEqual(rec["status"], v.ACTIVE)

    def test_missing_dates(self):
        rec = v.evaluate(Path("z.md"), v.parse_yaml_block(NO_DATE_DOC),
                         _dt.date(2026, 10, 5))
        self.assertEqual(rec["status"], v.MISSING)

    def test_naver_keywords(self):
        rec = v.evaluate(Path("naver-search-limits.md"),
                         v.parse_yaml_block(DOC), _dt.date(2026, 6, 1))
        self.assertTrue(any("naver" in k.lower() for k in rec["suggestedKeywords"]))


class CliTests(unittest.TestCase):
    def _run(self, argv, docs):
        with tempfile.TemporaryDirectory() as td:
            for name, text in docs.items():
                Path(td, name).write_text(text, encoding="utf-8")
            buf = io.StringIO()
            with redirect_stdout(buf):
                code = v.main(["--root", td, "--json"] + argv)
            return code, json.loads(buf.getvalue())

    def test_exit_zero_with_expired(self):
        code, rep = self._run(["--today", "2026-06-01"], {"x-limits.md": DOC})
        self.assertEqual(code, 0)
        self.assertEqual(len(rep["expired"]), 1)
        self.assertEqual(rep["expired"][0]["sourceUrls"],
                         ["https://example.com/a", "https://example.com/b"])
        self.assertTrue(rep["expired"][0]["suggestedKeywords"])

    def test_missing_root_still_zero(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = v.main(["--root", "no/such/dir-9f31", "--json"])
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(buf.getvalue())["error"], "root-missing")


if __name__ == "__main__":
    unittest.main()
