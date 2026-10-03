#!/usr/bin/env python3
"""Unit tests for scripts/agent_archive.py using a fake tree."""
import io
import json
import os
import sys
import tempfile
import time
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import agent_archive as aa
import copy_residue_cleanup as crc

OLD = time.time() - 30 * 24 * 3600          # 30d: past 7d and 14d tiers
MID = time.time() - 8 * 24 * 3600           # 8d: past 7d, inside 14d
NEW = time.time() - 3600                    # inside 72h


def w(root, rel, data=b"x", mtime=OLD):
    p = os.path.join(root, rel.replace("/", os.sep))
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "wb") as f:
        f.write(data)
    os.utime(p, (mtime, mtime))
    return p


class FakeTree(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        self.ext = tempfile.TemporaryDirectory()
        for d in ("docs/diagnostics", "data/agent-handoff/task-a",
                  "var/debug", "build/reports", "scripts",
                  "scripts/__pycache__"):
            os.makedirs(os.path.join(self.root, d), exist_ok=True)
        self.trackedfile = os.path.join(self.ext.name, "tracked.txt")
        Path(self.trackedfile).write_text("", encoding="utf-8")
        self.skipfile = os.path.join(self.ext.name, "skip.json")
        Path(self.skipfile).write_text('{"skipPrefixes": []}',
                                       encoding="utf-8")
        self.catalog = os.path.join(self.ext.name, "catalog.jsonl")
        self.ledger = os.path.join(self.ext.name, "ledger")
        self.qroot = os.path.join(self.ext.name, "quarantine")

    def tearDown(self):
        self.tmp.cleanup()
        self.ext.cleanup()

    def run_scan(self):
        return aa.scan(self.root, tracked_file=self.trackedfile,
                       skip_file=self.skipfile)

    def by_path(self, rows):
        return {r["path"]: r for r in rows}

    def argv(self, mode, extra):
        return [mode, "--root", self.root,
                "--tracked-file", self.trackedfile,
                "--skip-file", self.skipfile] + extra

    def test_01_catalog_row_fields(self):
        w(self.root, "docs/diagnostics/rep.md", b"# Title\nbody")
        rows = self.run_scan()
        self.assertTrue(rows)
        for r in rows:
            for key in ("path", "kind", "topic", "tags", "bin", "score",
                        "why", "sha12", "size", "mtime", "tracked",
                        "referenced_by", "superseded_by", "run_hint"):
                self.assertIn(key, r, "missing %s" % key)
            self.assertIn(r["bin"], ("DELETE", "COLD", "RECYCLE",
                                     "KEEP-LIVE", "TRACKED", "SKIP"))

    def test_02_referenced_script_keep_live(self):
        w(self.root, "scripts/tool_a.py",
          b'"""tool."""\nimport argparse\n')
        w(self.root, "Run-Thing.bat", b"call python scripts\\tool_a.py\n")
        rows = self.by_path(self.run_scan())
        self.assertEqual(rows["scripts/tool_a.py"]["bin"], "KEEP-LIVE")
        self.assertEqual(rows["scripts/tool_a.py"]["why"], "referenced")

    def test_03_unref_old_script_cold_and_archive_flag(self):
        w(self.root, "scripts/helper_b.py", b"x = 1\n")
        w(self.root, "scripts/toolc_c.py",
          b'"""useful tool."""\nimport argparse\n'
          b'p = argparse.ArgumentParser()\nif __name__ == "__main__":\n'
          b'    pass\n')
        rows = self.by_path(self.run_scan())
        self.assertEqual(rows["scripts/helper_b.py"]["bin"], "COLD")
        self.assertIsNone(rows["scripts/helper_b.py"]["archive_to"])
        self.assertEqual(rows["scripts/toolc_c.py"]["bin"], "COLD")
        self.assertEqual(rows["scripts/toolc_c.py"]["archive_to"],
                         "scripts/_archive/toolc/toolc_c.py")

    def test_04_supersession_same_topic(self):
        w(self.root, "docs/diagnostics/aa-topic-20260901.md",
          b"# old\n", mtime=OLD)
        w(self.root, "docs/diagnostics/aa-topic-20260902.md",
          b"# new\n", mtime=MID)
        rows = self.by_path(self.run_scan())
        old = rows["docs/diagnostics/aa-topic-20260901.md"]
        new = rows["docs/diagnostics/aa-topic-20260902.md"]
        self.assertEqual(old["topic"], new["topic"])
        self.assertEqual(old["superseded_by"], new["path"])
        self.assertTrue(new["is_representative"])

    def test_05_cache_and_zero_byte_delete(self):
        w(self.root, "scripts/__pycache__/x.pyc", b"pyc")
        w(self.root, "var/debug/empty.bin", b"")
        rows = self.by_path(self.run_scan())
        self.assertEqual(rows["scripts/__pycache__/x.pyc"]["bin"], "DELETE")
        self.assertEqual(rows["scripts/__pycache__/x.pyc"]["why"],
                         "regenerable-cache")
        self.assertEqual(rows["var/debug/empty.bin"]["why"],
                         "zero-byte-file")

    def test_06_recent_file_keep_live(self):
        w(self.root, "var/debug/fresh.log", b"l", mtime=NEW)
        rows = self.by_path(self.run_scan())
        self.assertEqual(rows["var/debug/fresh.log"]["bin"], "KEEP-LIVE")

    def test_07_plan_roundtrip_dry_run(self):
        w(self.root, "scripts/__pycache__/x.pyc", b"pyc")
        w(self.root, "data/agent-handoff/task-a/junk.bin", b"j")
        plan_json = os.path.join(self.ledger, "plan.json")
        aa.main(self.argv("plan", ["--plan-json", plan_json]))
        with open(plan_json, "rb") as f:
            payload = f.read()
        import hashlib
        sha = hashlib.sha256(payload).hexdigest()
        doc = json.loads(payload.decode("utf-8"))
        classes = {r["path"]: r["class"] for r in doc["rows"]}
        self.assertEqual(
            classes["scripts/__pycache__/x.pyc"], "DELETE")
        self.assertEqual(
            classes["data/agent-handoff/task-a/junk.bin"], "QUARANTINE")
        res = crc.apply_plan(plan_json, sha, self.root, self.qroot,
                             self.ledger, dry_run=True)
        self.assertNotIn("error", res)

    def test_08_find_surfaces_hit(self):
        w(self.root, "docs/diagnostics/jev-report-20260928.md",
          b"# Jev scoring\n")
        aa.main(["scan", "--root", self.root,
                 "--catalog", self.catalog,
                 "--tracked-file", self.trackedfile,
                 "--skip-file", self.skipfile])
        buf = io.StringIO()
        with redirect_stdout(buf):
            aa.main(["find", "jev", "--root", self.root,
                     "--catalog", self.catalog])
        self.assertIn("jev-report", buf.getvalue())

    def test_09_dup_card_detection(self):
        os.makedirs(os.path.join(self.root, aa.CARDS_DIR), exist_ok=True)
        w(self.root, aa.CARDS_DIR + "/a-topic-1111.md",
          b"# card\nclaim: same root cause found\n")
        w(self.root, aa.CARDS_DIR + "/b-topic-2222.md",
          b"# card\nclaim: same root cause found\n")
        dups = aa.dup_cards(self.root)
        self.assertEqual(len(dups), 1)

    def test_10_lease_prefix_protected(self):
        Path(self.skipfile).write_text(
            '{"skipPrefixes": ["data/agent-handoff/leased"]}',
            encoding="utf-8")
        w(self.root, "data/agent-handoff/leased/x.bin", b"x")
        rows = self.by_path(self.run_scan())
        self.assertEqual(
            rows["data/agent-handoff/leased/x.bin"]["bin"], "KEEP-LIVE")

    def test_11_tracked_report_only(self):
        w(self.root, "docs/diagnostics/tracked.md", b"t")
        Path(self.trackedfile).write_text(
            "docs/diagnostics/tracked.md\n", encoding="utf-8")
        rows = self.by_path(self.run_scan())
        self.assertEqual(
            rows["docs/diagnostics/tracked.md"]["bin"], "TRACKED")

    def test_12_dup_sha_original_survives(self):
        w(self.root, "data/agent-handoff/task-a/copy.txt",
          b"identical")
        w(self.root, "data/agent-handoff/task-b/orig.txt",
          b"identical")
        rows = self.by_path(self.run_scan())
        dels = [r for r in rows.values()
                if r["why"] == "duplicate-sha-original-exists"]
        self.assertEqual(len(dels), 1)
        self.assertIsNotNone(dels[0]["original"])


if __name__ == "__main__":
    unittest.main()
