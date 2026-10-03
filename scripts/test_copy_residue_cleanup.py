#!/usr/bin/env python3
"""Unit tests for scripts/copy_residue_cleanup.py using a fake tree."""
import json
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import copy_residue_cleanup as crc

OLD = time.time() - 10 * 24 * 3600          # older than 72h and 7d
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
        self.downloads = self.ext.name
        for d in ("agent-prompts", "data/agent-handoff/task-a",
                  "data/agent-handoff/task-b", "scratch", "toss", "docs",
                  "__patch_drop__/source-edit-locks", "logs"):
            os.makedirs(os.path.join(self.root, d), exist_ok=True)
        w(self.root, "docs/refs.md",
          b"see data/agent-handoff/task-a and agent-prompts/keepme")
        self.skipfile = os.path.join(self.ext.name, "skip.json")
        with open(self.skipfile, "w") as f:
            json.dump({"skipPrefixes": ["scratch/leased"]}, f)
        self.trackedfile = os.path.join(self.ext.name, "tracked.txt")
        Path(self.trackedfile).write_text("", encoding="utf-8")
        self.procfile = os.path.join(self.ext.name, "procs.txt")
        Path(self.procfile).write_text("", encoding="utf-8")
        self.ledger = os.path.join(self.ext.name, "ledger")
        self.qroot = os.path.join(self.ext.name, "quarantine")

    def tearDown(self):
        self.tmp.cleanup()
        self.ext.cleanup()

    def run_plan(self, extra_dirs=None):
        plan_json = os.path.join(self.ledger, "plan.json")
        plan_md = os.path.join(self.ledger, "PLAN.md")
        argv = ["plan", "--root", self.root, "--ledger", self.ledger,
                "--tracked-file", self.trackedfile,
                "--skip-file", self.skipfile,
                "--original-dir", self.downloads,
                "--downloads-dir", self.downloads,
                "--process-list-file", self.procfile,
                "--plan-json", plan_json, "--plan-md", plan_md]
        for d in (extra_dirs or []):
            argv += ["--extra-candidate-dir", d]
        crc.main(argv)
        with open(plan_json, encoding="utf-8") as f:
            doc = json.load(f)
        return doc, plan_json

    def classes(self, doc):
        return {r["path"]: r for r in doc["rows"]}

    def test_01_tracked_file_never_deleted(self):
        w(self.root, "data/agent-handoff/task-a/tracked.bin", b"same")
        Path(self.trackedfile).write_text(
            "data/agent-handoff/task-a/tracked.bin\n", encoding="utf-8")
        doc, _ = self.run_plan()
        row = self.classes(doc)["data/agent-handoff/task-a/tracked.bin"]
        self.assertEqual(row["class"], "TRACKED_CANDIDATE")

    def test_02_recent_file_skipped(self):
        w(self.root, "data/agent-handoff/task-a/new.bin", b"n", mtime=NEW)
        doc, _ = self.run_plan()
        row = self.classes(doc)["data/agent-handoff/task-a/new.bin"]
        self.assertEqual(row["class"], "SKIP")
        self.assertIn("recent", row["reason"])

    def test_03_lease_prefix_skipped(self):
        w(self.root, "scratch/leased/a.bin", b"l")
        doc, _ = self.run_plan()
        row = self.classes(doc)["scratch/leased/a.bin"]
        self.assertEqual(row["class"], "SKIP")
        self.assertEqual(row["reason"], "active-lease-or-forbidden")

    def test_04_dup_of_original_deleted(self):
        w(self.root, "docs/orig.txt", b"identical-bytes")
        w(self.root, "scratch/copy.txt", b"identical-bytes")
        doc, _ = self.run_plan()
        row = self.classes(doc)["scratch/copy.txt"]
        self.assertEqual(row["class"], "DELETE")
        self.assertEqual(row["reason"], "duplicate-sha-original-exists")
        self.assertEqual(row["original"], "docs/orig.txt")

    def test_05_downloads_dup_deleted(self):
        w(self.downloads, "PASTE_x.txt", b"brief-body")
        w(self.root, "agent-prompts/keepme/BRIEF.txt", b"brief-body")
        doc, _ = self.run_plan()
        row = self.classes(doc)["agent-prompts/keepme/BRIEF.txt"]
        # keepme dir is referenced by docs/refs.md -> referenced wins over dup
        w(self.root, "agent-prompts/other/BRIEF.txt", b"brief-body")
        doc, _ = self.run_plan()
        row2 = self.classes(doc)["agent-prompts/other/BRIEF.txt"]
        self.assertEqual(row2["class"], "DELETE")
        self.assertEqual(row2["reason"], "duplicate-of-downloads")

    def test_06_apply_skips_sha_drift(self):
        w(self.root, "docs/orig.txt", b"identical-bytes")
        p = w(self.root, "scratch/copy.txt", b"identical-bytes")
        doc, plan_json = self.run_plan()
        with open(p, "wb") as f:
            f.write(b"changed")
        res = crc.apply_plan(plan_json, self._sha(plan_json), self.root,
                             self.qroot, self.ledger, only="DELETE")
        self.assertEqual(res["results"].get("skipped-sha-drift"), 1)
        self.assertTrue(os.path.isfile(p))

    def test_07_quarantine_restore_roundtrip(self):
        w(self.root, "scratch/odd.bin", b"odd-unique-bytes")
        doc, plan_json = self.run_plan()
        res = crc.apply_plan(plan_json, self._sha(plan_json), self.root,
                             self.qroot, self.ledger, only="QUARANTINE")
        self.assertEqual(res["results"].get("quarantined"), 1)
        self.assertFalse(os.path.isfile(
            os.path.join(self.root, "scratch/odd.bin")))
        manifest = os.path.join(self.qroot, "quarantine-manifest.json")
        out = crc.restore(manifest)
        self.assertEqual(out["restored"], 1)
        self.assertTrue(os.path.isfile(
            os.path.join(self.root, "scratch/odd.bin")))

    def test_08_referenced_kept(self):
        w(self.root, "data/agent-handoff/task-a/data.bin", b"ref")
        doc, _ = self.run_plan()
        row = self.classes(doc)["data/agent-handoff/task-a/data.bin"]
        self.assertEqual(row["class"], "KEEP")

    def test_09_cache_and_empty_dir_deleted(self):
        w(self.root, "scratch/pkg/__pycache__/m.pyc", b"pyc")
        os.makedirs(os.path.join(self.root, "autoevolve_debug"),
                    exist_ok=True)
        os.utime(os.path.join(self.root, "autoevolve_debug"), (OLD, OLD))
        doc, _ = self.run_plan()
        rows = self.classes(doc)
        self.assertEqual(rows["scratch/pkg/__pycache__/m.pyc"]["reason"],
                         "regenerable-cache")
        self.assertEqual(rows["autoevolve_debug"]["class"], "DELETE")

    def test_10_whisper_model_no_original_quarantined(self):
        p = w(self.root, "data/agent-handoff/task-b/runtime/models/turbo/model.bin", b"")
        with open(p, "wb") as f:
            f.truncate(101 * 1024 * 1024)
        os.utime(p, (OLD, OLD))
        doc, _ = self.run_plan()
        row = self.classes(doc)[
            "data/agent-handoff/task-b/runtime/models/turbo/model.bin"]
        self.assertEqual(row["class"], "QUARANTINE")
        self.assertEqual(row["reason"], "model-copy-no-in-use-original")

    def test_11_extra_candidate_dir_scoped(self):
        w(self.root, "diag/old-report.md", b"old")
        w(self.root, "other/outside.bin", b"o")
        doc, _ = self.run_plan(extra_dirs=["diag"])
        rows = self.classes(doc)
        self.assertIn("diag/old-report.md", rows)
        self.assertEqual(rows["diag/old-report.md"]["class"], "QUARANTINE")
        self.assertNotIn("other/outside.bin", rows)

    def test_12_extra_dir_forbidden_and_tracked_blocked(self):
        w(self.root, "main/java/Foo.java", b"src")
        w(self.root, "diag/tracked.md", b"t")
        Path(self.trackedfile).write_text("diag/tracked.md\n",
                                          encoding="utf-8")
        doc, _ = self.run_plan(extra_dirs=["main", "diag"])
        rows = self.classes(doc)
        self.assertNotIn("main/java/Foo.java", rows)
        self.assertEqual(rows["diag/tracked.md"]["class"],
                         "TRACKED_CANDIDATE")

    def test_13_default_scope_unchanged_without_extra(self):
        w(self.root, "diag/ignored.md", b"x")
        doc, _ = self.run_plan()
        self.assertNotIn("diag/ignored.md", self.classes(doc))

    def _sha(self, plan_json):
        import hashlib
        with open(plan_json, "rb") as f:
            return hashlib.sha256(f.read()).hexdigest()


if __name__ == "__main__":
    unittest.main()
