"""db_migration_ledger unittest — 파싱/판정/주장 대조 (fixture, live 없음).

Contract DEMO1-DEVIN-F01B-POST-TOOLS-20260929 항목 1.
"""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("db_migration_ledger.py")
SPEC = importlib.util.spec_from_file_location("db_migration_ledger", SCRIPT) \
    if SCRIPT.exists() else None
MOD = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    sys.modules.setdefault("db_migration_ledger", MOD)
    SPEC.loader.exec_module(MOD)

SQL_A = """CREATE TABLE IF NOT EXISTS awx_jobs (
    task_id VARCHAR(36) PRIMARY KEY,
    state VARCHAR(32) NOT NULL,
    INDEX awx_jobs_pending (state)
);
CREATE TABLE IF NOT EXISTS awx_job_results (
    result_id VARCHAR(36) PRIMARY KEY
);
"""
SQL_B = """ALTER TABLE awx_jobs ADD COLUMN admission_key VARCHAR(64);
ALTER TABLE awx_jobs ADD COLUMN request_fingerprint VARCHAR(64);
CREATE UNIQUE INDEX awx_jobs_admission_key ON awx_jobs(admission_key);
"""


def make_root(tmp: Path) -> Path:
    mig = tmp / "main" / "resources" / "db" / "migration"
    mig.mkdir(parents=True)
    (mig / "V001__a.sql").write_text(SQL_A, encoding="utf-8")
    (mig / "V002__b.sql").write_text(SQL_B, encoding="utf-8")
    return tmp


def fixture_live(tmp: Path, live: dict) -> Path:
    path = tmp / "fx.json"
    path.write_text(json.dumps({"live": live}), encoding="utf-8")
    return path


LIVE_ALL = {
    "reachable": True,
    "tables": ["awx_jobs", "awx_job_results"],
    "columns": [["awx_jobs", "admission_key"],
                ["awx_jobs", "request_fingerprint"]],
    "indexes": [["awx_jobs", "awx_jobs_pending"],
                ["awx_jobs", "awx_jobs_admission_key"]],
    "history": [],
}

LIVE_PARTIAL = {
    "reachable": True,
    "tables": ["awx_jobs"],          # awx_job_results 없음
    "columns": [],                   # idempotency 미적용
    "indexes": [],
    "history": [],
}


class MigrationLedgerTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(MOD)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = make_root(Path(self.tmp.name))

    def run_main(self, *argv):
        out = self.root / "db-ledger" / "LATEST.json"
        code = MOD.main(["--root", str(self.root), "--json-out", str(out),
                         *argv])
        payload = json.loads(out.read_text(encoding="utf-8")) \
            if out.is_file() else None
        return code, payload

    def test_parse_sql_objects(self):
        obj = MOD.parse_sql_objects(SQL_B)
        self.assertEqual(2, len(obj["columns"]))
        self.assertEqual("awx_jobs_admission_key", obj["indexes"][0]["name"])

    def test_all_applied(self):
        fx = fixture_live(self.root, LIVE_ALL)
        code, payload = self.run_main("--fixture", str(fx))
        self.assertEqual(0, code)
        self.assertEqual("executed", payload["verdict"])
        by_name = {e["file"]: e for e in payload["ledger"]}
        self.assertEqual("applied", by_name["V001__a.sql"]["status"])
        self.assertEqual("applied", by_name["V002__b.sql"]["status"])
        self.assertEqual("awx.db-migration-ledger.v1",
                         payload["schemaVersion"])

    def test_partial_and_missing(self):
        fx = fixture_live(self.root, LIVE_PARTIAL)
        code, payload = self.run_main("--fixture", str(fx))
        self.assertEqual(0, code)
        by_name = {e["file"]: e for e in payload["ledger"]}
        self.assertEqual("partial", by_name["V001__a.sql"]["status"])
        self.assertEqual("missing", by_name["V002__b.sql"]["status"])
        self.assertIn("table:awx_job_results",
                      by_name["V001__a.sql"]["missingParts"])

    def test_unreachable_exit_3(self):
        fx = fixture_live(self.root, {"reachable": False,
                                      "error": "locked"})
        code, payload = self.run_main("--fixture", str(fx))
        self.assertEqual(3, code)
        self.assertEqual("unreachable", payload["verdict"])
        self.assertTrue(all(e["status"] == "unknown"
                            for e in payload["ledger"]))

    def test_handoff_claim_crosscheck(self):
        ho = self.root / "handoff"
        ho.mkdir()
        (ho / "migration-durable-applied.json").write_text(json.dumps({
            "file": "V001__a.sql", "applied": True,
            "approvedMigrationSha256": "0" * 64, "statementCount": 2}),
            encoding="utf-8")
        fx = fixture_live(self.root, LIVE_ALL)
        code, payload = self.run_main("--fixture", str(fx),
                                      "--handoff-dir", str(ho))
        self.assertEqual(0, code)
        a = {e["file"]: e for e in payload["ledger"]}["V001__a.sql"]
        self.assertEqual(True, a["handoffClaim"]["applied"])
        self.assertTrue(a["sha256DriftFromClaim"])  # 0*64 != 실제 sha256

    def test_no_migrations_dir_exit_2(self):
        code = MOD.main(["--root", str(self.root / "elsewhere"),
                         "--migrations-dir", "nope"])
        self.assertEqual(2, code)


if __name__ == "__main__":
    unittest.main()
