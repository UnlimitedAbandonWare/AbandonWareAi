"""classify_h2_ddl_warnings unittest - 텍스트/디렉터리 fixture 만, DB 불필요.

P1-2 확장 검증: already-exists 외 라인이 있으면 has-non-already-exists,
@Table(name="awx_*") 엔티티 겹침 시 overlap=True + advisory 문구.
"""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("classify_h2_ddl_warnings.py")
SPEC = importlib.util.spec_from_file_location(
    "classify_h2_ddl_warnings", SCRIPT) if SCRIPT.exists() else None
MOD = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    sys.modules.setdefault("classify_h2_ddl_warnings", MOD)
    SPEC.loader.exec_module(MOD)

LOG_NOISE = """2026-09-29 Error executing DDL "alter table chat_message add constraint FKabc foreign key" via JDBC [Constraint "FKABC" already exists]
Caused by: org.h2.jdbc.JdbcSQLSyntaxErrorException: Constraint "FKABC" already exists; SQL statement:
2026-09-29 Error executing DDL "create index IDX1" via JDBC [Index "IDX1" already exists]
Caused by: org.h2.jdbc.JdbcSQLSyntaxErrorException: Index "IDX1" already exists; SQL statement:
2026-09-29 GenerationTarget encountered exception accepting command : Error executing DDL
"""
LOG_REAL_ERROR = LOG_NOISE + \
    'Caused by: org.h2.jdbc.JdbcSQLSyntaxErrorException: Column "X" not found; SQL statement:\n'

MIGRATION_SQL = """CREATE TABLE IF NOT EXISTS awx_jobs (task_id VARCHAR(36) PRIMARY KEY,
    INDEX awx_jobs_pending (state));
CREATE TABLE awx_understanding_receipts (effect_key VARCHAR(64) PRIMARY KEY,
    CONSTRAINT awx_understanding_receipt_state CHECK (receipt_state IN ('PERSISTED')),
    INDEX awx_understanding_source (session_id));
"""

ENTITY_OVERLAP = """import javax.persistence.*;
@Entity
@Table(name="awx_jobs")
public class AwxJobsEntity { }
"""
ENTITY_CLEAN = """import javax.persistence.*;
@Entity
@Table(name="users")
public class User { }
"""


class ClassifyTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(MOD)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.mig = self.root / "migration"
        self.mig.mkdir()
        (self.mig / "V1__manual.sql").write_text(MIGRATION_SQL,
                                               encoding="utf-8")
        self.ents = self.root / "java"
        (self.ents / "com" / "x").mkdir(parents=True)

    def write_entity(self, text):
        (self.ents / "com" / "x" / "E.java").write_text(text,
                                                      encoding="utf-8")

    def test_noise_only_verdict(self):
        self.write_entity(ENTITY_CLEAN)
        result = MOD.classify(LOG_NOISE)
        overlap = MOD.overlap_report(result, self.ents, self.mig)
        verdict = MOD.verdict_block(result, overlap)
        self.assertEqual(verdict["classification"], "all-already-exists")
        self.assertEqual(verdict["nonAlreadyExistsCount"], 0)
        self.assertFalse(overlap["overlap"])
        self.assertIn("awx_jobs_pending",
                      overlap["manualObjectsFromMigrations"])
        self.assertIn("awx_understanding_receipt_state",
                      overlap["manualObjectsFromMigrations"])

    def test_real_error_escalates(self):
        self.write_entity(ENTITY_CLEAN)
        result = MOD.classify(LOG_REAL_ERROR)
        overlap = MOD.overlap_report(result, self.ents, self.mig)
        verdict = MOD.verdict_block(result, overlap)
        self.assertEqual(verdict["classification"], "has-non-already-exists")
        self.assertGreater(verdict["nonAlreadyExistsCount"], 0)

    def test_entity_overlap_detected(self):
        self.write_entity(ENTITY_OVERLAP)
        result = MOD.classify(LOG_NOISE)
        overlap = MOD.overlap_report(result, self.ents, self.mig)
        self.assertTrue(overlap["overlap"])
        self.assertEqual(overlap["entityTablesMatchingManual"], ["awx_jobs"])
        self.assertIn("ASK_ONCE", overlap["recommendation"])

    def test_manual_object_in_log(self):
        self.write_entity(ENTITY_CLEAN)
        log = LOG_NOISE + (
            'Caused by: org.h2.jdbc.JdbcSQLSyntaxErrorException: '
            'Index "AWX_JOBS_PENDING" already exists; SQL statement:\n')
        result = MOD.classify(log)
        overlap = MOD.overlap_report(result, self.ents, self.mig)
        self.assertIn("awx_jobs_pending", overlap["manualObjectsSeenInLog"])
        self.assertTrue(overlap["overlap"])

    def test_main_json_out(self):
        self.write_entity(ENTITY_CLEAN)
        log_path = self.root / "boot.log"
        log_path.write_text(LOG_NOISE, encoding="utf-8")
        out_path = self.root / "out" / "ddl-noise.json"
        code = MOD.main([str(log_path), "--entities-root", str(self.ents),
                         "--migrations-dir", str(self.mig),
                         "--json-out", str(out_path)])
        self.assertEqual(code, 0)
        payload = json.loads(out_path.read_text(encoding="utf-8"))
        self.assertEqual(payload["verdict"]["classification"],
                         "all-already-exists")
        self.assertIn("manualOverlap", payload)


if __name__ == "__main__":
    unittest.main()
