#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Unit tests for scripts/provider_limits_janitor.py (stdlib, offline)."""
import datetime as _dt
import importlib.util
import io
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "provider_limits_janitor", os.path.join(HERE, "provider_limits_janitor.py"))
janitor = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(janitor)

TODAY = _dt.date(2026, 10, 5)

DOC_ACTIVE = """# Doc

```yaml
capturedAt: "2026-10-05"
timezone: "Asia/Seoul"
ttlDays: 90
expiresAt: "2027-01-03"
status: "ACTIVE"
expiryAction: "archive"
```

body
"""

DOC_EXPIRED = """# Old

```yaml
capturedAt: "2026-01-01"
ttlDays: 90
expiresAt: "2026-04-01"
status: "ACTIVE"
expiryAction: "archive"
```
"""

DOC_DERIVED = """# Derived

```yaml
capturedAt: "2026-01-01"
reviewAfterDays: 90
status: "ACTIVE"
```
"""

DOC_NO_TTL = """# NoTtl

```yaml
capturedAt: "2026-10-01"
status: "ACTIVE"
```
"""

DOC_NO_FENCE = "# Plain\n\nno yaml fence\n"


def _write(root, name, text):
    p = os.path.join(root, "docs", "provider-limits")
    os.makedirs(p, exist_ok=True)
    fp = os.path.join(p, name)
    with open(fp, "w", encoding="utf-8") as fh:
        fh.write(text)
    return fp


def _run(argv):
    buf = io.StringIO()
    with redirect_stdout(buf):
        code = janitor.main(argv)
    return code, buf.getvalue()


class JanitorTests(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def test_parse_meta_reads_yaml_fence(self):
        p = _write(self.root, "a.md", DOC_ACTIVE)
        meta = janitor.parse_meta(p)
        self.assertEqual(meta["expiresAt"], "2027-01-03")
        self.assertEqual(meta["ttlDays"], "90")
        self.assertEqual(meta["status"], "ACTIVE")

    def test_classify_active_and_days_left(self):
        p = _write(self.root, "a.md", DOC_ACTIVE)
        e = janitor.classify(p, "docs/provider-limits/a.md", TODAY)
        self.assertEqual(e["state"], "ACTIVE")
        self.assertEqual(e["daysLeft"], 90)

    def test_classify_expired(self):
        p = _write(self.root, "old.md", DOC_EXPIRED)
        e = janitor.classify(p, "docs/provider-limits/old.md", TODAY)
        self.assertEqual(e["state"], "EXPIRED")
        self.assertTrue(e["daysLeft"] < 0)

    def test_classify_derived_from_captured_plus_ttl(self):
        p = _write(self.root, "d.md", DOC_DERIVED)
        e = janitor.classify(p, "docs/provider-limits/d.md", TODAY)
        self.assertEqual(e["state"], "EXPIRED")  # 2026-01-01 + 90d < today
        self.assertEqual(e["expiryBasis"], "derived")
        self.assertEqual(e["expiresAt"], "2026-04-01")

    def test_classify_no_ttl_and_no_fence(self):
        p1 = _write(self.root, "n.md", DOC_NO_TTL)
        p2 = _write(self.root, "p.md", DOC_NO_FENCE)
        e1 = janitor.classify(p1, "docs/provider-limits/n.md", TODAY)
        e2 = janitor.classify(p2, "docs/provider-limits/p.md", TODAY)
        self.assertEqual(e1["state"], "NO_TTL")
        self.assertEqual(e2["state"], "NO_TTL")

    def test_check_exit0_when_all_active(self):
        _write(self.root, "a.md", DOC_ACTIVE)
        code, out = _run(["check", "--root", self.root,
                          "--today", "2026-10-05"])
        self.assertEqual(code, 0)
        self.assertIn("expired=0", out)

    def test_check_exit1_when_expired(self):
        _write(self.root, "a.md", DOC_ACTIVE)
        _write(self.root, "old.md", DOC_EXPIRED)
        code, out = _run(["check", "--root", self.root,
                          "--today", "2026-10-05"])
        self.assertEqual(code, 1)
        self.assertIn("EXPIRED docs/provider-limits/old.md", out)

    def test_check_no_ttl_does_not_fail(self):
        _write(self.root, "n.md", DOC_NO_TTL)
        code, out = _run(["check", "--root", self.root,
                          "--today", "2026-10-05"])
        self.assertEqual(code, 0)
        self.assertIn("no_ttl=1", out)

    def test_status_lists_all_states(self):
        _write(self.root, "a.md", DOC_ACTIVE)
        _write(self.root, "old.md", DOC_EXPIRED)
        _write(self.root, "n.md", DOC_NO_TTL)
        code, out = _run(["status", "--root", self.root,
                          "--today", "2026-10-05"])
        self.assertEqual(code, 0)
        self.assertIn("ACTIVE", out)
        self.assertIn("EXPIRED", out)
        self.assertIn("NO_TTL", out)
        self.assertIn("docs=3", out)

    def test_archive_moves_and_watermarks(self):
        src = _write(self.root, "old.md", DOC_EXPIRED)
        _write(self.root, "a.md", DOC_ACTIVE)
        code, out = _run(["archive", "--root", self.root,
                          "--today", "2026-10-05"])
        self.assertEqual(code, 0)
        self.assertFalse(os.path.exists(src))
        dst = os.path.join(self.root, "docs", "provider-limits",
                           "archive", "old.md")
        self.assertTrue(os.path.exists(dst))
        with open(dst, encoding="utf-8") as fh:
            body = fh.read()
        self.assertIn("ttl-expired", body.splitlines()[0])
        self.assertIn('expiresAt="2026-04-01"', body.splitlines()[0])
        self.assertIn('status: "ARCHIVED"', body)
        # active doc untouched
        p_active = os.path.join(self.root, "docs", "provider-limits", "a.md")
        self.assertTrue(os.path.exists(p_active))

    def test_archive_dry_run_moves_nothing(self):
        src = _write(self.root, "old.md", DOC_EXPIRED)
        code, out = _run(["archive", "--root", self.root,
                          "--today", "2026-10-05", "--dry-run"])
        self.assertEqual(code, 0)
        self.assertTrue(os.path.exists(src))
        self.assertIn("WOULD-ARCHIVE", out)

    def test_check_missing_dir_exit2(self):
        code, out = _run(["check", "--root", self.root, "--dir", "no/such"])
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main(verbosity=1)
