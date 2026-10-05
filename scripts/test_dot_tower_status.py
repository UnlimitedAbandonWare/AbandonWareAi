"""Regression tests for scripts/dot_tower_status.py (stdlib unittest, offline)."""
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dot_tower_status as dts  # noqa: E402


class TowerStatusTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / "repo"
        (self.root / "data" / "agent-handoff").mkdir(parents=True)
        (self.root / "configs").mkdir(parents=True)
        self.dl = Path(self.tmp.name) / "Downloads"
        self.docs = Path(self.tmp.name) / "Documents" / "Codex"
        self.dl.mkdir()
        self.docs.mkdir(parents=True)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, base, rel, text="{}"):
        p = base / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
        return p

    def journal(self, task, status, purpose="x"):
        return self.write(
            self.root,
            "data/agent-handoff/codex-autonomy/%s/journal.json" % task,
            json.dumps({"taskId": task, "status": status, "purpose": purpose}))

    def run_cli(self, *extra):
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = dts.main(["--root", str(self.root), "--downloads", str(self.dl),
                             "--documents", str(self.docs), "--json", *extra])
        return code, buf.getvalue()

    def test_brief_meta_tokens_and_fields(self):
        p = self.write(self.dl, "PASTE_CODEX_TEMPERATURE_SELFASK_MODES_20261005.md", "body")
        meta = dts.brief_meta(p)
        self.assertEqual(meta["topic"], "TEMPERATURE_SELFASK_MODES")
        self.assertEqual(meta["tokens"], ["TEMPERATURE", "SELFASK", "MODES"])
        self.assertEqual(meta["date"], "20261005")
        self.assertTrue(meta["sha12"] and len(meta["sha12"]) == 12)
        self.assertIn("+09:00", meta["mtimeKst"])

    def test_brief_meta_digits_inside_topic(self):
        meta = dts.brief_meta(
            self.write(self.dl, "PASTE_CODEX_ASTRA_BACKEND_P0_20261004.md", "x"))
        self.assertEqual(meta["tokens"], ["ASTRA", "BACKEND", "P0"])

    def test_brief_meta_lowercase_rejected(self):
        self.assertIsNone(dts.brief_meta(
            self.write(self.dl, "PASTE_CODEX_lower_snake_20261005.md", "x")))
        self.assertIsNone(dts.brief_meta(self.write(self.dl, "notes.md", "x")))

    def test_status_running_via_journal(self):
        self.journal("temperature-mode-history-1c246035", "in_progress")
        self.write(self.dl, "PASTE_CODEX_TEMPERATURE_SELFASK_MODES_20261005.md", "x")
        code, out = self.run_cli()
        doc = json.loads(out)
        self.assertEqual(code, 0)
        self.assertEqual(doc["briefs"][0]["status"], "RUNNING")
        self.assertIn("temperature-mode-history-1c246035",
                      str(doc["briefs"][0]["matched"]))

    def test_status_closed(self):
        self.journal("graph-hybrid-reuse-1699f1c7", "closed")
        self.write(self.dl, "PASTE_CODEX_EXISTING_GRAPH_HYBRID_REUSE_20261005.md", "x")
        code, out = self.run_cli()
        doc = json.loads(out)
        self.assertEqual(code, 0)
        self.assertEqual(doc["briefs"][0]["status"], "CLOSED")

    def test_status_unknown_and_not_started(self):
        self.journal("interview-memory-retention-c90366f7", "in_progress")
        self.write(self.dl, "PASTE_CODEX_INTERVIEW_10TURN_TEST_AND_FIX_20261005.md", "x")
        self.write(self.dl, "PASTE_CODEX_ZZZ_NOTHERE_20261005.md", "x")
        code, out = self.run_cli()
        doc = json.loads(out)
        by = {b["name"]: b["status"] for b in doc["briefs"]}
        self.assertEqual(by["PASTE_CODEX_INTERVIEW_10TURN_TEST_AND_FIX_20261005.md"],
                         "UNKNOWN")
        self.assertEqual(by["PASTE_CODEX_ZZZ_NOTHERE_20261005.md"], "NOT_STARTED")

    def test_lease_conflict_exit3(self):
        exp = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()
        for topic in ("lease-a", "lease-b"):
            self.write(self.root,
                       "__patch_drop__/source-edit-locks/%s.lock/lease.json" % topic,
                       json.dumps({"expiresAtUtc": exp,
                                   "targetPaths": ["scripts/x.py"]}))
        code, out = self.run_cli()
        self.assertEqual(code, 3)
        self.assertEqual(json.loads(out)["leaseConflicts"][0]["file"], "scripts/x.py")

    def test_empty_downloads_ok(self):
        code, out = self.run_cli()
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out)["briefs"], [])

    def test_korean_names_no_crash(self):
        self.write(self.dl, "PASTE_CODEX_한글_TOPIC_20261005.md", "x")
        self.journal("한글-저널-테스트", "in_progress", purpose="한국어 목적")
        code, out = self.run_cli()
        doc = json.loads(out)
        self.assertEqual(code, 0)
        self.assertEqual(doc["briefs"], [])
        self.assertEqual(doc["journals"][0]["taskId"], "한글-저널-테스트")

    def test_secret_masked(self):
        fake = "sk-" + "live-" + "abcdef1234567890"
        self.journal("secret-task-abc123", "in_progress",
                     purpose="call api_key: " + fake + " now")
        code, out = self.run_cli()
        self.assertNotIn(fake, out)

    def test_json_schema_keys(self):
        code, out = self.run_cli()
        doc = json.loads(out)
        self.assertEqual(doc["schemaVersion"], "awx.dot_tower_status.v1")
        for key in ("briefs", "journals", "leases", "leaseConflicts",
                    "ratchet", "summary"):
            self.assertIn(key, doc)
        self.assertEqual(doc["ratchet"]["status"], "config-missing")

    def test_missing_inputs_exit4(self):
        gone = Path(self.tmp.name) / "nope"
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = dts.main(["--root", str(self.root), "--downloads", str(gone),
                             "--documents", str(gone / "c"), "--json"])
        self.assertEqual(code, 4)


if __name__ == "__main__":
    unittest.main()
