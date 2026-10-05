"""Unit tests for scripts/failure_archive.py — isolated temp-root registry."""
from __future__ import annotations

import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import failure_archive as fa


def run(argv, root):
    argv = ["--root", str(root)] + list(argv)
    try:
        return fa.main(argv)
    except SystemExit as err:
        return err.code if isinstance(err.code, int) else 1


def record_args(**over):
    args = {
        "agent": "DEVIN", "category": "TASK_FAILURE", "domain": "ops-tooling",
        "title": "directive goal missed on verify",
        "evidence": "data/agent-handoff/example/report.md exit=1",
        "repro": "DETERMINISTIC", "severity": "HIGH",
    }
    args.update(over)
    argv = ["record"]
    for key, value in args.items():
        argv += ["--" + key.replace("_", "-"), value]
    return argv


class FailureArchiveTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def registry_rows(self):
        path = self.root / fa.REGISTRY
        if not path.exists():
            return []
        return [json.loads(l) for l in
                path.read_text(encoding="utf-8").splitlines() if l.strip()]

    def test_record_creates_registry_and_locked_status(self):
        self.assertEqual(run(record_args(), self.root), 0)
        rows = self.registry_rows()
        self.assertEqual(len(rows), 1)
        row = rows[0]
        self.assertEqual(row["schemaVersion"], fa.SCHEMA)
        self.assertRegex(row["id"], r"^FA-\d{8}-[0-9A-F]{4}$")
        self.assertEqual(row["status"], "LOCKED_ACTIVE")
        self.assertEqual(row["slot"], "DEVIN")
        self.assertEqual(row["set"], "ops-tooling")
        self.assertEqual(row["mainStat"], "TASK_FAILURE")
        self.assertEqual(row["subStats"]["severity"], "HIGH")
        per_file = self.root / fa.BASE / "records" / (row["id"] + ".json")
        self.assertTrue(per_file.exists())

    def test_task_failure_vs_runtime_error_distinct(self):
        self.assertEqual(run(record_args(title="goal missed"), self.root), 0)
        self.assertEqual(run(record_args(
            category="RUNTIME_ERROR", title="gradle aborted exit 1",
            domain="rag-core"), self.root), 0)
        rows = self.registry_rows()
        cats = {r["mainStat"] for r in rows}
        self.assertEqual(cats, {"TASK_FAILURE", "RUNTIME_ERROR"})
        labels = {r["mainStat"]: r["mainStatLabel"] for r in rows}
        self.assertEqual(labels["TASK_FAILURE"], "임무 실패")
        self.assertEqual(labels["RUNTIME_ERROR"], "시스템 에러")

    def test_list_filters(self):
        run(record_args(agent="CODEX", domain="rag-core"), self.root)
        run(record_args(agent="DEVIN", domain="display-meta",
                        category="LOCK_COLLISION"), self.root)
        self.assertEqual(run(["list", "--status", "ALL", "--json"], self.root), 0)
        records = fa.read_records(fa.root_path(str(self.root)))
        self.assertEqual(len(records), 2)
        out = fa.cmd_list(fa.root_path(str(self.root)),
                          _ns(agent="CODEX", domain=None, category=None,
                              status="ALL", json=True))
        self.assertEqual(out["count"], 1)
        self.assertEqual(out["records"][0]["slot"], "CODEX")
        locked = fa.cmd_list(fa.root_path(str(self.root)),
                             _ns(agent=None, domain=None, category=None,
                                 status="LOCKED_ACTIVE", json=True))
        self.assertEqual(locked["count"], 2)

    def test_resolve_then_terminal_block(self):
        run(record_args(), self.root)
        rid = self.registry_rows()[0]["id"]
        self.assertEqual(
            run(["resolve", rid, "--by", "devin-failure-archive-8b145425",
                 "--note", "fixed by patch"], self.root), 0)
        row = self.registry_rows()[0]
        self.assertEqual(row["status"], "ENHANCED_RESOLVED")
        self.assertEqual(row["statusHistory"][-1]["by"],
                         "devin-failure-archive-8b145425")
        self.assertEqual(
            run(["supersede", rid, "--reason", "stale"], self.root), 2)

    def test_supersede(self):
        run(record_args(), self.root)
        rid = self.registry_rows()[0]["id"]
        self.assertEqual(
            run(["supersede", rid, "--reason", "superseded by newer report"],
                self.root), 0)
        row = self.registry_rows()[0]
        self.assertEqual(row["status"], "FODDER_SUPERSEDED")

    def test_show(self):
        run(record_args(), self.root)
        rid = self.registry_rows()[0]["id"]
        self.assertEqual(run(["show", rid], self.root), 0)
        out = fa.cmd_show(fa.root_path(str(self.root)),
                          _ns(id=rid, json=True))
        self.assertEqual(out["record"]["id"], rid)
        self.assertEqual(run(["show", "FA-00000000-AAAA"], self.root), 2)

    def test_secret_like_value_refused(self):
        fake_token = "sk-" + "a" * 25
        self.assertEqual(run(record_args(
            evidence="token=" + fake_token), self.root), 2)
        self.assertEqual(self.registry_rows(), [])

    def test_required_fields(self):
        self.assertNotEqual(run(["record", "--agent", "DEVIN"], self.root), 0)


def _ns(**kw):
    class N:
        pass
    n = N()
    for key, value in kw.items():
        setattr(n, key, value)
    return n


if __name__ == "__main__":
    unittest.main(verbosity=2)
